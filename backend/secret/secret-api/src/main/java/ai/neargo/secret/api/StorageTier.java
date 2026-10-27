package ai.neargo.secret.api;

/**
 * 密钥材料存在哪一档（{@code sec_key_version.storage_tier}，TDD-支付服务-开放API加解密与密钥分发 §2.8.1）。
 *
 * <p>每个版本各自记录：换档就是一次轮换，新版本写新档，旧版本留在旧档直到退役，两档共存不停机。
 * 业务代码不看档位 —— 运算一律经 {@link KeyVault}；这里只给运维、策略校验与路由用。
 */
public enum StorageTier {
    /** 配置明文（本机、CI）；生产禁止 */
    L1(true),
    /** 库 + 部署注入的主密钥（信封加密） */
    L2(true),
    /** 库 + KMS 主密钥（信封加密，主密钥不出 KMS） */
    L3(true),
    /** 外部密钥服务（Vault / 云凭据管家），库里只存路径 */
    L4(true),
    /** KMS / HSM 内生成、永不导出；只能调它做运算 */
    L5(false);

    private final boolean exportable;

    StorageTier(boolean exportable) {
        this.exportable = exportable;
    }

    /** 材料能否进进程内存。L5 为 false：{@link KeyRegistry#materialOf} 对它没有意义 */
    public boolean exportable() {
        return exportable;
    }
}
