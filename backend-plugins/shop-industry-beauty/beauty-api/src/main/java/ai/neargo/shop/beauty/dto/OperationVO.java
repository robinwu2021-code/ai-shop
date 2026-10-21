package ai.neargo.shop.beauty.dto;


/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record OperationVO(String operationNo, String appointmentNo, String state, String action, String
        recoveryAction) { }
