package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Appointment protocol projection; resource evidence stays on trusted server links. */
public record AppointmentQuery(String storeNo, OffsetDateTime from, OffsetDateTime to, String status, String
        personnelNo, Integer page, Integer size, String assignmentState, String memberNo, ContactHistory contactHistory) {
    public record ContactHistory(String appointmentNo, String phone) {
        @Override public String toString() { return "ContactHistory[REDACTED]"; }
    }

    public AppointmentQuery(String storeNo, OffsetDateTime from, OffsetDateTime to, String status, String personnelNo,
            Integer page, Integer size, String assignmentState) {
        this(storeNo, from, to, status, personnelNo, page, size, assignmentState, null, null);
    }
}
