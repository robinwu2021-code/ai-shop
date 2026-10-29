package ai.neargo.shop.inventory.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.mapper.InventoryDemandPlanMapper;
import ai.neargo.inventory.domain.mapper.InventoryDemandPlanMapper.Head;
import ai.neargo.inventory.domain.mapper.InventoryDemandPlanMapper.Operation;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.StockPostingService;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Exact reservations and postings in the original isolated inventory transaction. */
@Service
@ConditionalOnInventory
@Transactional(transactionManager = "invTransactionManager")
public class InventoryDemandPlanPortImpl implements InventoryDemandPlanPort {
    private final InventoryDemandPlanMapper mapper;
    private final InventoryAclService acl;
    private final MaterialCatalogQueryPort materials;
    private final StockPostingService posting;
    private final JsonMapper json;

    public InventoryDemandPlanPortImpl(
            InventoryDemandPlanMapper mapper,
            InventoryAclService acl,
            MaterialCatalogQueryPort materials,
            StockPostingService posting,
            JsonMapper json) {
        this.mapper = mapper;
        this.acl = acl;
        this.materials = materials;
        this.posting = posting;
        this.json = json;
    }

    @Override
    public Receipt reserve(Plan p, long ttl, int lifecycle) {
        try {
            InventoryDemandPlanFacts.validate(p);
        } catch (IllegalArgumentException invalid) {
            throw bad();
        }
        if (ttl < 1 || ttl > 86400 || !Set.of(2, 3).contains(lifecycle)) throw bad();
        String owner = lock(p.scope());
        var prior = head(owner, p.scope());
        if (prior != null) {
            if (!p.planHash().equals(prior.planHash()) || lifecycle != prior.lifecycleVersion())
                throw conflict();
            return receipt(owner, p.scope());
        }
        if (!mapper.legacyReservations(owner, p.scope().externalRef()).isEmpty()) throw conflict();
        var expires = LocalDateTime.now().plusSeconds(ttl);
        mapper.insertPlan(
                owner,
                p.scope().externalRef(),
                p.planNo(),
                p.planHash(),
                json.writeValueAsString(p),
                lifecycle,
                expires);
        for (var branch : p.branches())
            for (var leaf : branch.leaves()) {
                String item =
                        "FINISHED_SKU".equals(branch.kind())
                                ? acl.itemIdOfSku(leaf.itemRef())
                                : leaf.itemRef();
                String store = store(branch.fulfillmentRef(), p.scope(), p);
                String location = acl.stockLocationOfExistingStore(p.scope().entityNo(), store);
                if (item == null || location == null) throw missing();
                var definitions = materials.getMaterials(p.scope().entityNo(), List.of(item));
                if (definitions.size() != 1
                        || !leaf.unit().equals(definitions.getFirst().baseUom())
                        || !leaf.definitionHash().equals(definitions.getFirst().definitionHash()))
                    throw conflict();
                if ("FINISHED_SKU".equals(branch.kind())) {
                    if (!branch.unit().equals(definitions.getFirst().baseUom())
                            || branch.quantity().compareTo(leaf.quantity()) != 0
                            || branch.quantity().stripTrailingZeros().scale() > 0
                                    && !Integer.valueOf(1).equals(definitions.getFirst().weighed()))
                        throw conflict();
                }
                if (mapper.reserveBalance(leaf.quantity(), owner, item, location, leaf.quantity())
                        != 1) throw BizException.of(ErrorCode.STOCK_SHORT_ITEMS, item);
                mapper.insertLeaf(
                        owner,
                        p.planNo(),
                        leaf.leafRef(),
                        branch.sourceComponentRef(),
                        branch.fulfillmentRef(),
                        branch.kind(),
                        item,
                        location,
                        leaf.quantity(),
                        leaf.unit(),
                        leaf.definitionHash());
            }
        return receipt(owner, p.scope());
    }

