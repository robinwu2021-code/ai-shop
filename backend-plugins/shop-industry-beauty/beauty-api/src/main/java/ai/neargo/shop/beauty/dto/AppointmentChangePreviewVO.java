package ai.neargo.shop.beauty.dto;


/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentChangePreviewVO(String previewNo, Long expectedRevision, AppointmentPreviewVO preview) { }
