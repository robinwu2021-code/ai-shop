package ai.neargo.shop.inventory.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本域下发给端上的形状。**按用途聚合成一个文件**，与 {@code FinanceVOs} 一致 ——
 * 一类一文件会让「这一屏用哪几个 VO」要翻六个文件才看得全。
 */
public final class InventoryVOs {

    private InventoryVOs() {
    }

    /**
     * 余额行。
     *
     * @param available <b>算出来的，不落库</b>：落库就有第三个数要维护，而三个数里错一个不会报警
     * @param flags     {@code SHORTAGE} / {@code STALE}，<b>由服务端判</b> ——
     *                  前端自己算的话，「要处理」的口径会在列表页与报表页各有一份
     */
    /**
     * @param skuNo 平台商品的 SKU 号（从 {@code inv_item_ref} 的 {@code AISHOP} 一系反查）。
     *              <b>绑码要它</b>：条码的真源是 {@code prd_sku.barcode}，那是商品域的列，
     *              而本域的 {@code itemId} 在那边不存在。
     *              <p>可空 —— 没有映射的物料（独立交付形态下的自有主数据）绑不了码，
     *              端上据此把绑码那一步跳过，而不是拿 {@code itemId} 冒充
     */
    public record BalanceVO(String itemId, String skuNo, String name, String specText,
                            String baseUom, int onHand, int reserved, int available,
                            Integer safetyStock, LocalDateTime lastMovedAt, List<String> flags) {
    }

    /**
     * 某个物料在各库位的分布 + 外部身份。
     *
     * @param safetyStock <b>物料上的默认阈值</b>，恒非 null（那一列 NOT NULL，默认 0）。
     *                    {@code 0} 的意思是<b>不预警</b>，不是「低于 0 才报」——
     *                    端上要把它显示成「不预警」而不是「0」
     */
    public record ItemDetailVO(String itemId, String name, String specText, String baseUom,
                               String barcode, String itemCode,
                               int onHand, int reserved, int available,
                               Integer safetyStock,
                               List<LocationQty> byLocation) {
    }

    /**
     * @param safetyStock <b>本库位的覆盖值</b>，{@code null} = 跟随物料默认。
     *                    与「显式设成 0」是两件事：前者跟着默认值走，后者是这个库位不预警。
     *                    合成一个数的话，撤掉覆盖这件事在界面上就没法表达了
     */
    public record LocationQty(String locationId, String locationName, int onHand,
                              Integer safetyStock) {
    }

    /** 流水行。**游标是 id 不是时间** —— 时钟回拨会让时间游标漏行，而漏的那几行不会报错。 */
    /**
     * 台账一行。**带上 itemId / itemName** —— 按单查时这就是「这张单动了哪几件货」，
     * 只给单号的话那一屏点进来是一列没有名字的数。
     */
    public record LedgerVO(long id, String itemId, String itemName,
                           String docKind, String docNo, String reasonCode,
                           int qtyDelta, int balanceAfter,
                           LocalDateTime occurredAt, String operator) {
    }

    public record LedgerPageVO(List<LedgerVO> entries, Long nextCursor) {
    }

    /**
     * 库存总览的四个数。
     *
     * <p>{@code inTransitCount} 数的是<b>待收货的调拨单</b>，不是件数 ——
     * 它对应的动作是「去收货」，而收货是<b>按单</b>做的。给件数的话，
     * 看见 20 也不知道该点进哪一张单。
     *
     * <p>在途这一档此前没有位置：调拨发出后货既不在 A 也不在 B，
     * 而总览三个数里没有它，只有翻单据才看得到 —— 它却是这四个数里
     * <b>唯一一个有人在等</b>的。
     *
     * <p>{@code openCountNo} 是<b>还开着的那张盘点单的单号</b>，没有就是 null。
     * 给单号不给个数，理由与上面那条相同：**数出来的东西要点得进去**。
     * 工作台的「继续盘点」要带着它跳（`stock-check?no=…`）——
     * 不带的话那一页会开一张<b>新的</b>盘点单，而按钮上写着「继续」。
     *
     * <p>有多张开着时给<b>最近的一张</b>。这不是随手选的：盘点是当场做的事，
     * 手上那张一定是刚开的；给最早的一张会让人回到一张已经忘了的单。
     */
    public record SummaryVO(int itemCount, int shortageCount, int staleCount,
                            int inTransitCount, String openCountNo) {
    }

    /**
     * 进销存月报。件数那几个要能在界面上看出 {@code 期初 + 进 − 销 − 损 ± 调 = 期末}。
     *
     * <h2>为什么没有毛利</h2>
     * 毛利 = 收入 − 成本，而<b>收入不在这个域</b>：出库单只带成本、不带售价
     *（同一件货不同渠道价不一样，写进来就有了第二个真源）。
     * 硬凑一个「销量 × 当前售价」出来，它会在促销、多渠道、改价之后统统对不上 ——
     * 而毛利恰恰是商家会拿去报税的那个数。
     *
     * <p>能诚实给的是<b>销货成本</b>：台账每一行都带 {@code unit_cost_minor}，
     * 按笔累加是这个域自己的真源。毛利要由**知道收入的那一侧**用它减出来。
     *
     * @param soldCostMinor 本月销售出库的成本合计（分）。<b>按每一笔当时的单位成本累加</b>，
     *                      不是「销量 × 当前成本价」—— 后者在进价波动时会把上个月的账算错
     * @param lostCostMinor 本月报损 + 盘亏的成本合计（分）。它是「这个月亏了多少钱」那个数
     */
    public record MonthlyVO(String month, int opening, int purchased, int sold,
                            int lost, int adjusted, int closing, boolean balanced,
                            long soldCostMinor, long lostCostMinor) {
    }

