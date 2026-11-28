package ai.neargo.shop.platform.impl;

import ai.neargo.secret.api.CredentialVerifier;
import ai.neargo.secret.api.CredentialVerifier.Credential;
import ai.neargo.secret.api.CredentialVerifier.NewCredential;
import ai.neargo.secret.api.CredentialVerifier.Registration;
import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.observability.OperationLog;
import ai.neargo.shop.platform.OpsApiKeyService;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.auth.OperatorIdentityLoader;
import ai.neargo.shop.platform.auth.OpsApiKeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营接口密钥：凭据经 {@link CredentialVerifier} 存密钥库（只存摘要），所有者是运营账号的数字主键（Q15 / Q16）；
 * 认证时再核账号状态（ACTIVE、未删、不需改密）。对外端点与 JSON 不变。
 */
@Service
public class OpsApiKeyServiceImpl implements OpsApiKeyService {

    private static final Pattern KEY_FORMAT = Pattern.compile(PREFIX + "[A-Za-z0-9_-]{43}");
    private final OpsApiKeyStore keys;
    private final CredentialVerifier credentials;
    private final OperatorIdentityLoader identities;
    private final OpsService ops;

    public OpsApiKeyServiceImpl(OpsApiKeyStore keys, CredentialVerifier credentials,
                                OperatorIdentityLoader identities, OpsService ops) {
        this.keys = keys;
        this.credentials = credentials;
        this.identities = identities;
        this.ops = ops;
    }

    @Override
    // 锁运营账号行（平台库）与它同一事务：停用、改密与发钥匙串行
    @Transactional(transactionManager = ai.neargo.shop.common.config.ModuleDataSources.PLATFORM_TX_MANAGER)
    public Issued issue(String staffNo, UUID keyNo, String label, Access access, int validDays) {
        KeyOwner owner = requireEligibleStaff(staffNo);
        var issued = conflictOnDuplicate(() -> credentials.issue(
                spec(owner, keyNo, label, access, validDays), PREFIX));
        audited(staffNo, keyNo, access);
        return new Issued(metadata(issued.credential()), issued.secret());
    }

    @Override
    // 锁运营账号行（平台库）与它同一事务：停用、改密与发钥匙串行
    @Transactional(transactionManager = ai.neargo.shop.common.config.ModuleDataSources.PLATFORM_TX_MANAGER)
    @OperationLog("ops.api_key.register")
    public Metadata registerOwn(UUID keyNo, String label, Access access, int validDays, String hash) {
        String staffNo = SecurityUtils.currentUserNo();
        KeyOwner owner = requireEligibleStaff(staffNo);
        Registration registration = conflictOnDuplicate(() -> credentials.register(
                spec(owner, keyNo, label, access, validDays), hash));
        if (registration.created()) {
            audited(staffNo, keyNo, access);
        }
        return metadata(registration.credential());
    }

    private static NewCredential spec(KeyOwner owner, UUID keyNo, String label, Access access, int validDays) {
        return new NewCredential(owner, keyNo.toString(), label.strip(), access.name(), Duration.ofDays(validDays),
                SecurityUtils.currentUserNo());
    }

    private KeyOwner requireEligibleStaff(String staffNo) {
        OpsApiKeyStore.Staff staff = keys.lockStaff(staffNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (!staff.eligible()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return staff.owner();
    }

    /** 凭据号已被占用（重复发放、自助登记内容不一致、号被别人用过）对调用方都是冲突 */
    private static <T> T conflictOnDuplicate(Supplier<T> call) {
        try {
            return call.get();
        } catch (SecretException e) {
            if (e.reason() == SecretException.Reason.DUPLICATE) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            throw e;
        }
    }

    private void audited(String staffNo, UUID keyNo, Access access) {
        ops.audit("OPS_API_KEY_ISSUE", staffNo + ":" + keyNo, access.name(), true, null, null);
    }

    @Override
    public List<Metadata> list(String staffNo) {
        return keys.ownerOf(staffNo).map(credentials::list).orElse(List.of()).stream()
                .map(OpsApiKeyServiceImpl::metadata).toList();
    }

    @Override
    // 锁运营账号行（平台库）与它同一事务：停用、改密与发钥匙串行
    @Transactional(transactionManager = ai.neargo.shop.common.config.ModuleDataSources.PLATFORM_TX_MANAGER)
    @OperationLog("ops.api_key.revoke")
    public void revoke(String staffNo, UUID keyNo) {
        boolean revoked = keys.ownerOf(staffNo).map(owner -> credentials.revoke(owner, keyNo.toString(),
                SecurityUtils.currentUserNo(), "revoked by operator")).orElse(false);
        if (revoked) {
            ops.audit("OPS_API_KEY_REVOKE", staffNo + ":" + keyNo, null, true, null, null);
        }
    }

    @Override
    public Optional<Identity> authenticate(String apiKey) {
        if (!KEY_FORMAT.matcher(apiKey).matches()) {
            return Optional.empty();
        }
        return credentials.verify(SecretNamespace.PLATFORM, apiKey)
                .filter(key -> OpsApiKeyStore.OWNER_TYPE.equals(key.owner().ownerType()))
                .flatMap(key -> keys.eligibleStaffNo(key.owner())
                        .flatMap(identities::load)
                        .map(user -> new Identity(key.credentialNo(), Access.valueOf(key.scope()), user)));
    }

    private static Metadata metadata(Credential c) {
        return new Metadata(c.credentialNo(), c.label(), Access.valueOf(c.scope()), c.issuedBy(),
                c.issuedAt().toEpochMilli(), c.expiresAt().toEpochMilli(),
                c.revokedAt() == null ? null : c.revokedAt().toEpochMilli());
    }
}
