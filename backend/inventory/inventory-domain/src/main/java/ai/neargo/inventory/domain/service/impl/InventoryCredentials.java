package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvClientSystem;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ClientSystemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.secret.api.CredentialVerifier;
import ai.neargo.secret.api.CredentialVerifier.Credential;
import ai.neargo.secret.api.CredentialVerifier.NewCredential;
import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 库存开放凭证（开放接口凭证、服务凭证、系统凭证）在密钥库里的存取（TDD-基础能力库对齐「1h 开放应用」Q18）。
 *
 * <p>凭证走密钥模块的 {@link CredentialVerifier}：库里只有出示串的 SHA-256，没有可还原的材料。
 * {@code appKey} 是凭据号（{@code sec_key_version.external_key_id}），{@code appSecret} 是出示串；
 * 认的时候按 secret 找到凭证、再核对它的凭据号就是请求里的 appKey。
 * 所有者：业主凭证 {@code KeyOwner(INVENTORY, INV_OWNER, inv_owner.id)}，
 * 系统凭证 {@code KeyOwner(INVENTORY, INVENTORY_SYSTEM, inv_client_system.id)}；权限与绑定写在 scope 里（{@link CredentialScope}）。
 *
 * <p>内嵌（ai-shop）与独立部署都用宿主装好的那个 {@link CredentialVerifier}：前者是 shop-app 的，
 * 后者是 inventory-svc 自己指向同一个密钥库的那一套。
 */
@ConditionalOnInventory
@Component
public class InventoryCredentials {

    /** 出示串的显示前缀：日志或工单里看到就知道是库存凭证 */
    static final String SECRET_PREFIX = "ivk_";
    /** 「不过期」：密钥库要求有期限，给一百年 */
    static final Duration NO_EXPIRY = Duration.ofDays(36_500);

    private final CredentialVerifier verifier;
    private final OwnerMapper ownerMapper;
    private final ClientSystemMapper systemMapper;

    public InventoryCredentials(CredentialVerifier verifier, OwnerMapper ownerMapper, ClientSystemMapper systemMapper) {
        this.verifier = verifier;
        this.ownerMapper = ownerMapper;
        this.systemMapper = systemMapper;
    }

    /** 认出且确属本库的一张凭证：业主凭证 {@code owner} 非空，系统凭证 {@code system} 非空 */
    record Bound(String appKey, KeyOwner keyOwner, InvOwner owner, InvClientSystem system, CredentialScope scope) {
    }

    static KeyOwner keyOf(InvOwner owner) {
        return new KeyOwner(SecretNamespace.INVENTORY, InvEnums.CredentialOwnerType.OWNER, owner.getId());
    }

    static KeyOwner keyOf(InvClientSystem system) {
        return new KeyOwner(SecretNamespace.INVENTORY, InvEnums.CredentialOwnerType.SYSTEM, system.getId());
    }

    /**
     * 签发一张，返回 secret 明文（只此一次）。
     *
     * @param expiresAt 空 = 不过期（{@link #NO_EXPIRY}）
     * @throws InventoryException {@code BAD_REQUEST}：期限已经过去
     */
    String issue(KeyOwner keyOwner, String appKey, String name, CredentialScope scope, LocalDateTime expiresAt,
                 String operator) {
        Duration validity = expiresAt == null ? NO_EXPIRY : Duration.between(LocalDateTime.now(), expiresAt);
        if (!validity.isPositive()) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "expiresAt");
        }
        return verifier.issue(new NewCredential(keyOwner, appKey, name, scope.encode(), validity, operator),
                SECRET_PREFIX).secret();
    }

    /** 不存在、secret 不对、key 与 secret 不配对、已吊销、已过期、不属本库 —— 一律为空，不区分 */
    Optional<Bound> verify(String appKey, String appSecret) {
        if (appKey == null || appSecret == null) {
            return Optional.empty();
        }
        return verifier.verify(SecretNamespace.INVENTORY, appSecret)
                .filter(v -> v.credentialNo().equals(appKey))
                .flatMap(v -> bind(v.owner(), v.credentialNo(), v.scope()));
    }

    /** 按凭据号（= credentialId = appKey）找一张属于本库的，含已吊销 */
    Optional<Bound> find(String credentialNo) {
        if (credentialNo == null) {
            return Optional.empty();
        }
        return Stream.of(InvEnums.CredentialOwnerType.OWNER, InvEnums.CredentialOwnerType.SYSTEM)
                .map(type -> verifier.find(SecretNamespace.INVENTORY, type, credentialNo))
                .flatMap(Optional::stream)
                .findFirst()
                .flatMap(c -> bind(c.owner(), c.credentialNo(), c.scope()));
    }

    /** 一个业主的全部凭证（含吊销），新的在前；只留绑定到本库这一行的 */
    List<Credential> list(InvOwner owner) {
        return verifier.list(keyOf(owner)).stream()
                .filter(c -> owner.getOwnerId().equals(CredentialScope.parse(c.scope()).owner()))
                .toList();
    }

    void revoke(Bound credential, String operator) {
        verifier.revoke(credential.keyOwner(), credential.appKey(), operator, null);
    }

    private Optional<Bound> bind(KeyOwner keyOwner, String appKey, String rawScope) {
        CredentialScope scope = CredentialScope.parse(rawScope);
        if (InvEnums.CredentialOwnerType.OWNER.equals(keyOwner.ownerType())) {
            InvOwner owner = ownerMapper.selectById(keyOwner.ownerId());
            return owner != null && owner.getOwnerId().equals(scope.owner())
                    ? Optional.of(new Bound(appKey, keyOwner, owner, null, scope)) : Optional.empty();
        }
        if (!InvEnums.CredentialOwnerType.SYSTEM.equals(keyOwner.ownerType())) {
            return Optional.empty();
        }
        InvClientSystem system = systemMapper.selectById(keyOwner.ownerId());
        return system != null && system.getSystemCode().equals(scope.system())
                ? Optional.of(new Bound(appKey, keyOwner, null, system, scope)) : Optional.empty();
    }
}
