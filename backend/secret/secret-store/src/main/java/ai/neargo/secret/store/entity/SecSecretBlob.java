package ai.neargo.secret.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * sec_secret_blob（db/secret V1 · TDD-支付服务-W5密钥模块）：密文存储：密钥私密材料与各域敏感字段的信封加密密文。数据密钥由主密钥包裹，主密钥不落库
 *
 * <p>字段注释取自 DDL，逐列定义以表结构说明 §6.8 为准。
 */
@Getter
@Setter
@TableName("sec_secret_blob")
public class SecSecretBlob extends SecBaseEntity {

    /** 命名空间（同 sec_master_key.namespace） */
    private String namespace;

    /** KEY_MATERIAL 密钥材料 / SENSITIVE_FIELD 敏感字段（如进件证件号、银行账号） */
    private String purpose;

    /** KEY_VERSION / ONBOARDING_MATERIAL / …（调用方模块的对象类型） */
    private String ownerType;

    /** 归属对象 id（sec_key_version.id 或 pay_onboarding_material.id 等） */
    private Long ownerId;

    /** SANDBOX / PRODUCTION */
    private String env;

    /** AES-256-GCM 密文（Base64，含 IV 与认证标签） */
    private String cipherText;

    /** 被主密钥包裹的数据密钥 */
    private String dataKeyCipher;

    /** 包裹所用主密钥 sec_master_key.id */
    private Long secMasterKeyId;

    /** AES-256-GCM */
    private String algorithm;

    /** 附加认证数据（id + namespace + owner_type + owner_id + env）的摘要：密文被挪到别的行即解密失败 */
    private String aadSha256;

    /** 最近一次主密钥轮换重新包裹的时间 */
    private LocalDateTime rewrappedAt;
}
