package ai.neargo.shop.inventory.service;

import ai.neargo.shop.inventory.dto.InventoryVOs.BalanceVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.ItemDetailVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.LedgerPageVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.SummaryVO;

import java.util.List;

/** 读侧。**没有任何写方法** —— 改余额只有过账一条路。 */
public interface StockQueryService {

    SummaryVO summary(String ownerId, String locationId);

    /**
     * @param filter {@code todo}（缺货或滞销，默认）/ {@code all} / {@code reserved}
     *               「要处理」排最前：一屏两百个 SKU，按字母排等于没排 ——
     *               商家打开这一页只为两件事，哪件断了、哪件压着
     */
    List<BalanceVO> balances(String ownerId, String locationId, String filter, int limit);

    ItemDetailVO itemDetail(String ownerId, String itemId);

    /** 变动明细。游标分页，{@code cursor} 传上一页最后一行的 {@code id}。 */
    LedgerPageVO ledger(String ownerId, String itemId, String locationId, Long cursor, int size);
}
