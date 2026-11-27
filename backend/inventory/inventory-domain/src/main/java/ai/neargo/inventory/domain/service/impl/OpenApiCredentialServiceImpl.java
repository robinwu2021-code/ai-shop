package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.inventory.domain.service.OpenApiCredentialService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import ai.neargo.secret.api.CredentialVerifier.Credential;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * 开放接口凭证：存在密钥库（只存摘要，Q18），{@code inv_open_credential} 停读停写。
 *
 * <p>{@code credentialId} 与 {@code appKey} 是同一个值（密钥库的凭据号）。{@code lastUsedAt} 密钥库不按张记，恒为空。
 */
@ConditionalOnInventory
@Service
public class OpenApiCredentialServiceImpl implements OpenApiCredentialService {

    /** 开放接口没带签发人（运营端与控制台的签发口都没传），记成系统 */
    private static final String OPERATOR = "SYSTEM";
    /** 不给 scopes 时的缺省，与停用前的 {@code inv_open_credential.scopes} 列默认一致 */
    private static final String DEFAULT_SCOPES = "read";

    private final InventoryCredentials credentials;
    private final OwnerMapper ownerMapper;

    public OpenApiCredentialServiceImpl(InventoryCredentials credentials, OwnerMapper ownerMapper) {
        this.credentials = credentials;
        this.ownerMapper = ownerMapper;
    }

    @Override
    public String ownerOf(String appKey, String appSecret, String requiredScope) {
        /*
         * 四种失败一个错码：key 不存在 / secret 不对 / 已吊销 / 已过期。
         *
         * 分开报的话，「key 不存在」与「密码错了」两个不同的错就等于一个探测接口 ——
         * 对方可以拿它枚举出哪些 key 是真的。
         */
        return credentials.verify(appKey, appSecret)
                .filter(c -> c.owner() != null && c.scope().allows(requiredScope))
                .map(c -> c.owner().getOwnerId())
                .orElseThrow(() -> InventoryException.of(InventoryError.UNAUTHORIZED));
    }

    @Override
    public Issued issue(String ownerId, String name, String scopes, LocalDateTime expiresAt) {
        InvOwner owner = ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery().eq(InvOwner::getOwnerId, ownerId));
        if (owner == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "owner");
        }
        /*
         * key 用 InvKeys 生成 —— **不用自增也不用可猜的前缀**：
         * 可枚举的 appKey 会把「四种失败一个错码」那条防线变成摆设。secret 由密钥模块生成。
         */
        String appKey = "AK" + InvKeys.next(InvKeys.CREDENTIAL);
        String secret = credentials.issue(InventoryCredentials.keyOf(owner), appKey, name,
                new CredentialScope(scopes == null ? DEFAULT_SCOPES : scopes, Set.of(), owner.getOwnerId(), null),
                expiresAt, OPERATOR);
        return new Issued(appKey, appKey, secret);
    }

    @Override
    public List<Listed> list(String ownerId) {
        InvOwner owner = ownerMapper.selectOne(Wrappers.<InvOwner>lambdaQuery().eq(InvOwner::getOwnerId, ownerId));
        if (owner == null) {
            return List.of();
        }
        return credentials.list(owner).stream().map(OpenApiCredentialServiceImpl::listed).toList();
    }

    @Override
    public void revoke(String credentialId) {
        // **不删**：密钥库里置吊销，「什么时候停的」仍查得到
        credentials.find(credentialId).ifPresent(c -> credentials.revoke(c, OPERATOR));
    }

    @Override
    public String ownerIdOfCredential(String credentialId) {
        return credentials.find(credentialId)
                .filter(c -> c.owner() != null)
                .map(c -> c.owner().getOwnerId())
                .orElse(null);
    }

    private static Listed listed(Credential c) {
        return new Listed(c.credentialNo(), c.credentialNo(), c.label(), CredentialScope.parse(c.scope()).scopes(),
                c.revokedAt() == null ? InvEnums.CredentialStatus.ACTIVE : InvEnums.CredentialStatus.REVOKED,
                local(c.expiresAt()), null, local(c.issuedAt()));
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
