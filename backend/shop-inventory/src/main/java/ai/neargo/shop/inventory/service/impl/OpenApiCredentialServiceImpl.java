package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.auth.PasswordHasher;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.entity.InvOpenCredential;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OpenCredentialMapper;
import ai.neargo.shop.inventory.service.OpenApiCredentialService;
import ai.neargo.shop.inventory.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 凭证校验实现。 */
@ConditionalOnInventory
@Service
public class OpenApiCredentialServiceImpl implements OpenApiCredentialService {

    private final OpenCredentialMapper credentialMapper;
    private final PasswordHasher hasher;

    public OpenApiCredentialServiceImpl(OpenCredentialMapper credentialMapper, PasswordHasher hasher) {
        this.credentialMapper = credentialMapper;
        this.hasher = hasher;
    }

    @Override
    public String ownerOf(String appKey, String appSecret, String requiredScope) {
        if (appKey == null || appSecret == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        InvOpenCredential c = credentialMapper.selectOne(
                Wrappers.<InvOpenCredential>lambdaQuery().eq(InvOpenCredential::getAppKey, appKey));
        /*
         * 四种失败一个错码：key 不存在 / secret 不对 / 已吊销 / 已过期。
         *
         * 分开报的话，「key 不存在」与「密码错了」两个不同的错就等于一个探测接口 ——
         * 对方可以拿它枚举出哪些 key 是真的。
         */
        boolean ok = c != null
                && "ACTIVE".equals(c.getStatus())
                && (c.getExpiresAt() == null || c.getExpiresAt().isAfter(LocalDateTime.now()))
                && hasher.matches(appSecret, c.getAppSecretHash())
                && scopeAllows(c.getScopes(), requiredScope);
        if (!ok) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        // 「这把钥匙半年没人用了」的唯一依据。**失败不记** —— 否则被暴力试的 key 看着很活跃
        c.setLastUsedAt(LocalDateTime.now());
        credentialMapper.updateById(c);
        return c.getOwnerId();
    }

    private static boolean scopeAllows(String scopes, String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        for (String s : scopes.split(",")) {
            if (s.trim().equals(required)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Issued issue(String ownerId, String name, String scopes, LocalDateTime expiresAt) {
        /*
         * key 与 secret 都用 InvKeys 生成 —— **不用自增也不用可猜的前缀**：
         * 可枚举的 appKey 会把「四种失败一个错码」那条防线变成摆设，
         * 对方不必枚举 key，直接按序号试就行。
         */
        String key = "AK" + InvKeys.next(InvKeys.CREDENTIAL);
        String secret = InvKeys.secret();

        InvOpenCredential row = new InvOpenCredential();
        row.setCredentialId(InvKeys.next(InvKeys.CREDENTIAL));
        row.setOwnerId(ownerId);
        row.setAppKey(key);
        // **只存哈希**。明文只在返回值里出现这一次，库里找不回来
        row.setAppSecretHash(hasher.encode(secret));
        row.setName(name);
        row.setScopes(scopes);
        row.setStatus("ACTIVE");
        row.setExpiresAt(expiresAt);
        credentialMapper.insert(row);

        return new Issued(row.getCredentialId(), key, secret);
    }

    @Override
    public List<Listed> list(String ownerId) {
        return credentialMapper.selectList(Wrappers.<InvOpenCredential>lambdaQuery()
                        .eq(InvOpenCredential::getOwnerId, ownerId)
                        // 新的在前：运营多数时候要看的是刚发的那一把
                        .orderByDesc(InvOpenCredential::getId))
                .stream()
                .map(c -> new Listed(c.getCredentialId(), c.getAppKey(), c.getName(),
                        c.getScopes(), c.getStatus(), c.getExpiresAt(),
                        c.getLastUsedAt(), c.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void revoke(String credentialId) {
        // **不删行**：删了之后「这把钥匙什么时候被谁停的」没人答得上来
        credentialMapper.update(null, Wrappers.<InvOpenCredential>lambdaUpdate()
                .eq(InvOpenCredential::getCredentialId, credentialId)
                .set(InvOpenCredential::getStatus, "REVOKED"));
    }
}