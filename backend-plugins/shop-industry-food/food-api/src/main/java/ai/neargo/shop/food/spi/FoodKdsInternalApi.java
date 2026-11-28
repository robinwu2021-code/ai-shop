package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStationsReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStatusReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.StoreDeviceVO;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/** 门店设备与厨显工位的内部接口（独立部署时网关侧调餐饮核心，TDD-餐饮域迁移新库 §2.8 D-5）。 */
public interface FoodKdsInternalApi {
    String ROOT = "/internal/food/kds";

    record Register(String entityNo, String storeNo, String actorNo, RegisterDeviceReq definition, String key) {}

    record Status(String entityNo, String storeNo, String actorNo, String deviceNo, DeviceStatusReq definition) {}

    record Stations(String entityNo, String storeNo, String actorNo, String deviceNo, DeviceStationsReq definition) {}

    record Store(String entityNo, String storeNo) {}

    record Authorize(String entityNo, String storeNo, String actorNo, String deviceNo) {}

    @PostExchange(ROOT + "/register")
    StoreDeviceVO register(@RequestBody Register request);

    @PostExchange(ROOT + "/status")
    StoreDeviceVO changeStatus(@RequestBody Status request);

    @PostExchange(ROOT + "/stations")
    StoreDeviceVO assignStations(@RequestBody Stations request);

    @PostExchange(ROOT + "/list")
    List<StoreDeviceVO> list(@RequestBody Store request);

    @PostExchange(ROOT + "/authorize")
    StoreDeviceVO authorize(@RequestBody Authorize request);
}
