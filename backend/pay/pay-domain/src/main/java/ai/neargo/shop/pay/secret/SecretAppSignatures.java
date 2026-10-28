package ai.neargo.shop.pay.secret;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyVault;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.shop.pay.openapp.AppFieldCipher;
import ai.neargo.shop.pay.openapp.AppSignatureVerifier;
import ai.neargo.shop.pay.openapp.AppSigner;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 接入方签名经平台密钥模块（开发计划 M3.1；类清单 §3 {@code SecretAppSignatureVerifier}，取代 {@code SecretAppKeyResolver}）：
 * {@code sec_key(namespace=PAY, owner_type=PARTNER_APP|INTERNAL_APP, owner_id=pay_app.id, INBOUND_HMAC)}，
 * 运算交 {@link KeyVault#mac} —— 材料不离开密钥模块。验签接受 ACTIVE 与重叠期内的 ROTATING；出站只用 ACTIVE。
 *
 * <p>开关 {@code shop.pay.open.key-source=secret}（开发计划 M3.10 切换）；默认是部署配置里的 {@code ConfigAppSignatures}。
 * 找不到接入方的密钥、版本不可用、密钥服务出错一律「验不过 / 签不出」，不把密钥模块的内部状态透给调用方。
 */
@Component
@ConditionalOnProperty(name = "shop.pay.open.key-source", havingValue = "secret")
public class SecretAppSignatures implements AppSignatureVerifier, AppSigner, AppFieldCipher {

    private final KeyRegistry keys;
    private final KeyVault vault;

    public SecretAppSignatures(KeyRegistry keys, KeyVault vault) {
        this.keys = keys;
        this.vault = vault;
    }

    @Override
    public Optional<Verified> verify(PayApp app, String keySerial, String toSign, String signature) {
        byte[] given = decode(signature);
        if (given == null) {
            return Optional.empty();
        }
        try {
            Optional<Long> keyId = keyIdOf(app);
            if (keyId.isEmpty()) {
                return Optional.empty();
            }
            List<KeyVersionRef> candidates = candidates(keyId.get(), keySerial);
            byte[] data = toSign.getBytes(StandardCharsets.UTF_8);
            for (KeyVersionRef ref : candidates) {
                if (MessageDigest.isEqual(vault.mac(ref, data), given)) {
                    return Optional.of(new Verified(ref.versionIndex()));
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Signature> sign(PayApp app, byte[] data) {
        try {
            return keyIdOf(app).map(keys::current)
                    .map(ref -> new Signature(Base64.getEncoder().encodeToString(vault.mac(ref, data)), ref.versionIndex(),
                            keys.serialOf(ref).orElse(null)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** EncryptKey（{@code PARTNER_DATA}）经 {@link KeyVault#decrypt}：带 X-Encrypt-Serial 只用那一版，否则逐把试可用的版本 */
    @Override
    public Optional<byte[]> decrypt(PayApp app, String encryptSerial, byte[] packed, byte[] aad) {
        try {
            Optional<Long> keyId = keyIdOf(app, KeyPurpose.PARTNER_DATA);
            if (keyId.isEmpty()) {
                return Optional.empty();
            }
            for (KeyVersionRef ref : candidates(keyId.get(), encryptSerial)) {
                try {
                    return Optional.of(vault.decrypt(ref, packed, aad));
                } catch (RuntimeException wrongVersionOrTampered) {
                    // 换下一版；都不对就是解不开
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private List<KeyVersionRef> candidates(long keyId, String serial) {
        List<KeyVersionRef> acceptable = keys.verificationVersions(keyId);
        return serial == null || serial.isBlank() ? acceptable
                : keys.findVersion(keyId, serial.trim()).filter(acceptable::contains).map(List::of).orElse(List.of());
    }

    private Optional<Long> keyIdOf(PayApp app) {
        return keyIdOf(app, KeyPurpose.INBOUND_HMAC);
    }

    private Optional<Long> keyIdOf(PayApp app, KeyPurpose purpose) {
        KeyOwner owner = new KeyOwner(SecretNamespace.PAY, ownerTypeOf(app), app.getId());
        return keys.find(owner, purpose).map(KeyRegistry.KeyInfo::keyId);
    }

    /** {@code pay_app.owner_type}（PARTNER / INTERNAL）→ {@code sec_key.owner_type}（表结构说明 §3.1） */
    static String ownerTypeOf(PayApp app) {
        return "INTERNAL".equals(app.getOwnerType()) ? "INTERNAL_APP" : "PARTNER_APP";
    }

    private static byte[] decode(String signature) {
        try {
            return signature == null ? null : Base64.getDecoder().decode(signature.trim());
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }
}
