package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.paybridge.ShippingUploadSnapshot;
import ai.neargo.shop.paybridge.WxShippingUploadResolver;
import ai.neargo.shop.paybridge.WxShippingUploadService;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.support.OrderLogisticsFixture;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.mapper.ShippingUploadMapper;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** A confirmed sibling refund refreshes the same payment upload, even after a successful partial upload. */
@SpringBootTest
@ActiveProfiles("test")
class ShippingRefundSnapshotFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrderShippingService shipping;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private OutboxEventBus events;
    @Autowired private WxShippingUploadService uploads;
    @Autowired private WxShippingUploadResolver resolver;
    @Autowired private ShippingUploadMapper queue;
    @Autowired private ObjectMapper json;

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUCCESS", "HARD_FAILURE"})
    void siblingRefundRefreshesOriginalQueueWithoutClearingChannelCorrectionLimit(String initial) {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, true);
        String sibling = sub + "R";
        String pay = "O-" + sub;
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no,order_no,user_no,entity_no,store_no,fulfillment,"
                + "status,pay_amount,created_at,updated_at) VALUES (?,?,'LG-BUYER','LG-MERCHANT','LG-STORE-2',"
                + "'EXPRESS','WAIT_FULFILL',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sibling, pay);
        String number = "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT);
        shipping.ship(sub, new ShipCommand(List.of(), "STO", number), new OrderActor("LG-MERCHANT", null));
        drain();
        TrdShippingUpload before = row(pay);
        assertThat(ShippingUploadSnapshot.parse(before.getSourceSnapshot(), json).allDelivered()).isFalse();
        if ("SUCCESS".equals(initial)) { uploads.upload(before, "物流商品", number, "STO", "LG-OPENID"); }
        if ("HARD_FAILURE".equals(initial)) {
            jdbc.update("UPDATE trd_shipping_upload SET status='FAILED',err_code=10060002,attempts=8 WHERE id=?",
                    before.getId());
        }
        // This sibling has no merchandise or cash: persist its actual zero-value refund closure.
        // Both coupon consumers validate the original case/child/buyer before deciding no entitlement is due.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_item WHERE sub_order_no=?", Long.class, sibling))
                .isZero();
        String afterSaleNo = "RF-" + sibling;
        assertThat(jdbc.update("INSERT INTO ord_after_sale (after_sale_no,sub_order_no,order_no,user_no,entity_no,"
                + "type,status,reason,refund_minor,approved_minor,refund_mode,refunded_at,tenant_no,created_at,updated_at) "
                + "SELECT ?,sub_order_no,order_no,user_no,entity_no,'REFUND_ONLY','REFUNDED','NOT_WANTED',"
                + "0,0,'CASH',?,tenant_no,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM ord_sub_order "
                + "WHERE sub_order_no=? AND pay_amount=0", afterSaleNo, System.currentTimeMillis(), sibling))
                .isEqualTo(1);
        jdbc.update("UPDATE ord_sub_order SET status='REFUNDED',refunded_minor=0,refund_progress='FULL' "
                + "WHERE sub_order_no=?", sibling);
        events.publish(new OrderEvents.AfterSaleRefunded(afterSaleNo, sibling, "LG-BUYER", 0L));
        drain();
        TrdShippingUpload refreshed = row(pay);
        assertThat(refreshed.getId()).isEqualTo(before.getId());
        assertThat(refreshed.getOutTradeNo()).isEqualTo(before.getOutTradeNo());
        assertThat(refreshed.getVersion()).isGreaterThan(before.getVersion());
        var snapshot = ShippingUploadSnapshot.parse(refreshed.getSourceSnapshot(), json);
        assertThat(snapshot.allDelivered()).isTrue();
        assertThat(snapshot.deliveryMode()).isEqualTo(1);
        assertThat(snapshot.shipments()).hasSize(1);
        assertThat(snapshot.shipments().getFirst().trackingNo()).isEqualTo(number);
        assertThat(refreshed.getStatus()).isEqualTo("HARD_FAILURE".equals(initial) ? "FAILED" : "PENDING");
        if ("HARD_FAILURE".equals(initial)) { assertThat(refreshed.getErrCode()).isEqualTo(10060002); }
        else { assertThat(resolver.resolve(refreshed)).isNotNull(); }
    }

    private TrdShippingUpload row(String pay) {
        return queue.selectOne(Wrappers.<TrdShippingUpload>lambdaQuery().eq(TrdShippingUpload::getOrderNo, pay));
    }

    private void drain() {
        for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); }
    }
}
