package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Getter;
import lombok.Setter;

/** Permanent links protect existing stock rows; original quantities are immutable evidence only. */
@Getter
@Setter
@TableName("prd_execution_retention")
public class PrdExecutionRetention extends BaseEntity {
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String entityNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String storeNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String retentionNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String executionNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String unitNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String grantNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer attemptNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String referenceSchema;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String referenceJson;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String referenceHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String originalBindingBytesHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long preparedGrantRevision;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long originalRowId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String originalLockNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String stockOriginalJson;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String originalHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String originalManifestHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String confirmationOperationNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String sourceConfirmationNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long originalRowVersion;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String originalDisposition;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String originalRowJson;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String ownerSetHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer ownerSetCount;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String requestHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String resourceCommandNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String resourceCommandHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String planHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String creatorNamespaceHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String creatorPersonnelHash;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String acceptedAuthorityJson;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String acceptedAuthorityHash;

    private String state;

    private String issueObservationJson;

    private String issueObservationHash;

    private String noEffectSealJson;

    private String noEffectSealNo;

    private Long noEffectSealRevision;

    private String noEffectSealHash;

}
