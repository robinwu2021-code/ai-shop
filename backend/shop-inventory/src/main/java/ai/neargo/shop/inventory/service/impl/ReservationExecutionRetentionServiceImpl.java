package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvExecutionRetention;
import ai.neargo.shop.inventory.entity.InvExecutionRetentionClaim;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.entity.InvReservation;
import ai.neargo.shop.inventory.entity.InvReservationLine;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ExecutionRetentionClaimMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ExecutionRetentionMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.mapper.RefundRestoreMapper;
import ai.neargo.shop.inventory.mapper.ReturnReceiptMapper;
import ai.neargo.shop.inventory.policy.ExecutionStockRetentionSchemaVerifier;
import ai.neargo.shop.inventory.service.ReservationExecutionRetentionService;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.AcceptedRetention;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentDescriptor;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentIssued;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentLookup;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentReceipt;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentRelease;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentRetain;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.HeadProof;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.InventoryProof;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.LedgerProof;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.LineProof;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.OutboundLineProof;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.OutboundProof;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ProofMode;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.ContinuityState;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.EvidenceOutcome;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.UnitState;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort.CurrentActor;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Complete native owner proof and conservative pin; Core/source IO always finishes before Inventory roots. */
@Service
@ConditionalOnInventory
public final class ReservationExecutionRetentionServiceImpl implements ReservationExecutionRetentionService {
    private final ReservationMapper heads;
    private final ReservationLineMapper lines;
    private final OwnerMapper owners;
    private final BalanceMapper balances;
    private final OutboundOrderMapper outbound;
    private final OutboundLineMapper outboundLines;
    private final LedgerMapper ledger;
    private final RefundRestoreMapper refunds;
    private final ReturnReceiptMapper returns;
    private final ExecutionRetentionMapper links;
    private final ExecutionRetentionClaimMapper claims;
    private final ExecutionStockRetentionSchemaVerifier schema;
    private final ObjectProvider<ServiceExecutionEvidencePort> evidence;
    private final ObjectMapper json;
    private final TransactionTemplate writes;

