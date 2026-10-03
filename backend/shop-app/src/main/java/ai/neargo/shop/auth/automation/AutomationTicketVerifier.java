package ai.neargo.shop.auth.automation;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 密钥票据的校验（ADR-027，TDD-密钥票据免登录）。
 *
 * <p>票据 = {@code v1.<base64url(payload)>.<base64url(Ed25519 签名)>}，签名覆盖 {@code v1.<payload>}。
 * payload = {@code {realm: B|OPS, sub, exp, nonce}}。
 *
 * <p><b>线上只有公钥</b>：这台机器被翻遍也签不出票据。私钥在本机（仓库外），由
 * {@code scripts/automation/ticket.py} 使用。
 *
 * <p>校验顺序：先验签，再看字段 —— 没验过签的 payload 一个字都不信（包括拿它的 nonce 去占位）。
 */
@Component
public class AutomationTicketVerifier {

    /** 票据最长有效期。签得更远的也拒：防止有人签一张一年有效的票据放着用。 */
    static final long MAX_TTL_SECONDS = 60;
    /** 允许的时钟偏差：本机与服务器差几秒很常见，差更多说明有问题 */
    static final long CLOCK_SKEW_SECONDS = 5;

    public enum TicketRealm { B, OPS }

    public record Verified(TicketRealm realm, String subject) {
    }

    /** 被拒的原因码，进登录审计。对外一律同一个错误，不告诉调用方哪一步没过。 */
    public static final class Rejected extends RuntimeException {
        private final String code;

        Rejected(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean enabled;
    private final PublicKey publicKey;
    private final Set<String> subjects;
    private final Clock clock;
    /** 用过的 nonce → 可以忘掉它的时刻（秒）。单实例部署，进程内存够用；多实例要换共享存储（ADR-027 代价）。 */
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

    @Autowired
    public AutomationTicketVerifier(@Value("${shop.auth.automation.enabled:false}") boolean enabled,
                                    @Value("${shop.auth.automation.public-key:}") String publicKey,
                                    @Value("${shop.auth.automation.subjects:}") String subjects) {
        this(enabled, publicKey, subjects, Clock.systemUTC());
    }

    AutomationTicketVerifier(boolean enabled, String publicKey, String subjects, Clock clock) {
        this.enabled = enabled;
        this.clock = clock;
        this.subjects = Arrays.stream(subjects == null ? new String[0] : subjects.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (!enabled) {
            this.publicKey = null;
            return;
        }
        /*
         * 开着却没配齐就拒绝启动：公钥空 = 什么票据都验不过（以为开了其实没开）；
         * 白名单空 = 同上。两种都不报错，只会让人对着「登录失败」猜半天。
         */
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalStateException("shop.auth.automation.enabled=true 但 SHOP_AUTOMATION_PUBLIC_KEY 为空");
        }
        if (this.subjects.isEmpty()) {
            throw new IllegalStateException("shop.auth.automation.enabled=true 但 SHOP_AUTOMATION_SUBJECTS 为空");
        }
        try {
            this.publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.trim())));
        } catch (Exception e) {
            throw new IllegalStateException("SHOP_AUTOMATION_PUBLIC_KEY 不是有效的 Ed25519 公钥（X.509 DER 的 base64）", e);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public Verified verify(String ticket) {
        if (!enabled) {
            throw new Rejected("AUTOMATION_DISABLED");
        }
        String[] parts = ticket == null ? new String[0] : ticket.trim().split("\\.");
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            throw new Rejected("AUTOMATION_MALFORMED");
        }
        byte[] payload;
        byte[] sig;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
            sig = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new Rejected("AUTOMATION_MALFORMED");
        }
        try {
            Signature ed = Signature.getInstance("Ed25519");
            ed.initVerify(publicKey);
            ed.update(("v1." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!ed.verify(sig)) {
                throw new Rejected("AUTOMATION_BAD_SIGNATURE");
            }
        } catch (Rejected r) {
            throw r;
        } catch (Exception e) {
            throw new Rejected("AUTOMATION_BAD_SIGNATURE");
        }

        JsonNode p;
        try {
            p = JSON.readTree(payload);
        } catch (Exception e) {
            throw new Rejected("AUTOMATION_MALFORMED");
        }
        TicketRealm realm;
        try {
            realm = TicketRealm.valueOf(p.path("realm").asString(""));
        } catch (IllegalArgumentException e) {
            throw new Rejected("AUTOMATION_BAD_REALM");
        }
        String sub = p.path("sub").asString("");
        String nonce = p.path("nonce").asString("");
        long exp = p.path("exp").asLong(0);
        long now = clock.millis() / 1000;
        if (exp <= now) {
            throw new Rejected("AUTOMATION_EXPIRED");
        }
        if (exp > now + MAX_TTL_SECONDS + CLOCK_SKEW_SECONDS) {
            throw new Rejected("AUTOMATION_TTL_TOO_LONG");
        }
        if (sub.isBlank() || nonce.length() < 16) {
            throw new Rejected("AUTOMATION_MALFORMED");
        }
        if (!subjects.contains(realm + ":" + sub)) {
            throw new Rejected("AUTOMATION_NOT_ALLOWED");
        }
        usedNonces.values().removeIf(forgetAt -> forgetAt < now);
        if (usedNonces.putIfAbsent(nonce, exp + CLOCK_SKEW_SECONDS) != null) {
            throw new Rejected("AUTOMATION_REPLAYED");
        }
        return new Verified(realm, sub);
    }
}
