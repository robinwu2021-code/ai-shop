package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.support.RefundScenarioPayments;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.paybridge.EntitlementOrderUseDecisionConsumer;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.EntitlementOrderUseService;
import ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy;
import ai.neargo.shop.trade.service.OrderService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"shop.core.entitlement.authorization.enabled=true", "shop.pay.entitlement.use.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:coupon-public;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementCheckoutHttpFlowTest {
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenStores tokens;
    @Autowired OtpStore otp;
    @Autowired EntitlementOrderUseDecisionConsumer consumer;
    @Autowired EntitlementOrderUseService orderUses;
    @Autowired OrderService orderService;
    @Autowired PaymentLedgerService ledger;
    @Autowired TestPayGateway paymentChannel;
    @MockitoSpyBean EntitlementUsePort pay;
    private String id() { return UUID.randomUUID().toString(); }
    private String phone() {
        return "125" + String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000));
    }
    private JsonNode request(MockMvc mvc, String path, String token, Object body) throws Exception {
        return json.readTree(mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString());
    }
    @ParameterizedTest @ValueSource(strings = {"NORMAL", "LOST_REPLY", "OUTBOX_FAILURE"})
    void trustedQuoteConfirmAndOriginalEventRecoverWithoutDoubleDiscount(String mode) throws Exception {
        boolean lostReply = "LOST_REPLY".equals(mode);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String token = TestLogin.consumer(mvc, json, otp, phone());
        String user = tokens.of(Realm.CONSUMER).get(token).orElseThrow().user().userNo();
        String other = TestLogin.consumer(mvc, json, otp, phone());
        String entity = id(), store = id(), order = id(), sub = id(), instance = id();
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'owner','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, user);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        jdbc.update("INSERT INTO ord_order(order_no,user_no,status,pay_amount,goods_amount,freight_amount,"
                + "discount_amount,currency,tenant_no,created_at,updated_at) VALUES (?,?,'WAIT_PAY',900,1000,0,100,"
                + "'CNY','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order, user);
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                + "goods_amount,freight_amount,discount_amount,pay_amount,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,'WAIT_PAY','SERVICE',1000,0,100,900,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sub, order, user, entity, store);
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,qty,price,amount,paid_share_minor,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,'G1','SKU1','Care',1,1000,1000,900,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, order);
        var rule = new EntitlementDefinition("Care coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 100L,
                null, null, 0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of("TARGETED"));
        var now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,"
                + "entity_no,kind,status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,"
                + "effective_from,effective_until,activation_at,tenant_no,created_at,updated_at,created_by,updated_by) "
                + "VALUES (?,'V1',?,?,?,?,'ORDER_COUPON','ACTIVE',?,1,1,1,0,1,?,?,?,'MAIN',?,?,'TEST','TEST')",
                instance, id(), id().replace("-", "") + "a".repeat(32), user, entity, json.writeValueAsString(rule),
                now.minusDays(1), now.plusDays(30), now, now, now);
        var q = Map.of("orderDraftRef", sub, "orderRevision", 0, "selectedInstanceNos", List.of(instance));
        assertThat(request(mvc, "/mp/entitlement-quotes", other, q).path("code").asInt()).isNotZero();
        assertThat(request(mvc, "/mp/entitlement-quotes", token,
                Map.of("orderDraftRef", sub, "orderRevision", 9, "selectedInstanceNos", List.of(instance)))
                .path("code").asInt()).isNotZero();
        var quoted = request(mvc, "/mp/entitlement-quotes", token, q);
        assertThat(quoted.has("code")).isTrue();
        assertThat(quoted.path("code").asInt()).isZero();
        assertThat(quoted.path("data").path("discountMinor").asLong()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
        var view = quoted.path("data");
        var c = Map.of("orderRevision", 0, "quoteRef", view.path("quoteRef").asString(), "selectionDigest",
                view.path("selectionDigest").asString(), "requestKey", id());
        if ("OUTBOX_FAILURE".equals(mode)) {
            String expected = "CU" + EntitlementServiceSnapshotPolicy.hash(view.path("quoteRef").asString())
                    .substring(0, 60);
            jdbc.execute("ALTER TABLE sys_outbox ADD CONSTRAINT reject_checkout_prepare CHECK (aggregate_id<>'"
                    + expected + "')");
            try {
                assertThat(request(mvc, "/mp/orders/" + order + "/entitlement-authorizations", token, c)
                        .path("code").asInt()).isNotZero();
            } finally { jdbc.execute("ALTER TABLE sys_outbox DROP CONSTRAINT reject_checkout_prepare"); }
            assertThat(jdbc.queryForObject("SELECT status FROM ord_ent_authorization WHERE quote_ref=?", String.class,
                    view.path("quoteRef").asString())).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_ent_order_use WHERE operation_no=?", Integer.class,
                    expected)).isZero();
        }
        if (lostReply) doThrow(BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE)).doCallRealMethod()
                .when(pay).prepare(any(), any());
        var confirmation = request(mvc, "/mp/orders/" + order + "/entitlement-authorizations", token, c);
        assertThat(confirmation.path("code").asInt()).isZero();
        String op = confirmation.path("data").path("operationNo").asString();
        assertThat(op).isNotBlank();
        if (lostReply) {
            assertThat(confirmation.path("data").path("useStatus").asString()).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("SELECT status FROM ord_ent_order_use WHERE operation_no=?",
                    String.class, op)).isEqualTo("PENDING");
            assertThatThrownBy(() -> orderUses.sealForPayment("MAIN", order))
                    .isInstanceOf(BizException.class);
            var prepared = new SysOutbox();
            prepared.setEventType("ENTITLEMENT_ORDER_USE_PREPARED");
            prepared.setPayload(jdbc.queryForObject("SELECT payload FROM sys_outbox WHERE aggregate_id=? "
                    + "AND event_type='ENTITLEMENT_ORDER_USE_PREPARED'", String.class, op));
            consumer.consume(prepared);
        }
        var repeated = request(mvc, "/mp/orders/" + order + "/entitlement-authorizations", token, c).path("data");
        assertThat(repeated.path("operationNo").asString()).isEqualTo(op);
        assertThat(repeated.path("useStatus").asString()).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT pay_amount FROM ord_order WHERE order_no=?", Long.class, order))
                .isEqualTo(800);
        assertThat(orderUses.sealForPayment("MAIN", order).getPayAmount()).isEqualTo(800);
        String trade = RefundScenarioPayments.receivedForOrder(jdbc, ledger, paymentChannel, order);
        orderService.markPaid(order, "TEST", trade);
        var decided = new SysOutbox(); decided.setEventType("ENTITLEMENT_ORDER_USE_DECIDED");
        decided.setPayload(jdbc.queryForObject("SELECT payload FROM sys_outbox WHERE aggregate_id=? "
                + "AND event_type='ENTITLEMENT_ORDER_USE_DECIDED'", String.class, op));
        consumer.consume(decided); consumer.consume(decided);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage WHERE operation_no=?", Integer.class, op))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
    }
}
