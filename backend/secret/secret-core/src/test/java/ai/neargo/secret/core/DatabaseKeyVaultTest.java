package ai.neargo.secret.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry.Generation;
import ai.neargo.secret.api.KeyRegistry.IssuedSecret;
import ai.neargo.secret.api.KeyRegistry.NewKey;
import ai.neargo.secret.api.KeyRegistry.NewVersion;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretException.Reason;
import ai.neargo.secret.api.SecretFormat;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.StorageTier;
import ai.neargo.secret.store.entity.SecKeyVersion;
import ai.neargo.secret.store.mapper.SecretMappers.KeyVersionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 基于数据库的密钥保存（TDD-支付服务-开放API加解密与密钥分发 §2.8 L2 / L3、类清单 §1–§3）：
 * 平台生成 AppSecret / EncryptKey 落 {@code sec_*}，运算只经 {@link ai.neargo.secret.api.KeyVault}。
 *
 * <p>向量取自 TDD-支付服务-开放API加解密-API清单 §7（Java 与 Python 各算过一遍）。
 */
class DatabaseKeyVaultTest {

    private static final KeyOwner APP_7 = new KeyOwner(SecretNamespace.PAY, "PARTNER_APP", 7);
    private static final KeyOwner PLATFORM = new KeyOwner(SecretNamespace.PAY, "PLATFORM_OPEN", 0);
    private static final String APP_SECRET_CORE = "Zk3Qm9Lp2Xv7Rt5Wb8Nc4Hd6Jf1Gs0Ay";
    private static final String ENCRYPT_KEY = "Pa7Ve2Ky9Wq4Zm6Tn3Lr8Hs5Dc1Fb0Gx";

    private static long register(SecretFixture f, KeyOwner owner, KeyPurpose purpose, String algorithm,
                                 String materialType) {
        return f.registry.register(new NewKey(owner, purpose, "test", null, algorithm, materialType, "ops-alice"));
    }

    private static long appSecretKey(SecretFixture f) {
        return register(f, APP_7, KeyPurpose.INBOUND_HMAC, "HMAC_SHA256", "SYMMETRIC_SECRET");
    }

    private static long encryptKey(SecretFixture f) {
        return register(f, APP_7, KeyPurpose.PARTNER_DATA, "AES_256_GCM", "SYMMETRIC_SECRET");
    }

    /** 录入 → 测试通过 → 第二人启用 */
    private static KeyVersionRef uploadActive(SecretFixture f, long keyId, byte[] material) {
        KeyVersionRef ref = f.registry.addVersion(keyId,
                new NewVersion(material, null, "UPLOADED", null, null, null, "ops-alice"));
        f.registry.markVerified(ref, "PAY:pay_api_request_log:1", "ops-alice");
        f.registry.enable(ref, "ops-bob", null);
        return ref;
    }

    private static SecKeyVersion row(SecretFixture f, KeyVersionRef ref) {
        return f.store.mapper(KeyVersionMapper.class).selectOne(Wrappers.<SecKeyVersion>lambdaQuery()
                .eq(SecKeyVersion::getSecKeyId, ref.keyId()).eq(SecKeyVersion::getVersionIndex, ref.versionIndex()));
    }

    @Test
    @DisplayName("★★★ 生成 AppSecret：32 位字母数字只给一次；库里记显示前缀、KeySerial、存储档 L2，材料不含前缀")
    void generateAppSecretStoresPrefixSerialAndTier() {
        SecretFixture f = SecretFixture.withPayKey();
        long keyId = appSecretKey(f);

        IssuedSecret first = f.registry.generate(keyId,
                new Generation(SecretFormat.ALNUM_32, "sk_test_", "S", "dev-alice", "first issue"));
        IssuedSecret second = f.registry.generate(keyId,
                new Generation(SecretFormat.ALNUM_32, "sk_test_", "S", "dev-alice", "reset"));

        String plain = new String(first.plaintext(), StandardCharsets.US_ASCII);
        assertThat(plain).matches("[A-Za-z0-9]{32}");
        assertThat(first.toString()).doesNotContain(plain);

        SecKeyVersion v1 = row(f, first.ref());
        assertThat(v1.getStorageTier()).isEqualTo("L2");
        assertThat(v1.getDisplayPrefix()).isEqualTo("sk_test_");
        assertThat(v1.getSource()).isEqualTo("GENERATED");
        assertThat(v1.getExternalKeyId()).as("S + 日期 + 两位版本号").isEqualTo("S2026102601");
        assertThat(row(f, second.ref()).getExternalKeyId()).isEqualTo("S2026102602");

        assertThat(f.registry.findVersion(keyId, "S2026102602")).contains(second.ref());
        assertThat(f.registry.findVersion(keyId, "S2026102699")).isEmpty();
        assertThat(f.keyVault.tierOf(first.ref())).isEqualTo(StorageTier.L2);
    }

