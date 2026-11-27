package ai.neargo.inventory.svc;

import ai.neargo.inventory.domain.service.OpenApiCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 平台层端到端（独立进程、空库、H2）：
 * 运维开通业主 → 签服务凭证 → 装档案 → 宿主带凭证与幂等键建仓库 → 审计。
 *
 * <p>本套按「测试环境」配置但只放行 SHAREHUB 与 CHARGING —— 用来同时验证实例放行（AC-28）。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_platform;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=DEVICE",
        "inventory.instance.allowed-systems=SHAREHUB,CHARGING",
        "inventory.admin.token=admin-test-token"})
class ServicePlatformFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROFILE = """
            {"schemaVersion":1,"capabilities":["SERIAL","DISPATCH_POOL"],
             "targetTypes":[{"code":"MERCHANT","nameKey":"m","parentType":null},
                            {"code":"SITE","nameKey":"s","parentType":"MERCHANT"},
                            {"code":"PERSON","nameKey":"p","parentType":null}],
             "inboundPurposes":[{"code":"PURCHASE","basisRequired":false,"attachmentRequired":false}],
             "outboundPurposes":[{"code":"ISSUE","requiredTargets":["MERCHANT","PERSON"],
                                  "optionalTargets":["SITE"],"handover":"POOL"}],
             "gateRefTypes":["QC"],"countReview":{"required":true,"reviewerMustDiffer":true}}""";

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;
    @Autowired @Qualifier("invDataSource") DataSource invDataSource;
    @Autowired @Qualifier("invSecretDataSource") DataSource secretDataSource;
    @Autowired OpenApiCredentialService credentials;

    MockMvc mvc;
    JdbcTemplate sql;

    @BeforeEach
    void setUp() {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
        sql = new JdbcTemplate(invDataSource);
    }

    @Test
    void ownerCredentialProfileAndIdempotentWarehouse() throws Exception {
        String owner = openOwner("SHAREHUB", "OP-" + UUID.randomUUID());
        String bearer = issue(owner);
        putProfile(owner, PROFILE).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/inventory/v1/profile").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.profile.gateRefTypes[0]").value("QC"));

        // AC-03：同键同参 → 同一个结果，只建一个仓
        String key = "REQ-" + UUID.randomUUID();
        String first = body(createWarehouse(bearer, key, "W1").andExpect(status().isOk()).andReturn());
        String second = body(createWarehouse(bearer, key, "W1").andExpect(status().isOk()).andReturn());
        assertThat(second).isEqualTo(first);
        assertThat(sql.queryForObject("select count(*) from inv_location where owner_id=? and kind='WAREHOUSE'",
                Integer.class, owner)).isEqualTo(1);

        // AC-03：同键异参 → 409
        createWarehouse(bearer, key, "W2").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("inventory.command.conflict"));

        // 回包丢了先查结果
        mvc.perform(get("/api/inventory/v1/commands/" + key).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commandType").value("warehouse.create"));

        // 审计：一条，带操作人与幂等键
        assertThat(sql.queryForMap("select actor, request_id, action from inv_audit where owner_id=?", owner))
                .containsEntry("actor", "STAFF:E1003").containsEntry("request_id", key)
                .containsEntry("action", "warehouse.create");
    }

    @Test
    void ownersAreIsolatedByCredential() throws Exception {
        String a = openOwner("SHAREHUB", "A-" + UUID.randomUUID());
        String b = openOwner("CHARGING", "B-" + UUID.randomUUID());
        String bearerA = issue(a);
        String bearerB = issue(b);
        createWarehouse(bearerA, "K-" + UUID.randomUUID(), "only-a").andExpect(status().isOk());

        // AC-15：B 的凭证看不到 A 的仓；请求里怎么写业主都没用 —— 业主只取自凭证
        JsonNode seenByB = JSON.readTree(body(mvc.perform(get("/api/inventory/v1/warehouses")
                .header("Authorization", bearerB).param("ownerId", a)).andExpect(status().isOk()).andReturn()));
        assertThat(seenByB.findValuesAsString("name")).doesNotContain("only-a");
    }

    @Test
    void shopCredentialIsRejectedByTheDeviceInstance() throws Exception {
        // AC-28：本套只放行 SHAREHUB / CHARGING；电商的凭证即使有效也进不来
        String shopOwner = openOwner("SHOP", "S-" + UUID.randomUUID());
        mvc.perform(get("/api/inventory/v1/warehouses").header("Authorization", issue(shopOwner)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("inventory.instance.system_not_served"));
    }

    @Test
    void writesAreRejectedWhenThisProcessIsNotTheWriter() throws Exception {
        // AC-19：这本账的写入方翻回内嵌，独立服务就只能读
        String owner = openOwner("SHAREHUB", "W-" + UUID.randomUUID());
        String bearer = issue(owner);
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/write-authority")
                .content("{\"writeAuthority\":\"EMBEDDED\"}")).andExpect(status().isOk());
        createWarehouse(bearer, "K-" + UUID.randomUUID(), "W").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("inventory.owner.not_writer"));
        mvc.perform(get("/api/inventory/v1/warehouses").header("Authorization", bearer)).andExpect(status().isOk());
    }

    @Test
    void badCredentialsAllLookTheSame() throws Exception {
        String owner = openOwner("SHAREHUB", "C-" + UUID.randomUUID());
        String bearer = issue(owner);
        String wrongSecret = bearer.substring(0, bearer.indexOf(':') + 1) + "nope";
        for (String auth : new String[] {wrongSecret, "Bearer SKnope:nope", "Bearer garbage", ""}) {
            mvc.perform(get("/api/inventory/v1/warehouses").header("Authorization", auth))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("inventory.unauthorized"));
        }
    }

    @Test
    void credentialsLiveInTheSecretStoreAsDigestsBoundToTheirOwner() throws Exception {
        // Q18：凭证进密钥库（只存摘要），inv_open_credential 停读停写；appKey 即凭据号，也即 credentialId
        String owner = openOwner("SHAREHUB", "K-" + UUID.randomUUID());
        JsonNode ownerCred = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"t\",\"capabilities\":[\"SERIAL\",\"DISPATCH_POOL\"]}"))
                .andExpect(status().isOk()).andReturn()));
        JsonNode systemCred = JSON.readTree(body(admin(post("/api/inventory/admin/v1/systems/SHAREHUB/credentials")
                .content("{\"name\":\"hub\"}")).andExpect(status().isOk()).andReturn()));
        assertThat(ownerCred.get("credentialId").asString()).isEqualTo(ownerCred.get("appKey").asString());

        assertThat(sql.queryForObject("select count(*) from inv_open_credential", Integer.class)).isZero();
        JdbcTemplate sec = new JdbcTemplate(secretDataSource);
        Long ownerPk = sql.queryForObject("select id from inv_owner where owner_id=?", Long.class, owner);
        assertThat(sec.queryForMap("select k.owner_type, k.owner_id, v.storage_tier, v.scope, v.material_digest"
                        + " from sec_key k join sec_key_version v on v.sec_key_id=k.id where v.external_key_id=?",
                ownerCred.get("appKey").asString()))
                .containsEntry("owner_type", "INV_OWNER").containsEntry("owner_id", ownerPk)
                .containsEntry("storage_tier", "DIGEST")
                .containsEntry("scope", "scopes=service;caps=DISPATCH_POOL,SERIAL;owner=" + owner)
                .doesNotContainValue(ownerCred.get("appSecret").asString());
        Long systemPk = sql.queryForObject("select id from inv_client_system where system_code='SHAREHUB'", Long.class);
        assertThat(sec.queryForMap("select k.owner_type, k.owner_id, v.scope from sec_key k"
                        + " join sec_key_version v on v.sec_key_id=k.id where v.external_key_id=?",
                systemCred.get("appKey").asString()))
                .containsEntry("owner_type", "INVENTORY_SYSTEM").containsEntry("owner_id", systemPk)
                .containsEntry("scope", "scopes=service;system=SHAREHUB");

        String bearer = "Bearer " + ownerCred.get("appKey").asString() + ":" + ownerCred.get("appSecret").asString();
        mvc.perform(get("/api/inventory/v1/warehouses").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/inventory/v1/warehouses").header("Authorization",
                        "Bearer " + systemCred.get("appKey").asString() + ":" + systemCred.get("appSecret").asString())
                        .header("X-Inventory-Owner-Ref", "K-none"))
                .andExpect(status().isNotFound());
        // appKey 与 secret 要配对：拿别人的 key 配自己的 secret 不行
        mvc.perform(get("/api/inventory/v1/warehouses").header("Authorization",
                        "Bearer " + systemCred.get("appKey").asString() + ":" + ownerCred.get("appSecret").asString()))
                .andExpect(status().isUnauthorized());

        assertThat(credentials.ownerIdOfCredential(ownerCred.get("credentialId").asString())).isEqualTo(owner);
        assertThat(credentials.list(owner)).singleElement().satisfies(l -> {
            assertThat(l.appKey()).isEqualTo(ownerCred.get("appKey").asString());
            assertThat(l.scopes()).isEqualTo("service");
            assertThat(l.status()).isEqualTo("ACTIVE");
            assertThat(l.lastUsedAt()).as("密钥库不记单张凭据的最近使用").isNull();
        });
        credentials.revoke(ownerCred.get("credentialId").asString());
        mvc.perform(get("/api/inventory/v1/warehouses").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
        assertThat(credentials.list(owner)).singleElement()
                .satisfies(l -> assertThat(l.status()).isEqualTo("REVOKED"));
    }

    @Test
    void adminEndpointsNeedTheAdminTokenAndRejectBadProfiles() throws Exception {
        mvc.perform(post("/api/inventory/admin/v1/owners").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Inventory-Actor", "OPS:x")
                        .content("{\"systemCode\":\"SHAREHUB\",\"externalRef\":\"X\",\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        String owner = openOwner("SHAREHUB", "P-" + UUID.randomUUID());
        // ISSUE 走出库池却没开 DISPATCH_POOL —— 存不进去，并说清是哪一条
        putProfile(owner, PROFILE.replace("\"SERIAL\",\"DISPATCH_POOL\"", "\"SERIAL\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.args[0]").value("outboundPurposes.handover.requiresDispatchPool:ISSUE"));
    }

    private String openOwner(String system, String externalRef) throws Exception {
        MvcResult r = admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"" + system + "\",\"externalRef\":\"" + externalRef + "\",\"name\":\"n\"}"))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(body(r)).get("ownerId").asString();
    }

    private String issue(String owner) throws Exception {
        JsonNode issued = JSON.readTree(body(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"t\"}")).andExpect(status().isOk()).andReturn()));
        return "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString();
    }

    private ResultActions putProfile(String owner, String profile)
            throws Exception {
        return admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + profile + ",\"reason\":\"init\"}"));
    }

    private ResultActions createWarehouse(String bearer, String key, String name)
            throws Exception {
        return mvc.perform(post("/api/inventory/v1/warehouses").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer).header("Idempotency-Key", key)
                .header("X-Inventory-Actor", "STAFF:E1003")
                .content("{\"name\":\"" + name + "\"}"));
    }

    private ResultActions admin(MockHttpServletRequestBuilder req)
            throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)
                .header("X-Inventory-Admin-Token", "admin-test-token").header("X-Inventory-Actor", "OPS:test"));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }
}
