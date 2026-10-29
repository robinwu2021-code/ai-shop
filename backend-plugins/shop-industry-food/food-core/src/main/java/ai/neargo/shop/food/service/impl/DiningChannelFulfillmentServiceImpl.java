package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts;
import ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts.Facts;
import ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts.Line;
import ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts.Query;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelOrderSourceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.DiningChannelFulfillmentService;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Original receipt + original accepted financial/physical allocations, read in one local FOOD
 * snapshot.
 */
public class DiningChannelFulfillmentServiceImpl implements DiningChannelFulfillmentService {
    private final ChannelOrderSourceMapper sources;
    private final OrderBatchMapper batches;
    private final OrderLineMapper lines;
    private final CoreOrderBindingMapper bindings;
    private final CoreLineAllocationMapper allocations;
    private final KitchenTaskService kitchen;
    private final ObjectMapper json;
    private final Clock clock;

    public DiningChannelFulfillmentServiceImpl(
            ChannelOrderSourceMapper sources,
            OrderBatchMapper batches,
            OrderLineMapper lines,
            CoreOrderBindingMapper bindings,
            CoreLineAllocationMapper allocations,
            KitchenTaskService kitchen,
            ObjectMapper json,
            Clock clock) {
        this.sources = sources;
        this.batches = batches;
        this.lines = lines;
        this.bindings = bindings;
        this.allocations = allocations;
        this.kitchen = kitchen;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(
            transactionManager = "transactionManager",
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public Facts capture(Query query) {
        check(query != null);
        text(query.entityNo());
        text(query.storeNo());
        text(query.batchNo());
        text(query.coreOrderNo());
        text(query.custodyOperationNo());
        text(query.sourceRevision());
        return DataScopeContext.executeWithoutScope(() -> original(query));
    }

    private Facts original(Query q) {
        var source =
                sources.selectOne(
                        Wrappers.<FnbChannelOrderSource>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", q.entityNo())
                                .eq("store_no", q.storeNo())
                                .eq("batch_no", q.batchNo()));
        check(
                source != null
                        && "ACCEPTED".equals(source.getStatus())
                        && q.coreOrderNo().equals(source.getCoreOrderNo())
                        && source.getCoreAcceptanceSnapshot() != null
                        && source.getCoreAcceptanceReceiptRef() != null);
        var accepted = json.readValue(source.getCoreAcceptanceSnapshot(), Acceptance.class);
        var expected =
                new Reference(
                        new Scope("MAIN", q.entityNo(), q.storeNo()),
                        "FOOD",
                        source.getSourceNo(),
                        source.getSourceVersion(),
                        source.getSourceHash(),
                        source.getImportNo());
        check(
                expected.equals(accepted.reference())
                        && q.coreOrderNo().equals(accepted.orderNo())
                        && source.getCoreSubOrderNo().equals(accepted.subOrderNo())
                        && source.getCoreAcceptanceReceiptRef()
                                .equals(accepted.acceptanceReceiptRef())
                        && !accepted.allocations().isEmpty()
                        && accepted.allocations().size() <= 10000);
        var batch =
                batches.selectOne(
                        Wrappers.<FnbOrderBatch>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", q.entityNo())
                                .eq("store_no", q.storeNo())
                                .eq("batch_no", q.batchNo()));
        check(
                batch != null
                        && "ACCEPTED".equals(batch.getStatus())
                        && "CHANNEL".equals(batch.getPayMode())
                        && "DELIVERY".equals(batch.getFulfillment())
                        && batch.getFrozenRequest() != null);
        var binding =
                bindings.selectOne(
                        Wrappers.<FnbCoreOrderBinding>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", q.entityNo())
                                .eq("batch_no", q.batchNo())
                                .eq("status", "BOUND"));
        check(
                binding != null
                        && q.coreOrderNo().equals(binding.getPaymentRootNo())
                        && accepted.subOrderNo().equals(binding.getStoreOrderNo()));
        var receipts = kitchen.custodyReceipts(q.entityNo(), q.storeNo(), q.batchNo());
        check(receipts != null && receipts.size() <= 5000);
        var matching =
                receipts.stream()
                        .filter(
                                receipt ->
                                        receipt != null
                                                && q.custodyOperationNo()
                                                        .equals(receipt.operationNo()))
                        .toList();
        check(matching.size() == 1);
        var receipt = matching.getFirst();
        check(
                q.sourceRevision().equals(DiningChannelFulfillmentFacts.revision(receipt))
                        && q.entityNo().equals(receipt.entityNo())
                        && q.storeNo().equals(receipt.storeNo())
                        && q.batchNo().equals(receipt.batchNo())
                        && q.coreOrderNo().equals(receipt.orderNo())
                        && accepted.subOrderNo().equals(receipt.storeOrderNo())
                        && binding.getBindingNo().equals(receipt.bindingNo())
                        && receipt.bindingVersion() != null
                        && receipt.batchVersion() != null
                        && receipt.frozenRequestHash() != null
                        && receipt.frozenRequestHash()
                                .equals(
                                        FoodCommandFingerprint.hash(
                                                "custody-source:v1", batch.getFrozenRequest()))
                        && "CUSTODY_HANDOVER".equals(receipt.action())
                        && "DELIVERY".equals(receipt.kind())
                        && receipt.lines() != null
                        && !receipt.lines().isEmpty()
                        && receipt.lines().size() <= 1000
                        && receipt.sources() != null
                        && receipt.sources().size() == receipt.lines().size());
        Instant occurred = Instant.parse(receipt.recordedAt());
        check(!occurred.isAfter(clock.instant()));
        var output = new ArrayList<Line>();
        var seen = new HashSet<String>();
        var seenAllocations = new HashSet<String>();
        for (var selected : receipt.lines()) {
            check(
                    selected != null
                            && selected.qty() != null
                            && selected.qty().signum() > 0
                            && seen.add(selected.foodLineNo()));
            var original =
                    lines.selectOne(
                            Wrappers.<FnbOrderLine>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", q.entityNo())
                                    .eq("batch_no", q.batchNo())
                                    .eq("food_line_no", selected.foodLineNo()));
            check(
                    original != null
                            && original.getSourceLineRef() != null
                            && original.getQty() != null
                            && original.getQty().signum() > 0
                            && selected.qty().compareTo(original.getQty()) <= 0);
            var sourceLines =
                    receipt.sources().stream()
                            .filter(s -> s != null && selected.foodLineNo().equals(s.foodLineNo()))
                            .toList();
            check(sourceLines.size() == 1);
            var custody = sourceLines.getFirst();
            check(
                    original.getSourceLineRef().equals(custody.sourceLineRef())
                            && custody.originalLines() != null
                            && !custody.originalLines().isEmpty()
                            && custody.originalLines().size() <= 1000);
            var originalAllocations =
                    accepted.allocations().stream()
                            .filter(a -> original.getSourceLineRef().equals(a.sourceLineRef()))
                            .toList();
            check(originalAllocations.size() == custody.originalLines().size());
            int mains = 0;
            for (var scope : custody.originalLines()) {
                check(
                        scope != null
                                && scope.orderItemId() != null
                                && scope.sourceComponentRef() != null
                                && scope.originalQty() != null
                                && scope.originalQty().signum() > 0
                                && scope.version() != null
                                && seenAllocations.add(scope.allocationNo()));
                var matches =
                        originalAllocations.stream()
                                .filter(
                                        a ->
                                                a.orderItemId() == scope.orderItemId()
                                                        && scope.sourceComponentRef()
                                                                .equals(a.componentRef()))
                                .toList();
                check(matches.size() == 1);
                var core = matches.getFirst();
                check(
                        core.unit().equals(scope.unit())
                                && core.receiptRef().equals(scope.coreSnapshotHash())
                                && new BigDecimal(core.physicalQuantity())
                                                .compareTo(scope.originalQty())
                                        == 0);
                var actual =
                        allocations.selectOne(
                                Wrappers.<FnbCoreLineAllocation>query()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", q.entityNo())
                                        .eq("food_line_no", selected.foodLineNo())
                                        .eq("allocation_no", scope.allocationNo()));
                check(
                        actual != null
                                && scope.orderItemId().equals(actual.getOrderItemId())
                                && scope.sourceComponentRef().equals(actual.getSourceComponentRef())
                                && scope.version().equals(actual.getVersion())
                                && actual.getQty() != null
                                && actual.getQty().compareTo(scope.originalQty()) == 0
                                && core.unit().equals(actual.getUnit())
                                && core.receiptRef().equals(actual.getCoreSnapshotHash()));
                // All physical components are validated; only the supplier's outer MAIN line is
                // publishable in
                // v1.
                if ("MAIN".equals(core.externalKind())) {
                    mains++;
                    text(core.coreItemRef());
                    BigDecimal quantity;
                    try {
                        quantity =
                                scope.originalQty()
                                        .multiply(selected.qty())
                                        .divide(original.getQty());
                    } catch (ArithmeticException inexact) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    check(
                            quantity.signum() > 0
                                    && quantity.compareTo(scope.originalQty()) <= 0
                                    && quantity.stripTrailingZeros().scale() <= 6);
                    output.add(
                            new Line(
                                    core.sourceLineRef(),
                                    core.componentRef(),
                                    core.coreItemRef(),
                                    quantity,
                                    core.unit()));
                }
            }
            check(mains == 1);
        }
        check(output.size() == receipt.lines().size());
        var now = clock.instant();
        return new Facts(
                q,
                output,
                occurred,
                "FOOD:CUSTODY:" + receipt.operationNo() + ":" + q.sourceRevision(),
                now,
                now.plusSeconds(30));
    }

    private static void text(String value) {
        check(value != null && !value.isBlank() && value.length() <= 128);
    }

    private static void check(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }
}
