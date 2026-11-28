package ai.neargo.shop.merchantbridge.impl;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;

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

/**
 * 收银员 PIN 找回：先过一道挑战（登录密码 / 手机验证码 / 邮箱验证码），过了发一张短时重置票，凭票改 PIN。
 *
 * <p>平台基础库换表 I（拆库清单：「PIN 是一种登录凭证」）：原 {@code mch_pos_pin_recovery} 一行装挑战与重置，
 * 新库并进一次性票据 {@code auth_ticket}，拆成两行——挑战（{@value #CHALLENGE}）与重置（{@value #RESET}，
 * {@code source_ticket_hash} 指回挑战）。票据表只存哈希，所以挑战号由服务端按「会话哈希 + 员工范围 + 方式 + 签发时刻」
 * 确定性算出：同一会话重复要挑战时能重算出同一个号（一员工一方式同时只有一张有效挑战，5 分钟内最多试 5 次），
 * 而挑战号离开本人会话没有用处。挑战验过即作废（不能再试），重置票用一次即作废；改成功后这名员工所有未用的票一并作废。
 */
@Service
public class PosPinRecoveryService {
    static final String CHALLENGE = "PIN_CHALLENGE";
    static final String RESET = "PIN_RESET";
    private static final long CHALLENGE_MS = 300_000L;
    private static final long RESET_MS = 120_000L;
    private static final int MAX_ATTEMPTS = 5;
    private static final String SCOPE = " tenant_no=? AND entity_no=? AND store_no=? AND actor_kind=? AND actor_no=?";
    private static final String FIND = "SELECT id, ticket_hash, origin_token_hash, reset_method, issued_at, expires_at, attempts"
            + " FROM auth_ticket WHERE purpose='" + CHALLENGE + "' AND deleted=0 AND consumed_at IS NULL AND";
    private final JdbcTemplate db;
    private final PosStaffPinService pins;
    private final MerchantAccountService accounts;
    private final AuthService auth;
    private final OtpSendService otp;