    /**
     * 单据中心的一行。四类单据长得不一样，**下发的是它们的交集** ——
     * 差异字段（供应商、去向、来源单号）都收进 {@code subtitle}，由服务端拼好：
     * 让端上按 kind 分四种拼法，那四段文案迟早各自漂。
     *
     * <h2>但取值域不能拼进 subtitle</h2>
     *
     * <p><b>2026-09-02 修</b>：此前 {@code subtitle} 里混着两种东西 ——
     * 入库的 {@code source_type}、出库的 {@code purpose} 是<b>裸枚举</b>
     *（商家看到的是 {@code PURCHASE} / {@code SCRAP}），
     * 而盘点与调拨那两行是<b>硬编码的中文</b>（阿语商家看到的是中文）。
     * 两种坏法都藏在同一个字段里，且 mock 的种子手写成中文，替身上一处都看不出来。
     *
     * <p>所以码走 {@code label}、文案回端上，自由文本仍走 {@code subtitle}。
     * <b>这不违反上面那条设计</b>：端上多的是一次 i18n 查表，不是四种拼法。
     *
     * @param label    取值域码：{@code PURCHASE} / {@code SCRAP} / {@code RETURN_SUPPLIER} /
     *                 {@code COUNT} / {@code TRANSFER} …… 端上用它查文案。可空
     * @param subtitle <b>只放自由文本</b>：供应商名、去向名、订单号、库位名。
     *                 再往里塞枚举的话，这次修的东西下一轮就长回来了
     */
    public record DocumentVO(String kind, String docNo, String status,
                             String label, String subtitle,
                             int totalQty, LocalDateTime occurredAt, String operator) {
    }

    /** 榜单一行。 */
    /**
     * @param costAmountMinor <b>销货成本，不是销售额</b>。
     *        毛利算不出来 —— 售价在销售域，本域刻意没有它（出库单只带成本不带售价）。
     *        毛利由调用方拿订单金额减去这个数，见 {@code InventoryReportService} 类注释
     */
    public record RankVO(String itemId, String name, String specText, int qty, Long costAmountMinor) {
    }

    /**
     * 一张盘点单。<b>{@code bookQty} 是开单那一刻的快照</b> ——
     * 端上不要拿当前余额去顶替它：盘的过程中照常卖，用当前数算差异，
     * 中间卖掉的量会被算成盘亏，而那是一笔凭空出现的损失。
     */
    public record CountVO(String countNo, String status, String locationId,
                          LocalDateTime startedAt, String operator, List<CountLineVO> lines) {
    }

    /**
     * @param diffQty 实盘 − 账面。<b>没填实盘时为 null</b>，不是 0 ——
     *                0 的意思是「盘了，一件不差」，与「还没盘」是两件事
     */
    public record CountLineVO(String itemId, String name, String specText, String baseUom,
                              int bookQty, Integer countedQty, Integer diffQty, String reasonCode) {
    }

    /**
     * 一张调拨单。
     *
     * <p>行取自<b>发出那张出库单</b>（调拨不另建行表）—— 还没发出时没有行，
     * 这一点在界面上要说清楚，否则草稿态的调拨单看起来像「空单」。
     *
     * @param carrierName 承运方名字快照，可空（自己送或没记）。
     *                    <b>不下发 {@code carrierNo}</b>：端上拿它没有用处 ——
     *                    它是给报表按承运方聚合的，而单据要显示的是名字
     * @param trackingNo  运单号，可空。<b>它与 carrierName 一起构成收货方的核对依据</b> ——
     *                    只存不下发的话，那两列写进库里也没有人看得见
     */
    public record TransferVO(String transferNo, String status,
                             String fromLocationId, String fromLocationName,
                             String toLocationId, String toLocationName,
                             LocalDateTime shippedAt, LocalDateTime receivedAt,
                             String carrierName, String trackingNo,
                             int totalQty, List<TransferLineVO> lines) {
    }

    public record TransferLineVO(String itemId, String name, String specText, int qty, String uom) {
    }

    /**
     * 新建单据的返回：**一个对象，不是裸字符串**。
     *
     * <p>{@code ApiResponseWrapper} 把 {@code String} 返回<b>故意排除</b>在信封之外
     *（{@code StringHttpMessageConverter} 的经典坑），于是 {@code return docNo} 发出去的是
     * 裸串 {@code IN2026…}。而端上的 http 客户端读 {@code body.code} ——
     * 拿到裸串直接抛「响应格式不符合契约」。
     *
     * <p>症状很坏：<b>服务端把单建好了，端上报错</b>。商家会再点一次，
     * 于是一次操作留下两张草稿单，而他看到的只有两次失败。
     */
    public record DocNoVO(String no) {
    }
}