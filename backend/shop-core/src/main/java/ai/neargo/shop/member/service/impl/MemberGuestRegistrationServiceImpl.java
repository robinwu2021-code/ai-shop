package ai.neargo.shop.member.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.BoundActionContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ConsumerScope;
import ai.neargo.shop.spi.member.GuestRegistrationPort.CurrentClaim;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimedCustomer;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ResolvedCustomer;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestRef;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.config.MemberGuestCrypto;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.entity.MbrGuestRegistration;
import ai.neargo.shop.member.mapper.MemberGuestRegistrationMapper;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.member.service.MemberGuestRegistrationService;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.member.GuestRegistrationPort.RegisterCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Registration;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestFact;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Claim;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Current permission checks precede short owner transactions; no raw credentials enter idempotency JSON. */
@Service
public class MemberGuestRegistrationServiceImpl implements MemberGuestRegistrationService {
    private final MemberGuestRegistrationMapper rows;
    private final MerchantOperatorAccessPort access;
    private final MerchantScopeFactsPort stores;
    private final MemberCodeResolveService codes;
    private final MemberGuestCrypto crypto;
    private final UserScopeFactsPort users;
    private final TransactionTemplate writes;
    private TokenStores tokens;

    public MemberGuestRegistrationServiceImpl(MemberGuestRegistrationMapper rows, MerchantOperatorAccessPort access,
            MerchantScopeFactsPort stores, MemberCodeResolveService codes, MemberGuestCrypto crypto,
            UserScopeFactsPort users,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.rows = rows; this.access = access; this.stores = stores;
        this.codes = codes; this.crypto = crypto; this.users = users;
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Production current-consumer proof uses the actual realm token pool; legacy constructors
     * remain compatible.
     */
    @Autowired
    public MemberGuestRegistrationServiceImpl(
            MemberGuestRegistrationMapper rows,
            MerchantOperatorAccessPort access,
            MerchantScopeFactsPort stores,
            MemberCodeResolveService codes,
            MemberGuestCrypto crypto,
            UserScopeFactsPort users,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            TokenStores tokens) {
        this(rows, access, stores, codes, crypto, users, manager);
        this.tokens = tokens;
    }

    @Override
    public ClaimedCustomer resolveClaimedCustomer(Scope scope, String guestNo, String claimToken) {
        var payload = new GuestRegistrationInternalApi.Verify(scope, guestNo, claimToken);
        requireClaimedSelector(scope, payload);
        var observed =
                transaction(
                        () -> {
                            var row = guest(scope, guestNo, false);
                            verifyToken(row, claimToken);
                            if (!"CLAIMED".equals(row.getState())) {
                                throw BizException.of(ErrorCode.CONFLICT);
                            }
                            return fact(row);
                        });
        var user =
                users.findActive(scope.identity().tenantNo(), observed.userNo())
                        .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        requireClaimedSelector(scope, payload);
        var current =
                transaction(
                        () -> {
                            var row = guest(scope, guestNo, false);
                            verifyToken(row, claimToken);
                            return fact(row);
                        });
        if (!observed.equals(current)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new ClaimedCustomer(current, new ResolvedCustomer(user.userNo()));
    }

    private void requireClaimedSelector(Scope scope, GuestRegistrationInternalApi.Verify payload) {
        requireScope(scope);
        if (BoundActionContext.present()) {
            BoundActionContext.require(
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    GuestRegistrationInternalApi.COUNTER_CUSTOMER_ENDPOINT,
                                    "RESOLVE",
                                    scope.identity(),
                                    scope.entityNo(),
                                    scope.storeNo(),
                                    payload.guestNo()),
                            payload));
        } else {
            if (tokens == null) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            String token =
                    SecurityUtils.currentToken()
                            .filter(value -> !value.isBlank())
                            .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED));
            var session =
                    tokens.of(Realm.MERCHANT)
                            .get(token)
                            .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED))
                            .user();
            if (session == null
                    || !scope.identity().tenantNo().equals(session.tenantNo())
                    || !scope.identity().actorNo().equals(session.userNo())
                    || !scope.identity().realm().equals(session.realm().name())
                    || !scope.identity().subjectKind().equals(session.subjectKind().name())) {
                throw BizException.of(ErrorCode.UNAUTHORIZED);
            }
        }
    }

    @Override
    public CurrentClaim currentClaim(ConsumerScope scope, GuestRef guest) {
        if (guest == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var payload = new GuestRegistrationInternalApi.CurrentClaimRequest(scope, guest);
        requireConsumer(scope, payload, "CURRENT_CLAIM", guest.guestNo());
        var row =
                transaction(
                        () ->
                                rows.findCurrentClaim(
                                        scope.tenantNo(),
                                        scope.identity().actorNo(),
                                        guest.entityNo(),
                                        guest.storeNo(),
                                        guest.guestNo()));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var result = currentClaim(row);
        requireConsumer(scope, payload, "CURRENT_CLAIM", guest.guestNo());
        return result;
    }

    @Override
    public List<CurrentClaim> currentClaims(ConsumerScope scope) {
        var payload = new GuestRegistrationInternalApi.CurrentClaimsRequest(scope);
        String actor = scope == null ? null : scope.identity().actorNo();
        requireConsumer(scope, payload, "CURRENT_CLAIMS", actor);
        var result =
                transaction(
                        () ->
                                rows.findCurrentClaims(scope.tenantNo(), actor).stream()
                                        .map(MemberGuestRegistrationServiceImpl::currentClaim)
                                        .toList());
        requireConsumer(scope, payload, "CURRENT_CLAIMS", actor);
        return List.copyOf(result);
    }

    private void requireConsumer(
            ConsumerScope scope, Object payload, String action, String target) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("current claim proof requires no caller transaction");
        }
        if (scope == null
                || !"MAIN".equals(scope.tenantNo())
                || !scope.identity().equals(DelegatedIdentityContext.current())
                || !"CONSUMER".equals(scope.identity().realm())
                || !"USR".equals(scope.identity().subjectKind())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (BoundActionContext.present()) {
            BoundActionContext.require(
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    GuestRegistrationInternalApi.CURRENT_ENDPOINT,
                                    action,
                                    scope.identity(),
                                    null,
                                    null,
                                    target),
                            payload));
        } else {
            if (tokens == null) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            String token =
                    SecurityUtils.currentToken()
                            .filter(value -> !value.isBlank())
                            .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED));
            var session =
                    tokens.of(Realm.CONSUMER)
                            .get(token)
                            .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED))
                            .user();
            if (session == null
                    || !scope.tenantNo().equals(session.tenantNo())
                    || !scope.identity().actorNo().equals(session.userNo())
                    || !scope.identity().realm().equals(session.realm().name())
                    || !scope.identity().subjectKind().equals(session.subjectKind().name())) {
                throw BizException.of(ErrorCode.UNAUTHORIZED);
            }
        }
        if (users.findActive(scope.tenantNo(), scope.identity().actorNo()).isEmpty()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    private static CurrentClaim currentClaim(MbrGuestRegistration row) {
        return new CurrentClaim(
                row.getEntityNo(),
                row.getStoreNo(),
                row.getGuestNo(),
                row.getClaimedUserNo(),
                row.getVersion());
    }

    @Override public Registration register(Scope scope, RegisterCommand command) {
        requireScope(scope);
        if (command == null || !command.consent() || command.contact() != null && command.contact().length() > 128
                || command.displayName() != null && command.displayName().length() > 80) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        requireId(command.idempotencyKey(), 128);
        String key = MemberGuestCrypto.hash(command.idempotencyKey());
        String hash = crypto.requestFingerprint(scope.identity().tenantNo(), scope.entityNo(), scope.storeNo(),
                scope.identity().realm(), scope.identity().subjectKind(), scope.identity().actorNo(),
                command.contact(), command.displayName(), "true");
        try {
            return transaction(() -> {
                var prior = registered(scope, key);
                if (prior != null) { return replay(prior, hash); }
                var now = LocalDateTime.now(ZoneOffset.UTC);
                var row = new MbrGuestRegistration();
                row.setTenantNo(scope.identity().tenantNo()); row.setEntityNo(scope.entityNo());
                row.setStoreNo(scope.storeNo()); row.setGuestNo(BizKey.next("GUE"));
                row.setActorNo(scope.identity().actorNo()); row.setActorRealm(scope.identity().realm());
                row.setActorKind(scope.identity().subjectKind()); row.setKeyHash(key); row.setRequestHash(hash);
                row.setDisplayName(command.displayName()); row.setState("REGISTERED");
                row.setContactCipher(encrypt(row, "contact", command.contact()));
                String token = MemberGuestCrypto.token();
                row.setClaimTokenHash(MemberGuestCrypto.hash(token));
                row.setClaimTokenCipher(encrypt(row, "claim-token", token));
                row.setVersion(1L); row.setDeleted(0); row.setCreatedAt(now); row.setUpdatedAt(now);
                row.setCreatedBy(scope.identity().actorNo()); row.setUpdatedBy(scope.identity().actorNo());
                rows.insert(row);
                return new Registration(row.getGuestNo(), token, "REGISTERED", 1);
            });
        } catch (DuplicateKeyException raced) {
            return transaction(() -> {
                var prior = registered(scope, key);
                if (prior == null) { throw BizException.of(ErrorCode.CONFLICT); }
                return replay(prior, hash);
            });
        }
    }
    @Override public GuestFact get(Scope scope, String guestNo) {
        requireScope(scope); return transaction(() -> fact(guest(scope, guestNo, false)));
    }
    @Override public GuestFact verify(Scope scope, String guestNo, String token) {
        requireScope(scope);
        return transaction(() -> {
            var row = guest(scope, guestNo, false); verifyToken(row, token); return fact(row);
        });
    }
    @Override public Claim claim(Scope scope, String guestNo, ClaimCommand command) {
        requireScope(scope);
        if (command == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        requireId(command.idempotencyKey(), 128);
        // A committed replay is a stored result. Only new claims need a still-live original reference.
        var observed = transaction(() -> guest(scope, guestNo, false));
        boolean committed = "CLAIMED".equals(observed.getState());
        String customer = committed
                ? users.findActive(scope.identity().tenantNo(), observed.getClaimedUserNo())
                        .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND)).userNo()
                : codes.requireGuestClaimReference(new MemberCodeScope(scope.identity().tenantNo(), scope.entityNo(),
                        scope.storeNo(), scope.identity().actorNo()), command.verifiedIdentity()).userNo();
        String key = MemberGuestCrypto.hash(command.idempotencyKey());
        String request = MemberGuestCrypto.hash(command.verifiedIdentity(), command.claimToken());
        return transaction(() -> {
            var row = guest(scope, guestNo, true); verifyToken(row, command.claimToken());
            if ("CLAIMED".equals(row.getState())) {
                if (!key.equals(row.getClaimKeyHash()) || !request.equals(row.getClaimRequestHash())
                        || !scope.identity().actorNo().equals(row.getClaimActorNo())
                        || !scope.identity().realm().equals(row.getClaimActorRealm())
                        || !scope.identity().subjectKind().equals(row.getClaimActorKind())
                        || !customer.equals(row.getClaimedUserNo())
                        || committed && !observed.getVersion().equals(row.getVersion())) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                return new Claim(row.getGuestNo(), row.getClaimedUserNo(), row.getVersion());
            }
            if (committed) { throw BizException.of(ErrorCode.CONFLICT); }
            row.setState("CLAIMED"); row.setClaimedUserNo(customer);
            row.setClaimedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setClaimKeyHash(key);
            row.setClaimRequestHash(request); row.setClaimActorNo(scope.identity().actorNo());
            row.setClaimActorRealm(scope.identity().realm()); row.setClaimActorKind(scope.identity().subjectKind());
            row.setUpdatedAt(row.getClaimedAt()); row.setUpdatedBy(scope.identity().actorNo());
            if (rows.updateById(row) != 1) { throw BizException.of(ErrorCode.CONFLICT); }
            return new Claim(row.getGuestNo(), row.getClaimedUserNo(), row.getVersion());
        });
    }
    private void requireScope(Scope scope) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("guest preflight requires no caller transaction");
        }
        if (scope == null || scope.identity() == null) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        var current = SecurityUtils.requireUser(); var identity = scope.identity();
        if (!identity.tenantNo().equals(current.tenantNo()) || !identity.actorNo().equals(current.userNo())
                || !identity.realm().equals(current.realm().name())
                || !identity.subjectKind().equals(current.subjectKind().name())
                || !"MERCHANT".equals(identity.realm())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        requireId(scope.entityNo(), 32); requireId(scope.storeNo(), 32);
        if (!stores.entityActive(identity.tenantNo(), scope.entityNo())
                || !stores.storesOwned(identity.tenantNo(), scope.entityNo(), Set.of(scope.storeNo()))) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!access.allowed(new MerchantOperatorAccessPort.Query(identity.tenantNo(), scope.entityNo(), scope.storeNo(),
                identity.actorNo(), identity.realm(), identity.subjectKind(), BizPerms.CUSTOMER))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        crypto.requireAvailable();
    }
    private MbrGuestRegistration registered(Scope s, String key) {
        return rows.selectOne(Wrappers.<MbrGuestRegistration>lambdaQuery()
                .eq(MbrGuestRegistration::getTenantNo, s.identity().tenantNo())
                .eq(MbrGuestRegistration::getEntityNo, s.entityNo()).eq(MbrGuestRegistration::getStoreNo, s.storeNo())
                .eq(MbrGuestRegistration::getActorNo, s.identity().actorNo())
                .eq(MbrGuestRegistration::getActorRealm, s.identity().realm())
                .eq(MbrGuestRegistration::getActorKind, s.identity().subjectKind())
                .eq(MbrGuestRegistration::getKeyHash, key));
    }
    private Registration replay(MbrGuestRegistration row, String hash) {
        if (!hash.equals(row.getRequestHash())) { throw BizException.of(ErrorCode.CONFLICT); }
        String token = crypto.decrypt(row.getTenantNo(), row.getEntityNo(), row.getStoreNo(), row.getGuestNo(),
                "claim-token", row.getClaimTokenCipher());
        verifyToken(row, token);
        // Original registration response is immutable even after claim.
        return new Registration(row.getGuestNo(), token, "REGISTERED", 1);
    }
    private MbrGuestRegistration guest(Scope s, String guestNo, boolean lock) {
        requireId(guestNo, 32);
        var row = lock ? rows.lockGuest(s, guestNo) : rows.selectOne(Wrappers.<MbrGuestRegistration>lambdaQuery()
                .eq(MbrGuestRegistration::getTenantNo, s.identity().tenantNo())
                .eq(MbrGuestRegistration::getEntityNo, s.entityNo()).eq(MbrGuestRegistration::getStoreNo, s.storeNo())
                .eq(MbrGuestRegistration::getGuestNo, guestNo));
        if (row == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        return row;
    }
    private static void verifyToken(MbrGuestRegistration row, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || !MessageDigest.isEqual(
                row.getClaimTokenHash().getBytes(StandardCharsets.US_ASCII),
                MemberGuestCrypto.hash(token).getBytes(StandardCharsets.US_ASCII))) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }
    private String encrypt(MbrGuestRegistration row, String purpose, String value) {
        return crypto.encrypt(row.getTenantNo(), row.getEntityNo(), row.getStoreNo(), row.getGuestNo(), purpose, value);
    }
    private static GuestFact fact(MbrGuestRegistration row) {
        return new GuestFact(row.getGuestNo(), row.getState(), row.getClaimedUserNo(), row.getVersion());
    }
    private <T> T transaction(Supplier<T> operation) {
        return DataScopeContext.executeWithoutScope(() -> writes.execute(status -> operation.get()));
    }
    private static void requireId(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
