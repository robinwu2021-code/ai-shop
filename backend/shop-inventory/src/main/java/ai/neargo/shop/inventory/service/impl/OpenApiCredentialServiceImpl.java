package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.auth.PasswordHasher;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.entity.InvOpenCredential;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OpenCredentialMapper;
import ai.neargo.shop.inventory.service.OpenApiCredentialService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 凭证校验实现。 */
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
}
