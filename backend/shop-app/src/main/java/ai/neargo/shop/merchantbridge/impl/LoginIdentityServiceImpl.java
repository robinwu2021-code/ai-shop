package ai.neargo.shop.merchantbridge.impl;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.DbTokenStore;
import ai.neargo.shop.common.*;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.service.LoginIdentityService;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.user.service.LoginTicketService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.*;
import java.util.function.BiFunction;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Application composition; merchant and user modules retain their domain boundaries. */
@Service
public class LoginIdentityServiceImpl implements LoginIdentityService {
    private final AuthService auth;
    private final LoginTicketService tickets;
    private final TokenStore tokens;
    private final TokenStore merchantTokens;
    private final LoginAuditor auditor;
    private final MchAccountMapper accounts;
    private final MchEntityMapper entities;
    private final MchStoreMapper stores;
    private final MchStoreRoleMapper roles;
    private final IdentityMapper credentials;
    private final UserMapper users;
    private final MessageSource messages;

    public LoginIdentityServiceImpl(AuthService auth, LoginTicketService tickets, TokenStore tokens,
            LoginAuditor auditor, MchAccountMapper accounts, MchEntityMapper entities, MchStoreMapper stores,
            MchStoreRoleMapper roles, IdentityMapper credentials, UserMapper users, MessageSource messages) {
        this.auth = auth; this.tickets = tickets; this.tokens = tokens; this.auditor = auditor;
        this.merchantTokens = tokens instanceof TokenStores pools ? pools.of(Realm.MERCHANT) : tokens;
        this.accounts = accounts; this.entities = entities; this.stores = stores; this.roles = roles;
        this.credentials = credentials; this.users = users; this.messages = messages;
    }

    private String label(String code, Locale locale) {
        return switch (code) {
            case "OWNER" -> messages.getMessage("auth.identity.role.owner", null, code, locale);
            case "MANAGER" -> messages.getMessage("auth.identity.role.manager", null, code, locale);
            case "CLERK" -> messages.getMessage("auth.identity.role.clerk", null, code, locale);
            case "PICKER" -> messages.getMessage("auth.identity.role.picker", null, code, locale);
            case "COURIER" -> messages.getMessage("auth.identity.role.courier", null, code, locale);
            case "CS" -> messages.getMessage("auth.identity.role.cs", null, code, locale);
            default -> code;
        };
    }

