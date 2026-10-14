package ai.neargo.shop.inventory.service;

import java.time.LocalDate;

/**
 * 日快照。**派生数据，删光重跑一遍就回来** —— 重跑是它的正常工作方式，不是异常处置。
 *
 * <p>它解决的不是「聚合太慢」，是**区间查询的劣化**：
 * 「今年每个月的进销存」在流水上是一次全量扫描，在快照上是十二行。
 * 今天的量还用不上它，但它一旦要用就来不及现建 —— 历史流水可以重放，
 * 而重放一年的流水要一个专门的窗口。
 */
public interface InventorySnapshotService {

    /**
     * 结算某一天。**幂等**：先删当天的行再写，重跑结果逐字相同。
     *
     * @return 写了几行
     */
    int buildFor(LocalDate date);
}
