package ai.neargo.shop.merchant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.approval.PayoutAccountApprovalSubject;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.paymerchant.entity.MchPayoutAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.paymerchant.impl.PayoutAccountServiceImpl;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalPort;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** T17 · 供应商出款账户接入审批（方案-第二批审批工作流 T17）。 */
@DisplayName("出款账户审批：提交即送审；运营端不能直接审；审批按修订号生效、未通过一律驳回")
class PayoutAccountApprovalTest {

    static final String NAME = "深圳市虹选科技有限公司";

    ai.neargo.shop.merchant.paymerchant.mapper.PayMerchantMappers.PayoutAccountMapper accounts = mock(ai.neargo.shop.merchant.paymerchant.mapper.PayMerchantMappers.PayoutAccountMapper.class);
    MerchantMappers.MchEntityMapper entities = mock(MerchantMappers.MchEntityMapper.class);
    ApprovalPort port = mock(ApprovalPort.class);
    PayoutAccountServiceImpl service = new PayoutAccountServiceImpl(accounts, entities,
            new PayoutAccountCipher("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<ApprovalPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        when(port.enabled(PayoutAccountService.APPROVAL_TYPE)).thenReturn(true);
        service.setApprovals(provider);
        MchEntity e = new MchEntity();
        e.setEntityNo("E-001");
        e.setName(NAME);
        when(entities.selectOne(any())).thenReturn(e);
        when(accounts.selectCount(any())).thenReturn(0L);
    }

    MchPayoutAccount pending(long version) {
        MchPayoutAccount a = new MchPayoutAccount();
        a.setAccountNo("PA-1");
        a.setEntityNo("E-001");
        a.setStatus(MchPayoutAccount.PENDING);
        a.setVersion(version);
        return a;
    }

    @Test
    void submitSendsForApprovalAndDirectAuditIsRefused() {
        when(accounts.selectOne(any())).thenReturn(pending(0));
        var vo = service.submit("E-001", new PayoutAccountService.SubmitCommand(MchPayoutAccount.CORPORATE, NAME, "6222021234567890123", "浦发银行", "深圳分行"));
        verify(port).submit(eq(PayoutAccountService.APPROVAL_TYPE), eq(vo.accountNo()), eq(0L), any());

        assertThatThrownBy(() -> service.audit("PA-1", true, null))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.APPROVAL_IN_OPSKIT));
    }

    @Test
    void auditApprovedOnlyWhenStillPendingAtSameRevision() {
        var a = pending(3);
        when(accounts.selectOne(any())).thenReturn(a);
        when(accounts.selectList(any())).thenReturn(List.of());
        assertThat(service.auditApproved("PA-1", 2, true, null, "OPSKIT")).isFalse();
        assertThat(a.getStatus()).isEqualTo(MchPayoutAccount.PENDING);

        assertThat(service.auditApproved("PA-1", 3, true, null, "OPSKIT")).isTrue();
        assertThat(a.getStatus()).isEqualTo(MchPayoutAccount.ACTIVE);
        assertThat(a.getAuditedBy()).isEqualTo("OPSKIT");

        assertThat(service.auditApproved("PA-1", 3, true, null, "OPSKIT")).isFalse(); // 已生效，迟到回调不动
    }

    @Test
    void subjectReplaysStaleAndClosesAsRejected() {
        var jdbc = mock(JdbcTemplate.class);
        var svc = mock(PayoutAccountService.class);
        var subject = new PayoutAccountApprovalSubject(jdbc, svc, mock(AuditLogPort.class));
        assertThat(subject.level()).isEqualTo(ApprovalSubject.Level.IMPORTANT);

        when(jdbc.queryForList(anyString(), eq("PA-1"))).thenReturn(List.of(Map.of("entity_no", "E-001", "account_masked", "****0123", "status", "PENDING")));
        when(svc.auditApproved("PA-1", 5, true, "审批通过", "OPSKIT")).thenReturn(false);
        assertThatThrownBy(() -> subject.apply("PA-1", 5))
                .isInstanceOfSatisfying(ApprovalApplyException.class, e -> assertThat(e.code()).isEqualTo(ApprovalApplyException.STALE));

        subject.onClosed("PA-1", 5, ApprovalSubject.Outcome.WITHDRAWN, null);
        verify(svc).auditApproved("PA-1", 5, false, "审批已撤回", "OPSKIT");

        when(jdbc.queryForList(anyString(), eq("PA-1"))).thenReturn(List.of(Map.of("entity_no", "E-001", "account_masked", "****0123", "status", "ACTIVE")));
        subject.apply("PA-1", 5); // 重投
        verify(svc, never()).auditApproved(anyString(), anyLong(), eq(true), eq("x"), anyString());
    }
}
