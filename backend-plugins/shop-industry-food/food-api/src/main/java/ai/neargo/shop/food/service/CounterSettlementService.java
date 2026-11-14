package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CounterSettlementContracts.CounterSettlementVO;
import java.util.List;

/**
 * 柜台后付整桌结账（TDD-餐饮柜台后付结账 · ADR-059）：创建结算单（账单随即冻结）→ 用外部记账收款 → 确认收齐（核心把这桌的
 * 后付单一次推到已付）→ 关单、菜已上齐则释放桌台。
 */
public interface CounterSettlementService {
    CounterSettlementVO create(String entity, String store, String actor, String checkNo, String requestKey);

    CounterSettlementVO finalize(String entity, String store, String actor, String settlementNo,
            List<String> offlineRecordNos);

    CounterSettlementVO cancel(String entity, String store, String actor, String settlementNo);

    CounterSettlementVO get(String entity, String store, String settlementNo);
}