    public PosPinRecoveryService(@Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate db, PosStaffPinService pins, MerchantAccountService accounts,
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
    private record Row(long id, String ticket, String session, Method method, long issued, long expires, int attempts) {}

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

    @Transactional(transactionManager = ModuleDataSources.PLATFORM_TX_MANAGER)
    public Challenge challenge(Scope scope, LoginUser user, String session, Method method, String ip) {
        if (!methods(scope, user).contains(method)) throw BizException.of(ErrorCode.FORBIDDEN);
        String sessionHash = TokenHash.of(session);
        long now = System.currentTimeMillis();
        // 同一员工的挑战串行：锁住他的 PIN（有 PIN 才有找回，methods 已判过）
        lockCredential(scope);
        var live = db.query(FIND + SCOPE + " AND reset_method=? AND expires_at>? FOR UPDATE", (rs, i) -> row(rs),
                scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor(), method.name(), at(now));
        Row row;
        boolean fresh = live.isEmpty();
        if (fresh) {
            long expires = now + CHALLENGE_MS;
            String id = challengeId(sessionHash, scope, method, now);
            db.update("""
                    INSERT INTO auth_ticket(tenant_no, ticket_hash, purpose, entity_no, store_no, actor_kind, actor_no,
                        origin_token_hash, reset_method, attempts, issued_at, expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,0,?,?)
                    """, scope.tenant(), TokenHash.of(id), CHALLENGE, scope.entity(), scope.store(), scope.subjectKind(),
                    scope.actor(), sessionHash, method.name(), at(now), at(expires));
            row = new Row(0, TokenHash.of(id), sessionHash, method, now, expires, 0);
        } else {
            row = live.getFirst();
            if (!row.session().equals(sessionHash)) throw BizException.of(ErrorCode.FORBIDDEN);
        }
        String id = challengeId(row.session(), scope, method, row.issued());
        String recipient = recipient(accounts.account(user), method);
        Integer resend = null;
        if (method != Method.PASSWORD) {
            resend = fresh ? otp.send(new OtpSendService.SendCommand(OtpScenes.POS_PIN_RECOVERY,
                    channel(method), recipient, id, "zh-Hant", Map.of(), null), otpContext(ip)).resendAfterSeconds()
                    : (int) Math.max(0, (row.expires() - now + 999) / 1000);
        }
        return new Challenge(id, row.expires(), recipient == null ? null : mask(recipient), resend);
    }

    public Proof verify(Scope scope, LoginUser user, String session, String id, String credential, String ip) {
        if (!pins.enabled(scope)) return new Proof("EXPIRED", null, null, null);
        long checkedAt = System.currentTimeMillis();
        var rows = db.query(FIND + SCOPE + " AND ticket_hash=? AND origin_token_hash=? AND expires_at>?", (rs, i) -> row(rs),
                scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor(), TokenHash.of(id),
                TokenHash.of(session), at(checkedAt));
        if (rows.isEmpty()) return new Proof("EXPIRED", null, null, null);
        Row row = rows.getFirst();
        int reserved = db.update("""
                UPDATE auth_ticket SET attempts=attempts+1
                WHERE id=? AND consumed_at IS NULL AND expires_at>? AND attempts=? AND attempts<?
                """, row.id(), at(System.currentTimeMillis()), row.attempts(), MAX_ATTEMPTS);
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
        // 挑战验过即作废：抢到作废的那一次才发重置票
        int consumed = db.update("UPDATE auth_ticket SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND expires_at>?",
                at(now), row.id(), at(now));
        if (consumed != 1) return new Proof("EXPIRED", null, null, null);
        db.update("""
                INSERT INTO auth_ticket(tenant_no, ticket_hash, purpose, entity_no, store_no, actor_kind, actor_no,
                    origin_token_hash, source_ticket_hash, reset_method, issued_at, expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, scope.tenant(), TokenHash.of(reset), RESET, scope.entity(), scope.store(), scope.subjectKind(),
                scope.actor(), row.session(), row.ticket(), row.method().name(), at(now), at(expiry));
        return new Proof("VERIFIED", reset, expiry, null);
    }

    @Transactional(transactionManager = ModuleDataSources.PLATFORM_TX_MANAGER)
    public Result reset(Scope scope, String session, String token, String newPin) {
        if (!pins.enabled(scope)) return Result.of(Outcome.DISABLED);
        if (PosStaffPinService.weak(newPin)) return Result.of(Outcome.WEAK);
        long now = System.currentTimeMillis();
        // Serialize all reset methods for a single employee credential before claiming any grant.
        if (!lockCredential(scope)) return Result.of(Outcome.NOT_SET);
        int claimed = db.update("UPDATE auth_ticket SET consumed_at=? WHERE purpose='" + RESET + "' AND deleted=0 AND" + SCOPE
                        + " AND origin_token_hash=? AND ticket_hash=? AND consumed_at IS NULL AND expires_at>?",
                at(now), scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor(),
                TokenHash.of(session), TokenHash.of(token), at(now));
        if (claimed != 1) throw BizException.of(ErrorCode.BAD_REQUEST);
        Result result = pins.reset(scope, newPin);
        if (result.outcome() != Outcome.SAVED) throw BizException.of(ErrorCode.BAD_REQUEST);
        db.update("UPDATE auth_ticket SET consumed_at=? WHERE purpose IN ('" + CHALLENGE + "','" + RESET + "') AND" + SCOPE
                        + " AND consumed_at IS NULL",
                at(now), scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor());
        return result;
    }

    private boolean lockCredential(Scope scope) {
        return !db.queryForList("SELECT 1 FROM mch_pos_staff_pin WHERE tenant_no=? AND entity_no=? AND store_no=?"
                        + " AND subject_kind=? AND actor_no=? FOR UPDATE", Integer.class,
                scope.tenant(), scope.entity(), scope.store(), scope.subjectKind(), scope.actor()).isEmpty();
    }

    /** 挑战号：按会话哈希、员工范围、方式与签发时刻确定性生成；票据表里只存它的哈希 */
    static String challengeId(String sessionHash, Scope scope, Method method, long issuedAtMs) {
        return UUID.nameUUIDFromBytes(String.join("|", sessionHash, scope.tenant(), scope.entity(), scope.store(),
                scope.subjectKind(), scope.actor(), method.name(), Long.toString(issuedAtMs))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static java.sql.Timestamp at(long ms) {
        return new java.sql.Timestamp(ms);
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong(1), rs.getString(2), rs.getString(3), Method.valueOf(rs.getString(4)),
                rs.getTimestamp(5).getTime(), rs.getTimestamp(6).getTime(), rs.getInt(7));
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
