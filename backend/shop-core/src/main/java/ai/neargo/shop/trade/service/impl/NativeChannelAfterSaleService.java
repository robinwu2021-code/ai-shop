package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Target;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.trade.entity.OrdChannelAfterSale;
import ai.neargo.shop.trade.entity.OrdChannelAfterSaleLine;
import ai.neargo.shop.trade.entity.OrdChannelImport;
import ai.neargo.shop.trade.mapper.ChannelAfterSaleMappers.CaseMapper;
import ai.neargo.shop.trade.mapper.ChannelAfterSaleMappers.LineMapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Native channel after-sale authorization is independent of buyer accounts, supplier ACK and
 * treasury refunds.
 */
@Service
public final class NativeChannelAfterSaleService implements IndustryChannelAfterSalePort {
    private final IndustryChannelOrderStore store;
    private final CaseMapper cases;
    private final LineMapper lines;
    private final ObjectProvider<InventoryDemandPlanPort> inventories;

    public NativeChannelAfterSaleService(
            IndustryChannelOrderStore store,
            CaseMapper cases,
            LineMapper lines,
            ObjectProvider<InventoryDemandPlanPort> inventories) {
        this.store = store;
        this.cases = cases;
        this.lines = lines;
        this.inventories = inventories;
    }

    private record Allocations(List<Allocation> allocations) {}

    private record Physical(
            Reference reference,
            Plan plan,
            Map<String, BigDecimal> quantities,
            Map<String, BigDecimal> originals) {}

