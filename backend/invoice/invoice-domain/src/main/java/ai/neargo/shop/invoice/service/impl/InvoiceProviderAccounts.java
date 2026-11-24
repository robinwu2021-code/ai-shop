package ai.neargo.shop.invoice.service.impl;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 主体在供应商的账号（三元含义见 {@code InvoiceGateway.ProviderAccount}）。secret 里的 JSON 键沿用首版的 user / password，
 * 已封存的数据不迁移。
 *
 * <p>主体在加值中心的账号：代号在 {@code ivc_regime_profile.provider_account_code}，账号与密码封存进 secret 模块
 * （命名空间 PLATFORM，归属 {@value #OWNER_TYPE} + 配置行 id），业务表只存引用。
 */
@Component
public class InvoiceProviderAccounts {

    static final String OWNER_TYPE = "INVOICE_PROVIDER";

    private final ObjectProvider<SecretVault> vault;
    private final ObjectMapper json;

    public InvoiceProviderAccounts(ObjectProvider<SecretVault> vault, ObjectMapper json) {
        this.vault = vault;
        this.json = json;
    }

    /** 封存账号与密码，返回 {@code provider_secret_ref}。 */
    long seal(long profileId, String user, String password) {
        return seal(OWNER_TYPE, profileId, user, password);
    }

    long sealChannel(long accountId, String user, String password) {
        return seal("INVOICE_CHANNEL_PROVIDER", accountId, user, password);
    }

    private long seal(String ownerType, long ownerId, String user, String password) {
        var body = new LinkedHashMap<String, String>();
        body.put("user", user);
        body.put("password", password);
        return vault().seal(new KeyOwner(SecretNamespace.PLATFORM, ownerType, ownerId), json.writeValueAsBytes(body))
                .blobId();
    }

    /** 代号与账号都配过才算有账号；缺一项回空，由调用方决定是拒绝还是记一次失败。 */
    public Optional<ProviderAccount> of(IvcRegimeProfile profile) {
        if (profile == null || profile.getProviderAccountCode() == null || profile.getProviderSecretRef() == null)
            return Optional.empty();
        var node = json.readTree(vault().open(SecretNamespace.PLATFORM, new SecretRef(profile.getProviderSecretRef())));
        return Optional.of(new ProviderAccount(profile.getProviderAccountCode(), node.path("user").asText(),
                node.path("password").asText()));
    }

    private SecretVault vault() {
        var v = vault.getIfAvailable();
        if (v == null) throw BizException.of(ErrorCode.INVOICE_KEY_UNAVAILABLE);
        return v;
    }
}
