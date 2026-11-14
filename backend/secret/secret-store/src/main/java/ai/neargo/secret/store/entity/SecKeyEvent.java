package ai.neargo.secret.store.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * sec_key_event（db/secret V1 · TDD-支付服务-W5密钥模块）：密钥生命周期事件：创建、录入、测试、启用、轮换、退役、吊销、到期提醒、重新包裹。只增不改
 *
 * <p>字段注释取自 DDL，逐列定义以表结构说明 §6.8 为准。
 */
@Getter
@Setter
@TableName("sec_key_event")
public class SecKeyEvent extends SecBaseEntity {

    /** 密钥 sec_key.id */
    private Long secKeyId;

    /** 版本 sec_key_version.id（密钥级事件为空） */
    private Long secKeyVersionId;

    /** CREATED / MATERIAL_ADDED / VERIFIED / VERIFY_FAILED / ENABLED / ROTATION_STARTED / RETIRED / REVOKED / SUSPENDED / RESUMED / EXPIRING_SOON / EXPIRED / REWRAPPED */
    private String eventType;

    /** OPS 运营 / BIZ 商家 / SYSTEM 系统任务 */
    private String operatorType;

    /** 操作人（审计字段） */
    private String operatorNo;

    /** 两人复核证据：调用方模块的复核单引用，如 PAY:pay_approval:88（不作关联） */
    private String approvalRef;

    /** 原因 */
    private String reason;

    /** 操作来源 IP */
    private String clientIp;

    /** 发生时间 */
    private LocalDateTime occurredAt;
}
