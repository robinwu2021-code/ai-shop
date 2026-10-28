package ai.neargo.secret.core;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.KeyPurpose;
import ai.neargo.secret.api.KeyRegistry;
import ai.neargo.secret.api.KeyVersionRef;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretException.Reason;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.store.entity.SecAccessLog;
import ai.neargo.secret.store.entity.SecKey;
import ai.neargo.secret.store.entity.SecKeyEvent;
import ai.neargo.secret.store.entity.SecKeyVersion;
import ai.neargo.secret.store.entity.SecSecretBlob;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link KeyRegistry} 的实现：{@code sec_key} / {@code sec_key_version} / {@code sec_key_event} / {@code sec_access_log}。
 *
 * <p>状态迁移一律「带旧状态条件的 UPDATE + 判影响行数」：并发的两次启用只有一次能成功，
 * 另一次看到 0 行就报 {@link Reason#ILLEGAL_STATE}，不会出现两个 ACTIVE（唯一键 {@code active_key} 是第二道）。
 * 清空列（{@code active_key = NULL}）只能走 {@code UpdateWrapper#set}：{@code updateById} 默认跳过 null 字段。
 */
public class DefaultKeyRegistry implements KeyRegistry {

    /** 冻结版本可用于退款、查单、验旧回调的状态（表结构说明 §3.3） */
    private static final Set<String> FROZEN_USABLE = Set.of("ACTIVE", "ROTATING", "RETIRED");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter SERIAL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SecretStoreMappers db;
    private final DefaultSecretVault vault;
    private final SecretSettings settings;
    private final TransactionTemplate tx;

    public DefaultKeyRegistry(SecretStoreMappers db, DefaultSecretVault vault, SecretSettings settings,
                              PlatformTransactionManager txManager) {
        this.db = db;
        this.vault = vault;
        this.settings = settings;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public long register(NewKey spec) {
        String env = settings.requireEnv();
        return tx.execute(s -> {
            SecKey existing = keyOf(spec.owner(), spec.purpose(), env);
            if (existing != null) {
                return existing.getId();
            }
            SecKey key = new SecKey();
            key.setNamespace(spec.owner().namespace().name());
            key.setName(spec.name());
            key.setOwnerType(spec.owner().ownerType());
            key.setOwnerId(spec.owner().ownerId());
            key.setCounterparty(spec.counterparty());
            key.setDirection(spec.purpose().direction().name());
            key.setKeyUsage(spec.purpose().usage().name());
            key.setAlgorithm(spec.algorithm());
            key.setMaterialType(spec.materialType());
            key.setEnv(env);
            key.setStatus("DRAFT");
            db.keys().insert(key);
            event(key.getId(), null, "CREATED", spec.operator(), null, null);
            return key.getId();
        });
    }

    @Override
    public Optional<KeyInfo> find(KeyOwner owner, KeyPurpose purpose) {
        SecKey key = keyOf(owner, purpose, settings.requireEnv());
        return Optional.ofNullable(key).map(k -> new KeyInfo(k.getId(), owner, purpose, k.getStatus(),
                k.getCurrentVersion(), k.getExpiresAt()));
    }

    @Override
    public KeyVersionRef addVersion(long keyId, NewVersion v) {
        return tx.execute(s -> insertVersion(requireKey(keyId), v, null, null).ref);
    }

    @Override
    public IssuedSecret generate(long keyId, int lengthBytes, String issuedBy, String reason) {
        if (lengthBytes < 32) {
            throw new IllegalArgumentException("generated keys must be at least 32 bytes");
        }
        // 材料是可打印的 Base64URL 文本本身：对方拿到的就是这串字符，按 UTF-8 字节当 HMAC 密钥，两边不会差一层编码
        return issue(keyId, SecretFormats.base64Url(lengthBytes, RANDOM), null, null, issuedBy, reason);
    }

    @Override
    public IssuedSecret generate(long keyId, Generation g) {
        return issue(keyId, SecretFormats.generate(g.format(), RANDOM), g.displayPrefix(), g.serialPrefix(),
                g.issuedBy(), g.reason());
    }

    private IssuedSecret issue(long keyId, byte[] material, String displayPrefix, String serialPrefix,
                               String issuedBy, String reason) {
        return tx.execute(s -> {
            SecKey key = requireKey(keyId);
            Inserted ins = insertVersion(key, new NewVersion(material, null, "GENERATED", null, null, null, issuedBy),
                    displayPrefix, serialPrefix);
            SecAccessLog log = new SecAccessLog();
            log.setAccessType("REVEAL_ON_ISSUE");
            log.setNamespace(key.getNamespace());
            log.setSecKeyId(keyId);
            log.setSecSecretBlobId(ins.row.getSecSecretBlobId());
            log.setAccessorNo(issuedBy);
            log.setAccessorRole("sec:key:write");
            log.setReason(reason == null || reason.isBlank() ? "issue generated key" : reason);
            log.setAccessedAt(settings.now());
            db.accessLogs().insert(log);
            return new IssuedSecret(ins.ref, material, ins.row.getFingerprint(), ins.row.getExternalKeyId());
        });
    }

    @Override
    public void markVerified(KeyVersionRef ref, String evidence, String operator) {
        tx.executeWithoutResult(s -> {
            SecKeyVersion v = requireVersion(ref);
            transition(v, "DRAFT", Wrappers.<SecKeyVersion>lambdaUpdate()
                    .set(SecKeyVersion::getStatus, "VERIFIED")
                    .set(SecKeyVersion::getVerifiedAt, settings.now())
                    .set(SecKeyVersion::getVerifyEvidence, evidence));
            event(ref.keyId(), v.getId(), "VERIFIED", operator, null, evidence);
        });
    }

    @Override
    public void enable(KeyVersionRef ref, String operator, String approvalRef) {
        tx.executeWithoutResult(s -> {
            SecKey key = requireKey(ref.keyId());
            if ("REVOKED".equals(key.getStatus())) {
                throw new SecretException(Reason.ILLEGAL_STATE, "key " + key.getId() + " is REVOKED");
            }
            SecKeyVersion v = requireVersion(ref);
            KeyPolicy policy = KeyPolicy.of(db.policies(), key);
            String from = v.getStatus();
            if (!"VERIFIED".equals(from) && !("DRAFT".equals(from) && !policy.verifyBeforeEnable())) {
                throw new SecretException(Reason.ILLEGAL_STATE, "version " + ref + " is " + from
                        + (policy.verifyBeforeEnable() ? "; a passed test call (VERIFIED) is required" : ""));
            }
            // 与 ck_sec_key_version_2 同一条：启用人 ≠ 录入人。库上的 CHECK 在 H2 上不执行，这里先拦
            if (operator == null || operator.equals(v.getEnteredBy())) {
                throw new SecretException(Reason.DUAL_CONTROL,
                        "version " + ref + " must be enabled by someone other than who entered it");
            }
            LocalDateTime now = settings.now();
            SecKeyVersion previous = db.versions().selectOne(Wrappers.<SecKeyVersion>lambdaQuery()
                    .eq(SecKeyVersion::getActiveKey, String.valueOf(key.getId())));
            if (previous != null) {
                transition(previous, "ACTIVE", Wrappers.<SecKeyVersion>lambdaUpdate()
                        .set(SecKeyVersion::getStatus, "ROTATING")
                        .set(SecKeyVersion::getActiveKey, null)
                        .set(SecKeyVersion::getOverlapUntil, now.plusDays(policy.overlapDays())));
                event(key.getId(), previous.getId(), "ROTATION_STARTED", operator, approvalRef, null);
            }
            LocalDateTime validTo = v.getValidTo();
            if (policy.maxAgeDays() > 0) {
                LocalDateTime byPolicy = now.plusDays(policy.maxAgeDays());
                validTo = validTo == null || byPolicy.isBefore(validTo) ? byPolicy : validTo;
            }
            transition(v, from, Wrappers.<SecKeyVersion>lambdaUpdate()
                    .set(SecKeyVersion::getStatus, "ACTIVE")
                    .set(SecKeyVersion::getActiveKey, String.valueOf(key.getId()))
                    .set(SecKeyVersion::getEnabledBy, operator)
                    .set(SecKeyVersion::getEnabledAt, now)
                    .set(SecKeyVersion::getValidTo, validTo));
            db.keys().update(null, Wrappers.<SecKey>lambdaUpdate()
                    .set(SecKey::getStatus, "ACTIVE")
                    .set(SecKey::getCurrentVersion, ref.versionIndex())
                    .set(SecKey::getExpiresAt, validTo)
                    .eq(SecKey::getId, key.getId()));
            event(key.getId(), v.getId(), "ENABLED", operator, approvalRef, null);
        });
    }

    @Override
    public void revoke(KeyVersionRef ref, String operator, String reason) {
        tx.executeWithoutResult(s -> {
            SecKeyVersion v = requireVersion(ref);
            if ("REVOKED".equals(v.getStatus())) {
                return;
            }
            transition(v, v.getStatus(), Wrappers.<SecKeyVersion>lambdaUpdate()
                    .set(SecKeyVersion::getStatus, "REVOKED")
                    .set(SecKeyVersion::getActiveKey, null)
                    .set(SecKeyVersion::getRevokedBy, operator)
                    .set(SecKeyVersion::getRevokedAt, settings.now())
                    .set(SecKeyVersion::getRevokeReason, reason));
            if ("ACTIVE".equals(v.getStatus())) {
                // 吊销的是当前版本：不自动换用别的版本（换版本意味着身份可能已变，表结构说明 §3.4）
                db.keys().update(null, Wrappers.<SecKey>lambdaUpdate()
                        .set(SecKey::getCurrentVersion, 0).eq(SecKey::getId, ref.keyId()));
            }
            event(ref.keyId(), v.getId(), "REVOKED", operator, null, reason);
        });
    }

    @Override
    public KeyVersionRef current(long keyId) {
        SecKey key = requireKey(keyId);
        if (!"ACTIVE".equals(key.getStatus())) {
            throw new SecretException(Reason.KEY_UNUSABLE, "key " + keyId + " is " + key.getStatus());
        }
        SecKeyVersion v = db.versions().selectOne(Wrappers.<SecKeyVersion>lambdaQuery()
                .eq(SecKeyVersion::getActiveKey, String.valueOf(keyId)));
        if (v == null) {
            throw new SecretException(Reason.VERSION_UNUSABLE, "key " + keyId + " has no ACTIVE version");
        }
        return new KeyVersionRef(keyId, v.getVersionIndex());
    }

    @Override
    public byte[] materialOf(KeyVersionRef ref) {
        SecKeyVersion v = requireVersion(ref);
        if (!FROZEN_USABLE.contains(v.getStatus())) {
            throw new SecretException(Reason.VERSION_UNUSABLE, "version " + ref + " is " + v.getStatus());
        }
        return secretOf(ref, v);
    }

    @Override
    public List<KeyVersionRef> verificationVersions(long keyId) {
        return verifiableRows(keyId).stream().map(v -> new KeyVersionRef(keyId, v.getVersionIndex())).toList();
    }

    @Override
    public Optional<KeyVersionRef> findVersion(long keyId, String externalKeyId) {
        if (externalKeyId == null || externalKeyId.isBlank()) {
            return Optional.empty();
        }
        requireKey(keyId);
        return Optional.ofNullable(db.versions().selectOne(Wrappers.<SecKeyVersion>lambdaQuery()
                        .eq(SecKeyVersion::getSecKeyId, keyId).eq(SecKeyVersion::getExternalKeyId, externalKeyId)))
                .map(v -> new KeyVersionRef(keyId, v.getVersionIndex()));
    }

    /** ACTIVE 加上仍在重叠期内的 ROTATING；ACTIVE 在前：验签先试当前那把，命中率最高 */
    private List<SecKeyVersion> verifiableRows(long keyId) {
        SecKey key = requireKey(keyId);
        if (!"ACTIVE".equals(key.getStatus())) {
            return List.of();
        }
        LocalDateTime now = settings.now();
        return db.versions().selectList(Wrappers.<SecKeyVersion>lambdaQuery()
                        .eq(SecKeyVersion::getSecKeyId, keyId)
                        .in(SecKeyVersion::getStatus, "ACTIVE", "ROTATING")
                        .orderByDesc(SecKeyVersion::getVersionIndex))
                .stream()
                .filter(v -> "ACTIVE".equals(v.getStatus())
                        || v.getOverlapUntil() != null && v.getOverlapUntil().isAfter(now))
                .toList();
    }

    @Override
    public String fingerprintOf(KeyVersionRef ref) {
        return requireVersion(ref).getFingerprint();
    }

    private record Inserted(KeyVersionRef ref, SecKeyVersion row) {
    }

    private Inserted insertVersion(SecKey key, NewVersion v, String displayPrefix, String serialPrefix) {
        boolean hasSecret = v.secretMaterial() != null && v.secretMaterial().length > 0;
        boolean hasPublic = v.publicMaterial() != null && !v.publicMaterial().isBlank();
        // 与 ck_sec_key_version_1 同一条：私密材料与公开材料至少一个
        if (!hasSecret && !hasPublic) {
            throw new IllegalArgumentException("a key version needs secret material or public material");
        }
        if (v.enteredBy() == null || v.enteredBy().isBlank()) {
            throw new IllegalArgumentException("enteredBy is required");
        }
        Integer max = db.versions().selectList(Wrappers.<SecKeyVersion>lambdaQuery()
                        .select(SecKeyVersion::getVersionIndex).eq(SecKeyVersion::getSecKeyId, key.getId()))
                .stream().map(SecKeyVersion::getVersionIndex).max(Integer::compare).orElse(0);
        SecSecretBlob blob = hasSecret
                ? vault.reserve(SecretNamespace.valueOf(key.getNamespace()), DefaultSecretVault.PURPOSE_KEY, "KEY_VERSION")
                : null;
        SecKeyVersion row = new SecKeyVersion();
        row.setSecKeyId(key.getId());
        row.setVersionIndex(max + 1);
        row.setStatus("DRAFT");
        row.setSecSecretBlobId(blob == null ? null : blob.getId());
        row.setPublicMaterial(hasPublic ? v.publicMaterial() : null);
        row.setFingerprint(hasSecret ? EnvelopeCipher.symmetricFingerprint(v.secretMaterial())
                : EnvelopeCipher.sha256Hex(v.publicMaterial().getBytes(StandardCharsets.UTF_8)));
        // KeySerial：前缀 + 生成日 + 两位版本号（S2026102601）。版本号在同一把密钥内唯一，序列号因此也唯一，不用另查
        row.setExternalKeyId(serialPrefix == null ? v.externalKeyId()
                : serialPrefix + SERIAL_DATE.format(settings.now()) + String.format("%02d", max + 1));
        row.setStorageTier(vault.storageTier().name());
        row.setDisplayPrefix(displayPrefix);
        row.setSource(v.source());
        row.setValidFrom(v.validFrom());
        row.setValidTo(v.validTo());
        row.setEnteredBy(v.enteredBy());
        db.versions().insert(row);
        if (blob != null) {
            vault.fill(blob, row.getId(), v.secretMaterial());
        }
        event(key.getId(), row.getId(), "MATERIAL_ADDED", v.enteredBy(), null, v.source());
        return new Inserted(new KeyVersionRef(key.getId(), row.getVersionIndex()), row);
    }

    private byte[] secretOf(KeyVersionRef ref, SecKeyVersion v) {
        if (v.getSecSecretBlobId() == null) {
            throw new SecretException(Reason.VERSION_UNUSABLE, "version " + ref + " has public material only");
        }
        SecKey key = requireKey(ref.keyId());
        return vault.open(SecretNamespace.valueOf(key.getNamespace()), new SecretRef(v.getSecSecretBlobId()));
    }

    private void transition(SecKeyVersion v, String expected,
                            LambdaUpdateWrapper<SecKeyVersion> set) {
        int n = db.versions().update(null, set.eq(SecKeyVersion::getId, v.getId())
                .eq(SecKeyVersion::getStatus, expected));
        if (n != 1) {
            throw new SecretException(Reason.ILLEGAL_STATE,
                    "version #" + v.getId() + " is no longer " + expected + " (concurrent change)");
        }
    }

    private void event(long keyId, Long versionId, String type, String operator, String approvalRef, String reason) {
        SecKeyEvent e = new SecKeyEvent();
        e.setSecKeyId(keyId);
        e.setSecKeyVersionId(versionId);
        e.setEventType(type);
        e.setOperatorType("SYSTEM".equals(operator) || operator == null ? "SYSTEM" : "OPS");
        e.setOperatorNo(operator == null ? "SYSTEM" : operator);
        e.setApprovalRef(approvalRef);
        e.setReason(reason);
        e.setOccurredAt(settings.now());
        db.events().insert(e);
    }

    private SecKey keyOf(KeyOwner owner, KeyPurpose purpose, String env) {
        return db.keys().selectOne(Wrappers.<SecKey>lambdaQuery()
                .eq(SecKey::getNamespace, owner.namespace().name())
                .eq(SecKey::getOwnerType, owner.ownerType())
                .eq(SecKey::getOwnerId, owner.ownerId())
                .eq(SecKey::getDirection, purpose.direction().name())
                .eq(SecKey::getKeyUsage, purpose.usage().name())
                .eq(SecKey::getEnv, env));
    }

    private SecKey requireKey(long keyId) {
        SecKey key = db.keys().selectById(keyId);
        if (key == null || !key.getEnv().equals(settings.requireEnv())) {
            throw new SecretException(Reason.NOT_FOUND, "key " + keyId);
        }
        return key;
    }

    private SecKeyVersion requireVersion(KeyVersionRef ref) {
        SecKeyVersion v = db.versions().selectOne(Wrappers.<SecKeyVersion>lambdaQuery()
                .eq(SecKeyVersion::getSecKeyId, ref.keyId()).eq(SecKeyVersion::getVersionIndex, ref.versionIndex()));
        if (v == null) {
            throw new SecretException(Reason.NOT_FOUND, "version " + ref);
        }
        return v;
    }
}
