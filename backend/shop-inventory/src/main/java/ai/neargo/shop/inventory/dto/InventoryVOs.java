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

    /** 榜单一行。 */
    public record RankVO(String itemId, String name, String specText, int qty, Long amountMinor) {
    }
}
