package ai.neargo.secret.core;

import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.api.SecretNamespace;
import ai.neargo.secret.store.entity.SecMasterKey;
import ai.neargo.secret.store.mapper.SecretMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

/**
 * {@code sec_master_key} 与本进程主密钥提供者的对账（表结构说明 §3.7 第 1 步）。
 *
 * <p>启动时 {@link #sync()} 一次：提供者手里新出现的名字登记进表；标了 active 的那把若与表里当前 ACTIVE 不同，
 * 旧的转 {@code DECRYPT_ONLY}（{@code rotated_at}）、新的转 {@code ACTIVE} —— 这就是「主密钥轮换」的入口，
 * 运维动作只有「注入一把新的并标 active」。之后由 {@link SecretLifecycleService} 逐条重新包裹。
 */
public class MasterKeyBook {

    private final SecretMappers.MasterKeyMapper masterKeys;
    private final MasterKeyProvider provider;
    private final SecretSettings settings;

    public MasterKeyBook(SecretMappers.MasterKeyMapper masterKeys, MasterKeyProvider provider, SecretSettings settings) {
        this.masterKeys = masterKeys;
        this.provider = provider;
        this.settings = settings;
    }

    /** 启动时调用。提供者里没有主密钥就什么都不做；有主密钥却没配环境 —— 配置错了，启动即失败 */
    public void sync() {
        if (provider.declared().isEmpty()) {
            return;
        }
        String env = settings.requireEnv();
        for (MasterKeyProvider.Descriptor d : provider.declared()) {
            SecMasterKey row = byAlias(d.alias());
            if (row == null) {
                row = new SecMasterKey();
                row.setKmsKeyId(d.alias());
                row.setProvider(provider.providerType());
                row.setNamespace(d.namespace().name());
                row.setEnv(env);
                row.setStatus("DECRYPT_ONLY");
                masterKeys.insert(row);
            } else if (!row.getNamespace().equals(d.namespace().name()) || !row.getEnv().equals(env)) {
                throw new IllegalStateException("master key alias " + d.alias() + " is registered for "
                        + row.getNamespace() + "/" + row.getEnv() + ", not " + d.namespace() + "/" + env);
            }
            if (d.active() && !"ACTIVE".equals(row.getStatus())) {
                if ("RETIRED".equals(row.getStatus())) {
                    throw new IllegalStateException("master key " + d.alias() + " is RETIRED and cannot be reactivated");
                }
                promote(row, d.namespace(), env);
            }
        }
    }

    private void promote(SecMasterKey row, SecretNamespace ns, String env) {
        String activeKey = ns.name() + "|" + env;
        // 先腾出唯一键 active_key，再占用 —— 反过来会撞 uk_sec_master_key_2
        masterKeys.update(null, Wrappers.<SecMasterKey>lambdaUpdate()
                .set(SecMasterKey::getStatus, "DECRYPT_ONLY")
                .set(SecMasterKey::getActiveKey, null)
                .set(SecMasterKey::getRotatedAt, settings.now())
                .eq(SecMasterKey::getActiveKey, activeKey));
        masterKeys.update(null, Wrappers.<SecMasterKey>lambdaUpdate()
                .set(SecMasterKey::getStatus, "ACTIVE")
                .set(SecMasterKey::getActiveKey, activeKey)
                .set(SecMasterKey::getActivatedAt, settings.now())
                .eq(SecMasterKey::getId, row.getId()));
    }

    /** 本命名空间本环境用于新加密的主密钥；没有、或它不在本进程手里，都封不了 */
    SecMasterKey activeFor(SecretNamespace ns, String env) {
        SecMasterKey row = masterKeys.selectOne(Wrappers.<SecMasterKey>lambdaQuery()
                .eq(SecMasterKey::getActiveKey, ns.name() + "|" + env));
        if (row == null || !provider.has(row.getKmsKeyId())) {
            throw new SecretException(SecretException.Reason.NO_MASTER_KEY,
                    "no active master key for " + ns + "/" + env + " in this process");
        }
        return row;
    }

    SecMasterKey byId(long id) {
        return masterKeys.selectById(id);
    }

    List<SecMasterKey> decryptOnly(String env) {
        return masterKeys.selectList(Wrappers.<SecMasterKey>lambdaQuery()
                .eq(SecMasterKey::getEnv, env).eq(SecMasterKey::getStatus, "DECRYPT_ONLY"));
    }

    void retire(SecMasterKey row) {
        masterKeys.update(null, Wrappers.<SecMasterKey>lambdaUpdate()
                .set(SecMasterKey::getStatus, "RETIRED")
                .set(SecMasterKey::getRetiredAt, settings.now())
                .eq(SecMasterKey::getId, row.getId()).eq(SecMasterKey::getStatus, "DECRYPT_ONLY"));
    }

    private SecMasterKey byAlias(String alias) {
        return masterKeys.selectOne(Wrappers.<SecMasterKey>lambdaQuery().eq(SecMasterKey::getKmsKeyId, alias));
    }
}
