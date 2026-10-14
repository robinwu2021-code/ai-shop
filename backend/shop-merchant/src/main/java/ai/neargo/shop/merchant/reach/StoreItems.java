package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchServiceAreaCell;

import java.util.List;

/**
 * 一家店的全部范围项与网格行（ADR-034）。内存查找器 {@link InMemoryHitFinder} 的输入：
 * 反向展开（这家店覆盖哪些小区）、保存前预览都从这里算，不走库。
 */
public record StoreItems(String storeNo, List<MchServiceArea> areas, List<MchServiceAreaCell> cells) {

    public StoreItems {
        areas = areas == null ? List.of() : List.copyOf(areas);
        cells = cells == null ? List.of() : List.copyOf(cells);
    }
}
