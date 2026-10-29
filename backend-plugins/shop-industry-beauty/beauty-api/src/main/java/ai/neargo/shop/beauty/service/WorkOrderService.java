package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.MyWorkOrderVO;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;

/** Opens a frozen scope and reads real local facts; execution requires later explicit source binding. */
public interface WorkOrderService {
    /** Freeze only the admitted service scope; execution needs later explicit source binding. */
    WorkOrderVO openWorkOrder(String entityNo, String currentStoreNo, String actorNo,
            OpenWorkOrderReq req, String idempotencyKey);
    /** Authorize the live store root in SQL before projecting its real task children. */
    WorkOrderDetailVO getWorkOrder(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);
    /** Consumer ownership uses canonical USER or immutable scoped guest claim receipts. */
    MyWorkOrderVO getMyWorkOrder(String userNo, String workOrderNo);

    /** Explicitly select source purchase/binding; OPEN remains a local scope draft. */
    ServiceSubmissionVO submitServices(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            String workOrderNo,
            SubmitServicesReq req,
            String idempotencyKey);

    /** Read authenticated owner money per source and currency without collecting or executing. */
    WorkOrderBillVO getBill(
            String entityNo, String currentStoreNo, String actorNo, String workOrderNo);
}
