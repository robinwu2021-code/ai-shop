package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercise the production request and database claim, not a fake idempotency cache. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.test.url:jdbc:h2:mem:order-create-cash;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
class OrderCreateCashWiringFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void foreignBuyerCannotReplay() throws Exception {
        String first = buyer("13107020001");
        String second = buyer("13107020002");
        String key = key();
        JsonNode created = create(first, key, command(1));
        assertThat(created.path("code").asInt()).isZero();
        JsonNode forbidden = create(second, key, command(1));
        assertThat(forbidden.path("code").asInt()).isEqualTo(10404);
        assertThat(forbidden.path("data").isNull() || forbidden.path("data").isMissingNode()).isTrue();
    }

    @Test
    void sameKeyDifferentPayloadRejected() throws Exception {
        String token = buyer("13107020003");
        String key = key();
        JsonNode first = create(token, key, command(1));
        assertThat(first.path("code").asInt()).isZero();
        assertThat(create(token, key, command(2)).path("code").asInt()).isEqualTo(10409);
        String payNo = first.path("data").path("payOrderNo").asString();
        assertThat(jdbc.queryForObject("select sum(qty) from ord_item where order_no=?", Integer.class, payNo))
                .isEqualTo(1);
    }

    @Test
    void sameKeyRaceCreatesOneReservation() throws Exception {
        String token = buyer("13107020004");
        String key = key();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return create(token, key, command(1));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return create(token, key, command(1));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            JsonNode a = first.get(30, TimeUnit.SECONDS);
            JsonNode b = second.get(30, TimeUnit.SECONDS);
            assertThat(a.path("code").asInt()).isZero();
            assertThat(b.path("code").asInt()).isZero();
            String payNo = a.path("data").path("payOrderNo").asString();
            assertThat(b.path("data").path("payOrderNo").asString()).isEqualTo(payNo);
            assertThat(jdbc.queryForObject("select count(*) from ord_sub_order where order_no=?", Long.class, payNo))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_lock where lock_no=?", Long.class, payNo))
                    .isEqualTo(1L);
        }
    }

    @Test
    void legacyCachedResponseReplaysOriginalPayment() throws Exception {
        String token = buyer("13107020005");
        JsonNode original = create(token, key(), command(1));
        String payNo = original.path("data").path("payOrderNo").asString();
        String owner = jdbc.queryForObject("select user_no from ord_order where order_no=?", String.class, payNo);
        String legacyKey = key();
        jdbc.update("insert into sys_idempotent (idem_key,endpoint,user_no,result_json,created_at,expire_at)"
                        + " values (?, 'POST /mp/order', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                legacyKey, owner, json.writeValueAsString(original.path("data")));
        JsonNode replay = create(token, legacyKey, command(1));
        assertThat(replay.path("code").asInt()).isZero();
        assertThat(replay.path("data").path("payOrderNo").asString()).isEqualTo(payNo);
    }

    @Test
    void equivalentMapOrderingReplaysSamePayment() throws Exception {
        String token = buyer("13107020006");
        String key = key();
        String input = command(1);
        String a = input.substring(0, input.length() - 1)
                + ",\"storeChoices\":[{\"merchantNo\":\"M0001\",\"storeNo\":\"ST-M0001\"},"
                + "{\"merchantNo\":\"M0002\",\"storeNo\":\"ST-M0002\"}]}";
        String b = input.substring(0, input.length() - 1)
                + ",\"storeChoices\":[{\"merchantNo\":\"M0002\",\"storeNo\":\"ST-M0002\"},"
                + "{\"merchantNo\":\"M0001\",\"storeNo\":\"ST-M0001\"}]}";
        JsonNode first = create(token, key, a);
        JsonNode second = create(token, key, b);
        assertThat(first.path("code").asInt()).isZero();
        assertThat(second.path("code").asInt()).isZero();
        assertThat(second.path("data").path("payOrderNo").asString())
                .isEqualTo(first.path("data").path("payOrderNo").asString());
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String buyer(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otp, phone);
    }

    private JsonNode create(String token, String key, String command) throws Exception {
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(command))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String command(int qty) {
        return "{\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":" + qty
                + "}],\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"usePoints\":0}";
    }
}
