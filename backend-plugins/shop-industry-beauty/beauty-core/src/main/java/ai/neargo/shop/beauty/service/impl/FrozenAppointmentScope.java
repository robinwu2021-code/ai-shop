package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.AppointmentPlanItemVO;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import java.util.List;

/** Internal immutable scope constructed only from persisted server evidence, never a public request. */
public record FrozenAppointmentScope(List<AppointmentPlanItemVO> items, List<WorkOrderTaskSnapshotVO> definitions,
        String policyVersionNo, PublishStorePolicyVersionReq.CancellationRule cancelRule,
        List<ObservedHead> observedHeads, HoldEvidence holdEvidence) {
    public FrozenAppointmentScope {
        items = List.copyOf(items); definitions = List.copyOf(definitions); observedHeads = List.copyOf(observedHeads);
        if (items.size() != definitions.size()) { throw new IllegalArgumentException("Frozen lines must align"); }
    }
}
