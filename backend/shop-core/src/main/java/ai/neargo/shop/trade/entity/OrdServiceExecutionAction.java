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
@TableName("ord_service_execution_action")
public class OrdServiceExecutionAction extends BaseEntity {
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String actionNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String executionNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String entityNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String storeNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String kind;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String actorNamespaceHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String requestKeyHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String actionHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long expectedRevision;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long observationEpoch;

    private String resultState;

    private Long resultRevision;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String proofJson;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String proofHash;

}
