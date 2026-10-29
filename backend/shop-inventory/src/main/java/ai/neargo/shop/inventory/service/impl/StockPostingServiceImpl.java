package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.entity.InvOutbox;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.mapper.InventoryExactSupportMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboxMapper;
import ai.neargo.shop.inventory.policy.ExecutionStockRetentionGuard;
import ai.neargo.shop.inventory.service.StockPostingService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 过账实现。三件事在**一个事务**里：改余额 → 写流水 → 发事件。
 *
 * <p><b>改余额靠 SQL 条件更新，不是「先查后改」</b>： {@code UPDATE … SET on_hand = on_hand + ? WHERE on_hand + ? >=
 * 0}， 影响行数为 0 即库存不足。先查后改在并发下必然超卖 —— 两个请求都查到「还有 1 件」。 与平台 {@code SkuMapper.lockStock} 完全同一手法。
 *
 * <p><b>流水的 {@code balanceAfter} 是改完之后回读的</b>，不是「改之前的值 + delta」算出来的： 并发下另一笔可能插在中间，算出来的数会与库里真实结存对不上，
 * 而回放守卫查的正是这一列 —— 算错的话守卫会报一堆假红，真问题反而被淹掉。
 */
@ConditionalOnInventory
@Service
public class StockPostingServiceImpl implements StockPostingService {

    private final BalanceMapper balanceMapper;
    private final LedgerMapper ledgerMapper;
    private final OutboxMapper outboxMapper;
    private InventoryExactSupportMapper exactMapper;

    @Autowired
    public void configureExactDatabase(InventoryExactSupportMapper mapper) {
        this.exactMapper = mapper;
    }

    private final ExecutionStockRetentionGuard retention;

