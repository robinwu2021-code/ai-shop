package ai.neargo.shop.pay.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ai.neargo.shop.pay.entity.*;
import ai.neargo.shop.pay.mapper.SettleMappers.*;
import ai.neargo.shop.pay.service.recon.ReconAxis;
import ai.neargo.shop.spi.pay.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;

class RefundReconIsolationTest {
    PaymentMapper payments=mock(PaymentMapper.class);
    ReconDiffMapper diffs=mock(ReconDiffMapper.class);
    PayQueryPort query=mock(PayQueryPort.class);
    RefundCapacityLedger capacity=mock(RefundCapacityLedger.class);
    @SuppressWarnings("unchecked") ObjectProvider<ReconAxis> axes=mock(ObjectProvider.class);
    ReconServiceImpl service=new ReconServiceImpl(payments,diffs,query,axes,20,capacity);
    StlPayment row(String no) {
        var p=new StlPayment();p.setPaymentNo(no);p.setOrderNo("O:"+no);p.setDirection("REFUND");
        p.setPayChannel("TEST");p.setOutTradeNo(no);p.setAmountMinor(100L);p.setCurrency("CNY");p.setStatus("PENDING");return p;
    }
    RefundQueryResult outcome(String no,RefundQueryResult.Status status) {
        return new RefundQueryResult(status,status.name(),no,100,"BANK:"+no,"CNY",1);
    }
    @Test void abnormalRefundProducesAnOperatorIssue() {
        var p=row("A");when(payments.selectList(any())).thenReturn(List.of(p));
        when(payments.selectOne(any())).thenReturn(p);
        when(query.queryRefundOutcome("TEST","A")).thenReturn(outcome("A",RefundQueryResult.Status.MANUAL_REQUIRED));
        service.checkStalePayments(System.currentTimeMillis());
        verify(diffs).insert(any(StlReconDiff.class));
    }
    @Test void oneInvalidReceiptDoesNotBlockTheNextRefund() {
        var a=row("A");var b=row("B");when(payments.selectList(any())).thenReturn(List.of(a,b));
        when(query.queryRefundOutcome("TEST","A")).thenReturn(outcome("A",RefundQueryResult.Status.TERMINAL_FAILED));
        when(query.queryRefundOutcome("TEST","B")).thenReturn(outcome("B",RefundQueryResult.Status.SUCCEEDED));
        doThrow(new IllegalArgumentException("REFUND_TERMINAL_RECEIPT_MISMATCH"))
                .when(capacity).observe(eq("A"),any());
        var findings=service.checkStalePayments(System.currentTimeMillis());
        assertThat(findings).hasSize(2);
        assertThat(findings.getFirst().queryFailed()).isTrue();
        assertThat(findings.getLast().paidOnChannel()).isTrue();
    }
    @Test void archiveFailureDoesNotBlockOtherReceipts() {
        var a=row("A");var b=row("B");when(payments.selectList(any())).thenReturn(List.of(a,b));
        when(payments.selectOne(any())).thenReturn(a);
        when(query.queryRefundOutcome("TEST","A")).thenReturn(outcome("A",RefundQueryResult.Status.MANUAL_REQUIRED));
        when(query.queryRefundOutcome("TEST","B")).thenReturn(outcome("B",RefundQueryResult.Status.SUCCEEDED));
        when(diffs.insert(any(StlReconDiff.class))).thenThrow(new IllegalStateException("storage unavailable"));
        var findings=service.checkStalePayments(System.currentTimeMillis());
        assertThat(findings).hasSize(2);
        assertThat(findings.getFirst().queryFailed()).isTrue();
        assertThat(findings.getFirst().errorCode()).isEqualTo("REFUND_RECON_ARCHIVE_FAILED");
        assertThat(findings.getLast().paidOnChannel()).isTrue();
    }
    @Test void lateSuccessIssueDoesNotDependOnAnotherChannelResponse() {
        var p=row("A");p.setStatus("FAILED");p.setRefundQueryState("LATE_SUCCESS");
        when(payments.selectList(any())).thenReturn(List.of(p));when(payments.selectOne(any())).thenReturn(p);
        var finding=service.checkStalePayments(System.currentTimeMillis()).getFirst();
        assertThat(finding.requiresManual()).isTrue();
        verify(diffs).insert(any(StlReconDiff.class));verifyNoInteractions(query);
    }
    @Test void transportFailureDoesNotBlockTheLastRefund() {
        var a=row("A");var b=row("B");when(payments.selectList(any())).thenReturn(List.of(a,b));
        when(query.queryRefundOutcome("TEST","A")).thenThrow(new IllegalStateException("timeout"));
        when(query.queryRefundOutcome("TEST","B")).thenReturn(outcome("B",RefundQueryResult.Status.SUCCEEDED));
        var findings=service.checkStalePayments(System.currentTimeMillis());
        assertThat(findings.getFirst().queryFailed()).isTrue();
        assertThat(findings.getLast().paidOnChannel()).isTrue();
    }
}
