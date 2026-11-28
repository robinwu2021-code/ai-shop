package ai.neargo.shop.food.service.support;

import ai.neargo.shop.food.dto.ProductionContracts.PendingVO;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ProductionConsumptionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenEventMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 出品耗料的来源查询。原是一条跨行业库（服务事件、制作任务、出品单）、交易库（批次）、耗料表的联表 SQL
 * （TDD-餐饮域迁移新库 §1.2），拆成三次查询在这里合并：
 * 行业库按事件顺序取候选事件与批次号 → 交易库按批次号取门店与接单状态 → 耗料表按事件号取处理状态。
 * 耗料记录按主体、批次所在门店、事件号对上才算数。
 */
public final class ProductionSourceQuery {
    public record Source(String entityNo, String storeNo, String eventNo) {}

    private static final Set<String> OPEN =
            Set.of("PREPARED", "SUBMITTED", "UNKNOWN", "REVERSAL_PENDING", "BLOCKED", "FAILED");
    private static final int SCAN = 500;

    private final KitchenEventMapper events;
    private final OrderBatchMapper batches;
    private final OrderLineMapper lines;
    private final ProductionConsumptionMapper heads;

    public ProductionSourceQuery(
            KitchenEventMapper events,
            OrderBatchMapper batches,
            OrderLineMapper lines,
            ProductionConsumptionMapper heads) {
        this.events = events;
        this.batches = batches;
        this.lines = lines;
        this.heads = heads;
    }

    /** 已接单批次上、还没有耗料记录的服务事件（提交到首次准备之间崩溃留下的缺口）。 */
    public List<Source> unconsumed(int limit) {
        var found = new ArrayList<Source>();
        long after = 0;
        while (found.size() < limit) {
            var page = events.productionCandidates(null, after, SCAN);
            if (page.isEmpty()) break;
            after = page.getLast().sourceId();
            var facts = facts(page);
            for (var candidate : page) {
                var batch = facts.batch(candidate);
                if (batch == null
                        || !"ACCEPTED".equals(batch.status())
                        || facts.consumption(candidate, batch.storeNo()) != null) continue;
                found.add(new Source(candidate.entityNo(), batch.storeNo(), candidate.sourceEventNo()));
                if (found.size() == limit) break;
            }
            if (page.size() < SCAN) break;
        }
        return found;
    }

    /** 门店待处理的来源：已有未结耗料记录的，或还没有记录、批次已接单的；按事件顺序，afterId 之后。 */
    public List<PendingVO> pending(String entity, String store, long afterId, int limit) {
        var found = new ArrayList<PendingVO>();
        long after = afterId;
        while (found.size() < limit) {
            var page = events.productionCandidates(entity, after, SCAN);
            if (page.isEmpty()) break;
            after = page.getLast().sourceId();
            var facts = facts(page);
            for (var candidate : page) {
                var batch = facts.batch(candidate);
                if (batch == null || !store.equals(batch.storeNo())) continue;
                var head = facts.consumption(candidate, store);
                if (head == null ? !"ACCEPTED".equals(batch.status()) : !OPEN.contains(head.status()))
                    continue;
                found.add(
                        new PendingVO(
                                candidate.sourceId(),
                                candidate.sourceEventNo(),
                                candidate.taskNo(),
                                candidate.action(),
                                head == null ? "NOT_STARTED" : head.status()));
                if (found.size() == limit) break;
            }
            if (page.size() < SCAN) break;
        }
        return found;
    }

    /** 按商品筛选耗料时的事件号；不按商品筛时返回 null。 */
    public List<String> skuEvents(String entity, String store, String sku) {
        if (sku == null) return null;
        var numbers = lines.lineNosBySku(entity, store, sku);
        return numbers.isEmpty() ? List.of() : events.eventNosForLines(entity, numbers);
    }

    private record BatchKey(String entityNo, String batchNo) {}

    private record EventKey(String entityNo, String storeNo, String eventNo) {}

    private record PageFacts(
            Map<BatchKey, OrderBatchMapper.BatchState> batches,
            Map<EventKey, ProductionConsumptionMapper.EventState> consumptions) {
        OrderBatchMapper.BatchState batch(KitchenEventMapper.ProductionCandidate candidate) {
            return batches.get(new BatchKey(candidate.entityNo(), candidate.batchNo()));
        }

        ProductionConsumptionMapper.EventState consumption(
                KitchenEventMapper.ProductionCandidate candidate, String store) {
            return consumptions.get(new EventKey(candidate.entityNo(), store, candidate.sourceEventNo()));
        }
    }

    private PageFacts facts(List<KitchenEventMapper.ProductionCandidate> page) {
        var byEntity = new HashMap<String, Set<String>>();
        for (var candidate : page)
            byEntity.computeIfAbsent(candidate.entityNo(), k -> new TreeSet<>()).add(candidate.batchNo());
        var batchStates = new HashMap<BatchKey, OrderBatchMapper.BatchState>();
        byEntity.forEach(
                (entity, numbers) -> {
                    for (var state : batches.states(entity, numbers))
                        batchStates.put(new BatchKey(entity, state.batchNo()), state);
                });
        var consumptions = new HashMap<EventKey, ProductionConsumptionMapper.EventState>();
        for (var state :
                heads.eventStates(
                        page.stream().map(KitchenEventMapper.ProductionCandidate::sourceEventNo).toList()))
            consumptions.put(new EventKey(state.entityNo(), state.storeNo(), state.sourceEventNo()), state);
        return new PageFacts(batchStates, consumptions);
    }
}