    private UsrIdentity credential(String type, String value) {
        return credentials.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getIdentityType, type).eq(UsrIdentity::getIdentityValue, value).last("LIMIT 1"));
    }

    private String phone(String userNo) {
        if (userNo == null) return null;
        var row = credentials.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getUserNo, userNo).eq(UsrIdentity::getIdentityType, IdentityType.PHONE).last("LIMIT 1"));
        return row == null ? null : row.getIdentityValue();
    }

    private AuthService.VerifiedPrincipal effective(AuthService.VerifiedPrincipal p) {
        if ("ACCOUNT".equals(p.type())) {
            requireUser(p.userNo());
            return p;
        }
        var row = credential(p.type(), p.value());
        if (p.userNo() != null && (row == null || !p.userNo().equals(row.getUserNo())))
            throw BizException.of(ErrorCode.TOKEN_EXPIRED);
        String no = row == null ? null : row.getUserNo();
        if (no != null) requireUser(no);
        return new AuthService.VerifiedPrincipal(p.type(), p.value(), no);
    }

    private UsrAccount requireUser(String no) {
        var user = users.selectOne(Wrappers.<UsrAccount>lambdaQuery().eq(UsrAccount::getUserNo, no).last("LIMIT 1 FOR UPDATE"));
        if (user == null) throw BizException.of(ErrorCode.TOKEN_EXPIRED);
        if ("BANNED".equals(user.getStatus())) throw BizException.of(ErrorCode.RISK_BLOCKED);
        return user;
    }

    private Identity owner(List<MchAccount> memberships, Locale locale) {
        var entityNos = memberships.stream().map(MchAccount::getEntityNo).distinct().toList();
        String first = entityNos.isEmpty() ? "" : entityNos.getFirst();
        MchEntity entity = first.isEmpty() ? null : entities.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, first).last("LIMIT 1"));
        int count = entityNos.isEmpty() ? 0 : Math.toIntExact(stores.selectCount(Wrappers.<MchStore>lambdaQuery()
                .in(MchStore::getEntityNo, entityNos)));
        return new Identity("OWNER", "OWNER", first, entityNos.size() == 1 && entity != null ? entity.getName() : null,
                entityNos.size(), label("OWNER", locale), List.of("OWNER"), count);
    }

    private Identity staff(MchAccount row, Locale locale) {
        var entity = entities.selectOne(Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getEntityNo, row.getEntityNo()).last("LIMIT 1"));
        var grants = roles.selectList(Wrappers.<MchStoreRole>lambdaQuery().eq(MchStoreRole::getMchAccountNo, row.getMchAccountNo()));
        var codes = grants.stream().map(MchStoreRole::getRole).distinct().sorted().toList();
        return new Identity("STAFF-" + row.getMchAccountNo(), "STAFF", row.getEntityNo(),
                entity == null ? null : entity.getName(), 1,
                String.join("、", codes.stream().map(c -> label(c, locale)).toList()), codes,
                (int) grants.stream().map(MchStoreRole::getStoreNo).distinct().count());
    }

    private List<MchAccount> memberships(String no) {
        return memberships(no, false);
    }

    private List<MchAccount> memberships(String no, boolean lock) {
        return no == null ? List.of() : accounts.selectList(Wrappers.<MchAccount>lambdaQuery()
                .eq(MchAccount::getUserNo, no).eq(MchAccount::getStatus, MchAccount.ACTIVE)
                .orderByDesc(MchAccount::getIsPrimary).orderByAsc(MchAccount::getId).last(lock ? "FOR UPDATE" : ""));
    }

    private List<Identity> list(AuthService.VerifiedPrincipal principal, Locale locale) {
        return list(principal, locale, false);
    }

    private List<Identity> list(AuthService.VerifiedPrincipal principal, Locale locale, boolean lock) {
        var p = effective(principal);
        var result = new ArrayList<Identity>();
        var owned = memberships(p.userNo(), lock);
        if (!owned.isEmpty()) result.add(owner(owned, locale));
        String verifiedPhone = IdentityType.PHONE.equals(p.type()) ? p.value() : phone(p.userNo());
        if (verifiedPhone != null && !verifiedPhone.isBlank()) {
            var query = Wrappers.<MchAccount>lambdaQuery().eq(MchAccount::getLoginPhone, verifiedPhone)
                    .eq(MchAccount::getStatus, MchAccount.ACTIVE).eq(MchAccount::getIsOwner, false)
                    .orderByDesc(MchAccount::getIsPrimary).orderByAsc(MchAccount::getId).last(lock ? "FOR UPDATE" : "");
            for (var row : accounts.selectList(query)) {
                if (p.userNo() == null || !p.userNo().equals(row.getUserNo())) result.add(staff(row, locale));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public IdentityChoice verifyAndList(String grant, String principal, String credential, Boolean agreed,
                                        String market, Locale locale) {
        try {
            var verified = auth.verify(new AuthService.LoginCommand(grant, principal, credential, null, null, agreed, market));
            return DataScopeContext.executeWithoutScope(() -> issue(verified, agreed, market, null, null, locale));
        } catch (BizException failure) {
            auditor.failed(Realm.MERCHANT, LoginAuditor.maskPrincipal(principal), failure.errorCode().name());
            throw failure;
        }
    }

    private IdentityChoice issue(AuthService.VerifiedPrincipal p, Boolean agreed, String market,
                                 LoginUser origin, String token, Locale locale) {
        var identities = list(p, locale);
        boolean canCreate = identities.stream().noneMatch(i -> i.type().equals("OWNER"));
        var issued = tickets.issue(p, agreed, market, identities.stream().map(Identity::identityNo).toList(), canCreate,
                token, origin == null ? null : origin.userNo(), origin == null ? null : origin.subjectKind().name());
        return new IdentityChoice(issued.ticket(), issued.expiresInSeconds(), identities, canCreate,
                origin == null ? null : current(origin, locale).identityNo());
    }

    @Override
    public IdentityChoice mine(LoginUser user, String token, Locale locale) {
        return DataScopeContext.executeWithoutScope(() -> {
            var live = merchantTokens.getByHash(TokenHash.of(token), user.userNo())
                    .filter(s -> s.user().subjectKind() == user.subjectKind() && s.user().realm() == Realm.MERCHANT)
                    .orElseThrow(() -> BizException.of(ErrorCode.TOKEN_EXPIRED));
            AuthService.VerifiedPrincipal p;
            if (live.user().subjectKind() == SubjectKind.MCH) {
                var row = accounts.selectOne(Wrappers.<MchAccount>lambdaQuery().eq(MchAccount::getMchAccountNo, user.userNo())
                        .eq(MchAccount::getStatus, MchAccount.ACTIVE).last("LIMIT 1 FOR UPDATE"));
                if (row == null || row.getLoginPhone() == null) throw BizException.of(ErrorCode.TOKEN_EXPIRED);
                var c = credential(IdentityType.PHONE, row.getLoginPhone());
                p = new AuthService.VerifiedPrincipal(IdentityType.PHONE, row.getLoginPhone(), c == null ? null : c.getUserNo());
            } else {
                requireUser(user.userNo());
                p = new AuthService.VerifiedPrincipal("ACCOUNT", null, user.userNo());
            }
            return issue(p, true, null, user, token, locale);
        });
    }

    @Override
    public Identity current(LoginUser user, Locale locale) {
        return DataScopeContext.executeWithoutScope(() -> {
            if (user.realm() != Realm.MERCHANT) throw BizException.of(ErrorCode.FORBIDDEN);
            if (user.subjectKind() == SubjectKind.USR) return owner(memberships(user.userNo()), locale);
            var row = accounts.selectOne(Wrappers.<MchAccount>lambdaQuery().eq(MchAccount::getMchAccountNo, user.userNo())
                    .eq(MchAccount::getStatus, MchAccount.ACTIVE).last("LIMIT 1 FOR UPDATE"));
            if (row == null) throw BizException.of(ErrorCode.TOKEN_EXPIRED);
            return staff(row, locale);
        });
    }

    private record Entry<T>(T response, List<Identity> invalid) {}

    @Override
    public <T> T enter(String token, String identityNo, Locale locale, BiFunction<LoginUser, String, T> response) {
        try {
            Entry<T> result = DataScopeContext.executeWithoutScope(() -> tickets.locked(token, ticket -> {
                if (ticket.originHash() != null) {
                    merchantTokens.getByHash(ticket.originHash(), ticket.originPrincipal())
                            .filter(s -> s.user().realm() == Realm.MERCHANT && s.user().subjectKind().name().equals(ticket.originKind()))
                            .orElseThrow(() -> BizException.of(ErrorCode.LOGIN_TICKET_INVALID));
                    if ("MCH".equals(ticket.originKind())) {
                        var origin = accounts.selectOne(Wrappers.<MchAccount>lambdaQuery()
                                .eq(MchAccount::getMchAccountNo, ticket.originPrincipal())
                                .eq(MchAccount::getStatus, MchAccount.ACTIVE).last("LIMIT 1 FOR UPDATE"));
                        if (origin == null || !Objects.equals(origin.getLoginPhone(), ticket.principal().value()))
                            throw BizException.of(ErrorCode.LOGIN_TICKET_INVALID);
                    }
                }
                var identities = list(ticket.principal(), locale, true);
                var allowed = identities.stream().filter(i -> ticket.identities().contains(i.identityNo())).toList();
                boolean newOwner = "NEW_OWNER".equals(identityNo) && ticket.canCreateOwner()
                        && identities.stream().noneMatch(i -> i.type().equals("OWNER"));
                var chosen = allowed.stream().filter(i -> i.identityNo().equals(identityNo)).findFirst();
                if (!newOwner && chosen.isEmpty()) {
                    tickets.narrow(ticket, allowed.stream().map(Identity::identityNo).toList());
                    return new Entry<T>(null, allowed);
                }
                LoginUser user;
                if (newOwner || "OWNER".equals(identityNo)) {
                    var account = auth.enterVerified(effective(ticket.principal()), ticket.agreed(), ticket.marketCode());
                    user = LoginUser.merchantByUser(account.getUserNo(), account.getNickname());
                } else {
                    var no = identityNo.substring("STAFF-".length());
                    var row = accounts.selectOne(Wrappers.<MchAccount>lambdaQuery().eq(MchAccount::getMchAccountNo, no)
                            .eq(MchAccount::getStatus, MchAccount.ACTIVE).last("LIMIT 1 FOR UPDATE"));
                    if (row == null) return new Entry<T>(null, allowed);
                    user = LoginUser.merchant(row.getMchAccountNo(), row.getDisplayName());
                }
                tickets.consume(ticket);
                String session = tokens.issue(TokenStore.SessionData.of(user));
                // DB session writes share the ticket transaction. Evict issued credentials on rollback,
                // including commit failures; the same compensation also covers local cache stores.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        // External/cache stores cannot roll back an immediate deletion.
                        if (ticket.originHash() != null && !(merchantTokens instanceof DbTokenStore))
                            merchantTokens.revokeByHash(ticket.originHash(), ticket.originPrincipal());
                    }
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) tokens.revoke(session);
                    }
                });
                T answer = response.apply(user, session);
                auditor.succeeded(Realm.MERCHANT, user.userNo());
                if (ticket.originHash() != null && merchantTokens instanceof DbTokenStore)
                    merchantTokens.revokeByHash(ticket.originHash(), ticket.originPrincipal());
                return new Entry<T>(answer, null);
            }));
            if (result.invalid() != null) throw new IdentityUnavailable(result.invalid());
            return result.response();
        } catch (BizException failure) {
            auditor.failed(Realm.MERCHANT, "***", failure.errorCode().name());
            throw failure;
        }
    }

}
