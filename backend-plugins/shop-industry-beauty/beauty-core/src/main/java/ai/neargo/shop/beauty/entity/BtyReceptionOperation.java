package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable scoped reception fact; never a shared customer master. */
@Getter
@Setter
@TableName("bty_reception_operation")
public class BtyReceptionOperation extends BaseEntity {
    /** entity_no; scoped internal persistence only. */
    private String entityNo;
    /** store_no; scoped internal persistence only. */
    private String storeNo;
    /** operation_no; scoped internal persistence only. */
    private String operationNo;
    /** actor_no; scoped internal persistence only. */
    private String actorNo;
    /** actor_realm; scoped internal persistence only. */
    private String actorRealm;
    /** actor_kind; scoped internal persistence only. */
    private String actorKind;
    /** endpoint; scoped internal persistence only. */
    private String endpoint;
    /** action; scoped internal persistence only. */
    private String action;
    /** key_hash; scoped internal persistence only. */
    private String keyHash;
    /** request_hash; scoped internal persistence only. */
    private String requestHash;
    /** appointment_no; scoped internal persistence only. */
    private String appointmentNo;
    /** work_order_no; scoped internal persistence only. */
    private String workOrderNo;
    /** identity_kind; scoped internal persistence only. */
    private String identityKind;
    /** identity_ref; scoped internal persistence only. */
    private String identityRef;
    /** phase; scoped internal persistence only. */
    private String phase;
    /** appointment_operation_no; scoped internal persistence only. */
    private String appointmentOperationNo;
    /** derived_key_hash; scoped internal persistence only. */
    private String derivedKeyHash;
    /** frozen_payload; scoped internal persistence only. */
    private String frozenPayload;
    /** result; scoped internal persistence only. */
    private String result;
    /** next_retry_at; scoped internal persistence only. */
    private LocalDateTime nextRetryAt;
    /** lease_until; scoped internal persistence only. */
    private LocalDateTime leaseUntil;
    /** failure_code; scoped internal persistence only. */
    private String failureCode;
}