    public StockPostingServiceImpl(BalanceMapper balanceMapper, LedgerMapper ledgerMapper,
                                   OutboxMapper outboxMapper, ExecutionStockRetentionGuard retention) {
        this.balanceMapper = balanceMapper;
        this.ledgerMapper = ledgerMapper;
        this.outboxMapper = outboxMapper;
        this.retention = retention;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public List<Long> post(PostingDoc doc) {
        int sign = InvEnums.DocKind.IN.equals(doc.docKind()) ? 1 : -1;
        List<String> shortages = new ArrayList<>();
        List<Long> ids = new ArrayList<>();

        for (Line line : doc.lines()) {
            int delta = sign * line.qty();
            var lot =
                    batch(
                            doc.ownerId(),
                            doc.docKind(),
                            doc.docNo(),
                            line.lineNo(),
                            line.itemId(),
                            line.locationId(),
                            BigDecimal.valueOf(line.qty()),
                            line.unitCostMinor(),
                            line.currency(),
                            line.batchNo(),
                            line.lotRef(),
                            line.expireAt(),
                            doc.operator());
            ensureBalanceRow(doc.ownerId(), line.itemId(), line.locationId());
            int changed =
                    balanceMapper.applyDelta(
                            doc.ownerId(), line.itemId(), line.locationId(), delta);
            if (changed == 0) {
                // 收集而不是立刻抛：一次说清「哪几件各差多少」，
                // 否则商家改一件提交一次，六件货要提交六次
                shortages.add(line.itemId());
                continue;
            }
            ids.add(writeLedger(doc, line, delta, lot));
        }

        if (!shortages.isEmpty()) {
            // 事务回滚，前面改掉的余额一并撤销
            throw BizException.of(ErrorCode.STOCK_SHORT_ITEMS, String.join(",", shortages));
        }
        publish(doc);
        return ids;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public List<Long> postExact(ExactPostingDoc doc) {
        var ids = new ArrayList<Long>();
        for (var line : doc.lines()) {
            var quantity = InventoryDemandPlanFacts.quantity(line.qty(), false);
            var delta = "IN".equals(doc.docKind()) ? quantity : quantity.negate();
            var lot =
                    batch(
                            doc.ownerId(),
                            doc.docKind(),
                            doc.docNo(),
                            line.lineNo(),
                            line.itemId(),
                            line.locationId(),
                            quantity,
                            line.unitCostMinor(),
                            line.currency(),
                            line.batchNo(),
                            line.lotRef(),
                            line.expireAt(),
                            doc.operator());
            ensureBalanceRow(doc.ownerId(), line.itemId(), line.locationId());
            if (balanceMapper.applyExactDelta(
                            doc.ownerId(), line.itemId(), line.locationId(), delta)
                    != 1) throw BizException.of(ErrorCode.STOCK_SHORT_ITEMS, line.itemId());
            var after =
                    balanceMapper.selectOne(
                            Wrappers.<InvStockBalance>lambdaQuery()
                                    .eq(InvStockBalance::getOwnerId, doc.ownerId())
                                    .eq(InvStockBalance::getItemId, line.itemId())
                                    .eq(InvStockBalance::getLocationId, line.locationId()));
            var e = new InvLedger();
            e.setOwnerId(doc.ownerId());
            e.setItemId(line.itemId());
            e.setLocationId(line.locationId());
            e.setDocKind(doc.docKind());
            e.setDocNo(doc.docNo());
            e.setLineNo(line.lineNo());
            e.setReasonCode(doc.reasonCode());
            e.setQtyDeltaQuantity(delta);
            e.setBalanceAfterQuantity(after.getOnHandQuantity());
            e.setUnitCostMinor(line.unitCostMinor());
            e.setCostCurrency(line.currency());
            e.setBatchNo(line.batchNo());
            e.setLotRef(lot);
            e.setOccurredAt(doc.occurredAt());
            e.setOperator(doc.operator());
            e.setCreatedBy(doc.operator());
            ledgerMapper.insert(e);
            ids.add(e.getId());
        }
        publish(
                new PostingDoc(
                        doc.ownerId(),
                        doc.docKind(),
                        doc.docNo(),
                        doc.reasonCode(),
                        doc.occurredAt(),
                        doc.operator(),
                        List.of()));
        return ids;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public List<Long> reverse(String ownerId, String docNo, String docKind, String operator) {
        var source = retention.lockReversibleOutbound(ownerId, docNo);
        List<InvLedger> origin = ledgerMapper.selectList(Wrappers.<InvLedger>lambdaQuery()
                .eq(InvLedger::getOwnerId, ownerId)
                .eq(InvLedger::getDocNo, docNo)
                .orderByAsc(InvLedger::getLineNo).last("FOR UPDATE"));
        if (origin.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (source == null && !InvEnums.DocKind.IN.equals(docKind)
                || origin.stream().anyMatch(row -> !docKind.equals(row.getDocKind())
                || row.getQtyDelta() == null || row.getQtyDelta() == 0 || row.getQtyDelta() == Integer.MIN_VALUE)
                || origin.stream().anyMatch(row -> "SALE".equals(row.getReasonCode()))
                        && (source == null || !"SALE".equals(source.getPurpose()) || !"OUT".equals(docKind))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        // 反向单号 = 原单号 + "-R"：**幂等靠它** —— 唯一键 (doc_no, line_no) 会挡住重复作废，
        // 而不是靠先查一遍「作废过没有」（那在并发下挡不住）
        String reverseNo = docNo + "-R";
        String reverseKind =
                InvEnums.DocKind.IN.equals(docKind) ? InvEnums.DocKind.OUT : InvEnums.DocKind.IN;
        List<Line> lines = new ArrayList<>();
        for (InvLedger e : origin) {
            lines.add(
                    new Line(
                            e.getLineNo(),
                            e.getItemId(),
                            e.getLocationId(),
                            Math.abs(e.getQtyDelta()),
                            e.getUnitCostMinor(),
                            e.getBatchNo(),
                            null,
                            e.getCostCurrency(),
                            e.getLotRef()));
        }
        return post(
                new PostingDoc(
                        ownerId,
                        reverseKind,
                        reverseNo,
                        InvEnums.Reason.OTHER,
                        LocalDateTime.now(),
                        operator,
                        lines));
    }

    /** Batch allocation is an extra dimension of the same original balance/ledger transaction. */
    private String batch(
            String owner,
            String kind,
            String doc,
            int line,
            String item,
            String location,
            BigDecimal qty,
            Long cost,
            String currency,
            String batchNo,
            String lotRef,
            LocalDate expiry,
            String actor) {
        if (exactMapper == null) return null;
        var definitions = exactMapper.batch(owner, item);
        if (definitions.isEmpty() || definitions.getFirst().batchTracked() != 1) {
            if (batchNo != null || lotRef != null) throw BizException.of(ErrorCode.CONFLICT);
            return null;
        }
        if (lotRef != null) {
            var delta = "IN".equals(kind) ? qty : qty.negate();
            if (exactMapper.adjustLot(owner, item, location, lotRef, delta) != 1)
                throw BizException.of(ErrorCode.STOCK_SHORT_ITEMS, item);
            return lotRef;
        }
        if (!"IN".equals(kind) || batchNo == null || batchNo.isBlank() || batchNo.length() > 32)
            throw BizException.of(ErrorCode.CONFLICT);
        String generated = PricingSnapshotHash.of(List.of(owner, doc, line)).substring(0, 32);
        String cc = currency == null ? definitions.getFirst().currency() : currency;
        if (exactMapper.insertLot(
                        owner, item, location, generated, batchNo, qty, cost, cc, expiry, doc, line,
                        actor)
                != 1) throw BizException.of(ErrorCode.CONFLICT);
        return generated;
    }

    /**
     * 余额行不存在就先建一行 0。
     *
     * <p><b>为什么不在建品时就把所有库位的余额行铺满</b>：一个商家 200 个 SKU × 4 个库位 = 800 行， 其中绝大多数永远是 0。按需建行的代价是这里多一次查询，
     * 而铺满的代价是每加一个库位就要给全部物料补行 —— 补漏一次，那个库位就永远入不了货。
     */
    private void ensureBalanceRow(String ownerId, String itemId, String locationId) {
        Long exists =
                balanceMapper.selectCount(
                        Wrappers.<InvStockBalance>lambdaQuery()
                                .eq(InvStockBalance::getOwnerId, ownerId)
                                .eq(InvStockBalance::getItemId, itemId)
                                .eq(InvStockBalance::getLocationId, locationId));
        if (exists != null && exists > 0) {
            return;
        }
        InvStockBalance row = new InvStockBalance();
        row.setOwnerId(ownerId);
        row.setItemId(itemId);
        row.setLocationId(locationId);
        row.setOnHand(0);
        row.setReserved(0);
        balanceMapper.insert(row);
    }

    private long writeLedger(PostingDoc doc, Line line, int delta, String lot) {
        InvStockBalance after =
                balanceMapper.selectOne(
                        Wrappers.<InvStockBalance>lambdaQuery()
                                .eq(InvStockBalance::getOwnerId, doc.ownerId())
                                .eq(InvStockBalance::getItemId, line.itemId())
                                .eq(InvStockBalance::getLocationId, line.locationId()));
        InvLedger e = new InvLedger();
        e.setOwnerId(doc.ownerId());
        e.setItemId(line.itemId());
        e.setLocationId(line.locationId());
        e.setDocKind(doc.docKind());
        e.setDocNo(doc.docNo());
        e.setLineNo(line.lineNo());
        e.setReasonCode(doc.reasonCode());
        e.setQtyDelta(delta);
        e.setBalanceAfterQuantity(after.getOnHandQuantity());
        e.setUnitCostMinor(line.unitCostMinor());
        e.setCostCurrency(line.currency());
        e.setBatchNo(line.batchNo());
        e.setLotRef(lot);
        e.setOccurredAt(doc.occurredAt());
        e.setOperator(doc.operator());
        e.setCreatedBy(doc.operator());
        ledgerMapper.insert(e);
        return e.getId();
    }

    /** 事件写出站表，**不在事务里直接调下游**：下游挂了不该把这笔过账一起回滚。 独立库用不了平台的 {@code sys_outbox}，自己带一份。 */
    private UnifiedOutboxFacade unified;

    @Autowired(required = false)
    public void setUnified(UnifiedOutboxFacade unified) {
        this.unified = unified;
    }

    private record PostingEvent(String ownerId, String docNo, String docKind)
            implements DomainEvent {
        public String aggregateType() {
            return "INVENTORY";
        }

        public String aggregateId() {
            return ownerId;
        }

        public String eventType() {
            return InvEnums.EventType.DOCUMENT_POSTED;
        }
    }

    private void publish(PostingDoc doc) {
        if (unified != null && unified.isMaintenance()) {
            throw new IllegalStateException("Outbox maintenance");
        }
        if (unified != null && unified.enabled("INVENTORY")) {
            unified.publish(
                    "INVENTORY", new PostingEvent(doc.ownerId(), doc.docNo(), doc.docKind()));
            return;
        }
        InvOutbox evt = new InvOutbox();
        evt.setEventNo(InvKeys.next(InvKeys.EVENT));
        evt.setOwnerId(doc.ownerId());
        evt.setEventType(InvEnums.EventType.DOCUMENT_POSTED);
        evt.setPayload("{\"docNo\":\"" + doc.docNo() + "\",\"docKind\":\"" + doc.docKind() + "\"}");
        evt.setStatus("PENDING");
        evt.setRetryCount(0);
        evt.setCreatedBy(doc.operator());
        outboxMapper.insert(evt);
    }
}
