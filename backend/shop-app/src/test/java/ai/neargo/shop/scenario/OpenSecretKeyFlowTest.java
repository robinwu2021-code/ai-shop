package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.shop.pay.api.guard.open.service.PartnerKeyService;
import ai.neargo.shop.pay.api.guard.open.service.PartnerKeyService.IssuedCredential;
import ai.neargo.shop.pay.openapp.AppSignatureVerifier;
import ai.neargo.shop.pay.openapp.AppSigner;
import ai.neargo.shop.pay.secret.SecretAppSignatures;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 密钥在平台密钥模块里（{@code shop.pay.open.key-source=secret}；开发计划 M3.1 / M3.2）：真发密钥、真轮换、真验签。
 * 验签命中哪一版记进请求记录（{@code sign_key_version}）；{@code X-Key-Serial} 指定版本时只认那一版。
 */
@SpringBootTest(properties = {"shop.pay.open.key-source=secret",
        "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        // 密钥模块要环境与主密钥才肯加解密；这把主密钥只给本测试用（32 字节，Base64）
        "shop.secret.env=SANDBOX",
        "shop.secret.master-keys[0].alias=test-pay-mk-1",
        "shop.secret.master-keys[0].namespace=PAY",
        "shop.secret.master-keys[0].material=dGVzdC1vbmx5LW1hc3Rlci1rZXktMDEyMzQ1Njc4OSE=",
        "shop.secret.master-keys[0].active=true"})
