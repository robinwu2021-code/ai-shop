package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Durable original execution evidence; immutable associations survive every terminal state. */
@Getter
@Setter
@TableName("ord_service_execution_unit")
public class OrdServiceExecutionUnit extends BaseEntity {
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String unitNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String entityNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String storeNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String caller;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sourceOperationNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String orderNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String subOrderNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sourceLineNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long orderItemId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer unitOrdinal;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer orderedUnits;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer quantity;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String uom;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String taskNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String workOrderNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String bindingNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long bindingRevision;

    private String state;

    private String currentExecutionNo;

    private Integer attemptNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sourceIdentityHash;

}
