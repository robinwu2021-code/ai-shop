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
        "shop.secret.master-keys[0].active=true",
        // 试点接入方的密钥今天在部署配置里（M3.10 要把它导进密钥模块）：整串带前缀的 AppSecret 与 EncryptKey
        "shop.pay.open.apps.APP-IMPORT-1.hmac-secret=sk_test_Imp0rtSecretAbCdEfGh0123456789",
        "shop.pay.open.apps.APP-IMPORT-1.encrypt-key=Imp0rtEncryptKey0123456789abcdef"})
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
    @Autowired private ai.neargo.shop.spi.settle.SettlePort settle;
    @Autowired private ai.neargo.shop.pay.notify.service.BizNotifyService notifier;
    @Autowired private tools.jackson.databind.ObjectMapper json;
    @Autowired private ai.neargo.shop.pay.secret.OpenKeyImport keyImport;
    @Autowired private ai.neargo.shop.pay.openapp.AppFieldCipher cipher;

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

    @Test
    @DisplayName("★★★ 推送（M3.6）：resource 用 EncryptKey 加密、签名用 AppSecret；首投冻结两个版本 —— 轮换密钥后重推仍用旧版本，nonce 每次都新")
    void webhookUsesFrozenVersionsAcrossRotation() throws Exception {
        jdbc.update("UPDATE pay_app SET scopes='pay:create,pay:read', webhook_url=? WHERE app_no=?",
                OpenApiFlowTest.hookUrl(), appNo);
        OpenApiFlowTest.TestStore store = OpenApiFlowTest.storeWithTestRoute(jdbc, bindings);
        jdbc.update("INSERT INTO pay_app_grant (pay_app_id, store_id, actions, status, granted_by, granted_at, created_at,"
                + " updated_at) SELECT id, ?, 'COLLECT,READ', 'ACTIVE', 'U-SK', NOW(), NOW(), NOW() FROM pay_app WHERE app_no=?",
                store.storeId(), appNo);
        IssuedCredential v1 = issueAndEnable();
        IssuedCredential e1 = issueEncryptKeyAndEnable();
        String out = "OUT-WH-" + UUID.randomUUID().toString().substring(0, 8);
        MockHttpServletResponse created = call("POST", "/open/pay/v1/orders", "{\"outOrderNo\":\"" + out
                + "\",\"storeRef\":\"" + store.storeNo() + "\",\"amountMinor\":800,\"currency\":\"CNY\",\"subject\":\"推送\"}",
                v1, null);
        String no = json.readTree(created.getContentAsString()).get("data").get("payOrderNo").asString();
        MockHttpServletResponse started = call("POST", "/open/pay/v1/orders/" + no + "/pay", "{\"payMethod\":\"TEST_PAY\"}",
                v1, null);
        settle.settlePayment(new ai.neargo.shop.spi.settle.SettlePort.PaymentSettled(
                json.readTree(started.getContentAsString()).get("data").get("outTradeNo").asString(), "TEST",
                "T-" + UUID.randomUUID(), 800, System.currentTimeMillis()));
        Map<String, Object> n = jdbc.queryForMap("SELECT n.id, n.event_key FROM pay_biz_notify n JOIN pay_order o"
                + " ON o.id=n.pay_order_id WHERE o.pay_order_no=? AND n.event_type='PAYMENT_SUCCEEDED'", no);
        String eventKey = (String) n.get("event_key");

        OpenApiFlowTest.HOOK_FAILS.set(true);
        try {
            notifier.deliverDue(java.time.Instant.now(), 100);
        } finally {
            OpenApiFlowTest.HOOK_FAILS.set(false);
        }
        assertThat(jdbc.queryForMap("SELECT enc_key_version, sign_key_version FROM pay_biz_notify WHERE id=?", n.get("id")))
                .as("首投就冻结").containsEntry("enc_key_version", 1).containsEntry("sign_key_version", 1);

        issueAndEnable();
        issueEncryptKeyAndEnable();
        notifier.deliverDue(java.time.Instant.now().plus(java.time.Duration.ofHours(1)), 100);

        var hooks = OpenApiFlowTest.HOOKS.stream().filter(h -> eventKey.equals(h.headers().get("X-Event-Id"))).toList();
        assertThat(hooks).as("失败一次、重推一次").hasSize(2);
        for (var h : hooks) {
            assertThat(h.headers().get("X-Key-Serial")).as("轮换后重推仍用冻结的 AppSecret v1").isEqualTo(v1.keySerial());
            assertThat(h.headers().get("X-Signature")).isEqualTo(ai.neargo.shop.pay.notify.WebhookEnvelopesTestSupport
                    .signature(v1.secret().substring("sk_test_".length()), h.headers().get("X-Timestamp"),
                            h.headers().get("X-Nonce"), h.body()));
            var resource = json.readTree(h.body()).get("resource");
            assertThat(resource.get("keySerial").asString()).as("用冻结的 EncryptKey v1 加密").isEqualTo(e1.keySerial());
            assertThat(json.readTree(ai.neargo.shop.pay.notify.WebhookEnvelopesTestSupport.decrypt(e1.secret(), resource))
                    .get("payOrderNo").asString()).isEqualTo(no);
            assertThat(h.body()).doesNotContain(no);
        }
        assertThat(json.readTree(hooks.get(0).body()).get("resource").get("nonce").asString())
                .as("nonce 每次新生成").isNotEqualTo(json.readTree(hooks.get(1).body()).get("resource").get("nonce").asString());
        assertThat(jdbc.queryForObject("SELECT status FROM pay_biz_notify WHERE id=?", String.class, n.get("id")))
                .isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("★★★ 试点密钥导入（M3.10）：上传人导入并验证 → 同一人不能启用（两人规则）→ 另一人启用后，接入方照旧用原来那串 AppSecret 签的请求过得去；重复导入不加版本；报告里没有密钥")
    void importConfigKeysWithTwoPeople() throws Exception {
        jdbc.update("DELETE FROM pay_app WHERE app_no='APP-IMPORT-1'");
        jdbc.update("INSERT INTO pay_app (app_no, name, owner_type, status, scopes, rate_limit_qps,"
                + " daily_refund_limit_minor, sandbox, created_at, updated_at) VALUES ('APP-IMPORT-1', '导入', 'PARTNER',"
                + " 'ACTIVE', 'pay:read', 1000, 0, 1, NOW(), NOW())");
        IssuedCredential configSecret = new IssuedCredential(null, 0, "sk_test_Imp0rtSecretAbCdEfGh0123456789", null);
        String saved = appNo;
        appNo = "APP-IMPORT-1";
        try {
            assertThat(getOrder(configSecret, null, null, rid()).getStatus()).as("导入前：密钥模块里没有").isEqualTo(401);

            java.util.List<String> uploaded = keyImport.upload(java.util.List.of("APP-IMPORT-1"), "ops-alice");
            assertThat(String.join("\n", uploaded)).contains("AppSecret").contains("EncryptKey").contains("VERIFIED")
                    .doesNotContain("Imp0rtSecret").doesNotContain("Imp0rtEncryptKey");
            assertThat(getOrder(configSecret, null, null, rid()).getStatus()).as("还没启用").isEqualTo(401);

            assertThat(String.join("\n", keyImport.upload(java.util.List.of("APP-IMPORT-1"), "ops-alice")))
                    .as("重复导入不加版本").contains("already");
            assertThat(String.join("\n", keyImport.enable(java.util.List.of("APP-IMPORT-1"), "ops-alice")))
                    .as("上传人自己不能启用").contains("two-person");
            assertThat(getOrder(configSecret, null, null, rid()).getStatus()).isEqualTo(401);

            String enabled = String.join("\n", keyImport.enable(java.util.List.of("APP-IMPORT-1"), "ops-bob"));
            assertThat(enabled).contains("ACTIVE");
            assertThat(getOrder(configSecret, null, null, rid()).getStatus()).as("启用后：原来那串照样能用")
                    .isNotEqualTo(401);
            long appId = jdbc.queryForObject("SELECT id FROM pay_app WHERE app_no='APP-IMPORT-1'", Long.class);
            ai.neargo.shop.pay.openapp.entity.PayApp app = new ai.neargo.shop.pay.openapp.entity.PayApp();
            app.setId(appId);
            app.setAppNo("APP-IMPORT-1");
            app.setOwnerType("PARTNER");
            var encrypted = cipher.encrypt(app, null, "x".getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[0]);
            assertThat(encrypted).as("EncryptKey 也进了密钥模块").isPresent();
            assertThat(String.join("\n", keyImport.status(java.util.List.of("APP-IMPORT-1"))))
                    .contains("v1").contains("ACTIVE").doesNotContain("Imp0rt");
        } finally {
            appNo = saved;
        }
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
