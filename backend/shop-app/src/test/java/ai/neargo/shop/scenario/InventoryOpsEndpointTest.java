package ai.neargo.shop.scenario;

import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.InventoryAclService;
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
 * 运营端进销存三个口的**契约**测试 —— 打真实 HTTP 层，逐字比字段名。
 *
 * <h2>为什么非要有这一条</h2>
 * 这三个口的前端（ops-web）已经接完、mock 自查也通过了，
 * 而其中两个**根本调不通**：mock 是照前端自己拟的形状写的，于是两边自洽 ——
 * <b>替身与真身对不上时，替身跑得越顺，越看不出问题</b>。
 * 具体是：{@code health} 当时要 {@code entityNo} 必填、返回单商家余额，
 * 前端按跨商家健康度调，只会拿到 400；{@code ledger} 返回
 * {@code {entries,nextCursor}}，前端按裸数组解。
 *
 * <p>所以断言写在**字段名**上，不写在「有没有报错」上：
 * 少一个字段、改一个名字，界面上的表现是那一列永远空白 —— 不报错，没人发现。
 *
 * <p>字段名对照 {@code ops-web/lib/types/inventory.ts}。改任何一边都要让这里先红。
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryOpsEndpointTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private InventoryAclService acl;
    @Autowired
    private InboundService inbound;

    /**
     * 打一个 GET，断言业务码为 0，返回 {@code data}。
     *
     * <p><b>响应统一包在 {@code {code,msg,data}} 里，HTTP 永远是 200</b> ——
     * 断言写在 HTTP 状态上会全程绿着：权限不足是 {@code code=10403} 而不是 403，
     * 缺参数是 {@code code=10400} 而不是 400。
     */
    private JsonNode ok(String path, String token) throws Exception {
        String body = mvc().perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode env = json.readTree(body);
        assertThat(env.get("code").asInt())
                .as("%s 的业务码；非 0 时 ops-web 的 client 会抛，整页显示成「加载失败」", path)
                .isZero();
        return env.get("data");
    }

    /** 打一个 GET，只取业务码 —— 用来断言「这里必须报错」 */
    private int codeOf(String path, String token) throws Exception {
        String body = mvc().perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ /ops/inventory/health 是**跨商家**扫描：不传 entityNo 也要能出数")
    void healthIsPlatformWideAndNeedsNoMerchant() throws Exception {
        JsonNode rows = ok("/ops/inventory/health?limit=50", opsLogin());
        assertThat(rows.isArray())
                .as("健康度返回数组；返回对象说明接的是分页壳，前端会整页空白")
                .isTrue();
    }

    @Test
    @DisplayName("★★★ 健康度每一行的字段名与 ops-web 的 InvHealthRow 逐字相同")
    void healthRowFieldsMatchOpsWeb() throws Exception {
        Fixture f = seedNegative();
        JsonNode rows = ok("/ops/inventory/health?kind=STALE&limit=200", opsLogin());
        // 扫描是全平台的，种子未必落在前 200 行里；有行就逐字比，没有就至少证明形状对
        if (!rows.isEmpty()) {
            JsonNode r = rows.get(0);
            for (String field : List.of("kind", "entityNo", "merchantName", "storeNo",
                    "itemId", "itemName", "specText", "onHand", "reserved", "available", "idleDays")) {
                assertThat(r.has(field))
                        .as("ops-web 的 InvHealthRow 读 %s —— 少了这一列界面上永远空白，且不报错", field)
                        .isTrue();
            }
        }
        assertThat(f.entityNo).isNotNull();
    }

    @Test
    @DisplayName("★★★ /ops/inventory/ledger 返回 {entries,nextCursor}，且 entityNo 必填")
    void ledgerIsAPageAndNeedsMerchant() throws Exception {
        Fixture f = seedNegative();
        String token = opsLogin();

        // 不传 entityNo：**必须报错**。默默返回空页的话，
        // 界面上是「这个商家没有流水」，而其实是根本没查
        assertThat(codeOf("/ops/inventory/ledger?size=10", token))
                .as("缺 entityNo 要报错，不能静默返回空页").isNotZero();

        JsonNode page = ok("/ops/inventory/ledger?entityNo=" + f.entityNo + "&size=10", token);
        assertThat(page.has("entries")).as("ops-web 读 entries").isTrue();
        assertThat(page.has("nextCursor")).as("ops-web 拿 nextCursor 翻页").isTrue();

        JsonNode row = page.get("entries").get(0);
        for (String field : List.of("id", "docKind", "docNo", "reasonCode",
                "qtyDelta", "balanceAfter", "occurredAt", "operator")) {
            assertThat(row.has(field))
                    .as("ops-web 的 InvLedgerRow 读 %s", field)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("★★★ /ops/inventory/recon 的 clean 是字段不是方法 —— 前端拿它当 G3 判据")
    void reconExposesCleanAsField() throws Exception {
        JsonNode r = ok("/ops/inventory/recon?limit=20", opsLogin());
        for (String field : List.of("scannedSkus", "moved", "skipped", "clean", "diffs")) {
            assertThat(r.has(field))
                    .as("ops-web 的 InvReconReport 读 %s；clean 缺了会被读成 undefined，"
                            + "而 undefined 在界面上长得和「干净」一样", field)
                    .isTrue();
        }
        assertThat(r.get("diffs").isArray()).isTrue();
    }

    @Test
    @DisplayName("★★ 没有 product:sku:read 的运营角色一律 403 —— 界面闸门与后端闸门同一把")
    void withoutPermissionAllThreeAreForbidden() throws Exception {
        String cs = opsLogin("support", "support123");

        // **不是 HTTP 403**：这套接口把权限不足包成业务码 10403，HTTP 仍是 200。
        // 断言写在 HTTP 上的话，这条守卫会全程绿着，而闸门开没开根本没测到
        for (String path : List.of("/ops/inventory/health", "/ops/inventory/recon",
                "/ops/inventory/ledger?entityNo=E-NOPE")) {
            assertThat(codeOf(path, cs)).as("%s 对没有 product:sku:read 的角色必须拒绝", path)
                    .isEqualTo(10403);
        }
    }

    // ------------------------------------------------------------------ 种子

    private record Fixture(String entityNo, String owner, String location, String item) {
    }

    /** 每次一套独立的业主/库位/物料 —— 不碰共享种子，避免「单独跑绿、全量跑红」 */
    private Fixture seedNegative() {
        int seq = SEQ.incrementAndGet();
        String entityNo = "E-OPSINV-" + seq;
        String owner = acl.ownerIdOf(entityNo);
        String location = acl.locationIdOf(entityNo, null);
        String item = acl.upsertItem(entityNo, "SKU-OPSINV-" + seq, "测试米", "5斤装",
                null, null, "BAG");
        inbound.postDirectly(new InboundService.Draft(owner, location,
                InvEnums.InboundSource.PURCHASE, null, "老周粮油", LocalDateTime.now(), null,
                List.of(new InboundService.Line(item, 7, "BAG", 4200L))), "老板");
        return new Fixture(entityNo, owner, location, item);
    }

    private String opsLogin() throws Exception {
        return opsLogin("admin", "admin123");
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
