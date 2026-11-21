package ai.neargo.shop.merchantbridge.impl;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.merchant.service.MerchantAccountService;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.merchantbridge.MerchantDeregisterChecks;
import ai.neargo.shop.user.service.AccountCredentialService;
import ai.neargo.shop.user.service.AccountCredentialService.Credentials;
import ai.neargo.shop.user.service.AccountCredentialService.VerifyBy;
import ai.neargo.shop.user.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MerchantAccountServiceImpl implements MerchantAccountService {

    private static final Logger log = LoggerFactory.getLogger(MerchantAccountServiceImpl.class);

    private final AccountCredentialService credentials;
    private final MerchantStaffService staff;
    private final MerchantDeregisterChecks checks;
    private final UserService users;
    private final TokenStores tokens;
    private final MchAccountMapper accounts;

    public MerchantAccountServiceImpl(AccountCredentialService credentials, MerchantStaffService staff,
                                      MerchantDeregisterChecks checks, UserService users, TokenStores tokens,
                                      MchAccountMapper accounts) {
        this.credentials = credentials;
        this.staff = staff;
        this.checks = checks;
        this.users = users;
        this.tokens = tokens;
        this.accounts = accounts;
    }

    @Override
    public Account account(LoginUser user) {
        if (user.subjectKind() == SubjectKind.MCH) {
            // Staff sessions read their own login phone; every write stays with the owner (AC20).
            MchAccount row = DataScopeContext.executeWithoutScope(() -> accounts.selectOne(
                    Wrappers.<MchAccount>lambdaQuery().eq(MchAccount::getMchAccountNo, user.userNo()).last("limit 1")));
            return new Account(row == null ? null : row.getUserNo(), user.userNo(),
                    row == null ? null : row.getLoginPhone(), null, false, false, null, false, false,
                    credentials.smsUnavailablePrefixes());
        }
        return view(credentials.credentials(personal(user)));
    }

    @Override
    @Transactional
    public Account changePhone(LoginUser user, String currentToken, String currentCode, String newPhone,
                               String newCode, String clientIp) {
        String userNo = personal(user);
        String oldPhone = credentials.changePhone(userNo, currentCode, newPhone, newCode, clientIp);
        staff.followLoginPhone(userNo, oldPhone, newPhone);
        revokeOthersAfterCommit(userNo, currentToken);
        return view(credentials.credentials(userNo));
    }

    @Override
    @Transactional
    public Account bindEmail(LoginUser user, String currentToken, String email, String code, String currentCode,
                             String clientIp) {
        String userNo = personal(user);
        credentials.bindEmail(userNo, email, code, currentCode, clientIp);
        revokeOthersAfterCommit(userNo, currentToken);
        return view(credentials.credentials(userNo));
    }

    @Override
    public Precheck precheck(LoginUser user) {
        String userNo = personal(user);
        Credentials c = credentials.credentials(userNo);
        var blockers = checks.blockers(userNo);
        String target = c.verifyTarget();
        String masked = target == null ? null
                : c.verifyBy() == VerifyBy.PHONE ? Masks.phone(target) : Masks.email(target);
        return new Precheck(blockers.isEmpty() && c.verifyBy() != null,
                c.verifyBy() == null ? null : c.verifyBy().name(), target, masked, blockers,
                staff.activeStaffMemberships(userNo, c.phone()));
    }

    @Override
    @Transactional
    public void deregister(LoginUser user, String code, String clientIp) {
        String userNo = personal(user);
        credentials.verifyCurrent(userNo, code);
        // Re-check inside the transaction: an order may have arrived since the page was opened.
        if (!checks.blockers(userNo).isEmpty()) throw BizException.of(ErrorCode.DEREGISTER_BLOCKED);
        credentials.logDeregister(userNo, clientIp);
        users.deregisterAccount(userNo);
    }

    private Account view(Credentials c) {
        boolean phoneChange = c.verifyBy() != null;
        return new Account(c.userNo(), c.userNo(), c.phone(), c.email(), c.hasPassword(), phoneChange,
                c.verifyBy() == null ? null : c.verifyBy().name(), c.emailDeliverable(), true,
                c.smsUnavailablePrefixes());
    }

    private void revokeOthersAfterCommit(String userNo, String keepToken) {
        Runnable revoke = () -> {
            // Single-pool deployments (memory / ehcache / redis) return the same store for both realms,
            // so the consumer pool must keep the current token too.
            try {
                tokens.of(Realm.MERCHANT).revokeUserExcept(userNo, keepToken);
                tokens.of(Realm.CONSUMER).revokeUserExcept(userNo, keepToken);
            } catch (RuntimeException failure) {
                // The credential change is committed; a failed revocation must not report it as failed.
                log.error("[account] revoking other sessions failed user={}", userNo, failure);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    revoke.run();
                }
            });
        } else {
            revoke.run();
        }
    }

    private static String personal(LoginUser user) {
        if (user.realm() != Realm.MERCHANT || user.subjectKind() != SubjectKind.USR) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return user.userNo();
    }
}
