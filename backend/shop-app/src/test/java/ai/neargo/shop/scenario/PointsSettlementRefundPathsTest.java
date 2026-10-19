package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.SplitGateway;
import ai.neargo.shop.pay.settle.SplitCommand;
import ai.neargo.shop.pay.settle.SettlementPointsFundingService;
import ai.neargo.shop.spi.points.PointsRefundPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:points-settlement-refund-paths;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PointsSettlementRefundPathsTest {
    @Autowired SettlePort port;
    @Autowired JdbcTemplate jdbc;
    @Autowired SettlementPointsFundingService funding;
    @MockitoBean PointsRefundPort points;
    @MockitoBean SplitGateway bank;
    @MockitoBean ai.neargo.shop.pay.service.impl.RefundCapacityLedger capacity;

    private SplitReversal bill(String state, String route) {
        String no="PS"+UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO stl_bill(settle_no,sub_order_no,order_no,entity_no,store_no,pay_merchant_no,"
                +"pay_channel,currency,status,gross_minor,commission_minor,net_minor,split_amount_minor,"
                +"subsidy_minor,points_cost_minor,funds_mode,split_at,paid_at,payment_ref,created_at,updated_at)"
                +" VALUES(?,?,?,'M','STORE','ORIGINAL','TEST','CNY',?,100,10,90,10,0,0,?,?,?, ?,NOW(),NOW())",
                no,no,no,state,route,state.startsWith("SPLIT")?1L:null,"PAID".equals(state)?2L:null,
                "PAID".equals(state)?"BANK-PAID":null);
        if(state.startsWith("SPLIT")) jdbc.update("INSERT INTO stl_split_log(settle_no,sub_order_no,"
                +"split_action,amount_minor,request_no,result,provider_no,at,created_at)"
                +" VALUES(?,?,'SPLIT',10,?,'SUCCESS','ORIGINAL-SPLIT',1,NOW())",no,no,"SPL-"+no);
        return new SplitReversal(no,"AS-"+no,100,0,100);
    }
    private void proof(SplitReversal cmd) {
        when(points.recoveryStatus("PREP:"+cmd.afterSaleNo(),"HASH")).thenReturn(
                new PointsRefundPort.RefundRecoveryStatus("PREP:"+cmd.afterSaleNo(),cmd.afterSaleNo(),
                        cmd.subOrderNo(),"CONFIRMED",100,"CASH:"+cmd.afterSaleNo(),false,false));
    }
    private void originalBasis(SplitReversal cmd) {
        jdbc.update("INSERT INTO stl_bill_refund_basis(settle_no,sub_order_no,basis_version,source,status,"
                +"goods_paid_minor,gross_minor,merchant_goods_minor,commission_minor,service_fee_minor,"
                +"points_fee_minor,points_cost_minor,subsidy_minor,freight_income_minor,freight_cost_minor,created_at)"
                +" VALUES(?,?,1,'ORIGINAL','READY',100,100,90,10,0,0,0,0,0,0,NOW())",cmd.subOrderNo(),cmd.subOrderNo());
    }
    @Test void aggregatedUnpaidRefundHoldsPaymentAndReopensTheRemainingPayable() {
        for(String state:java.util.List.of("PENDING_RECON","CONFIRMED")) {
            var full=bill(state,"AGGREGATED");
            jdbc.update("UPDATE stl_bill SET points_cost_minor=20 WHERE sub_order_no=?",full.subOrderNo());
            var cmd=new SplitReversal(full.subOrderNo(),full.afterSaleNo(),50,0,100);
            assertThat(port.reversePreparedRefund(cmd)).isTrue();
            assertThat(jdbc.queryForObject("SELECT net_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isEqualTo(90);
            assertThatThrownBy(() -> funding.assertReady(cmd.subOrderNo())).hasMessage("SETTLEMENT_REFUND_PENDING");
            proof(cmd);
            assertThat(port.confirmPreparedRefund(cmd,"PREP:"+cmd.afterSaleNo(),"HASH")).isTrue();
            assertThat(port.confirmPreparedRefund(cmd,"PREP:"+cmd.afterSaleNo(),"HASH")).isTrue();
            assertThat(jdbc.queryForObject("SELECT net_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isEqualTo(45);
            assertThat(jdbc.queryForObject("SELECT points_cost_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT status FROM stl_bill WHERE sub_order_no=?",String.class,cmd.subOrderNo())).isEqualTo("PENDING_RECON");
        }
        verifyNoInteractions(bank);
    }
    @Test void paidSupplierRefundRetainsThePaymentAndRecordsAnUnrecoveredReceivable() {
        var cmd=bill("PAID","AGGREGATED");
        assertThat(port.reversePreparedRefund(cmd)).isTrue();
        proof(cmd);
        assertThat(port.confirmPreparedRefund(cmd,"PREP:"+cmd.afterSaleNo(),"HASH")).isTrue();
        assertThat(port.confirmPreparedRefund(cmd,"PREP:"+cmd.afterSaleNo(),"HASH")).isTrue();
        var row=jdbc.queryForMap("SELECT * FROM stl_bill WHERE sub_order_no=?",cmd.subOrderNo());
        assertThat(row).containsEntry("status","PAID").containsEntry("net_minor",90L)
                .containsEntry("paid_at",2L).containsEntry("payment_ref","BANK-PAID").containsEntry("reversed_minor",0L);
        var hold=jdbc.queryForMap("SELECT * FROM stl_points_refund_hold WHERE after_sale_no=?",cmd.afterSaleNo());
        assertThat(hold).containsEntry("recovery_minor",90L).containsEntry("recovery_status","PENDING");
        verifyNoInteractions(bank);
    }
    @Test void repeatedUnpaidRefundsUseTheOriginalBasisUntilEveryCentIsAccountedFor() {
        var full=bill("PENDING_RECON","AGGREGATED");
        originalBasis(full);
        for (String no:java.util.List.of(full.afterSaleNo(),"B-"+full.subOrderNo())) {
            var part=new SplitReversal(full.subOrderNo(),no,50,0,100);
            assertThat(port.reversePreparedRefund(part)).isTrue();
            proof(part);
            assertThat(port.confirmPreparedRefund(part,"PREP:"+part.afterSaleNo(),"HASH")).isTrue();
        }
        var row=jdbc.queryForMap("SELECT * FROM stl_bill WHERE sub_order_no=?",full.subOrderNo());
        assertThat(row).containsEntry("net_minor",0L).containsEntry("commission_minor",0L)
                .containsEntry("gross_minor",0L).containsEntry("refunded_goods_minor",100L);
        verifyNoInteractions(bank);
    }
    @Test void legacyPendingHoldCannotAllowCashWhenTheOriginalBasisIsUnknown() {
        var full=bill("PENDING_RECON","AGGREGATED");
        var cmd=new SplitReversal(full.subOrderNo(),full.afterSaleNo(),50,0,100);
        jdbc.update("UPDATE stl_bill SET refunded_goods_minor=50,net_minor=45,commission_minor=5,gross_minor=50 WHERE sub_order_no=?",
                cmd.subOrderNo());
        jdbc.update("INSERT INTO stl_points_refund_hold(after_sale_no,settle_no,sub_order_no,goods_minor,freight_minor,goods_paid_minor,status,created_at,updated_at)"
                +" VALUES(?,?,?,50,0,100,'PENDING',NOW(),NOW())",cmd.afterSaleNo(),cmd.subOrderNo(),cmd.subOrderNo());
        assertThatThrownBy(() -> port.reversePreparedRefund(cmd)).hasMessage("SETTLEMENT_REFUND_BASIS_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT refunded_goods_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isEqualTo(50);
        verifyNoInteractions(bank);
    }
    @Test void preparedRefundAfterAnEarlierPayableReductionUsesOriginalAmountsAndRemainingSplit() {
        var full=bill("PENDING","DIRECT");
        originalBasis(full);
        var first=new SplitReversal(full.subOrderNo(),full.afterSaleNo(),50,0,100);
        assertThat(port.reversePreparedRefund(first)).isTrue();
        proof(first);
        assertThat(port.confirmPreparedRefund(first,"PREP:"+first.afterSaleNo(),"HASH")).isTrue();
        jdbc.update("UPDATE stl_bill SET status='SPLIT',split_at=1 WHERE sub_order_no=?",full.subOrderNo());
        jdbc.update("INSERT INTO stl_split_log(settle_no,sub_order_no,split_action,amount_minor,request_no,result,provider_no,at,created_at)"
                +" VALUES(?,?,'SPLIT',5,?,'SUCCESS',?,1,NOW())",full.subOrderNo(),full.subOrderNo(),"SPL-"+full.subOrderNo(),"BANK-"+full.subOrderNo());
        var second=new SplitReversal(full.subOrderNo(),"B-"+full.subOrderNo(),50,0,100);
        when(bank.reverse(any(SplitCommand.class))).thenAnswer(call -> {
            assertThat(call.<SplitCommand>getArgument(0).amountMinor()).isEqualTo(5);
            return SplitGateway.Result.ok("RETURNED:"+second.afterSaleNo());
        });
        assertThat(port.reversePreparedRefund(second)).isTrue();
        proof(second);
        assertThat(port.confirmPreparedRefund(second,"PREP:"+second.afterSaleNo(),"HASH")).isTrue();
        assertThat(jdbc.queryForObject("SELECT SUM(merchant_reverse_minor) FROM stl_bill_refund WHERE settle_no=?",Long.class,full.subOrderNo())).isEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT refunded_goods_minor FROM stl_bill WHERE sub_order_no=?",Long.class,full.subOrderNo())).isEqualTo(100);
        verify(bank,times(1)).reverse(any(SplitCommand.class));
    }
    @Test void directSplitReplyLossQueriesTheOriginalReversalBeforeCashAndPostsGoodsOnlyAfterConfirmation() {
        var cmd=bill("SPLIT","DIRECT");
        jdbc.update("UPDATE stl_bill SET points_cost_minor=20,subsidy_minor=5 WHERE sub_order_no=?",cmd.subOrderNo());
        when(bank.reverse(any(SplitCommand.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new IllegalStateException("REPLY_LOST");
        });
        assertThat(port.reversePreparedRefund(cmd)).isFalse();
        assertThat(jdbc.queryForObject("SELECT refunded_goods_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isZero();
        when(points.recoveryStatus("PREP:"+cmd.afterSaleNo(),"HASH")).thenReturn(
                new PointsRefundPort.RefundRecoveryStatus("PREP:"+cmd.afterSaleNo(),cmd.afterSaleNo(),
                        cmd.subOrderNo(),"ABORTED",100,"FAILED-CASH",false,false));
        assertThatThrownBy(() -> port.cancelPreparedRefund(cmd,"PREP:"+cmd.afterSaleNo(),"HASH"))
                .hasMessage("SETTLEMENT_REFUND_REVERSAL_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM stl_points_refund_hold WHERE after_sale_no=?",String.class,cmd.afterSaleNo())).isEqualTo("PENDING");
        jdbc.update("UPDATE stl_bill SET pay_merchant_no='CHANGED',status='SPLIT_CONFIRMED' WHERE sub_order_no=?",cmd.subOrderNo());
        when(bank.queryReverse(any(SplitCommand.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var frozen=call.<SplitCommand>getArgument(0);
            assertThat(frozen.payMerchantNo()).isEqualTo("ORIGINAL");
            assertThat(frozen.amountMinor()).isEqualTo(10);
            return SplitGateway.Result.ok("RETURNED:"+cmd.afterSaleNo());
        });
        assertThat(port.reversePreparedRefund(cmd)).isTrue();
        assertThat(jdbc.queryForObject("SELECT refunded_goods_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT reversed_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isEqualTo(10);
        proof(cmd);
        assertThat(port.confirmPreparedRefund(cmd,"PREP:"+cmd.afterSaleNo(),"HASH")).isTrue();
        assertThat(port.confirmPreparedRefund(cmd,"PREP:"+cmd.afterSaleNo(),"HASH")).isTrue();
        assertThat(jdbc.queryForObject("SELECT refunded_goods_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT reversed_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isEqualTo(10);
        verify(bank,times(1)).reverse(any(SplitCommand.class));
        verify(bank,times(1)).queryReverse(any(SplitCommand.class));
    }

    @Test void cancelledCashRefundRetainsActualReversalAndRetryCannotReverseItTwice() {
        var cmd=bill("SPLIT","DIRECT");
        when(bank.reverse(any(SplitCommand.class))).thenReturn(SplitGateway.Result.ok("RETURNED:"+cmd.afterSaleNo()));
        assertThat(port.reversePreparedRefund(cmd)).isTrue();
        var proof=new PointsRefundPort.RefundRecoveryStatus("PREP:"+cmd.afterSaleNo(),cmd.afterSaleNo(),
                cmd.subOrderNo(),"ABORTED",100,"FAILED-CASH",false,false);
        when(points.recoveryStatus(proof.preparationNo(),"HASH")).thenReturn(proof);
        assertThat(port.cancelPreparedRefund(cmd,proof.preparationNo(),"HASH")).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM stl_bill WHERE sub_order_no=?",String.class,cmd.subOrderNo())).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("SELECT refunded_goods_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT reversed_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isEqualTo(10);
        var retry=new SplitReversal(cmd.subOrderNo(),"B-"+cmd.subOrderNo(),100,0,100);
        assertThat(port.reversePreparedRefund(retry)).isTrue();
        proof(retry);
        assertThat(port.confirmPreparedRefund(retry,"PREP:"+retry.afterSaleNo(),"HASH")).isTrue();
        verify(bank,times(1)).reverse(any(SplitCommand.class));
        verify(bank,never()).queryReverse(any(SplitCommand.class));
        assertThat(jdbc.queryForObject("SELECT reversed_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isEqualTo(10);
    }
}
