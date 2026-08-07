package ai.neargo.shop.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 操作审计（append-only）。**能改审计的审计等于没有审计**，
 * 因此不继承 BaseEntity（没有 version/deleted）。
 */
@Getter
@Setter
@TableName("sys_audit_log")
public class SysAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String staffNo;
    private String staffName;

    /** 操作码，如 MERCHANT_AUDIT。列名 op_action —— action 是 H2 保留字（R13）。 */
    private String opAction;

    private String target;
    private String detail;
    private Long at;
    private String tenantNo;
    private LocalDateTime createdAt;
}
