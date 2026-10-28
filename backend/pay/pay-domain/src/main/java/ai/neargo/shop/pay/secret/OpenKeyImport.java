package ai.neargo.shop.pay.secret;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyRegistry.NewKey;
import ai.neargo.secret.api.KeyRegistry.NewVersion;
import ai.neargo.secret.api.KeyRegistry.VersionInfo;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 试点接入方密钥从部署配置迁进平台密钥模块（开发计划 M3.10；运维手册见
 * {@code docs/technical/支付服务-开放API密钥迁入密钥模块-运维手册.md}）：
 * <ol>
 *   <li>{@link #upload}：读 {@code shop.pay.open.apps.{appNo}.hmac-secret}（去掉 {@code sk_live_ / sk_test_} 前缀）与
 *       {@code .encrypt-key}，登记成新版本并标「已验证」—— 按指纹判重，重复跑不加版本；</li>
 *   <li>{@link #enable}：<b>另一个人</b>启用（两人规则由密钥模块强制：上传人自己启用被拒）；</li>
 *   <li>{@link #status}：列版本、状态、指纹，不出现任何材料。</li>
 * </ol>
 * 接入方手里的那串不变 —— 切 {@code key-source=secret} 之后照样能用；之后的轮换走正常发放（PartnerKeyService）。
 */
@Component
public class OpenKeyImport {

    private record Item(String label, KeyPurpose purpose, String algorithm, String configSuffix, boolean stripPrefix) {
    }

    private static final List<Item> ITEMS = List.of(
            new Item("AppSecret", KeyPurpose.INBOUND_HMAC, "HMAC_SHA256", "hmac-secret", true),
            new Item("EncryptKey", KeyPurpose.PARTNER_DATA, "AES_256_GCM", "encrypt-key", false));

    private final PayAppMapper apps;
    private final ObjectProvider<KeyRegistry> registry;
    private final Environment env;

    public OpenKeyImport(PayAppMapper apps, ObjectProvider<KeyRegistry> registry, Environment env) {
        this.apps = apps;
        this.registry = registry;
        this.env = env;
    }

    public List<String> upload(List<String> appNos, String operator) {
        requireOperator(operator);
        return each(appNos, (app, item, keys, out) -> {
            String configured = env.getProperty("shop.pay.open.apps." + app.getAppNo() + "." + item.configSuffix());
            if (configured == null || configured.isBlank()) {
                out.add(line(app, item, "not in deployment config, skipped"));
                return;
            }
            String value = item.stripPrefix() ? stripPrefix(configured.trim()) : configured.trim();
            byte[] material = value.getBytes(StandardCharsets.UTF_8);
            try {
                long keyId = keyId(keys, app, item).orElseGet(() -> keys.register(new NewKey(owner(app), item.purpose(),
                        app.getAppNo() + " " + item.label(), null, item.algorithm(), "SYMMETRIC_SECRET", operator)));
                String fingerprint = keys.fingerprintOfMaterial(material);
                Optional<VersionInfo> same = keys.versions(keyId).stream()
                        .filter(v -> !"REVOKED".equals(v.status()) && fingerprint.equals(v.fingerprint())).findFirst();
                if (same.isPresent()) {
                    out.add(line(app, item, "already imported as v" + same.get().ref().versionIndex() + " ("
                            + same.get().status() + ")"));
                    return;
                }
                KeyVersionRef ref = keys.addVersion(keyId, new NewVersion(material, null, "UPLOADED", null, null, null,
                        operator));
                keys.markVerified(ref, "imported from deployment config by " + operator, operator);
                out.add(line(app, item, "v" + ref.versionIndex() + " VERIFIED fingerprint " + fingerprint
                        + " - enable it as a different operator"));
            } finally {
                Arrays.fill(material, (byte) 0);
            }
        });
    }

    public List<String> enable(List<String> appNos, String operator) {
        requireOperator(operator);
        return each(appNos, (app, item, keys, out) -> {
            Optional<Long> keyId = keyId(keys, app, item);
            List<VersionInfo> pending = keyId.map(keys::versions).orElse(List.of()).stream()
                    .filter(v -> "VERIFIED".equals(v.status())).toList();
            if (pending.isEmpty()) {
                out.add(line(app, item, "nothing to enable"));
                return;
            }
            for (VersionInfo v : pending) {
                try {
                    keys.enable(v.ref(), operator, "pay-open-key-import");
                    out.add(line(app, item, "v" + v.ref().versionIndex() + " ACTIVE (enabled by " + operator + ")"));
                } catch (SecretException e) {
                    out.add(line(app, item, "v" + v.ref().versionIndex() + " refused: "
                            + (e.reason() == SecretException.Reason.DUAL_CONTROL
                            ? "the uploader cannot enable it (two-person rule)" : e.reason().name())));
                }
            }
        });
    }

    public List<String> status(List<String> appNos) {
        return each(appNos, (app, item, keys, out) -> {
            Optional<Long> keyId = keyId(keys, app, item);
            if (keyId.isEmpty()) {
                out.add(line(app, item, "no key in the secret module"));
                return;
            }
            for (VersionInfo v : keys.versions(keyId.get())) {
                out.add(line(app, item, "v" + v.ref().versionIndex() + " " + v.status() + " entered by " + v.enteredBy()
                        + " fingerprint " + v.fingerprint() + (v.externalKeyId() == null ? "" : " serial " + v.externalKeyId())));
            }
        });
    }

    private interface Step {
        void run(PayApp app, Item item, KeyRegistry keys, List<String> out);
    }

    private List<String> each(List<String> appNos, Step step) {
        KeyRegistry keys = registry.getIfAvailable();
        if (keys == null) {
            return List.of("secret module (KeyRegistry) is not wired in this process; nothing done");
        }
        List<String> out = new ArrayList<>();
        for (String appNo : appNos) {
            PayApp app = DataScopeContext.executeWithoutScope(() -> apps.selectOne(
                    Wrappers.<PayApp>lambdaQuery().eq(PayApp::getAppNo, appNo.trim())));
            if (app == null || !"PARTNER".equals(app.getOwnerType())) {
                out.add(appNo + ": not a partner app, skipped");
                continue;
            }
            for (Item item : ITEMS) {
                try {
                    step.run(app, item, keys, out);
                } catch (RuntimeException e) {
                    out.add(line(app, item, "failed: " + e.getClass().getSimpleName()));
                }
            }
        }
        return out;
    }

    private static Optional<Long> keyId(KeyRegistry keys, PayApp app, Item item) {
        return keys.find(owner(app), item.purpose()).map(KeyRegistry.KeyInfo::keyId);
    }

    private static KeyOwner owner(PayApp app) {
        return new KeyOwner(SecretNamespace.PAY, SecretAppSignatures.ownerTypeOf(app), app.getId());
    }

    private static String line(PayApp app, Item item, String what) {
        return app.getAppNo() + " " + item.label() + ": " + what;
    }

    private static String stripPrefix(String secret) {
        for (String p : List.of("sk_live_", "sk_test_")) {
            if (secret.startsWith(p)) {
                return secret.substring(p.length());
            }
        }
        return secret;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator is required (who did this goes into the key audit trail)");
        }
    }
}
