package ai.neargo.shop.common;

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
 * 实体基类：物理主键 + 租户键 + 审计 + 乐观锁 + 逻辑删除。
 *
 * <p><b>主键约定</b>：{@code id} 是库内自增物理主键，<b>不对外、不进 URL、不进响应</b>；
 * 对外稳定标识一律用业务键 {@code xxxNo}（由 {@link BizKey} 生成）。
 * 这条不是洁癖 —— 一旦 {@code id} 出现在 URL 里，分库或数据迁移就再也做不了。
 *
 * <p>{@code tenantNo} 一期恒为 {@code MAIN}（[矩阵 P-17.1.6] 预留），不启用行级租户隔离。
 */
@Getter
@Setter
public abstract class BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一期恒 MAIN。留字段不留逻辑，将来要开多租户不必改表。 */
    private String tenantNo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 创建人业务键：C 端 = userNo，运营端 = staffNo，系统作业 = SYSTEM。 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @Version
    private Long version;

    /** 逻辑删除。契约层禁止 {@code delete*}，软删语义一律 {@code archive}（[architecture.md §10.6]）。 */
    @TableLogic
    private Integer deleted;
}
