package ai.neargo.shop.pay.channel.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * 回调验签的两处补强（TDD-支付服务-W2渠道层补完 S6 · AC8 / AC9；安全规范 G4 / G5；Review R31）。
 *
 * <ul>
 *   <li><b>时间窗</b>：此前只验签不看时间 —— 一条三个月前的真回调重放进来照样验得过（靠查单兜底，但会刷日志与告警）。
 *       先验签（证明是微信发的）再看新鲜度（证明不是重放）：顺序反过来，任何人随手 POST 一条就能刷「过期」计数。</li>
 *   <li><b>证书序列号</b>：此前只有一把静态平台公钥 —— 平台证书轮换当天，新证书签的回调全部验不过。
 *       按 {@code Wechatpay-Serial} 选公钥，新旧两把并存；认不出的 serial 直接拒。</li>
 *   <li><b>支付宝配置项名（G5）</b>：代码读 {@code public-key}，yml 只配了 {@code public-key-path} —— 启用即验签恒失败。</li>
 * </ul>
 */
class CallbackFreshnessAndRotationTest {

    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-26T08:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KeyPair oldKey = rsa();
    private final KeyPair newKey = rsa();

    @Test
    @DisplayName("★★★ G4 时间戳超出 ±5 分钟一律拒（验签过了也拒）：4 分钟前收、6 分钟前拒、6 分钟后（时钟漂移伪造）拒")
    void timestampWindow() throws Exception {
        WechatCallbackVerifier v = verifier(WechatPlatformKeys.of(pub(oldKey), "", null, null));

        assertThat(v.verify(signed(oldKey, NOW.minusSeconds(240), null), body())).isNotNull();
        assertThat(v.verify(signed(oldKey, NOW.minusSeconds(360), null), body()))
                .as("三个月前的真回调重放进来也该拒").isNull();
        assertThat(v.verify(signed(oldKey, NOW.plusSeconds(360), null), body())).isNull();
        assertThat(v.verify(withTimestamp(signed(oldKey, NOW, null), "not-a-number"), body())).isNull();
    }

    @Test
    @DisplayName("★★★ G4 按 Wechatpay-Serial 选平台公钥，新旧两把并存；认不出的 serial、serial 与签名对不上 → 拒")
    void serialSelectsKeyAndOldNewCoexist() throws Exception {
        WechatCallbackVerifier v = verifier(WechatPlatformKeys.of(pub(oldKey), "SERIAL_OLD", pub(newKey), "SERIAL_NEW"));

        assertThat(v.verify(signed(oldKey, NOW, "SERIAL_OLD"), body())).as("轮换期旧证书签的").isNotNull();
        assertThat(v.verify(signed(newKey, NOW, "SERIAL_NEW"), body())).as("轮换期新证书签的").isNotNull();
        assertThat(v.verify(signed(newKey, NOW, "SERIAL_UNKNOWN"), body())).isNull();
        assertThat(v.verify(signed(oldKey, NOW, "SERIAL_NEW"), body())).as("拿旧钥签、报新 serial").isNull();
        assertThat(v.verify(signed(oldKey, NOW, null), body())).as("两把都有名字时不带 serial 不猜").isNull();
    }

    @Test
    @DisplayName("★★ 存量配置（一把公钥、没配 serial）照旧可用：任何 serial 都用这一把 —— 升级不能把线上回调全拒了")
    void singleUnnamedKeyKeepsWorking() throws Exception {
        WechatCallbackVerifier v = verifier(WechatPlatformKeys.of(pub(oldKey), null, null, null));

        assertThat(v.verify(signed(oldKey, NOW, "PUB_KEY_ID_0112"), body())).isNotNull();
        assertThat(v.verify(signed(oldKey, NOW, null), body())).isNotNull();
        assertThat(v.verify(signed(newKey, NOW, "PUB_KEY_ID_0112"), body())).isNull();
    }

    @Test
    @DisplayName("★★ 轮换期旧一把没配名字、新一把配了：报新 serial 用新的，其余回落到旧的")
    void unnamedPrimaryIsFallbackDuringRotation() throws Exception {
        WechatCallbackVerifier v = verifier(WechatPlatformKeys.of(pub(oldKey), null, pub(newKey), "SERIAL_NEW"));

        assertThat(v.verify(signed(newKey, NOW, "SERIAL_NEW"), body())).isNotNull();
        assertThat(v.verify(signed(oldKey, NOW, "SERIAL_OLD"), body())).isNotNull();
    }

    @Test
    @DisplayName("★★★ G5 支付宝公钥按 yml 实际配的 public-key-path 读；内容与路径二选一，内容优先")
    void alipayReadsPublicKeyPath(@TempDir Path tmp) throws Exception {
        KeyPair alipay = rsa();
        Path pem = tmp.resolve("alipay_public.pem");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" + pub(alipay) + "\n-----END PUBLIC KEY-----\n");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("out_trade_no", "OT-G5");
        p.put("trade_status", "TRADE_SUCCESS");
        String sign = rsaSign(alipay, AlipayCallbackVerifier.signContent(p));
        String body = "out_trade_no=OT-G5&trade_status=TRADE_SUCCESS&sign_type=RSA2&sign="
                + URLEncoder.encode(sign, StandardCharsets.UTF_8);

        assertThat(new AlipayCallbackVerifier("", pem.toString()).verify(Map.of(), body))
                .as("只配了路径（yml 的真实形状）").isNotNull();
        assertThat(new AlipayCallbackVerifier(pub(alipay), "/does/not/exist.pem").verify(Map.of(), body))
                .as("内容优先，路径不读").isNotNull();
        assertThat(new AlipayCallbackVerifier("", "/does/not/exist.pem").verify(Map.of(), body))
                .as("都读不到：拒（fail-closed），但能起来").isNull();
    }

    // ---------------------------------------------------------------- 夹具

    private WechatCallbackVerifier verifier(WechatPlatformKeys keys) {
        return new WechatCallbackVerifier(API_V3_KEY, keys, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC), JSON);
    }

    private static String body() throws Exception {
        String nonce = "abcdefghijkl";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        c.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        String ct = Base64.getEncoder().encodeToString(c.doFinal("{\"out_trade_no\":\"OT-1\"}".getBytes(StandardCharsets.UTF_8)));
        return "{\"resource\":{\"associated_data\":\"transaction\",\"nonce\":\"" + nonce + "\",\"ciphertext\":\"" + ct + "\"}}";
    }

    private Map<String, String> signed(KeyPair key, Instant at, String serial) throws Exception {
        String ts = String.valueOf(at.getEpochSecond());
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Wechatpay-Signature", rsaSign(key, WechatCallbackVerifier.signContent(ts, "nc", body())));
        h.put("Wechatpay-Timestamp", ts);
        h.put("Wechatpay-Nonce", "nc");
        if (serial != null) {
            h.put("Wechatpay-Serial", serial);
        }
        return h;
    }

    private static Map<String, String> withTimestamp(Map<String, String> h, String ts) {
        Map<String, String> out = new LinkedHashMap<>(h);
        out.put("Wechatpay-Timestamp", ts);
        return out;
    }

    private static String rsaSign(KeyPair kp, String content) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private static String pub(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
