package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStationsReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.DeviceStatusReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.RegisterDeviceReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.StoreDeviceVO;

import java.util.List;

/**
 * 门店设备与厨显工位（TDD-餐饮域迁移新库 §2.8 D-5）。屏用店员账号登录；设备只需在册、在用、类型对。
 */
public interface FoodKdsService {
    /** 登记设备；同一请求键重放得到同一台设备（设备号由请求键派生），同键异参冲突。 */
    StoreDeviceVO register(String entity, String store, String actor, RegisterDeviceReq request, String key);

    /** 停用或恢复；已是目标状态按重放返回。 */
    StoreDeviceVO changeStatus(String entity, String store, String actor, String device, DeviceStatusReq request);

    /** 设定厨显看的工位；工位须是本店的。 */
    StoreDeviceVO assignStations(String entity, String store, String actor, String device, DeviceStationsReq request);

    /** 本店设备，含已停用的。 */
    List<StoreDeviceVO> list(String entity, String store);

    /**
     * 核验连上来的屏：本店、在册、在用，且是厨显（已设定工位）或叫号屏。返回它的类型与工位。
     */
    StoreDeviceVO authorize(String entity, String store, String actor, String device);
}
