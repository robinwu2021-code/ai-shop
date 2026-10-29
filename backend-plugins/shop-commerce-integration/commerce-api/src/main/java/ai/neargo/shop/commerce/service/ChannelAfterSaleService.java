package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.AfterSaleContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.common.PageData;

public interface ChannelAfterSaleService {
    PageData<AfterSaleContracts.Link> listCases(
            String entity, String store, AfterSaleContracts.Query query);

    AfterSaleContracts.Link getCase(String entity, String store, String caseNo);

    OperationVO applyDecision(
            String entity,
            String store,
            String actor,
            String caseNo,
            AfterSaleContracts.ApplyDecision request,
            String idempotencyKey);
}
