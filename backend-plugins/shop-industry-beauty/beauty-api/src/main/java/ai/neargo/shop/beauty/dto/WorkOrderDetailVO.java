package ai.neargo.shop.beauty.dto;

import java.util.List;

/** Frozen work-order facts expose no execution or payment permission. */
public record WorkOrderDetailVO(WorkOrderVO workOrder, CustomerIdentityVO customer, long originalScopeRevision,
        List<WorkOrderTaskVO> tasks, List<String> availableActions) {
    public WorkOrderDetailVO { tasks = List.copyOf(tasks); availableActions = List.copyOf(availableActions); }
}
