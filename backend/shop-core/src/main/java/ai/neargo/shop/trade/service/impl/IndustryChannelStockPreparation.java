package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Receipt;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort;
import ai.neargo.shop.trade.entity.OrdChannelImport;
import ai.neargo.shop.trade.entity.OrdChannelPreparation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

/** Persists an exact reservation intent before calling the independent stock owner. */
@Component
public final class IndustryChannelStockPreparation {
    private final IndustryChannelOrderStore store;
    private final ObjectProvider<InventoryDemandPlanPort> inventories;

    public IndustryChannelStockPreparation(
            IndustryChannelOrderStore store, ObjectProvider<InventoryDemandPlanPort> inventories) {
        this.store = store;
        this.inventories = inventories;
    }

    record Claim(
            Reference reference,
            long fence,
            OrdChannelImport admission,
            OrdChannelPreparation preparation) {}

    /**
     * A current lease serializes preparation; uncertain calls recover the same original generation.
     */
    public void prepare(Reference reference, IndustryChannelOrderSourcePort source) {
        var inventory = inventory();
        var claim = claim(reference);
        if (claim == null) return;
        var preparation = claim.preparation();
        Plan plan;
        if (preparation.getPlanSnapshot() == null) {
            plan =
                    source.demandPlan(
                            reference,
                            preparation.getReservationRef(),
                            claim.admission().getSubOrderNo());
            validatePlan(reference, claim.admission(), preparation, plan);
            if (!persistPlan(claim, plan)) return;
        } else {
            plan = store.json.readValue(preparation.getPlanSnapshot(), Plan.class);
            validatePlan(reference, claim.admission(), preparation, plan);
        }
        // A cancellation may have fenced the builder while source preparation was in flight.
        if (!owns(claim)) return;
        var receipt = inventory.query(plan.scope());
        if ("ABSENT".equals(receipt.status())) {
            require(
                    plan.scope().equals(receipt.scope())
                            && receipt.planNo() == null
                            && receipt.planHash() == null
                            && receipt.lifecycleVersion() == 0
                            && receipt.lines().isEmpty());
            // Reusing this persisted plan is idempotent. Cancellation writes a withdrawal tombstone
            // at the stock owner, so a late reserve can never resurrect a cancelled preparation.
            if (!owns(claim)) return;
            var grant =
                    store.json.readValue(
                            claim.admission().getPolicySnapshot(),
                            IndustryChannelOrderStore.FundingGrant.class);
            receipt = inventory.reserve(plan, grant.holdSeconds(), 3);
        }
        validateReceipt(plan, receipt);
        finish(claim, plan, receipt);
    }

    private Claim claim(Reference ref) {
        return store.atomic(
                () -> {
                    store.activeGuard(ref);
                    var row = store.required(ref, true);
                    if (!Set.of("WAITING_STOCK", "PREPARING", "PREPARED").contains(row.getStatus())
                            || row.getPolicyNo() == null
                            || row.getLeaseUntil() != null
                                    && row.getLeaseUntil().isAfter(store.now())) return null;
                    var current = store.preparation(row, true);
                    // An expired generation can only be replaced after an authoritative EXPIRED
                    // receipt.
                    if (current == null || "EXPIRED".equals(current.getStatus())) {
                        var next = new OrdChannelPreparation();
                        store.audit(next, ref.scope());
                        next.setPreparationNo(BizKey.next("OCP"));
                        next.setEntityNo(row.getEntityNo());
                        next.setStoreNo(row.getStoreNo());
                        next.setAdmissionNo(row.getAdmissionNo());
                        next.setGeneration(
                                current == null ? 1L : Math.addExact(current.getGeneration(), 1));
                        next.setPreviousPreparationNo(
                                current == null ? null : current.getPreparationNo());
                        next.setReservationRef(BizKey.next("OCR"));
                        next.setStatus("BUILDING");
                        store.preparations.insert(next);
                        current = next;
                    }
                    long fence = Math.addExact(row.getFence(), 1);
                    require(
                            store.imports.update(
                                            Wrappers.<OrdChannelImport>update()
                                                    .eq("tenant_no", ref.scope().tenantNo())
                                                    .eq("id", row.getId())
                                                    .eq("version", row.getVersion())
                                                    .set("fence", fence)
                                                    .set("status", "PREPARING")
                                                    .set("lease_until", store.now().plusMinutes(2))
                                                    .set(
                                                            "preparation_no",
                                                            current.getPreparationNo())
                                                    .setSql("version=version+1"))
                                    == 1);
                    return new Claim(ref, fence, store.required(ref, false), current);
                });
    }

