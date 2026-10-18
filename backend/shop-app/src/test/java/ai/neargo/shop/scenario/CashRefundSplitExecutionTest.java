package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.SplitGateway;
import ai.neargo.shop.pay.settle.refund.CashRefundSplitService;
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

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:cash-reverse-execution;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class CashRefundSplitExecutionTest {
    @Autowired CashRefundSplitService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SplitGateway bank;
    @Autowired ai.neargo.shop.pay.SettleService settlements;
    @Autowired ai.neargo.shop.spi.settle.SettlePort settlePort;
    @org.junit.jupiter.api.BeforeEach void legacy_adapter() {
        when(bank.reverse(any(ai.neargo.shop.pay.settle.SplitCommand.class))).thenAnswer(call -> {
            var c=call.<ai.neargo.shop.pay.settle.SplitCommand>getArgument(0);
            assertThat(c.storeNo()).isEqualTo("STORE"); assertThat(c.currency()).isEqualTo("CNY");
            assertThat(c.channel()).isEqualTo("TEST");
            return bank.reverse(c.subOrderNo(),c.payMerchantNo(),c.amountMinor(),c.requestNo());
        });
        when(bank.queryReverse(any(ai.neargo.shop.pay.settle.SplitCommand.class))).thenAnswer(call -> {
            var c=call.<ai.neargo.shop.pay.settle.SplitCommand>getArgument(0);
            assertThat(c.storeNo()).isEqualTo("STORE"); assertThat(c.currency()).isEqualTo("CNY");
            assertThat(c.channel()).isEqualTo("TEST");
            return bank.queryReverse(c.subOrderNo(),c.payMerchantNo(),c.amountMinor(),c.requestNo());
        });
    }

    private SplitReversal bill(boolean proof) {
        String sub="CR"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO stl_bill(settle_no,sub_order_no,order_no,entity_no,store_no,pay_merchant_no,pay_channel,"
                +"currency,status,gross_minor,commission_minor,net_minor,split_amount_minor,funds_mode,split_at,created_at,updated_at)"
                +" VALUES(?,?,?,'M','STORE','ORIGINAL','TEST','CNY','SPLIT',9000,540,8460,540,'DIRECT',1,NOW(),NOW())",sub,sub,sub);
        if(proof) jdbc.update("INSERT INTO stl_split_log(settle_no,sub_order_no,split_action,amount_minor,request_no,result,provider_no,at,created_at)"
                +" VALUES(?,?,'SPLIT',540,?,'SUCCESS',?,1,NOW())",sub,sub,"SPL-"+sub,"BANK-SPL-"+sub);
        return new SplitReversal(sub,"AS-"+sub,3000,0,9000);
    }
    @Test void unproven_split_never_sends_a_reversal() {
        var cmd=bill(false);
        assertThat(service.reverse(cmd)).isFalse();
        verifyNoInteractions(bank);
    }
    @Test void reply_loss_queries_only_the_original_committed_command_and_route() {
        var cmd=bill(true);
        when(bank.reverse(anyString(),anyString(),anyLong(),anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT state FROM stl_cash_refund_reverse WHERE after_sale_no=?",String.class,cmd.afterSaleNo())).isEqualTo("DISPATCHING");
            assertThat(call.<String>getArgument(1)).isEqualTo("ORIGINAL");
            assertThat(call.<Long>getArgument(2)).isEqualTo(180L);
            throw new IllegalStateException("BANK_ACCEPTED_REPLY_LOST");
        });
        assertThat(service.reverse(cmd)).isFalse();
        assertThat(jdbc.queryForObject("SELECT refunded_goods_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isZero();
        jdbc.update("UPDATE stl_bill SET pay_merchant_no='CHANGED',status='SPLIT_CONFIRMED' WHERE sub_order_no=?",cmd.subOrderNo());
        when(bank.queryReverse(anyString(),anyString(),anyLong(),anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(call.<String>getArgument(1)).isEqualTo("ORIGINAL");
            assertThat(call.<Long>getArgument(2)).isEqualTo(180L);
            return SplitGateway.Result.ok("BANK-REV-"+cmd.afterSaleNo());
        });
        assertThat(service.reverse(cmd)).isTrue();
        assertThat(service.reverse(cmd)).isTrue();
        verify(bank,times(1)).reverse(eq(cmd.subOrderNo()),eq("ORIGINAL"),eq(180L),anyString());
        verify(bank,times(1)).queryReverse(eq(cmd.subOrderNo()),eq("ORIGINAL"),eq(180L),anyString());
        assertThat(jdbc.queryForObject("SELECT reversed_minor FROM stl_bill WHERE sub_order_no=?",Long.class,cmd.subOrderNo())).isEqualTo(180);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_bill_refund WHERE after_sale_no=?",Long.class,cmd.afterSaleNo())).isEqualTo(1);
    }
    @Test void unknown_reversal_reserves_the_bill_against_a_second_refund() {
        var cmd=bill(true);
        when(bank.reverse(anyString(),anyString(),anyLong(),anyString())).thenReturn(SplitGateway.Result.fail("UNKNOWN"));
        assertThat(service.reverse(cmd)).isFalse();
        assertThat(service.reverse(new SplitReversal(cmd.subOrderNo(),"OTHER-"+cmd.afterSaleNo(),3000,0,9000))).isFalse();
        verify(bank,times(1)).reverse(anyString(),anyString(),anyLong(),anyString());
        verify(bank,never()).queryReverse(anyString(),anyString(),anyLong(),anyString());
        assertThat(settlePort.reverseSplit(new SplitReversal(cmd.subOrderNo(),"LEGACY-"+cmd.afterSaleNo(),3000,0,9000))).isFalse();
        assertThat(settlements.reverseSplit(cmd.subOrderNo())).isFalse();
        assertThatThrownBy(() -> settlements.executeSplit(cmd.subOrderNo())).hasMessage("SETTLEMENT_CASH_REVERSAL_PENDING");
    }
    @Test void an_unknown_original_request_rejects_changed_refund_inputs() {
        var cmd=bill(true);
        when(bank.reverse(anyString(),anyString(),anyLong(),anyString())).thenReturn(SplitGateway.Result.fail("UNKNOWN"));
        assertThat(service.reverse(cmd)).isFalse();
        assertThatThrownBy(() -> service.reverse(new SplitReversal(cmd.subOrderNo(),cmd.afterSaleNo(),3001,0,9000)))
                .hasMessage("CASH_REVERSAL_INPUT_CONFLICT");
        verify(bank,times(1)).reverse(anyString(),anyString(),anyLong(),anyString());
    }
    @Test void success_without_a_provider_receipt_keeps_the_bill_unadjusted() {
        var cmd=bill(true);
        when(bank.reverse(anyString(),anyString(),anyLong(),anyString())).thenReturn(new SplitGateway.Result(true,null,null));
        assertThat(service.reverse(cmd)).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM stl_cash_refund_reverse WHERE after_sale_no=?",String.class,cmd.afterSaleNo())).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_bill_refund WHERE after_sale_no=?",Long.class,cmd.afterSaleNo())).isZero();
    }
    @Test void cash_pre_split_adjustment_never_calls_the_bank() {
        var cmd=bill(false);
        jdbc.update("UPDATE stl_bill SET status='PENDING_RECON',split_at=NULL WHERE sub_order_no=?",cmd.subOrderNo());
        assertThat(service.reverse(cmd)).isTrue();
        assertThat(service.reverse(cmd)).isTrue();
        verifyNoInteractions(bank);
    }
}
