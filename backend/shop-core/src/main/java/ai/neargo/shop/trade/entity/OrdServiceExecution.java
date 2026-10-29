package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Durable original execution evidence; immutable associations survive every terminal state. */
@Getter
@Setter
@TableName("ord_service_execution")
public class OrdServiceExecution extends BaseEntity {
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String executionNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String unitNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String entityNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String storeNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer attemptNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String grantNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer quantity;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String uom;

    private String state;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String bindingJson;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String bindingHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sourceIntentHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String prepareActionNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String prepareKeyHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String prepareActionHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String declarationNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long admissionRevision;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long manualSourceRevision;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String manualSourceHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String originalStockManifestHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String confirmationNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String stockObservationHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sourceFenceNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long sourceFenceRevision;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String resourceCommandNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String resourceCommandHash;

    private String resourceOutcomeNo;

    private String resourceOutcomeHash;

    private Long observationEpoch;

    private LocalDateTime nextRetryAt;

    private String reasonCode;

}