    @Override
    public Facts authorize(Authorize command, String actor, String key) {
        if (command == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        IndustryChannelOrderFacts.reference(command.original());
        IndustryChannelOrderFacts.key(actor, 32);
        IndustryChannelOrderFacts.key(key, 128);
        IndustryChannelOrderFacts.key(command.externalCaseRef(), 128);
        IndustryChannelOrderFacts.key(command.reasonCode(), 64);
        require(
                command.action() != null
                        && command.lines() != null
                        && command.lines().size() <= 1000
                        && (command.action() == Action.REJECT
                                ? command.lines().isEmpty()
                                : !command.lines().isEmpty()));
        String requestHash = PricingSnapshotHash.of(List.of(command, actor));
        String keyHash = PricingSnapshotHash.of(List.of(command.original(), key));
        var query =
                store.atomic(
                        () -> {
                            var admission = store.required(command.original(), true);
                            accepted(admission);
                            var existing =
                                    cases.selectOne(
                                            Wrappers.<OrdChannelAfterSale>query()
                                                    .eq(
                                                            "tenant_no",
                                                            command.original().scope().tenantNo())
                                                    .eq("entity_no", admission.getEntityNo())
                                                    .eq("store_no", admission.getStoreNo())
                                                    .eq("admission_no", admission.getAdmissionNo())
                                                    .and(
                                                            q ->
                                                                    q.eq(
                                                                                    "request_key_hash",
                                                                                    keyHash)
                                                                            .or()
                                                                            .eq(
                                                                                    "external_case_ref",
                                                                                    command
                                                                                            .externalCaseRef())));
                            if (existing != null) {
                                require(requestHash.equals(existing.getRequestHash()));
                                return query(existing, command.original());
                            }
                            var original = store.data(admission);
                            var accepted =
                                    store.json
                                            .readValue(
                                                    admission.getAllocationsJson(),
                                                    Allocations.class)
                                            .allocations();
                            require(
                                    accepted != null
                                            && accepted.size() == original.components().size());
                            var allocations = new HashMap<List<String>, Allocation>();
                            for (var allocation : accepted)
                                require(
                                        allocations.putIfAbsent(
                                                        List.of(
                                                                allocation.sourceLineRef(),
                                                                allocation.componentRef()),
                                                        allocation)
                                                == null);
                            var selected = new ArrayList<Line>();
                            var identities = new HashSet<List<String>>();
                            long ceiling = 0;
                            String decisionNo = BizKey.next("OAS");
                            for (var requested : command.lines()) {
                                require(requested != null);
                                IndustryChannelOrderFacts.key(requested.sourceLineRef(), 128);
                                IndustryChannelOrderFacts.key(requested.componentRef(), 64);
                                var identity =
                                        List.of(
                                                requested.sourceLineRef(),
                                                requested.componentRef());
                                require(identities.add(identity));
                                var allocation = allocations.get(identity);
                                require(allocation != null);
                                var component =
                                        original.components().stream()
                                                .filter(
                                                        c ->
                                                                requested
                                                                                .componentRef()
                                                                                .equals(
                                                                                        c
                                                                                                .componentRef())
                                                                        && requested
                                                                                .sourceLineRef()
                                                                                .equals(
                                                                                        c
                                                                                                .sourceLineRef()))
                                                .findFirst()
                                                .orElseThrow();
                                require(
                                        allocation.amountMinor() == component.netMinor()
                                                && allocation.orderItemId() > 0
                                                && original.currency().equals(allocation.currency())
                                                && component.unit().equals(allocation.unit())
                                                && component
                                                                .physicalQuantity()
                                                                .compareTo(
                                                                        new BigDecimal(
                                                                                allocation
                                                                                        .physicalQuantity()))
                                                        == 0);
                                var quantity =
                                        ChannelAfterSaleAllocationPolicy.quantity(
                                                requested.physicalQuantity());
                                var prior =
                                        lines.selectList(
                                                Wrappers.<OrdChannelAfterSaleLine>query()
                                                        .eq(
                                                                "tenant_no",
                                                                command.original()
                                                                        .scope()
                                                                        .tenantNo())
                                                        .eq("entity_no", admission.getEntityNo())
                                                        .eq("store_no", admission.getStoreNo())
                                                        .eq(
                                                                "admission_no",
                                                                admission.getAdmissionNo())
                                                        .eq(
                                                                "source_line_ref",
                                                                requested.sourceLineRef())
                                                        .eq(
                                                                "component_ref",
                                                                requested.componentRef())
                                                        .last("LIMIT 10001"));
                                require(prior.size() <= 10000);
                                BigDecimal used = BigDecimal.ZERO;
                                long usedMinor = 0;
                                for (var line : prior) {
                                    used = used.add(line.getPhysicalQuantity());
                                    usedMinor =
                                            Math.addExact(usedMinor, line.getRefundCeilingMinor());
                                }
                                long amount =
                                        ChannelAfterSaleAllocationPolicy.incrementalCeiling(
                                                component.netMinor(),
                                                component.physicalQuantity(),
                                                used,
                                                usedMinor,
                                                quantity);
                                ceiling = Math.addExact(ceiling, amount);
                                selected.add(
                                        new Line(
                                                allocation.externalLineRef(),
                                                allocation.parentExternalLineRef(),
                                                allocation.sourceLineRef(),
                                                allocation.componentRef(),
                                                allocation.coreItemRef(),
                                                quantity.toPlainString(),
                                                allocation.unit(),
                                                amount,
                                                allocation.receiptRef()));
                                var line = new OrdChannelAfterSaleLine();
                                store.audit(line, command.original().scope());
                                line.setDecisionNo(decisionNo);
                                line.setEntityNo(admission.getEntityNo());
                                line.setStoreNo(admission.getStoreNo());
                                line.setAdmissionNo(admission.getAdmissionNo());
                                line.setSourceLineRef(requested.sourceLineRef());
                                line.setComponentRef(requested.componentRef());
                                line.setPhysicalQuantity(quantity);
                                line.setRefundCeilingMinor(amount);
                                lines.insert(line);
                            }
                            var decision =
                                    new Decision(
                                            command.original(),
                                            admission.getOrderNo(),
                                            admission.getSubOrderNo(),
                                            original.externalOrderRef(),
                                            original.externalFinancialHash(),
                                            command.externalCaseRef(),
                                            decisionNo,
                                            command.action(),
                                            command.reasonCode(),
                                            original.currency(),
                                            selected,
                                            ceiling,
                                            actor,
                                            store.clock.instant(),
                                            BizKey.next("OASR"));
                            var row = new OrdChannelAfterSale();
                            store.audit(row, command.original().scope());
                            row.setDecisionNo(decisionNo);
                            row.setEntityNo(admission.getEntityNo());
                            row.setStoreNo(admission.getStoreNo());
                            row.setAdmissionNo(admission.getAdmissionNo());
                            row.setSourceSystem(admission.getSourceSystem());
                            row.setOrderNo(admission.getOrderNo());
                            row.setSubOrderNo(admission.getSubOrderNo());
                            row.setExternalCaseRef(command.externalCaseRef());
                            row.setRequestKeyHash(keyHash);
                            row.setRequestHash(requestHash);
                            row.setSourceHash(PricingSnapshotHash.of(decision));
                            row.setDecisionSnapshot(store.json.writeValueAsString(decision));
                            row.setAction(command.action().name());
                            row.setRefundCeilingMinor(ceiling);
                            row.setCurrency(original.currency());
                            row.setFundsStatus(
                                    command.action() == Action.REJECT
                                            ? "NOT_REQUIRED"
                                            : "WAITING_FUNDS_OWNER");
                            row.setInventoryStatus(
                                    command.action() == Action.REJECT
                                            ? "NOT_REQUIRED"
                                            : "WAITING_ORIGINAL_ACTION");
                            row.setCreatedBy(actor);
                            row.setUpdatedBy(actor);
                            cases.insert(row);
                            return query(row, command.original());
                        });
        return get(query);
    }

    @Override
    public Facts get(Query query) {
        validateQuery(query);
        return store.query(() -> facts(row(query, false), query));
    }

    private Facts facts(OrdChannelAfterSale row, Query query) {
        var decision = store.json.readValue(row.getDecisionSnapshot(), Decision.class);
        require(
                query.scope().equals(decision.original().scope())
                        && query.sourceSystem().equals(decision.original().sourceSystem())
                        && query.coreOrderNo().equals(decision.coreOrderNo())
                        && query.externalCaseRef().equals(decision.externalCaseRef())
                        && query.decisionNo().equals(decision.decisionNo())
                        && query.revisionNo().equals(PricingSnapshotHash.of(decision))
                        && query.revisionNo().equals(row.getSourceHash()));
        var original = store.required(decision.original(), false);
        accepted(original);
        require(
                original.getOrderNo().equals(decision.coreOrderNo())
                        && original.getSubOrderNo().equals(decision.subOrderNo()));
        var now = store.clock.instant();
        return new Facts(
                query,
                decision,
                row.getSourceHash(),
                now,
                now.plusSeconds(30),
                PricingSnapshotHash.of(List.of(query, row.getSourceHash(), now)),
                row.getFundsStatus(),
                row.getInventoryStatus());
    }

    @Override
    public Facts releaseUnissued(Query query, String actor) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Inventory calls require no local transaction");
        validateQuery(query);
        IndustryChannelOrderFacts.key(actor, 32);
        var physical =
                store.query(
                        () -> {
                            var current = facts(row(query, false), query);
                            require(current.decision().action() != Action.REJECT);
                            var admission = store.required(current.decision().original(), false);
                            var preparation = store.preparation(admission, false);
                            require(preparation != null && preparation.getPlanSnapshot() != null);
                            var plan =
                                    store.json.readValue(preparation.getPlanSnapshot(), Plan.class);
                            var selected =
                                    lines.selectList(
                                            Wrappers.<OrdChannelAfterSaleLine>query()
                                                    .eq("tenant_no", query.scope().tenantNo())
                                                    .eq("entity_no", query.scope().entityNo())
                                                    .eq("store_no", query.scope().storeNo())
                                                    .eq(
                                                            "admission_no",
                                                            admission.getAdmissionNo()));
                            var quantities = new HashMap<String, BigDecimal>();
                            selected.forEach(
                                    l ->
                                            quantities.merge(
                                                    l.getComponentRef(),
                                                    l.getPhysicalQuantity(),
                                                    BigDecimal::add));
                            var originals = new HashMap<String, BigDecimal>();
                            store.data(admission)
                                    .components()
                                    .forEach(
                                            c ->
                                                    originals.put(
                                                            c.componentRef(),
                                                            c.physicalQuantity()));
                            return new Physical(
                                    current.decision().original(), plan, quantities, originals);
                        });
        var owners = inventories.orderedStream().limit(2).toList();
        if (owners.size() != 1) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        var inventory = owners.getFirst();
        var receipt = inventory.query(physical.plan().scope());
        IndustryChannelStockPreparation.validateReceipt(physical.plan(), receipt);
        var targets = new ArrayList<Target>();
        boolean issued = false;
        for (var branch : physical.plan().branches()) {
            var selected = physical.quantities().get(branch.sourceComponentRef());
            if (selected == null) continue;
            var original = physical.originals().get(branch.sourceComponentRef());
            require(original != null && selected.compareTo(original) <= 0);
            for (var leaf : branch.leaves()) {
                var progress =
                        receipt.lines().stream()
                                .filter(l -> l.leafRef().equals(leaf.leafRef()))
                                .findFirst()
                                .orElseThrow();
                var desired =
                        leaf.quantity()
                                .multiply(selected)
                                .divide(original, 6, RoundingMode.UNNECESSARY)
                                .stripTrailingZeros();
                if (progress.issuedQty().signum() > 0) issued = true;
                var unissued = progress.originalQty().subtract(progress.issuedQty());
                var target = desired.min(unissued).max(progress.releasedQty());
                targets.add(new Target(leaf.leafRef(), target));
            }
        }
        require(!targets.isEmpty());
        String operation =
                "OASREL:"
                        + PricingSnapshotHash.of(
                                List.of(physical.reference(), physical.plan().planHash(), targets));
        var after =
                inventory.releaseTo(
                        physical.plan().scope(), operation, physical.plan().planHash(), targets);
        IndustryChannelStockPreparation.validateReceipt(physical.plan(), after);
        for (var target : targets)
            require(
                    after.lines().stream()
                            .anyMatch(
                                    l ->
                                            l.leafRef().equals(target.leafRef())
                                                    && l.releasedQty()
                                                                    .compareTo(
                                                                            target.cumulativeQty())
                                                            >= 0));
        boolean issuedReturn = issued;
        return store.atomic(
                () -> {
                    var row = row(query, true);
                    facts(row, query);
                    require(
                            cases.update(
                                            Wrappers.<OrdChannelAfterSale>update()
                                                    .eq("tenant_no", query.scope().tenantNo())
                                                    .eq("id", row.getId())
                                                    .eq("version", row.getVersion())
                                                    .set(
                                                            "inventory_status",
                                                            issuedReturn
                                                                    ? "WAITING_ISSUED_RETURN_OWNER"
                                                                    : "UNISSUED_RELEASED")
                                                    .set(
                                                            "inventory_receipt_snapshot",
                                                            store.json.writeValueAsString(after))
                                                    .set("updated_by", actor)
                                                    .setSql("version=version+1"))
                                    == 1);
                    return facts(row(query, false), query);
                });
    }

