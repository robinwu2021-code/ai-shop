package ai.neargo.shop.beauty.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.ManualReceiptSummaryVO;
import ai.neargo.shop.beauty.dto.PreviewWorkOrderSettlementReq;
import ai.neargo.shop.beauty.dto.ReceiptSummaryVO;
import ai.neargo.shop.beauty.dto.SourceBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkOrderSettlementServiceImplTest {
    final WorkOrderBillService bills = mock(WorkOrderBillService.class);
    final WorkOrderSettlementServiceImpl service = new WorkOrderSettlementServiceImpl(bills, JsonMapper.builder().build());
    static final Instant AT = Instant.parse("2026-11-11T00:00:00Z");
    <T> T actor(Supplier<T> action) {
        return DelegatedIdentityContext.call(new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A"), action);
    }
    SourceBillVO manual(long amount) {
        return new SourceBillVO("OP", "ORDER", "SUB", "CNY", amount, null, null, null, null,
                "WHOLE_SOURCE", List.of(), List.of(), null, true, List.of(), "MANUAL_OFFLINE",
                "NOT_APPLICABLE_MANUAL", new ManualReceiptSummaryVO("D1", "CASH", amount, "CNY", AT, AT,
                "MANUAL_DECLARATION", "NOT_APPLICABLE_MANUAL"), amount);
    }
    SourceBillVO funded(long refund) {
        return new SourceBillVO("FUNDED", "O2", "S2", "TWD", 200L, 200L, refund, 200L-refund, refund,
                "WHOLE_SOURCE", List.of(new ReceiptSummaryVO("P", "P1", "T1", "R1", "CARD", "SUCCESS", 200, "TWD")),
                List.of(), null, true, List.of());
    }
    WorkOrderBillVO bill(List<SourceBillVO> sources, Instant at, String local, boolean complete) {
        return new WorkOrderBillVO("WO", 2, local, at, sources, List.of(), complete,
                complete ? List.of() : List.of("SOURCE_UNAVAILABLE"));
    }
    void given(List<SourceBillVO> sources) {
        when(bills.getBill("E", "S", "A", "WO")).thenReturn(bill(sources, AT, "local", true));
    }
    @Test void originalFullManualDeclarationIsNeverConvertedToPaymentOrChargedAgain() {
        given(List.of(manual(100)));
        var view = actor(() -> service.get("E", "S", "A", "WO"));
        assertThat(view.complete()).isTrue();
        assertThat(view.canSettle()).isFalse();
        assertThat(view.methods()).isEmpty();
        assertThat(view.sources().getFirst().state()).isEqualTo("MANUAL_DECLARED");
        assertThat(view.sources().getFirst().canCollect()).isFalse();
        assertThat(view.bill().sources().getFirst().receivedMinor()).isNull();
        assertThat(view.bill().sources().getFirst().manualDeclaredMinor()).isEqualTo(100);
        verify(bills).getBill("E", "S", "A", "WO");
    }
    @Test void originalPrepaymentAndRefundGapNeverAuthorizeNewCollection() {
        given(List.of(funded(0)));
        assertThat(actor(() -> service.get("E", "S", "A", "WO")).sources().getFirst().state()).isEqualTo("PREPAID");
        given(List.of(funded(50)));
        var view = actor(() -> service.get("E", "S", "A", "WO"));
        assertThat(view.sources().getFirst().state()).isEqualTo("REFUND_RECOLLECTION_CLOSED");
        assertThat(view.sources().getFirst().canCollect()).isFalse();
        assertThat(view.bill().sources().getFirst().outstandingMinor()).isEqualTo(50);
    }
    @Test void mixedCurrenciesAndManualFactsRemainSeparateAndNeverAddAnArbitraryDeposit() {
        given(List.of(manual(100), funded(0)));
        var view = actor(() -> service.get("E", "S", "A", "WO"));
        assertThat(view.bill().sources()).extracting(SourceBillVO::currency).containsExactly("CNY", "TWD");
        assertThat(view.blockingReasons()).contains("DEPOSIT_ALLOCATION_NOT_AUTHORIZED", "ASSET_COMBINATION_NOT_AUTHORIZED");
        assertThat(view.canSettle()).isFalse();
    }
    @Test void fingerprintIgnoresReadTimeButChangesForMoneyLocalBindingAndActorNamespace() {
        given(List.of(manual(100)));
        var first = actor(() -> service.get("E", "S", "A", "WO"));
        when(bills.getBill("E", "S", "A", "WO")).thenReturn(bill(List.of(manual(100)), AT.plusSeconds(1), "local", true));
        assertThat(actor(() -> service.get("E", "S", "A", "WO")).fingerprint()).isEqualTo(first.fingerprint());
        given(List.of(manual(101)));
        assertThat(actor(() -> service.get("E", "S", "A", "WO")).fingerprint()).isNotEqualTo(first.fingerprint());
        when(bills.getBill("E", "S", "A", "WO")).thenReturn(bill(List.of(manual(100)), AT, "new-binding", true));
        assertThat(actor(() -> service.get("E", "S", "A", "WO")).fingerprint()).isNotEqualTo(first.fingerprint());
        given(List.of(manual(100)));
        var anotherKind = DelegatedIdentityContext.call(new DelegatedIdentity("MAIN", "MERCHANT", "USR", "A"),
                () -> service.get("E", "S", "A", "WO"));
        assertThat(anotherKind.fingerprint()).isNotEqualTo(first.fingerprint());
    }
    @Test void previewRechecksCurrentOwnerAndRejectsChangedFinancialSnapshot() {
        given(List.of(manual(100)));
        var view = actor(() -> service.get("E", "S", "A", "WO"));
        var req = new PreviewWorkOrderSettlementReq(view.fingerprint(), "CNY", 0L, 100L, "CASH");
        var preview = actor(() -> service.preview("E", "S", "A", "WO", req));
        assertThat(preview.executable()).isFalse();
        assertThat(preview.blockingReasons()).contains("MANUAL_RECEIPT_ALREADY_DECLARED");
        given(List.of(manual(101)));
        assertThatThrownBy(() -> actor(() -> service.preview("E", "S", "A", "WO", req)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @Test void incompleteOwnerAndRevocationCannotBecomeSettlementSuccess() {
        when(bills.getBill("E", "S", "A", "WO")).thenReturn(bill(List.of(), AT, "local", false));
        var view = actor(() -> service.get("E", "S", "A", "WO"));
        assertThat(view.complete()).isFalse();
        assertThat(view.blockingReasons()).contains("SOURCE_UNAVAILABLE", "SOURCE_INCOMPLETE");
        when(bills.getBill("E", "S", "A", "WO")).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> actor(() -> service.get("E", "S", "A", "WO")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
