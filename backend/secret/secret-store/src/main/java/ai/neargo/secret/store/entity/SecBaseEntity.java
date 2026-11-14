package ai.neargo.secret.store.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * {@code sec_*} 表的实体基类：与支付的 {@code PayBaseEntity} 逐字段一致（无 {@code tenant_no}，BIGINT 自增 id）。
 *
 * <p>不直接继承 {@code PayBaseEntity}：密钥模块是平台级的，依赖支付就反了方向（表结构说明 §3.0）；
 * 也不继承平台 {@code BaseEntity}：那个多一个 {@code tenantNo}，填充器会往一个不存在的列里写值。
 * 审计列由宿主的 {@code MetaObjectHandler} 填（{@code strictInsertFill}，字段对得上就填）。
 */
@Getter
@Setter
public abstract class SecBaseEntity {

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
