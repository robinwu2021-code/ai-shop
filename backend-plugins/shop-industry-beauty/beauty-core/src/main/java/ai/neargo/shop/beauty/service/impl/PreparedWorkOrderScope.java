package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.AppointmentAssignmentVO;
import ai.neargo.shop.beauty.policy.ReceptionIdentity.VerifiedIdentity;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import java.util.List;

/** Read-only preparation shared with later work-order opening; this creates no order or task. */
public record PreparedWorkOrderScope(VerifiedIdentity identity, FrozenAppointmentScope frozen,
        List<AppointmentAssignmentVO> assignments, ResourceBookingPort.Scope scope) {
    public PreparedWorkOrderScope { assignments = List.copyOf(assignments); }
    public PreparedWorkOrderScope(VerifiedIdentity identity, FrozenAppointmentScope frozen,
            List<AppointmentAssignmentVO> assignments) {
        this(identity, frozen, assignments, new ResourceBookingPort.Scope(frozen.holdEvidence().binding().tenantNo(),
                frozen.holdEvidence().binding().entityNo(), frozen.holdEvidence().binding().storeNo()));
    }
}
