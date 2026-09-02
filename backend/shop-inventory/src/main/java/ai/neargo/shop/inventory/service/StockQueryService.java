package ai.neargo.shop.inventory.service;

import ai.neargo.shop.inventory.dto.InventoryVOs.BalanceVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.CrossStoreVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.ItemDetailVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.LedgerPageVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.DocumentVO;
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

    /**
     * 跨店总览：一件货一行，把它在<b>全部库位</b>的分布收在一起。
     *
     * <p>多门店商家在 {@link #balances} 里看到的是同一件货重复 N 行（一个库位一行），
     * 「哪家店断了」得自己在脑子里合并。这一条替他合并。
     *
     * @param filter {@code shortage}（默认，只给至少一个库位缺货的）/ {@code all}。
     *               默认给缺货是因为这一屏的用途就是补货：全给的话
     *               209 件里有 200 件是「都还有」，那 9 件反而看不见了
     */
    List<CrossStoreVO> crossStore(String ownerId, String filter, int limit);

    /**
     * 可挑的货 —— **从物料出发，不从余额出发**。
     *
     * <p><b>为什么要与 {@link #balances} 分开</b>：那一条回答的是「我有多少」，
     * 读 `inv_stock_balance`；而挑货问的是「哪件货」。余额行是**按需建**的
     *（见 `StockPostingServiceImpl.ensureBalanceRow`），一件从没进过货的物料压根没有那一行 ——
     * 于是它在挑货弹层里不存在，商家<b>没法给它记第一笔进货</b>。
     * 2026-08-28 线上就有一件这样的：207 个物料、206 行余额。
     *
     * <p>没有余额行的返回 0，不是不返回。
     *
     * @param keyword 按名称或规格模糊匹配，null 或空则不筛
     */
    List<BalanceVO> pickableItems(String ownerId, String locationId, String keyword, int limit);

    ItemDetailVO itemDetail(String ownerId, String itemId);

    /**
     * 按条码找货。<b>扫码那一步的服务端</b>。
     *
     * <p>读的是 {@code inv_item_ref}（{@code ref_system = BARCODE}）——
     * 条码的真源是平台商品的 {@code prd_sku.barcode}，本域只有一份投影。
     * <b>不新增 {@code inv_item.barcode}</b>：条码是商品的属性不是库存的属性，
     * 放两处一定会分岔。
     *
     * @return 命中的那件货；<b>没命中回 {@code null} 而不是抛</b> ——
     *         「这个码还没绑过」是这个功能的<b>常态</b>（线上 {@code prd_sku.barcode} 是 0/396），
     *         把常态做成异常，端上就要靠 catch 走正常流程，而错误提示会在日志里刷屏
     */
    BalanceVO byBarcode(String ownerId, String locationId, String code);

    /** 变动明细。游标分页，{@code cursor} 传上一页最后一行的 {@code id}。 */
    /**
     * 台账。**两种问法共用一条**：给 itemId 是「这件货怎么变的」，
     * 给 docNo 是「这张单动了哪几件货」—— 台账本来就是单据的行。
     */
    LedgerPageVO ledger(String ownerId, String itemId, String docNo, String locationId,
                        Long cursor, int size);

    /**
     * 单据中心：入库 / 出库 / 盘点 / 调拨四类**合成一个列表**。
     *
     * <p>拆成四个端点的话，界面要自己合并与排序 —— 而它本来就是一个列表。
     *
     * @param kind {@code IN} / {@code OUT} / {@code COUNT} / {@code TRANSFER}，空 = 全部
     */
    List<DocumentVO> documents(String ownerId, String locationId, String kind, String docNo, int limit);
}
