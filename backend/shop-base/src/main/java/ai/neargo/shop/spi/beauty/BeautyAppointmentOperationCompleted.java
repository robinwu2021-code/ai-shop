package ai.neargo.shop.spi.beauty;

import ai.neargo.shop.event.DomainEvent;

/** Stable operation identity lets consumers deduplicate source outcomes without customer, plan or money data. */
public record BeautyAppointmentOperationCompleted(String aggregateId, String entityNo, String storeNo,
        String operationNo, String action, String operationState, String appointmentStatus, long revision,
        long arrangementRevision) implements DomainEvent {
    @Override
    public String aggregateType() { return "BEAUTY_APPOINTMENT"; }

    @Override
    public String eventType() { return "BEAUTY_APPOINTMENT_OPERATION_COMPLETED"; }
}
