package ai.neargo.shop.pay.channel.verify;

import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 微信支付 APIv3 回调验签 + 解密。
 *
 * <p>两步，缺一不可：
 * <ol>
 *   <li><b>验签</b>：待签串是 {@code timestamp\nnonce\nbody\n}（<b>结尾那个换行也要</b>），
 *       用微信平台证书的公钥做 SHA256withRSA。签名与时间戳、随机串都在请求头里。</li>
 *   <li><b>解密</b>：验过之后 {@code resource} 才是密文，用 APIv3 密钥
 *       AES-256-GCM 解出业务报文。{@code associated_data} 参与认证 ——
 *       它对不上时解密必须失败，那正是 GCM 的作用。</li>
 * </ol>
 *
 * <p><b>顺序不能反。</b>先解密后验签的话，攻击者可以拿一段自己加密的密文进来 ——
 * 而解密成功会让人以为「能解出来就是真的」。
 *
 * <p><b>默认不装配</b>（{@code shop.pay.wechat.enabled}）。没有平台证书时装配它，
 * 结果是每一条回调都验签失败 —— 而通道会一直重推，日志刷满而没人知道是配置没给。
 */
@Component
@ConditionalOnProperty(name = "shop.pay.wechat.enabled", havingValue = "true")
public class WechatCallbackVerifier implements ChannelCallbackVerifier {

    private static final Logger log = LoggerFactory.getLogger(WechatCallbackVerifier.class);

    /**
     * **连续 N 次验签失败提级到 error**。
     *
     * <p>提级不是要打扰谁 —— 而是让日志真的能被看到：
     * `LOGGING_THRESHOLD_CONSOLE=ERROR` 之下，warn 只落文件、不进 systemd journal，
     * 巡检时 journalctl 看不到；提到 error 就落 journal。
     *
     * <p>为什么不是每次都 error：/pay/callback/wechat 是公网可达端点，
     * 攻击者随手 POST 一条会打一条 error，日志会被无意义地拉高噪声。
     * 3 次是给单次伪造留的容错，配置真错或证书轮换未同步时会持续失败、
     * 从第 3 次起持续报 error —— 排查的人会在 journal 里看到。
     *
     * <p>成功一次归零。计数是进程内的，重启也归零 —— 那正合适：
     * 重启后如果配置对了应该立即成功。
     */
    static final int ALARM_AFTER = 3;
    private static final java.util.concurrent.atomic.AtomicInteger consecFail =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 仅测试用：显式归零。生产靠一次成功 verify 自动清 */
    static void resetFailCount() {
        consecFail.set(0);
    }

    static int currentFailCount() {
        return consecFail.get();
    }

    /** 时间窗失败单独计数（安全规范 §监控：「时间窗失败单独计数」）—— 与验签失败是两种处置：前者多半是重放或时钟漂移 */
    private static final java.util.concurrent.atomic.AtomicInteger consecStale =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private final String apiV3Key;
    private final WechatPlatformKeys platformKeys;
    private final Duration window;
    private final Clock clock;
    private final ObjectMapper json;

