package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.impl.PaymentLedgerServiceImpl;
import ai.neargo.shop.pay.settle.refund.BillRefundJournal;
import ai.neargo.shop.pay.settle.refund.DefaultSettlementRefundPolicy;
import ai.neargo.shop.pay.settle.refund.FinancialClosurePolicy;
import ai.neargo.shop.pay.settle.refund.PaymentSettlementSerialPoint;
import ai.neargo.shop.pay.settle.refund.PreSplitAdjuster;
import ai.neargo.shop.pay.settle.refund.RefundReceiptMutation;
import ai.neargo.shop.pay.settle.refund.SettlementRefundInitializer;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 明确持旧RR快照重放成功回执；不可把已初始化账单改为MANUAL或重复扣减。 */
@EnabledIfSystemProperty(named = "order.basis.mariadb.url", matches = "jdbc:mysql:.*")
class RefundReceiptMariaDbReplayTest {
    @Test
    void successReceiptWithOldOuterSnapshotCannotDamageNewlyInitializedBill() throws Exception {
        var f = RefundBasisMariaDbReplayTest.fixture();
        var payments = f.session().getMapper(PaymentMapper.class);
        var refunds = f.session().getMapper(BillRefundMapper.class);
        var serial = new PaymentSettlementSerialPoint(payments);
        var adjust = new PreSplitAdjuster(refunds, new BillRefundJournal(refunds), new FinancialClosurePolicy());
        var init = new SettlementRefundInitializer(f.store(), payments, f.bills(), refunds,
                new DefaultSettlementRefundPolicy(), adjust);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(f.tx().getTransactionManager());
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var receiptProxy = new ProxyFactory(new RefundReceiptMutation(payments, f.bills(), serial, init));
        receiptProxy.setProxyTargetClass(true);
        receiptProxy.addAdvice(advice);
        var target = new PaymentLedgerServiceImpl(payments, serial, (RefundReceiptMutation) receiptProxy.getProxy());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(advice);
        var ledger = (PaymentLedgerService) proxy.getProxy();
        payments(f);
        var rr = new TransactionTemplate(f.tx().getTransactionManager());
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var rc = new TransactionTemplate(f.tx().getTransactionManager());
        rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var generating = new CountDownLatch(1);
        var oldSnapshot = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var generation = pool.submit(() -> {
                rc.executeWithoutResult(tx -> {
                    serial.lock("RECEIPT"); generating.countDown(); await(oldSnapshot);
                    createBill(f, init);
                });
                committed.countDown();
            });
            var receipt = pool.submit(() -> {
                await(generating);
                rr.executeWithoutResult(tx -> {
                    assertThat(f.jdbc().queryForObject("select status from stl_payment where payment_no='RF-RECEIPT'",
                            String.class)).isEqualTo("SUCCESS");
                    oldSnapshot.countDown(); await(committed);
                    ledger.markRefundSettled("RF-RECEIPT", "REPLAY");
                });
            });
            assertThatCode(() -> generation.get(20, TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThatCode(() -> receipt.get(20, TimeUnit.SECONDS)).doesNotThrowAnyException();
        }
        assertThat(f.jdbc().queryForMap("select net_minor,status,refunded_goods_minor from stl_bill"
                + " where sub_order_no='RECEIPT'")).containsEntry("net_minor", 180L)
                .containsEntry("status", "PENDING").containsEntry("refunded_goods_minor", 100L);
        assertThat(f.jdbc().queryForObject("select count(*) from stl_bill_refund where sub_order_no='RECEIPT'",
                Long.class)).isEqualTo(1);
    }

    private static void payments(RefundBasisMariaDbReplayTest.Fixture f) {
        f.jdbc().update("insert into stl_payment(payment_no,direction,order_no,user_no,pay_channel,pay_scene,"
                + "currency,amount_minor,status,wx_appid,tenant_no,created_at,updated_at) values('PAY-RECEIPT','PAY',"
                + "'RECEIPT','BUYER','WECHAT','MP_WECHAT','CNY',300,'SUCCESS','ORIGINAL-APP','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        f.jdbc().update("insert into stl_payment(payment_no,direction,order_no,sub_order_no,after_sale_no,user_no,"
                + "pay_channel,pay_scene,currency,amount_minor,status,tenant_no,created_at,updated_at) values("
                + "'RF-RECEIPT','REFUND','RECEIPT','RECEIPT','AS-RECEIPT','BUYER','WECHAT','MP_WECHAT','CNY',100,"
                + "'SUCCESS','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }

    private static void createBill(RefundBasisMariaDbReplayTest.Fixture f, SettlementRefundInitializer init) {
        f.jdbc().update("insert into stl_bill(settle_no,sub_order_no,order_no,entity_no,gross_minor,commission_minor,"
                + "net_minor,status,pay_channel,pay_scene,currency,pay_merchant_no,funds_mode,fee_bearer,tenant_no,"
                + "created_at,updated_at) values('STL-RECEIPT','RECEIPT','RECEIPT','M1',300,30,270,'PENDING',"
                + "'WECHAT','MP_WECHAT','CNY','ORIGINAL-MCH','DIRECT','PLATFORM','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        StlBill bill = f.bills().selectOne(Wrappers.<StlBill>lambdaQuery()
                .eq(StlBill::getSubOrderNo, "RECEIPT"));
        f.store().recordCreated(bill, 300);
        init.reconcile(bill);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
