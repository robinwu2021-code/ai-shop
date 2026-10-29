package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.dto.CreatePaymentCmd;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.pay.acquiring.service.PaymentService.StartPaymentCmd;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.OrdinaryPaymentInitiationService;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.settle.SettlePort;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/** A selected financial route must reach the channel unchanged and survive retries. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fiat-route-p1;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class FiatPaymentRouteFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService payments;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private PayMerchantBindingService bindings;
    @Autowired private InternalCallers callers;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean(name = "channelGateway") private ChannelGateway gateway;
    @MockitoSpyBean private OrdinaryPaymentInitiationService initiation;
    private PayOrder order;
    private PayCaller caller;
    private long merchantId;
    private long productId;

    @BeforeEach
    void world() {
        jdbc.update("UPDATE sys_market SET enabled=1 WHERE market='CN'");
        jdbc.update("UPDATE sys_pay_channel SET enabled=1 WHERE pay_channel='TEST'");
        jdbc.update("UPDATE sys_pay_channel_market SET status='ENABLED' WHERE pay_channel='TEST' AND market='CN'");
        jdbc.update("UPDATE pay_channel_product SET pay_channel='TEST',access_mode='PLATFORM_SUB',status='ENABLED',"
                + " allowed_funds_paths='DIRECT,AGGREGATED'"
                + " WHERE product_code='TEST_ONLINE'");
        productId = jdbc.queryForObject("SELECT id FROM pay_channel_product WHERE product_code='TEST_ONLINE'",
                Long.class);
        jdbc.update("UPDATE pay_channel_product_market SET status='ENABLED', currency='CNY',"
                + " single_min_minor=1,single_max_minor=0,amount_scale=2"
                + " WHERE pay_channel_product_id=? AND market='CN'",
                productId);
        var store = OpenApiFlowTest.storeWithTestRoute(jdbc, bindings);
        merchantId = jdbc.queryForObject("SELECT pay_merchant_id FROM pay_merchant_binding"
                + " WHERE store_id=? AND status='ACTIVE' AND pay_channel='TEST'", Long.class, store.storeId());
        caller = callers.system(InternalCallers.MALL, store.storeId());
        String no = "FIAT-" + UUID.randomUUID();
        order = payments.create(caller, new CreatePaymentCmd(PaymentPort.MALL_ORDER, no, no, store.storeId(),
                "Store", "Entity", "CN", 1001, "CNY", "Goods", "ONLINE", Instant.now().plusSeconds(600),
                "AGGREGATED"));
    }

    private PaymentService.StartPaymentResult start() {
        return payments.start(caller, order.getPayOrderNo(), new StartPaymentCmd("TEST_PAY", null, null));
    }

    private Map<String, Object> frozen() {
        return jdbc.queryForMap("SELECT * FROM pay_order WHERE id=?", order.getId());
    }

    @Test
    void selectedRouteReachesTheChannelAndItsOriginalRequest() throws Exception {
        var result = start();
        var account = ArgumentCaptor.forClass(ChannelAccountContext.class);
        verify(gateway).execute(account.capture(), any(ChannelCommand.class));
        String merchantNo = jdbc.queryForObject("SELECT pay_merchant_no FROM pay_merchant WHERE id=?",
                String.class, merchantId);
        assertThat(account.getValue().payMerchantId()).isEqualTo(merchantId);
        assertThat(account.getValue().payMerchantNo()).isEqualTo(merchantNo);
        assertThat(account.getValue().market()).isEqualTo("CN");
        assertThat(account.getValue().subMerchantId()).isEqualTo("SUB-" + merchantNo);
        assertThat(account.getValue().keyVersion()).isZero();
        String snapshot = jdbc.queryForObject("SELECT payment_request_json FROM stl_payment WHERE out_trade_no=?",
                String.class, result.outTradeNo());
        var route = json.readTree(snapshot).get("acquiringRoute");
        assertThat(route).isNotNull();
        assertThat(route.get("payMerchantNo").asString()).isEqualTo(merchantNo);
        assertThat(jdbc.queryForMap("SELECT pay_merchant_no,sub_merchant_id FROM stl_payment WHERE out_trade_no=?",
                result.outTradeNo())).containsEntry("pay_merchant_no", merchantNo)
                .containsEntry("sub_merchant_id", "SUB-" + merchantNo);
    }

    @Test
    void currentAccountChangesCannotRewriteAnInFlightIntent() {
        var first = start();
        jdbc.update("UPDATE pay_merchant SET sub_merchant_id='NEW-SUB',sec_key_version=8 WHERE id=?", merchantId);
        var second = start();
        assertThat(second.outTradeNo()).isEqualTo(first.outTradeNo());
        assertThat(frozen()).containsEntry("sec_key_version", 0);
        assertThat(frozen().get("sub_merchant_id")).isNotEqualTo("NEW-SUB");
        var accounts = ArgumentCaptor.forClass(ChannelAccountContext.class);
        verify(gateway, times(2)).execute(accounts.capture(), any(ChannelCommand.class));
        assertThat(accounts.getAllValues()).allSatisfy(a -> {
            assertThat(a.keyVersion()).isZero();
            assertThat(a.subMerchantId()).isNotEqualTo("NEW-SUB");
        });
    }

    @Test
    void unknownOriginalRouteIsRejectedWithoutChangingParentOrExistingAttempt() {
        String out = ledger.open(new SettlePort.PaymentOpen(order.getPayOrderNo(), "OTHER", null,
                "TEST", 1001, "Goods"));
        assertThatThrownBy(this::start).isInstanceOf(BizException.class);
        assertThat(frozen().get("pay_merchant_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE pay_order_id=?", Long.class,
                order.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_no FROM stl_payment WHERE out_trade_no=?", String.class, out))
                .isEqualTo("OTHER");
        verify(gateway, never()).execute(any(), any());
    }

    @Test
    void preparationFailureRollsBackRouteAndAttemptTogether() {
        doThrow(new IllegalArgumentException("PAY_PAYER_IDENTITY_CONFLICT"))
                .when(initiation).prepare(any(), any());
        assertThatThrownBy(this::start).isInstanceOf(BizException.class);
        assertThat(frozen().get("pay_merchant_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE pay_order_id=?", Long.class,
                order.getId())).isZero();
        verify(gateway, never()).execute(any(), any());
    }

    @Test
    void realHandlerWithoutSigningAccountProofIsRejectedBeforeAnyAttempt() {
        bindings.unbind(order.getStoreId(), "TEST", "REVIEW");
        jdbc.update("UPDATE pay_merchant SET pay_channel='WECHAT',access_mode='MERCHANT_OWN' WHERE id=?", merchantId);
        jdbc.update("UPDATE pay_channel_product SET pay_channel='WECHAT',access_mode='MERCHANT_OWN' WHERE id=?",
                productId);
        jdbc.update("UPDATE sys_pay_channel SET enabled=1 WHERE pay_channel='WECHAT'");
        bindings.bind(order.getStoreId(), "WECHAT", merchantId, "REVIEW");
        assertThatThrownBy(this::start).isInstanceOf(BizException.class);
        assertThat(frozen().get("pay_merchant_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE pay_order_id=?", Long.class,
                order.getId())).isZero();
        verify(gateway, never()).execute(any(), any());
    }

    @Test
    void frozenIntentCannotBeDowngradedToALegacyRequest() {
        var sent = start();
        var original = ledger.paymentRequest(sent.outTradeNo()).orElseThrow();
        var stripped = new SettlePort.PaymentOpen(original.orderNo(), original.userNo(), original.entityNo(),
                original.payChannel(), original.amountMinor(), original.subject(), original.currency(),
                original.stores());
        assertThatThrownBy(() -> ledger.open(stripped)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PAY_REQUEST_SNAPSHOT_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE pay_order_id=?", Long.class,
                order.getId())).isEqualTo(1);
    }

    @Test
    void networkDispatchIsOutsideTheTransactionAndUncertaintyRetainsTheOriginalIntent() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new IllegalStateException("uncertain transport");
        }).when(gateway).execute(any(), any());
        assertThatThrownBy(this::start).isInstanceOf(IllegalStateException.class).hasMessage("uncertain transport");
        String original = jdbc.queryForObject("SELECT out_trade_no FROM stl_payment WHERE pay_order_id=?",
                String.class, order.getId());
        assertThat(frozen()).containsEntry("status", "AWAITING_PAYMENT").containsEntry("pay_merchant_id", merchantId);
        doCallRealMethod().when(gateway).execute(any(), any());
        assertThat(start().outTradeNo()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE pay_order_id=?", Long.class,
                order.getId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CURRENCY", "FUNDS_PATH", "PRODUCT_MARKET", "CHANNEL_MARKET", "MARKET",
            "MIN", "MAX", "SCALE"})
    void incompatibleRouteIsRejectedBeforePreparingOrCallingTheChannel(String change) {
        switch (change) {
            case "CURRENCY" -> jdbc.update("UPDATE pay_merchant SET currency='USD' WHERE id=?", merchantId);
            case "FUNDS_PATH" -> jdbc.update("UPDATE pay_channel_product SET allowed_funds_paths='DIRECT' WHERE id=?",
                    productId);
            case "PRODUCT_MARKET" -> jdbc.update("UPDATE pay_channel_product_market SET status='DISABLED'"
                    + " WHERE pay_channel_product_id=? AND market='CN'", productId);
            case "CHANNEL_MARKET" -> jdbc.update("UPDATE sys_pay_channel_market SET status='DISABLED'"
                    + " WHERE pay_channel='TEST' AND market='CN'");
            case "MARKET" -> jdbc.update("UPDATE sys_market SET enabled=0 WHERE market='CN'");
            case "MIN" -> jdbc.update("UPDATE pay_channel_product_market SET single_min_minor=2000"
                    + " WHERE pay_channel_product_id=? AND market='CN'", productId);
            case "MAX" -> jdbc.update("UPDATE pay_channel_product_market SET single_max_minor=1000"
                    + " WHERE pay_channel_product_id=? AND market='CN'", productId);
            case "SCALE" -> jdbc.update("UPDATE pay_channel_product_market SET amount_scale=0"
                    + " WHERE pay_channel_product_id=? AND market='CN'", productId);
            default -> throw new AssertionError(change);
        }
        assertThatThrownBy(this::start).isInstanceOf(BizException.class);
        assertThat(frozen().get("pay_merchant_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE pay_order_id=?", Long.class,
                order.getId())).isZero();
        verify(gateway, never()).execute(any(), any());
    }

    @Test
    void concurrentStartsShareOneOriginalAttempt() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> after(go));
        var second = CompletableFuture.supplyAsync(() -> after(go));
        go.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).outTradeNo())
                .isEqualTo(second.get(10, TimeUnit.SECONDS).outTradeNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payment WHERE pay_order_id=?", Long.class,
                order.getId())).isEqualTo(1);
    }

    private PaymentService.StartPaymentResult after(CountDownLatch go) {
        try {
            if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("start barrier");
            return start();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
