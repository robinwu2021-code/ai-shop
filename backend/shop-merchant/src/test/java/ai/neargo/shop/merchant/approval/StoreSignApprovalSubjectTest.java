package ai.neargo.shop.merchant.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** T24 · 店招 / 公告人工审核接入审批（方案-第二批审批工作流 T24）。 */
@DisplayName("店铺公告审批：待审且修订号未变才通过 / 驳回；重投与迟到回调不动")
class StoreSignApprovalSubjectTest {

    final JdbcTemplate db = mock(JdbcTemplate.class);
    final MerchantGovernService govern = mock(MerchantGovernService.class);
    final StoreSignApprovalSubject subject = new StoreSignApprovalSubject(db, govern, mock(AuditLogPort.class));

    void audit(String status, long version) {
        Map<String, Object> row = new HashMap<>();
        row.put("entity_no", "E1");
        row.put("store_no", "S1");
        row.put("content", "今日到货");
        row.put("hits", "[\"到货\"]");
        row.put("status", status);
        row.put("version", version);
        when(db.queryForList(anyString(), eq("AU-1"))).thenReturn(List.of(row));
        when(db.queryForList(anyString(), eq((Object) "S1"))).thenReturn(List.of(Map.of("name", "南门店", "announcement", "旧公告")));
    }

    @Test
    void passesWhenPendingAndSnapshotShowsBeforeAfter() {
        audit("PENDING", 2);
        assertThat(subject.level()).isEqualTo(ApprovalSubject.Level.GENERAL);
        assertThat(subject.snapshot("AU-1").facts()).anySatisfy(f -> {
            assertThat(f.label()).isEqualTo("店铺公告");
            assertThat(f.before()).isEqualTo("旧公告");
            assertThat(f.value()).isEqualTo("今日到货");
        });
        subject.apply("AU-1", 2);
        verify(govern).decideStoreAudit("AU-1", true, null, "OPSKIT");
    }

    @Test
    void staleReplayAndRejection() {
        audit("PENDING", 3);
        assertThatThrownBy(() -> subject.apply("AU-1", 2))
                .isInstanceOfSatisfying(ApprovalApplyException.class, e -> assertThat(e.code()).isEqualTo(ApprovalApplyException.STALE));
        subject.onClosed("AU-1", 3, ApprovalSubject.Outcome.REJECTED, "含违规用语");
        verify(govern).decideStoreAudit("AU-1", false, "含违规用语", "OPSKIT");

        audit("PASSED", 4);
        subject.apply("AU-1", 3); // 重投
        subject.onClosed("AU-1", 3, ApprovalSubject.Outcome.WITHDRAWN, null); // 迟到
        verify(govern, never()).decideStoreAudit(eq("AU-1"), eq(true), any(), anyString());
    }
}
