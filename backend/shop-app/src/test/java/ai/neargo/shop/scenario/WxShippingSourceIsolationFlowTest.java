package ai.neargo.shop.scenario;

import ai.neargo.shop.event.OutboxDispatcher;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.paybridge.WxShippingUploadResolver;
import ai.neargo.shop.paybridge.WxShippingUploadService;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.support.OrderLogisticsFixture;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.order.mapper.ShippingUploadMapper;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.service.OrderShippingService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Payment-scoped upload retries must preserve every exact source and reject late old-send success. */
@SpringBootTest
@ActiveProfiles("test")
class WxShippingSourceIsolationFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private OrderShippingService shipping;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private WxShippingUploadService uploads;
    @Autowired private WxShippingUploadResolver resolver;
    @Autowired private ShippingUploadMapper queue;
    @Autowired private WxShippingPort original;
    @Autowired private ObjectMapper json;

    @Test
    void correctionRequeuesSamePaymentRowWithActualNewSource() {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, true);
        ship(sub, "A");
        drain();
        TrdShippingUpload first = row(sub);
        uploads.upload(first, "物流商品", number(sub, "A"), "STO", "LG-OPENID");
        String replacement = ship(sub, "B");
        drain();
        TrdShippingUpload current = row(sub);
        assertThat(current.getId()).isEqualTo(first.getId());
        assertThat(current.getStatus()).isEqualTo("PENDING");
        assertThat(json.valueToTree(current).path("sourceSnapshot").asString(""))
                .contains(replacement).contains(number(sub, "B")).doesNotContain(number(sub, "A"));
    }

    @Test
    void lateOldSendCannotMarkReplacementUploaded() throws Exception {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, true);
        ship(sub, "C");
        drain();
        TrdShippingUpload old = row(sub);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        WxShippingPort blocked = new WxShippingPort() {
            public boolean enabled() { return true; }
            public Result upload(Command command) {
                entered.countDown();
                try {
                    if (!released.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("release timeout"); }
                } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return Result.ok();
            }
        };
        ReflectionTestUtils.setField(uploads, "shipping", blocked);
        CompletableFuture<Boolean> sending = CompletableFuture.supplyAsync(() ->
                uploads.upload(old, "物流商品", number(sub, "C"), "STO", "LG-OPENID"));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            ship(sub, "D");
            drain();
            released.countDown();
            sending.get(10, TimeUnit.SECONDS);
            drain();
            assertThat(row(sub).getStatus()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT wx_uploaded_at FROM lgs_waybill WHERE biz_ref=?",
                    Long.class, sub)).isNull();
        } finally {
            released.countDown();
            sending.get(10, TimeUnit.SECONDS);
            ReflectionTestUtils.setField(uploads, "shipping", original);
        }
    }

    @Test
    void paymentWithTwoStoresResolvesEveryCurrentPackage() {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, true);
        String sibling = sub + "S";
        jdbc.update("UPDATE ord_sub_order SET pay_amount=500 WHERE sub_order_no=?", sub);
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no,order_no,user_no,entity_no,store_no,fulfillment,"
                + "status,pay_amount,created_at,updated_at) VALUES (?,?,'LG-BUYER','LG-MERCHANT','LG-STORE-2',"
                + "'EXPRESS','WAIT_FULFILL',500,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sibling, "O-" + sub);
        jdbc.update("INSERT INTO ord_item (sub_order_no,order_no,goods_no,sku_no,title,qty,created_at,updated_at) "
                + "VALUES (?,?,'LG-GOODS','LG-SKU','另一门店商品',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sibling, "O-" + sub);
        ship(sub, "E");
        ship(sibling, "F");
        drain();
        var material = json.valueToTree(resolver.resolve(row(sub)));
        assertThat(material.path("shipments").size()).isEqualTo(2);
        assertThat(material.path("shipments").toString()).contains(number(sub, "E"), number(sibling, "F"));
    }

    @ParameterizedTest
    @ValueSource(ints = {10060002, 10060003})
    void furtherCorrectionCannotClearKnownPaymentUploadExhaustion(int code) {
        String sub = OrderLogisticsFixture.seed(jdbc, ledger, true);
        ship(sub, "G");
        drain();
        TrdShippingUpload first = row(sub);
        WxShippingPort exhausted = new WxShippingPort() {
            public boolean enabled() { return true; }
            public Result upload(Command command) { return Result.fatal(code, "原支付发货已拒绝继续录入"); }
        };
        ReflectionTestUtils.setField(uploads, "shipping", exhausted);
        try { assertThat(uploads.upload(first, "物流商品", number(sub, "G"), "STO", "LG-OPENID")).isTrue(); }
        finally { ReflectionTestUtils.setField(uploads, "shipping", original); }
        assertThat(row(sub).getStatus()).isEqualTo("FAILED");
        String replacement = ship(sub, "H");
        drain();
        TrdShippingUpload blocked = row(sub);
        assertThat(blocked.getId()).isEqualTo(first.getId());
        assertThat(blocked.getStatus()).isEqualTo("FAILED");
        assertThat(blocked.getErrCode()).isEqualTo(code);
        assertThat(blocked.getErrMsg()).contains("拒绝继续录入");
        assertThat(blocked.getSourceSnapshot()).contains(replacement).contains(number(sub, "H"));
    }

    private TrdShippingUpload row(String sub) {
        return queue.selectOne(Wrappers.<TrdShippingUpload>lambdaQuery()
                .eq(TrdShippingUpload::getOrderNo, "O-" + sub).last("LIMIT 1"));
    }
    private String ship(String sub, String suffix) {
        return shipping.ship(sub, new ShipCommand(List.of(), "STO", number(sub, suffix)),
                new OrderActor("LG-MERCHANT", null)).shipmentId();
    }
    private String number(String sub, String suffix) {
        return "STO" + sub.substring(2, 22).toUpperCase(Locale.ROOT) + suffix;
    }
    private void drain() {
        for (int i = 0; i < 8; i++) { dispatcher.dispatchPending(); }
    }
}
