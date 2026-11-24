package ai.neargo.shop.merchantbridge.impl;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.otp.OtpEntries;
import ai.neargo.shop.common.otp.OtpScenes;
import ai.neargo.shop.common.otp.service.OtpSendService;
import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import ai.neargo.shop.common.otp.service.OtpSendService.OtpContext;
import ai.neargo.shop.merchant.service.MerchantAccountService;
import ai.neargo.shop.merchant.service.PosStaffPinService;
import ai.neargo.shop.merchant.service.PosStaffPinService.Outcome;
import ai.neargo.shop.merchant.service.PosStaffPinService.Result;
import ai.neargo.shop.merchant.service.PosStaffPinService.Scope;
import ai.neargo.shop.user.service.AuthService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosPinRecoveryService {
    private static final long CHALLENGE_MS = 300_000L;
    private static final long RESET_MS = 120_000L;
    private static final int MAX_ATTEMPTS = 5;
    private static final String WHERE_SCOPE =
            " WHERE tenant_no=? AND entity_no=? AND store_no=? AND subject_kind=? AND actor_no=?";
    private static final String FIND =
            "SELECT challenge_id, session_hash, method, expires_at_ms, attempts FROM mch_pos_pin_recovery";
    private final JdbcTemplate db;
    private final PosStaffPinService pins;
    private final MerchantAccountService accounts;
    private final AuthService auth;
    private final OtpSendService otp;

    public PosPinRecoveryService(JdbcTemplate db, PosStaffPinService pins, MerchantAccountService accounts,
                                 AuthService auth, OtpSendService otp) {
        this.db = db;
        this.pins = pins;
        this.accounts = accounts;
        this.auth = auth;
        this.otp = otp;
    }

    public enum Method { PASSWORD, PHONE_OTP, EMAIL_OTP }
    public record Challenge(String challengeId, long expiresAtMs, String maskedRecipient, Integer resendAfterSeconds) {}
    public record Proof(String outcome, String resetToken, Long expiresAtMs, Integer remaining) {}
    private record Row(String id, String session, Method method, long expires, int attempts) {}

    public List<Method> methods(Scope scope, LoginUser user) {
        if (!pins.status(scope).configured()) return List.of();
        var account = accounts.account(user);
        var methods = new ArrayList<Method>();
        if (account.hasPassword() && principal(account) != null) methods.add(Method.PASSWORD);
        if (account.phone() != null && account.smsUnavailablePrefixes().stream().noneMatch(account.phone()::startsWith))
            methods.add(Method.PHONE_OTP);
        if (account.email() != null) methods.add(Method.EMAIL_OTP);
        return methods;
    }

    @Transactional
    public Challenge challenge(Scope scope, LoginUser user, String session, Method method, String ip) {
        if (!methods(scope, user).contains(method)) throw BizException.of(ErrorCode.FORBIDDEN);
        String sessionHash = TokenHash.of(session);
        long now = System.currentTimeMillis();
        var args = List.of(scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor(), method.name());
        String id = UUID.randomUUID().toString();
        db.update("""
                INSERT INTO mch_pos_pin_recovery(tenant_no, entity_no, store_no, subject_kind, actor_no, method,
                    challenge_id, session_hash, expires_at_ms)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    challenge_id=CASE WHEN expires_at_ms<=? THEN ? ELSE challenge_id END,
                    session_hash=CASE WHEN expires_at_ms<=? THEN ? ELSE session_hash END,
                    attempts=CASE WHEN expires_at_ms<=? THEN 0 ELSE attempts END,
                    reset_hash=CASE WHEN expires_at_ms<=? THEN NULL ELSE reset_hash END,
                    reset_expires_at_ms=CASE WHEN expires_at_ms<=? THEN NULL ELSE reset_expires_at_ms END,
                    consumed_at_ms=CASE WHEN expires_at_ms<=? THEN NULL ELSE consumed_at_ms END,
                    expires_at_ms=CASE WHEN expires_at_ms<=? THEN ? ELSE expires_at_ms END
                """, scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor(), method.name(),
                id, sessionHash, now + CHALLENGE_MS, now, id, now, sessionHash, now, now, now, now, now, now + CHALLENGE_MS);
        Row row = db.query(FIND + WHERE_SCOPE + " AND method=? FOR UPDATE", (rs, index) -> row(rs), args.toArray()).getFirst();
        if (!row.session().equals(sessionHash)) throw BizException.of(ErrorCode.FORBIDDEN);
        String recipient = recipient(accounts.account(user), method);
        Integer resend = null;
        if (method != Method.PASSWORD) {
            resend = row.id().equals(id) ? otp.send(new OtpSendService.SendCommand(OtpScenes.POS_PIN_RECOVERY,
                    channel(method), recipient, row.id(), "zh-Hant", Map.of(), null), otpContext(ip)).resendAfterSeconds()
                    : (int) Math.max(0, (row.expires() - now + 999) / 1000);
        }
        return new Challenge(row.id(), row.expires(), recipient == null ? null : mask(recipient), resend);
    }

    public Proof verify(Scope scope, LoginUser user, String session, String id, String credential, String ip) {
        if (!pins.enabled(scope)) return new Proof("EXPIRED", null, null, null);
        var rows = db.query(FIND + WHERE_SCOPE + " AND challenge_id=? AND session_hash=?",
                (rs, i) -> row(rs), scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor(), id, TokenHash.of(session));
        if (rows.isEmpty()) return new Proof("EXPIRED", null, null, null);
        Row row = rows.getFirst();
        int reserved = db.update("""
                UPDATE mch_pos_pin_recovery SET attempts=attempts+1
                WHERE challenge_id=? AND session_hash=? AND expires_at_ms>? AND reset_hash IS NULL AND consumed_at_ms IS NULL AND attempts=?
                AND attempts<?
                """, id, row.session(), System.currentTimeMillis(), row.attempts(), MAX_ATTEMPTS);
        if (reserved != 1) return new Proof("EXPIRED", null, null, null);
        var account = accounts.account(user);
        boolean passed;
        if (row.method() == Method.PASSWORD) {
            try {
                passed = account.hasPassword() && principal(account) != null && account.userNo() != null
                        && Objects.equals(account.userNo(), auth.verify(new AuthService.LoginCommand("PASSWORD",
                        principal(account), credential, null, null, true)).userNo());
            } catch (BizException badCredential) {
                passed = false;
            }
        } else {
            String recipient = recipient(account, row.method());
            passed = recipient != null && otp.verifyAndConsume(new OtpSendService.VerifyCommand(
                    OtpScenes.POS_PIN_RECOVERY, channel(row.method()), recipient, id, credential), otpContext(ip));
        }
        if (!passed) return new Proof("WRONG", null, null, MAX_ATTEMPTS - row.attempts() - 1);
        String reset = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expiry = Math.min(row.expires(), now + RESET_MS);
        int issued = db.update("""
                UPDATE mch_pos_pin_recovery SET reset_hash=?, reset_expires_at_ms=?
                WHERE challenge_id=? AND session_hash=? AND expires_at_ms>? AND reset_hash IS NULL AND consumed_at_ms IS NULL
                """, TokenHash.of(reset), expiry, id, row.session(), now);
        return issued == 1 ? new Proof("VERIFIED", reset, expiry, null) : new Proof("EXPIRED", null, null, null);
    }

    @Transactional
    public Result reset(Scope scope, String session, String token, String newPin) {
        if (!pins.enabled(scope)) return Result.of(Outcome.DISABLED);
        if (PosStaffPinService.weak(newPin)) return Result.of(Outcome.WEAK);
        long now = System.currentTimeMillis();
        // Serialize all reset methods for a single employee credential before claiming any grant.
        var credentials = db.queryForList("SELECT 1 FROM mch_pos_staff_pin" + WHERE_SCOPE + " FOR UPDATE", Integer.class,
                scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor());
        if (credentials.isEmpty()) return Result.of(Outcome.NOT_SET);
        int claimed = db.update("UPDATE mch_pos_pin_recovery SET consumed_at_ms=?" + WHERE_SCOPE
                        + " AND session_hash=? AND reset_hash=? AND consumed_at_ms IS NULL AND reset_expires_at_ms>?",
                now, scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor(),
                TokenHash.of(session), TokenHash.of(token), now);
        if (claimed != 1) throw BizException.of(ErrorCode.BAD_REQUEST);
        Result result = pins.reset(scope, newPin);
        if (result.outcome() != Outcome.SAVED) throw BizException.of(ErrorCode.BAD_REQUEST);
        db.update("UPDATE mch_pos_pin_recovery SET consumed_at_ms=?" + WHERE_SCOPE + " AND consumed_at_ms IS NULL",
                now, scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor());
        return result;
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getString(1), rs.getString(2), Method.valueOf(rs.getString(3)),
                rs.getLong(4), rs.getInt(5));
    }

    private static String principal(MerchantAccountService.Account a) { return a.phone() != null ? a.phone() : a.email(); }
    private static String recipient(MerchantAccountService.Account a, Method m) {
        return switch (m) {
            case PASSWORD -> null;
            case PHONE_OTP -> a.phone();
            case EMAIL_OTP -> a.email();
        };
    }
    private static Channel channel(Method m) { return m == Method.PHONE_OTP ? Channel.SMS : Channel.EMAIL; }
    private static String mask(String value) { return "•••" + value.substring(Math.max(0, value.length() - 4)); }
    private static OtpContext otpContext(String ip) { return new OtpContext(OtpEntries.POS_PIN_RECOVERY, ip, null); }
}
