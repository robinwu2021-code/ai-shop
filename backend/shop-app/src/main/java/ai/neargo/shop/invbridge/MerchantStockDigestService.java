package ai.neargo.shop.invbridge;

import java.time.LocalDateTime;

/**
 * 单商家进销存概况（M5）。
 *
 * <p>缺口清单里进销存④ 的那一条：「这家<b>记了多少笔、最近一笔什么时候</b>」——
 * 库存流水那一页能按商家翻明细，但翻之前答不出「这家到底在不在用」。
 * 而 2026-09-03 线上是 6 家商家里只有 2 家真在记账，
 * 那 4 家在流水页上的样子与「今天恰好没动」一模一样。
 *
 * <p><b>不含「账实差」。</b>那是平台侧 vs 进销存侧的逐条比对，
 * 按商家拆要重扫一遍全平台的 SKU —— 代价与价值不成比例，
 * 而「库存对差」那一页本来就是按商家列出差异行的。
 * 一个数放在两处算，两处迟早给出两种说法。
 */
public interface MerchantStockDigestService {

    /** 这家还没搬进进销存时返回 null —— 那本身就是答案，不是「零笔」 */
    Digest of(String entityNo);

    /**
     * @param itemCount     建了几条账（物料）
     * @param shortageCount 低于安全库存的
     * @param staleCount    长期未动销的
     * @param ledgerCount   累计流水笔数。<b>0 = 建了账但一次都没记过</b>
     * @param lastLedgerAt  最近一笔的时间。null = 从没记过
     * @param openCountNo   还开着的盘点单号，没有则 null
     */
    record Digest(String entityNo, String ownerId,
                  int itemCount, int shortageCount, int staleCount,
                  long ledgerCount, LocalDateTime lastLedgerAt, String openCountNo) {
    }
}
