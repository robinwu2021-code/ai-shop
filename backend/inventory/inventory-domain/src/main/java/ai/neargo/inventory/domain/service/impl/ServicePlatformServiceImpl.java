package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvClientSystem;
import ai.neargo.inventory.domain.entity.InvOpenCredential;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ClientSystemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OpenCredentialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import ai.neargo.inventory.domain.support.SecretHasher;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

@ConditionalOnInventory
@Service
public class ServicePlatformServiceImpl implements ServicePlatformService {

    /** 服务凭证的 scopes 固定值：与存量开放接口的 read / stock:sync 区分开，后者认不了服务凭证。 */
    static final String SERVICE_SCOPE = "service";

    private final OwnerMapper ownerMapper;
    private final ClientSystemMapper systemMapper;
    private final OpenCredentialMapper credentialMapper;
    private final SecretHasher hasher;

    public ServicePlatformServiceImpl(OwnerMapper ownerMapper, ClientSystemMapper systemMapper,
                                      OpenCredentialMapper credentialMapper, SecretHasher hasher) {
        this.ownerMapper = ownerMapper;
        this.systemMapper = systemMapper;
        this.credentialMapper = credentialMapper;
        this.hasher = hasher;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String openOwner(String systemCode, String externalRef, String name, String operator) {
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

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Issued issueCredential(String ownerId, String name, Set<Capability> capabilities,
                                  LocalDateTime expiresAt, String operator) {
        InvOwner owner = owner(ownerId);
        String key = "SK" + InvKeys.next(InvKeys.CREDENTIAL);
        String secret = InvKeys.secret();
        InvOpenCredential row = new InvOpenCredential();
        row.setCredentialId(InvKeys.next(InvKeys.CREDENTIAL));
        row.setOwnerId(ownerId);
        row.setSystemCode(owner.getSystemCode());
        row.setAppKey(key);
        row.setAppSecretHash(hasher.encode(secret));
        row.setName(name);
        row.setScopes(SERVICE_SCOPE);
        row.setCapabilities(capabilities == null || capabilities.isEmpty() ? null
                : capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        row.setStatus(InvEnums.MasterStatus.ACTIVE);
        row.setExpiresAt(expiresAt);
        row.setCreatedBy(operator);
        credentialMapper.insert(row);
        return new Issued(row.getCredentialId(), key, secret);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Authenticated authenticate(String appKey, String appSecret) {
        InvOpenCredential c = appKey == null ? null : credentialMapper.selectOne(
                Wrappers.<InvOpenCredential>lambdaQuery().eq(InvOpenCredential::getAppKey, appKey));
        boolean ok = c != null
                && c.getSystemCode() != null
                && SERVICE_SCOPE.equals(c.getScopes())
                && InvEnums.MasterStatus.ACTIVE.equals(c.getStatus())
                && (c.getExpiresAt() == null || c.getExpiresAt().isAfter(LocalDateTime.now()))
                && hasher.matches(appSecret, c.getAppSecretHash());
        if (!ok) {
            throw InventoryException.of(InventoryError.UNAUTHORIZED);
        }
        c.setLastUsedAt(LocalDateTime.now());
        credentialMapper.updateById(c);
        return new Authenticated(c.getSystemCode(), c.getOwnerId(), c.getCredentialId(), capabilities(c));
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

    private static Set<Capability> capabilities(InvOpenCredential c) {
        if (c.getCapabilities() == null || c.getCapabilities().isBlank()) {
            return Set.of();
        }
        EnumSet<Capability> result = EnumSet.noneOf(Capability.class);
        Arrays.stream(c.getCapabilities().split(",")).map(String::strip).map(Capability::valueOf).forEach(result::add);
        return result;
    }
}
