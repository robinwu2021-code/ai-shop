package ai.neargo.auth.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 令牌的存储形态：**SHA-256 十六进制，明文永不入库**。
 *
 * <p>库被拖走 ≠ 所有人的会话被拿走。查询按哈希查，功能上无差别。
 *
 * <p><b>为什么是 SHA-256 而不是 bcrypt / argon2</b>：令牌是 128 位随机串，不是密码 ——
 * 没有字典攻击面，不需要慢哈希。上 KDF 等于给<b>每一个请求</b>加几十毫秒，
 * 那会让整套方案在第一天就被判定为「太慢」，然后被换掉。
 *
 * <p>同理**不加盐**：盐是为了让相同口令产生不同摘要，而令牌本来就各不相同。
 * 加了只是让「按哈希查」变成不可能。
 */
public final class TokenHash {

    private TokenHash() {
    }

    public static String of(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token 不能为空");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
