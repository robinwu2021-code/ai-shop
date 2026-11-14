package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApprovalVO;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApproveReq;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ReplacementReq;
import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.food.spi.BusinessReturnApprovalInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

public class RemoteBusinessReturnApprovalService implements BusinessReturnApprovalService {
    private final BusinessReturnApprovalInternalApi api;

    public RemoteBusinessReturnApprovalService(BusinessReturnApprovalInternalApi api) {
        this.api = api;
    }

    public ApprovalVO approve(String e, String s, String a, String n, ApproveReq r, String k) {
        return ServiceCalls.call(
                ServiceName.FOOD,
                () -> api.approve(new BusinessReturnApprovalInternalApi.Approve(e, s, a, n, r, k)));
    }

    public ApprovalVO get(String e, String s, String a, String n) {
        return ServiceCalls.call(
                ServiceName.FOOD,
                () -> api.get(new BusinessReturnApprovalInternalApi.Query(e, s, a, n)));
    }

    public ApprovalVO linkReplacement(
            String e, String s, String a, String n, ReplacementReq r, String k) {
        return ServiceCalls.call(
                ServiceName.FOOD,
                () ->
                        api.replacement(
                                new BusinessReturnApprovalInternalApi.Replacement(
                                        e, s, a, n, r, k)));
    }

    public int recover(int limit) {
        throw new UnsupportedOperationException("Only original FOOD host performs recovery");
    }
}
