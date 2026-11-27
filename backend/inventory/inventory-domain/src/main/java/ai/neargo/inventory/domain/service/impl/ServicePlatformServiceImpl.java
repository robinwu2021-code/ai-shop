package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvClientSystem;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ClientSystemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import ai.neargo.secret.api.KeyOwner;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@ConditionalOnInventory
@Service
public class ServicePlatformServiceImpl implements ServicePlatformService {

    /** 服务凭证的 scopes 固定值：与存量开放接口的 read / stock:sync 区分开，后者认不了服务凭证。 */
    static final String SERVICE_SCOPE = "service";

    private final OwnerMapper ownerMapper;
    private final ClientSystemMapper systemMapper;
    private final InventoryCredentials credentials;

    public ServicePlatformServiceImpl(OwnerMapper ownerMapper, ClientSystemMapper systemMapper,
                                      InventoryCredentials credentials) {
        this.ownerMapper = ownerMapper;
        this.systemMapper = systemMapper;
        this.credentials = credentials;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String openOwner(String systemCode, String externalType, String externalRef, String name,
                            String operator) {
        if (externalRef == null || externalRef.isBlank() || name == null || name.isBlank()) {
            throw InventoryException.of(InventoryError.BAD_REQUEST);
        }
        InvClientSystem system = systemMapper.selectOne(Wrappers.<InvClientSystem>lambdaQuery()
                .eq(InvClientSystem::getSystemCode, systemCode));
        if (system == null || !InvEnums.MasterStatus.ACTIVE.equals(system.getStatus())) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "system");
        }
        InvOwner owner = new InvOwner();
        owner.setOwnerId(InvKeys.next(InvKeys.OWNER));
        owner.setSystemCode(systemCode);
        owner.setExternalType(externalType == null ? InvEnums.OwnerType.defaultFor(systemCode) : externalType);
        owner.setExternalRef(externalRef);
        owner.setName(name);
        owner.setStatus(InvEnums.MasterStatus.ACTIVE);
        owner.setWriteAuthority(InvEnums.WriteAuthority.SVC);
        owner.setCreatedBy(operator);
        try {
            ownerMapper.insert(owner);
        } catch (DuplicateKeyException e) {
            throw InventoryException.of(InventoryError.CONFLICT, "owner");
        }
        return owner.getOwnerId();
    }

    /** 业主凭证：密钥库所有者 {@code INV_OWNER / inv_owner.id}，scope 绑定 {@code owner=} */
    @Override
    public Issued issueCredential(String ownerId, String name, Set<Capability> capabilities,
                                  LocalDateTime expiresAt, String operator) {
        InvOwner owner = owner(ownerId);
        return issue(InventoryCredentials.keyOf(owner), name,
                new CredentialScope(SERVICE_SCOPE, capabilities, ownerId, null), expiresAt, operator);
    }

    /** 系统凭证：密钥库所有者 {@code INVENTORY_SYSTEM / inv_client_system.id}，scope 绑定 {@code system=} */
    @Override
    public Issued issueSystemCredential(String systemCode, String name, Set<Capability> capabilities,
                                        LocalDateTime expiresAt, String operator) {
        InvClientSystem system = systemCode == null ? null : systemMapper.selectOne(
                Wrappers.<InvClientSystem>lambdaQuery().eq(InvClientSystem::getSystemCode, systemCode));
        if (system == null || !InvEnums.MasterStatus.ACTIVE.equals(system.getStatus())) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "system");
        }
        return issue(InventoryCredentials.keyOf(system), name,
                new CredentialScope(SERVICE_SCOPE, capabilities, null, systemCode), expiresAt, operator);
    }

    @Override
    public InvOwner ownerByRef(String systemCode, String externalType, String externalRef) {
        if (externalRef == null) {
            return null;
        }
        List<InvOwner> found = ownerMapper.selectList(Wrappers.<InvOwner>lambdaQuery()
                .eq(InvOwner::getSystemCode, systemCode)
                .eq(externalType != null, InvOwner::getExternalType, externalType)
                .eq(InvOwner::getExternalRef, externalRef));
        if (found.size() > 1) {
            throw InventoryException.of(InventoryError.CONFLICT, "owner_type_required");
        }
        return found.isEmpty() ? null : found.get(0);
    }

    private Issued issue(KeyOwner keyOwner, String name, CredentialScope scope, LocalDateTime expiresAt,
                         String operator) {
        String key = "SK" + InvKeys.next(InvKeys.CREDENTIAL);
        String secret = credentials.issue(keyOwner, key, name, scope, expiresAt, operator);
        return new Issued(key, key, secret);
    }

    @Override
    public Authenticated authenticate(String appKey, String appSecret) {
        InventoryCredentials.Bound c = credentials.verify(appKey, appSecret)
                .filter(b -> SERVICE_SCOPE.equals(b.scope().scopes()))
                .orElseThrow(() -> InventoryException.of(InventoryError.UNAUTHORIZED));
        return c.owner() != null
                ? new Authenticated(c.owner().getSystemCode(), c.owner().getOwnerId(), c.appKey(),
                        c.scope().capabilities())
                : new Authenticated(c.system().getSystemCode(), null, c.appKey(), c.scope().capabilities());
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void setWriteAuthority(String ownerId, String writeAuthority, String operator) {
        if (!InvEnums.WriteAuthority.EMBEDDED.equals(writeAuthority)
                && !InvEnums.WriteAuthority.SVC.equals(writeAuthority)) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "writeAuthority");
        }
        InvOwner owner = owner(ownerId);
        owner.setWriteAuthority(writeAuthority);
        owner.setUpdatedBy(operator);
        ownerMapper.updateById(owner);
    }

    @Override
    public InvOwner owner(String ownerId) {
        InvOwner owner = ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery().eq(InvOwner::getOwnerId, ownerId));
        if (owner == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "owner");
        }
        return owner;
    }
}
