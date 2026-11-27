package ai.neargo.shop.scenario;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.support.TestLogin;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 运营接口密钥（TDD-基础能力库对齐 §2.2「1h 开放应用」）：凭据在密钥库 sec_key，只存摘要；sys_ops_api_key 停写停读 */
@SpringBootTest(properties = "shop.secret.env=SANDBOX")
@ActiveProfiles("test")
class OpsApiKeyFlowTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private MockMvc mvc;
    private String admin;
    private String staffNo;
    private String keys;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = TestLogin.admin(mvc, json);
        staffNo = "ST-KEY-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, created_at, updated_at)
                SELECT ?, ?, password, 'Key test', '["BD"]', 'ACTIVE', NOW(), NOW()
                FROM sys_ops_staff WHERE username='admin'
                """, staffNo, staffNo);
        roles("BD");
        keys = "/ops/staffs/" + staffNo + "/api-keys";
    }

    @Test
    void bindsKeyAndAppliesCurrentRolesAndRouteScope() throws Exception {
        String keyNo = UUID.randomUUID().toString();
        String key = issueKey(keyNo, "READ_ONLY");
        assertThat(jdbc.queryForObject("""
                SELECT v.material_digest FROM sec_key_version v JOIN sec_key k ON k.id=v.sec_key_id
                WHERE v.external_key_id=? AND v.storage_tier='DIGEST' AND v.sec_secret_blob_id IS NULL
                    AND k.namespace='PLATFORM' AND k.owner_type='OPS_STAFF'
                    AND k.owner_id=(SELECT id FROM sys_ops_staff WHERE staff_no=?)
                """, String.class, keyNo, staffNo)).isEqualTo(TokenHash.of(key));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_ops_api_key", Integer.class))
                .as("旧表停写").isZero();
        getAs(keys, admin).andExpect(jsonPath("$.data[0].keyNo").value(keyNo))
                .andExpect(jsonPath("$.data[0].apiKey").doesNotExist());
        issue(keyNo, "READ_ONLY").andExpect(jsonPath("$.code").value(10409));
        getAs("/ops/auth/me", key).andExpect(jsonPath("$.data.staffNo").value(staffNo));
        getAs("/ops/stores", key).andExpect(jsonPath("$.code").value(0));
        getAs("/ops/staffs", key).andExpect(jsonPath("$.code").value(10403));
        postAs("/ops/industries/RETAIL/enabled", key, "{}")
                .andExpect(jsonPath("$.code").value(10403));
        postAs("/biz/auth/switch", key, "{}").andExpect(status().isUnauthorized());
        roles();
        getAs("/ops/stores", key).andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    void allowsManagementSubjectToRoleAndRejectsExpiredOrRevokedKeys() throws Exception {
        roles("SUPER_ADMIN");
        String keyNo = UUID.randomUUID().toString();
        String key = issueKey(keyNo, "MANAGE");
        getAs(keys, key).andExpect(jsonPath("$.code").value(10403));
        getAs("/ops/stores/qrcodes", key).andExpect(jsonPath("$.code").value(10403));
        postAs("/ops/stores/KEY-TEST-MISSING/channels/EXPRESS/lock", key, "{}")
                .andExpect(jsonPath("$.code").value(10404));
        postAs(keys + "/" + keyNo + "/revoke", admin, null).andExpect(jsonPath("$.code").value(0));
        postAs(keys + "/" + keyNo + "/revoke", admin, null).andExpect(jsonPath("$.code").value(0));
        getAs("/ops/auth/me", key).andExpect(status().isUnauthorized());

        String expiredNo = UUID.randomUUID().toString();
        String expired = issueKey(expiredNo, "READ_ONLY");
        jdbc.update("UPDATE sec_key_version SET valid_to=valid_from WHERE external_key_id=?", expiredNo);
        getAs("/ops/auth/me", expired).andExpect(status().isUnauthorized());
    }

    @Test
    void legacyKeyTableIsNoLongerRead() throws Exception {
        String legacy = "oak_" + "Z".repeat(43);
        String keyNo = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO sys_ops_api_key
                    (key_no,staff_no,secret_hash,label,access_level,issued_by,created_at,expires_at)
                VALUES (?,?,?,'legacy','READ_ONLY','admin',?,?)
                """, keyNo, staffNo, TokenHash.of(legacy), now, now + 86_400_000L);
        try {
            getAs("/ops/auth/me", legacy).andExpect(status().isUnauthorized());
            getAs(keys, admin).andExpect(jsonPath("$.data[?(@.keyNo=='" + keyNo + "')]").isEmpty());
        } finally {
            jdbc.update("DELETE FROM sys_ops_api_key WHERE key_no=?", keyNo);
        }
    }

    @Test
    void disablingStaffRevokesKeysPermanently() throws Exception {
        String key = issueKey(UUID.randomUUID().toString(), "READ_ONLY");
        postAs("/ops/staffs/" + staffNo + "/enabled", admin, "{\"enabled\":false}")
                .andExpect(jsonPath("$.code").value(0));
        getAs("/ops/auth/me", key).andExpect(status().isUnauthorized());
        postAs("/ops/staffs/" + staffNo + "/enabled", admin, "{\"enabled\":true}")
                .andExpect(jsonPath("$.code").value(0));
        getAs("/ops/auth/me", key).andExpect(status().isUnauthorized());
    }

    @Test
    void limitedDataScopeBlocksCrossPlatformOperations() throws Exception {
        String key = issueKey(UUID.randomUUID().toString(), "MANAGE");
        String lock = "/ops/stores/KEY-TEST-MISSING/channels/EXPRESS/lock";
        postAs(lock, key, null).andExpect(jsonPath("$.code").value(10404));
        jdbc.update("UPDATE sys_ops_staff SET merchant_no='KEY-TEST-BOUND' WHERE staff_no=?", staffNo);
        getAs("/ops/stores?merchantNo=KEY-TEST-OTHER", key)
                .andExpect(jsonPath("$.data.total").value(0));
        postAs(lock, key, null).andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    void couponIssuesFollowStaffMerchantScope() throws Exception {
        for (String merchant : new String[] {"KEY-TEST-BOUND", "KEY-TEST-OTHER"}) {
            jdbc.update("""
                    INSERT INTO mkt_coupon (coupon_no,title,type,entity_no,start_at,end_at,created_at,updated_at)
                    VALUES (?,?,'CASH',?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, merchant, merchant, merchant);
            jdbc.update("""
                    INSERT INTO mkt_coupon_issue (issue_no,coupon_no,coupon_name,target,created_at,updated_at)
                    VALUES (?,?,?,'SINGLE_USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, merchant, merchant, merchant);
        }
        roles("CAMPAIGN_OPS");
        String session = TestLogin.operator(mvc, json, staffNo, "admin123");
        getAs("/ops/coupon-issues?couponNo=KEY-TEST-OTHER", session)
                .andExpect(jsonPath("$.data.total").value(1));

        jdbc.update("UPDATE sys_ops_staff SET merchant_no='KEY-TEST-BOUND' WHERE staff_no=?", staffNo);
        session = TestLogin.operator(mvc, json, staffNo, "admin123");
        getAs("/ops/coupon-issues?couponNo=KEY-TEST-BOUND", session)
                .andExpect(jsonPath("$.data.total").value(1));
        getAs("/ops/coupon-issues?couponNo=KEY-TEST-OTHER", session)
                .andExpect(jsonPath("$.code").value(10404));
        getAs("/ops/coupon-issues", session)
                .andExpect(jsonPath("$.data.records[?(@.couponNo=='KEY-TEST-BOUND')]").isNotEmpty())
                .andExpect(jsonPath("$.data.records[?(@.couponNo=='KEY-TEST-OTHER')]").isEmpty());
    }

    @Test
    void initialPasswordBlocksIssueAndChangingPasswordRevokesKeys() throws Exception {
        jdbc.update("UPDATE sys_ops_staff SET must_change_password=1 WHERE staff_no=?", staffNo);
        issue(UUID.randomUUID().toString(), "READ_ONLY").andExpect(jsonPath("$.code").value(10403));
        jdbc.update("UPDATE sys_ops_staff SET must_change_password=0 WHERE staff_no=?", staffNo);
        String key = issueKey(UUID.randomUUID().toString(), "READ_ONLY");
        String staffToken = TestLogin.operator(mvc, json, staffNo, "admin123");
        postAs("/ops/staffs/me/password", staffToken,
                """
                {"oldPassword":"admin123","newPassword":"OpsKeyTest#2026"}
                """).andExpect(jsonPath("$.code").value(0));
        getAs("/ops/auth/me", key).andExpect(status().isUnauthorized());
    }

    @Test
    void selfRegistersUsingSessionAndUsesTheLocallyHeldKey() throws Exception {
        String own = "/ops/staffs/me/api-keys";
        String staffToken = TestLogin.operator(mvc, json, staffNo, "admin123");
        byte[] entropy = new byte[32];
        new SecureRandom().nextBytes(entropy);
        String localKey = "oak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String keyNo = UUID.randomUUID().toString();
        String digest = TokenHash.of(localKey);
        String request = """
                {"keyNo":"%s","label":"Local","validDays":7,"secretHash":"%s"}
                """.formatted(keyNo, digest);

        postAs(own, staffToken, request)
                .andExpect(jsonPath("$.data.keyNo").value(keyNo))
                .andExpect(jsonPath("$.data.access").value("READ_ONLY"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.secretHash").doesNotExist())
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
        postAs(own, staffToken, request).andExpect(jsonPath("$.data.keyNo").value(keyNo));
        postAs(own, staffToken, request.replace("Local", "Different")).andExpect(jsonPath("$.code").value(10409));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_log WHERE op_action='OPS_API_KEY_ISSUE' AND target=?
                """, Integer.class, staffNo + ":" + keyNo)).isEqualTo(1);
        getAs(own, staffToken).andExpect(jsonPath("$.data[0].keyNo").value(keyNo));
        getAs(own, admin).andExpect(jsonPath("$.data[?(@.keyNo=='" + keyNo + "')]").isEmpty());
        getAs(own, localKey).andExpect(jsonPath("$.code").value(10403));
        postAs(own + "/" + keyNo + "/revoke", admin, null).andExpect(jsonPath("$.code").value(0));
        getAs("/ops/auth/me", localKey).andExpect(jsonPath("$.data.staffNo").value(staffNo));
        getAs("/ops/stores", localKey).andExpect(jsonPath("$.code").value(0));
        getAs("/ops/auth/me", digest).andExpect(status().isUnauthorized());
        postAs(own + "/" + keyNo + "/revoke", staffToken, null).andExpect(jsonPath("$.code").value(0));
        postAs(own + "/" + keyNo + "/revoke", staffToken, null).andExpect(jsonPath("$.code").value(0));
        postAs(own, staffToken, request).andExpect(jsonPath("$.data.status").value("REVOKED"));
        getAs("/ops/auth/me", localKey).andExpect(status().isUnauthorized());
    }

    @Test
    void selfRegistrationRequiresAnEligibleOperatorSession() throws Exception {
        String own = "/ops/staffs/me/api-keys";
        String staffToken = TestLogin.operator(mvc, json, staffNo, "admin123");
        String request = """
                {"keyNo":"%s","label":"Local","validDays":7,"secretHash":"%s"}
                """.formatted(UUID.randomUUID(), "a".repeat(64));
        postAs(own, staffToken, request.replace("a".repeat(64), "invalid"))
                .andExpect(jsonPath("$.code").value(10400));
        mvc.perform(post(own).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE sys_ops_staff SET must_change_password=1 WHERE staff_no=?", staffNo);
        postAs(own, staffToken, request).andExpect(jsonPath("$.code").value(10403));
    }

    private String issueKey(String keyNo, String access) throws Exception {
        String body = issue(keyNo, access).andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("apiKey").asString();
    }

    private ResultActions issue(String keyNo, String access) throws Exception {
        return postAs(keys, admin, """
                {"keyNo":"%s","label":"CLI test","access":"%s","validDays":7}
                """.formatted(keyNo, access));
    }

    private ResultActions getAs(String path, String credential) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + credential));
    }

    private ResultActions postAs(String path, String credential, String body) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + credential);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    /** 账号的角色在 sys_role_member（TDD-基础能力库对齐 2a），不再看 sys_ops_staff.roles */
    private void roles(String... roleCodes) {
        jdbc.update("DELETE FROM sys_role_member WHERE end_code='OPS' AND subject_no=?", staffNo);
        for (String role : roleCodes) {
            jdbc.update("INSERT INTO sys_role_member (end_code, subject_no, role_code, created_at, updated_at)"
                    + " VALUES ('OPS', ?, ?, NOW(), NOW())", staffNo, role);
        }
    }
}
