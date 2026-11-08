package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * 登录的是账户，进店凭登录令牌再校验门店（TDD-账户登录与选店校验）：新模型（STORE_V1）门店的店主，
 * 选了门店之后，日常经营接口（商品、订单、主体、收款）按「令牌 + 门店成员 + 原有权限码」放行，
 * 不要求业务端权益；没选门店回 SELECT_STORE；未登记的新命名空间照旧拒绝。
 */
@SpringBootTest(
        properties = {
            "shop.auth.token-store=db",
            "commerce.enabled=true",
            "commerce.access.active-key-id=test",
            "commerce.access.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "spring.autoconfigure.exclude=ai.neargo.commerce.starter.CommercePersistenceAutoConfiguration,"
                + "ai.neargo.common.security.SecurityAutoConfiguration,ai.neargo.common.security.SessionStoreAutoConfiguration,"
                + "ai.neargo.common.data.DataAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:store_daily;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class StoreScopedDailyRoutesFlowTest {

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestPersistence {
        @org.springframework.context.annotation.Bean
        ai.neargo.commerce.core.spi.CommerceUnitOfWork uow(
                javax.sql.DataSource ds,
                org.springframework.transaction.PlatformTransactionManager manager) {
            return new ai.neargo.commerce.persistence.JdbcCommerceUnitOfWork(ds, manager);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OtpStore otp;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String login(String phone) throws Exception {
        mvc.perform(post("/biz/auth/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        var body = mvc.perform(post("/biz/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone + "\",\"agreed\":true,"
                                + "\"credential\":\"" + otp.peek(phone).orElseThrow() + "\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode env = json.readTree(body);
        assertEquals(0, env.path("code").asInt(), body);
        return env.path("data").path("token").asText();
    }

    private JsonNode call(String token, String store, String path) throws Exception {
        var b = get(path).header("Authorization", "Bearer " + token);
        if (store != null) {
            b.header("X-Store-No", store);
        }
        return json.readTree(mvc.perform(b).andReturn().getResponse().getContentAsString());
    }

    /** 新店主：账户登录 → 快速开店 → 门店改成新模型 → 重新登录（新账户令牌） */
    private String[] newModelOwner(String phone) throws Exception {
        String first = login(phone);
        JsonNode profile = json.readTree(mvc.perform(post("/biz/merchant/quick-start")
                        .header("Authorization", "Bearer " + first).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"Daily routes shop\"}"))
                .andReturn().getResponse().getContentAsString()).path("data");
        String store = profile.path("currentStoreNo").asText();
        assertFalse(store.isBlank());
        jdbc.update("update mch_store set commerce_model='STORE_V1', business_type_code='RETAIL' where store_no=?",
                store);
        return new String[] {login(phone), store};
    }

    @Test
    @DisplayName("★★★ 新模型门店的店主选了门店：商品、订单、主体、收款这些日常接口按门店成员放行，不再 70006")
    void selectedStoreOpensDailyRoutes() throws Exception {
        String[] o = newModelOwner("15965020101");
        for (String path : new String[] {"/biz/goods?size=1", "/biz/order?caller=RETAIL", "/biz/entities",
                "/biz/payment/collect-status", "/biz/after-sale/worklist", "/biz/merchant/payment?storeNo=" + o[1]}) {
            JsonNode r = call(o[0], o[1], path);
            assertEquals(0, r.path("code").asInt(), path + " → " + r);
        }
        String entity = call(o[0], o[1], "/biz/merchant/profile").path("data").path("merchantNo").asText();
        JsonNode e = call(o[0], o[1], "/biz/entity/" + entity);
        assertEquals(0, e.path("code").asInt(), e.toString());
    }

    @Test
    @DisplayName("★★★ App 的店员角色、管理空间、证照协议、地图选址与看板，在新模型门店上都能进门店（不是 70006）")
    void appAdminAndOnboardingRoutesPassAdmission() throws Exception {
        String[] o = newModelOwner("15965020107");
        for (String path : new String[] {"/biz/staff", "/biz/roles", "/biz/role-perms", "/biz/management/context",
                "/biz/management/spaces/M1/stores", "/biz/qualifications", "/biz/merchant/pay-channel",
                "/biz/geo/tips?keyword=taipei", "/biz/geo/reverse?lat=25.03&lng=121.56",
                "/biz/dashboard/todo", "/biz/dashboard/stats", "/biz/merchant/pay-service"}) {
            JsonNode r = call(o[0], o[1], path);
            assertNotEquals(70006, r.path("code").asInt(), path + " → " + r);
        }
    }

    @Test
    @DisplayName("★★★ App 第二批（门店资料 / 方案 / 评价 / 列印 / 通知 / 自提 / 进销存 / 会员营销 / 团购 / 平台活动）读接口在新模型门店上能进门店（2026-11-08 台北早餐 166 上 70006）")
    void appSecondBatchReadsPassAdmission() throws Exception {
        String[] o = newModelOwner("15965020110");
        String s = "/biz/store/" + o[1] + "/";
        for (String path : new String[] {
                "/biz/store", "/biz/store/share-kit", "/biz/store/poster", "/biz/store/qrcode", "/biz/plan",
                "/biz/review", "/biz/print/printers", "/biz/print/health", "/biz/print/jobs", "/biz/print/scene-policies",
                "/biz/notify/setting", "/biz/pickup/overview", "/biz/pickup/orders", "/biz/pickup-points/candidates",
                "/biz/inventory/summary", "/biz/inventory/balances", "/biz/inventory/ledger",
                "/biz/inventory/category-setting", "/biz/inventory/report/monthly",
                s + "offline-sale", s + "stock-overview", s + "stock-alignment", s + "stock-reconcile/records",
                "/biz/members", "/biz/members/stats", "/biz/member-tags", "/biz/member-segments", "/biz/member-settings",
                "/biz/member-reach/task", "/biz/coupons", "/biz/coupon-issues", "/biz/points/store-account",
                "/biz/points/store-statements", "/biz/groups", "/biz/group/pickups", "/biz/group-request/pool",
                "/biz/period", "/biz/platform-activity", "/biz/activity-presets", "/biz/marketing-summary"}) {
            JsonNode r = call(o[0], o[1], path);
            assertNotEquals(70006, r.path("code").asInt(), path + " → " + r);
        }
    }

    @Test
    @DisplayName("★★ App 第二批写接口（门店公告、改店名、评价回复、加打印机）在新模型门店上能进门店（不是 70006）")
    void appSecondBatchWritesPassAdmission() throws Exception {
        String[] o = newModelOwner("15965020111");
        for (String path : new String[] {"/biz/store/announcement", "/biz/store/" + o[1] + "/rename",
                "/biz/review/R1/reply", "/biz/print/printers", "/biz/coupons", "/biz/member-tags"}) {
            String body = mvc.perform(post(path).header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getContentAsString();
            assertNotEquals(70006, json.readTree(body).path("code").asInt(), path + " → " + body);
        }
    }

    @Test
    @DisplayName("★★★ 账号级接口（改显示名、推送注册 / 解绑）在新模型门店账号上不选门店也能调（ACCOUNT 通道，不是 70006）")
    void accountScopedRoutesWorkWithoutStoreSelection() throws Exception {
        String[] o = newModelOwner("15965020113");
        String[][] calls = {
                {"/biz/merchant/display-name", "{\"displayName\":\"Robin\"}"},
                {"/biz/push-token", "{\"platform\":\"android\",\"clientId\":\"cid-daily-routes\"}"},
                {"/biz/push-token/unregister", "{\"clientId\":\"cid-daily-routes\"}"}};
        for (String[] c : calls) {
            for (String store : new String[] {null, o[1]}) {
                var b = post(c[0]).header("Authorization", "Bearer " + o[0])
                        .contentType(MediaType.APPLICATION_JSON).content(c[1]);
                if (store != null) {
                    b.header("X-Store-No", store);
                }
                String body = mvc.perform(b).andReturn().getResponse().getContentAsString();
                assertEquals(0, json.readTree(body).path("code").asInt(), c[0] + " store=" + store + " → " + body);
            }
        }
    }

    @Test
    @DisplayName("★★★ 问题回报（提交 / 我的回报）是账号级：新模型门店账号不选门店也能调（ACCOUNT 通道，不是 70006）")
    void feedbackRoutesWorkWithoutStoreSelection() throws Exception {
        String[] o = newModelOwner("15965020116");
        for (String store : new String[] {null, o[1]}) {
            var b = post("/biz/feedback").header("Authorization", "Bearer " + o[0])
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"category\":\"BUG\",\"description\":\"选不了店\",\"appVersion\":\"0.1.0\","
                            + "\"platform\":\"android\",\"storeNo\":\"" + o[1] + "\"}");
            if (store != null) {
                b.header("X-Store-No", store);
            }
            String body = mvc.perform(b).andReturn().getResponse().getContentAsString();
            assertEquals(0, json.readTree(body).path("code").asInt(), "POST /biz/feedback store=" + store + " → " + body);
            JsonNode mine = call(o[0], store, "/biz/feedback/mine");
            assertEquals(0, mine.path("code").asInt(), "GET /biz/feedback/mine store=" + store + " → " + mine);
        }
    }

    @Test
    @DisplayName("★★ 出款账户（收款账户 F06）在新模型门店上能进门店（不是 70006）")
    void payoutAccountPassesAdmission() throws Exception {
        String[] o = newModelOwner("15965020114");
        JsonNode r = call(o[0], o[1], "/biz/payout-account");
        assertNotEquals(70006, r.path("code").asInt(), r.toString());
        String body = mvc.perform(post("/biz/payout-account").header("Authorization", "Bearer " + o[0])
                        .header("X-Store-No", o[1]).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString();
        assertNotEquals(70006, json.readTree(body).path("code").asInt(), body);
    }

    @Test
    @DisplayName("★★★ 门店级配送方式（App W05 多选）在新模型门店上能读能存；没配过的店如实回「沿用商户设置」那一路")
    void storeFulfillmentWorksOnNewModelStores() throws Exception {
        String[] o = newModelOwner("15965020115");
        String path = "/biz/stores/" + o[1] + "/fulfillment";

        JsonNode before = call(o[0], o[1], path);
        assertEquals(0, before.path("code").asInt(), before.toString());
        /*
         * 新建门店没有门店级配置行，买家侧按主体 fulfillment_reach 兜底（StoreRoutes）。
         * 读接口若照实回「四路全关」，App 会显示一家「谁也看不到」的店，而买家其实看得到 ——
         * 所以兜底那一路要标成开着、并注明 inherited。
         */
        var inherited = new java.util.ArrayList<String>();
        for (JsonNode ch : before.path("data").path("channels")) {
            if (ch.path("enabled").asBoolean()) {
                assertTrue(ch.path("inherited").asBoolean(), "兜底开着的那一路要标 inherited：" + ch);
                inherited.add(ch.path("channel").asText());
            }
        }
        assertEquals(java.util.List.of("STORE_PICKUP"), inherited, "主体 fulfillment_reach 为空按自提兜底");

        String saved = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                        .header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channels\":[{\"channel\":\"EXPRESS\",\"enabled\":true}]}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode s = json.readTree(saved);
        assertEquals(0, s.path("code").asInt(), saved);
        for (JsonNode ch : s.path("data").path("channels")) {
            assertFalse(ch.path("inherited").asBoolean(), "存过之后就是门店自己的配置，不再沿用：" + ch);
            assertEquals("EXPRESS".equals(ch.path("channel").asText()), ch.path("enabled").asBoolean(), ch.toString());
        }

        /*
         * 关路前的影响清单仍走 LEGACY_PREVIEW、只给老模型门店（见 ShopStoreMemberRoutes 的说明），
         * 新模型门店上是 70006。这里钉住现状：哪天开放了，这条会变红提醒同步改 App 与 TDD。
         */
        JsonNode impact = call(o[0], o[1], path + "/EXPRESS/impact");
        assertEquals(70006, impact.path("code").asInt(), impact.toString());
    }

    @Test
    @DisplayName("★★ 逐条登记、不放开兜底：本店下未登记的子路径仍然 70006")
    void unregisteredStoreSubpathStillDenied() throws Exception {
        String[] o = newModelOwner("15965020112");
        for (String path : new String[] {"/biz/store/" + o[1] + "/not-registered", "/biz/store/not-registered"}) {
            JsonNode r = call(o[0], o[1], path);
            assertEquals(70006, r.path("code").asInt(), path + " → " + r);
        }
    }

    @Test
    @DisplayName("★★ 商家在 App 申请支付服务（POST）在新模型门店上能进门店（内嵌部署回依赖不可用，不是 70006）")
    void payServiceApplyPassesAdmission() throws Exception {
        String[] o = newModelOwner("15965020108");
        String body = mvc.perform(post("/biz/merchant/pay-service/apply")
                        .header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"x\",\"registrationNo\":\"1\"}"))
                .andReturn().getResponse().getContentAsString();
        assertNotEquals(70006, json.readTree(body).path("code").asInt(), body);
    }

    @Test
    @DisplayName("★★★ 新模型门店店主设门店代码（立牌链接 /h5/s/{代码}）：能进门店，且 updated_at 前进，门店同步会把它推到 near-pay")
    void ownerSetsStoreSlugAndTriggersPaySync() throws Exception {
        String[] o = newModelOwner("15965020109");
        jdbc.update("update mch_store set updated_at='2026-01-01 00:00:00' where store_no=?", o[1]);
        String body = mvc.perform(post("/biz/store/" + o[1] + "/slug")
                        .header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"daily-routes-shop\"}"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, json.readTree(body).path("code").asInt(), body);
        assertEquals("daily-routes-shop", jdbc.queryForObject("select slug from mch_store where store_no=?", String.class, o[1]));
        java.time.LocalDateTime at = jdbc.queryForObject("select updated_at from mch_store where store_no=?",
                java.time.LocalDateTime.class, o[1]);
        org.junit.jupiter.api.Assertions.assertTrue(at.isAfter(java.time.LocalDateTime.of(2026, 1, 2, 0, 0)),
                "改了门店代码 updated_at 没动 —— 门店同步扫不到，near-pay 的立牌码拿不到 store_code：" + at);
    }

    @Test
    @DisplayName("★★ 新店主给自己的店设货架类目（POST）：同样按门店成员放行")
    void ownerSetsStoreCategories() throws Exception {
        String[] o = newModelOwner("15965020104");
        String body = mvc.perform(post("/biz/store/" + o[1] + "/categories")
                        .header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"categoryNo\":\"CAT210\",\"sort\":1}]}"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, json.readTree(body).path("code").asInt(), body);
        assertEquals("CAT210", json.readTree(body).path("data").path(0).path("categoryNo").asText(), body);
    }

    @Test
    @DisplayName("★★★ 没选门店：日常接口回 SELECT_STORE（让 App 去选店），而不是「联系管理员」")
    void noSelectionAsksToSelectStore() throws Exception {
        String[] o = newModelOwner("15965020102");
        JsonNode r = call(o[0], null, "/biz/goods?size=1");
        assertEquals(70006, r.path("code").asInt(), r.toString());
        assertEquals("SELECT_STORE", r.path("data").path("nextAction").asText(), r.toString());
    }

    @Test
    @DisplayName("★★ 默认拒绝不变：选了门店，未登记的新命名空间仍然拒绝")
    void unregisteredStillDenied() throws Exception {
        String[] o = newModelOwner("15965020103");
        JsonNode r = call(o[0], o[1], "/biz/stores/" + o[1] + "/not-registered");
        assertEquals(70006, r.path("code").asInt(), r.toString());
    }
}
