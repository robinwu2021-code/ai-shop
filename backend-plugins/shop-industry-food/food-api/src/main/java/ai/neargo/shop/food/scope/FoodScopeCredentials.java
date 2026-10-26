package ai.neargo.shop.food.scope;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 宿主签发、food-server 本地验签的短期范围凭证（{@code X-Food-Scope}），见 TDD-餐饮系统-内部调用签名凭证。
 *
 * <p>共享令牌 {@code X-Internal-Token} 只说明「调用方是平台内部进程」，而且全平台一把；这张凭证说明
 * 「这个请求体里的主体 / 门店 / 操作人，是宿主在哪个身份上下文里核对过的」，并用只有宿主与 food-server
 * 持有的密钥签名，摘要绑住方法、路径与请求体 —— 拿到共享令牌的人既造不出凭证，也改不了签过的请求。
 *
 * <p>格式：{@code v1.<base64url(JSON 声明)>.<base64url(HMAC-SHA256(key, "v1." + payload))>}。
 */
public final class FoodScopeCredentials {
    public static final String HEADER = "X-Food-Scope";
    /** 签发有效期。 */
    public static final long TTL_SECONDS = 60;
    /** 验签时过期时间最多允许在「现在」之后多久 —— 防一张签了很长有效期的凭证。 */
    public static final long MAX_AHEAD_SECONDS = 120;
    /** 两端时钟差的宽限。 */
    public static final long SKEW_SECONDS = 30;
    public static final int MIN_KEY_BYTES = 32;

    private static final String VERSION = "v1";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private FoodScopeCredentials() {}

    public enum Realm { STAFF, CONSUMER, OPERATOR, SYSTEM }

    /**
     * @param entity STAFF 时为当前商家主体，其余为空
     * @param stores STAFF 时为请求体目标门店（都已在宿主上下文的授权门店内），其余为空
     * @param actor  STAFF / OPERATOR 为登录人，CONSUMER 为登录买家（可空），SYSTEM 为空
     * @param exp    过期时刻（epoch 秒）
     * @param digest {@link #digest} 的结果
     */
    public record Claims(Realm realm, String entity, List<String> stores, String actor, long exp, String digest) {
        public Claims {
            Objects.requireNonNull(realm, "realm");
            Objects.requireNonNull(digest, "digest");
            stores = stores == null ? List.of() : List.copyOf(stores);
        }
    }

    /** 拒绝：401 = 凭证本身不成立（缺失、签名、过期、摘要、密钥未配）；403 = 凭证成立但与请求体不符。 */
    public static final class Rejected extends RuntimeException {
        private final int status;

