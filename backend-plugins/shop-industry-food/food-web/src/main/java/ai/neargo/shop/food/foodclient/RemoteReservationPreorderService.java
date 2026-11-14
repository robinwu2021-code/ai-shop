package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.dto.ReservationPreorderContracts;
import ai.neargo.shop.food.service.ReservationPreorderService;
import ai.neargo.shop.food.spi.BusinessOperationsInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

public class RemoteReservationPreorderService implements ReservationPreorderService {
    private final BusinessOperationsInternalApi api;

    public RemoteReservationPreorderService(BusinessOperationsInternalApi api) {
        this.api = api;
    }

    @Override
    public ReservationPreorderContracts.PreorderVO save(
            String entity,
            String store,
            String actor,
            String reservation,
            ReservationPreorderContracts.SaveReq request,
            String key) {
        return invoke(
                () ->
                        api.reservationPreorderSave(
                                new BusinessOperationsInternalApi.ReservationPreorderSaveCommand(
                                        entity, store, actor, reservation, request, key)));
    }

    @Override
    public ReservationPreorderContracts.PreorderVO get(
            String entity, String store, String reservation, String preorder) {
        return invoke(
                () ->
                        api.reservationPreorderGet(
                                new BusinessOperationsInternalApi.ReservationPreorderGetCommand(
                                        entity, store, reservation, preorder)));
    }

    @Override
    public CartResultVO prepareCart(
            String entity,
            String store,
            String actor,
            String reservation,
            String preorder,
            long version,
            String key) {
        return invoke(
                () ->
                        api.reservationPreorderPrepareCart(
                                new BusinessOperationsInternalApi
                                        .ReservationPreorderPrepareCartCommand(
                                        entity,
                                        store,
                                        actor,
                                        reservation,
                                        preorder,
                                        version,
                                        key)));
    }

    @Override
    public DiningOrderBatchVO convert(
            String entity,
            String store,
            String actor,
            String reservation,
            String preorder,
            ReservationPreorderContracts.ConvertReq request,
            String key) {
        return invoke(
                () ->
                        api.reservationPreorderConvert(
                                new BusinessOperationsInternalApi.ReservationPreorderConvertCommand(
                                        entity,
                                        store,
                                        actor,
                                        reservation,
                                        preorder,
                                        request,
                                        key)));
    }

    @Override
    public ReservationPreorderContracts.PreorderVO cancel(
            String entity,
            String store,
            String actor,
            String reservation,
            String preorder,
            long version,
            String reason,
            String key) {
        return invoke(
                () ->
                        api.reservationPreorderCancel(
                                new BusinessOperationsInternalApi.ReservationPreorderCancelCommand(
                                        entity,
                                        store,
                                        actor,
                                        reservation,
                                        preorder,
                                        version,
                                        reason,
                                        key)));
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
