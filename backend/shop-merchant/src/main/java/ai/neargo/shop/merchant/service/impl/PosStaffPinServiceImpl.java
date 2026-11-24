package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.auth.PasswordHasher;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.PosStaffPinService;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosStaffPinServiceImpl implements PosStaffPinService {
    private static final int MAX_FAILURES = 3;
    private static final long LOCK_MS = 300_000L;
    private static final String KEY = " WHERE tenant_no=? AND entity_no=? AND store_no=? AND subject_kind=? AND actor_no=?";
    private final JdbcTemplate db;
    private final PasswordHasher hasher;
    private final SecretKeySpec pepper;

    public PosStaffPinServiceImpl(JdbcTemplate db, PasswordHasher hasher,
                              @Value("${shop.pos-pin.pepper:}") String configuredPepper) {
        this.db = db;
        this.hasher = hasher;
        if (configuredPepper.isBlank()) {
            this.pepper = null;
        } else {
            byte[] key = Base64.getDecoder().decode(configuredPepper.trim());
            if (key.length < 32) throw new IllegalArgumentException("shop.pos-pin.pepper needs at least 32 bytes");
            this.pepper = new SecretKeySpec(key, "HmacSHA256");
        }
    }

    private record Credential(String hash, int failures, long lockedUntil) {}

    @Override
    public Status status(Scope scope) {
        boolean enabled = enabled(scope);
        return new Status(pepper != null, enabled && pepper != null && configured(scope), enabled);
    }

    private boolean configured(Scope scope) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM mch_pos_staff_pin" + KEY, Integer.class, scopeArgs(scope));
        return count != null && count > 0;
    }

    @Override
    public boolean enabled(Scope scope) {
        return db.queryForList("""
                SELECT enabled FROM mch_pos_staff_pin_policy WHERE tenant_no=? AND entity_no=?
                """, Boolean.class, scope.tenant(), scope.entity()).stream().anyMatch(Boolean.TRUE::equals);
    }

    @Transactional
    @Override
    public void setEnabled(Scope scope, boolean enabled) {
        if (enabled && pepper == null) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        db.update("""
                INSERT INTO mch_pos_staff_pin_policy (tenant_no, entity_no, enabled) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE enabled=?
                """, scope.tenant(), scope.entity(), enabled, enabled);
    }

    @Transactional
    @Override
    public Result save(Scope scope, String newPin, String currentPin) {
        if (!enabled(scope)) return Result.of(Outcome.DISABLED);
        if (pepper == null) return Result.of(Outcome.UNAVAILABLE);
        if (PosStaffPinService.weak(newPin)) return Result.of(Outcome.WEAK);
        if (currentPin == null) {
            if (configured(scope)) return Result.of(Outcome.CURRENT_REQUIRED);
            try {
                db.update("""
                        INSERT INTO mch_pos_staff_pin(tenant_no,entity_no,store_no,subject_kind,actor_no,pin_hash)
                        VALUES(?,?,?,?,?,?)
                        """, scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor(),
                        hasher.encode(keyed(scope, newPin)));
                return Result.of(Outcome.SAVED);
            } catch (DuplicateKeyException concurrentSetup) {
                return Result.of(Outcome.CURRENT_REQUIRED);
            }
        }
        Result attempt = check(scope, currentPin);
        if (attempt.outcome() != Outcome.MATCH) return attempt;
        db.update("UPDATE mch_pos_staff_pin SET pin_hash=?" + KEY, hasher.encode(keyed(scope, newPin)),
                scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor());
        return Result.of(Outcome.SAVED);
    }

    @Transactional
    @Override
    public Result verify(Scope scope, String pin) {
        if (!enabled(scope)) return Result.of(Outcome.DISABLED);
        return pepper == null ? Result.of(Outcome.UNAVAILABLE) : check(scope, pin);
    }

    /** Called only after a scoped recovery grant has been consumed in the caller's transaction. */
    @Transactional
    @Override
    public Result reset(Scope scope, String newPin) {
        if (!enabled(scope)) return Result.of(Outcome.DISABLED);
        if (pepper == null) return Result.of(Outcome.UNAVAILABLE);
        if (PosStaffPinService.weak(newPin)) return Result.of(Outcome.WEAK);
        int updated = db.update("UPDATE mch_pos_staff_pin SET pin_hash=?, failed_count=0, locked_until_ms=NULL" + KEY,
                hasher.encode(keyed(scope, newPin)), scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor());
        return Result.of(updated == 1 ? Outcome.SAVED : Outcome.NOT_SET);
    }

    private Result check(Scope scope, String pin) {
        var rows = db.query("SELECT pin_hash,failed_count,locked_until_ms FROM mch_pos_staff_pin" + KEY + " FOR UPDATE",
                (rs, i) -> new Credential(rs.getString(1), rs.getInt(2), rs.getLong(3)), scopeArgs(scope));
        if (rows.isEmpty()) return Result.of(Outcome.NOT_SET);
        Credential row = rows.getFirst();
        long now = System.currentTimeMillis();
        if (row.lockedUntil() > now) return new Result(Outcome.LOCKED, null, row.lockedUntil());
        if (hasher.matches(keyed(scope, pin), row.hash())) {
            if (row.failures() != 0) writeAttempts(scope, 0, null);
            return Result.of(Outcome.MATCH);
        }
        int failures = (row.lockedUntil() == 0 ? row.failures() : 0) + 1;
        Long lockedUntil = failures >= MAX_FAILURES ? now + LOCK_MS : null;
        writeAttempts(scope, failures, lockedUntil);
        return lockedUntil != null ? new Result(Outcome.LOCKED, null, lockedUntil)
                : new Result(Outcome.WRONG, MAX_FAILURES - failures, null);
    }

    private void writeAttempts(Scope scope, int failures, Long lockedUntil) {
        db.update("UPDATE mch_pos_staff_pin SET failed_count=?, locked_until_ms=?" + KEY, failures, lockedUntil,
                scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor());
    }

    private String keyed(Scope scope, String pin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(pepper);
            String value = String.join(":", scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor(), pin);
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("POS PIN hashing failed", e);
        }
    }

    private static Object[] scopeArgs(Scope scope) {
        return new Object[]{scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor()};
    }
}
