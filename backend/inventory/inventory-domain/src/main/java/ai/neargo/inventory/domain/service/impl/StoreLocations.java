package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * 门店号 → 门店库位（没有就建）。商城内嵌（{@code InventoryAclServiceImpl#locationIdOf}）与
 * 独立服务的商家后台接口（{@code HostReferenceService#storeLocationOf}）共用这一份，两边建出来的库位一模一样。
 */
final class StoreLocations {

    private StoreLocations() {
    }

    static String ensure(LocationMapper locationMapper, LocationService locations, String ownerId, String storeNo) {
        InvLocation row =
                locationMapper.selectOne(
                        Wrappers.<InvLocation>lambdaQuery()
                                .eq(InvLocation::getOwnerId, ownerId)
                                .eq(InvLocation::getExternalRef, storeNo));
        if (row != null) {
            return row.getLocationId();
        }
        InvLocation created = new InvLocation();
        created.setLocationId(InvKeys.next(InvKeys.LOCATION));
        created.setOwnerId(ownerId);
        created.setName(storeNo);
        created.setKind(InvEnums.LocationKind.STORE);
        created.setExternalRef(storeNo);
        created.setIsDefault(0);
        /*
         * **新门店默认从主体默认仓发货。**
         *
         * 不设的话 `resolveStockLocation` 会解析到门店自己这个**刚建出来的空库位**，
         * 于是商家点进「库存」看到的是一片空 —— 而货就在默认仓里，一件没少。
         * 2026-08-27 线上实测：两个默认仓 204 + 2 件，门店库位 0 条余额。
         *
         * 这不是权宜之计，是与平台侧语义对齐：`StockPortImpl` 按 SKU 判定 ——
         * 该 SKU 一行门店库存都没有时就是主体级，**每个门店都从主体池卖**。
         * 门店确实是从那个仓取货的。
         *
         * 商家真要给这家店单独备货时，在「仓库与库位」里改发货源即可（setSource）。
         */
        created.setSourceLocationId(locations.defaultLocation(ownerId));
        created.setStatus(InvEnums.MasterStatus.ACTIVE);
        created.setCreatedBy("SYSTEM");
        locationMapper.insert(created);
        return created.getLocationId();
    }
}
