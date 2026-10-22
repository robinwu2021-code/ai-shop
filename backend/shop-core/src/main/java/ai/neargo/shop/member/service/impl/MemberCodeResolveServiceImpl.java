package ai.neargo.shop.member.service.impl;

import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.auth.SecurityUtils;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.config.MemberCodeProperties;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.dto.MemberCodeViews.CustomerReference;
import ai.neargo.shop.member.dto.MemberCodeViews.MemberCodeView;
import ai.neargo.shop.member.entity.MbrIdentityTicket;
import ai.neargo.shop.member.mapper.MemberIdentityTicketMapper;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ResolvedCustomer;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort.ActiveUser;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** No process-local ticket map and no login-token fallback; the member domain owns credential facts. */
@Service
public class MemberCodeResolveServiceImpl implements MemberCodeResolveService {
    private static final String CODE = "MEMBER_CODE";
    private static final String REFERENCE = "CUSTOMER_REFERENCE";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final MemberIdentityTicketMapper tickets;
    private final UserScopeFactsPort users;
    private final MerchantScopeFactsPort merchants;
    private final MemberCodeProperties properties;

    public MemberCodeResolveServiceImpl(MemberIdentityTicketMapper tickets, UserScopeFactsPort users,
            MerchantScopeFactsPort merchants, MemberCodeProperties properties) {
        this.tickets = tickets;
        this.users = users;
        this.merchants = merchants;
        this.properties = properties;
    }

    @Override
    @Transactional
    public MemberCodeView issue(String tenantNo, String userNo) {
        requireEnabled();
        requireId(tenantNo, 32);
        requireId(userNo, 64);
        activeUser(tenantNo, userNo);
        String raw = token("mc1_");
        var row = ticket(tenantNo, userNo, CODE, raw, 60, userNo);
        persist(row);
        return new MemberCodeView(raw, row.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    @Override
    @Transactional
    public CustomerReference resolve(MemberCodeScope scope, String memberCode) {
        requireEnabled();
        requireMerchant(scope);
        var code = lookup(scope.tenantNo(), CODE, "mc1_", memberCode, ErrorCode.MEMBER_CODE_INVALID);
        var user = activeUser(scope.tenantNo(), code.getUserNo());
        String raw = token("cr1_");
        var ref = ticket(scope.tenantNo(), user.userNo(), REFERENCE, raw, 120, scope.actorNo());
        ref.setEntityNo(scope.entityNo());
        ref.setStoreNo(scope.storeNo());
        ref.setActorNo(scope.actorNo());
        SecurityUtils.currentUser().ifPresent(current -> {
            if (!"MERCHANT".equals(current.realm().name()) || !current.tenantNo().equals(scope.tenantNo())
                    || !current.userNo().equals(scope.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            ref.setActorRealm(current.realm().name()); ref.setActorKind(current.subjectKind().name());
        });
        ref.setSourceTicketHash(code.getTicketHash());
        persist(ref);
        return new CustomerReference(raw, mask(user.nickname()), ref.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedCustomer requireReference(MemberCodeScope scope, String customerRef) {
        requireMerchant(scope);
        var ref = lookup(scope.tenantNo(), REFERENCE, "cr1_", customerRef, ErrorCode.NOT_FOUND);
        if (!scope.entityNo().equals(ref.getEntityNo()) || !scope.storeNo().equals(ref.getStoreNo())
                || !scope.actorNo().equals(ref.getActorNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        activeUser(scope.tenantNo(), ref.getUserNo());
        return new ResolvedCustomer(ref.getUserNo());
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedCustomer requireGuestClaimReference(MemberCodeScope scope, String customerRef) {
        var current = SecurityUtils.requireUser();
        if (scope == null || !"MERCHANT".equals(current.realm().name())
                || !current.tenantNo().equals(scope.tenantNo()) || !current.userNo().equals(scope.actorNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        requireMerchant(scope);
        var ref = lookup(scope.tenantNo(), REFERENCE, "cr1_", customerRef, ErrorCode.NOT_FOUND);
        if (!scope.entityNo().equals(ref.getEntityNo()) || !scope.storeNo().equals(ref.getStoreNo())
                || !scope.actorNo().equals(ref.getActorNo()) || !current.realm().name().equals(ref.getActorRealm())
                || !current.subjectKind().name().equals(ref.getActorKind())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        activeUser(scope.tenantNo(), ref.getUserNo());
        return new ResolvedCustomer(ref.getUserNo());
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedCustomer requireConsumerProof(String tenantNo, String userNo, String memberCode) {
        var current = SecurityUtils.requireUser();
        if (!"CONSUMER".equals(current.realm().name()) || !"USR".equals(current.subjectKind().name())
                || !current.tenantNo().equals(tenantNo) || !current.userNo().equals(userNo)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        requireEnabled();
        var ticket = lookup(tenantNo, CODE, "mc1_", memberCode, ErrorCode.MEMBER_CODE_INVALID);
        if (!userNo.equals(ticket.getUserNo())) { throw BizException.of(ErrorCode.MEMBER_CODE_INVALID); }
        activeUser(tenantNo, userNo);
        return new ResolvedCustomer(userNo);
    }

    private MbrIdentityTicket lookup(String tenant, String purpose, String prefix, String raw, ErrorCode failure) {
        if (raw == null || !raw.matches(prefix + "[A-Za-z0-9_-]{43}")) {
            throw BizException.of(failure);
        }
        var row = DataScopeContext.executeWithoutScope(() -> tickets.selectOne(
                Wrappers.<MbrIdentityTicket>lambdaQuery().eq(MbrIdentityTicket::getTenantNo, tenant)
                        .eq(MbrIdentityTicket::getPurpose, purpose).eq(MbrIdentityTicket::getTicketHash, hash(raw))));
        if (row == null || !LocalDateTime.now(ZoneOffset.UTC).isBefore(row.getExpiresAt())) {
            throw BizException.of(failure);
        }
        return row;
    }

    private void requireMerchant(MemberCodeScope scope) {
        if (scope == null || !merchants.entityActive(scope.tenantNo(), scope.entityNo())
                || !merchants.storesOwned(scope.tenantNo(), scope.entityNo(), Set.of(scope.storeNo()))) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private ActiveUser activeUser(String tenant, String user) {
        return users.findActive(tenant, user).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
    }

    private void persist(MbrIdentityTicket row) {
        DataScopeContext.executeWithoutScope(() -> tickets.insert(row));
    }

    private static MbrIdentityTicket ticket(String tenant, String user, String purpose, String raw,
            int ttl, String actor) {
        var now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        var row = new MbrIdentityTicket();
        row.setTenantNo(tenant);
        row.setUserNo(user);
        row.setPurpose(purpose);
        row.setTicketHash(hash(raw));
        row.setIssuedAt(now);
        row.setExpiresAt(now.plusSeconds(ttl));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setVersion(0L);
        row.setDeleted(0);
        return row;
    }

    private static String token(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String mask(String name) {
        return name == null || name.isBlank() ? "顾客" : name.substring(0, name.offsetByCodePoints(0, 1)) + "**";
    }

    private static void requireId(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