    /**
     * Store is explicit in the plan scope extension, never inferred from an arbitrary warehouse.
     */
    private String store(String fulfillment, Scope scope, Plan plan) {
        // The reservation external reference is an order; store ownership travels with each
        // fulfillment.
        var b =
                plan.branches().stream()
                        .filter(x -> x.fulfillmentRef().equals(fulfillment))
                        .findFirst()
                        .orElseThrow();
        return b.storeNo();
    }

    @Override
    public Receipt query(Scope scope) {
        String owner = lock(scope);
        expire(owner, scope);
        return receipt(owner, scope);
    }

    @Override
    public Receipt acceptUnpaid(Scope scope) {
        String owner = lock(scope);
        expire(owner, scope);
        var h = required(owner, scope);
        if (h.lifecycleVersion() != 3 || !Set.of("HELD", "ACCEPTED_UNPAID").contains(h.status()))
            throw conflict();
        mapper.acceptUnpaid(owner, h.planNo());
        return receipt(owner, scope);
    }

    @Override
    public Receipt allocatePaid(Scope scope) {
        String owner = lock(scope);
        expire(owner, scope);
        var h = required(owner, scope);
        if (!Set.of("HELD", "ACCEPTED_UNPAID", "ALLOCATED").contains(h.status())) throw conflict();
        mapper.allocatePaid(owner, h.planNo());
        return receipt(owner, scope);
    }

    @Override
    public Receipt issue(
            Scope scope, String op, String hash, String phase, List<Target> quantities) {
        return change(scope, op, hash, phase, quantities, false, true);
    }

    @Override
    public Receipt issueTo(
            Scope scope, String op, String hash, String phase, List<Target> targets) {
        return change(scope, op, hash, phase, targets, false, false);
    }

    @Override
    public Receipt releaseTo(Scope scope, String op, String hash, List<Target> targets) {
        return change(scope, op, hash, "RELEASE", targets, true, false);
    }

