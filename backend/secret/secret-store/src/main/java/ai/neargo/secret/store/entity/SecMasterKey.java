package ai.neargo.secret.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * sec_master_key（db/secret V1 · TDD-支付服务-W5密钥模块）：主密钥登记：按命名空间与环境登记包裹数据密钥的主密钥元数据。本表不存任何密钥材料
 *
 * <p>字段注释取自 DDL，逐列定义以表结构说明 §6.8 为准。
 */
@Getter
@Setter
@TableName("sec_master_key")
public class SecMasterKey extends SecBaseEntity {

    /** KMS key id 或环境注入别名（是名字，不是密钥） */
    private String kmsKeyId;

    /** ENV 部署注入 / KMS 云 KMS / HSM 硬件 */
    private String provider;

    /** PAY / LOGISTICS / INVENTORY / NOTIFY / PLATFORM：每个域一套主密钥，进程只能解本域 */
    private String namespace;

    /** SANDBOX / PRODUCTION：两套环境主密钥必须不同 */
    private String env;

    /** ACTIVE 用于新加密 / DECRYPT_ONLY 只解密（轮换后的旧主密钥）/ RETIRED 已无密文引用 */
    private String status;

    /** ACTIVE 时 = namespace|env：每个命名空间每个环境同一时刻一个 ACTIVE */
    private String activeKey;

    /** 启用时间 */
    private LocalDateTime activatedAt;

    /** 被新主密钥替换的时间 */
    private LocalDateTime rotatedAt;

    /** 退役时间（全部密文已重新包裹） */
    private LocalDateTime retiredAt;
}