    public ReservationExecutionRetentionServiceImpl(ReservationMapper heads, ReservationLineMapper lines,
            OwnerMapper owners, BalanceMapper balances, OutboundOrderMapper outbound, OutboundLineMapper outboundLines,
            LedgerMapper ledger, RefundRestoreMapper refunds, ReturnReceiptMapper returns,
            ExecutionRetentionMapper links, ExecutionRetentionClaimMapper claims,
            ExecutionStockRetentionSchemaVerifier schema, ObjectProvider<ServiceExecutionEvidencePort> evidence,
            ObjectMapper json, @Qualifier("invTransactionManager") PlatformTransactionManager manager) {
        this.heads = heads;
        this.lines = lines;
        this.owners = owners;
        this.balances = balances;
        this.outbound = outbound;
        this.outboundLines = outboundLines;
        this.ledger = ledger;
        this.refunds = refunds;
        this.returns = returns;
        this.links = links;
        this.claims = claims;
        this.schema = schema;
        this.evidence = evidence;
        this.json = json;
        this.writes = new TransactionTemplate(manager);
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ComponentReceipt retainComponent(ComponentRetain request) {
        outside();
        var component = request.component();
        var unavailable = unavailable(component);
        if (unavailable != null) {
            return unavailable;
        }
        var provider = evidence.getIfAvailable();
        if (provider == null) {
            return receipt(component, Outcome.UNAVAILABLE, "EXECUTION_EVIDENCE_UNAVAILABLE", null);
        }
        var lookup = new ExecutionEvidenceLookup(component.original().reference());
        var original = provider.queryOriginalEvidence(lookup);
        if (original == null || original.outcome() != EvidenceOutcome.PROVEN) {
            return receipt(component, Outcome.PENDING, "EXECUTION_ORIGINAL_UNPROVEN", null);
        }
        original.validate();
        if (!compatible(component, original)) {
            return receipt(component, Outcome.CONFLICT, "EXECUTION_ORIGINAL_CONFLICT", null);
        }
        // Core independently rechecks current HUMAN SELF after source/personnel IO, never saved authority replay.
        CurrentActor actor = provider.authorizeCurrent(lookup);
        actor.validate(component.original().reference());
        var current = DelegatedIdentityContext.current();
        require(current.actorNo().equals(actor.actorNo()) && current.tenantNo().equals(actor.scope().tenantNo()));
        try {
            return tx(() -> retainLocked(component, original, actor));
        } catch (DuplicateKeyException collision) {
            // Losing insertion transaction has rolled back completely before exact-original current observation.
            var winner = queryOriginalComponent(new ComponentLookup(component));
            return winner.outcome() == Outcome.UNKNOWN
                    ? receipt(component, Outcome.CONFLICT, "PERMANENT_EXECUTION_ASSOCIATION", null) : winner;
        } catch (OriginalConflict failure) {
            return receipt(component, Outcome.CONFLICT, failure.getMessage(), null);
        }
    }

    @Override
    public ComponentReceipt queryOriginalComponent(ComponentLookup request) {
        outside();
        var unavailable = unavailable(request.component());
        if (unavailable != null) {
            return unavailable;
        }
        try {
            return tx(() -> observeLocked(request.component()));
        } catch (OriginalConflict failure) {
            return receipt(request.component(), Outcome.CONFLICT, failure.getMessage(), null);
        }
    }

    private ComponentReceipt retainLocked(ComponentDescriptor component, ExecutionOriginalEvidence original,
            CurrentActor actor) {
        var snapshot = lock(component);
        var prior = claims.lockComponent(component.componentNo());
        var existing = links.lockComponent(component.componentNo());
        var facts = prove(component, snapshot);
        if (prior != null) {
            return ownerReceipt(component, facts, prior, existing);
        }
        check(existing.isEmpty(), "ORPHAN_COMPONENT_LINKS");
        // Permanent execution association is checked across all existing links, including terminal ones.
        for (var head : snapshot.heads()) {
            for (var link : links.lockReservation(head.getOwnerId(), head.getReservationId())) {
                check(!component.original().reference().executionNo().equals(link.getExecutionNo()),
                        "PERMANENT_EXECUTION_ASSOCIATION");
            }
        }
        var claim = new InvExecutionRetentionClaim();
        fillClaim(claim, component, original, actor, facts);
        require(claims.insert(claim) == 1);
        for (int i = 0; i < facts.lines().size(); i++) {
            var line = facts.lines().get(i);
            var head = facts.heads().stream().filter(value -> value.reservationId().equals(line.reservationId()))
                    .findFirst().orElseThrow();
            var link = new InvExecutionRetention();
            copyClaim(link, claim);
            link.setRetentionRevision(0L);
            link.setOwnerId(line.ownerId());
            link.setReservationId(line.reservationId());
            link.setOriginalLineId(line.originalLineId());
            link.setOriginalLineNo(line.lineNo());
            link.setItemId(line.itemId());
            link.setLocationId(line.locationId());
            link.setSourceSkuNo(line.sourceSkuNo());
            link.setFulfillmentRef(line.fulfillmentRef());
            link.setLifecycleVersion(head.lifecycleVersion());
            link.setOriginalQty(line.originalQty());
            link.setNativeConfirmationOperationNo(head.confirmationOperationNo());
            link.setRetainedDispositionRevision(head.dispositionRevision());
            link.setRetainedDisposition(facts.disposition());
            link.setOriginalRowJson(json.writeValueAsString(List.of(head, line)));
            require(links.insert(link) == 1);
        }
        return ownerReceipt(component, facts, claims.lockComponent(component.componentNo()),
                links.lockComponent(component.componentNo()));
    }

    private ComponentReceipt observeLocked(ComponentDescriptor component) {
        var snapshot = lock(component);
        var claim = claims.lockComponent(component.componentNo());
        var retained = links.lockComponent(component.componentNo());
        if (claim == null && retained.isEmpty()) {
            return receipt(component, Outcome.UNKNOWN, "COMPONENT_UNKNOWN", null);
        }
        return ownerReceipt(component, prove(component, snapshot), claim, retained);
    }

    private Snapshot lock(ComponentDescriptor component) {
        var originalHeads = heads.lockOriginal(component.original().stockOriginal().orderNo());
        var originalLines = new ArrayList<InvReservationLine>();
        for (var head : originalHeads) {
            originalLines.addAll(lines.lockOriginal(head.getReservationId()));
        }
        // Current permanent links follow the complete original roots/lines, before claims/outbound/balance.
        for (var head : originalHeads) {
            for (var link : links.lockReservation(head.getOwnerId(), head.getReservationId())) {
                check(originalLines.stream().anyMatch(line -> line.getId().equals(link.getOriginalLineId()))
                        && link.getRetentionRevision() != null && link.getRetentionRevision() >= 0
                        && List.of("RETAINED", "RELEASED_NO_EFFECT").contains(link.getState()),
                        "INVALID_PERMANENT_LINK");
                var header = claims.lockComponent(link.getComponentNo());
                check(header != null && Objects.equals(link.getRequestHash(), header.getRequestHash())
                        && Objects.equals(link.getReferenceHash(), header.getReferenceHash())
                        && Objects.equals(link.getComponentDescriptorHash(), header.getComponentDescriptorHash())
                        && Objects.equals(header.getState(), "RETAINED".equals(link.getState())
                                ? "RETAINED" : "SEALED_NO_EFFECT"), "ORPHAN_PERMANENT_LINK");
            }
        }
        return new Snapshot(originalHeads, originalLines);
    }

    private Facts prove(ComponentDescriptor component, Snapshot snapshot) {
        var original = component.original().stockOriginal();
        check(!snapshot.heads().isEmpty() && !snapshot.lines().isEmpty(), "STOCK_ORIGINAL_MISSING");
        var headProofs = new ArrayList<HeadProof>();
        var lineProofs = new ArrayList<LineProof>();
        var actualNeeds = new ArrayList<StockPort.SkuQty>();
        boolean allocated = snapshot.heads().stream().allMatch(head -> "ALLOCATED".equals(head.getStatus()));
        boolean consumed = snapshot.heads().stream().allMatch(head -> "COMMITTED".equals(head.getStatus()));
        check(allocated && original.lifecycleVersion() == 2 || consumed, "STOCK_NOT_WHOLE_START_READY");
        for (var head : snapshot.heads()) {
            var owner = owners.selectOne(Wrappers.<InvOwner>lambdaQuery().eq(InvOwner::getOwnerId, head.getOwnerId())
                    .last("FOR UPDATE"));
            check(owner != null && original.entityNo().equals(owner.getExternalRef())
                    && original.orderNo().equals(head.getExternalRef())
                    && Objects.equals(original.lifecycleVersion(), head.getLifecycleVersion())
                    && component.original().confirmationOperationNo().equals(head.getConfirmationOperationNo())
                    && head.getDispositionRevision() != null && head.getDispositionRevision() > 0,
                    "NATIVE_HEAD_CONFIRMATION_CONFLICT");
            var ownLines = snapshot.lines().stream().filter(line -> head.getReservationId()
                    .equals(line.getReservationId())).toList();
            check(!ownLines.isEmpty(), "STOCK_LINE_COVERAGE");
            var ownNeeds = new ArrayList<StockPort.SkuQty>();
            var seenLineNos = new HashSet<Integer>();
            for (var line : ownLines) {
                check(line.getId() != null && line.getId() > 0 && line.getLineNo() != null && line.getLineNo() > 0
                        && seenLineNos.add(line.getLineNo()) && head.getOwnerId().equals(line.getOwnerId())
                        && line.getItemId() != null && line.getLocationId() != null && line.getSourceSkuNo() != null
                        && component.original().reference().scope().storeNo().equals(line.getSourceStoreNo())
                        && line.getFulfillmentRef() != null && line.getQty() != null && line.getQty() > 0
                        && line.getIssuedQty() != null && line.getReleasedQty() != null
                        && line.getReleasedQty() == 0 && (allocated ? line.getIssuedQty() == 0
                                : original.lifecycleVersion() == 1 ? line.getIssuedQty() == 0
                                        : line.getIssuedQty().equals(line.getQty())), "STOCK_LINE_CONFLICT");
                ownNeeds.add(new StockPort.SkuQty(line.getSourceSkuNo(), line.getQty(), line.getSourceStoreNo(),
                        line.getFulfillmentRef(), head.getLifecycleVersion()));
                lineProofs.add(new LineProof(line.getId(), line.getLineNo(), line.getOwnerId(), line.getReservationId(),
                        line.getItemId(), line.getLocationId(), line.getSourceSkuNo(), line.getSourceStoreNo(),
                        line.getFulfillmentRef(), line.getQty(), line.getIssuedQty(), line.getReleasedQty(),
                        line.getOutboundNo()));
            }
            check(StockDispositionContract.manifestHash(StockDispositionContract.needs(ownNeeds))
                    .equals(head.getSourceManifestHash()), "PER_HEAD_MANIFEST_CONFLICT");
            actualNeeds.addAll(ownNeeds);
            headProofs.add(new HeadProof(head.getOwnerId(), head.getReservationId(), head.getExternalRef(),
                    head.getLifecycleVersion(), head.getSourceManifestHash(), head.getConfirmationOperationNo(),
                    head.getDispositionRevision(), head.getStatus(), head.getOutboundNo()));
        }
        check(StockDispositionContract.needs(actualNeeds).equals(component.expectedNeeds()), "FULL_MANIFEST_CONFLICT");
        var ids = snapshot.lines().stream().map(InvReservationLine::getId).toList();
        var priorRefunds = refunds.lockByOriginalLines(ids);
        var priorReturns = returns.lockByOriginalLines(ids);
        check(priorRefunds.isEmpty() && priorReturns.isEmpty(), "ORIGINAL_ALREADY_RETURNED");
        List<OutboundProof> outbounds = consumed ? consumedProof(snapshot) : List.of();
        if (allocated) {
            protectBalances(snapshot);
        }
        return new Facts(headProofs, lineProofs, outbounds, consumed ? "CONSUMED" : "ALLOCATED");
    }

    private void protectBalances(Snapshot snapshot) {
        var totals = new TreeMap<BalanceKey, Integer>(Comparator.comparing(BalanceKey::owner)
                .thenComparing(BalanceKey::item).thenComparing(BalanceKey::location));
        for (var line : snapshot.lines()) {
            totals.merge(new BalanceKey(line.getOwnerId(), line.getItemId(), line.getLocationId()), line.getQty(),
                    Math::addExact);
        }
        for (var entry : totals.entrySet()) {
            var key = entry.getKey();
            var balance = balances.selectOne(Wrappers.<InvStockBalance>lambdaQuery()
                    .eq(InvStockBalance::getOwnerId, key.owner()).eq(InvStockBalance::getItemId, key.item())
                    .eq(InvStockBalance::getLocationId, key.location()).last("FOR UPDATE"));
            check(balance != null && balance.getReserved() != null && balance.getOnHand() != null
                    && balance.getReserved() >= entry.getValue() && balance.getOnHand() >= balance.getReserved(),
                    "RESERVED_BALANCE_UNPROTECTED");
        }
    }

    private List<OutboundProof> consumedProof(Snapshot snapshot) {
        var expected = new TreeMap<String, List<InvReservationLine>>();
        for (var head : snapshot.heads()) {
            check(head.getOutboundNo() != null, "OUTBOUND_HEAD_MISSING");
            for (var line : snapshot.lines().stream().filter(value -> head.getReservationId()
                    .equals(value.getReservationId())).toList()) {
                String no = head.getLifecycleVersion() == 1 ? head.getOutboundNo() : line.getOutboundNo();
                check(no != null, "OUTBOUND_LINE_MISSING");
                expected.computeIfAbsent(no, ignored -> new ArrayList<>()).add(line);
            }
            check(expected.containsKey(head.getOutboundNo()), "OUTBOUND_HEAD_LINE_CONFLICT");
        }
        var actual = outbound.selectList(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getSourceRef, snapshot.heads().getFirst().getExternalRef())
                .orderByAsc(InvOutboundOrder::getOwnerId, InvOutboundOrder::getOutboundNo).last("FOR UPDATE"));
        check(actual.size() == expected.size() && actual.stream().allMatch(out -> expected.containsKey(
                out.getOutboundNo())), "OUTBOUND_WHOLE_COVERAGE");
        var proofs = new ArrayList<OutboundProof>();
        for (var out : actual) {
            var sourceLines = expected.get(out.getOutboundNo());
            check("POSTED".equals(out.getStatus()) && "SALE".equals(out.getPurpose()) && out.getTotalQty() != null
                    && sourceLines.stream().allMatch(line -> line.getOwnerId().equals(out.getOwnerId())
                            && line.getReservationId().equals(out.getReservationId())
                            && line.getLocationId().equals(out.getLocationId())), "OUTBOUND_SOURCE_CONFLICT");
            var postedLines = outboundLines.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                    .eq(InvOutboundLine::getOutboundNo, out.getOutboundNo()).orderByAsc(InvOutboundLine::getLineNo)
                    .last("FOR UPDATE"));
            var needed = new TreeMap<String, Integer>();
            sourceLines.forEach(line -> needed.merge(line.getItemId(), line.getQty(), Math::addExact));
            var posted = new TreeMap<String, Integer>();
            var outProofs = new ArrayList<OutboundLineProof>();
            var nos = new HashSet<Integer>();
            for (var line : postedLines) {
                check(out.getOwnerId().equals(line.getOwnerId()) && line.getQty() != null && line.getQty() > 0
                        && line.getLineNo() != null && line.getLineNo() > 0 && nos.add(line.getLineNo()),
                        "OUTBOUND_LINE_CONFLICT");
                posted.merge(line.getItemId(), line.getQty(), Math::addExact);
                outProofs.add(new OutboundLineProof(line.getId(), line.getLineNo(), line.getOwnerId(), line.getItemId(),
                        line.getQty(), line.getUnitCostMinor()));
            }
            check(needed.equals(posted) && needed.values().stream().reduce(0, Math::addExact).equals(out.getTotalQty()),
                    "OUTBOUND_QUANTITY_CONFLICT");
            var entries = ledgerRows(out.getOwnerId(), out.getOutboundNo());
            var reversals = ledgerRows(out.getOwnerId(), out.getOutboundNo() + "-R");
            check(reversals.isEmpty() && entries.size() == outProofs.size(), "SALE_LEDGER_REVERSED_OR_INCOMPLETE");
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                var line = outProofs.get(i);
                check(entry.lineNo() == line.lineNo() && entry.ownerId().equals(line.ownerId())
                        && entry.itemId().equals(line.itemId()) && entry.locationId().equals(out.getLocationId())
                        && "OUT".equals(entry.docKind()) && "SALE".equals(entry.reasonCode())
                        && entry.quantityDelta() == -line.quantity() && entry.balanceAfter() >= 0
                        && Objects.equals(entry.unitCostMinor(), line.unitCostMinor()), "SALE_LEDGER_CONFLICT");
            }
            proofs.add(new OutboundProof(out.getOutboundNo(), out.getOwnerId(), out.getLocationId(), out.getSourceRef(),
                    out.getReservationId(), out.getStatus(), out.getPurpose(), out.getTotalQty(), outProofs, entries,
                    reversals));
        }
        return List.copyOf(proofs);
    }

    private List<LedgerProof> ledgerRows(String owner, String document) {
        return ledger.selectList(Wrappers.<InvLedger>lambdaQuery().eq(InvLedger::getOwnerId, owner)
                .eq(InvLedger::getDocNo, document).orderByAsc(InvLedger::getLineNo).last("FOR UPDATE")).stream()
                .map(row -> new LedgerProof(row.getId(), row.getLineNo(), row.getOwnerId(), row.getItemId(),
                        row.getLocationId(), row.getDocKind(), row.getDocNo(), row.getReasonCode(), row.getQtyDelta(),
                        row.getBalanceAfter(), row.getUnitCostMinor())).toList();
    }

    private ComponentReceipt ownerReceipt(ComponentDescriptor component, Facts facts, InvExecutionRetentionClaim claim,
            List<InvExecutionRetention> retained) {
        check(claim != null && retained.size() == facts.lines().size(), "PERMANENT_COMPONENT_COVERAGE");
        String ownerSet = InventoryExecutionStockRetentionContract.ownerSetHash(facts.heads(), facts.lines(),
                facts.outbounds());
        check("RETAINED".equals(claim.getState()) && claim.getClaimRevision() != null && claim.getClaimRevision() >= 0
                && commonMatches(claim, component) && ownerSet.equals(claim.getOwnerSetHash())
                && Objects.equals(claim.getOwnerSetCount(), facts.lines().size())
                && json.writeValueAsString(facts).equals(claim.getOwnerSetJson()),
                "PERMANENT_COMPONENT_CONFLICT");
        var authority = json.readValue(claim.getAcceptedAuthorityJson(), CurrentActor.class);
        authority.validate(component.original().reference());
        check(authority.authorityHash().equals(claim.getAcceptedAuthorityHash()), "ACCEPTED_AUTHORITY_CONFLICT");
        if (claim.getIssueObservationHash() != null || claim.getIssueObservationJson() != null) {
            check("CONSUMED".equals(facts.disposition()) && claim.getIssueObservationHash() != null
                    && claim.getIssueObservationHash().equals(InventoryExecutionStockRetentionContract
                            .issueObservationHash(component, facts.heads(), facts.lines(), facts.outbounds()))
                    && json.writeValueAsString(List.of(component, facts.heads(), facts.lines(), facts.outbounds()))
                            .equals(claim.getIssueObservationJson()), "ISSUED_OBSERVATION_CONFLICT");
        }
        var byId = new HashMap<Long, InvExecutionRetention>();
        retained.forEach(link -> check(byId.put(link.getOriginalLineId(), link) == null, "DUPLICATE_LINK"));
        for (var line : facts.lines()) {
            var link = byId.get(line.originalLineId());
            var head = facts.heads().stream().filter(value -> value.reservationId().equals(line.reservationId()))
                    .findFirst().orElseThrow();
            check(link != null && "RETAINED".equals(link.getState()) && link.getRetentionRevision() != null
                    && link.getRetentionRevision() >= 0
                    && Objects.equals(link.getRetentionRevision(), claim.getClaimRevision())
                    && commonMatches(link, claim)
                    && line.ownerId().equals(link.getOwnerId()) && line.reservationId().equals(link.getReservationId())
                    && Objects.equals(line.lineNo(), link.getOriginalLineNo()) && line.itemId().equals(link.getItemId())
                    && line.locationId().equals(link.getLocationId())
                    && line.sourceSkuNo().equals(link.getSourceSkuNo())
                    && line.fulfillmentRef().equals(link.getFulfillmentRef())
                    && Objects.equals(line.originalQty(), link.getOriginalQty())
                    && Objects.equals(head.lifecycleVersion(), link.getLifecycleVersion())
                    && head.confirmationOperationNo().equals(link.getNativeConfirmationOperationNo())
                    && Objects.equals(head.dispositionRevision(), link.getRetainedDispositionRevision())
                    && facts.disposition().equals(link.getRetainedDisposition())
                    && json.writeValueAsString(List.of(head, line)).equals(link.getOriginalRowJson()),
                    "PERMANENT_LINE_CONFLICT");
        }
        var proof = new InventoryProof(component, claim.getAcceptedEvidenceHash(), claim.getAcceptedAuthorityHash(),
                ownerSet, facts.heads(), facts.lines(), facts.outbounds(), List.of(), List.of(), facts.disposition(),
                "RETAINED",
                Math.addExact(claim.getClaimRevision(), 1), claim.getIssueObservationHash(), null);
        proof = new InventoryProof(proof.component(), proof.acceptedEvidenceHash(), proof.acceptedAuthorityHash(),
                proof.ownerSetHash(), proof.heads(), proof.lines(), proof.outbounds(), proof.refundClaims(),
                proof.returnClaims(),
                proof.disposition(), proof.state(),
                proof.revision(), proof.issueObservationHash(), ExecutionEvidenceContract.digest(
                        "INVENTORY_EXECUTION_PROOF_V1", proof, "proofHash"));
        proof.validate();
        return receipt(component, Outcome.RETAINED, null, proof);
    }

    @Override
    public ComponentReceipt acknowledgeIssued(ComponentIssued request) {
        outside();
        var component = request.lookup().component();
        var unavailable = unavailable(component);
        if (unavailable != null) {
            return unavailable;
        }
        try {
            return tx(() -> {
                var current = observeLocked(component);
                if (current.outcome() != Outcome.RETAINED) {
                    return current;
                }
                var proof = current.proof();
                if (!"CONSUMED".equals(proof.disposition())) {
                    return receipt(component, Outcome.PENDING, "ORIGINAL_NOT_CONSUMED", null);
                }
                String hash = InventoryExecutionStockRetentionContract.issueObservationHash(component,
                        proof.heads(), proof.lines(), proof.outbounds());
                check(hash.equals(request.expectedIssueObservationHash()), "ISSUE_OBSERVATION_CONFLICT");
                String observation = json.writeValueAsString(List.of(component, proof.heads(), proof.lines(),
                        proof.outbounds()));
                var claim = claims.lockComponent(component.componentNo());
                if (claim.getIssueObservationHash() == null && claim.getIssueObservationJson() == null) {
                    require(claims.recordIssued(claim, observation, hash) == 1);
                } else {
                    check(hash.equals(claim.getIssueObservationHash())
                            && observation.equals(claim.getIssueObservationJson()), "ISSUE_OBSERVATION_CONFLICT");
                }
                for (var link : links.lockComponent(component.componentNo())) {
                    if (link.getIssueObservationHash() == null && link.getIssueObservationJson() == null) {
                        require(links.recordIssued(link, observation, hash) == 1);
                    } else {
                        check(hash.equals(link.getIssueObservationHash())
                                && observation.equals(link.getIssueObservationJson()), "ISSUE_OBSERVATION_CONFLICT");
                    }
                }
                return observeLocked(component);
            });
        } catch (OriginalConflict failure) {
            return receipt(component, Outcome.CONFLICT, failure.getMessage(), null);
        }
    }

    @Override
    public ComponentReceipt releaseAfterNoEffect(ComponentRelease request) {
        outside();
        // C04c has no genuine permanent resource seal producer yet: no header, link, stock or quantity mutation.
        return receipt(request.lookup().component(), Outcome.UNAVAILABLE, "RESOURCE_SEAL_PROVIDER_UNAVAILABLE", null);
    }

    @Override
    public ComponentReceipt continueAcceptedRetention(AcceptedRetention request) {
        outside();
        // B2b must prove actual HOST saved route/intent authority; no replay of accepted HUMAN provenance.
        return receipt(request.lookup().component(), Outcome.UNAVAILABLE, "HOST_ACCEPTED_INTENT_UNAVAILABLE", null);
    }

    private ComponentReceipt unavailable(ComponentDescriptor component) {
        component.validate();
        if (component.proofMode() != ProofMode.NATIVE_CONFIRMATION
                || !"INVENTORY".equals(component.original().stockOriginal().authority())) {
            return receipt(component, Outcome.UNAVAILABLE, "MIRROR_AUTHORITY_PROVIDER_UNAVAILABLE", null);
        }
        if (schema.installation() != ExecutionStockRetentionSchemaVerifier.Installation.INSTALLED) {
            return receipt(component, Outcome.UNAVAILABLE, "INVENTORY_RETENTION_SCHEMA_UNAVAILABLE", null);
        }
        return null;
    }

    private boolean compatible(ComponentDescriptor component, ExecutionOriginalEvidence evidence) {
        var ref = component.original().reference();
        var facts = evidence.facts();
        return ref.equals(evidence.reference()) && component.original().stockOriginal().equals(facts.stockOriginal())
                && component.original().confirmationOperationNo().equals(facts.stockConfirmationOperationNo())
                && component.sourceConfirmationNo().equals(facts.confirmationNo())
                && facts.grantState() == GrantState.PREPARED && facts.unitState() == UnitState.RESERVED
                && ref.executionNo().equals(facts.currentExecutionNo()) && ref.attemptNo() == facts.unitAttemptNo()
                && ref.sourceIntentHash().equals(facts.sourceIntentHash()) && ref.planHash().equals(facts.planHash())
                && ref.plannedResourceCommandNo().equals(facts.plannedResourceCommandNo())
                && ref.plannedResourceCommandHash().equals(facts.plannedResourceCommandHash())
                && facts.suspension() == null && facts.continuity().state() == ContinuityState.READY_UNCHANGED;
    }

    private void fillClaim(InvExecutionRetentionClaim claim, ComponentDescriptor component,
            ExecutionOriginalEvidence original, CurrentActor actor, Facts facts) {
        var lookup = component.original();
        var ref = lookup.reference();
        claim.setRetentionNo(lookup.retentionNo());
        claim.setComponentNo(component.componentNo());
        claim.setComponentDescriptorHash(component.componentHash());
        claim.setRequestHash(lookup.requestHash());
        claim.setReferenceHash(ref.referenceHash());
        claim.setSourceTenantNo(ref.scope().tenantNo());
        claim.setSourceEntityNo(ref.scope().entityNo());
        claim.setSourceStoreNo(ref.scope().storeNo());
        claim.setExecutionNo(ref.executionNo());
        claim.setUnitNo(ref.unitNo());
        claim.setGrantNo(ref.grantNo());
        claim.setAttemptNo(ref.attemptNo());
        claim.setPreparedGrantRevision(ref.preparedGrantRevision());
        claim.setReferenceSchema(ref.schema());
        claim.setReferenceJson(json.writeValueAsString(ref));
        claim.setParentOriginalJson(json.writeValueAsString(lookup.stockOriginal()));
        claim.setParentOriginalHash(ExecutionStockRetentionContract.originalHash(lookup.stockOriginal()));
        claim.setParentManifestHash(component.parentManifestHash());
        claim.setComponentDescriptorJson(json.writeValueAsString(component));
        claim.setComponentManifestHash(component.componentManifestHash());
        claim.setProofMode(component.proofMode().name());
        claim.setSourceConfirmationNo(component.sourceConfirmationNo());
        claim.setExternalStockConfirmationOperationNo(lookup.confirmationOperationNo());
        claim.setAcceptedEvidenceHash(original.proofHash());
        claim.setAcceptedAuthorityJson(json.writeValueAsString(actor));
        claim.setAcceptedAuthorityHash(actor.authorityHash());
        claim.setResourceCommandNo(ref.plannedResourceCommandNo());
        claim.setResourceCommandHash(ref.plannedResourceCommandHash());
        claim.setPlanHash(ref.planHash());
        claim.setOwnerSetHash(InventoryExecutionStockRetentionContract.ownerSetHash(facts.heads(), facts.lines(),
                facts.outbounds()));
        claim.setOwnerSetCount(facts.lines().size());
        claim.setOwnerSetJson(json.writeValueAsString(facts));
        claim.setState("RETAINED");
        claim.setClaimRevision(0L);
        claim.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        claim.setUpdatedAt(claim.getCreatedAt());
        claim.setCreatedBy(actor.actorNo());
        claim.setUpdatedBy(actor.actorNo());
    }

    private boolean commonMatches(InvExecutionRetentionClaim claim, ComponentDescriptor component) {
        var lookup = component.original();
        var ref = lookup.reference();
        return lookup.retentionNo().equals(claim.getRetentionNo())
                && component.componentNo().equals(claim.getComponentNo())
                && component.componentHash().equals(claim.getComponentDescriptorHash())
                && lookup.requestHash().equals(claim.getRequestHash())
                && ref.referenceHash().equals(claim.getReferenceHash())
                && ref.scope().tenantNo().equals(claim.getSourceTenantNo())
                && ref.scope().entityNo().equals(claim.getSourceEntityNo())
                && ref.scope().storeNo().equals(claim.getSourceStoreNo())
                && ref.executionNo().equals(claim.getExecutionNo())
                && ref.unitNo().equals(claim.getUnitNo()) && ref.grantNo().equals(claim.getGrantNo())
                && Objects.equals(ref.attemptNo(), claim.getAttemptNo())
                && Objects.equals(ref.preparedGrantRevision(), claim.getPreparedGrantRevision())
                && ref.schema().equals(claim.getReferenceSchema())
                && json.writeValueAsString(ref).equals(claim.getReferenceJson())
                && json.writeValueAsString(lookup.stockOriginal()).equals(claim.getParentOriginalJson())
                && ExecutionStockRetentionContract.originalHash(lookup.stockOriginal())
                        .equals(claim.getParentOriginalHash())
                && component.parentManifestHash().equals(claim.getParentManifestHash())
                && json.writeValueAsString(component).equals(claim.getComponentDescriptorJson())
                && component.componentManifestHash().equals(claim.getComponentManifestHash())
                && component.proofMode().name().equals(claim.getProofMode())
                && component.sourceConfirmationNo().equals(claim.getSourceConfirmationNo())
                && lookup.confirmationOperationNo().equals(claim.getExternalStockConfirmationOperationNo())
                && claim.getMirrorProofJson() == null && claim.getMirrorProofHash() == null
                && ref.plannedResourceCommandNo().equals(claim.getResourceCommandNo())
                && ref.plannedResourceCommandHash().equals(claim.getResourceCommandHash())
                && ref.planHash().equals(claim.getPlanHash())
                && claim.getAcceptedEvidenceHash() != null && claim.getAcceptedAuthorityJson() != null
                && claim.getAcceptedAuthorityHash() != null && claim.getNoEffectSealJson() == null
                && claim.getNoEffectSealNo() == null && claim.getNoEffectSealHash() == null
                && claim.getNoEffectSealRevision() == null;
    }

    private void copyClaim(InvExecutionRetention link, InvExecutionRetentionClaim claim) {
        link.setRetentionNo(claim.getRetentionNo());
        link.setComponentNo(claim.getComponentNo());
        link.setComponentDescriptorHash(claim.getComponentDescriptorHash());
        link.setRequestHash(claim.getRequestHash());
        link.setReferenceHash(claim.getReferenceHash());
        link.setSourceTenantNo(claim.getSourceTenantNo());
        link.setSourceEntityNo(claim.getSourceEntityNo());
        link.setSourceStoreNo(claim.getSourceStoreNo());
        link.setExecutionNo(claim.getExecutionNo());
        link.setUnitNo(claim.getUnitNo());
        link.setGrantNo(claim.getGrantNo());
        link.setAttemptNo(claim.getAttemptNo());
        link.setPreparedGrantRevision(claim.getPreparedGrantRevision());
        link.setReferenceSchema(claim.getReferenceSchema());
        link.setReferenceJson(claim.getReferenceJson());
        link.setParentOriginalJson(claim.getParentOriginalJson());
        link.setParentOriginalHash(claim.getParentOriginalHash());
        link.setParentManifestHash(claim.getParentManifestHash());
        link.setComponentDescriptorJson(claim.getComponentDescriptorJson());
        link.setComponentManifestHash(claim.getComponentManifestHash());
        link.setProofMode(claim.getProofMode());
        link.setSourceConfirmationNo(claim.getSourceConfirmationNo());
        link.setExternalStockConfirmationOperationNo(claim.getExternalStockConfirmationOperationNo());
        link.setMirrorProofJson(claim.getMirrorProofJson());
        link.setMirrorProofHash(claim.getMirrorProofHash());
        link.setAcceptedEvidenceHash(claim.getAcceptedEvidenceHash());
        link.setAcceptedAuthorityJson(claim.getAcceptedAuthorityJson());
        link.setAcceptedAuthorityHash(claim.getAcceptedAuthorityHash());
        link.setResourceCommandNo(claim.getResourceCommandNo());
        link.setResourceCommandHash(claim.getResourceCommandHash());
        link.setPlanHash(claim.getPlanHash());
        link.setOwnerSetHash(claim.getOwnerSetHash());
        link.setOwnerSetCount(claim.getOwnerSetCount());
        link.setState(claim.getState());
        link.setIssueObservationJson(claim.getIssueObservationJson());
        link.setIssueObservationHash(claim.getIssueObservationHash());
        link.setNoEffectSealJson(claim.getNoEffectSealJson());
        link.setNoEffectSealNo(claim.getNoEffectSealNo());
        link.setNoEffectSealRevision(claim.getNoEffectSealRevision());
        link.setNoEffectSealHash(claim.getNoEffectSealHash());

        link.setCreatedAt(claim.getCreatedAt());
        link.setCreatedBy(claim.getCreatedBy());
        link.setUpdatedAt(claim.getUpdatedAt());
        link.setUpdatedBy(claim.getUpdatedBy());
    }

    private boolean commonMatches(InvExecutionRetention link, InvExecutionRetentionClaim claim) {
        return Objects.equals(link.getRetentionNo(), claim.getRetentionNo())
                && Objects.equals(link.getComponentNo(), claim.getComponentNo())
                && Objects.equals(link.getComponentDescriptorHash(), claim.getComponentDescriptorHash())
                && Objects.equals(link.getRequestHash(), claim.getRequestHash())
                && Objects.equals(link.getReferenceHash(), claim.getReferenceHash())
                && Objects.equals(link.getSourceTenantNo(), claim.getSourceTenantNo())
                && Objects.equals(link.getSourceEntityNo(), claim.getSourceEntityNo())
                && Objects.equals(link.getSourceStoreNo(), claim.getSourceStoreNo())
                && Objects.equals(link.getExecutionNo(), claim.getExecutionNo())
                && Objects.equals(link.getUnitNo(), claim.getUnitNo())
                && Objects.equals(link.getGrantNo(), claim.getGrantNo())
                && Objects.equals(link.getAttemptNo(), claim.getAttemptNo())
                && Objects.equals(link.getPreparedGrantRevision(), claim.getPreparedGrantRevision())
                && Objects.equals(link.getReferenceSchema(), claim.getReferenceSchema())
                && Objects.equals(link.getReferenceJson(), claim.getReferenceJson())
                && Objects.equals(link.getParentOriginalJson(), claim.getParentOriginalJson())
                && Objects.equals(link.getParentOriginalHash(), claim.getParentOriginalHash())
                && Objects.equals(link.getParentManifestHash(), claim.getParentManifestHash())
                && Objects.equals(link.getComponentDescriptorJson(), claim.getComponentDescriptorJson())
                && Objects.equals(link.getComponentManifestHash(), claim.getComponentManifestHash())
                && Objects.equals(link.getProofMode(), claim.getProofMode())
                && Objects.equals(link.getSourceConfirmationNo(), claim.getSourceConfirmationNo())
                && Objects.equals(link.getExternalStockConfirmationOperationNo(),
                        claim.getExternalStockConfirmationOperationNo())
                && Objects.equals(link.getMirrorProofJson(), claim.getMirrorProofJson())
                && Objects.equals(link.getMirrorProofHash(), claim.getMirrorProofHash())
                && Objects.equals(link.getAcceptedEvidenceHash(), claim.getAcceptedEvidenceHash())
                && Objects.equals(link.getAcceptedAuthorityJson(), claim.getAcceptedAuthorityJson())
                && Objects.equals(link.getAcceptedAuthorityHash(), claim.getAcceptedAuthorityHash())
                && Objects.equals(link.getResourceCommandNo(), claim.getResourceCommandNo())
                && Objects.equals(link.getResourceCommandHash(), claim.getResourceCommandHash())
                && Objects.equals(link.getPlanHash(), claim.getPlanHash())
                && Objects.equals(link.getOwnerSetHash(), claim.getOwnerSetHash())
                && Objects.equals(link.getOwnerSetCount(), claim.getOwnerSetCount())
                && Objects.equals(link.getState(), claim.getState())
                && Objects.equals(link.getIssueObservationJson(), claim.getIssueObservationJson())
                && Objects.equals(link.getIssueObservationHash(), claim.getIssueObservationHash())
                && Objects.equals(link.getNoEffectSealJson(), claim.getNoEffectSealJson())
                && Objects.equals(link.getNoEffectSealNo(), claim.getNoEffectSealNo())
                && Objects.equals(link.getNoEffectSealRevision(), claim.getNoEffectSealRevision())
                && Objects.equals(link.getNoEffectSealHash(), claim.getNoEffectSealHash());
    }

    private <T> T tx(Supplier<T> operation) {
        return writes.execute(status -> operation.get());
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Inventory execution retention IO requires no caller transaction");
        }
    }

    private static void check(boolean valid, String reason) {
        if (!valid) {
            throw new OriginalConflict(reason);
        }
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static final class OriginalConflict extends RuntimeException {
        private OriginalConflict(String reason) {
            super(reason);
        }
    }

    private record Snapshot(List<InvReservation> heads, List<InvReservationLine> lines) {
    }

    private record Facts(List<HeadProof> heads, List<LineProof> lines, List<OutboundProof> outbounds,
            String disposition) {
    }

    private record BalanceKey(String owner, String item, String location) {
    }

    private static ComponentReceipt receipt(ComponentDescriptor component, Outcome outcome, String reason,
            InventoryProof proof) {
        var receipt = new ComponentReceipt("INVENTORY_EXECUTION_RETENTION_RECEIPT_V1", component.componentNo(),
                component.componentHash(), component.original().reference().referenceHash(),
                component.original().requestHash(), outcome, reason, proof, Instant.now(), null);
        return new ComponentReceipt(receipt.schema(), receipt.componentNo(), receipt.componentHash(),
                receipt.referenceHash(), receipt.requestHash(), receipt.outcome(), receipt.reason(), receipt.proof(),
                receipt.observedAt(), ExecutionEvidenceContract.digest("INVENTORY_EXECUTION_RETENTION_RECEIPT_V1",
                        receipt, "receiptHash"));
    }
}
