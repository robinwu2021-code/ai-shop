package ai.neargo.shop.inventory.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvOutboundLine;
import ai.neargo.inventory.domain.entity.InvOutboundOrder;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.inventory.domain.mapper.InventoryProductionMapper;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.OutboundService;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryProductionFacts;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Production issues use the original outbound owner; no FOOD stock balance or second cost ledger is
 * created.
 */
@Service
@ConditionalOnInventory
public class InventoryProductionPortImpl implements InventoryProductionPort {
    private final InventoryAclService acl;
    private final MaterialCatalogQueryPort materials;
    private final OwnerMapper owners;
    private final OutboundOrderMapper documents;
    private final OutboundLineMapper documentLines;
    private final OutboundService outbound;
    private InventoryDemandPlanPort plans;
    private InventoryProductionMapper persistence;
    private JsonMapper json;

    @Autowired
    public void configureExact(
            InventoryDemandPlanPort plans, InventoryProductionMapper persistence, JsonMapper json) {
        this.plans = plans;
        this.persistence = persistence;
        this.json = json;
    }

    public InventoryProductionPortImpl(
            InventoryAclService acl,
            MaterialCatalogQueryPort materials,
            OwnerMapper owners,
            OutboundOrderMapper documents,
            OutboundLineMapper documentLines,
            OutboundService outbound) {
        this.acl = acl;
        this.materials = materials;
        this.owners = owners;
        this.documents = documents;
        this.documentLines = documentLines;
        this.outbound = outbound;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Receipt consume(Command input) {
        var command = normalized(input);
        if (command.planProof() != null) return exact(command, false);
        String owner = owner(command);
        var locked =
                owners.selectOne(
                        Wrappers.<InvOwner>lambdaQuery()
                                .eq(InvOwner::getOwnerId, owner)
                                .last("FOR UPDATE"));
        if (locked == null || !"ACTIVE".equals(locked.getStatus()))
            throw failure(ErrorCode.FORBIDDEN);
        var prior = existing(owner, command);
        if (prior != null) return receipt(prior, command);
        String location = acl.stockLocationOfExistingStore(command.entityNo(), command.storeNo());
        if (location == null) throw failure(ErrorCode.NOT_FOUND);
        try {
            InventoryProductionFacts.integerTotal(command);
        } catch (ArithmeticException unsupported) {
            throw failure(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
        var facts =
                materials.getMaterials(
                        command.entityNo(), command.lines().stream().map(Line::itemRef).toList());
        for (Line line : command.lines()) {
            var fact =
                    facts.stream()
                            .filter(f -> f.itemId().equals(line.itemRef()))
                            .findFirst()
                            .orElseThrow(() -> failure(ErrorCode.NOT_FOUND));
            if (!MaterialCatalogFacts.validFact(fact)
                    || !command.entityNo().equals(fact.entityNo())
                    || !fact.baseUom().equals(line.unit())
                    || !fact.definitionHash().equals(line.definitionHash()))
                throw failure(ErrorCode.CONFLICT);
            try {
                line.quantity().intValueExact();
            } catch (ArithmeticException unsupported) {
                throw failure(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            }
        }
        String purpose = "MATERIAL_WASTE".equals(command.purpose()) ? "SCRAP" : "PRODUCTION";
        var draft =
                new OutboundService.Draft(
                        owner,
                        location,
                        purpose,
                        source(command),
                        null,
                        "MATERIAL_WASTE".equals(command.purpose()) ? "OTHER" : null,
                        LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC),
                        fingerprint(command),
                        command.lines().stream()
                                .map(
                                        l ->
                                                new OutboundService.Line(
                                                        l.itemRef(),
                                                        l.quantity().intValueExact(),
                                                        l.unit()))
                                .toList());
        String documentNo = outbound.postDirectly(draft, "FOOD_PRODUCTION");
        var posted = existing(owner, command);
        if (posted == null || !documentNo.equals(posted.getOutboundNo()))
            throw failure(ErrorCode.CONFLICT);
        return receipt(posted, command);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", readOnly = true)
    public Receipt query(Command input) {
        var command = normalized(input);
        if (command.planProof() != null) return exact(command, true);
        var existing = existing(owner(command), command);
        return existing == null
                ? new Receipt(
                        command.eventNo(),
                        fingerprint(command),
                        "ABSENT",
                        null,
                        List.of(),
                        null,
                        null)
                : receipt(existing, command);
    }

    private Receipt exact(Command command, boolean queryOnly) {
        if (plans == null) throw failure(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var proof = command.planProof();
        if (proof.scope() == null
                || !command.entityNo().equals(proof.scope().entityNo())
                || proof.planHash() == null
                || !proof.planHash().matches("[0-9a-f]{64}")
                || proof.leaves().isEmpty()) throw failure(ErrorCode.BAD_REQUEST);
        String owner = owner(command);
        if (persistence.activeOwners(queryOnly, owner).size() != 1)
            throw failure(ErrorCode.FORBIDDEN);
        var prior = persistence.operations(owner, command.storeNo(), command.eventNo());
        if (!prior.isEmpty()) {
            var row = prior.getFirst();
            if (!fingerprint(command).equals(row.requestHash())) throw failure(ErrorCode.CONFLICT);
            return json.readValue(row.receiptSnapshot(), Receipt.class);
        }
        if (queryOnly)
            return new Receipt(
                    command.eventNo(), fingerprint(command), "ABSENT", null, List.of(), null, null);
        var plan = plans.query(proof.scope());
        if (!proof.planHash().equals(plan.planHash())) throw failure(ErrorCode.CONFLICT);
        var targets = new ArrayList<InventoryDemandPlanPort.Target>();
        var aggregate = new TreeMap<String, BigDecimal>();
        var seen = new HashSet<String>();
        for (var selected : proof.leaves()) {
            if (!seen.add(selected.leafRef())) throw failure(ErrorCode.BAD_REQUEST);
            var matches =
                    plan.lines().stream()
                            .filter(l -> selected.leafRef().equals(l.leafRef()))
                            .toList();
            if (matches.size() != 1) throw failure(ErrorCode.CONFLICT);
            var leaf = matches.getFirst();
            if (!"RECIPE_MATERIAL".equals(leaf.kind())
                    || !proof.sourceComponentRef().equals(leaf.sourceComponentRef()))
                throw failure(ErrorCode.CONFLICT);
            InventoryDemandPlanFacts.quantity(selected.quantity(), false);
            var original =
                    command.lines().stream()
                            .filter(l -> l.itemRef().equals(leaf.itemRef()))
                            .findFirst()
                            .orElseThrow(() -> failure(ErrorCode.CONFLICT));
            if (!original.unit().equals(leaf.unit())
                    || !original.definitionHash().equals(leaf.definitionHash()))
                throw failure(ErrorCode.CONFLICT);
            aggregate.merge(leaf.itemRef(), selected.quantity(), BigDecimal::add);
            targets.add(new InventoryDemandPlanPort.Target(leaf.leafRef(), selected.quantity()));
        }
        if (aggregate.size() != command.lines().size()
                || command.lines().stream()
                        .anyMatch(
                                l ->
                                        aggregate.get(l.itemRef()) == null
                                                || aggregate
                                                                .get(l.itemRef())
                                                                .compareTo(l.quantity())
                                                        != 0)) throw failure(ErrorCode.CONFLICT);
        String operation =
                "FP"
                        + PricingSnapshotHash.of(List.of(command.storeNo(), command.eventNo()))
                                .substring(0, 62);
        if (List.of("REMAKE", "MATERIAL_SUPPLEMENT", "MATERIAL_WASTE").contains(command.purpose()))
            plans.supplement(
                    proof.scope(),
                    "FS"
                            + PricingSnapshotHash.of(List.of(command.storeNo(), command.eventNo()))
                                    .substring(0, 62),
                    proof.planHash(),
                    targets);
        var posted = plans.issue(proof.scope(), operation, proof.planHash(), "PRODUCTION", targets);
        var actual = new ArrayList<IssuedLine>();
        Long total = 0L;
        String currency = null;
        String document = null;
        var opRecord = persistence.demandDelta(owner, operation);
        var deltas =
                json.readValue(opRecord.deltaSnapshot(), InventoryDemandPlanPortImpl.Delta[].class);
        for (var delta : deltas) {
            var selected =
                    proof.leaves().stream()
                            .filter(l -> l.leafRef().equals(delta.leafRef()))
                            .findFirst()
                            .orElseThrow(() -> failure(ErrorCode.CONFLICT));
            var leaf =
                    posted.lines().stream()
                            .filter(l -> delta.leafRef().equals(l.leafRef()))
                            .findFirst()
                            .orElseThrow();
            var history = persistence.ledger(owner, Long.valueOf(delta.ledgerRef()));
            if ((history.qtyDelta()).negate().compareTo(delta.qty()) != 0)
                throw failure(ErrorCode.CONFLICT);
            Long cost = null;
            String cc = history.costCurrency();
            if (history.unitCostMinor() != null && cc != null)
                try {
                    cost =
                            delta.qty()
                                    .multiply(BigDecimal.valueOf(history.unitCostMinor()))
                                    .longValueExact();
                } catch (ArithmeticException unknown) {
                    cost = null;
                }
            if (cost == null || currency != null && !currency.equals(cc)) total = null;
            if (total != null) total = Math.addExact(total, cost);
            if (currency == null) currency = cc;
            document = history.docNo();
            actual.add(
                    new IssuedLine(
                            leaf.leafRef(),
                            leaf.itemRef(),
                            delta.qty(),
                            leaf.unit(),
                            document,
                            delta.ledgerRef(),
                            cost,
                            cost == null ? null : cc,
                            history.batchNo(),
                            cost == null ? "UNKNOWN" : "KNOWN"));
        }
        for (var selected : proof.leaves())
            if (actual.stream()
                            .filter(l -> l.leafRef().equals(selected.leafRef()))
                            .map(IssuedLine::quantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .compareTo(selected.quantity())
                    != 0) throw failure(ErrorCode.CONFLICT);
        if (total == null) currency = null;
        var receipt =
                new Receipt(
                        command.eventNo(),
                        fingerprint(command),
                        "CONFIRMED",
                        document,
                        command.lines(),
                        total,
                        currency,
                        actual);
        persistence.insertOperation(
                owner,
                command.storeNo(),
                command.eventNo(),
                fingerprint(command),
                posted.planNo(),
                operation,
                json.writeValueAsString(receipt));
        return receipt;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Receipt reverse(Reverse reversal) {
        if (reversal == null
                || reversal.original() == null
                || reversal.reverseEventNo() == null
                || reversal.reverseEventNo().isBlank()
                || reversal.reverseEventNo().length() > 32
                || reversal.reason() == null
                || reversal.reason().isBlank()
                || reversal.reason().length() > 255) throw failure(ErrorCode.BAD_REQUEST);
        var command = normalized(reversal.original());
        if (command.planProof() == null || plans == null) throw failure(ErrorCode.CONFLICT);
        String owner = owner(command);
        persistence.lockOwner(owner);
        var rows = persistence.originalOperations(owner, command.storeNo(), command.eventNo());
        if (rows.size() != 1) throw failure(ErrorCode.NOT_FOUND);
        var original = rows.getFirst();
        if (!fingerprint(command).equals(original.requestHash())) throw failure(ErrorCode.CONFLICT);
        String reverseHash = PricingSnapshotHash.of(reversal);
        if (original.reverseEventNo() != null) {
            if (!reversal.reverseEventNo().equals(original.reverseEventNo())
                    || !reverseHash.equals(original.reverseHash()))
                throw failure(ErrorCode.CONFLICT);
            return json.readValue(original.receiptSnapshot(), Receipt.class);
        }
        String operation =
                "FR"
                        + PricingSnapshotHash.of(
                                        List.of(command.storeNo(), reversal.reverseEventNo()))
                                .substring(0, 62);
        var reversed =
                plans.reverse(
                        command.planProof().scope(),
                        operation,
                        original.operationRef(),
                        command.planProof().planHash());
        var previous = json.readValue(original.receiptSnapshot(), Receipt.class);
        var receipt =
                new Receipt(
                        command.eventNo(),
                        fingerprint(command),
                        "REVERSED",
                        reversed.lines().stream()
                                .filter(
                                        l ->
                                                command.planProof().leaves().stream()
                                                        .anyMatch(
                                                                s ->
                                                                        s.leafRef()
                                                                                .equals(
                                                                                        l
                                                                                                .leafRef())))
                                .findFirst()
                                .orElseThrow()
                                .documentNo(),
                        previous.lines(),
                        previous.costMinor(),
                        previous.currency(),
                        previous.issuedLines());
        persistence.confirmReverse(
                reversal.reverseEventNo(),
                reverseHash,
                json.writeValueAsString(receipt),
                original.id());
        return receipt;
    }

    private String owner(Command command) {
        String owner = acl.findOwnerId(command.entityNo());
        if (owner == null) throw failure(ErrorCode.NOT_FOUND);
        return owner;
    }

    private InvOutboundOrder existing(String owner, Command command) {
        return documents.selectOne(
                Wrappers.<InvOutboundOrder>lambdaQuery()
                        .eq(InvOutboundOrder::getOwnerId, owner)
                        .eq(InvOutboundOrder::getSourceRef, source(command)));
    }

    private Receipt receipt(InvOutboundOrder original, Command command) {
        if (!fingerprint(command).equals(original.getRemark())) throw failure(ErrorCode.CONFLICT);
        var lines =
                documentLines.selectList(
                        Wrappers.<InvOutboundLine>lambdaQuery()
                                .eq(InvOutboundLine::getOwnerId, original.getOwnerId())
                                .eq(InvOutboundLine::getOutboundNo, original.getOutboundNo())
                                .orderByAsc(InvOutboundLine::getItemId));
        if (lines.size() != command.lines().size()) throw failure(ErrorCode.CONFLICT);
        for (int i = 0; i < lines.size(); i++) {
            var row = lines.get(i);
            var wanted = command.lines().get(i);
            if (!row.getItemId().equals(wanted.itemRef())
                    || !row.getUom().equals(wanted.unit())
                    || wanted.quantity().compareTo(BigDecimal.valueOf(row.getQty())) != 0) {
                throw failure(ErrorCode.CONFLICT);
            }
        }
        String state =
                "POSTED".equals(original.getStatus())
                        ? "CONFIRMED"
                        : "VOIDED".equals(original.getStatus()) ? "REVERSED" : "UNKNOWN";
        // Historical inv cost has no currency authority; do not label it with an input or invented
        // currency.
        return new Receipt(
                command.eventNo(),
                fingerprint(command),
                state,
                original.getOutboundNo(),
                command.lines(),
                null,
                null);
    }

    public static Command normalized(Command c) {
        try {
            return InventoryProductionFacts.normalize(c);
        } catch (IllegalArgumentException invalid) {
            throw failure(ErrorCode.BAD_REQUEST);
        }
    }

    private static String fingerprint(Command c) {
        return InventoryProductionFacts.hash(c);
    }

    private static String source(Command c) {
        return "FP" + PricingSnapshotHash.of(List.of(c.storeNo(), c.eventNo())).substring(0, 62);
    }

    private static BizException failure(ErrorCode code) {
        return BizException.of(code);
    }
}