        public Rejected(int status, String reason) {
            super(reason);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** 摘要：方法 + 原始路径（含查询串）+ 请求体字节。 */
    public static String digest(String method, String pathAndQuery, byte[] body) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((method + "\n" + pathAndQuery + "\n").getBytes(StandardCharsets.UTF_8));
            if (body != null) sha.update(body);
            return HexFormat.of().formatHex(sha.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 配置值 → 密钥字节；空或短于 {@link #MIN_KEY_BYTES} 返回 {@code null}（调用方据此失败即关闭）。 */
    public static byte[] key(String configured) {
        if (configured == null || configured.isBlank()) return null;
        byte[] bytes = configured.trim().getBytes(StandardCharsets.UTF_8);
        return bytes.length < MIN_KEY_BYTES ? null : bytes;
    }

    public static String sign(Claims claims, byte[] key) {
        if (key == null) throw new Rejected(401, "FOOD_SCOPE_KEY_NOT_CONFIGURED");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("r", claims.realm().name());
        body.put("e", claims.entity());
        body.put("s", claims.stores());
        body.put("a", claims.actor());
        body.put("x", claims.exp());
        body.put("d", claims.digest());
        String payload = B64.encodeToString(JSON.writeValueAsBytes(body));
        String signed = VERSION + "." + payload;
        return signed + "." + B64.encodeToString(hmac(key, signed));
    }

    /**
     * @param keys           当前密钥在前，轮换窗口里的旧密钥在后；空列表等于没配
     * @param expectedDigest 服务端按收到的请求重算的摘要
     */
    public static Claims verify(String header, List<byte[]> keys, String expectedDigest, long nowEpochSeconds) {
        List<byte[]> usable = keys == null ? List.of() : keys.stream().filter(Objects::nonNull).toList();
        if (usable.isEmpty()) throw new Rejected(401, "FOOD_SCOPE_KEY_NOT_CONFIGURED");
        if (header == null || header.isBlank()) throw new Rejected(401, "FOOD_SCOPE_MISSING");
        String[] parts = header.trim().split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) throw new Rejected(401, "FOOD_SCOPE_MALFORMED");
        byte[] given;
        try {
            given = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new Rejected(401, "FOOD_SCOPE_MALFORMED");
        }
        String signed = parts[0] + "." + parts[1];
        boolean ok = false;
        for (byte[] key : usable) ok |= MessageDigest.isEqual(hmac(key, signed), given);
        if (!ok) throw new Rejected(401, "FOOD_SCOPE_SIGNATURE");
        Claims claims = parse(parts[1]);
        if (claims.exp() <= nowEpochSeconds - SKEW_SECONDS) throw new Rejected(401, "FOOD_SCOPE_EXPIRED");
        if (claims.exp() > nowEpochSeconds + MAX_AHEAD_SECONDS) throw new Rejected(401, "FOOD_SCOPE_TTL");
        if (expectedDigest == null || !MessageDigest.isEqual(
                claims.digest().getBytes(StandardCharsets.US_ASCII), expectedDigest.getBytes(StandardCharsets.US_ASCII)))
            throw new Rejected(401, "FOOD_SCOPE_DIGEST");
        return claims;
    }

    /**
     * 声明与请求体的范围字段是否一致（403）。签发方用它核对「上下文 vs 请求体」，验签方用它核对「声明 vs 请求体」，
     * 两边是同一条规则。
     *
     * <ul>
     *   <li>STAFF：请求体里所有主体值都等于声明主体；所有门店值都在声明门店内；所有操作人值都等于声明操作人。
     *       买家号不约束（店员代客下单会带顾客的买家号）。</li>
     *   <li>CONSUMER：请求体里所有买家 / 操作人值都等于声明操作人；声明操作人为空时请求体不得带它们。</li>
     *   <li>OPERATOR / SYSTEM：不约束（平台数据域在宿主判；后台任务没有人的身份）。</li>
     * </ul>
     */
    public static void requireBodyMatches(Claims claims, FoodScopeBodyScanner.Scope body) {
        switch (claims.realm()) {
            case STAFF -> {
                if (claims.entity() == null || claims.actor() == null)
                    throw new Rejected(403, "FOOD_SCOPE_STAFF_INCOMPLETE");
                for (String e : body.entities())
                    if (!e.equals(claims.entity())) throw new Rejected(403, "FOOD_SCOPE_ENTITY");
                for (String s : body.stores())
                    if (!claims.stores().contains(s)) throw new Rejected(403, "FOOD_SCOPE_STORE");
                for (String a : body.actors())
                    if (!a.equals(claims.actor())) throw new Rejected(403, "FOOD_SCOPE_ACTOR");
            }
            case CONSUMER -> {
                for (String a : body.actors())
                    if (!a.equals(claims.actor())) throw new Rejected(403, "FOOD_SCOPE_ACTOR");
                for (String b : body.buyers())
                    if (!b.equals(claims.actor())) throw new Rejected(403, "FOOD_SCOPE_BUYER");
            }
            case OPERATOR, SYSTEM -> {
            }
        }
    }

    private static Claims parse(String payload) {
        try {
            JsonNode n = JSON.readTree(B64D.decode(payload));
            List<String> stores = new ArrayList<>();
            JsonNode s = n.get("s");
            if (s != null && s.isArray()) for (JsonNode store : s) stores.add(store.asString());
            return new Claims(Realm.valueOf(n.get("r").asString()), text(n.get("e")), stores, text(n.get("a")),
                    n.get("x").asLong(), n.get("d").asString());
        } catch (RuntimeException e) {
            throw new Rejected(401, "FOOD_SCOPE_MALFORMED");
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
