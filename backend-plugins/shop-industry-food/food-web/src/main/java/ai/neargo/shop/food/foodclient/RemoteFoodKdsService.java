package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStationsReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStatusReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.StoreDeviceVO;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.spi.FoodKdsInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** 独立部署时网关侧调餐饮核心的门店设备接口；回来的设备必须属于请求的主体与门店。 */
public class RemoteFoodKdsService implements FoodKdsService {
    private final FoodKdsInternalApi api;

    public RemoteFoodKdsService(FoodKdsInternalApi api) {
        this.api = api;
    }

    @Override
    public StoreDeviceVO register(String e, String s, String a, RegisterDeviceReq r, String k) {
        var result = valid(e, s, call(() -> api.register(new FoodKdsInternalApi.Register(e, s, a, r, k))));
        if (!r.deviceType().equals(result.deviceType())) bad();
        return result;
    }

    @Override
    public StoreDeviceVO changeStatus(String e, String s, String a, String no, DeviceStatusReq r) {
        var result = valid(e, s, call(() -> api.changeStatus(new FoodKdsInternalApi.Status(e, s, a, no, r))));
        if (!no.equals(result.deviceNo()) || !r.status().equals(result.status())) bad();
        return result;
    }

    @Override
    public StoreDeviceVO assignStations(String e, String s, String a, String no, DeviceStationsReq r) {
        var result = valid(e, s, call(() -> api.assignStations(new FoodKdsInternalApi.Stations(e, s, a, no, r))));
        if (!no.equals(result.deviceNo())
                || !result.stationNos().equals(r.stationNos().stream().distinct().sorted().toList())) {
            bad();
        }
        return result;
    }

    @Override
    public List<StoreDeviceVO> list(String e, String s) {
        var result = call(() -> api.list(new FoodKdsInternalApi.Store(e, s)));
        if (result == null) bad();
        result.forEach(d -> valid(e, s, d));
        return result;
    }

    @Override
    public StoreDeviceVO authorize(String e, String s, String a, String no) {
        var result = valid(e, s, call(() -> api.authorize(new FoodKdsInternalApi.Authorize(e, s, a, no))));
        if (!no.equals(result.deviceNo()) || !"ACTIVE".equals(result.status())) bad();
        return result;
    }

    private StoreDeviceVO valid(String e, String s, StoreDeviceVO result) {
        if (result == null
                || !e.equals(result.entityNo())
                || !s.equals(result.storeNo())
                || result.deviceNo() == null
                || result.version() < 0
                || !Set.of("PAIRING", "ACTIVE", "SUSPENDED", "REVOKED").contains(result.status())) {
            bad();
        }
        return result;
    }

    private <T> T call(Supplier<T> request) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, request);
        } catch (ServiceCallException failure) {
            ErrorCode code =
                    switch (failure.statusCode()) {
                        case 400 -> ErrorCode.BAD_REQUEST;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        default -> null;
                    };
            if (code != null) {
                throw BizException.of(code);
            }
            throw failure;
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
