package ai.neargo.shop.inventory.service;

import ai.neargo.shop.inventory.entity.InvLocation;

import java.util.List;

/** 库位：门店 / 仓 / 在途 / 虚拟。仓不是一种门店，是一种库位。 */
public interface LocationService {

    List<InvLocation> list(String ownerId);

    /** 建仓。**不对 C 端露出** —— 否则买家会导航到一个储藏间。 */
    String createWarehouse(String ownerId, String name, String operator);

    /**
     * 设发货源。
     *
     * @throws ai.neargo.shop.common.BizException {@code BAD_REQUEST} —— 目标自己也指着别处时。
     *         <b>不允许链式</b>（A→B→C）：第一个后果是环，第二个是没人说得清货到底从哪出
     */
    void setSource(String ownerId, String locationId, String sourceLocationId, String operator);

    /**
     * 解析「这一单从哪个库位扣」：设了发货源就扣源仓的，否则扣自己的。
     * <b>解析发生在服务端</b> —— 前端只知道门店，不该知道有两套 ID。
     */
    String resolveStockLocation(String ownerId, String locationId);

    /** 取（必要时建）本业主的在途库位。每业主一条，不可删、不可直接出入库。 */
    String transitLocation(String ownerId);

    /** 取（必要时建）本业主的默认库位。存量「主体级库存」的落点。 */
    String defaultLocation(String ownerId);
}
