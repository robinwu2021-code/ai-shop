package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ai.neargo.shop.common.OtpStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** P1 resource authority remains independent of STORE_V1 admission. */
@SpringBootTest(
        properties = {
            "shop.auth.token-store=db",
            "shop.management.signing-key=chain-admission-test-key-at-least-thirty-two-characters",
            "commerce.enabled=true",
            "commerce.access.active-key-id=test",
            "commerce.access.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "spring.autoconfigure.exclude=ai.neargo.commerce.starter.CommercePersistenceAutoConfiguration,"
                + "ai.neargo.common.security.SecurityAutoConfiguration,ai.neargo.common.security.SessionStoreAutoConfiguration,"
                + "ai.neargo.common.data.DataAutoConfiguration",
            "spring.datasource.url=jdbc:h2:mem:chain_admission;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class ChainManagementAdmissionFlowTest {

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

    private JsonNode request(String[] owner, String method, String path, Map<String, ?> body, String key)
            throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> get(path);
        };
        request.header("Authorization", "Bearer " + owner[0]);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (key != null) request.header("Idempotency-Key", key);
        String response = mvc.perform(request).andReturn().getResponse().getContentAsString();
        JsonNode result = json.readTree(response);
        assertTrue(result.has("code"), method + " " + path + " → " + response);
        return result;
    }

    private JsonNode success(String[] owner, String method, String path, Map<String, ?> body, String key)
            throws Exception {
        JsonNode result = request(owner, method, path, body, key);
        assertEquals(0, result.path("code").asInt(), method + " " + path + " → " + result);
        return result.path("data");
    }

    private String space(String[] owner, String name) throws Exception {
        JsonNode sources = success(owner, "GET", "/biz/management/context", null, null);
        String account = sources.path("bootstrapSources").path(0).path("accountNo").asText();
        assertFalse(account.isBlank(), sources.toString());
        return success(owner, "POST", "/biz/management/spaces",
                Map.of("name", name, "sourceAccountNo", account), name).path("managementNo").asText();
    }

    private void grant(String[] owner, String management, String type, String no, String permission)
            throws Exception {
        String actor = success(owner, "GET", "/biz/management/context", null, null).path("actorKey").asText();
        success(owner, "POST", "/biz/management/spaces/" + management + "/grants",
                Map.of("actorNo", actor, "resourceType", type, "resourceNo", no, "permissionCode", permission), null);
    }

    @Test
    void accountWithoutAnyStoreCanDiscoverManagementEntry() throws Exception {
        String token = login("15965020206");
        JsonNode context = success(new String[] {token, ""}, "GET", "/biz/management/context", null, null);
        assertTrue(context.path("spaces").isEmpty(), context.toString());
        assertFalse(context.path("canBootstrap").asBoolean(), context.toString());
        assertTrue(context.path("bootstrapSources").isEmpty(), context.toString());
    }

    @Test
    void accountCanUseAuthorizedBrandLifecycleWithoutSelection() throws Exception {
        String[] owner = newModelOwner("15965020201");
        String entity = jdbc.queryForObject("select entity_no from mch_store where store_no=?", String.class, owner[1]);
        String management = space(owner, "chain-admission");
        String brands = "/biz/management/brands";
        JsonNode created = success(owner, "POST", brands,
                Map.of("managementNo", management, "name", "准入品牌", "status", "ACTIVE"), "create-brand");
        String brand = created.path("brandNo").asText();
        assertFalse(brand.isBlank(), created.toString());
        String brandPath = brands + "/" + brand;
        assertEquals(brand, success(owner, "GET", brands + "?managementNo=" + management, null, null)
                .path("items").path(0).path("brandNo").asText());
        JsonNode detail = success(owner, "GET", brandPath, null, null);
        success(owner, "PUT", brandPath, Map.of("name", "准入品牌改名", "expectedRevision", detail.path("revision").asLong()), "edit-brand");
        detail = success(owner, "GET", brandPath, null, null);
        JsonNode changed = success(owner, "POST", brandPath + "/status-changes",
                Map.of("targetStatus", "INACTIVE", "reason", "停用验证", "expectedRevision", detail.path("revision").asLong()), "disable-brand");
        detail = success(owner, "GET", brandPath, null, null);
        assertEquals("INACTIVE", detail.path("status").asText(), changed.toString());
        success(owner, "POST", brandPath + "/status-changes",
                Map.of("targetStatus", "ACTIVE", "reason", "启用验证", "expectedRevision", detail.path("revision").asLong()), "activate-brand");
        detail = success(owner, "GET", brandPath, null, null);
        for (String permission : List.of("store.read", "store.brand.change"))
            grant(owner, management, "STORE", owner[1], permission);
        String storePath = "/biz/stores/" + owner[1];
        JsonNode operating = success(owner, "GET", storePath + "/operating-context?managementNo=" + management, null, null);
        assertTrue(operating.path("brand").isNull(), operating.toString());
        JsonNode check = success(owner, "POST", storePath + "/brand-changes/check",
                Map.of("managementNo", management, "toBrandNo", brand), null);
        assertTrue(check.path("allowed").asBoolean(), check.toString());
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("managementNo", management); command.put("toBrandNo", brand);
        command.put("checkToken", check.path("checkToken").asText());
        command.put("expectedStoreRevision", check.path("storeVersion").asLong());
        command.put("expectedScopeRevision", 0);
        command.put("expectedToBrandRevision", check.path("toRevision").asLong());
        command.put("reason", "组织关联");
        JsonNode submitted = success(owner, "POST", storePath + "/brand-changes", command, "bind-brand");
        String changePath = "/biz/management/brand-changes/" + submitted.path("changeNo").asText();
        JsonNode change = success(owner, "GET", changePath, null, null);
        JsonNode approved = success(owner, "POST", changePath + "/decisions",
                Map.of("decision", "APPROVE", "expectedRevision", change.path("revision").asLong(), "reason", "同意关联"), "approve-brand");
        assertEquals("COMPLETED", approved.path("status").asText(), approved.toString());
        assertEquals(1, success(owner, "GET", brandPath + "/stores", null, null).path("items").size());
        assertFalse(success(owner, "GET", brandPath + "/audit-events", null, null).path("items").isEmpty());
        for (String permission : List.of("entity.read", "payment.merchant.read"))
            grant(owner, management, "ENTITY", entity, permission);
        JsonNode candidates = success(owner, "GET", "/biz/management/entities/" + entity
                + "/payment-merchants?managementNo=" + management + "&storeNo=" + owner[1], null, null);
        assertTrue(candidates.isArray(), candidates.toString());
        assertEquals(entity, jdbc.queryForObject("select entity_no from mch_store where store_no=?", String.class, owner[1]));
    }

    @Test
    void accountDoesNotGrantManagementAuthority() throws Exception {
        String[] owner = newModelOwner("15965020202");
        String management = space(owner, "chain-boundary");
        String brand = success(owner, "POST", "/biz/management/brands",
                Map.of("managementNo", management, "name", "权限边界品牌", "status", "ACTIVE"), "boundary-brand")
                .path("brandNo").asText();
        String[] other = newModelOwner("15965020203");
        JsonNode unselected = call(owner[0], null, "/biz/management/brands?managementNo=" + management);
        assertEquals(0, unselected.path("code").asInt(), unselected.toString());
        assertEquals(brand, unselected.path("data").path("items").path(0).path("brandNo").asText());
        for (String path : List.of("/biz/management/brands/" + brand,
                "/biz/management/brands/" + brand + "/stores",
                "/biz/management/brands/" + brand + "/audit-events",
                "/biz/stores/" + other[1] + "/operating-context?managementNo=" + management)) {
            JsonNode denied = request(other, "GET", path, null, null);
            assertNotEquals(0, denied.path("code").asInt(), path + " → " + denied);
            assertNotEquals(70006, denied.path("code").asInt(), path + " must reach resource authorization → " + denied);
        }
        String entity = jdbc.queryForObject("select entity_no from mch_store where store_no=?", String.class, owner[1]);
        String merchants = "/biz/management/entities/" + entity + "/payment-merchants?managementNo=" + management;
        JsonNode noFinance = request(owner, "GET", merchants, null, null);
        assertNotEquals(0, noFinance.path("code").asInt(), noFinance.toString());
        assertNotEquals(70006, noFinance.path("code").asInt(), noFinance.toString());
        for (String permission : List.of("entity.read", "payment.merchant.read")) grant(owner, management, "ENTITY", entity, permission);
        // Grant explicit read of a different owned subject's store: read authority cannot change entity matching.
        String foreignEntity = jdbc.queryForObject("select entity_no from mch_store where store_no=?", String.class, other[1]);
        String userNo = jdbc.queryForObject("select user_no from mch_account where entity_no=? and is_owner=1", String.class, entity);
        jdbc.update("update mch_account set user_no=? where entity_no=? and is_owner=1", userNo, foreignEntity);
        grant(owner, management, "STORE", other[1], "store.read");
        JsonNode cross = request(owner, "GET", merchants + "&storeNo=" + other[1], null, null);
        assertNotEquals(0, cross.path("code").asInt(), cross.toString());
        assertNotEquals(70006, cross.path("code").asInt(), cross.toString());
        assertNotEquals(entity, foreignEntity);
        for (String path : List.of("/biz/management/brands/" + brand + "/unknown", "/biz/management/brand-changes/C1/unknown",
                "/biz/stores/" + owner[1] + "/brand-changes/unknown", "/biz/management/entities/" + entity + "/payment-merchants/unknown",
                "/biz/ops/chain"))
            assertEquals(70006, request(owner, "GET", path, null, null).path("code").asInt(), path);
        assertEquals(70006, request(owner, "DELETE", "/biz/management/brands/" + brand, null, null).path("code").asInt());
        assertEquals(70006, request(owner, "GET", "/biz/stores/" + owner[1] + "/brand-changes/check", null, null).path("code").asInt());
    }

    @Test
    void delegatedAdministratorCanChangeAuthorizedStoreWithoutMembership() throws Exception {
        String[] owner = newModelOwner("15965020204");
        String[] administrator = newModelOwner("15965020205");
        String management = space(owner, "delegated-chain");
        String brand = success(owner, "POST", "/biz/management/brands",
                Map.of("managementNo", management, "name", "跨店授权品牌", "status", "ACTIVE"), "delegated-brand")
                .path("brandNo").asText();
        String actor = success(administrator, "GET", "/biz/management/context", null, null).path("actorKey").asText();
        String entity = jdbc.queryForObject("select entity_no from mch_store where store_no=?", String.class, owner[1]);
        String administratorUser = actor.substring(actor.indexOf(':') + 1);
        assertEquals(0, jdbc.queryForObject("select count(*) from mch_account where user_no=? and entity_no=?",
                Integer.class, administratorUser, entity));
        // The actor belongs to a different entity. Only these exact resource grants authorize target operations.
        for (String[] permission : List.of(
                new String[] {"SPACE", management, "management.enter"},
                new String[] {"BRAND", brand, "brand.read"},
                new String[] {"BRAND", brand, "brand.manage"},
                new String[] {"BRAND", brand, "brand.change.decide"},
                new String[] {"STORE", owner[1], "store.read"},
                new String[] {"STORE", owner[1], "store.brand.change"})) {
            success(owner, "POST", "/biz/management/spaces/" + management + "/grants",
                    Map.of("actorNo", actor, "resourceType", permission[0], "resourceNo", permission[1],
                            "permissionCode", permission[2]), null);
        }
        String brandPath = "/biz/management/brands/" + brand;
        JsonNode detail = success(administrator, "GET", brandPath, null, null);
        success(administrator, "PUT", brandPath,
                Map.of("name", "管理员改名", "expectedRevision", detail.path("revision").asLong()), "delegated-edit");
        String storePath = "/biz/stores/" + owner[1];
        assertEquals(owner[1], success(administrator, "GET", storePath + "/operating-context?managementNo=" + management,
                null, null).path("storeNo").asText());
        JsonNode check = success(administrator, "POST", storePath + "/brand-changes/check",
                Map.of("managementNo", management, "toBrandNo", brand), null);
        assertTrue(check.path("allowed").asBoolean(), check.toString());
        JsonNode submitted = success(administrator, "POST", storePath + "/brand-changes",
                Map.of("managementNo", management, "toBrandNo", brand, "checkToken", check.path("checkToken").asText(),
                        "expectedStoreRevision", check.path("storeVersion").asLong(),
                        "expectedScopeRevision", check.path("bindingRevision").asLong(),
                        "expectedToBrandRevision", check.path("toRevision").asLong(), "reason", "受托跨店关联"), "delegated-bind");
        String changePath = "/biz/management/brand-changes/" + submitted.path("changeNo").asText();
        JsonNode change = success(administrator, "GET", changePath, null, null);
        assertEquals("COMPLETED", success(administrator, "POST", changePath + "/decisions",
                Map.of("decision", "APPROVE", "expectedRevision", change.path("revision").asLong(), "reason", "受托确认"),
                "delegated-approve").path("status").asText());
        // Management-space and brand authority do not imply entity finance access.
        JsonNode finance = request(administrator, "GET", "/biz/management/entities/" + entity
                + "/payment-merchants?managementNo=" + management, null, null);
        assertNotEquals(0, finance.path("code").asInt(), finance.toString());
        assertNotEquals(70006, finance.path("code").asInt(), finance.toString());
    }

}
