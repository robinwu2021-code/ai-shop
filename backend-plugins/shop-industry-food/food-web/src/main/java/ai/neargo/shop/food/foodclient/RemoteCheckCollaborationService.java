package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CheckCollaborationContracts;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.service.CheckCollaborationService;
import ai.neargo.shop.food.spi.BusinessOperationsInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

public class RemoteCheckCollaborationService implements CheckCollaborationService {
    private final BusinessOperationsInternalApi api;

    public RemoteCheckCollaborationService(BusinessOperationsInternalApi api) {
        this.api = api;
    }

    @Override
    public CheckCollaborationContracts.ReceivablePreview previewReceivable(
            String entity, String store, String check) {
        return invoke(
                () ->
                        api.checkCollaborationPreviewReceivable(
                                new BusinessOperationsInternalApi
                                        .CheckCollaborationPreviewReceivableCommand(
                                        entity, store, check)));
    }

    @Override
    public String sourceHash(String entity, String store, String check) {
        return invoke(
                () ->
                        api.checkCollaborationSourceHash(
                                new BusinessOperationsInternalApi
                                        .CheckCollaborationSourceHashCommand(
                                        entity, store, check)));
    }

    @Override
    public OperationVO releaseService(
            String entity,
            String store,
            String actor,
            String check,
            long version,
            String reason,
            String key) {
        return invoke(
                () ->
                        api.checkCollaborationReleaseService(
                                new BusinessOperationsInternalApi
                                        .CheckCollaborationReleaseServiceCommand(
                                        entity, store, actor, check, version, reason, key)));
    }

    @Override
    public String currentLineOwner(String entity, String store, String foodLineNo) {
        return invoke(
                () ->
                        api.checkCollaborationCurrentLineOwner(
                                new BusinessOperationsInternalApi
                                        .CheckCollaborationCurrentLineOwnerCommand(
                                        entity, store, foodLineNo)));
    }

    @Override
    public CheckCollaborationContracts.MoveVO moveBatches(
            String entity,
            String store,
            String actor,
            CheckCollaborationContracts.MoveReq request,
            String key) {
        return invoke(
                () ->
                        api.checkCollaborationMoveBatches(
                                new BusinessOperationsInternalApi
                                        .CheckCollaborationMoveBatchesCommand(
                                        entity, store, actor, request, key)));
    }

    @Override
    public CheckCollaborationContracts.LineMoveVO moveLines(
            String entity,
            String store,
            String actor,
            CheckCollaborationContracts.LineMoveReq request,
            String key) {
        return invoke(
                () ->
                        api.checkCollaborationMoveLines(
                                new BusinessOperationsInternalApi
                                        .CheckCollaborationMoveLinesCommand(
                                        entity, store, actor, request, key)));
    }

    @Override
    public CheckCollaborationContracts.FreezeVO freeze(
            String entity,
            String store,
            String actor,
            String check,
            CheckCollaborationContracts.FreezeReq request,
            String key) {
        return invoke(
                () ->
                        api.checkCollaborationFreeze(
                                new BusinessOperationsInternalApi.CheckCollaborationFreezeCommand(
                                        entity, store, actor, check, request, key)));
    }

    @Override
    public CheckCollaborationContracts.FreezeVO release(
            String entity,
            String store,
            String actor,
            String freeze,
            long version,
            String reason,
            String key) {
        return invoke(
                () ->
                        api.checkCollaborationRelease(
                                new BusinessOperationsInternalApi.CheckCollaborationReleaseCommand(
                                        entity, store, actor, freeze, version, reason, key)));
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
