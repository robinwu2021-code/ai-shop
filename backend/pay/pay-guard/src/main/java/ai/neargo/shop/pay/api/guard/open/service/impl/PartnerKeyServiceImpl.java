package ai.neargo.shop.pay.api.guard.open.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyRegistry.Generation;
import ai.neargo.secret.api.KeyRegistry.IssuedSecret;
import ai.neargo.secret.api.KeyRegistry.NewKey;
import ai.neargo.secret.api.SecretFormat;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import ai.neargo.shop.pay.api.guard.open.service.PartnerKeyService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;

/** {@link PartnerKeyService} 的实现：只做「哪个接入方、哪一种密钥」，材料的生成与加密保存全在密钥模块 */
@ConditionalOnGuardEntry(PortalFace.OPEN)
@Service
public class PartnerKeyServiceImpl implements PartnerKeyService {

    /** {@code sec_key.owner_type}：与 {@code SecretAppKeyResolver} 取验签密钥用的是同一个归属 */
    static final String OWNER_TYPE = "PARTNER_APP";

    private final PayAppMapper apps;
    /**
     * 延迟取：独立形态的 {@code pay-svc} 扫描整个 {@code ai.neargo.shop.pay}，却不装密钥模块（它只暴露 /internal 与 /callback，
     * 不发密钥）—— 直接注入会让那个进程起不来（{@code PayApplicationBootTest}）。缺了就在调用时失败关闭
     */
    private final ObjectProvider<KeyRegistry> registry;

    public PartnerKeyServiceImpl(PayAppMapper apps, ObjectProvider<KeyRegistry> registry) {
        this.apps = apps;
        this.registry = registry;
    }

    @Override
    public IssuedCredential issueAppSecret(String appNo, String issuedBy) {
        PayApp app = requirePartner(appNo);
        // 前缀按 app 所在环境：沙箱 app 只能打沙箱、生产 app 只能打生产（安全规范 §2.3），两者不会错配
        String prefix = Boolean.TRUE.equals(app.getSandbox()) ? "sk_test_" : "sk_live_";
        return issue(app, KeyPurpose.INBOUND_HMAC, "HMAC_SHA256", "app secret", prefix, "S", issuedBy);
    }

    @Override
    public IssuedCredential issueEncryptKey(String appNo, String issuedBy) {
        return issue(requirePartner(appNo), KeyPurpose.PARTNER_DATA, "AES_256_GCM", "encrypt key", null, "E", issuedBy);
    }

    private IssuedCredential issue(PayApp app, KeyPurpose purpose, String algorithm, String what, String prefix,
                                   String serialPrefix, String issuedBy) {
        KeyRegistry registry = this.registry.getIfAvailable();
        if (registry == null) {
            throw new IllegalStateException("secret module (KeyRegistry) is not wired in this process; keys cannot be issued here");
        }
        KeyOwner owner = new KeyOwner(SecretNamespace.PAY, OWNER_TYPE, app.getId());
        long keyId = registry.register(new NewKey(owner, purpose, app.getAppNo() + " " + what, null, algorithm,
                "SYMMETRIC_SECRET", issuedBy));
        IssuedSecret issued = registry.generate(keyId,
                new Generation(SecretFormat.ALNUM_32, prefix, serialPrefix, issuedBy, "issue " + what + " to " + app.getAppNo()));
        String shown = prefix == null ? "" : prefix;
        String secret = shown + new String(issued.plaintext(), StandardCharsets.US_ASCII);
        Arrays.fill(issued.plaintext(), (byte) 0);
        return new IssuedCredential(issued.externalKeyId(), issued.ref().versionIndex(), secret,
                shown + "…" + issued.fingerprint());
    }

    private PayApp requirePartner(String appNo) {
        PayApp app = appNo == null ? null : DataScopeContext.executeWithoutScope(() -> apps.selectOne(
                Wrappers.<PayApp>lambdaQuery().eq(PayApp::getAppNo, appNo)));
        if (app == null || !"PARTNER".equals(app.getOwnerType()) || !"ACTIVE".equals(app.getStatus())) {
            // 内部系统不走签名密钥（用每调用方服务凭据）；不存在与停用同一个回答，不当作接入方名单的探测器
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return app;
    }
}
