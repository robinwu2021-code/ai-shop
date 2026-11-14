package ai.neargo.secret.core;

import ai.neargo.secret.api.KeyPurpose.Usage;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyVault;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretException.Reason;
import ai.neargo.secret.api.StorageTier;
import ai.neargo.secret.store.entity.SecKey;
import ai.neargo.secret.store.entity.SecKeyVersion;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link KeyVault} 的数据库实现：L2（库 + 注入主密钥）与 L3（库 + KMS 主密钥）。两档的区别只在
 * {@link MasterKeyProvider}，密文格式一样（类清单 §2 · TDD §3 S4）。
 *
 * <p><b>每次运算都查一次版本状态</b>（走 {@code uk_sec_key_version_1} 索引），吊销对任何节点的下一次运算立即生效；
 * 缓存的只是解开的材料（省一次取密文 + 两次 AES 解包），TTL 60 秒（安全规范 §2.3「不缓存超过 60 秒」）。
 * 材料本身按版本不可变，所以缓存不会读到旧值 —— TTL 只为不让明文在内存里长住。
 */
public class DatabaseKeyVault implements KeyVault {

    static final Duration MATERIAL_TTL = Duration.ofSeconds(60);
    /** 与 {@link KeyRegistry#materialOf} 同一口径：冻结版本可用于退款、查单、验旧签 */
    private static final Set<String> USABLE = Set.of("ACTIVE", "ROTATING", "RETIRED");
    private static final byte[] NO_AAD = new byte[0];

    private final SecretStoreMappers db;
    private final KeyRegistry registry;
    private final SecretSettings settings;
    private final Map<KeyVersionRef, Cached> cache = new ConcurrentHashMap<>();

    public DatabaseKeyVault(SecretStoreMappers db, KeyRegistry registry, SecretSettings settings) {
        this.db = db;
        this.registry = registry;
        this.settings = settings;
    }

    @Override
    public byte[] mac(KeyVersionRef ref, byte[] data) {
        Loaded k = load(ref, Usage.HMAC);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k.material, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed for key " + ref, e);
        }
    }

    @Override
    public byte[] sign(KeyVersionRef ref, byte[] data) {
        Loaded k = load(ref, Usage.SIGN);
        String jca = switch (k.algorithm) {
            case "RSA_SHA256" -> "SHA256withRSA";
            case "ECDSA_P256" -> "SHA256withECDSA";
            default -> throw new SecretException(Reason.WRONG_USAGE, "key " + ref + " algorithm " + k.algorithm
                    + " cannot sign");
        };
        try {
            Signature s = Signature.getInstance(jca);
            s.initSign(privateKey(k, jca.endsWith("RSA") ? "RSA" : "EC"));
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("sign failed for key " + ref, e);
        }
    }

    @Override
    public byte[] encrypt(KeyVersionRef ref, byte[] plaintext, byte[] aad) {
        Loaded k = load(ref, Usage.ENCRYPT);
        requireAesKey(ref, k);
        return EnvelopeCipher.encrypt(EnvelopeCipher.dataKey(k.material), plaintext, aad == null ? NO_AAD : aad);
    }

    @Override
    public byte[] decrypt(KeyVersionRef ref, byte[] ciphertext, byte[] aad) {
        Loaded k = load(ref, Usage.ENCRYPT);
        if ("RSA_OAEP_SHA256".equals(k.algorithm)) {
            if (aad != null && aad.length > 0) {
                throw new IllegalArgumentException("RSA-OAEP takes no associated data");
            }
            return rsaOaepDecrypt(ref, k, ciphertext);
        }
        requireAesKey(ref, k);
        return EnvelopeCipher.decrypt(EnvelopeCipher.dataKey(k.material), ciphertext, aad == null ? NO_AAD : aad);
    }

    @Override
    public StorageTier tierOf(KeyVersionRef ref) {
        return StorageTier.valueOf(version(ref).getStorageTier());
    }

    /** 加载失败、状态不对、用途不对都在这里拦；缓存命中也照样先查状态 */
    private Loaded load(KeyVersionRef ref, Usage usage) {
        SecKey key = db.keys().selectById(ref.keyId());
        if (key == null || !key.getEnv().equals(settings.requireEnv())) {
            throw new SecretException(Reason.NOT_FOUND, "key " + ref.keyId());
        }
        if (!usage.name().equals(key.getKeyUsage())) {
            throw new SecretException(Reason.WRONG_USAGE, "key " + ref.keyId() + " is for " + key.getKeyUsage()
                    + ", not " + usage);
        }
        SecKeyVersion v = version(ref);
        if (!USABLE.contains(v.getStatus())) {
            cache.remove(ref);
            throw new SecretException(Reason.VERSION_UNUSABLE, "version " + ref + " is " + v.getStatus());
        }
        Instant now = settings.clock().instant();
        Cached c = cache.get(ref);
        if (c == null || c.loadedAt.plus(MATERIAL_TTL).isBefore(now)) {
            // 旧条目不清零：另一个线程可能正拿着它算 HMAC，清零会让那次运算静默算错
            c = new Cached(registry.materialOf(ref), now);
            cache.put(ref, c);
        }
        return new Loaded(key.getAlgorithm(), c.material);
    }

    private SecKeyVersion version(KeyVersionRef ref) {
        SecKeyVersion v = db.versions().selectOne(Wrappers.<SecKeyVersion>lambdaQuery()
                .eq(SecKeyVersion::getSecKeyId, ref.keyId()).eq(SecKeyVersion::getVersionIndex, ref.versionIndex()));
        if (v == null) {
            throw new SecretException(Reason.NOT_FOUND, "version " + ref);
        }
        return v;
    }

    private static void requireAesKey(KeyVersionRef ref, Loaded k) {
        if (!"AES_256_GCM".equals(k.algorithm) || k.material.length != 32) {
            throw new SecretException(Reason.WRONG_USAGE, "key " + ref + " is not a 32-byte AES-256-GCM key");
        }
    }

    private static byte[] rsaOaepDecrypt(KeyVersionRef ref, Loaded k, byte[] ciphertext) {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
            // 显式 MGF1-SHA256：Java 的 "OAEPWithSHA-256AndMGF1Padding" 默认 MGF1 用 SHA-1，两边各自自测都过、一联调就解不开
            c.init(Cipher.DECRYPT_MODE, privateKey(k, "RSA"), new OAEPParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            return c.doFinal(ciphertext);
        } catch (BadPaddingException e) {
            throw new SecretException(Reason.INTEGRITY, "RSA-OAEP decrypt failed for key " + ref);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA-OAEP decrypt failed for key " + ref, e);
        }
    }

    /** 私钥材料：PKCS#8 DER，或其 PEM 文本 */
    private static PrivateKey privateKey(Loaded k, String family) throws GeneralSecurityException {
        byte[] der = k.material;
        String text = new String(der, StandardCharsets.US_ASCII);
        if (text.startsWith("-----BEGIN")) {
            der = Base64.getMimeDecoder().decode(text.replaceAll("-----[A-Z ]+-----", ""));
        }
        return KeyFactory.getInstance(family).generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private record Cached(byte[] material, Instant loadedAt) {
    }

    private record Loaded(String algorithm, byte[] material) {
    }
}
