package ai.neargo.secret.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * sec_key_version（db/secret V1 · TDD-支付服务-W5密钥模块）：密钥版本：一把密钥的每一份材料。私密材料只存密文 id，公钥与证书可明文存放
 *
 * <p>字段注释取自 DDL，逐列定义以表结构说明 §6.8 为准。
 */
@Getter
@Setter
@TableName("sec_key_version")
public class SecKeyVersion extends SecBaseEntity {

    /** 所属密钥 sec_key.id */
    private Long secKeyId;

    /** 版本号，从 1 起（交易冻结的是这个号） */
    private Integer versionIndex;

    /** DRAFT 已录入 / VERIFIED 测试通过 / ACTIVE 当前 / ROTATING 重叠期仍可用 / RETIRED 仅验旧签与在途单 / REVOKED 立即失效 */
    private String status;

    /** ACTIVE 时 = sec_key_id：一把密钥同一时刻一个 ACTIVE 版本 */
    private String activeKey;

    /** 私密材料的密文 sec_secret_blob.id（对称密钥、私钥） */
    private Long secSecretBlobId;

    /** 公钥或证书 PEM（可公开，不加密） */
    private String publicMaterial;

    /** 指纹：公钥 / 证书为 SHA-256，对称密钥为 HMAC 派生前缀；唯一可回显的信息 */
    private String fingerprint;

    /** 外部密钥标识：微信平台证书序列号、PayBy 公钥 ID、接入方 key id */
    private String externalKeyId;

    /** GENERATED 平台生成 / UPLOADED 人工录入 / FETCHED 从对端下载（微信平台证书） */
    private String source;

    /** 生效起（证书 notBefore） */
    private LocalDateTime validFrom;

    /** 生效止（证书 notAfter / 策略最长使用期） */
    private LocalDateTime validTo;

    /** 录入或生成人 */
    private String enteredBy;

    /** 测试调用通过时间 */
    private LocalDateTime verifiedAt;

    /** 测试调用证据：调用方模块写入的记录引用，如 PAY:pay_channel_call_log:123（不作关联） */
    private String verifyEvidence;

    /** 启用人，必须 ≠ 录入人 */
    private String enabledBy;

    /** 启用时间 */
    private LocalDateTime enabledAt;

    /** 轮换后旧版本仍可用至 */
    private LocalDateTime overlapUntil;

    /** 退役时间 */
    private LocalDateTime retiredAt;

    /** 吊销人 */
    private String revokedBy;

    /** 吊销时间 */
    private LocalDateTime revokedAt;

    /** 吊销原因（泄露、离职、商户解约…） */
    private String revokeReason;

    /** 存储档 L1–L5（db/secret V2）：每个版本各自记录，路由按它分发 */
    private String storageTier;

    /** 只用于显示的前缀（AppSecret 的 sk_live_ / sk_test_）；不参与任何运算，不属于材料 */
    private String displayPrefix;
}
