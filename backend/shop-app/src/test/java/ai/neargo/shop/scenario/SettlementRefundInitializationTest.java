package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import ai.neargo.shop.spi.trade.SettleSourcePort.SettleSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@ActiveProfiles("test")
class SettlementRefundInitializationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettleService service;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private SettlePort settle;

    private SettlementBasisFixtures fixture() { return new SettlementBasisFixtures(jdbc, service); }

    private SettleService.SettleInput input(String no) {
        var source = new SettleSource(no, "M0001", "PLATFORM", 300, 0, 0, null, 1,
                "ST-M0001", 0, 0, "WECHAT", "MP_WECHAT", 0, 0, "MERCHANT_SELF");
        return new SettleService.SettleInput(source, "THIRD_PARTY", "ORIGINAL-MCH", "ENTERPRISE",
                "DIRECT", "PLATFORM", "CN");
    }

    private void refund(String no, String afterSale, String status) {
        jdbc.update("insert into stl_payment(payment_no,direction,order_no,sub_order_no,after_sale_no,user_no,"
                + "pay_channel,pay_scene,amount_minor,currency,status,tenant_no,created_at,updated_at,version,deleted)"
                + " values(?,'REFUND',?,?,?,'INIT-BUYER','WECHAT','MP_WECHAT',100,'CNY',?,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)", "RF-" + no, no, no, afterSale, status);
    }

    @Test
    void successfulCashBeforeFirstBillReducesInitialDueExactlyOnce() {
        String no = fixture().payment();
        refund(no, "AS-" + no, "SUCCESS");
        assertThat(service.generateForOrder(no, List.of(input(no)))).isEqualTo(1);
        long original = (Long) fixture().basis(no).get("MERCHANT_GOODS_MINOR");
        assertThat(fixture().bill(no)).containsEntry("REFUNDED_GOODS_MINOR", 100L)
                .containsEntry("NET_MINOR", original - original / 3);
        assertThat(fixture().count("stl_bill_refund", no)).isEqualTo(1);
        assertThat(service.generateForOrder(no, List.of(input(no)))).isZero();
        assertThat(fixture().bill(no)).containsEntry("NET_MINOR", original - original / 3);
        assertThat(fixture().count("stl_bill_refund", no)).isEqualTo(1);
    }

    @Test
    void unknownCashFreightBreakdownMustNotBecomeAutomaticPayable() {
        String no = fixture().payment();
        refund(no, "AS-" + no, "SUCCESS");
        fixture().generate(no, "ORIGINAL-MCH");
        assertThat(fixture().bill(no)).containsEntry("STATUS", "MANUAL");
        assertThat(fixture().count("stl_bill_refund", no)).isZero();
        assertThat(fixture().basis(no)).containsEntry("GOODS_PAID_MINOR", 300L);
    }

    @Test
    void pendingCashOrMissingIdempotencyKeyRequiresReview() {
        for (String status : List.of("PENDING", "SUCCESS")) {
            String no = fixture().payment();
            refund(no, null, status);
            service.generateForOrder(no, List.of(input(no)));
            assertThat(fixture().bill(no)).containsEntry("STATUS", "MANUAL");
            assertThat(fixture().count("stl_bill_refund", no)).isZero();
        }
    }
    @Test
    void knownFreightBreakdownUsesSeparatePositionsAndImmutableBasis() {
        String no = fixture().payment();
        refund(no, "AS-" + no, "SUCCESS");
        jdbc.update("update stl_payment set refund_goods_minor=60,refund_freight_minor=40"
                + " where payment_no=?", "RF-" + no);
        fixture().generate(no, "ORIGINAL-MCH");
        assertThat(fixture().basis(no)).containsEntry("GOODS_PAID_MINOR", 300L)
                .containsEntry("FREIGHT_INCOME_MINOR", 50L).containsEntry("MERCHANT_GOODS_MINOR", 345L);
        assertThat(fixture().bill(no)).containsEntry("REFUNDED_GOODS_MINOR", 60L)
                .containsEntry("REFUNDED_FREIGHT_MINOR", 40L).containsEntry("NET_MINOR", 286L);
        assertThat(fixture().count("stl_bill_refund", no)).isEqualTo(1);
    }

    @Test
    void appliedKeyWithDifferentCashAmountLeavesVisibleReview() {
        String no = fixture().payment();
        refund(no, "AS-" + no, "SUCCESS");
        service.generateForOrder(no, List.of(input(no)));
        jdbc.update("update stl_payment set amount_minor=200 where payment_no=?", "RF-" + no);
        ledger.markRefundSettled("RF-" + no, "REPLAY");
        assertThat(fixture().bill(no)).containsEntry("STATUS", "MANUAL").containsEntry("REFUNDED_GOODS_MINOR", 100L);
        assertThat(fixture().count("stl_bill_refund", no)).isEqualTo(1);
    }

    @Test
    void duplicateSuccessfulAfterSaleKeysDoNotPartiallyApplyOrRollbackBillCreation() {
        String no = fixture().payment();
        refund(no, "AS-" + no, "SUCCESS");
        jdbc.update("insert into stl_payment(payment_no,direction,order_no,sub_order_no,after_sale_no,user_no,"
                + "pay_channel,pay_scene,amount_minor,currency,status,tenant_no,created_at,updated_at,version,deleted)"
                + " values(?,'REFUND',?,?,?,'INIT-BUYER','WECHAT','MP_WECHAT',100,'CNY','SUCCESS','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)", "RF2-" + no, no, no, "AS-" + no);
        assertThatCode(() -> service.generateForOrder(no, List.of(input(no)))).doesNotThrowAnyException();
        assertThat(fixture().bill(no)).containsEntry("STATUS", "MANUAL").containsEntry("REFUNDED_GOODS_MINOR", 0L);
        assertThat(fixture().count("stl_bill_refund", no)).isZero();
    }

    @Test
    void realLedgerRefundReceiptPreservesOriginalContextAndPreAdjustedDue() {
        String no = fixture().payment();
        jdbc.update("update stl_payment set out_trade_no=?,trade_no=? where payment_no=?",
                no, "TX-" + no, "PAY-" + no);
        service.generateForOrder(no, List.of(input(no)));
        assertThat(settle.reverseSplit(new SplitReversal(no, "AS-" + no, 100, 0, 300))).isTrue();
        String paymentNo = ledger.refund(no, no, "AS-" + no, 100, "actual partial refund");
        ledger.markRefundSettled(paymentNo, "CFM-" + no);
        long original = (Long) fixture().basis(no).get("MERCHANT_GOODS_MINOR");
        assertThat(fixture().bill(no)).containsEntry("STATUS", "PENDING")
                .containsEntry("NET_MINOR", original - original / 3).containsEntry("REFUNDED_GOODS_MINOR", 100L);
        assertThat(jdbc.queryForMap("select pay_scene,wx_appid from stl_payment where payment_no=?", paymentNo))
                .containsEntry("PAY_SCENE", "MP_WECHAT").containsEntry("WX_APPID", "ORIGINAL-APP");
        long remainingFees = (Long) fixture().bill(no).get("COMMISSION_MINOR")
                + (Long) fixture().bill(no).get("SERVICE_FEE_MINOR")
                + (Long) fixture().bill(no).get("POINTS_FEE_MINOR");
        assertThat(fixture().bill(no)).containsEntry("SPLIT_AMOUNT_MINOR", remainingFees);
        ledger.markRefundSettled(paymentNo, "REPLAY");
        assertThat(fixture().count("stl_bill_refund", no)).isEqualTo(1);
        assertThat(fixture().bill(no)).containsEntry("STATUS", "PENDING")
                .containsEntry("NET_MINOR", original - original / 3);
    }

}
