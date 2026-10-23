package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.MyWorkOrderVO;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
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
}
