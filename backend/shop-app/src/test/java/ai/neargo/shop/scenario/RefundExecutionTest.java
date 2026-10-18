package ai.neargo.shop.scenario;

import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import ai.neargo.shop.trade.order.mapper.RefundLedgerMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Source preparations use actual source/pay databases and never send money to a bank. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:refund_execution;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RefundExecutionTest {
    @Autowired private RefundExecutionService executions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefundLedgerMapper legacy;

    @Test void prepare_reserves_without_counting_refunded_and_replays_original_plan() {
        var fixture=fixture();
        var plan=new RefundPlan(fixture.sub(),List.of(new RefundLine(fixture.item(),1,50)),10);
        var prepared=executions.prepare(fixture.as(),plan);
        assertThat(executions.prepare(fixture.as(),plan)).isEqualTo(prepared);
        assertThat(refunded(fixture.sub())).isZero();
        assertThatThrownBy(() -> executions.prepare(fixture.as(),
                new RefundPlan(fixture.sub(),List.of(new RefundLine(fixture.item(),1,40)),10)))
                .hasMessage("REFUND_EXECUTION_INPUT_CONFLICT");
        String second=afterSale(fixture.sub(),60);
        assertThatThrownBy(() -> executions.prepare(second,plan)).hasMessage("REFUND_EXECUTION_CAPACITY_EXCEEDED");
    }

    @Test void only_verified_cash_commits_business_refund_once_and_money_only_lines_are_counted() {
        var f=fixture();
        var plan=new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),0,30)),0);
        jdbc.update("UPDATE ord_after_sale SET approved_minor=30 WHERE after_sale_no=?",f.as());
        executions.prepare(f.as(),plan);
        String payment="P:"+f.as();
        cash(payment,f,30,"PENDING");
        executions.beginCash(f.as());
        executions.bindCash(f.as(),payment,30);
        assertThatThrownBy(() -> executions.confirmCash(f.as())).hasMessage("REFUND_CASH_NOT_CONFIRMED");
        assertThatThrownBy(() -> executions.commit(f.as())).hasMessage("REFUND_EXECUTION_NOT_CONFIRMED");
        assertThat(refunded(f.sub())).isZero();
        jdbc.update("UPDATE stl_payment SET status='SUCCESS',succeeded_at=1,refund_verified_at=1,"
                + "refund_verified_minor=29,refund_verified_provider_no=? WHERE payment_no=?",payment,payment);
        assertThatThrownBy(() -> executions.confirmCash(f.as())).hasMessage("REFUND_CASH_EVIDENCE_MISMATCH");
        jdbc.update("UPDATE stl_payment SET refund_verified_minor=30 WHERE payment_no=?",payment);
        executions.confirmCash(f.as());
        var result=executions.commit(f.as());
        assertThat(executions.commit(f.as())).isEqualTo(result);
        assertThat(refunded(f.sub())).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT refunded_minor FROM ord_item WHERE id=?",Long.class,f.item()))
                .isEqualTo(30);
    }

    @Test void unbound_preparation_can_be_released_and_cash_pending_cannot() {
        var f=fixture();
        var plan=new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),1,50)),10);
        executions.prepare(f.as(),plan); executions.release(f.as()); executions.release(f.as());
        String second=afterSale(f.sub(),60);
        executions.prepare(second,plan);
        cash("P:"+second,new Fixture(f.sub(),second,f.item()),60,"PENDING");
        executions.beginCash(second);
        executions.bindCash(second,"P:"+second,60);
        assertThatThrownBy(() -> executions.release(second)).hasMessage("REFUND_EXECUTION_RELEASE_UNSAFE");
        assertThat(refunded(f.sub())).isZero();
    }
    @Test void reserved_capacity_cannot_be_consumed_by_legacy_ledger_writes() {
        var f=fixture();
        executions.prepare(f.as(),new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),1,50)),10));
        assertThat(legacy.addItemRefund(f.item(),1,50)).isZero();
        assertThat(legacy.addSubRefund(f.sub(),60)).isZero();
        cash("P:"+f.as(),f,60,"PENDING");
        executions.beginCash(f.as());
        executions.bindCash(f.as(),"P:"+f.as(),60);
        jdbc.update("UPDATE stl_payment SET status='SUCCESS',refund_verified_at=1,refund_verified_minor=60,"
                + "refund_verified_provider_no=? WHERE payment_no=?","PROOF:"+f.as(),"P:"+f.as());
        executions.confirmCash(f.as()); executions.commit(f.as());
        assertThat(refunded(f.sub())).isEqualTo(60);
    }

    @Test void concurrent_preparations_compete_for_the_same_freight_capacity() throws Exception {
        var f=fixture();
        String second=afterSale(f.sub(),60);
        var plan=new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),1,50)),10);
        var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> reserveAfter(start,f.as(),plan));
            var b=pool.submit(() -> reserveAfter(start,second,plan));
            start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)^b.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(refunded(f.sub())).isZero();
    }
    private boolean reserveAfter(CountDownLatch start,String as,RefundPlan plan) throws InterruptedException {
        start.await();
        try { executions.prepare(as,plan); return true; }
        catch (IllegalArgumentException rejected) {
            assertThat(rejected).hasMessage("REFUND_EXECUTION_CAPACITY_EXCEEDED");
            return false;
        }
    }

    @Test void currency_is_frozen_from_original_payment_and_different_reservations_commit_independently() {
        var f=fixture();
        jdbc.update("UPDATE stl_payment SET currency='KWD' WHERE direction='PAY' AND order_no=?",f.sub());
        jdbc.update("UPDATE ord_after_sale SET approved_minor=50 WHERE after_sale_no=?",f.as());
        executions.prepare(f.as(),new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),1,50)),0));
        String second=afterSale(f.sub(),60);
        executions.prepare(second,new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),1,50)),10));
        for (String as:List.of(f.as(),second)) {
            long amount=as.equals(f.as())?50:60;
            cash("P:"+as,new Fixture(f.sub(),as,f.item()),amount,"PENDING");
            jdbc.update("UPDATE stl_payment SET currency='KWD',status='SUCCESS',refund_verified_at=1,"
                    + "refund_verified_minor=?,refund_verified_provider_no=? WHERE payment_no=?",amount,"V:"+as,"P:"+as);
            executions.beginCash(as);
            executions.bindCash(as,"P:"+as,amount);
            executions.confirmCash(as); executions.commit(as);
        }
        assertThat(refunded(f.sub())).isEqualTo(110);
        assertThat(jdbc.queryForObject("SELECT refunded_qty FROM ord_item WHERE id=?",Integer.class,f.item()))
                .isEqualTo(2);
    }

    @Test void failed_ledger_commit_rolls_back_item_counters_and_execution_phase() {
        var f=fixture();
        executions.prepare(f.as(),new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),1,50)),10));
        cash("P:"+f.as(),f,60,"PENDING");
        executions.beginCash(f.as());
        executions.bindCash(f.as(),"P:"+f.as(),60);
        jdbc.update("UPDATE stl_payment SET status='SUCCESS',refund_verified_at=1,refund_verified_minor=60,"
                + "refund_verified_provider_no=? WHERE payment_no=?","V:"+f.as(),"P:"+f.as());
        executions.confirmCash(f.as());
        // Simulate a conflicting legacy counter: the item write must roll back when the later sub write fails.
        jdbc.update("UPDATE ord_sub_order SET refunded_minor=100 WHERE sub_order_no=?",f.sub());
        assertThatThrownBy(() -> executions.commit(f.as())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT refunded_qty FROM ord_item WHERE id=?",Integer.class,f.item())).isZero();
        assertThat(jdbc.queryForObject("SELECT refunded_minor FROM ord_item WHERE id=?",Long.class,f.item())).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM ord_points_refund_execution WHERE after_sale_no=?",
                String.class,f.as())).isEqualTo("CASH_CONFIRMED");
    }

    @Test void cash_opening_fences_release_before_a_payment_number_exists() {
        var f=fixture();
        executions.prepare(f.as(),new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),1,50)),10));
        var intent=executions.beginCash(f.as());
        assertThat(executions.beginCash(f.as())).isEqualTo(intent);
        assertThat(intent.netCashMinor()).isEqualTo(60);
        assertThatThrownBy(() -> executions.release(f.as())).hasMessage("REFUND_EXECUTION_RELEASE_UNSAFE");
        assertThat(refunded(f.sub())).isZero();
    }

    @Test void points_order_cannot_open_cash_without_points_decision_confirmation() {
        var f=fixture();
        jdbc.update("INSERT INTO ord_order(order_no,user_no,status,currency,pay_amount,points_integrated,"
                + "created_at,updated_at) VALUES(?,'USER','PAID','CNY',110,1,NOW(),NOW())",f.sub());
        executions.prepare(f.as(),new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),1,50)),10));
        jdbc.update("UPDATE ord_sub_order SET points_deduct=10 WHERE sub_order_no=?",f.sub());
        assertThatThrownBy(() -> executions.beginCash(f.as()))
                .hasMessage("REFUND_EXECUTION_POINTS_CONFIRMATION_REQUIRED");
        executions.release(f.as());
    }

    @Test void points_cash_uses_only_the_authorized_net_and_waits_for_points_confirmation() {
        var f=fixture();
        jdbc.update("INSERT INTO ord_order(order_no,user_no,status,currency,pay_amount,points_integrated,"
                + "created_at,updated_at) VALUES(?,'USER','PAID','CNY',110,1,NOW(),NOW())",f.sub());
        var plan=new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),1,50)),10);
        executions.prepare(f.as(),plan);
        jdbc.update("UPDATE ord_sub_order SET points_deduct=10 WHERE sub_order_no=?",f.sub());
        assertThatThrownBy(() -> executions.beginCash(f.as()))
                .hasMessage("REFUND_EXECUTION_POINTS_CONFIRMATION_REQUIRED");
        var payment=new ai.neargo.shop.spi.points.PointsRefundPort.PaymentSnapshot(
                "DIRECT","TEST","MERCHANT","STORE","V1","SM","TR","OUT",110);
        var command=new ai.neargo.shop.spi.points.PointsRefundPort.RefundCommand(
                f.as(),f.sub(),"USER","TYPE","CNY",0,60,50,
                List.of(new ai.neargo.shop.spi.points.PointsRefundPort.RefundLine(String.valueOf(f.item()),50)),
                payment);
        var prep=new ai.neargo.shop.spi.points.PointsRefundPort.RefundPreparation(
                "PREP","TYPE",1,"HASH",0,0,0,0,10,50,0,"COLLECTION","COLLECTED");
        var json=new tools.jackson.databind.ObjectMapper();
        jdbc.update("INSERT INTO ord_points_refund_binding(after_sale_no,sub_order_no,phase,source_version,"
                + "command_json,preparation_json) VALUES(?,?,'CASH_AUTHORIZED',0,?,?)",
                f.as(),f.sub(),json.writeValueAsString(command),json.writeValueAsString(prep));
        var intent=executions.beginCash(f.as());
        assertThat(intent.netCashMinor()).isEqualTo(50);
        cash("P:"+f.as(),f,50,"PENDING");
        executions.bindCash(f.as(),"P:"+f.as(),50);
        jdbc.update("UPDATE stl_payment SET status='SUCCESS',succeeded_at=1,refund_verified_at=1,"
                + "refund_verified_minor=50,refund_verified_provider_no=? WHERE payment_no=?",
                "V:"+f.as(),"P:"+f.as());
        executions.confirmCash(f.as());
        assertThatThrownBy(() -> executions.commit(f.as()))
                .hasMessage("REFUND_EXECUTION_POINTS_CONFIRMATION_REQUIRED");
        jdbc.update("UPDATE ord_points_refund_binding SET phase='POINTS_CONFIRMED' WHERE after_sale_no=?",f.as());
        executions.commit(f.as());
        assertThat(refunded(f.sub())).isEqualTo(60);
    }

    @Test void cash_binding_rejects_a_foreign_after_sale_intent_before_sending() {
        var f=fixture();
        executions.prepare(f.as(),new RefundPlan(f.sub(),List.of(new RefundLine(f.item(),1,50)),10));
        executions.beginCash(f.as());
        String other=afterSale(f.sub(),60);
        cash("P:"+other,new Fixture(f.sub(),other,f.item()),60,"PENDING");
        assertThatThrownBy(() -> executions.bindCash(f.as(),"P:"+other,60))
                .hasMessage("REFUND_CASH_EVIDENCE_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT state FROM ord_points_refund_execution WHERE after_sale_no=?",
                String.class,f.as())).isEqualTo("CASH_OPENING");
    }

    private Fixture fixture() {
        String sub="S"+UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                + "pay_amount,goods_amount,freight_amount,created_at,updated_at)"
                + " VALUES(?,?,'USER','MERCHANT','STORE','FULFILLING','STORE_PICKUP',110,100,10,NOW(),NOW())",sub,sub);
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,price,qty,amount,"
                + "paid_share_minor,created_at,updated_at) VALUES(?,?,'G','SKU','ITEM',50,2,100,100,NOW(),NOW())",sub,sub);
        long item=jdbc.queryForObject("SELECT id FROM ord_item WHERE sub_order_no=?",Long.class,sub);
        jdbc.update("INSERT INTO stl_payment(payment_no,direction,order_no,user_no,amount_minor,currency,status,"
                + "pay_channel,created_at,updated_at) VALUES(?,'PAY',?,'USER',110,'CNY','SUCCESS','TEST',NOW(),NOW())",
                "ORIG:"+sub,sub);
        return new Fixture(sub,afterSale(sub,60),item);
    }
    private String afterSale(String sub,long amount) {
        String as="A"+UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,"
                + "refund_minor,approved_minor,reason,created_at,updated_at)"
                + " VALUES(?,?,?,'USER','MERCHANT','REFUND_ONLY','REFUNDING',?,?,'OTHER',NOW(),NOW())",as,sub,sub,amount,amount);
        return as;
    }
    private void cash(String no,Fixture f,long amount,String status) {
        jdbc.update("INSERT INTO stl_payment(payment_no,direction,order_no,sub_order_no,after_sale_no,user_no,"
                + "amount_minor,currency,status,pay_channel,created_at,updated_at)"
                + " VALUES(?,'REFUND',?,?,?,'USER',?,'CNY',?,'TEST',NOW(),NOW())",no,f.sub(),f.sub(),f.as(),amount,status);
    }
    private long refunded(String sub) {
        return jdbc.queryForObject("SELECT refunded_minor FROM ord_sub_order WHERE sub_order_no=?",Long.class,sub);
    }
    private record Fixture(String sub,String as,long item) { }
}
