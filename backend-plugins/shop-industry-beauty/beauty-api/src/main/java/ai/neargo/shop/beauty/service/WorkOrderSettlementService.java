package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.PreviewWorkOrderSettlementReq;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementPreviewVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementVO;

/** Observes the original authorized bill; no settlement writer or new financial ledger. */
public interface WorkOrderSettlementService {
    WorkOrderSettlementVO get(String entity, String store, String actor, String workOrderNo);
    WorkOrderSettlementPreviewVO preview(
            String entity, String store, String actor, String workOrderNo,
            PreviewWorkOrderSettlementReq request);
}
