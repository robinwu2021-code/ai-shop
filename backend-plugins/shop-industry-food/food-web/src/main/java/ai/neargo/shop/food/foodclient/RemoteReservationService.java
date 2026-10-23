package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.ReservationVO;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.spi.ReservationInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Read-only original platform transport, preserving business HTTP errors. */
public class RemoteReservationService implements ReservationService {
    private final ReservationInternalApi api;
    public RemoteReservationService(ReservationInternalApi api) { this.api=api; }
    @Override public PageData<ReservationVO> listReservations(String entity,String store,ListReservationsQuery query) {
        return invoke(() -> api.list(new ReservationInternalApi.Lookup(entity,store,query)));
    }
    private <T> T invoke(Supplier<T> call) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, call);
        } catch (ServiceCallException failure) {
            ErrorCode business = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (business != null) throw BizException.of(business);
            throw failure;
        }
    }
}
