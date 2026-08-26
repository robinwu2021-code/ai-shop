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
    public record BalanceVO(String itemId, String name, String specText, String baseUom,
                            int onHand, int reserved, int available,
                            Integer safetyStock, LocalDateTime lastMovedAt, List<String> flags) {
    }

    /** 某个物料在各库位的分布 + 外部身份。 */
    public record ItemDetailVO(String itemId, String name, String specText, String baseUom,
                               String barcode, String itemCode,
                               int onHand, int reserved, int available,
                               List<LocationQty> byLocation) {
    }

    public record LocationQty(String locationId, String locationName, int onHand) {
    }

    /** 流水行。**游标是 id 不是时间** —— 时钟回拨会让时间游标漏行，而漏的那几行不会报错。 */
    public record LedgerVO(long id, String docKind, String docNo, String reasonCode,
                           int qtyDelta, int balanceAfter,
                           LocalDateTime occurredAt, String operator) {
    }

    public record LedgerPageVO(List<LedgerVO> entries, Long nextCursor) {
    }

    /** 库存总览的三个数。 */
    public record SummaryVO(int itemCount, int shortageCount, int staleCount) {
    }

    /** 进销存月报的五个数。界面上要能看出 {@code 期初 + 进 − 销 − 损 ± 调 = 期末}。 */
    public record MonthlyVO(String month, int opening, int purchased, int sold,
                            int lost, int adjusted, int closing, boolean balanced) {
    }

    /**
     * 单据中心的一行。四类单据长得不一样，**下发的是它们的交集** ——
     * 差异字段（供应商、原因、来源单号）都收进 {@code subtitle}，
     * 由服务端拼好：让端上按 kind 分四种拼法，那四段文案迟早各自漂。
     */
    public record DocumentVO(String kind, String docNo, String status, String subtitle,
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
     */
    public record TransferVO(String transferNo, String status,
                             String fromLocationId, String fromLocationName,
                             String toLocationId, String toLocationName,
                             LocalDateTime shippedAt, LocalDateTime receivedAt,
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