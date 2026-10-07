package ai.neargo.shop.product.port;

import ai.neargo.shop.product.service.impl.GoodsVisibility;
import ai.neargo.shop.spi.product.SupplyStatsPort;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 供给统计：每家 ACTIVE 门店算一次可达，再按它在卖的货聚合（{@link GoodsVisibility#supplyByCommunity}）。
 *
 * <p>与买家列表同一个判定 —— 此前读的是社区池全表，池漏了重建的地方，这张表跟着错。
 * 只在运营打开位置分布那一屏时调用。
 */
@Component
public class SupplyStatsPortImpl implements SupplyStatsPort {

    private final GoodsVisibility visibility;

    public SupplyStatsPortImpl(GoodsVisibility visibility) {
        this.visibility = visibility;
    }

    @Override
    public Map<String, SupplyStat> byCommunity() {
        Map<String, SupplyStat> out = new HashMap<>();
        visibility.supplyByCommunity().forEach((no, s) -> out.put(no, new SupplyStat(s.merchants(), s.goods())));
        return out;
    }
}
