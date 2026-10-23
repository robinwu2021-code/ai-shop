package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Pending reception exposes its original plan without claiming that a service scope already exists. */
public record WalkInVO(String operationNo, String operationState, String appointmentNo,
        WorkOrderVO workOrder, OffsetDateTime startAt, String recoveryAction) { }