    private OrdChannelAfterSale row(Query q, boolean lock) {
        var lookup =
                Wrappers.<OrdChannelAfterSale>query()
                        .eq("tenant_no", q.scope().tenantNo())
                        .eq("entity_no", q.scope().entityNo())
                        .eq("store_no", q.scope().storeNo())
                        .eq("source_system", q.sourceSystem())
                        .eq("order_no", q.coreOrderNo())
                        .eq("external_case_ref", q.externalCaseRef())
                        .eq("decision_no", q.decisionNo())
                        .eq("source_hash", q.revisionNo());
        if (lock) lookup.last("FOR UPDATE");
        var value = cases.selectOne(lookup);
        if (value == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return value;
    }

    private static Query query(OrdChannelAfterSale row, Reference ref) {
        return new Query(
                ref.scope(),
                ref.sourceSystem(),
                row.getOrderNo(),
                row.getExternalCaseRef(),
                row.getDecisionNo(),
                row.getSourceHash());
    }

    private static void validateQuery(Query q) {
        require(q != null);
        IndustryChannelOrderFacts.scope(q.scope());
        IndustryChannelOrderFacts.key(q.sourceSystem(), 32);
        IndustryChannelOrderFacts.key(q.coreOrderNo(), 32);
        IndustryChannelOrderFacts.key(q.externalCaseRef(), 128);
        IndustryChannelOrderFacts.key(q.decisionNo(), 32);
        require(q.revisionNo() != null && q.revisionNo().matches("[a-f0-9]{64}"));
    }

    private static void accepted(OrdChannelImport row) {
        require(
                "ACCEPTED".equals(row.getStatus())
                        && row.getAllocationsJson() != null
                        && row.getAcceptanceReceiptRef() != null);
    }

    private static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }
}
