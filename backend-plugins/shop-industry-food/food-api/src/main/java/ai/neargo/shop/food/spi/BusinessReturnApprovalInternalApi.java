package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApprovalVO;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApproveReq;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ReplacementReq;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface BusinessReturnApprovalInternalApi {
    record Approve(
            String entity,
            String store,
            String actor,
            String returnNo,
            ApproveReq request,
            String key) {}

    record Query(String entity, String store, String actor, String returnNo) {}

    record Replacement(
            String entity,
            String store,
            String actor,
            String returnNo,
            ReplacementReq request,
            String key) {}

    @PostExchange("/internal/food/return-approval/approve")
    ApprovalVO approve(@RequestBody Approve c);

    @PostExchange("/internal/food/return-approval/get")
    ApprovalVO get(@RequestBody Query c);

    @PostExchange("/internal/food/return-approval/replacement")
    ApprovalVO replacement(@RequestBody Replacement c);
}
