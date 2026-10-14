package ai.neargo.shop.scenario;

import ai.neargo.shop.inventory.service.OpenApiCredentialService;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.StockQueryService;
import ai.neargo.shop.inventory.support.InvEnums;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开放接口（{@code /open/v1/**}）。**这一面是「可独立交付」那条线的门面** ——
 * 客户那边的 ERP 就是照它对接的。
 *
 * <p>此前<b>零测试</b>：三个口从没被起过，也没人验过它们真的能鉴权。
 * 而这一面比内部接口更需要钉住 —— 内部接口改坏了自己人当天就发现，
 * 开放接口改坏了是对方的 ERP 在某个凌晨开始对不上账。
 *
 * <p>它带 {@code @Profile("openapi")}，与 api/ops/worker 互斥：一个 jar 四种起法。
 * 所以这里显式多开一个 profile —— 不开的话整类是「端点不存在」的绿。
 */
@SpringBootTest
@ActiveProfiles({"test", "openapi"})
class InventoryOpenApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private InventoryAclService acl;
    @Autowired
    private InboundService inbound;
    @Autowired
    private StockQueryService query;
    @Autowired
    private OpenApiCredentialService credentials;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 物料与结存：ERP 靠 barcode / 货号对上自己的货")
    void itemsCarryTheKeysErpMatchesOn() throws Exception {
        Cred c = cred("read");

        JsonNode rows = ok(get("/open/v1/items?size=50"), c);
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.isEmpty()).as("种子里入过货").isFalse();

        JsonNode r = rows.get(0);
        for (String f : List.of("itemId", "name", "specText", "baseUom",
                "onHand", "reserved", "available", "flags")) {
            assertThat(r.has(f)).as("对接方读 %s", f).isTrue();
        }
    }

    @Test
    @DisplayName("★★★ 流水增量的游标是 id 不是时间 —— 时钟回拨会让时间游标漏行且不报错")
    void ledgerCursorIsIdBased() throws Exception {
        Cred c = cred("read");

        JsonNode first = ok(get("/open/v1/stock-ledger?size=1"), c);
        assertThat(first.has("entries")).isTrue();
        assertThat(first.has("nextCursor")).as("增量拉取靠它").isTrue();

        long firstId = first.get("entries").get(0).get("id").asLong();
        JsonNode next = ok(get("/open/v1/stock-ledger?since=" + firstId + "&size=10"), c);
        for (JsonNode e : next.get("entries")) {
            assertThat(e.get("id").asLong())
                    .as("since 之后的行 id 必须严格小于它 —— 重复下发会让对方重复入账")
                    .isLessThan(firstId);
        }
    }

    @Test
    @DisplayName("★★★ 库存同步落的是一张盘点单，不是直接改数")
    void syncLandsAsAStockCount() throws Exception {
        Cred c = cred("read,stock:sync");
        int before = onHand(c);

        String body = """
                {"requestId":"REQ-%d","locationId":"%s","lines":[{"itemId":"%s","qty":3}]}
                """.formatted(SEQ.incrementAndGet(), c.location, c.itemId);
        JsonNode res = ok(post("/open/v1/stock:sync").content(body), c);
        assertThat(res.get("applied").asInt()).isEqualTo(1);

        assertThat(onHand(c)).as("同步之后实存等于对方推来的数").isEqualTo(3);
        assertThat(before).as("推之前不是 3，否则这条断言什么都没测到").isNotEqualTo(3);

        /*
         * **落成一张单**：外部推进来的量与商家自己盘出来的是同一件事（「实际有多少」），
         * 走同一个口，账上才分得清这一笔是谁改的。
         * 直接改余额的话，商家问「我的货怎么变了」时没有任何东西可以点开。
         */
        JsonNode ledger = ok(get("/open/v1/stock-ledger?size=5"), c);
        assertThat(ledger.get("entries").get(0).get("docNo").asString())
                .as("同步要留下一张单号，不能是一次无据可查的改数")
                .isNotBlank();
    }

    @Test
    @DisplayName("★★★ 四种鉴权失败**同一个错码** —— 分开报等于送对方一个 key 枚举器")
    void allFourAuthFailuresLookTheSame() throws Exception {
        Cred good = cred("read");

        int noKey = codeOf(get("/open/v1/items"), null, null);
        int badKey = codeOf(get("/open/v1/items"), "APPKEY-NOPE", good.secret);
        int badSecret = codeOf(get("/open/v1/items"), good.key, "wrong-secret");
        Cred revoked = cred("read", "REVOKED", null);
        int revokedCode = codeOf(get("/open/v1/items"), revoked.key, revoked.secret);
        Cred expired = cred("read", "ACTIVE", LocalDateTime.now().minusDays(1));
        int expiredCode = codeOf(get("/open/v1/items"), expired.key, expired.secret);

        assertThat(List.of(badKey, badSecret, revokedCode, expiredCode))
                .as("key 不存在 / secret 不对 / 已吊销 / 已过期 —— 四种必须一模一样；"
                        + "分开报的话对方能拿它枚举出哪些 key 是真的")
                .containsOnly(badKey);
        assertThat(noKey).as("连头都没带也是同一个错").isEqualTo(badKey);
        assertThat(badKey).as("必须是失败，不能是 0").isNotZero();
    }

    @Test
    @DisplayName("★★ scope 不够要拒 —— 只读的 key 不能推库存")
    void readOnlyKeyCannotSync() throws Exception {
        Cred c = cred("read");   // 没有 stock:sync

        String body = """
                {"requestId":"REQ-SCOPE","locationId":"%s","lines":[{"itemId":"%s","qty":9}]}
                """.formatted(c.location, c.itemId);
        assertThat(codeOf(post("/open/v1/stock:sync").content(body), c.key, c.secret))
                .as("scope 不够必须拒；放过去的话，一把只读钥匙能改对方所有库存")
                .isNotZero();
    }

    // ------------------------------------------------------------------ 脚手架

    private record Cred(String key, String secret, String owner, String location, String itemId) {
    }

    private Cred cred(String scopes) {
        return cred(scopes, "ACTIVE", null);
    }

    /** 每个用例一套独立的业主与凭证 —— 用例之间不共享种子 */
    private Cred cred(String scopes, String status, LocalDateTime expiresAt) {
        int seq = SEQ.incrementAndGet();
        String entityNo = "E-OPENINV-" + seq;
        String owner = acl.ownerIdOf(entityNo);
        String location = acl.locationIdOf(entityNo, null);
        String itemId = acl.upsertItem(entityNo, "SKU-OPENINV-" + seq, "东北大米", "5斤装",
                "690123456789" + (seq % 10), "LM-" + seq, "袋");
        inbound.postDirectly(new InboundService.Draft(owner, location,
                InvEnums.InboundSource.PURCHASE, null, null, "老周粮油", LocalDateTime.now(), null,
                List.of(new InboundService.Line(itemId, 10, "袋", 4200L))), "老板");

        /*
         * **走签发口，不直接插表**。
         *
         * 第一版是 `credentialMapper.insert(row)` —— 被 `inventory-write-ownership`
         * 守卫当场拦下（域外写 inv_* 表）。而我之所以那么写，是因为当时
         * **根本没有签发凭证的口**：开放接口写完了，却没有任何办法发出一把钥匙。
         * 守卫拦的是一个真问题，不是测试的不便。
         */
        OpenApiCredentialService.Issued issued =
                credentials.issue(owner, "测试对接", scopes, expiresAt);

        // 吊销态要单独造：签发口只发 ACTIVE 的（没人会「签发一把已经作废的钥匙」）
        if (!"ACTIVE".equals(status)) {
            credentials.revoke(issued.credentialId());
        }

        return new Cred(issued.appKey(), issued.appSecret(), owner, location, itemId);
    }

    private int onHand(Cred c) {
        return query.itemDetail(c.owner, c.itemId).onHand();
    }

    /** 打一个请求，断言业务码为 0，返回 {@code data} */
    private JsonNode ok(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                        Cred c) throws Exception {
        String body = mvc().perform(req
                        .header("X-App-Key", c.key).header("X-App-Secret", c.secret)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode env = json.readTree(body);
        assertThat(env.get("code").asInt()).as("业务码。msg=%s", env.get("msg")).isZero();
        return env.get("data");
    }

    /** 只取业务码 —— 鉴权失败包在信封里，HTTP 仍是 200 */
    private int codeOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                       String key, String secret) throws Exception {
        if (key != null) {
            req = req.header("X-App-Key", key).header("X-App-Secret", secret);
        }
        String body = mvc().perform(req.contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        JsonNode env = json.readTree(body);
        return env.has("code") ? env.get("code").asInt() : -1;
    }
}
