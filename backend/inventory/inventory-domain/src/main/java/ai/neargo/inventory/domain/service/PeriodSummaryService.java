package ai.neargo.inventory.domain.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 期间汇总（TDD §2.11，M5 T5.2）：给定期间与仓 / 物料，期初、按用途分类的入与出、期末 ——
 * 宿主的统一库存报表（在仓 + 在途 + 投放中）取仓内这一段。全部从只追加的流水算，可逐笔追溯。
 *
 * <p>恒等式：{@code opening + Σin − Σout = closing}（AC-12）。
 */
public interface PeriodSummaryService {

    /** @param from 含；@param to 不含；@param locationId / itemId 空 = 不过滤 */
    List<Row> summarize(String ownerId, LocalDateTime from, LocalDateTime to, String locationId, String itemId);

    record Row(String locationId, String itemId, BigDecimal opening, Map<String, BigDecimal> inByReason,
               Map<String, BigDecimal> outByReason, BigDecimal closing) {
    }
}
