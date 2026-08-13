package ai.neargo.shop.platform.impl;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运营端密码重置令牌。
 *
 * <p><b>存哈希不存明文</b>：这些令牌等价于「凭它就能改任意账号的密码」。
 * 明文存着的话，一次内存 dump 或（将来换 Redis 后）一次快照泄露，
 * 就等于把所有正在重置的账号交出去。与 {@code OtpStore} 该做而没做的是同一件事，
 * 这里从第一天就做对。
 *
 * <p><b>一次性 + 15 分钟</b>：重置链接会留在邮箱里，而邮箱可能被转发、被同步到
 * 别的设备。用过即焚 + 短有效期，把「链接躺在收件箱里」这段时间的风险压到最小。
 *
 * <p><b>同一账号新发即作废旧的</b>：连点两次「忘记密码」会收到两封信，
 * 两个链接都能用的话，用户不知道该点哪个，而攻击者两个都能试。
 *
 * <p>⚠️ 进程内。与限流、验证码同样的过渡态：运营端 1–2 实例够用，
 * 多实例无粘性时表现为「链接总是失效」，那时换 Redis 只改这一个类。
 */
@Component
public class PasswordResetTokens {

    private static final Duration TTL = Duration.ofMinutes(15);
    private static final int MAX_PENDING = 10_000;

    private final SecureRandom random = new SecureRandom();
    /** hash(token) → 谁 + 何时过期 */
    private final Map<String, Entry> pending = new ConcurrentHashMap<>();

    private record Entry(String staffNo, Instant expireAt) {
    }

    /** @return 明文令牌，**只在这一次出现**（放进邮件里） */
    public String issue(String staffNo) {
        sweep();
        if (pending.size() > MAX_PENDING) {
            pending.clear();
        }
        // 同一账号的旧令牌全部作废
        pending.values().removeIf(e -> e.staffNo().equals(staffNo));

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        pending.put(hash(token), new Entry(staffNo, Instant.now().plus(TTL)));
        return token;
    }

    /**
     * 校验并**消费**。
     *
     * @return 该令牌对应的 staffNo；无效或过期时为空
     */
    public Optional<String> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Entry e = pending.remove(hash(token));
        if (e == null || Instant.now().isAfter(e.expireAt())) {
            return Optional.empty();
        }
        return Optional.of(e.staffNo());
    }

    private static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void sweep() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> now.isAfter(e.getValue().expireAt()));
    }
}
