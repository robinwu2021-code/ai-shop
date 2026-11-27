package ai.neargo.secret.core;

import ai.neargo.secret.api.CredentialVerifier;
import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretException.Reason;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.StorageTier;
import ai.neargo.secret.store.entity.SecAccessLog;
import ai.neargo.secret.store.entity.SecKey;
import ai.neargo.secret.store.entity.SecKeyEvent;
import ai.neargo.secret.store.entity.SecKeyVersion;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link CredentialVerifier} 的实现：每个所有者一把 {@code sec_key}（用途 {@link KeyPurpose#INBOUND_API_KEY}），
 * 每张凭据一个 {@link StorageTier#DIGEST} 版本 —— 只有摘要，没有密文。
 *
 * <p>版本状态只用两个：ACTIVE（发放即生效，API Key 没有「测试调用」可验）与 REVOKED；到期看 {@code valid_to}。
 * 唯一性靠库：摘要 {@code uk_sec_key_version_3}、凭据号 {@code uk_sec_key_version_4}（{@code credential_key}）。
 */
public class DigestCredentialVerifier implements CredentialVerifier {

    static final String ALGORITHM = "SHA256_DIGEST";
    static final String MATERIAL_TYPE = "DIGEST";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretStoreMappers db;
    private final SecretSettings settings;
    private final TransactionTemplate tx;

    public DigestCredentialVerifier(SecretStoreMappers db, SecretSettings settings,
                                    PlatformTransactionManager txManager) {
        this.db = db;
        this.settings = settings;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public Issued issue(NewCredential spec, String prefix) {
        String secret = prefix + new String(SecretFormats.base64Url(32, RANDOM), StandardCharsets.US_ASCII);
        return tx.execute(s -> {
            SecKeyVersion row = insert(spec, digestOf(secret), prefix);
            SecAccessLog log = new SecAccessLog();
            log.setAccessType("REVEAL_ON_ISSUE");
            log.setNamespace(spec.owner().namespace().name());
            log.setSecKeyId(row.getSecKeyId());
            log.setAccessorNo(spec.issuedBy());
            log.setAccessorRole("sec:credential:issue");
            log.setReason("issue credential " + spec.credentialNo());
            log.setAccessedAt(settings.now());
            db.accessLogs().insert(log);
            return new Issued(toCredential(spec.owner(), row), secret);
        });
    }

    @Override
    public Registration register(NewCredential spec, String digest) {
        return tx.execute(s -> {
            SecKeyVersion existing = byCredentialKey(spec.owner(), spec.credentialNo());
            if (existing == null) {
                return new Registration(toCredential(spec.owner(), insert(spec, digest, null)), true);
            }
            SecKey key = db.keys().selectById(existing.getSecKeyId());
            boolean same = ownerOf(key).equals(spec.owner())
                    && digest.equals(existing.getMaterialDigest())
                    && Objects.equals(spec.label(), existing.getLabel())
                    && Objects.equals(spec.scope(), existing.getScope())
                    && spec.validity().equals(Duration.between(existing.getValidFrom(), existing.getValidTo()));
            if (!same) {
                throw duplicate(spec);
            }
            return new Registration(toCredential(spec.owner(), existing), false);
        });
    }

    @Override
    public Optional<Verified> verify(SecretNamespace namespace, String presented) {
        SecKeyVersion v = db.versions().selectOne(Wrappers.<SecKeyVersion>lambdaQuery()
                .eq(SecKeyVersion::getMaterialDigest, digestOf(presented))
                .eq(SecKeyVersion::getStatus, "ACTIVE"));
        if (v == null || !v.getValidTo().isAfter(settings.now())) {
            return Optional.empty();
        }
        SecKey key = db.keys().selectById(v.getSecKeyId());
        if (!namespace.name().equals(key.getNamespace()) || !settings.requireEnv().equals(key.getEnv())
                || !"ACTIVE".equals(key.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(new Verified(ownerOf(key), v.getExternalKeyId(), v.getScope()));
    }

    @Override
    public List<Credential> list(KeyOwner owner) {
        SecKey key = keyOf(owner);
        if (key == null) {
            return List.of();
        }
        return db.versions().selectList(Wrappers.<SecKeyVersion>lambdaQuery()
                        .eq(SecKeyVersion::getSecKeyId, key.getId())
                        .orderByDesc(SecKeyVersion::getValidFrom).orderByAsc(SecKeyVersion::getExternalKeyId))
                .stream().map(v -> toCredential(owner, v)).toList();
    }

    @Override
    public boolean revoke(KeyOwner owner, String credentialNo, String operator, String reason) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            SecKeyVersion v = byCredentialKey(owner, credentialNo);
            if (v == null || !ownerOf(db.keys().selectById(v.getSecKeyId())).equals(owner)) {
                return false;
            }
            return revokeRow(v, operator, reason);
        }));
    }

    @Override
    public int revokeAll(KeyOwner owner, String operator, String reason) {
        SecKey key = keyOf(owner);
        if (key == null) {
            return 0;
        }
        return Objects.requireNonNull(tx.execute(s -> (int) db.versions().selectList(Wrappers.<SecKeyVersion>lambdaQuery()
                        .eq(SecKeyVersion::getSecKeyId, key.getId()).eq(SecKeyVersion::getStatus, "ACTIVE"))
                .stream().filter(v -> revokeRow(v, operator, reason)).count()));
    }

    /** 出示串（含显示前缀）UTF-8 的 SHA-256 小写十六进制：与令牌存储同一口径 */
    static String digestOf(String presented) {
        return EnvelopeCipher.sha256Hex(presented.getBytes(StandardCharsets.UTF_8));
    }

    private boolean revokeRow(SecKeyVersion v, String operator, String reason) {
        int n = db.versions().update(null, Wrappers.<SecKeyVersion>lambdaUpdate()
                .set(SecKeyVersion::getStatus, "REVOKED")
                .set(SecKeyVersion::getRevokedBy, operator)
                .set(SecKeyVersion::getRevokedAt, settings.now())
                .set(SecKeyVersion::getRevokeReason, reason)
                .eq(SecKeyVersion::getId, v.getId()).eq(SecKeyVersion::getStatus, "ACTIVE"));
        if (n == 1) {
            event(v.getSecKeyId(), v.getId(), "REVOKED", operator, reason);
        }
        return n == 1;
    }

    private SecKeyVersion insert(NewCredential spec, String digest, String prefix) {
        SecKey key = ensureKey(spec);
        LocalDateTime now = settings.now().truncatedTo(ChronoUnit.MILLIS);
        Integer max = db.versions().selectList(Wrappers.<SecKeyVersion>lambdaQuery()
                        .select(SecKeyVersion::getVersionIndex).eq(SecKeyVersion::getSecKeyId, key.getId()))
                .stream().map(SecKeyVersion::getVersionIndex).max(Integer::compare).orElse(0);
        SecKeyVersion row = new SecKeyVersion();
        row.setSecKeyId(key.getId());
        row.setVersionIndex(max + 1);
        row.setStatus("ACTIVE");
        row.setFingerprint(EnvelopeCipher.sha256Hex(digest.getBytes(StandardCharsets.US_ASCII)).substring(0, 32));
        row.setExternalKeyId(spec.credentialNo());
        row.setCredentialKey(credentialKey(spec.owner(), spec.credentialNo()));
        row.setMaterialDigest(digest);
        row.setLabel(spec.label());
        row.setScope(spec.scope());
        row.setStorageTier(StorageTier.DIGEST.name());
        row.setDisplayPrefix(prefix);
        row.setSource(prefix == null ? "UPLOADED" : "GENERATED");
        row.setValidFrom(now);
        row.setValidTo(now.plus(spec.validity()));
        row.setEnteredBy(spec.issuedBy());
        row.setEnabledAt(now);
        try {
            db.versions().insert(row);
        } catch (DuplicateKeyException e) {
            // 凭据号或摘要撞了唯一键：并发的两次登记、或别人已用过这个号 —— 对调用方都是同一个冲突
            throw duplicate(spec);
        }
        event(key.getId(), row.getId(), "MATERIAL_ADDED", spec.issuedBy(), row.getSource());
        return row;
    }

    /** 所有者的那把密钥：没有就建，建出来即 ACTIVE（凭据本身各自有状态，密钥只是归属） */
    private SecKey ensureKey(NewCredential spec) {
        SecKey existing = keyOf(spec.owner());
        if (existing != null) {
            return existing;
        }
        KeyOwner owner = spec.owner();
        SecKey key = new SecKey();
        key.setNamespace(owner.namespace().name());
        key.setName(owner.ownerType() + " " + owner.ownerId() + " API keys");
        key.setOwnerType(owner.ownerType());
        key.setOwnerId(owner.ownerId());
        key.setDirection(KeyPurpose.INBOUND_API_KEY.direction().name());
        key.setKeyUsage(KeyPurpose.INBOUND_API_KEY.usage().name());
        key.setAlgorithm(ALGORITHM);
        key.setMaterialType(MATERIAL_TYPE);
        key.setEnv(settings.requireEnv());
        key.setStatus("ACTIVE");
        db.keys().insert(key);
        event(key.getId(), null, "CREATED", spec.issuedBy(), null);
        return key;
    }

    private SecKey keyOf(KeyOwner owner) {
        return db.keys().selectOne(Wrappers.<SecKey>lambdaQuery()
                .eq(SecKey::getNamespace, owner.namespace().name())
                .eq(SecKey::getOwnerType, owner.ownerType())
                .eq(SecKey::getOwnerId, owner.ownerId())
                .eq(SecKey::getDirection, KeyPurpose.INBOUND_API_KEY.direction().name())
                .eq(SecKey::getKeyUsage, KeyPurpose.INBOUND_API_KEY.usage().name())
                .eq(SecKey::getEnv, settings.requireEnv()));
    }

    private SecKeyVersion byCredentialKey(KeyOwner owner, String credentialNo) {
        return db.versions().selectOne(Wrappers.<SecKeyVersion>lambdaQuery()
                .eq(SecKeyVersion::getCredentialKey, credentialKey(owner, credentialNo)));
    }

    private static String credentialKey(KeyOwner owner, String credentialNo) {
        return owner.namespace().name() + "|" + owner.ownerType() + "|" + credentialNo;
    }

    private static KeyOwner ownerOf(SecKey key) {
        return new KeyOwner(SecretNamespace.valueOf(key.getNamespace()), key.getOwnerType(), key.getOwnerId());
    }

    private static Credential toCredential(KeyOwner owner, SecKeyVersion v) {
        return new Credential(owner, v.getExternalKeyId(), v.getLabel(), v.getScope(), v.getEnteredBy(),
                instant(v.getValidFrom()), instant(v.getValidTo()), instant(v.getRevokedAt()));
    }

    private static Instant instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    private static SecretException duplicate(NewCredential spec) {
        return new SecretException(Reason.DUPLICATE, "credential " + spec.credentialNo() + " of "
                + spec.owner().namespace() + "/" + spec.owner().ownerType() + " is already registered differently");
    }

    private void event(long keyId, Long versionId, String type, String operator, String reason) {
        SecKeyEvent e = new SecKeyEvent();
        e.setSecKeyId(keyId);
        e.setSecKeyVersionId(versionId);
        e.setEventType(type);
        e.setOperatorType("SYSTEM".equals(operator) ? "SYSTEM" : "OPS");
        e.setOperatorNo(operator);
        e.setReason(reason);
        e.setOccurredAt(settings.now());
        db.events().insert(e);
    }
}