    private boolean owns(Claim claim) {
        return store.query(() -> owned(store.required(claim.reference(), false), claim));
    }

    private boolean owned(OrdChannelImport row, Claim claim) {
        return row.getFence() == claim.fence()
                && "PREPARING".equals(row.getStatus())
                && Objects.equals(row.getPreparationNo(), claim.preparation().getPreparationNo())
                && row.getLeaseUntil() != null
                && row.getLeaseUntil().isAfter(store.now());
    }

    private boolean persistPlan(Claim claim, Plan plan) {
        return store.atomic(
                () -> {
                    var row = store.required(claim.reference(), true);
                    if (!owned(row, claim)) return false;
                    var preparation = store.preparation(row, true);
                    require(preparation.getPlanSnapshot() == null);
                    require(
                            store.preparations.update(
                                            Wrappers.<OrdChannelPreparation>update()
                                                    .eq("tenant_no", row.getTenantNo())
                                                    .eq("id", preparation.getId())
                                                    .eq("version", preparation.getVersion())
                                                    .set(
                                                            "plan_snapshot",
                                                            store.json.writeValueAsString(plan))
                                                    .set("plan_hash", plan.planHash())
                                                    .set("status", "RESERVING")
                                                    .setSql("version=version+1"))
                                    == 1);
                    return true;
                });
    }

    private void finish(Claim claim, Plan plan, Receipt receipt) {
        store.atomic(
                () -> {
                    var row = store.required(claim.reference(), true);
                    if (!owned(row, claim)) return null;
                    var preparation = store.preparation(row, true);
                    if ("EXPIRED".equals(receipt.status()))
                        require(
                                receipt.expiresAt() != null
                                        && !receipt.expiresAt().isAfter(store.clock.instant()));
                    boolean ready =
                            "HELD".equals(receipt.status())
                                    && receipt.expiresAt() != null
                                    && receipt.expiresAt().isAfter(store.clock.instant());
                    // Accepted/issued inventory is never relabelled HELD or discarded as an expired
                    // lease.
                    String state =
                            ready
                                    ? "HELD"
                                    : "EXPIRED".equals(receipt.status())
                                            ? "EXPIRED"
                                            : "RECOVERY_REQUIRED";
                    String proof = ready ? preparation.getPreparationReceiptRef() : null;
                    if (ready && proof == null) proof = BizKey.next("OCH");
                    require(
                            store.preparations.update(
                                            Wrappers.<OrdChannelPreparation>update()
                                                    .eq("tenant_no", row.getTenantNo())
                                                    .eq("id", preparation.getId())
                                                    .eq("version", preparation.getVersion())
                                                    .set("status", state)
                                                    .set(
                                                            "inventory_receipt_snapshot",
                                                            store.json.writeValueAsString(receipt))
                                                    .set("preparation_receipt_ref", proof)
                                                    .set(
                                                            "valid_until",
                                                            ready
                                                                    ? receipt.expiresAt()
                                                                            .atOffset(
                                                                                    ZoneOffset.UTC)
                                                                            .toLocalDateTime()
                                                                    : null)
                                                    .setSql("version=version+1"))
                                    == 1);
                    require(
                            store.imports.update(
                                            Wrappers.<OrdChannelImport>update()
                                                    .eq("tenant_no", row.getTenantNo())
                                                    .eq("id", row.getId())
                                                    .eq("version", row.getVersion())
                                                    .set("lease_until", null)
                                                    .set(
                                                            "status",
                                                            ready
                                                                    ? "PREPARED"
                                                                    : "EXPIRED".equals(state)
                                                                            ? "WAITING_STOCK"
                                                                            : "RECOVERY_REQUIRED")
                                                    .setSql("version=version+1"))
                                    == 1);
                    return null;
                });
    }

