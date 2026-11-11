package ai.neargo.shop.beauty.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Frozen work-order facts expose no execution or payment permission. */
public record WorkOrderDetailVO(WorkOrderVO workOrder, CustomerIdentityVO customer, long originalScopeRevision,
        List<WorkOrderTaskVO> tasks, List<String> availableActions,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<EntitlementAllocationVO> entitlementUses) {
    public WorkOrderDetailVO(WorkOrderVO workOrder, CustomerIdentityVO customer, long originalScopeRevision,
            List<WorkOrderTaskVO> tasks, List<String> availableActions) {
        this(workOrder, customer, originalScopeRevision, tasks, availableActions, List.of());
    }
    public WorkOrderDetailVO {
        tasks = List.copyOf(tasks); availableActions = List.copyOf(availableActions);
        entitlementUses = List.copyOf(entitlementUses);
    }
}
