package ai.neargo.shop.pay.secret;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.openapp.AppKeyResolver;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 接入方验签密钥从平台密钥模块取（完成度复核 §3.5「{@code ConfigAppKeyResolver} 换 {@code SecretAppKeyResolver}」）：
 * {@code sec_key(namespace=PAY, owner_type=PARTNER_APP|INTERNAL_APP, owner_id=pay_app.id, INBOUND, HMAC)}，
 * 返回 ACTIVE 与仍在重叠期内的 ROTATING 两把（轮换期新旧签名都认）。
 *
 * <p><b>开关 {@code shop.pay.open.key-source=secret}</b>，默认不开：试点接入方的密钥还在部署配置里，
 * 迁进 {@code sec_key} 并启用之后再切 —— 切之前两边并存，{@code ConfigAppKeyResolver} 不动。
 * 开了之后它以 {@code @Primary} 顶替配置实现。
 *
 * <p>找不到接入方、找不到密钥、密钥不可用（到期 / 暂停）一律返回空表 —— 验签因此失败，与「没配密钥」同一个结果，
 * 不把密钥模块的内部状态透给调用方。
 */
@Primary
@Component
@ConditionalOnProperty(name = "shop.pay.open.key-source", havingValue = "secret")
public class SecretAppKeyResolver implements AppKeyResolver {

    private final PayAppMapper apps;
    private final KeyRegistry keys;

    public SecretAppKeyResolver(PayAppMapper apps, KeyRegistry keys) {
        this.apps = apps;
        this.keys = keys;
    }

    @Override
    public List<String> hmacSecretsOf(String appNo) {
        PayApp app = apps.selectOne(Wrappers.<PayApp>lambdaQuery().eq(PayApp::getAppNo, appNo));
        if (app == null) {
            return List.of();
        }
        KeyOwner owner = new KeyOwner(SecretNamespace.PAY, ownerTypeOf(app), app.getId());
        try {
            return keys.find(owner, KeyPurpose.INBOUND_HMAC)
                    .map(k -> keys.verificationMaterials(k.keyId()).stream()
                            .map(m -> new String(m, StandardCharsets.UTF_8)).toList())
                    .orElse(List.of());
        } catch (SecretException e) {
            return List.of();
        }
    }

    /** {@code pay_app.owner_type}（PARTNER / INTERNAL）→ {@code sec_key.owner_type}（表结构说明 §3.1） */
    static String ownerTypeOf(PayApp app) {
        return "INTERNAL".equals(app.getOwnerType()) ? "INTERNAL_APP" : "PARTNER_APP";
    }
}
