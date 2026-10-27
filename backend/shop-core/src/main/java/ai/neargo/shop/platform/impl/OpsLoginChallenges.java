package ai.neargo.shop.platform.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 运营登录第二因子的挑战（支付服务-安全规范 §2.7）：密码对了发一封 6 位码到登录邮箱，
 * 凭「挑战号 + 码」换会话。挑战号 32 字节随机、库里（内存里）只存哈希；码 5 分钟有效、最多试 5 次、用一次即作废。
 * 与 {@code PasswordResetTokens} 同形：单实例内存，同一账号再登录一次旧挑战全部作废。
 */
@Component
public class OpsLoginChallenges {

    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;
    private static final int MAX_PENDING = 10_000;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> pending = new ConcurrentHashMap<>();

    private static final class Entry {
        final String staffNo;
        final String codeHash;
        final Instant expireAt;
        int attempts;

        Entry(String staffNo, String codeHash, Instant expireAt) {
            this.staffNo = staffNo;
            this.codeHash = codeHash;
            this.expireAt = expireAt;
        }
    }

    public record Issued(String challenge, String code) {
    }

    public Issued issue(String staffNo) {
        Instant now = Instant.now();
        pending.values().removeIf(e -> e.expireAt.isBefore(now) || e.staffNo.equals(staffNo));
        if (pending.size() > MAX_PENDING) {
            pending.clear();
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String code = String.format("%06d", random.nextInt(1_000_000));
        pending.put(sha256(challenge), new Entry(staffNo, sha256(code), now.plus(TTL)));
        return new Issued(challenge, code);
    }

    /** 码对 → 运营员工号（挑战随即作废）；码错计一次，满 5 次作废；过期、不存在 → 空 */
    public Optional<String> verify(String challenge, String code) {
        if (challenge == null || challenge.isBlank() || code == null || code.isBlank()) {
            return Optional.empty();
        }
        String key = sha256(challenge);
        Entry e = pending.get(key);
        if (e == null) {
            return Optional.empty();
        }
        synchronized (e) {
            if (Instant.now().isAfter(e.expireAt) || e.attempts >= MAX_ATTEMPTS) {
                pending.remove(key);
                return Optional.empty();
            }
            if (!MessageDigest.isEqual(e.codeHash.getBytes(StandardCharsets.UTF_8),
                    sha256(code.trim()).getBytes(StandardCharsets.UTF_8))) {
                e.attempts++;
                if (e.attempts >= MAX_ATTEMPTS) {
                    pending.remove(key);
                }
                return Optional.empty();
            }
            pending.remove(key);
            return Optional.of(e.staffNo);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
