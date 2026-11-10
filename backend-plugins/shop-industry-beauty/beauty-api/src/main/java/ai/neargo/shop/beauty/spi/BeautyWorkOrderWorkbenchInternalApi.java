package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.dto.WorkOrderSummaryVO;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Fixed bound read route; actor/entity/store come from the authenticated delegation. */
public interface BeautyWorkOrderWorkbenchInternalApi {
    String ENDPOINT = "BEAUTY_WORK_ORDER_WORKBENCH";
    String ACTION = "LIST_WORK_ORDERS";
    String PATH = "/internal/beauty/work-orders/queries";

    @PostExchange(PATH)
    InternalReply<PageData<WorkOrderSummaryVO>> list(
            @RequestBody InternalCall<WorkOrderQueryReq> request);
}
