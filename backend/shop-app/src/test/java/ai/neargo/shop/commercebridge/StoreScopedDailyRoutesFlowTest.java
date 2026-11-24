package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.otp.OtpKeys;
import ai.neargo.shop.common.otp.OtpScenes;
import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
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
            "shop.pos-pin.pepper=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "shop.sms.stub=true",
            "commerce.enabled=true",
            "commerce.access.active-key-id=test",
            "commerce.access.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "spring.autoconfigure.exclude=ai.neargo.commerce.starter.CommercePersistenceAutoConfiguration,"
                + "ai.neargo.commerce.starter.CommerceStoreRelinkAutoConfiguration,"
                + "ai.neargo.common.security.SecurityAutoConfiguration,ai.neargo.common.security.SessionStoreAutoConfiguration,"
                + "ai.neargo.common.data.DataAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:store_daily;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class StoreScopedDailyRoutesFlowTest {

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    // Native store relinking requires the real migration owner and is outside this H2 UOW fixture.
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
    void posPinRecoveryIsScopedToTheEmployeeStoreAndLoginSession() throws Exception {
        String phone = "15965020135";
        String staffPhone = "15965020137";
        String[] owner = newModelOwner(phone);
        String[] other = newModelOwner("15965020136");
        String path = "/biz/auth/pos-pin";
        String password = "Local-password-for-pin-qa";

        JsonNode staff = write(HttpMethod.POST, owner[0], owner[1], "/biz/staff",
                json.createObjectNode().put("loginPhone", staffPhone));
        assertEquals(0, staff.path("code").asInt(-1), staff.toString());
        String staffNo = staff.path("data").path("mchAccountNo").asText();
        assertFalse(staffNo.isBlank());
        JsonNode grant = write(HttpMethod.POST, owner[0], owner[1], "/biz/staff/" + staffNo + "/store",
                json.createObjectNode().put("storeNo", owner[1]).put("role", "CLERK"));
        assertEquals(0, grant.path("code").asInt(-1), grant.toString());
        String staffToken = login(staffPhone);

        JsonNode initial = call(owner[0], owner[1], path).path("data");
        assertTrue(initial.path("canManagePolicy").asBoolean());
        assertFalse(initial.path("enabled").asBoolean());
        assertFalse(call(staffToken, owner[1], path).path("data").path("canManagePolicy").asBoolean());
        var enable = json.createObjectNode().put("enabled", true);
        assertNotEquals(0, write(HttpMethod.PUT, staffToken, owner[1], path + "/policy", enable).path("code").asInt());
        assertTrue(write(HttpMethod.PUT, owner[0], owner[1], path + "/policy", enable)
                .path("data").path("enabled").asBoolean());
        assertTrue(call(staffToken, owner[1], path).path("data").path("enabled").asBoolean());
        assertFalse(call(other[0], other[1], path).path("data").path("enabled").asBoolean());
        assertTrue(write(HttpMethod.PUT, other[0], other[1], path + "/policy", enable)
                .path("data").path("enabled").asBoolean());

        JsonNode setPassword = write(HttpMethod.POST, owner[0], null, "/biz/auth/password",
                json.createObjectNode().put("password", password));
        assertEquals(0, setPassword.path("code").asInt(-1), setPassword.toString());
        assertEquals("SAVED", outcome(write(HttpMethod.PUT, owner[0], owner[1], path,
                json.createObjectNode().put("newPin", "1739"))));
        assertEquals("SAVED", outcome(write(HttpMethod.PUT, staffToken, owner[1], path,
                json.createObjectNode().put("newPin", "2759"))));
        assertEquals("SAVED", outcome(write(HttpMethod.PUT, other[0], other[1], path,
                json.createObjectNode().put("newPin", "7395"))));
        assertEquals("WRONG", outcome(write(HttpMethod.POST, staffToken, owner[1], path + "/verify",
                json.createObjectNode().put("pin", "1739"))));

        JsonNode challenge = write(HttpMethod.POST, owner[0], owner[1], path + "/recovery/challenges",
                json.createObjectNode().put("method", "PASSWORD"));
        assertEquals(0, challenge.path("code").asInt(-1), challenge.toString());
        String challengeId = challenge.path("data").path("challengeId").asText();
        assertFalse(challengeId.isBlank());
        JsonNode proof = write(HttpMethod.POST, owner[0], owner[1],
                path + "/recovery/challenges/" + challengeId + "/verify",
                json.createObjectNode().put("credential", password));
        assertEquals("VERIFIED", outcome(proof));
        String resetToken = proof.path("data").path("resetToken").asText();
        assertFalse(resetToken.isBlank());
        JsonNode reset = json.createObjectNode().put("resetToken", resetToken).put("newPin", "5813");

        assertEquals(ErrorCode.BAD_REQUEST.code(), write(HttpMethod.PUT, staffToken, owner[1], path + "/recovery", reset)
                .path("code").asInt());
        assertEquals(ErrorCode.BAD_REQUEST.code(), write(HttpMethod.PUT, other[0], other[1], path + "/recovery", reset)
                .path("code").asInt());
        assertNotEquals(0, call(owner[0], other[1], path).path("code").asInt());
        String secondSession = login(phone);
        assertNotEquals(owner[0], secondSession);
        assertTrue(call(secondSession, owner[1], path).path("data").path("configured").asBoolean());
        assertEquals(ErrorCode.BAD_REQUEST.code(), write(HttpMethod.PUT, secondSession, owner[1], path + "/recovery", reset)
                .path("code").asInt());
        assertEquals("SAVED", outcome(write(HttpMethod.PUT, owner[0], owner[1], path + "/recovery", reset)));
        assertEquals(ErrorCode.BAD_REQUEST.code(), write(HttpMethod.PUT, owner[0], owner[1], path + "/recovery", reset)
                .path("code").asInt());
        assertEquals("WRONG", outcome(write(HttpMethod.POST, owner[0], owner[1], path + "/verify",
                json.createObjectNode().put("pin", "1739"))));
        assertEquals("MATCH", outcome(write(HttpMethod.POST, owner[0], owner[1], path + "/verify",
                json.createObjectNode().put("pin", "5813"))));
        assertEquals("MATCH", outcome(write(HttpMethod.POST, staffToken, owner[1], path + "/verify",
                json.createObjectNode().put("pin", "2759"))));

        JsonNode staffChallenge = write(HttpMethod.POST, staffToken, owner[1], path + "/recovery/challenges",
                json.createObjectNode().put("method", "PHONE_OTP"));
        assertEquals(0, staffChallenge.path("code").asInt(-1), staffChallenge.toString());
        assertTrue(staffChallenge.path("data").path("maskedRecipient").asText().endsWith("0137"));
        String staffChallengeId = staffChallenge.path("data").path("challengeId").asText();
        String otpKey = OtpKeys.storageKey(OtpScenes.POS_PIN_RECOVERY, Channel.SMS, staffPhone, staffChallengeId);
        String verifyStaffOtp = path + "/recovery/challenges/" + staffChallengeId + "/verify";
        JsonNode staffProof = write(HttpMethod.POST, staffToken, owner[1], verifyStaffOtp,
                json.createObjectNode().put("credential", otp.peek(otpKey).orElseThrow()));
        assertEquals("VERIFIED", outcome(staffProof));
        assertTrue(otp.peek(otpKey).isEmpty());
        JsonNode resetStaff = json.createObjectNode().put("resetToken", staffProof.path("data").path("resetToken").asText())
                .put("newPin", "6815");
        assertEquals("SAVED", outcome(write(HttpMethod.PUT, staffToken, owner[1], path + "/recovery", resetStaff)));
        assertEquals("MATCH", outcome(write(HttpMethod.POST, staffToken, owner[1], path + "/verify",
                json.createObjectNode().put("pin", "6815"))));

        var disable = json.createObjectNode().put("enabled", false);
        JsonNode disabled = write(HttpMethod.PUT, owner[0], owner[1], path + "/policy", disable);
        assertEquals(0, disabled.path("code").asInt(-1));
        assertFalse(call(staffToken, owner[1], path).path("data").path("enabled").asBoolean(true));
        assertEquals("DISABLED", outcome(write(HttpMethod.POST, staffToken, owner[1], path + "/verify",
                json.createObjectNode().put("pin", "6815"))));
        assertTrue(call(other[0], other[1], path).path("data").path("enabled").asBoolean());
        assertEquals(0, write(HttpMethod.PUT, other[0], other[1], path + "/policy", disable).path("code").asInt(-1));
    }

    private JsonNode write(HttpMethod method, String token, String store, String path, JsonNode body) throws Exception {
        var builder = request(method, path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString());
        if (store != null) {
            builder.header("X-Store-No", store);
        }
        return json.readTree(mvc.perform(builder).andReturn().getResponse().getContentAsString());
    }

    private static String outcome(JsonNode response) {
        assertEquals(0, response.path("code").asInt(-1), response.toString());
        return response.path("data").path("outcome").asText();
    }

    @Test
    void selectedNewModelStoreCanReadClaimDiagnosticsWithoutOpeningMutation() throws Exception {
        String[] owner = newModelOwner("15965020129");
        var result = call(owner[0], owner[1], "/biz/store/"+owner[1]+"/entity/relink-check?entityNo=OTHER");
        assertEquals(0, result.path("code").asInt(), result.toString());
        assertFalse(result.path("data").path("executionReady").asBoolean());
        assertFalse(result.path("data").path("allowed").asBoolean());
        assertTrue(result.path("data").path("blockers").toString().contains("RELINK_CONCURRENCY_UNAVAILABLE"));
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
    @DisplayName("★★★ App 外收款记录（/biz/payment/offline-records*）在新模型门店上能进门店：记一笔、列表、汇总都是 0，不是 70006")
    void new_model_store_member_reaches_offline_records() throws Exception {
        String[] o = newModelOwner("15965020117");
        jdbc.update("update mch_entity set market='TW' where entity_no=(select entity_no from mch_store where store_no=?)",
                o[1]);
        String body = mvc.perform(post("/biz/payment/offline-records").header("Authorization", "Bearer " + o[0])
                        .header("X-Store-No", o[1]).header("Idempotency-Key", "daily-routes-offline-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":48000,\"currency\":\"TWD\",\"method\":\"CASH\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(body);
        assertTrue(created.has("code"), body);
        assertEquals(0, created.path("code").asInt(), body);
        for (String path : new String[] {"/biz/payment/offline-records", "/biz/payment/offline-records/summary"}) {
            JsonNode r = call(o[0], o[1], path);
            assertTrue(r.has("code") && r.path("data").isObject(), path + " → " + r);
            assertEquals(0, r.path("code").asInt(), path + " → " + r);
        }
        String no = created.path("data").path("recordNo").asText();
        assertTrue(no.startsWith("OFR"), body);
        String voided = mvc.perform(post("/biz/payment/offline-records/" + no + "/void")
                        .header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"测试\",\"expectedVersion\":1}"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, json.readTree(voided).path("code").asInt(), voided);
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
    @DisplayName("★★★ App 第三批（结算 / 保证金 / 配送规则 / 社区 / 客户 / 跨店 / 区划 / 零散漏掉的）在新模型门店上能进门店（2026-11-19 清点出的漏登记）")
    void appThirdBatchPassesAdmission() throws Exception {
        String[] o = newModelOwner("15965020131");
        for (String path : new String[] {
                "/biz/settle/batch", "/biz/settle/bills", "/biz/settle/daily-flow", "/biz/settle/income",
                "/biz/settle/invoice-pending", "/biz/settle/invoice-title", "/biz/settle/invoices",
                "/biz/settle/rate-card", "/biz/settle/statement", "/biz/deposit", "/biz/deposit/txns",
                "/biz/merchant/debt", "/biz/delivery/rule", "/biz/communities/applies", "/biz/customers",
                "/biz/cross-store/compare", "/biz/cross-store/overview", "/biz/regions", "/biz/regions/path",
                "/biz/regions/search", "/biz/regions/villages", "/biz/geo/estates", "/biz/geo/estates/counts",
                "/biz/store-spec-dims", "/biz/fulfillment/carriers"}) {
            JsonNode r = call(o[0], o[1], path);
            assertNotEquals(70006, r.path("code").asInt(), path + " → " + r);
        }
        // 写接口：只看有没有被门店准入挡住。请求体是空的，进到入口之后的参数错、业务错都算「进去了」
        for (String[] write : new String[][] {
                {"POST", "/biz/settle/invoices"}, {"POST", "/biz/delivery/rule"}, {"POST", "/biz/communities/apply"},
                {"POST", "/biz/communities/from-map"}, {"POST", "/biz/member-code/resolve"},
                {"POST", "/biz/regions/parse"}, {"POST", "/biz/notify/wecom/test"}, {"POST", "/biz/message/subscribe"},
                {"PUT", "/biz/activities/NO-SUCH-ACTIVITY/status"}, {"POST", "/biz/store/" + o[1] + "/default"}}) {
            var request = "PUT".equals(write[0])
                    ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(write[1]) : post(write[1]);
            var response = mvc.perform(request.header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse();
            String body = response.getContentAsString();
            assertFalse(body.contains("STORE_ACCESS"), write[0] + " " + write[1] + " → " + body);
            assertNotEquals(403, response.getStatus(), write[0] + " " + write[1] + " → " + body);
        }
    }

    @Test
    @DisplayName("★★★ 发票（/biz/invoices*、/biz/invoice-setup/*）在新模型门店上能进门店（2026-11-23 v2 上全部 70006 STORE_ACCESS）")
    void invoiceRoutesPassAdmission() throws Exception {
        String[] o = newModelOwner("15965020139");
        // 只看门店准入：权限码与业务错误由控制器判，进到入口之后的 4xx 都算「进去了」
        for (String[] r : new String[][] {
                {"GET", "/biz/invoices"}, {"GET", "/biz/invoices/IVC-NO-SUCH"}, {"GET", "/biz/invoices/IVC-NO-SUCH/print-facts"},
                {"GET", "/biz/invoices/upload-backlog"}, {"GET", "/biz/invoices/buyer-preferences"},
                {"GET", "/biz/invoice-applications"}, {"GET", "/biz/invoice-applications/IVA-MISSING"},
                {"GET", "/biz/invoice-applications/IVA-MISSING/revisions"}, {"GET", "/biz/invoice-applications/IVA-MISSING/events"},
                {"POST", "/biz/invoice-applications"}, {"POST", "/biz/invoice-applications/IVA-MISSING/issuances"},
                {"GET", "/biz/invoice-channel-applications"}, {"GET", "/biz/invoice-channel-applications/ICA-MISSING"},
                {"POST", "/biz/invoice-channel-applications"}, {"POST", "/biz/invoice-channel-applications/ICA-MISSING/revisions"},
                {"GET", "/biz/invoice-channel-accounts"}, {"GET", "/biz/invoice-channel-routes"},
                {"GET", "/biz/invoice-setup/profile"}, {"GET", "/biz/invoice-setup/providers"},
                {"GET", "/biz/invoice-setup/ranges"}, {"GET", "/biz/invoice-setup/provider-tracks"},
                {"POST", "/biz/invoices"}, {"POST", "/biz/invoices/IVC-NO-SUCH/void"},
                {"POST", "/biz/invoices/IVC-NO-SUCH/revocation"}, {"POST", "/biz/invoices/IVC-NO-SUCH/reissue"},
                {"POST", "/biz/invoices/IVC-NO-SUCH/allowances"}, {"POST", "/biz/invoices/IVC-NO-SUCH/allowances/ALW-NO-SUCH/void"},
                {"POST", "/biz/invoices/IVC-NO-SUCH/retransmissions"}, {"POST", "/biz/invoices/IVC-NO-SUCH/provider-checks"},
                {"PUT", "/biz/invoice-setup/profile"}, {"POST", "/biz/invoice-setup/ranges"},
                {"POST", "/biz/invoice-setup/provider-verification"}, {"POST", "/biz/invoice-setup/provider-tracks"},
                {"POST", "/biz/invoice-setup/provider-ranges"}}) {
            var request = switch (r[0]) {
                case "GET" -> get(r[1]);
                case "PUT" -> put(r[1]);
                default -> post(r[1]);
            };
            var response = mvc.perform(request.header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse();
            String body = response.getContentAsString();
            assertFalse(body.contains("STORE_ACCESS"), r[0] + " " + r[1] + " → " + body);
        }
    }

    @Test
    @DisplayName("★★★ 门店零售单（/biz/store-orders*）在新模型门店上能进门店：POS 建单、看单、补开票资料、取消")
    void storeOrderRoutesPassAdmission() throws Exception {
        String[] o = newModelOwner("15965020140");
        for (String[] r : new String[][] {
                {"POST", "/biz/store-orders"}, {"GET", "/biz/store-orders/OD-NO-SUCH"},
                {"PUT", "/biz/store-orders/OD-NO-SUCH/invoice-data"}, {"POST", "/biz/store-orders/OD-NO-SUCH/cancel"}}) {
            var request = switch (r[0]) {
                case "GET" -> get(r[1]);
                case "PUT" -> put(r[1]);
                default -> post(r[1]);
            };
            var response = mvc.perform(request.header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                            .header("Idempotency-Key", "K-ROUTE").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse();
            String body = response.getContentAsString();
            assertFalse(body.contains("STORE_ACCESS"), r[0] + " " + r[1] + " → " + body);
        }
    }

    @Test
    @DisplayName("★★★ 门店开票设定与常用品项（/biz/invoice-setup/store-*）在新模型门店上能进门店：POS 快选、店长设定")
    void storeInvoiceSetupRoutesPassAdmission() throws Exception {
        String[] o = newModelOwner("15965020141");
        for (String[] r : new String[][] {
                {"GET", "/biz/invoice-setup/store-settings"}, {"PUT", "/biz/invoice-setup/store-settings"},
                {"GET", "/biz/invoice-setup/store-items"}, {"POST", "/biz/invoice-setup/store-items"},
                {"PUT", "/biz/invoice-setup/store-items/IVS-NO-SUCH"},
                {"DELETE", "/biz/invoice-setup/store-items/IVS-NO-SUCH"}}) {
            var request = switch (r[0]) {
                case "GET" -> get(r[1]);
                case "PUT" -> put(r[1]);
                case "DELETE" -> delete(r[1]);
                default -> post(r[1]);
            };
            var response = mvc.perform(request.header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse();
            String body = response.getContentAsString();
            assertFalse(body.contains("STORE_ACCESS"), r[0] + " " + r[1] + " → " + body);
        }
    }

    @Test
    @DisplayName("★★★ 储值对新模型门店开放（2026-11-19）：门店侧、门店命名空间下、管理空间下的入口都能进；没列出来的储值入口仍然关着")
    void storedValueOpensForNewModelStoresButUnlistedStaysClosed() throws Exception {
        String[] o = newModelOwner("15965020132");
        for (String path : new String[] {"/biz/stored-value/plans", "/biz/stored-value/context?storeNo=" + o[1],
                "/biz/stored-value/account-directory", "/biz/stored-value/brand-capability",
                "/biz/stores/" + o[1] + "/stored-value/issuers", "/biz/stores/" + o[1] + "/stored-value/issuer-records",
                "/biz/stores/" + o[1] + "/stored-value/store-summary",
                "/biz/management/stored-value/settlement-bills", "/biz/management/stored-value/financial-capability",
                "/biz/management/brands/B1/stored-value/activities", "/biz/management/stores/" + o[1] + "/stored-value/issuer"}) {
            var response = mvc.perform(get(path).header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1]))
                    .andReturn().getResponse();
            String body = response.getContentAsString();
            assertFalse(body.contains("STORE_ACCESS"), path + " → " + body);
            assertNotEquals(403, response.getStatus(), path + " → " + body);
        }
        // 逐条登记的意思：没列进去的照旧默认关闭（迁移清单、端上没有用的旧充值与流水入口）
        for (String path : new String[] {"/biz/stored-value/records", "/biz/stored-value/recharges/R1",
                "/biz/management/stored-value/issuers/I1"}) {
            String body = mvc.perform(get(path).header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1]))
                    .andReturn().getResponse().getContentAsString();
            assertEquals(70006, json.readTree(body).path("code").asInt(), path + " → " + body);
            assertTrue(body.contains("STORE_ACCESS"), path + " → " + body);
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
        String[] o = newModelOwner("15965020118");
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
    @DisplayName("★★★ 台湾主体：加员工、建会员收 +886 号码（此前只认 11 位大陆号，台湾店主加不了员工）；大陆主体口径不变")
    void taiwanEntityAcceptsTaiwanPhones() throws Exception {
        String[] o = newModelOwner("15965020119");
        String entity = jdbc.queryForObject("select entity_no from mch_store where store_no=?", String.class, o[1]);
        String staffBody = "{\"loginPhone\":\"+886912345678\",\"displayName\":\"阿明\"}";
        String memberBody = "{\"phone\":\"+886912345679\"}";

        // 大陆主体：台湾号照旧拒（大陆号段校验不放宽）
        assertEquals(10400, postCode(o, "/biz/staff", staffBody));
        assertNotEquals(0, postCode(o, "/biz/members", memberBody));

        jdbc.update("update mch_entity set market='TW' where entity_no=?", entity);
        assertEquals(0, postCode(o, "/biz/staff", staffBody), "台湾主体加员工");
        assertEquals("+886912345678", jdbc.queryForObject(
                "select login_phone from mch_account where entity_no=? and login_phone like '+886%'", String.class, entity),
                "按 App 送来的 E.164 原样存 —— 员工登录按同一个号匹配");
        assertEquals(0, postCode(o, "/biz/members", memberBody), "台湾主体建会员");
        // 明显不是号码的仍然拒
        assertEquals(10400, postCode(o, "/biz/staff", "{\"loginPhone\":\"abc\",\"displayName\":\"x\"}"));
    }

    private int postCode(String[] o, String path, String body) throws Exception {
        String r = mvc.perform(post(path).header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(r).path("code").asInt();
    }

    @Test
    @DisplayName("★★★ 餐饮插件 /biz/x/food/** 与外卖渠道 /biz/x/commerce/** 在新模型门店上能进门店（门店功能矩阵开发计划 E3；接口自带 @PreAuthorize 判权）")
    void foodPluginRoutesPassAdmission() throws Exception {
        String[] o = newModelOwner("15965020120");
        for (String path : new String[] {
                "/biz/x/food/stores/" + o[1] + "/tables", "/biz/x/food/queue-tickets",
                "/biz/x/food/stores/" + o[1] + "/kitchen/tasks", "/biz/x/food/reports/business-day",
                "/biz/x/commerce/orders/CO1", "/biz/x/commerce/providers",
                "/biz/stores/" + o[1] + "/channel-order-policies",
                "/biz/stores/" + o[1] + "/receivables/FST1?dueMinor=0&currency=TWD"}) {
            JsonNode r = call(o[0], o[1], path);
            assertNotEquals(70006, r.path("code").asInt(), path + " → " + r);
        }
        for (String path : new String[] {"/biz/x/food/queue-tickets:call-next", "/biz/x/food/tasks/T1:start",
                "/biz/x/commerce/orders/CO1:accept",
                "/biz/stores/" + o[1] + "/channel-order-policies/" + "a".repeat(64) + "/enabled"}) {
            String body = mvc.perform(post(path).header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getContentAsString();
            assertNotEquals(70006, json.readTree(body).path("code").asInt(), path + " → " + body);
        }
        String body = mvc.perform(put("/biz/x/food/menus/M1").header("Authorization", "Bearer " + o[0])
                        .header("X-Store-No", o[1]).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString();
        assertNotEquals(70006, json.readTree(body).path("code").asInt(), body);
    }

    @Test
    @DisplayName("★★★ 美业插件 /biz/x/beauty/** 与人员、资源、排班在新模型门店上能进门店（开发计划 B3；26 个接口服务层判权已逐条审计）")
    void beautyAndStaffingRoutesPassAdmission() throws Exception {
        String[] o = newModelOwner("15965020121");
        for (String path : new String[] {"/biz/x/beauty/appointments", "/biz/x/beauty/appointments/A1",
                "/biz/x/beauty/work-orders/W1/bill", "/biz/x/beauty/store-policies",
                "/biz/x/beauty/service-definitions", "/biz/personnel", "/biz/resources", "/biz/schedules",
                "/biz/stores/" + o[1] + "/appointment-slots"}) {
            JsonNode r = call(o[0], o[1], path);
            assertNotEquals(70006, r.path("code").asInt(), path + " → " + r);
        }
        for (String path : new String[] {"/biz/x/beauty/appointments", "/biz/x/beauty/arrivals",
                "/biz/x/beauty/work-orders/W1/tasks/T1/preparations", "/biz/personnel/P1/assignments",
                "/biz/resources", "/biz/schedule-rules", "/biz/appointment-slots/SL1/close"}) {
            String body = mvc.perform(post(path).header("Authorization", "Bearer " + o[0]).header("X-Store-No", o[1])
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getContentAsString();
            assertNotEquals(70006, json.readTree(body).path("code").asInt(), path + " → " + body);
        }
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
    @DisplayName("★★★ 快递公司清单（TDD-商户App快递公司 E7）在新模型门店上能进门店，且店主读得到（不是 70006）")
    void express_companies_pass_admission_for_new_model_store() throws Exception {
        String[] o = newModelOwner("15965020116");
        JsonNode r = call(o[0], o[1], "/biz/express/companies");
        assertEquals(0, r.path("code").asInt(), r.toString());
        assertFalse(r.path("data").path("companies").isEmpty(), r.toString());
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

    @Test
    @DisplayName("★★ 新模型门店：建的券在券列表里看得到（2026-11-24 v2：POST /biz/coupons 成功，GET /biz/coupons 却是 []）")
    void couponCreatedOnNewModelStoreIsListed() throws Exception {
        String[] o = newModelOwner("15965020143");
        long now = System.currentTimeMillis();
        String body = "{\"title\":\"QA-coupon\",\"benefitMode\":\"CASH\",\"benefitValue\":1000,"
                + "\"minAmountMinor\":0,\"scopeType\":\"STORE\",\"scopeRefs\":[\"" + o[1] + "\"],"
                + "\"validityMode\":\"ABSOLUTE\",\"startAt\":" + now + ",\"endAt\":" + (now + 86_400_000L * 30)
                + ",\"issueMode\":\"TARGETED\",\"redeemMode\":\"ORDER\",\"timesTotal\":1,\"perUserLimit\":1}";
        JsonNode created = json.readTree(mvc.perform(post("/biz/coupons").header("Authorization", "Bearer " + o[0])
                        .header("X-Store-No", o[1]).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
        assertEquals(0, created.path("code").asInt(), created.toString());
        String couponNo = created.path("data").path("couponNo").asText();
        assertFalse(couponNo.isBlank(), created.toString());

        JsonNode list = call(o[0], o[1], "/biz/coupons?includeEnded=true");
        assertEquals(0, list.path("code").asInt(), list.toString());
        assertTrue(list.path("data").toString().contains(couponNo), "列表里没有刚建的券 " + couponNo + " → " + list);
        JsonNode detail = call(o[0], o[1], "/biz/coupons/" + couponNo);
        assertEquals(couponNo, detail.path("data").path("couponNo").asText(), detail.toString());
    }

    @Test
    @DisplayName("★★ 新模型门店：柜台录入、还没消费的会员在本店会员名单里看得到（2026-11-24 v2：建成功、名单为空）")
    void memberEnrolledAtCounterIsListedForStore() throws Exception {
        String[] o = newModelOwner("15965020142");
        String body = "{\"phone\":\"13912000142\",\"remark\":\"QA\",\"storeNo\":\"" + o[1] + "\",\"tagNos\":[]}";
        JsonNode created = json.readTree(mvc.perform(post("/biz/members").header("Authorization", "Bearer " + o[0])
                        .header("X-Store-No", o[1]).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
        assertEquals(0, created.path("code").asInt(), created.toString());
        String memberNo = created.path("data").path("memberNo").asText();
        assertFalse(memberNo.isBlank(), created.toString());
        for (String q : new String[] {"", "&status=LEAD", "&phone=13912000142"}) {
            JsonNode list = call(o[0], o[1], "/biz/members?storeNo=" + o[1] + "&page=1&size=50" + q);
            assertTrue(list.path("data").toString().contains(memberNo), q + " 名单里没有刚录入的会员 " + memberNo + " → " + list);
        }
        // 门店行专属条件（门店等级）对没下过单的人不成立：不应被补进来
        JsonNode byLevel = call(o[0], o[1], "/biz/members?storeNo=" + o[1] + "&level=ACTIVE&page=1&size=50");
        assertFalse(byLevel.path("data").toString().contains(memberNo), byLevel.toString());
    }
}
