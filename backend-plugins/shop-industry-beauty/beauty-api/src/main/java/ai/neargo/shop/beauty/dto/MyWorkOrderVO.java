package ai.neargo.shop.beauty.dto;

import java.util.List;

/** Frozen work-order facts expose no execution or payment permission. */
public record MyWorkOrderVO(WorkOrderVO workOrder, long originalScopeRevision, List<WorkOrderTaskVO> tasks,
        List<String> availableActions) {
    public MyWorkOrderVO { tasks = List.copyOf(tasks); availableActions = List.copyOf(availableActions); }
}