    private void validatePlan(
            Reference ref,
            OrdChannelImport admission,
            OrdChannelPreparation preparation,
            Plan plan) {
        InventoryDemandPlanFacts.validate(plan);
        require(
                new Scope(
                                ref.scope().tenantNo(),
                                ref.scope().entityNo(),
                                preparation.getReservationRef())
                        .equals(plan.scope()));
        var original = store.query(() -> store.data(admission));
        var components = new HashMap<String, IndustryChannelOrderSourcePort.Component>();
        original.components()
                .forEach(
                        component -> {
                            if ("BUNDLE_CONTAINER".equals(component.productionKind())) {
                                require(
                                        "MAIN".equals(component.externalKind())
                                                && original.components().stream()
                                                        .anyMatch(
                                                                child ->
                                                                        "BUNDLE_MEMBER"
                                                                                        .equals(
                                                                                                child
                                                                                                        .externalKind())
                                                                                && component
                                                                                        .sourceLineRef()
                                                                                        .equals(
                                                                                                child
                                                                                                        .sourceLineRef())
                                                                                && component
                                                                                        .externalLineRef()
                                                                                        .equals(
                                                                                                child
                                                                                                        .parentExternalLineRef())));
                                var own =
                                        plan.branches().stream()
                                                .filter(
                                                        branch ->
                                                                component
                                                                        .componentRef()
                                                                        .equals(
                                                                                branch
                                                                                        .sourceComponentRef()))
                                                .toList();
                                if (!own.isEmpty()) {
                                    require(
                                            own.size() == 1
                                                    && "BUNDLE_MODIFIERS"
                                                            .equals(own.getFirst().kind()));
                                    components.put(component.componentRef(), component);
                                }
                            } else components.put(component.componentRef(), component);
                        });
        require(plan.branches().size() == components.size());
        for (var branch : plan.branches()) {
            var component = components.remove(branch.sourceComponentRef());
            require(
                    component != null
                            && admission.getSubOrderNo().equals(branch.fulfillmentRef())
                            && ref.scope().storeNo().equals(branch.storeNo())
                            && component.unit().equals(branch.unit())
                            && component.physicalQuantity().compareTo(branch.quantity()) == 0);
            require(
                    "BUNDLE_CONTAINER".equals(component.productionKind())
                            == "BUNDLE_MODIFIERS".equals(branch.kind()));
            if ("FINISHED_SKU".equals(branch.kind())
                    || "FINISHED_WITH_MODIFIERS".equals(branch.kind())) {
                var finished =
                        branch.leaves().stream()
                                .filter(
                                        leaf ->
                                                "FINISHED_SKU"
                                                        .equals(
                                                                InventoryDemandPlanFacts.sourceKind(
                                                                        branch, leaf)))
                                .toList();
                require(finished.size() == 1);
                var leaf = finished.getFirst();
                require(
                        component.skuNo().equals(leaf.itemRef())
                                && component.unit().equals(leaf.unit())
                                && component.physicalQuantity().compareTo(leaf.quantity()) == 0);
            }
        }
        require(components.isEmpty());
    }

    /** Receipt itemRef is an inventory identity, not necessarily the source SKU identity. */
    static void validateReceipt(Plan plan, Receipt receipt) {
        require(
                receipt != null
                        && plan.scope().equals(receipt.scope())
                        && plan.planNo().equals(receipt.planNo())
                        && plan.planHash().equals(receipt.planHash())
                        && receipt.lifecycleVersion() == 3);
        var lines = new HashMap<String, InventoryDemandPlanPort.Progress>();
        for (var line : receipt.lines()) require(lines.putIfAbsent(line.leafRef(), line) == null);
        for (var branch : plan.branches()) {
            for (var leaf : branch.leaves()) {
                var line = lines.remove(leaf.leafRef());
                require(
                        line != null
                                && branch.sourceComponentRef().equals(line.sourceComponentRef())
                                && branch.fulfillmentRef().equals(line.fulfillmentRef())
                                && InventoryDemandPlanFacts.sourceKind(branch, leaf)
                                        .equals(line.kind())
                                && leaf.quantity().compareTo(line.originalQty()) == 0
                                && leaf.unit().equals(line.unit())
                                && leaf.definitionHash().equals(line.definitionHash()));
                require(
                        line.issuedQty() != null
                                && line.releasedQty() != null
                                && line.issuedQty().signum() >= 0
                                && line.releasedQty().signum() >= 0
                                && line.issuedQty()
                                                .add(line.releasedQty())
                                                .compareTo(line.originalQty())
                                        <= 0);
                if ("HELD".equals(receipt.status()))
                    require(line.issuedQty().signum() == 0 && line.releasedQty().signum() == 0);
                if ("EXPIRED".equals(receipt.status()))
                    require(
                            line.issuedQty().signum() == 0
                                    && line.releasedQty().compareTo(line.originalQty()) == 0);
            }
        }
        require(lines.isEmpty());
    }

    private InventoryDemandPlanPort inventory() {
        var providers = inventories.orderedStream().limit(2).toList();
        if (providers.size() != 1) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        return providers.getFirst();
    }

    private static void require(boolean condition) {
        IndustryChannelOrderStore.require(condition);
    }
}
