package ai.neargo.shop.auth.automation;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.ArrayList;
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
 * payload = {@code {realm: B|OPS|C, sub, exp, nonce}}。
 *
 * <p><b>线上只有公钥</b>：这台机器被翻遍也签不出票据。私钥在本机（仓库外），由
 * {@code scripts/automation/_ticket.py} 使用（keygen.py / api.py 经它签票）。
 *
 * <p>校验顺序：先验签，再看字段 —— 没验过签的 payload 一个字都不信（包括拿它的 nonce 去占位）。
 */
@Component
public class AutomationTicketVerifier {

    /** 票据最长有效期。签得更远的也拒：防止有人签一张一年有效的票据放着用。 */
    static final long MAX_TTL_SECONDS = 60;
    /** 允许的时钟偏差：本机与服务器差几秒很常见，差更多说明有问题 */
    static final long CLOCK_SKEW_SECONDS = 5;

    /** B 店主、OPS 运营、C 消费者（2026-11-19 加：三端都要能用测试账号从接口验证）。 */
    public enum TicketRealm { B, OPS, C }

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
    /**
     * 每一端认哪几把公钥。三端的测试账号不同、签票据的人也不同，所以钥按端分：
     * 运营端的钥签不出店主或买家的票据，反过来也一样 —— 一把私钥泄露，损失只到它那一端的白名单。
     * 某一端没有单独配时，用通用的那一把（{@code shop.auth.automation.public-key}，与分端之前的行为相同）。
     */
    private final Map<TicketRealm, List<PublicKey>> keys;
    private final Set<String> subjects;
    private final Clock clock;
    /** 用过的 nonce → 可以忘掉它的时刻（秒）。单实例部署，进程内存够用；多实例要换共享存储（ADR-027 代价）。 */
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

    @Autowired
    public AutomationTicketVerifier(@Value("${shop.auth.automation.enabled:false}") boolean enabled,
                                    @Value("${shop.auth.automation.public-key:}") String publicKey,
                                    @Value("${shop.auth.automation.public-key-ops:}") String opsKeys,
                                    @Value("${shop.auth.automation.public-key-b:}") String merchantKeys,
                                    @Value("${shop.auth.automation.public-key-c:}") String consumerKeys,
                                    @Value("${shop.auth.automation.subjects:}") String subjects) {
        this(enabled, publicKey, perRealm(opsKeys, merchantKeys, consumerKeys), subjects, Clock.systemUTC());
    }

    AutomationTicketVerifier(boolean enabled, String publicKey, String subjects, Clock clock) {
        this(enabled, publicKey, Map.of(), subjects, clock);
    }

    private static Map<TicketRealm, String> perRealm(String ops, String merchant, String consumer) {
        Map<TicketRealm, String> out = new EnumMap<>(TicketRealm.class);
        out.put(TicketRealm.OPS, ops == null ? "" : ops);
        out.put(TicketRealm.B, merchant == null ? "" : merchant);
        out.put(TicketRealm.C, consumer == null ? "" : consumer);
        return out;
    }

    /** @param perRealm 各端自己的公钥，逗号分隔可以列多把；空表示这一端用通用的那一把 */
    AutomationTicketVerifier(boolean enabled, String publicKey, Map<TicketRealm, String> perRealm, String subjects,
                             Clock clock) {
        this.enabled = enabled;
        this.clock = clock;
        this.subjects = Arrays.stream(subjects == null ? new String[0] : subjects.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (!enabled) {
            this.keys = Map.of();
            return;
        }
        /*
         * 开着却没配齐就拒绝启动：公钥空 = 什么票据都验不过（以为开了其实没开）；
         * 白名单空 = 同上。两种都不报错，只会让人对着「登录失败」猜半天。
         */
        if (this.subjects.isEmpty()) {
            throw new IllegalStateException("shop.auth.automation.enabled=true 但 SHOP_AUTOMATION_SUBJECTS 为空");
        }
        List<PublicKey> shared = parseKeys(publicKey, "SHOP_AUTOMATION_PUBLIC_KEY");
        Map<TicketRealm, List<PublicKey>> byRealm = new EnumMap<>(TicketRealm.class);
        for (TicketRealm realm : TicketRealm.values()) {
            List<PublicKey> own = parseKeys(perRealm.getOrDefault(realm, ""), "SHOP_AUTOMATION_PUBLIC_KEY_" + realm);
            byRealm.put(realm, own.isEmpty() ? shared : own);
        }
        // 白名单里出现的端必须有钥可验：否则那一端「以为开了其实没开」，只会让人对着登录失败猜
        for (String subject : this.subjects) {
            String realm = subject.contains(":") ? subject.substring(0, subject.indexOf(':')) : "";
            boolean known = Arrays.stream(TicketRealm.values()).anyMatch(r -> r.name().equals(realm));
            if (known && byRealm.get(TicketRealm.valueOf(realm)).isEmpty()) {
                throw new IllegalStateException("shop.auth.automation.enabled=true 但白名单里的 " + realm
                        + " 端没有公钥：配 SHOP_AUTOMATION_PUBLIC_KEY_" + realm + " 或通用的 SHOP_AUTOMATION_PUBLIC_KEY");
            }
        }
        this.keys = byRealm;
    }

    private static List<PublicKey> parseKeys(String raw, String name) {
        List<PublicKey> out = new ArrayList<>();
        for (String one : (raw == null ? "" : raw).split(",")) {
            if (one.isBlank()) {
                continue;
            }
            try {
                out.add(KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(one.trim()))));
            } catch (Exception e) {
                throw new IllegalStateException(name + " 不是有效的 Ed25519 公钥（X.509 DER 的 base64）", e);
            }
        }
        return out;
    }

    private static boolean verifies(PublicKey key, byte[] signed, byte[] signature) {
        try {
            Signature ed = Signature.getInstance("Ed25519");
            ed.initVerify(key);
            ed.update(signed);
            return ed.verify(signature);
        } catch (Exception e) {
            return false;
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
        // 先验签：找出是哪些端的钥签的。此时 payload 一个字都还没信
        Set<TicketRealm> signedFor = EnumSet.noneOf(TicketRealm.class);
        byte[] signedBytes = ("v1." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        keys.forEach((realm, candidates) -> {
            for (PublicKey candidate : candidates) {
                if (verifies(candidate, signedBytes, sig)) {
                    signedFor.add(realm);
                    break;
                }
            }
        });
        if (signedFor.isEmpty()) {
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
        // 签名是真的，但那把钥不是这一端的：运营端的钥签不出店主或买家的票据
        if (!signedFor.contains(realm)) {
            throw new Rejected("AUTOMATION_WRONG_KEY_FOR_REALM");
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
