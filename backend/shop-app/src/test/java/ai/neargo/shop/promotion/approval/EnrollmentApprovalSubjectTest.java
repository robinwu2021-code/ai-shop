package ai.neargo.shop.promotion.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.service.PlatformActivityService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalPort;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** T18 · 平台活动报名接入审批（方案-第二批审批工作流 T18）。 */
@DisplayName("报名审批：大额加复核；按内容修订号通过；超预算生效失败；未通过回到驳回")
class EnrollmentApprovalSubjectTest {

    final JdbcTemplate db = mock(JdbcTemplate.class);
    final PlatformActivityService activities = mock(PlatformActivityService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<ApprovalPort> provider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") final ObjectProvider<ai.neargo.shop.spi.user.MerchantQueryPort> merchantProvider = mock(ObjectProvider.class);
    final ai.neargo.shop.spi.user.MerchantQueryPort merchants = mock(ai.neargo.shop.spi.user.MerchantQueryPort.class);
    {
        when(merchantProvider.getIfAvailable()).thenReturn(merchants);
        when(merchants.find("E1")).thenReturn(java.util.Optional.of(new ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief(
                "E1", "甲商家", true, true, null, 0, 0, true, 0, false)));
    }
    final EnrollmentApprovalSubject subject = new EnrollmentApprovalSubject(db, activities, provider, merchantProvider, 10_000_000);

    @Test
    @DisplayName("商家名经商户端口取，不跨库连主体表")
    void entityNameComesFromTheMerchantPort() {
        enrollment("SUBMITTED", 9_000_000, 0);
        assertThat(subject.snapshot("EN-1").title() + subject.snapshot("EN-1").summary()).contains("甲商家");
    }

    Map<String, Object> enrollment(String status, long platformMax, long version) {
        Map<String, Object> e = new HashMap<>();
        e.put("enrollment_no", "EN-1");
        e.put("activity_no", "PA-1");
        e.put("entity_no", "E1");
        e.put("quota", 100);
        e.put("platform_max_minor", platformMax);
        e.put("merchant_max_minor", 50_000L);
        e.put("status", status);
        e.put("version", version);
        e.put("activity_name", "双十二");
        e.put("enroll_reserved_minor", 0L);
        when(db.queryForList(anyString(), eq("EN-1"))).thenReturn(List.of(e));
        when(db.queryForList(anyString(), eq(String.class), eq("EN-1"))).thenReturn(List.of("G1", "G2"));
        return e;
    }

    @Test
    void recheckAboveThresholdAndApplyAsApprovalService() {
        assertThat(subject.level()).isEqualTo(ApprovalSubject.Level.IMPORTANT);
        var e = enrollment("SUBMITTED", 12_000_000, 0);
        assertThat(subject.snapshot("EN-1").fields()).containsEntry(ApprovalSubject.RECHECK_FIELD, true);
        enrollment("SUBMITTED", 9_000_000, 0);
        assertThat(subject.snapshot("EN-1").fields()).containsEntry(ApprovalSubject.RECHECK_FIELD, false);

        long rev = EnrollmentApprovalSubject.revision(enrollment("SUBMITTED", 9_000_000, 0), List.of("G1", "G2"));
        subject.apply("EN-1", rev);
        verify(activities).review("EN-1", true, null, "OPSKIT");
        assertThat(e).isNotNull();
    }

    @Test
    void reEnrollChangesRevisionAndOverBudgetFailsApply() {
        long first = EnrollmentApprovalSubject.revision(enrollment("SUBMITTED", 9_000_000, 0), List.of("G1", "G2"));
        long again = EnrollmentApprovalSubject.revision(enrollment("SUBMITTED", 9_000_000, 1), List.of("G1", "G2"));
        assertThat(again).isNotEqualTo(first); // 内容相同的重报也换修订号
        assertThatThrownBy(() -> subject.apply("EN-1", first))
                .isInstanceOfSatisfying(ApprovalApplyException.class, ex -> assertThat(ex.code()).isEqualTo(ApprovalApplyException.STALE));

        when(activities.review("EN-1", true, null, "OPSKIT")).thenThrow(BizException.of(ErrorCode.ENROLLMENT_OVER_BUDGET));
        assertThatThrownBy(() -> subject.apply("EN-1", again))
                .isInstanceOfSatisfying(ApprovalApplyException.class, ex -> assertThat(ex.code()).isEqualTo("ENROLLMENT_OVER_BUDGET"));
    }

    @Test
    void notApprovedRejectsOnlyWhileStillSubmitted() {
        long rev = EnrollmentApprovalSubject.revision(enrollment("SUBMITTED", 1, 0), List.of("G1", "G2"));
        subject.onClosed("EN-1", rev, ApprovalSubject.Outcome.REJECTED, "商品不符合活动要求");
        verify(activities).review("EN-1", false, "商品不符合活动要求", "OPSKIT");

        enrollment("WITHDRAWN", 1, 0);
        subject.onClosed("EN-1", rev, ApprovalSubject.Outcome.CANCELLED, null);
        verify(activities, never()).review(eq("EN-1"), eq(false), eq("审批已作废"), any());
    }
}
