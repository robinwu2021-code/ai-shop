package ai.neargo.secret.core;

import ai.neargo.secret.api.KeyOwner;
import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.api.SecretRef;
import ai.neargo.secret.api.SecretVault;
import ai.neargo.secret.store.entity.SecMasterKey;
import ai.neargo.secret.store.entity.SecSecretBlob;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link SecretVault} 的实现：信封加密落 {@code sec_secret_blob}。
 *
 * <h2>为什么封存要写两次</h2>
 * 附加认证数据里有行自己的 {@code id}（表结构说明 §3.2），而 id 是自增的 —— 插入之前不知道。
 * 所以先插一行占位（密文列为空串），拿到 id 再加密回填，两步在同一个事务里。
 * 密钥版本更多一步：密文的归属是版本行，版本行又引用密文，于是先占位密文、再插版本、最后带上版本 id 回填
 * （{@link #reserve} / {@link #fill}，由 {@link DefaultKeyRegistry} 编排）。
 */
public class DefaultSecretVault implements SecretVault {

    static final String PURPOSE_FIELD = "SENSITIVE_FIELD";
    static final String PURPOSE_KEY = "KEY_MATERIAL";

    private final SecretStoreMappers db;
    private final MasterKeyBook book;
    private final MasterKeyProvider provider;
    private final SecretSettings settings;
    private final TransactionTemplate tx;

    public DefaultSecretVault(SecretStoreMappers db, MasterKeyBook book, MasterKeyProvider provider,
                              SecretSettings settings, PlatformTransactionManager txManager) {
        this.db = db;
        this.book = book;
        this.provider = provider;
        this.settings = settings;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public SecretRef seal(KeyOwner owner, byte[] plaintext) {
        return tx.execute(s -> {
            SecSecretBlob row = reserve(owner.namespace(), PURPOSE_FIELD, owner.ownerType());
            fill(row, owner.ownerId(), plaintext);
            return new SecretRef(row.getId());
        });
    }

    @Override
    public byte[] open(SecretNamespace namespace, SecretRef ref) {
        String env = settings.requireEnv();
        SecSecretBlob row = db.blobs().selectById(ref.blobId());
        if (row == null || row.getCipherText().isEmpty()) {
            throw new SecretException(SecretException.Reason.NOT_FOUND, "secret blob " + ref.blobId());
        }
        if (!row.getNamespace().equals(namespace.name()) || !row.getEnv().equals(env)) {
            throw new SecretException(SecretException.Reason.NAMESPACE_MISMATCH, "secret blob " + ref.blobId()
                    + " belongs to " + row.getNamespace() + "/" + row.getEnv() + ", caller is " + namespace + "/" + env);
        }
        byte[] aad = aadOf(row);
        byte[] dataKey = unwrapDataKey(row, aad);
        try {
            return EnvelopeCipher.decrypt(EnvelopeCipher.dataKey(dataKey),
                    Base64.getDecoder().decode(row.getCipherText()), aad);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /** 第一步：占位行，拿到 id。调用方必须在同一事务里 {@link #fill} */
    SecSecretBlob reserve(SecretNamespace namespace, String purpose, String ownerType) {
        String env = settings.requireEnv();
        SecMasterKey mk = book.activeFor(namespace, env);
        SecSecretBlob row = new SecSecretBlob();
        row.setNamespace(namespace.name());
        row.setPurpose(purpose);
        row.setOwnerType(ownerType);
        row.setOwnerId(0L);
        row.setEnv(env);
        row.setCipherText("");
        row.setDataKeyCipher("");
        row.setAadSha256("");
        row.setAlgorithm(EnvelopeCipher.ALGORITHM);
        row.setSecMasterKeyId(mk.getId());
        db.blobs().insert(row);
        return row;
    }

    /** 第二步：带上最终归属加密回填 */
    void fill(SecSecretBlob row, long ownerId, byte[] plaintext) {
        row.setOwnerId(ownerId);
        byte[] aad = aadOf(row);
        byte[] dataKey = EnvelopeCipher.newDataKey();
        try {
            String cipher = Base64.getEncoder().encodeToString(
                    EnvelopeCipher.encrypt(EnvelopeCipher.dataKey(dataKey), plaintext, aad));
            String alias = book.byId(row.getSecMasterKeyId()).getKmsKeyId();
            String wrapped = Base64.getEncoder().encodeToString(provider.wrap(alias, dataKey, aad));
            db.blobs().update(null, Wrappers.<SecSecretBlob>lambdaUpdate()
                    .set(SecSecretBlob::getOwnerId, ownerId)
                    .set(SecSecretBlob::getCipherText, cipher)
                    .set(SecSecretBlob::getDataKeyCipher, wrapped)
                    .set(SecSecretBlob::getAadSha256, EnvelopeCipher.sha256Hex(aad))
                    .eq(SecSecretBlob::getId, row.getId()));
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /**
     * 主密钥轮换：用旧主密钥解开数据密钥、用本命名空间当前 ACTIVE 主密钥重新包裹。<b>密文一个字节都不动</b>（§3.7 第 2 步）。
     */
    void rewrap(SecSecretBlob row) {
        byte[] aad = aadOf(row);
        byte[] dataKey = unwrapDataKey(row, aad);
        try {
            SecMasterKey target = book.activeFor(SecretNamespace.valueOf(row.getNamespace()), row.getEnv());
            String wrapped = Base64.getEncoder().encodeToString(provider.wrap(target.getKmsKeyId(), dataKey, aad));
            db.blobs().update(null, Wrappers.<SecSecretBlob>lambdaUpdate()
                    .set(SecSecretBlob::getDataKeyCipher, wrapped)
                    .set(SecSecretBlob::getSecMasterKeyId, target.getId())
                    .set(SecSecretBlob::getRewrappedAt, settings.now())
                    .eq(SecSecretBlob::getId, row.getId())
                    .eq(SecSecretBlob::getSecMasterKeyId, row.getSecMasterKeyId()));
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    private byte[] unwrapDataKey(SecSecretBlob row, byte[] aad) {
        if (!EnvelopeCipher.sha256Hex(aad).equals(row.getAadSha256())) {
            throw new SecretException(SecretException.Reason.INTEGRITY,
                    "secret blob " + row.getId() + " row binding changed since it was sealed");
        }
        SecMasterKey mk = book.byId(row.getSecMasterKeyId());
        if (mk == null || !provider.has(mk.getKmsKeyId())) {
            throw new SecretException(SecretException.Reason.NO_MASTER_KEY, "secret blob " + row.getId()
                    + " is wrapped by master key #" + row.getSecMasterKeyId() + " which this process does not hold");
        }
        return provider.unwrap(mk.getKmsKeyId(), Base64.getDecoder().decode(row.getDataKeyCipher()), aad);
    }

    private static byte[] aadOf(SecSecretBlob row) {
        return EnvelopeCipher.aad(row.getId(), row.getNamespace(), row.getOwnerType(), row.getOwnerId(), row.getEnv());
    }
}
