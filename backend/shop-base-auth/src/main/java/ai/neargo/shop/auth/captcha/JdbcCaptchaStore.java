package ai.neargo.shop.auth.captcha;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Authoritative state lives in the shared database, never in a process-local cache. */
public class JdbcCaptchaStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcCaptchaStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        // A failed login/send must not roll back consumption and resurrect a proof.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void save(String token, String scope, String payload, int seconds) {
        jdbc.update("INSERT INTO sys_auth_captcha_state(state_key,scope_hash,payload,expires_at) VALUES (?,?,?,?)",
                hash(token), scope, payload, clock.millis() + seconds * 1000L);
    }

    public String consume(String token, String scope) {
        if (token == null) return null;
        return transaction.execute(status -> {
            var entries = jdbc.query("SELECT scope_hash,payload,expires_at FROM sys_auth_captcha_state WHERE state_key=? FOR UPDATE",
                    (rs, row) -> new Entry(rs.getString(1), rs.getString(2), rs.getLong(3)), hash(token));
            if (entries.isEmpty()) return null;
            jdbc.update("DELETE FROM sys_auth_captcha_state WHERE state_key=?", hash(token));
            Entry entry = entries.getFirst();
            return entry.scope().equals(scope) && entry.expiresAt() > clock.millis() ? entry.payload() : null;
        });
    }

    /** Fixed-window counters are atomic across JVMs. Rejected requests retain their count. */
    public void limit(String key, int maximum) {
        String digest = hash(key);
        long now = clock.millis();
        // Cleanup uses separate autocommit statements: holding expiry range locks while
        // inserting a counter caused an InnoDB deadlock under concurrent first requests.
        jdbc.update("DELETE FROM sys_auth_captcha_state WHERE expires_at<=?", now);
        jdbc.update("DELETE FROM sys_auth_captcha_rate WHERE expires_at<=?", now);
        Integer hits = transaction.execute(status -> {
            jdbc.update("INSERT INTO sys_auth_captcha_rate(rate_key,hits,expires_at) VALUES (?,1,?) "
                    + "ON DUPLICATE KEY UPDATE hits=hits+1", digest, now + 60_000L);
            return jdbc.queryForObject("SELECT hits FROM sys_auth_captcha_rate WHERE rate_key=?", Integer.class, digest);
        });
        if (hits > maximum) throw BizException.of(ErrorCode.TOO_MANY_REQUESTS);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private record Entry(String scope, String payload, long expiresAt) {}
}
