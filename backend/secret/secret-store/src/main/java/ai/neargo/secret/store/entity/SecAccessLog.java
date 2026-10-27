package ai.neargo.secret.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * sec_access_log（db/secret V1 · TDD-支付服务-W5密钥模块）：明文接触记录：任何人看到明文（发放时展示、查看敏感字段、查看报文原文、应急调阅）逐次记录。只增不改
 *
 * <p>字段注释取自 DDL，逐列定义以表结构说明 §6.8 为准。
 */
@Getter
@Setter
@TableName("sec_access_log")
public class SecAccessLog extends SecBaseEntity {

    /** REVEAL_ON_ISSUE 发放时展示 / SENSITIVE_FIELD_VIEW / RAW_PAYLOAD_VIEW / BREAK_GLASS 应急调阅 */
    private String accessType;

    /** 命名空间 */
    private String namespace;

    /** 涉及的密钥 sec_key.id */
    private Long secKeyId;

    /** 涉及的密文 sec_secret_blob.id */
    private Long secSecretBlobId;

    /** 其他对象类型：RAW_PAYLOAD … */
    private String targetType;

    /** 其他对象 id（如 pay_raw_payload.id） */
    private Long targetId;

    /** 查看人 */
    private String accessorNo;

    /** 查看时的岗位 / 权限码 */
    private String accessorRole;

    /** 查看理由（必填） */
    private String reason;

    /** BREAK_GLASS 必须有复核单引用 */
    private String approvalRef;

    /** 来源 IP */
    private String clientIp;

    /** 时间 */
    private LocalDateTime accessedAt;
}