    @Test
    @DisplayName("★★★ mac：库里的 AppSecret 算出的签名与对外向量 P1 逐字一致")
    void macMatchesPublishedVector() {
        SecretFixture f = SecretFixture.withPayKey();
        KeyVersionRef ref = uploadActive(f, appSecretKey(f), APP_SECRET_CORE.getBytes(StandardCharsets.US_ASCII));

        String toSign = "POST\n/open/pay/v1/orders\n\nAPP7Q2K9XW\n1761465600000\n8c1f0e2a5b7d4c3e9f60a1b2c3d4e5f6\n"
                + "a5c9b0a01becc5121d926a7d702ff9e6d0d335367a53b9787e6e481d37bd9a35";

        byte[] mac = f.keyVault.mac(ref, toSign.getBytes(StandardCharsets.UTF_8));
        assertThat(Base64.getEncoder().encodeToString(mac)).isEqualTo("nsNjeNX+z0xMF/hIgsbFLBkbDWOFBAIYwMnKD+fQVxU=");
    }

    @Test
    @DisplayName("★★★ EncryptKey：解开对外向量 P3；加密往返、每次密文不同；AAD 不符即失败")
    void encryptKeyDecryptsVectorAndRoundTrips() {
        SecretFixture f = SecretFixture.withPayKey();
        KeyVersionRef ref = uploadActive(f, encryptKey(f), ENCRYPT_KEY.getBytes(StandardCharsets.US_ASCII));
        byte[] aad = "APP7Q2K9XW|refundAccount.accountNo".getBytes(StandardCharsets.UTF_8);

        byte[] vector = Base64.getDecoder().decode("obLD1OX2BxgpOktc59/SAi1E7uW27KsqhePcgjLC58SwEbcblEo=");
        assertThat(new String(f.keyVault.decrypt(ref, vector, aad), StandardCharsets.UTF_8)).isEqualTo("8123004567");

        byte[] a = f.keyVault.encrypt(ref, "hello".getBytes(StandardCharsets.UTF_8), aad);
        byte[] b = f.keyVault.encrypt(ref, "hello".getBytes(StandardCharsets.UTF_8), aad);
        assertThat(a).isNotEqualTo(b);
        assertThat(new String(f.keyVault.decrypt(ref, a, aad), StandardCharsets.UTF_8)).isEqualTo("hello");

        byte[] otherAad = "APP7Q2K9XW|refundAccount.accountName".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> f.keyVault.decrypt(ref, a, otherAad))
                .isInstanceOfSatisfying(SecretException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INTEGRITY));
    }

    @Test
    @DisplayName("★★★ 吊销立即失效：已缓存过材料的版本，吊销后下一次运算就失败")
    void revokedVersionFailsEvenWhenCached() {
        SecretFixture f = SecretFixture.withPayKey();
        KeyVersionRef ref = uploadActive(f, appSecretKey(f), APP_SECRET_CORE.getBytes(StandardCharsets.US_ASCII));
        f.keyVault.mac(ref, "warm the cache".getBytes(StandardCharsets.UTF_8));

        f.registry.revoke(ref, "ops-carol", "leak drill");

        assertThatThrownBy(() -> f.keyVault.mac(ref, "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(SecretException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.VERSION_UNUSABLE));
    }

    @Test
    @DisplayName("★★ 用途不符拒绝：HMAC 密钥不能加解密，加密密钥不能算 mac；DRAFT 版本不能用")
    void wrongUsageAndDraftRejected() {
        SecretFixture f = SecretFixture.withPayKey();
        KeyVersionRef hmac = uploadActive(f, appSecretKey(f), APP_SECRET_CORE.getBytes(StandardCharsets.US_ASCII));
        KeyVersionRef enc = uploadActive(f, encryptKey(f), ENCRYPT_KEY.getBytes(StandardCharsets.US_ASCII));
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> f.keyVault.encrypt(hmac, data, data))
                .isInstanceOfSatisfying(SecretException.class, e -> assertThat(e.reason()).isEqualTo(Reason.WRONG_USAGE));
        assertThatThrownBy(() -> f.keyVault.mac(enc, data))
                .isInstanceOfSatisfying(SecretException.class, e -> assertThat(e.reason()).isEqualTo(Reason.WRONG_USAGE));

        IssuedSecret draft = f.registry.generate(appSecretKey(f),
                new Generation(SecretFormat.ALNUM_32, "sk_test_", "S", "dev-alice", "reset"));
        assertThatThrownBy(() -> f.keyVault.mac(draft.ref(), data))
                .isInstanceOfSatisfying(SecretException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.VERSION_UNUSABLE));
    }

    @Test
    @DisplayName("★★ 验签可用的版本：ACTIVE 在前 + 重叠期内的 ROTATING；过了重叠期只剩 ACTIVE")
    void verificationVersionsHonourOverlap() {
        SecretFixture f = SecretFixture.withPayKey();
        long keyId = appSecretKey(f);
        KeyVersionRef v1 = uploadActive(f, keyId, "v1-secret-aaaaaaaaaaaaaaaaaaaaaaaa".getBytes(StandardCharsets.US_ASCII));
        KeyVersionRef v2 = uploadActive(f, keyId, "v2-secret-bbbbbbbbbbbbbbbbbbbbbbbb".getBytes(StandardCharsets.US_ASCII));

        assertThat(f.registry.verificationVersions(keyId)).containsExactly(v2, v1);
        f.clock.advance(Duration.ofDays(8));
        assertThat(f.registry.verificationVersions(keyId)).containsExactly(v2);
    }

    @Test
    @DisplayName("★★ 私钥也能存库：RSA 签名可被公钥验；RSA-OAEP-SHA256 解密，MGF1 用 SHA-1 的密文解不开")
    void rsaPrivateKeysSignAndDecrypt() throws Exception {
        SecretFixture f = SecretFixture.withPayKey();
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair signPair = gen.generateKeyPair();
        KeyPair encPair = gen.generateKeyPair();

        KeyVersionRef sign = uploadActive(f,
                register(f, PLATFORM, KeyPurpose.OUTBOUND_SIGN, "RSA_SHA256", "PRIVATE_KEY"),
                signPair.getPrivate().getEncoded());
        byte[] data = "1761465601000\nnonce\nhash".getBytes(StandardCharsets.UTF_8);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(signPair.getPublic());
        verifier.update(data);
        assertThat(verifier.verify(f.keyVault.sign(sign, data))).isTrue();

        KeyVersionRef dec = uploadActive(f,
                register(f, PLATFORM, KeyPurpose.PARTNER_DATA, "RSA_OAEP_SHA256", "PRIVATE_KEY"),
                encPair.getPrivate().getEncoded());
        Cipher sha256 = Cipher.getInstance("RSA/ECB/OAEPPadding");
        sha256.init(Cipher.ENCRYPT_MODE, encPair.getPublic(), new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] good = sha256.doFinal("8123004567".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(f.keyVault.decrypt(dec, good, null), StandardCharsets.UTF_8)).isEqualTo("8123004567");

        // Java 默认的 OAEPWithSHA-256AndMGF1Padding：MGF1 用 SHA-1 —— 最常见的对接坑，必须解不开
        Cipher mgf1Sha1 = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        mgf1Sha1.init(Cipher.ENCRYPT_MODE, encPair.getPublic());
        byte[] bad = mgf1Sha1.doFinal("8123004567".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> f.keyVault.decrypt(dec, bad, null))
                .isInstanceOfSatisfying(SecretException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INTEGRITY));
    }

    @Test
    @DisplayName("★ 字母数字生成无偏：62 个字符都出现，各自频数在期望 ±10% 内（拒绝采样，不取模）")
    void alnumAlphabetIsUnbiased() {
        // 16 万个字符：期望每个 2581、σ≈51，±10% ≈ 5σ 不会误报；直接取模会让前 8 个字符高出 25%（≈ 12σ），必红
        SecureRandom random = new SecureRandom();
        Map<Character, Integer> counts = new HashMap<>();
        for (int i = 0; i < 5000; i++) {
            for (char c : new String(SecretFormats.generate(SecretFormat.ALNUM_32, random), StandardCharsets.US_ASCII)
                    .toCharArray()) {
                counts.merge(c, 1, Integer::sum);
            }
        }
        double expected = 160_000 / 62.0;
        assertThat(counts).hasSize(62);
        assertThat(counts.values()).allSatisfy(n -> assertThat((double) n).isBetween(expected * 0.9, expected * 1.1));
    }
}
