package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApprovalVO;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApproveReq;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ReplacementReq;

public interface BusinessReturnApprovalService {
    ApprovalVO approve(
            String entity,
            String store,
            String actor,
            String returnNo,
            ApproveReq request,
            String key);

    ApprovalVO get(String entity, String store, String actor, String returnNo);

    ApprovalVO linkReplacement(
            String entity,
            String store,
            String actor,
            String returnNo,
            ReplacementReq request,
            String key);

    int recover(int limit);
}
