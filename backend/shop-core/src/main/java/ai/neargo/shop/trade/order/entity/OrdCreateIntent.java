package ai.neargo.shop.trade.order.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Getter;
import lombok.Setter;

/** Durable ownership and original stock references survive a failed order transaction. */
@Getter
@Setter
@TableName("ord_create_intent")
public class OrdCreateIntent extends BaseEntity {
    /** Historical customer identity never changes when the guest later claims an account. */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String customerKind = "USER";

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String guestNo;

    @TableField(insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.NEVER)
    private Long identityRevision;

    private String entityNo;
    private String storeNo;
    private String caller;
    private String operationNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String admissionKind = "LEGACY_FUNDED";

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String creationKind = "LEGACY";

    private String confirmationNo;
    private String subOrderNo;
    private String authorityNamespaceHash;
    private String counterPlanJson;
    private String planHash;
    private String reservationOperationNo;
    private String reservationState;
    private String reservationObservationJson;
    private String reservationObservationHash;

    public static final String PREPARED = "PREPARED";
    public static final String COMMITTED = "COMMITTED";
    public static final String COMPENSATING = "COMPENSATING";
    public static final String RECOVERING = "RECOVERING";
    public static final String RELEASED = "RELEASED";
    public static final String RECOVERY_REQUIRED = "RECOVERY_REQUIRED";

    private String payNo;
    private String userNo;
    private String endpoint;
    private String idemKey;
    private String requestHash;
    private String stockAuthority;
    private String stockNeedsJson;
    private String status;
    private String errorCode;
    private Long nextAttemptAt;
}
