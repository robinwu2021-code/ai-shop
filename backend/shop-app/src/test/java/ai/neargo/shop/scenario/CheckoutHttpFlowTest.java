package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.svc.InternalHttp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@TestPropertySource(properties = "shop.services.internal-token=checkout-http-token")
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutHttpFlowTest extends CheckoutFlowFixture {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    private MockMvc mvc;

    @BeforeEach
    void http() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void consumerReadsConfirmsAndPlacesThroughRealAuthenticatedHttp() throws Exception {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        String token =
                tokens.of(Realm.CONSUMER)
                        .issue(new TokenStore.SessionData(LoginUser.consumer(buyer, "buyer")));
        try {
            var read =
                    body(
                            mvc.perform(
                                            get("/mp/checkout/" + q.operation().operationNo())
                                                    .header("Authorization", "Bearer " + token))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(read.path("code").asInt()).isZero();
            assertThat(read.path("data").path("quoteNo").asString()).isEqualTo(q.quoteNo());
            String confirmBody =
                    json.writeValueAsString(
                            Map.of(
                                    "quoteNo",
                                    q.quoteNo(),
                                    "quoteRevision",
                                    q.quoteRevision(),
                                    "quoteHash",
                                    q.quoteHash()));
            var confirmed =
                    body(
                            mvc.perform(
                                            post("/mp/checkout/"
                                                            + q.operation().operationNo()
                                                            + "/confirmations")
                                                    .header("Authorization", "Bearer " + token)
                                                    .header(
                                                            "Idempotency-Key",
                                                            "http-confirm-" + sku)
                                                    .contentType("application/json")
                                                    .content(confirmBody))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(confirmed.path("code").asInt()).isZero();
            String no = confirmed.path("data").path("confirmationNo").asString();
            assertThat(no).startsWith("CC");
            var placed =
                    body(
                            mvc.perform(
                                            post("/mp/checkout/"
                                                            + q.operation().operationNo()
                                                            + "/orders")
                                                    .header("Authorization", "Bearer " + token)
                                                    .header("Idempotency-Key", "http-place-" + sku)
                                                    .contentType("application/json")
                                                    .content(
                                                            json.writeValueAsString(
                                                                    Map.of("confirmationNo", no))))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(placed.path("code").asInt()).isZero();
            assertThat(placed.path("data").path("phase").asString()).isEqualTo("CREATED");
        } finally {
            tokens.of(Realm.CONSUMER).revoke(token);
        }
    }

    @Test
    void internalPrepareIsVersionedBareAndDoesNotEchoIdentityCredentials() throws Exception {
        String response =
                mvc.perform(
                                post("/internal/trade/checkout/prepare")
                                        .header(InternalHttp.TOKEN_HEADER, "checkout-http-token")
                                        .contentType("application/json")
                                        .content(envelope(1, request(null))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var body = body(response);
        assertThat(body.path("protocolVersion").asInt()).isEqualTo(1);
        assertThat(body.has("code")).isFalse();
        assertThat(body.path("result").path("buyerNo").asString()).isEqualTo(buyer);
        assertThat(response).doesNotContain(reference, "customerRef", "requestKey");
    }

    @Test
    void internalExtraUnitIsRejectedInsteadOfImplicitCount() throws Exception {
        String content =
                envelope(1, request(null))
                        .replace("\"quantity\":3", "\"quantity\":3,\"uom\":\"EA\"");
        var response =
                mvc.perform(
                                post("/internal/trade/checkout/prepare")
                                        .header(InternalHttp.TOKEN_HEADER, "checkout-http-token")
                                        .contentType("application/json")
                                        .content(content))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_operation WHERE operation_no=?",
                                Long.class,
                                "op-" + sku))
                .isZero();
    }

    @Test
    void internalProtocolAndTransportCannotImpersonateCurrentBuyer() throws Exception {
        assertThat(
                        mvc.perform(
                                        post("/internal/trade/checkout/prepare")
                                                .contentType("application/json")
                                                .content(envelope(1, request(null))))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        assertThat(
                        mvc.perform(
                                        post("/internal/trade/checkout/prepare")
                                                .header(
                                                        InternalHttp.TOKEN_HEADER,
                                                        "checkout-http-token")
                                                .contentType("application/json")
                                                .content(envelope(2, request(null))))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
    }

    private String envelope(int protocol, Object request) {
        return json.writeValueAsString(
                Map.of(
                        "protocolVersion",
                        protocol,
                        "identity",
                        new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor),
                        "scope",
                        scope,
                        "request",
                        request));
    }

    private JsonNode body(String value) {
        assertThat(value).as("real HTTP JSON response").isNotBlank();
        return json.readTree(value);
    }

    @Test
    void unknownConsumerMoneyAndActorFieldsNeverBecomePurchaseAuthorization() throws Exception {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        String token =
                tokens.of(Realm.CONSUMER)
                        .issue(new TokenStore.SessionData(LoginUser.consumer(buyer, "buyer")));
        try {
            for (String extra : List.of("\"buyerNo\":\"other\"", "\"priceMinor\":1")) {
                String request =
                        json.writeValueAsString(
                                Map.of(
                                        "quoteNo",
                                        q.quoteNo(),
                                        "quoteRevision",
                                        1,
                                        "quoteHash",
                                        q.quoteHash()));
                request = request.substring(0, request.length() - 1) + "," + extra + "}";
                var response =
                        mvc.perform(
                                        post("/mp/checkout/"
                                                        + q.operation().operationNo()
                                                        + "/confirmations")
                                                .header("Authorization", "Bearer " + token)
                                                .header("Idempotency-Key", "unknown-" + sku)
                                                .contentType("application/json")
                                                .content(request))
                                .andReturn()
                                .getResponse();
                assertThat(body(response.getContentAsString()).path("code").asInt()).isNotZero();
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_checkout_confirmation WHERE"
                                            + " operation_no=?",
                                    Long.class,
                                    q.operation().operationNo()))
                    .isZero();
        } finally {
            tokens.of(Realm.CONSUMER).revoke(token);
        }
    }

    @Test
    void fractionalCountAndQuotedCountAreRejectedByActualInternalHttp() throws Exception {
        for (String quantity : List.of("3.5", "\"3\"")) {
            String content =
                    envelope(1, request(null))
                            .replace("\"quantity\":3", "\"quantity\":" + quantity);
            var response =
                    mvc.perform(
                                    post("/internal/trade/checkout/prepare")
                                            .header(
                                                    InternalHttp.TOKEN_HEADER,
                                                    "checkout-http-token")
                                            .contentType("application/json")
                                            .content(content))
                            .andReturn()
                            .getResponse();
            assertThat(response.getStatus())
                    .as("COUNT must be an actual integer JSON token: " + quantity)
                    .isEqualTo(400);
        }
    }

    @Test
    void foreignConsumerCannotReadConfirmOrReplayOrder() throws Exception {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        asBuyer(() -> checkout.place(place(q, c)));
        String foreign = buyer + "-other";
        jdbc.update(
                "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at) VALUES"
                        + " (?,'other','NORMAL',NOW(),NOW())",
                foreign);
        String token =
                tokens.of(Realm.CONSUMER)
                        .issue(new TokenStore.SessionData(LoginUser.consumer(foreign, "other")));
        try {
            var read =
                    body(
                            mvc.perform(
                                            get("/mp/checkout/" + q.operation().operationNo())
                                                    .header("Authorization", "Bearer " + token))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(read.path("code").asInt()).isNotZero();
            var confirmed =
                    body(
                            mvc.perform(
                                            post("/mp/checkout/"
                                                            + q.operation().operationNo()
                                                            + "/confirmations")
                                                    .header("Authorization", "Bearer " + token)
                                                    .header("Idempotency-Key", "confirm-" + sku)
                                                    .contentType("application/json")
                                                    .content(
                                                            json.writeValueAsString(
                                                                    Map.of(
                                                                            "quoteNo",
                                                                            q.quoteNo(),
                                                                            "quoteRevision",
                                                                            1,
                                                                            "quoteHash",
                                                                            q.quoteHash()))))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(confirmed.path("code").asInt()).isNotZero();
            var placed =
                    body(
                            mvc.perform(
                                            post("/mp/checkout/"
                                                            + q.operation().operationNo()
                                                            + "/orders")
                                                    .header("Authorization", "Bearer " + token)
                                                    .header("Idempotency-Key", "place-" + sku)
                                                    .contentType("application/json")
                                                    .content(
                                                            json.writeValueAsString(
                                                                    Map.of(
                                                                            "confirmationNo",
                                                                            c.confirmationNo()))))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(placed.path("code").asInt()).isNotZero();
        } finally {
            tokens.of(Realm.CONSUMER).revoke(token);
        }
    }

    @Test
    void actualConsumerReceiptHttpCannotCompleteManagedLegacyService() throws Exception {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        var created = asBuyer(() -> checkout.place(place(q, c)));
        jdbc.update("UPDATE ord_order SET status='PAID' WHERE order_no=?", created.orderNo());
        jdbc.update(
                "UPDATE ord_sub_order SET status='FULFILLING' WHERE sub_order_no=?",
                created.subOrderNo());
        jdbc.update(
                "INSERT INTO"
                        + " ord_status_log(sub_order_no,status,event,operator_type,label,at,created_at)"
                        + " VALUES (?,'FULFILLING','SHIPPED','MERCHANT','historical',?,NOW())",
                created.subOrderNo(),
                System.currentTimeMillis());
        String token =
                tokens.of(Realm.CONSUMER)
                        .issue(new TokenStore.SessionData(LoginUser.consumer(buyer, "buyer")));
        try {
            var response =
                    body(
                            mvc.perform(
                                            post("/mp/order/"
                                                            + created.subOrderNo()
                                                            + "/confirm-receipt")
                                                    .header("Authorization", "Bearer " + token))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(response.path("code").asInt()).isEqualTo(20018);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM ord_sub_order WHERE sub_order_no=?",
                                    String.class,
                                    created.subOrderNo()))
                    .isEqualTo("FULFILLING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT completed_at FROM ord_sub_order WHERE sub_order_no=?",
                                    Long.class,
                                    created.subOrderNo()))
                    .isNull();
        } finally {
            tokens.of(Realm.CONSUMER).revoke(token);
        }
    }
}
