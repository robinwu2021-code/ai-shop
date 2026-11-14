package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ReservationContracts.ArriveReq;
import ai.neargo.shop.food.dto.ReservationContracts.ChangeReq;
import ai.neargo.shop.food.dto.ReservationContracts.CreateReq;
import ai.neargo.shop.food.dto.ReservationContracts.DetailVO;
import ai.neargo.shop.food.dto.ReservationContracts.TransitionReq;

import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.ReservationVO;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.spi.ReservationInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Original platform transport preserves reservation conflicts and business HTTP errors. */
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
    @Override public DetailVO get(String entity,String store,String number) {
        return invoke(() -> api.get(new ReservationInternalApi.NumberLookup(entity,store,number)));
    }
    @Override public DetailVO create(String entity,String store,String actor,CreateReq request,String key) {
        return invoke(() -> api.create(new ReservationInternalApi.CreateCommand(entity,store,actor,request,key)));
    }
    @Override public DetailVO change(String entity,String store,String actor,String number,ChangeReq request,String key) {
        return invoke(() -> api.change(new ReservationInternalApi.ChangeCommand(entity,store,actor,number,request,key)));
    }
    @Override public DetailVO cancel(String entity,String store,String actor,String number,TransitionReq request,String key) {
        return invoke(() -> api.cancel(new ReservationInternalApi.TransitionCommand(entity,store,actor,number,request,key)));
    }
    @Override public DetailVO noShow(String entity,String store,String actor,String number,TransitionReq request,String key) {
        return invoke(() -> api.noShow(new ReservationInternalApi.TransitionCommand(entity,store,actor,number,request,key)));
    }
    @Override public DetailVO arrive(String entity,String store,String actor,String number,ArriveReq request,String key) {
        return invoke(() -> api.arrive(new ReservationInternalApi.ArriveCommand(entity,store,actor,number,request,key)));
    }
}
