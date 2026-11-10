package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.dto.WorkOrderSummaryVO;
import ai.neargo.shop.common.PageData;

/** Authorized store queue, separate from opening an original and from SELF task reads. */
public interface WorkOrderWorkbenchService {
    /** Uses current live booking-read authority; neither store query parameters nor stored actors grant it. */
    PageData<WorkOrderSummaryVO> list(
            String entityNo, String storeNo, String actorNo, WorkOrderQueryReq query);
}
