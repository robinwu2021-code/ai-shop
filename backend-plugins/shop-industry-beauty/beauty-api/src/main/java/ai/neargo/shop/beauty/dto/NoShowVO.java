package ai.neargo.shop.beauty.dto;


/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record NoShowVO(String appointmentNo, long revision, String status, String operationNo, String
        operationState) { }
