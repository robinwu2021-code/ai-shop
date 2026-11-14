package ai.neargo.secret.core;

import ai.neargo.secret.api.SecretNamespace;
import java.util.List;

/**
 * 主密钥提供者：<b>主密钥材料只在这里</b>，库里 {@code sec_master_key} 只登记名字（表结构说明 §3.2）。
 *
 * <p>接口刻意只有「包裹 / 解包数据密钥」，没有「取出主密钥」：KMS / HSM 的主密钥本来就出不了设备，
 * 注入式实现也照这个形状写，于是换成 KMS 那天调用方一行不用改。
 */
public interface MasterKeyProvider {

    /** {@code sec_master_key.provider}：ENV / KMS / HSM */
    String providerType();

    /** 本进程手里有的主密钥（名字 + 命名空间 + 是否用于新加密）。没有任何材料 */
    List<Descriptor> declared();

    boolean has(String alias);

    /** 用主密钥包裹数据密钥；{@code aad} 绑定密文所在行 */
    byte[] wrap(String alias, byte[] dataKey, byte[] aad);

    /** 解包；认证失败抛 {@link ai.neargo.secret.api.SecretException.Reason#INTEGRITY} */
    byte[] unwrap(String alias, byte[] wrapped, byte[] aad);

    /**
     * 主密钥的元数据。
     *
     * @param alias  {@code sec_master_key.kms_key_id}：KMS key id 或注入别名 —— 是名字，不是密钥
     * @param active true = 本命名空间本环境用于新加密的那一把；false = 只解密（轮换后的旧主密钥）
     */
    record Descriptor(String alias, SecretNamespace namespace, boolean active) {
    }
}
