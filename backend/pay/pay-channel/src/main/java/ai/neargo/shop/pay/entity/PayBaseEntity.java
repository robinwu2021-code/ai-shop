package ai.neargo.shop.pay.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 支付新表（v4 模型，{@code pay_*}）的实体基类：与 {@link ai.neargo.shop.common.BaseEntity} 逐字段一致，**只少一个 {@code tenantNo}**。
 *
 * <p>新表不带 {@code tenant_no}，关联一律用 BIGINT 自增 id、按门店 id 挂核心系统（ADR-043 §2-4）。
 * 继承旧基类的话，{@code AuditMetaObjectHandler} 会往 {@code tenantNo} 里填 {@code MAIN}，
 * MyBatis-Plus 随之在 INSERT 里写一个表上不存在的列 —— 报错要到第一次插入才出现。
 * 这里干脆没有这个字段：填充器用的是 {@code strictInsertFill}，字段不存在就跳过，**写不出来**。
 *
 * <p>I3–I4 期间两套约定并存（旧 {@code stl_*} / {@code sys_pay_*} 仍继承 {@code BaseEntity}），
 * 判据只有一条：**表名以 {@code pay_} 开头的实体继承本类**。
 */
@Getter
@Setter
public abstract class PayBaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @Version
    private Long version;

    @TableLogic
    private Integer deleted;
}
