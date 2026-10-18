package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.pay.service.impl.RefundPaymentStore;
import ai.neargo.shop.pay.service.impl.RefundCapacityLedger;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundRecoveryStatus;
import ai.neargo.shop.spi.settle.RefundRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:refund-capacity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RefundCapacityFlowTest {
    @Autowired RefundPaymentStore refunds;
    @Autowired RefundCapacityLedger capacity;
    @Autowired JdbcTemplate jdbc;
    @Autowired ai.neargo.shop.pay.service.PaymentLedgerService ledger;

    @Test void failedFullRefundOnlyReleasesAfterVerifiedRecovery() {
        String origin=origin();
        var first=refunds.prepare(request(origin,"A"));
        refunds.reject(first.getRefundExecutionNo(),"REFUND_REJECTED");
        assertThatThrownBy(() -> refunds.prepare(request(origin,"B"))).hasMessageContaining("CONFLICT");
        assertThatThrownBy(() -> capacity.releaseAfterRecovery(proof(first.getPaymentNo(),origin,"COMPENSATING",true,false)))
                .hasMessageContaining("RECOVERY");
        capacity.releaseAfterRecovery(proof(first.getPaymentNo(),origin,"ABORTED",true,true));
        capacity.releaseAfterRecovery(proof(first.getPaymentNo(),origin,"ABORTED",true,true));
        assertThat(refunds.prepare(request(origin,"B")).getAmountMinor()).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from stl_payment where order_no=? and direction='REFUND'",Long.class,origin))
                .isEqualTo(2);
    }
    @Test void unknownAndUnprovedHistoricalFailuresKeepCapacity() {
        String origin=origin();
        var first=refunds.prepare(request(origin,"A"));
        refunds.result(first.getRefundExecutionNo(),"PENDING","REFUND_SEND_UNKNOWN",null);
        assertThatThrownBy(() -> capacity.releaseAfterRecovery(proof(first.getPaymentNo(),origin,"ABORTED",false,false)))
                .hasMessageContaining("TERMINAL");
        jdbc.update("update stl_payment set status='FAILED' where payment_no=?",first.getPaymentNo());
        assertThatThrownBy(() -> capacity.releaseAfterRecovery(proof(first.getPaymentNo(),origin,"ABORTED",false,false)))
                .hasMessageContaining("TERMINAL");
    }
    @Test void lateSuccessAfterReleaseFreezesOriginAndDoesNotConsumeAnotherRefund() {
        String origin=origin();
        var first=refunds.prepare(request(origin,"A"));
        refunds.reject(first.getRefundExecutionNo(),"REFUND_REJECTED");
        capacity.releaseAfterRecovery(proof(first.getPaymentNo(),origin,"ABORTED",false,false));
        var receipt=refunds.confirm(first.getOutTradeNo(),"TEST",100,"CNY","LATE:"+origin);
        assertThat(receipt.status().name()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> refunds.prepare(request(origin,"B"))).hasMessageContaining("FROZEN");
    }
    @Test void bothCashPathsShareCapacityAndVerifiedClosure() {
        String origin=origin();
        String payment=ledger.refund(origin,"SUB:"+origin,"AS:"+origin+"A",100,"refund");
        capacity.observe(payment,new ai.neargo.shop.spi.pay.RefundQueryResult(
                ai.neargo.shop.spi.pay.RefundQueryResult.Status.PROCESSING,"PROCESSING",
                ledger.refundTicket(payment).orElseThrow().outRefundNo(),100,"BANK", "CNY",1));
        assertThatThrownBy(() -> refunds.prepare(request(origin,"B"))).hasMessageContaining("CONFLICT");
        capacity.observe(payment,new ai.neargo.shop.spi.pay.RefundQueryResult(
                ai.neargo.shop.spi.pay.RefundQueryResult.Status.TERMINAL_FAILED,"CLOSED",
                ledger.refundTicket(payment).orElseThrow().outRefundNo(),100,"BANK", "CNY",2));
        capacity.releaseAfterRecovery(proof(payment,origin,"ABORTED",false,false));
        assertThat(refunds.prepare(request(origin,"B")).getAmountMinor()).isEqualTo(100);
    }

    @Test void successfulRefundNeverReleasesAndAnotherOriginIsIndependent() {
        String first=origin(),second=origin();
        var refund=refunds.prepare(request(first,"A"));
        refunds.confirm(refund.getOutTradeNo(),"TEST",100,"CNY","SUCCESS:"+first);
        assertThatThrownBy(() -> capacity.releaseAfterRecovery(proof(refund.getPaymentNo(),first,"ABORTED",false,false)))
                .hasMessageContaining("TERMINAL");
        assertThatThrownBy(() -> refunds.prepare(request(first,"B"))).hasMessageContaining("CONFLICT");
        assertThat(refunds.prepare(request(second,"A")).getAmountMinor()).isEqualTo(100);
    }

    @Test void oldFatalClassificationIsNotAReleaseProof() {
        String origin=origin();
        String payment=ledger.refund(origin,"SUB:"+origin,"AS:"+origin+"A",100,"refund");
        ledger.markRefundSent(payment,false,false,null,"response missing provider");
        assertThatThrownBy(() -> capacity.releaseAfterRecovery(proof(payment,origin,"ABORTED",false,false)))
                .hasMessageContaining("TERMINAL");
        assertThatThrownBy(() -> refunds.prepare(request(origin,"B"))).hasMessageContaining("CONFLICT");
    }

    @Autowired ai.neargo.shop.pay.service.RefundExecutionService execution;

    @Test void lateSuccessIsManualInEverySubsequentQuery() {
        String origin=origin();
        var first=refunds.prepare(request(origin,"A"));
        refunds.reject(first.getRefundExecutionNo(),"REFUND_REJECTED");
        capacity.releaseAfterRecovery(proof(first.getPaymentNo(),origin,"ABORTED",false,false));
        refunds.confirm(first.getOutTradeNo(),"TEST",100,"CNY","LATE:"+origin);
        assertThat(execution.query(first.getRefundExecutionNo()).status().name()).isEqualTo("UNKNOWN");
        assertThat(execution.query(first.getRefundExecutionNo()).errorCode()).isEqualTo("REFUND_LATE_SUCCESS_FROZEN");
    }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    ai.neargo.shop.pay.channel.TestPayGateway gateway;
    @Autowired ai.neargo.shop.pay.SettleService settle;

    @Test void historicalFailedCashQueriesOriginalEvenWithoutSendSnapshot() {
        String origin=origin();
        String payment=ledger.refund(origin,"SUB:"+origin,"AS:"+origin+"A",100,"refund");
        String out=ledger.refundTicket(payment).orElseThrow().outRefundNo();
        jdbc.update("update stl_payment set status='FAILED',refund_request_json=null where payment_no=?",payment);
        org.mockito.Mockito.doReturn(new ai.neargo.shop.spi.pay.RefundQueryResult(
                ai.neargo.shop.spi.pay.RefundQueryResult.Status.SUCCEEDED,"SUCCESS",out,100,"BANK:"+origin,"CNY",1))
                .when(gateway).queryRefundOutcome(out);
        settle.refreshRefund(payment);
        assertThat(ledger.refundEvidence(payment).status()).isEqualTo("SUCCESS");
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.never()).refund(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
    }

    @Test void manualOutcomeSurvivesLaterUnknownObservation() {
        String origin=origin();
        var first=refunds.prepare(request(origin,"A"));
        capacity.observe(first.getPaymentNo(),new ai.neargo.shop.spi.pay.RefundQueryResult(
                ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED,"ABNORMAL",first.getOutTradeNo(),0,null,null,1));
        capacity.observe(first.getPaymentNo(),ai.neargo.shop.spi.pay.RefundQueryResult.unknown(first.getOutTradeNo()));
        assertThat(ledger.refundEvidence(first.getPaymentNo()).safety().outcome().name()).isEqualTo("MANUAL_REQUIRED");
    }

    @Test void manualOutcomeOnUnsentBasicIntentCannotEnableDispatch() {
        String origin=origin();var first=refunds.prepare(request(origin,"A"));
        capacity.observe(first.getPaymentNo(),new ai.neargo.shop.spi.pay.RefundQueryResult(
                ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED,"ABNORMAL",first.getOutTradeNo(),0,null,null,1));
        var result=execution.query(first.getRefundExecutionNo());
        assertThat(result.errorCode()).isEqualTo("REFUND_MANUAL_REQUIRED");
        assertThat(result.retryable()).isFalse();
        assertThat(refunds.claim(first.getRefundExecutionNo())).isFalse();
    }
    @Test void observedProcessingOnUnsentLedgerIntentCannotEnableDispatch() {
        String origin=origin();String payment=ledger.refund(origin,"SUB:"+origin,"AS:"+origin+"A",100,"refund");
        String out=ledger.refundTicket(payment).orElseThrow().outRefundNo();
        capacity.observe(payment,new ai.neargo.shop.spi.pay.RefundQueryResult(
                ai.neargo.shop.spi.pay.RefundQueryResult.Status.PROCESSING,"PROCESSING",out,100,"BANK","CNY",1));
        assertThat(ledger.claimRefundDispatch(payment)).isFalse();
    }

    private String origin() {
        String no="CAP"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into stl_payment(payment_no,direction,order_no,user_no,pay_channel,currency,amount_minor,status,trade_no,out_trade_no,created_at,updated_at) values(?,'PAY',?,'U','TEST','CNY',100,'SUCCESS',?,?,NOW(),NOW())",no,no,no,no);
        return no;
    }
    private RefundRequest request(String origin,String suffix) {
        return new RefundRequest("RF:"+origin+suffix,"AS:"+origin+suffix,"SUB:"+origin,origin,100,"CNY","refund");
    }
    private RefundRecoveryStatus proof(String payment,String origin,String state,boolean collection,boolean compensation) {
        return new RefundRecoveryStatus("PREP","AS:"+origin+"A","SUB:"+origin,state,100,payment,collection,compensation);
    }
}
