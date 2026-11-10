package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.AssignServiceTaskReq;
import ai.neargo.shop.beauty.dto.ServiceTaskAssignmentVO;

/**
 * Current coordinator publishes a pre-fence plan; saved operator provenance grants no future
 * authority.
 */
public interface ServiceTaskAssignmentService {
    ServiceTaskAssignmentVO assignTask(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            AssignServiceTaskReq request,
            String requestKey);

    /** Original-number GET/recovery is a pure projection under current family authorization. */
    ServiceTaskAssignmentVO projectOperation(
            String entity, String store, String actor, String submission);
}