    /**
     * @param platformPublicKey / -path      当前平台公钥（或微信支付公钥），内容优先、其次路径
     * @param platformPublicKeySerial       它的序列号；空 = 不按 serial 认（存量配置，见 {@link WechatPlatformKeys}）
     * @param nextKey / nextKeyPath / nextSerial 证书轮换期的新一把，带序列号（G4：新旧并存）
     * @param window                        回调时间戳允许偏差（G4，默认 ±5 分钟）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WechatCallbackVerifier(@Value("${shop.pay.wechat.apiv3-key:}") String apiV3Key,
                                  @Value("${shop.pay.wechat.platform-public-key:}") String platformPublicKey,
                                  @Value("${shop.pay.wechat.platform-public-key-path:}") String platformPublicKeyPath,
                                  @Value("${shop.pay.wechat.platform-public-key-serial:}") String platformPublicKeySerial,
                                  @Value("${shop.pay.wechat.platform-public-key-next:}") String nextKey,
                                  @Value("${shop.pay.wechat.platform-public-key-next-path:}") String nextKeyPath,
                                  @Value("${shop.pay.wechat.platform-public-key-next-serial:}") String nextSerial,
                                  @Value("${shop.pay.callback.timestamp-window:PT5M}") Duration window,
                                  ObjectMapper json) {
        /*
         * **内容优先，其次路径**（与 {@code WechatPayChannelConfig.pemOf} 同一口径）。
         * 之前只读 content，而生产用的是 `WX_PLATFORM_PUBLIC_KEY_PATH`——
         * 结果 content 恒空，每一条回调都验签失败。症状与 apiv3-key 配错完全一样，
         * 排查的人会去核那个**没被用到**的字段。
         *
         * 现在两种给法二选一：{@code -path} 读不出来当作没配（fail-closed，不像装配期 fail 那么重 ——
         * verifier 只做验证不签发）。
         */
        this(apiV3Key, WechatPlatformKeys.of(pemOf(platformPublicKey, platformPublicKeyPath), platformPublicKeySerial,
                pemOf(nextKey, nextKeyPath), nextSerial), window, Clock.systemUTC(), json);
    }

    WechatCallbackVerifier(String apiV3Key, WechatPlatformKeys platformKeys, Duration window, Clock clock,
                           ObjectMapper json) {
        this.apiV3Key = apiV3Key;
        this.platformKeys = platformKeys;
        this.window = window;
        this.clock = clock;
        this.json = json;
    }

    /** 测试用：一把没名字的公钥 + 路径，默认时间窗 */
    WechatCallbackVerifier(String apiV3Key, String platformPublicKey, String platformPublicKeyPath, ObjectMapper json) {
        this(apiV3Key, WechatPlatformKeys.of(pemOf(platformPublicKey, platformPublicKeyPath), null, null, null),
                Duration.ofMinutes(5), Clock.systemUTC(), json);
    }

    /** 测试用：老的二参构造，直接传 PEM 内容（等价于 path 空） */
    WechatCallbackVerifier(String apiV3Key, String platformPublicKey, ObjectMapper json) {
        this(apiV3Key, platformPublicKey, "", json);
    }

    private static String pemOf(String inline, String path) {
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        if (path == null || path.isBlank()) {
            return inline == null ? "" : inline;
        }
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            // 不带出路径内容，只带出路径本身
            log.warn("[callback] platform-public-key-path 读不出来：{}（{}）—— 回调全部会验签失败", path, e.toString());
            return "";
        }
    }

    @Override
    public String payChannel() {
        return "WECHAT";
    }

    /**
     * 待签串。<b>三个字段各占一行，最后一行也要换行</b> ——
     * 少一个 {@code \n} 签名恒不过，而那种失败看起来像「平台证书配错了」，
     * 会让人去查一个没问题的地方。
     */
    static String signContent(String timestamp, String nonce, String body) {
        return timestamp + "\n" + nonce + "\n" + body + "\n";
    }

    @Override
    public Map<String, Object> verify(Map<String, String> headers, String rawBody) {
        try {
            String sign = header(headers, "wechatpay-signature");
            String timestamp = header(headers, "wechatpay-timestamp");
            String nonce = header(headers, "wechatpay-nonce");
            String serial = header(headers, "wechatpay-serial");
            if (sign == null || timestamp == null || nonce == null || rawBody == null) {
                return null;
            }
            PublicKey key = platformKeys.select(serial).orElseThrow(() ->
                    new IllegalStateException("没有可用的平台公钥（Wechatpay-Serial=" + serial + "）"));
            if (!rsaVerify(key, signContent(timestamp, nonce, rawBody), sign)) {
                return null;
            }
            /*
             * 先验签、再看新鲜度（G4）：验签证明是微信发的，时间窗证明不是重放。顺序反过来，
             * 任何人随手 POST 一条带旧时间戳的就能刷「过期」告警。
             */
            if (!fresh(timestamp)) {
                return null;
            }
            Map<String, Object> envelope = json.readValue(rawBody, Map.class);
            Object resource = envelope.get("resource");
            if (!(resource instanceof Map<?, ?> res)) {
                return null;
            }
            String plain = decrypt(String.valueOf(res.get("associated_data")),
                    String.valueOf(res.get("nonce")),
                    String.valueOf(res.get("ciphertext")));
            Map<String, Object> out = json.readValue(plain, Map.class);
            // 成功一次归零 —— 配置刚修完 / 证书刚同步的场景下能立即静音
            consecFail.set(0);
            return out;
        } catch (Exception e) {
            /*
             * 验签/解密任一失败都当「这条回调不存在」返 null，不告诉外部原因
             * （端点公网可达，别把配置状态回给攻击者）。
             * 但**日志里必须留原因** —— 之前只写「验签失败」四个字，2026-09-28
             * 那次生产平台公钥没配读上，症状与「apiv3-key 打错字」一模一样，
             * 排查的人去查了另一个没问题的地方。带上 e.toString() 一眼看到
             * NoSuchFileException / InvalidKeySpecException / BadPaddingException。
             */
            int n = consecFail.incrementAndGet();
            if (n >= ALARM_AFTER) {
                log.error("[callback] 微信回调验签连续失败 {} 次 —— 多半是平台公钥配错或证书轮换未同步：{}",
                        n, e.toString());
            } else {
                log.warn("[callback] 微信回调验签失败（第 {} 次）：{}", n, e.toString());
            }
            return null;
        }
    }

    /** 请求头大小写不敏感，而 Spring 给的 Map 是原样的 —— 逐个比小写才稳。 */
    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().equals(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 时间戳与本机时钟的偏差在时间窗内。不是数字 = 不新鲜（拒）；偏差秒数进日志，便于分辨重放与时钟漂移 */
    private boolean fresh(String timestamp) {
        long skew;
        try {
            skew = clock.instant().getEpochSecond() - Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            skew = Long.MAX_VALUE;
        }
        if (Math.abs(skew) <= window.getSeconds()) {
            consecStale.set(0);
            return true;
        }
        int n = consecStale.incrementAndGet();
        log.warn("[callback] 微信回调时间戳超出 ±{}s 时间窗（偏差 {}s，连续第 {} 次）—— 重放或时钟漂移，拒收",
                window.getSeconds(), skew == Long.MAX_VALUE ? "?" : skew, n);
        return false;
    }

    private static boolean rsaVerify(PublicKey key, String content, String signBase64) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(content.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(signBase64));
    }

    /** AES-256-GCM。tag 长度 128 位，附加数据参与认证 —— 改一个字节就解不出来。 */
    private String decrypt(String associatedData, String nonce, String ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
    }

    /** 微信要 JSON。回错了不会报错，只会让通道一直重推。 */
    @Override
    public String ackOk() {
        return "{\"code\":\"SUCCESS\",\"message\":\"OK\"}";
    }

    @Override
    public String ackFail() {
        return "{\"code\":\"FAIL\",\"message\":\"FAIL\"}";
    }
}