    private Receipt change(
            Scope scope,
            String op,
            String hash,
            String phase,
            List<Target> targets,
            boolean release,
            boolean increments) {
        key(op);
        if (targets == null
                || targets.isEmpty()
                || targets.size() > 5000
                || !Set.of("PRODUCTION", "HANDOVER", "RELEASE").contains(phase)) throw bad();
        String owner = lock(scope);
        expire(owner, scope);
        var h = required(owner, scope);
        String no = h.planNo();
        if (!hash.equals(h.planHash())) throw conflict();
        var canonical =
                targets.stream()
                        .sorted(Comparator.comparing(Target::leafRef))
                        .map(
                                t ->
                                        new Target(
                                                t.leafRef(),
                                                InventoryDemandPlanFacts.quantity(
                                                        t.cumulativeQty(), true)))
                        .toList();
        if (canonical.stream().map(Target::leafRef).distinct().count() != canonical.size())
            throw bad();
        String request =
                PricingSnapshotHash.of(List.of(scope, op, hash, phase, increments, canonical));
        var prior = operation(owner, op);
        if (prior != null) return replay(owner, scope, prior, request);
        if (!release && !Set.of("ACCEPTED_UNPAID", "ALLOCATED").contains(h.status()))
            throw conflict();
        var deltas = new ArrayList<Delta>();
        String doc = BizKey.next("IOUT");
        int n = 0;
        for (var target : canonical) {
            var row = leaf(owner, no, target.leafRef());
            if (!release
                    && !("PRODUCTION".equals(phase) ? "RECIPE_MATERIAL" : "FINISHED_SKU")
                            .equals(row.branchKind())) throw conflict();
            var priorQty = (release ? row.releasedQty() : row.issuedQty());
            var cumulative =
                    increments ? priorQty.add(target.cumulativeQty()) : target.cumulativeQty();
            var delta = cumulative.subtract(priorQty);
            if (delta.signum() < 0
                    || cumulative
                                    .add((release ? row.issuedQty() : row.releasedQty()))
                                    .compareTo(row.qty())
                            > 0) throw conflict();
            if (delta.signum() == 0) continue;
            String item = row.itemId(), location = row.locationId();
            if (mapper.unreserveBalance(delta, owner, item, location, delta) != 1) throw conflict();
            Long cost = 0L;
            String currency = null;
            String ledger = null;
            if (!release) {
                var valuation = mapper.valuation(owner, item);
                var parts = new ArrayList<BatchPosting>();
                if (valuation.trackBatch() == 1) {
                    var remaining = delta;
                    var lots = mapper.availableLots(owner, item, location);
                    for (var lot : lots) {
                        if (remaining.signum() == 0) break;
                        var taking = remaining.min(lot.qty());
                        parts.add(
                                new BatchPosting(
                                        taking,
                                        lot.unitCostMinor(),
                                        lot.currency(),
                                        lot.batchNo(),
                                        lot.lotRef()));
                        remaining = remaining.subtract(taking);
                    }
                    if (remaining.signum() != 0)
                        throw BizException.of(ErrorCode.STOCK_SHORT_ITEMS, item);
                } else
                    parts.add(
                            new BatchPosting(
                                    delta,
                                    valuation.defaultCostMinor(),
                                    valuation.costCurrency(),
                                    null,
                                    null));
                for (var part : parts) {
                    Long partCost = null;
                    if (part.cost() != null && part.currency() != null)
                        try {
                            partCost =
                                    part.qty()
                                            .multiply(BigDecimal.valueOf(part.cost()))
                                            .longValueExact();
                        } catch (ArithmeticException unknown) {
                            partCost = null;
                        }
                    if (partCost == null || currency != null && !currency.equals(part.currency()))
                        cost = null;
                    if (cost != null) cost = Math.addExact(cost, partCost);
                    if (currency == null) currency = part.currency();
                    ledger =
                            posting.postExact(
                                            new StockPostingService.ExactPostingDoc(
                                                    owner,
                                                    "OUT",
                                                    doc,
                                                    phase,
                                                    LocalDateTime.now(),
                                                    "SYSTEM",
                                                    List.of(
                                                            new StockPostingService.ExactLine(
                                                                    ++n,
                                                                    item,
                                                                    location,
                                                                    part.qty(),
                                                                    part.cost(),
                                                                    part.currency(),
                                                                    part.batchNo(),
                                                                    part.lotRef(),
                                                                    null))))
                                    .getFirst()
                                    .toString();
                    mapper.insertOutboundLine(
                            owner,
                            doc,
                            n,
                            item,
                            part.qty(),
                            row.unit(),
                            part.cost(),
                            part.batchNo());
                    deltas.add(
                            new Delta(
                                    target.leafRef(),
                                    part.qty(),
                                    item,
                                    location,
                                    part.cost(),
                                    part.currency(),
                                    part.batchNo(),
                                    part.lotRef(),
                                    ledger));
                }
                if (cost == null) currency = null;
            }
            Long aggregateCost =
                    release
                            ? row.costMinor()
                            : row.issuedQty().signum() == 0
                                    ? cost
                                    : row.costMinor() == null || cost == null
                                            ? null
                                            : Math.addExact(row.costMinor(), cost);
            mapper.updateProgress(
                    release,
                    cumulative,
                    release ? null : doc,
                    ledger,
                    aggregateCost,
                    currency,
                    owner,
                    no,
                    target.leafRef());
        }
        if (deltas.stream().map(Delta::location).distinct().count() > 1) throw conflict();
        if (!release && !deltas.isEmpty())
            mapper.insertOutbound(
                    doc,
                    owner,
                    deltas.getFirst().location(),
                    phase,
                    op,
                    deltas.stream().map(Delta::qty).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (release) mapper.releaseCompletedHold(owner, no);
        var result = receipt(owner, scope);
        mapper.insertOperation(
                owner,
                no,
                op,
                request,
                phase,
                json.writeValueAsString(result),
                json.writeValueAsString(deltas));
        return result;
    }

    private record BatchPosting(
            BigDecimal qty, Long cost, String currency, String batchNo, String lotRef) {}

    public record Delta(
            String leafRef,
            BigDecimal qty,
            String item,
            String location,
            Long unitCost,
            String currency,
            String batchNo,
            String lotRef,
            String ledgerRef) {}

    @Override
    public Receipt supplement(Scope scope, String op, String hash, List<Target> quantities) {
        key(op);
        String owner = lock(scope);
        var h = required(owner, scope);
        if (!hash.equals(h.planHash())
                || !Set.of("ACCEPTED_UNPAID", "ALLOCATED").contains(h.status())
                || quantities == null
                || quantities.isEmpty()
                || quantities.size() > 5000) throw conflict();
        var normalized =
                quantities.stream()
                        .sorted(Comparator.comparing(Target::leafRef))
                        .map(
                                t ->
                                        new Target(
                                                t.leafRef(),
                                                InventoryDemandPlanFacts.quantity(
                                                        t.cumulativeQty(), false)))
                        .toList();
        if (normalized.stream().map(Target::leafRef).distinct().count() != normalized.size())
            throw bad();
        String request = PricingSnapshotHash.of(List.of(scope, op, hash, "SUPPLEMENT", normalized));
        var prior = operation(owner, op);
        if (prior != null) return replay(owner, scope, prior, request);
        for (var target : normalized) {
            var l = leaf(owner, h.planNo(), target.leafRef());
            if (!"RECIPE_MATERIAL".equals(l.branchKind())) throw conflict();
            if (mapper.reserveSupplement(
                            target.cumulativeQty(),
                            owner,
                            l.itemId(),
                            l.locationId(),
                            target.cumulativeQty())
                    != 1) throw BizException.of(ErrorCode.STOCK_SHORT_ITEMS, l.itemId());
            mapper.supplementLeaf(target.cumulativeQty(), l.id());
        }
        var receipt = receipt(owner, scope);
        mapper.insertSupplement(
                owner,
                h.planNo(),
                op,
                request,
                json.writeValueAsString(receipt),
                json.writeValueAsString(normalized));
        return receipt;
    }

    @Override
    public Receipt reverse(Scope scope, String op, String original, String hash) {
        key(op);
        key(original);
        String owner = lock(scope);
        var h = required(owner, scope);
        if (!hash.equals(h.planHash())) throw conflict();
        String request = PricingSnapshotHash.of(List.of(scope, op, original, hash));
        var repeat = operation(owner, op);
        if (repeat != null) return replay(owner, scope, repeat, request);
        var source = operation(owner, original);
        if (source == null
                || !h.planNo().equals(source.planNo())
                || !Set.of("PRODUCTION", "HANDOVER").contains(source.kind())
                || source.reversed() != 0) throw conflict();
        var deltas = json.readValue(source.deltaSnapshot(), Delta[].class);
        String doc = BizKey.next("IREV");
        int n = 0;
        for (var d : deltas) {
            var ledger =
                    posting.postExact(
                                    new StockPostingService.ExactPostingDoc(
                                            owner,
                                            "IN",
                                            doc,
                                            "PRODUCTION_REVERSE",
                                            LocalDateTime.now(),
                                            "SYSTEM",
                                            List.of(
                                                    new StockPostingService.ExactLine(
                                                            ++n,
                                                            d.item(),
                                                            d.location(),
                                                            d.qty(),
                                                            d.unitCost(),
                                                            d.currency(),
                                                            d.batchNo(),
                                                            d.lotRef(),
                                                            null))))
                            .getFirst();
            var leaf = leaf(owner, h.planNo(), d.leafRef());
            mapper.insertInboundLine(
                    owner, doc, n, d.item(), d.qty(), leaf.unit(), d.unitCost(), d.batchNo());
            mapper.markLeafDocument(doc, ledger.toString(), leaf.id());
            mapper.restoreReservation(d.qty(), owner, d.item(), d.location());
            if (mapper.reverseLeaf(d.qty(), owner, h.planNo(), d.leafRef(), d.qty()) != 1)
                throw conflict();
        }
        if (deltas.length > 0)
            mapper.insertInbound(
                    doc,
                    owner,
                    deltas[0].location(),
                    original,
                    Arrays.stream(deltas).map(Delta::qty).reduce(BigDecimal.ZERO, BigDecimal::add));
        mapper.markReversed(owner, original);
        var r = receipt(owner, scope);
        mapper.insertReversal(
                owner,
                h.planNo(),
                op,
                request,
                original,
                json.writeValueAsString(r),
                json.writeValueAsString(deltas));
        return r;
    }

    private Receipt replay(String owner, Scope scope, Operation row, String hash) {
        if (!hash.equals(row.requestHash())) throw conflict();
        if (row.reversed() != 0) throw conflict();
        return json.readValue(row.receiptSnapshot(), Receipt.class);
    }

    private void expire(String owner, Scope scope) {
        var h = head(owner, scope);
        if (h == null || !"HELD".equals(h.status())) return;
        var until = h.expiresAt();
        if (until.isAfter(LocalDateTime.now())) return;
        for (var row : mapper.leaves(owner, h.planNo())) {
            var remaining = row.qty().subtract(row.issuedQty()).subtract(row.releasedQty());
            mapper.expireUnreserve(remaining, owner, row.itemId(), row.locationId(), remaining);
            mapper.expireLeaf(row.id());
        }
        mapper.expirePlan(h.id());
    }

    private String lock(Scope scope) {
        if (scope == null || !"MAIN".equals(scope.tenantNo())) throw bad();
        String owner = acl.findOwnerId(scope.entityNo());
        if (owner == null) throw missing();
        var rows = mapper.lockOwner(owner);
        if (rows.size() != 1) throw missing();
        return owner;
    }

    private Head head(String owner, Scope scope) {
        var rows = mapper.heads(owner, scope.externalRef());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Head required(String owner, Scope scope) {
        var h = head(owner, scope);
        if (h == null) throw missing();
        return h;
    }

    private Operation operation(String owner, String op) {
        var rows = mapper.operations(owner, op);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private InventoryDemandPlanMapper.Leaf leaf(String owner, String no, String leaf) {
        var rows = mapper.findLeaves(owner, no, leaf);
        if (rows.size() != 1) throw conflict();
        return rows.getFirst();
    }

    private Receipt receipt(String owner, Scope scope) {
        var h = head(owner, scope);
        if (h == null) return new Receipt(scope, null, null, "ABSENT", 0, null, List.of());
        var rows = mapper.orderedLeaves(owner, h.planNo());
        var lines =
                rows.stream()
                        .map(
                                r ->
                                        new Progress(
                                                r.leafRef(),
                                                r.componentRef(),
                                                r.fulfillmentRef(),
                                                r.branchKind(),
                                                r.itemId(),
                                                r.qty(),
                                                r.issuedQty(),
                                                r.releasedQty(),
                                                r.unit(),
                                                r.definitionHash(),
                                                r.documentNo(),
                                                r.ledgerRef(),
                                                r.costMinor(),
                                                r.currency(),
                                                r.costMinor() == null ? "UNKNOWN" : "KNOWN"))
                        .toList();
        return new Receipt(
                scope,
                h.planNo(),
                h.planHash(),
                h.status(),
                h.lifecycleVersion(),
                h.expiresAt().atZone(ZoneId.systemDefault()).toInstant(),
                lines);
    }

    private static void key(String v) {
        if (!MaterialCatalogFacts.validKey(v, 64)) throw bad();
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static BizException missing() {
        return BizException.of(ErrorCode.NOT_FOUND);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