@ActiveProfiles("test")
class OpenSecretKeyFlowTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AppSignatureVerifier verifier;
    @Autowired private AppSigner signer;
    @Autowired private PartnerKeyService partnerKeys;
    @Autowired private KeyRegistry registry;
    @Autowired private ai.neargo.shop.pay.merchant.service.PayMerchantBindingService bindings;
    @Autowired private ai.neargo.secret.api.SecretVault vault;

    private MockMvc mvc;
    private String appNo;

    @BeforeEach
    void world() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        appNo = "APP-SK-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO pay_app (app_no, name, owner_type, status, scopes, rate_limit_qps,"
                + " daily_refund_limit_minor, sandbox, created_at, updated_at) VALUES (?, '密钥模块', 'PARTNER', 'ACTIVE',"
                + " 'pay:read', 1000, 0, 1, NOW(), NOW())", appNo);
    }

    /** 发一版 AppSecret 并走完「验证 → 另一人启用」（两人规则） */
    private IssuedCredential issueAndEnable() {
        return enable(partnerKeys.issueAppSecret(appNo, "ops-alice"), KeyPurpose.INBOUND_HMAC);
    }

    /** 发一版 EncryptKey（敏感字段加密，M3.5）并启用 */
    private IssuedCredential issueEncryptKeyAndEnable() {
        return enable(partnerKeys.issueEncryptKey(appNo, "ops-alice"), KeyPurpose.PARTNER_DATA);
    }

    private IssuedCredential enable(IssuedCredential c, KeyPurpose purpose) {
        long appId = jdbc.queryForObject("SELECT id FROM pay_app WHERE app_no=?", Long.class, appNo);
        long keyId = registry.find(new KeyOwner(SecretNamespace.PAY, "PARTNER_APP", appId), purpose)
                .orElseThrow().keyId();
        KeyVersionRef ref = new KeyVersionRef(keyId, c.version());
        registry.markVerified(ref, "test", "ops-alice");
        registry.enable(ref, "ops-bob", null);
        return c;
    }

    /** 用整串 AppSecret 签（HMAC 只用前缀之后那段 —— 接入方照 API 清单 §7 做） */
    private MockHttpServletResponse getOrder(IssuedCredential signWith, String keySerialHeader, String encryptSerial,
                                             String requestId) throws Exception {
        String path = "/open/pay/v1/orders/PYO-NOPE";
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String hmacKey = signWith.secret().substring("sk_test_".length());
        String sig = OpenApiFlowTest.sign(hmacKey, "GET", path, Map.of(), appNo, ts, nonce, "");
        MockHttpServletRequestBuilder b = get(path).contentType(MediaType.APPLICATION_JSON).content("")
                .header("X-App-Id", appNo).header("X-Timestamp", String.valueOf(ts)).header("X-Nonce", nonce)
                .header("X-Signature", sig).header("X-Request-Id", requestId);
        if (keySerialHeader != null) {
            b.header("X-Key-Serial", keySerialHeader);
        }
        if (encryptSerial != null) {
            b.header("X-Encrypt-Serial", encryptSerial);
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    /** 接入方的做法（P3，独立实现）：AES-256-GCM，密钥 = EncryptKey 的 UTF-8 字节，AAD = AppId|字段路径，值 = Base64(IV‖密文‖Tag) */
    private String encryptField(IssuedCredential encryptKey, String fieldPath, String plaintext) throws Exception {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(
                encryptKey.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8), "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        c.updateAAD((appNo + "|" + fieldPath).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(ct, 0, packed, iv.length, ct.length);
        return java.util.Base64.getEncoder().encodeToString(packed);
    }

    private MockHttpServletResponse call(String method, String path, String body, IssuedCredential signWith,
                                         String encryptSerial) throws Exception {
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String sig = OpenApiFlowTest.sign(signWith.secret().substring("sk_test_".length()), method, path, Map.of(), appNo,
                ts, nonce, body);
        MockHttpServletRequestBuilder b = ("GET".equals(method) ? get(path)
                : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
                .contentType(MediaType.APPLICATION_JSON).content(body).header("X-App-Id", appNo)
                .header("X-Timestamp", String.valueOf(ts)).header("X-Nonce", nonce).header("X-Signature", sig);
        if (encryptSerial != null) {
            b.header("X-Encrypt-Serial", encryptSerial);
        }
        return mvc.perform(b).andReturn().getResponse();
    }

    @Test
    @DisplayName("★★★ 敏感字段（M3.5）：付款人手机号按 P3 加密传入 → 守卫解开 → 封存进密钥模块，支付单只存 blob 与掩码；查单只回掩码；传明文 400")
    void sensitivePayerIsSealedAndMasked() throws Exception {
        jdbc.update("UPDATE pay_app SET scopes='pay:create,pay:read' WHERE app_no=?", appNo);
        OpenApiFlowTest.TestStore store = OpenApiFlowTest.storeWithTestRoute(jdbc, bindings);
        jdbc.update("INSERT INTO pay_app_grant (pay_app_id, store_id, actions, status, granted_by, granted_at, created_at,"
                + " updated_at) SELECT id, ?, 'COLLECT,READ', 'ACTIVE', 'U-SK', NOW(), NOW(), NOW() FROM pay_app WHERE app_no=?",
                store.storeId(), appNo);
        IssuedCredential secret = issueAndEnable();
        IssuedCredential encryptKey = issueEncryptKeyAndEnable();
        String out = "OUT-SK-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"outOrderNo\":\"" + out + "\",\"storeRef\":\"" + store.storeNo()
                + "\",\"amountMinor\":1990,\"currency\":\"CNY\",\"subject\":\"敏感字段\",\"payer\":{\"phone\":\""
                + encryptField(encryptKey, "payer.phone", "13800005678") + "\",\"name\":\""
                + encryptField(encryptKey, "payer.name", "王小明") + "\"}}";

        MockHttpServletResponse created = call("POST", "/open/pay/v1/orders", body, secret, encryptKey.keySerial());

        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(200);
        Map<String, Object> row = jdbc.queryForMap("SELECT pay_order_no, payer_blob_id, payer_masked FROM pay_order"
                + " WHERE out_order_no=?", out);
        assertThat(row.get("payer_blob_id")).isNotNull();
        assertThat((String) row.get("payer_masked")).contains("1380****5678").doesNotContain("13800005678");
        String sealed = new String(vault.open(SecretNamespace.PAY,
                new ai.neargo.secret.api.SecretRef(((Number) row.get("payer_blob_id")).longValue())),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(sealed).as("密钥模块里封存的是明文 JSON").contains("13800005678");

        assertThat(jdbc.queryForList("SELECT request_masked FROM pay_api_request_log WHERE app_no=? AND request_masked IS NOT NULL",
                String.class, appNo)).as("请求记录记的是原始（加密的）请求体，明文不落日志").isNotEmpty()
                .noneMatch(m -> m.contains("13800005678") || m.contains("王小明"));

        MockHttpServletResponse queried = call("GET", "/open/pay/v1/orders/" + row.get("pay_order_no"), "", secret, null);
        assertThat(queried.getStatus()).isEqualTo(200);
        assertThat(queried.getContentAsString()).contains("1380****5678").contains("王**").doesNotContain("13800005678")
                .doesNotContain("王小明");

        String plain = body.replace(out, out + "-P").replaceAll("\"phone\":\"[^\"]+\"", "\"phone\":\"13800005678\"");
        MockHttpServletResponse rejected = call("POST", "/open/pay/v1/orders", plain, secret, encryptKey.keySerial());
        assertThat(rejected.getStatus()).isEqualTo(400);
        assertThat(rejected.getContentAsString()).contains(String.valueOf(
                ai.neargo.shop.common.ErrorCode.PAY_OPEN_FIELD_ENCRYPT_REQUIRED.code()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE out_order_no=?", Integer.class, out + "-P"))
                .isZero();
    }

    private Integer loggedVersion(String requestId) {
        return jdbc.queryForObject("SELECT sign_key_version FROM pay_api_request_log WHERE trace_id=?", Integer.class,
                requestId);
    }

    private static String rid() {
        return "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    @Test
    @DisplayName("★★ key-source=secret：验签与出站签名都是 SecretAppSignatures，部署配置那一份不再装")
    void secretSourceIsTheOnlyImplementation() {
        assertThat(verifier).isInstanceOf(SecretAppSignatures.class);
        assertThat(signer).isSameAs(verifier);
    }

    @Test
    @DisplayName("★★★ 真发一版 AppSecret：整串里去掉 sk_test_ 签的请求过守卫；请求记录写下命中的是第 1 版，X-Encrypt-Serial 也落库")
    void issuedSecretVerifiesAndVersionIsRecorded() throws Exception {
        IssuedCredential v1 = issueAndEnable();
        assertThat(v1.secret()).startsWith("sk_test_");

        String rid = rid();
        MockHttpServletResponse r = getOrder(v1, null, "E-TEST-01", rid);

        assertThat(r.getStatus()).as(r.getContentAsString()).isNotEqualTo(401);
        assertThat(loggedVersion(rid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT encrypt_serial FROM pay_api_request_log WHERE trace_id=?", String.class,
                rid)).isEqualTo("E-TEST-01");
    }

    @Test
    @DisplayName("★★★ 轮换重叠期：新旧两版都能验、各记各的版本号；X-Key-Serial 指定版本只认那一版（M3.2）")
    void rotationOverlapAndPinnedSerial() throws Exception {
        IssuedCredential v1 = issueAndEnable();
        IssuedCredential v2 = issueAndEnable();
        assertThat(v2.version()).isEqualTo(2);

        String byOld = rid();
        assertThat(getOrder(v1, null, null, byOld).getStatus()).isNotEqualTo(401);
        assertThat(loggedVersion(byOld)).as("重叠期旧版本照样能验").isEqualTo(1);
        String byNew = rid();
        assertThat(getOrder(v2, null, null, byNew).getStatus()).isNotEqualTo(401);
        assertThat(loggedVersion(byNew)).isEqualTo(2);

        String pinnedRight = rid();
        assertThat(getOrder(v1, v1.keySerial(), null, pinnedRight).getStatus()).isNotEqualTo(401);
        assertThat(loggedVersion(pinnedRight)).isEqualTo(1);
        MockHttpServletResponse signed = getOrder(v2, null, null, rid());
        assertThat(signed.getHeader("X-Key-Serial")).as("响应用当前版本签，并说出是哪一版（M3.4）").isEqualTo(v2.keySerial());
        String pinnedWrong = rid();
        assertThat(getOrder(v1, v2.keySerial(), null, pinnedWrong).getStatus()).as("指了 v2 却用 v1 签").isEqualTo(401);
        assertThat(loggedVersion(pinnedWrong)).as("没通过就不记版本").isNull();
    }
}
