package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.AppointmentAssignmentVO;
import ai.neargo.shop.beauty.policy.ReceptionIdentity.VerifiedIdentity;
import java.util.List;

/** Read-only preparation shared with later work-order opening; this creates no order or task. */
public record PreparedWorkOrderScope(VerifiedIdentity identity, FrozenAppointmentScope frozen,
        List<AppointmentAssignmentVO> assignments) {
    public PreparedWorkOrderScope { assignments = List.copyOf(assignments); }
}
