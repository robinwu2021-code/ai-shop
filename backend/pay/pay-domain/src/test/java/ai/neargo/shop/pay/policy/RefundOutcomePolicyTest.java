package ai.neargo.shop.pay.policy;
import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.spi.pay.RefundQueryResult.Status;
import org.junit.jupiter.api.Test;
class RefundOutcomePolicyTest {
    @Test void lateSuccessWinsOverLegacyStatus() {
        var row=new StlPayment();row.setPayChannel("TEST");row.setOutTradeNo("RF");row.setStatus("FAILED");row.setRefundQueryState("LATE_SUCCESS");
        assertThat(RefundOutcomePolicy.resolve(row,true).outcome()).isEqualTo(Status.MANUAL_REQUIRED);
        assertThat(RefundOutcomePolicy.resolve(row,true).safeToRecover()).isFalse();
    }
    @Test void historicalFailureNeedsProofAndOriginMustNotBeFrozen() {
        var row=new StlPayment();row.setPayChannel("TEST");row.setOutTradeNo("RF");row.setStatus("FAILED");row.setRefundQueryState("TERMINAL_FAILED");
        assertThat(RefundOutcomePolicy.resolve(row,false).safeToRecover()).isFalse();
        row.setRefundTerminalProof("verified");
        assertThat(RefundOutcomePolicy.resolve(row,false).safeToRecover()).isTrue();
        assertThat(RefundOutcomePolicy.resolve(row,true).safeToRecover()).isFalse();
    }
    @Test void knownSuccessIsNotErasedByAnotherRefundFreezingTheOrigin() {
        var row=new StlPayment();row.setPayChannel("TEST");row.setOutTradeNo("RF");row.setStatus("SUCCESS");
        assertThat(RefundOutcomePolicy.resolve(row,true).outcome()).isEqualTo(Status.SUCCEEDED);
    }
    @Test void processingOnHistoricalFailedRowIsNotTerminal() {
        var row=new StlPayment();row.setPayChannel("TEST");row.setOutTradeNo("RF");row.setStatus("FAILED");row.setRefundQueryState("PROCESSING");
        assertThat(RefundOutcomePolicy.resolve(row,false).outcome()).isEqualTo(Status.PROCESSING);
        assertThat(RefundOutcomePolicy.resolve(row,false).terminalVerified()).isFalse();
    }
}
