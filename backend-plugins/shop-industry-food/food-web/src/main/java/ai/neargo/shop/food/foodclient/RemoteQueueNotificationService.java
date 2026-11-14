package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.service.QueueNotificationService;
import ai.neargo.shop.food.spi.BusinessOperationsInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.List;
import java.util.function.Supplier;

public class RemoteQueueNotificationService implements QueueNotificationService {
    private final BusinessOperationsInternalApi api;

    public RemoteQueueNotificationService(BusinessOperationsInternalApi api) {
        this.api = api;
    }

    @Override
    public List<QueueNotificationService.NoticeVO> list(
            String entity, String store, String ticket) {
        return invoke(
                () ->
                        api.queueNotificationList(
                                new BusinessOperationsInternalApi.QueueNotificationListCommand(
                                        entity, store, ticket)));
    }

    @Override
    public QueueNotificationService.NoticeVO recover(
            String entity, String store, String actor, String notice, String key) {
        return invoke(
                () ->
                        api.queueNotificationRecover(
                                new BusinessOperationsInternalApi.QueueNotificationRecoverCommand(
                                        entity, store, actor, notice, key)));
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
