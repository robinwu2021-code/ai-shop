package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.BusinessTraceVO;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;

/**
 * Authorized owner observations are reported per source and currency with explicit incompleteness.
 */
public interface WorkOrderBillService {
    WorkOrderBillVO getBill(
            String entityNo, String currentStoreNo, String actorNo, String workOrderNo);

    BusinessTraceVO getTrace(
            String entityNo, String currentStoreNo, String actorNo, String businessNo);
}
