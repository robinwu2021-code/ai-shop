package ai.neargo.shop.merchant.reach;

import java.util.Map;
import java.util.Set;

/**
 * 全平台门店属性的一份快照（ADR-034）。
 *
 * <p>{@link #unlimitedStores} 单独拎出来是因为「不限」的店<b>不会出现在命中查询结果里</b>
 * （它没有可点查的 ref_code / cell_id），必须由快照补成候选 —— 漏了这一份，
 * 显式选了「全平台不限」的商家对谁都不可见，而那恰好是存量回填出来的那一批。
 */
public record ReachSnapshot(Map<String, StoreMeta> stores, Set<String> unlimitedStores) {

    public ReachSnapshot {
        stores = stores == null ? Map.of() : Map.copyOf(stores);
        unlimitedStores = unlimitedStores == null ? Set.of() : Set.copyOf(unlimitedStores);
    }

    public StoreMeta meta(String storeNo) {
        return stores.get(storeNo);
    }
}
