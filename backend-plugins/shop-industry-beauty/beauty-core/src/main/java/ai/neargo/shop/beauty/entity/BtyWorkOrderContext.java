package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Durable scoped reception fact; never a shared customer master. */
@Getter
@Setter
@TableName("bty_work_order_context")
public class BtyWorkOrderContext extends BaseEntity {
    /** entity_no; scoped internal persistence only. */
    private String entityNo;
    /** store_no; scoped internal persistence only. */
    private String storeNo;
    /** work_order_no; scoped internal persistence only. */
    private String workOrderNo;
    /** identity_kind; scoped internal persistence only. */
    private String identityKind;
    /** identity_ref; scoped internal persistence only. */
    private String identityRef;
    /** source_reception_operation_no; scoped internal persistence only. */
    private String sourceReceptionOperationNo;
    /** appointment_arrangement_revision; scoped internal persistence only. */
    private Long appointmentArrangementRevision;
    /** frozen_scope; scoped internal persistence only. */
    private String frozenScope;
}
