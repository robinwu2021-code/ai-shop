package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessOperationsContracts;
import ai.neargo.shop.food.service.BusinessOperationsService;
import ai.neargo.shop.food.spi.BusinessOperationsInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.List;
import java.util.function.Supplier;

public class RemoteBusinessOperationsService implements BusinessOperationsService {
    private final BusinessOperationsInternalApi api;

    public RemoteBusinessOperationsService(BusinessOperationsInternalApi api) {
        this.api = api;
    }

    @Override
    public BusinessOperationsContracts.Evidence evidence(
            String entity, String store, BusinessOperationsContracts.Source source) {
        return invoke(
                () ->
                        api.businessOperationsEvidence(
                                new BusinessOperationsInternalApi.BusinessOperationsEvidenceCommand(
                                        entity, store, source)));
    }

    @Override
    public BusinessOperationsContracts.HandoverVO propose(
            String entity,
            String store,
            String actor,
            BusinessOperationsContracts.HandoverReq request,
            String key) {
        return invoke(
                () ->
                        api.businessOperationsPropose(
                                new BusinessOperationsInternalApi.BusinessOperationsProposeCommand(
                                        entity, store, actor, request, key)));
    }

    @Override
    public BusinessOperationsContracts.HandoverVO handover(
            String entity, String store, String handover) {
        return invoke(
                () ->
                        api.businessOperationsHandover(
                                new BusinessOperationsInternalApi.BusinessOperationsHandoverCommand(
                                        entity, store, handover)));
    }

    @Override
    public BusinessOperationsContracts.HandoverVO accept(
            String entity,
            String store,
            String actor,
            String handover,
            BusinessOperationsContracts.DecisionReq request,
            String key) {
        return invoke(
                () ->
                        api.businessOperationsAccept(
                                new BusinessOperationsInternalApi.BusinessOperationsAcceptCommand(
                                        entity, store, actor, handover, request, key)));
    }

    @Override
    public BusinessOperationsContracts.HandoverVO cancel(
            String entity,
            String store,
            String actor,
            String handover,
            BusinessOperationsContracts.DecisionReq request,
            String key) {
        return invoke(
                () ->
                        api.businessOperationsCancel(
                                new BusinessOperationsInternalApi.BusinessOperationsCancelCommand(
                                        entity, store, actor, handover, request, key)));
    }

    @Override
    public BusinessOperationsContracts.CaseVO openCase(
            String entity,
            String store,
            String actor,
            BusinessOperationsContracts.CaseReq request,
            String key) {
        return invoke(
                () ->
                        api.businessOperationsOpenCase(
                                new BusinessOperationsInternalApi.BusinessOperationsOpenCaseCommand(
                                        entity, store, actor, request, key)));
    }

    @Override
    public List<BusinessOperationsContracts.CaseVO> cases(
            String entity, String store, String status) {
        return invoke(
                () ->
                        api.businessOperationsCases(
                                new BusinessOperationsInternalApi.BusinessOperationsCasesCommand(
                                        entity, store, status)));
    }

    @Override
    public BusinessOperationsContracts.CaseVO recoverCase(
            String entity,
            String store,
            String actor,
            String caseNo,
            BusinessOperationsContracts.DecisionReq request,
            String key) {
        return invoke(
                () ->
                        api.businessOperationsRecoverCase(
                                new BusinessOperationsInternalApi
                                        .BusinessOperationsRecoverCaseCommand(
                                        entity, store, actor, caseNo, request, key)));
    }

    @Override
    public BusinessOperationsContracts.CaseVO resolveCase(
            String entity,
            String store,
            String actor,
            String caseNo,
            BusinessOperationsContracts.DecisionReq request,
            String key) {
        return invoke(
                () ->
                        api.businessOperationsResolveCase(
                                new BusinessOperationsInternalApi
                                        .BusinessOperationsResolveCaseCommand(
                                        entity, store, actor, caseNo, request, key)));
    }

    private static <T> T invoke(Supplier<T> call) {
        try {
            T result = ServiceCalls.call(ServiceName.FOOD, call);
            if (result == null) throw new IllegalStateException("Missing FOOD operations receipt");
            return result;
        } catch (ServiceCallException failure) {
            throw switch (failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                default -> failure;
            };
        }
    }
}
