package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.entity.PrdExecutionRetention;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.mapper.ProductMappers.ExecutionRetentionMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import ai.neargo.shop.product.mapper.StockRestoreMapper;
import ai.neargo.shop.product.policy.ExecutionStockRetentionGuard;
import ai.neargo.shop.product.policy.ExecutionStockRetentionSchemaVerifier;
import ai.neargo.shop.product.service.ExecutionStockRetentionService;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.PlatformExecutionStockRetentionComponent;
import ai.neargo.shop.spi.product.ExecutionStockRetentionIntentPort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionIntentPort.AcceptedComponentLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionIntentPort.AcceptedComponentAuthority;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentKind;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.AcknowledgeIssued;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.OwnerProof;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Receipt;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.ReleaseAfterNoEffect;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionState;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RowProof;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.EvidenceOutcome;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.ContinuityState;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort.CurrentActor;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

/** Pins complete existing PLATFORM originals; no owner IO under stock locks and no second quantity ledger. */
@Service
public final class ExecutionStockRetentionServiceImpl implements ExecutionStockRetentionService,
        PlatformExecutionStockRetentionComponent {
    private final StockLockMapper stocks;
    private final ExecutionRetentionMapper links;
    private final StockRestoreMapper claims;
    private final SkuMapper skus;
    private final ExecutionStockRetentionGuard guard;
    private final ExecutionStockRetentionSchemaVerifier schema;
    private final ObjectProvider<ServiceExecutionEvidencePort> evidence;
    private final ObjectMapper json;
    private final TransactionTemplate writes;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<ExecutionStockRetentionIntentPort> intents;

    private record Provenance(String json, String hash, String actorNo, String namespaceHash,
            String personnelHash) { }

    private Provenance human(CurrentActor actor) {
        return new Provenance(json.writeValueAsString(actor), actor.authorityHash(), actor.actorNo(),
                actor.namespaceHash(), ExecutionEvidenceContract.hash("EXECUTION_RETAIN_PERSONNEL_V1",
                        actor.personnel()));
    }

    private Provenance accepted(AcceptedComponentAuthority authority) {
        var human = authority.route().originalHumanAuthority();
        return new Provenance(json.writeValueAsString(authority), authority.authorityHash(), "SYSTEM:RETENTION",
                human.namespaceHash(), ExecutionEvidenceContract.hash("EXECUTION_RETAIN_PERSONNEL_V1",
                        human.personnel()));
    }

    public ExecutionStockRetentionServiceImpl(StockLockMapper stocks, ExecutionRetentionMapper links,
            StockRestoreMapper claims, SkuMapper skus, ExecutionStockRetentionGuard guard,
            ExecutionStockRetentionSchemaVerifier schema, ObjectProvider<ServiceExecutionEvidencePort> evidence,
            ObjectMapper json, @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.stocks = stocks;
        this.links = links;
        this.claims = claims;
        this.skus = skus;
        this.guard = guard;
        this.schema = schema;
        this.evidence = evidence;
        this.json = json;
        this.writes = new TransactionTemplate(manager);
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Receipt retain(RetainExecutionStock request) {
        outside();
        var lookup = request.lookup();
        if (!platform(lookup)) return receipt(lookup, Outcome.UNAVAILABLE, "STOCK_AUTHORITY_UNAVAILABLE", List.of());
        if (!installed()) return receipt(lookup, Outcome.UNAVAILABLE, "RETENTION_SCHEMA_UNAVAILABLE", List.of());
        var provider = evidence.getIfAvailable();
        if (provider == null) return receipt(lookup, Outcome.PENDING, "EXECUTION_EVIDENCE_UNAVAILABLE", List.of());
        var original = provider.queryOriginalEvidence(new ExecutionEvidenceLookup(request.reference()));
        if (original == null || original.outcome() != EvidenceOutcome.PROVEN) {
            return receipt(lookup, Outcome.PENDING, "EXECUTION_EVIDENCE_UNAVAILABLE", List.of());
        }
        original.validate();
        if (!compatible(lookup, original)) return receipt(lookup, Outcome.CONFLICT,
            "EXECUTION_ORIGINAL_CONFLICT", List.of());
        // This is the last external owner call. Core rechecks current SELF after its own source/personnel IO.
        CurrentActor actor = provider.authorizeCurrent(new ExecutionEvidenceLookup(request.reference()));
        actor.validate(request.reference());
        var identity = DelegatedIdentityContext.current();
        require(actor.actorNo().equals(identity.actorNo()) && actor.scope().tenantNo().equals(identity.tenantNo()));
        try {
            return tx(() -> retainLocked(lookup, original, human(actor)));
        } catch (DuplicateKeyException collision) {
            // The failed insert transaction has rolled back in full before an exact current reread.
            return tx(() -> observeLocked(lookup));
        }
    }

    private Receipt retainLocked(RetentionLookup lookup, ExecutionOriginalEvidence evidence, Provenance actor) {
        var rows = stocks.lockOriginal(lookup.stockOriginal().orderNo());
        var allLinks = guard.lockedLinks(rows);
        if (allLinks.stream().anyMatch(link -> !Integer.valueOf(0).equals(link.getDeleted())
                || link.getVersion() == null || link.getVersion() < 0
                || !List.of("RETAINED", "RELEASED_NO_EFFECT").contains(link.getState()))) {
            return receipt(lookup, Outcome.CONFLICT, "INVALID_PERMANENT_LINK", List.of());
        }
        var existing = links.findExactOriginalSet(lookup.retentionNo());
        if (rows.isEmpty()) return receipt(lookup, Outcome.CONFLICT, "STOCK_ORIGINAL_MISSING", List.of());
        var priorClaims = claims.lockOriginalClaims(rows.stream().map(PrdStockLock::getId).toList());
        if (!priorClaims.isEmpty()) return receipt(lookup, Outcome.CONFLICT, "ORIGINAL_ALREADY_RESTORED", List.of());
        String invalid = invalid(lookup, rows);
        if (invalid != null) return receipt(lookup, Outcome.CONFLICT, invalid, List.of());
        if (!existing.isEmpty()) return ownerReceipt(lookup, rows, existing);
        if (allLinks.stream().anyMatch(link -> lookup.reference().executionNo().equals(link.getExecutionNo()))) {
            return receipt(lookup, Outcome.CONFLICT, "PERMANENT_EXECUTION_ASSOCIATION", List.of());
        }
        List<RowProof> proofs = rows.stream().map(row -> rowProof(row, row.getVersion(), 0)).toList();
        String ownerSetHash = ExecutionEvidenceContract.hash("EXECUTION_PLATFORM_OWNER_SET_V1", proofs);
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var link = new PrdExecutionRetention();
            var ref = lookup.reference();
            link.setTenantNo(ref.scope().tenantNo());
            link.setEntityNo(ref.scope().entityNo());
            link.setStoreNo(ref.scope().storeNo());
            link.setRetentionNo(lookup.retentionNo());
            link.setExecutionNo(ref.executionNo());
            link.setUnitNo(ref.unitNo());
            link.setGrantNo(ref.grantNo());
            link.setAttemptNo(ref.attemptNo());
            link.setReferenceSchema(ref.schema());
            link.setReferenceJson(json.writeValueAsString(ref));
            link.setReferenceHash(ref.referenceHash());
            link.setOriginalBindingBytesHash(ref.originalBindingBytesHash());
            link.setPreparedGrantRevision(ref.preparedGrantRevision());
            link.setOriginalRowId(row.getId());
            link.setOriginalLockNo(row.getLockNo());
            link.setStockOriginalJson(json.writeValueAsString(lookup.stockOriginal()));
            link.setOriginalHash(ExecutionStockRetentionContract.originalHash(lookup.stockOriginal()));
            link.setOriginalManifestHash(row.getSourceManifestHash());
            link.setConfirmationOperationNo(lookup.confirmationOperationNo());
            link.setSourceConfirmationNo(evidence.facts().confirmationNo());
            link.setOriginalRowVersion(row.getVersion());
            link.setOriginalDisposition(row.getStatus());
            link.setOriginalRowJson(json.writeValueAsString(proofs.get(i)));
            link.setOwnerSetHash(ownerSetHash);
            link.setOwnerSetCount(rows.size());
            link.setRequestHash(lookup.requestHash());
            // These columns are the frozen planned BTRC identity, never an actual RBC/payload digest.
            link.setResourceCommandNo(ref.plannedResourceCommandNo());
            link.setResourceCommandHash(ref.plannedResourceCommandHash());
            link.setPlanHash(ref.planHash());
            link.setCreatorNamespaceHash(actor.namespaceHash());
            link.setCreatorPersonnelHash(actor.personnelHash());
            link.setAcceptedAuthorityJson(actor.json());
            link.setAcceptedAuthorityHash(actor.hash());
            link.setState("RETAINED");
            link.setDeleted(0);
            link.setVersion(0L);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            link.setCreatedAt(now);
            link.setUpdatedAt(now);
            link.setCreatedBy(actor.actorNo());
            link.setUpdatedBy(actor.actorNo());
            require(links.insertRetained(link) == 1);
        }
        return ownerReceipt(lookup, rows, links.findExactOriginalSet(lookup.retentionNo()));
    }

    @Override
    public Receipt queryOriginal(RetentionLookup lookup) {
        outside();
        if (!platform(lookup)) return receipt(lookup, Outcome.UNAVAILABLE, "STOCK_AUTHORITY_UNAVAILABLE", List.of());
        if (!installed()) return receipt(lookup, Outcome.UNAVAILABLE, "RETENTION_SCHEMA_UNAVAILABLE", List.of());
        return tx(() -> observeLocked(lookup));
    }

    private Receipt observeLocked(RetentionLookup lookup) {
        var rows = stocks.lockOriginal(lookup.stockOriginal().orderNo());
        guard.lockedLinks(rows);
        var exactLinks = links.findExactOriginalSet(lookup.retentionNo());
        if (rows.isEmpty()) return receipt(lookup, Outcome.CONFLICT, "STOCK_ORIGINAL_MISSING", List.of());
        if (!claims.lockOriginalClaims(rows.stream().map(PrdStockLock::getId).toList()).isEmpty()) {
            return receipt(lookup, Outcome.CONFLICT, "ORIGINAL_ALREADY_RESTORED", List.of());
        }
        String invalid = invalid(lookup, rows);
        if (invalid != null) return receipt(lookup, Outcome.CONFLICT, invalid, List.of());
        return ownerReceipt(lookup, rows, exactLinks);
    }

    private Receipt ownerReceipt(RetentionLookup lookup, List<PrdStockLock> rows,
        List<PrdExecutionRetention> retained) {
        if (retained.isEmpty()) return receipt(lookup, Outcome.UNKNOWN, "RETENTION_UNKNOWN", List.of());
        if (retained.size() != rows.size()) return receipt(lookup, Outcome.CONFLICT, "RETENTION_COVERAGE", List.of());
        var proofs = new ArrayList<RowProof>();
        String ownerSetHash = retained.getFirst().getOwnerSetHash();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var link = retained.get(i);
            var ref = lookup.reference();
            if (!Integer.valueOf(0).equals(link.getDeleted()) || !lookup.retentionNo().equals(link.getRetentionNo())
                    || !ref.scope().tenantNo().equals(link.getTenantNo()) || !ref.scope().entityNo().equals(
                        link.getEntityNo())
                    || !ref.scope().storeNo().equals(link.getStoreNo()) || !ref.executionNo().equals(
                        link.getExecutionNo())
                    || !ref.unitNo().equals(link.getUnitNo()) || !ref.grantNo().equals(link.getGrantNo())
                    || !Objects.equals(ref.attemptNo(), link.getAttemptNo()) || !ref.schema().equals(
                        link.getReferenceSchema())
                    || !Objects.equals(ref.preparedGrantRevision(), link.getPreparedGrantRevision())
                    || !ref.originalBindingBytesHash().equals(link.getOriginalBindingBytesHash())
                    || !ref.planHash().equals(link.getPlanHash())
                    || !ref.plannedResourceCommandNo().equals(link.getResourceCommandNo())
                    || !ref.plannedResourceCommandHash().equals(link.getResourceCommandHash())
                    || !ref.referenceHash().equals(link.getReferenceHash())
                    || !json.writeValueAsString(ref).equals(link.getReferenceJson())
                    || !json.writeValueAsString(lookup.stockOriginal()).equals(link.getStockOriginalJson())
                    || !ExecutionStockRetentionContract.originalHash(lookup.stockOriginal()).equals(
                        link.getOriginalHash())
                    || !row.getSourceManifestHash().equals(link.getOriginalManifestHash())
                    || !row.getLockNo().equals(link.getOriginalLockNo())
                    || !Objects.equals(retained.getFirst().getSourceConfirmationNo(), link.getSourceConfirmationNo())
                    || !Objects.equals(retained.getFirst().getAcceptedAuthorityJson(), link.getAcceptedAuthorityJson())
                    || !Objects.equals(retained.getFirst().getAcceptedAuthorityHash(), link.getAcceptedAuthorityHash())
                    || !Objects.equals(retained.getFirst().getIssueObservationJson(), link.getIssueObservationJson())
                    || !Objects.equals(retained.getFirst().getIssueObservationHash(), link.getIssueObservationHash())
                    || !lookup.requestHash().equals(link.getRequestHash())
                    || !lookup.confirmationOperationNo().equals(link.getConfirmationOperationNo())
                    || !row.getId().equals(link.getOriginalRowId()) || !row.getVersion().equals(
                        link.getOriginalRowVersion())
                    || !row.getStatus().equals(link.getOriginalDisposition())
                    || !ownerSetHash.equals(link.getOwnerSetHash()) || link.getOwnerSetCount() != rows.size()
                    || !"RETAINED".equals(link.getState()) || link.getVersion() == null || link.getVersion() < 0) {
                return receipt(lookup, Outcome.CONFLICT, "PERMANENT_RETENTION_CONFLICT", List.of());
            }
            var frozen = rowProof(row, link.getOriginalRowVersion(), 0);
            if (!json.writeValueAsString(frozen).equals(link.getOriginalRowJson())) {
                return receipt(lookup, Outcome.CONFLICT, "ORIGINAL_ROW_CHANGED", List.of());
            }
            proofs.add(rowProof(row, link.getOriginalRowVersion(), link.getVersion()));
        }
        var frozenRows = proofs.stream().map(p -> new RowProof(p.originalRowId(), p.lockNo(), p.skuNo(),
            p.sourceStoreNo(),
                p.effectiveStoreNo(), p.fulfillmentRef(), p.lifecycleVersion(), p.presale(), p.originalQuantity(),
                p.originalManifestHash(), p.confirmationOperationNo(), p.retainedOriginalVersion(),
                p.currentOriginalVersion(), p.disposition(), 0, List.of())).toList();
        if (!ownerSetHash.equals(ExecutionEvidenceContract.hash("EXECUTION_PLATFORM_OWNER_SET_V1", frozenRows))) {
            return receipt(lookup, Outcome.CONFLICT, "OWNER_SET_HASH_CONFLICT", List.of());
        }
        var first = retained.getFirst();
        var owner = new OwnerProof("PLATFORM", lookup.reference().scope(), lookup.stockOriginal().orderNo(),
                lookup.retentionNo(), lookup.requestHash(), ExecutionStockRetentionContract.originalHash(
                    lookup.stockOriginal()),
                lookup.confirmationOperationNo(), ownerSetHash, rows.size(), proofs, RetentionState.RETAINED,
                retained.stream().mapToLong(link -> Math.addExact(link.getVersion(), 1)).max().orElseThrow(),
                first.getIssueObservationJson(), first.getIssueObservationHash(), null);
        return receipt(lookup, Outcome.RETAINED, null, List.of(owner));
    }

    private String invalid(RetentionLookup lookup, List<PrdStockLock> rows) {
        if (rows.isEmpty()) return "STOCK_ORIGINAL_MISSING";
        var original = lookup.stockOriginal();
        var actual = new ArrayList<StockPort.SkuQty>();
        for (var row : rows) {
            if (!Integer.valueOf(0).equals(row.getDeleted()) || !original.tenantNo().equals(row.getTenantNo())
                    || !original.orderNo().equals(row.getLockNo()) || row.getQty() == null || row.getQty() <= 0
                    || row.getVersion() == null || row.getVersion() < 0 || row.getFulfillmentRef() == null
                    || !Objects.equals(original.lifecycleVersion(), row.getLifecycleVersion())
                    || !original.manifestHash().equals(row.getSourceManifestHash())
                    || !lookup.confirmationOperationNo().equals(row.getConfirmationOperationNo())
                    || !lookup.reference().scope().storeNo().equals(row.getSourceStoreNo())
                    || !(PrdStockLock.CONFIRMED.equals(row.getStatus())
                            || original.lifecycleVersion() == 2 && PrdStockLock.ALLOCATED.equals(row.getStatus()))) {
                return "STOCK_ORIGINAL_CONFLICT";
            }
            var identities = skus.lockSkuCurrent(row.getSkuNo());
            if (identities.isEmpty() || identities.stream().anyMatch(sku ->
                    !Integer.valueOf(0).equals(sku.getDeleted()) || !original.tenantNo().equals(sku.getTenantNo())
                    || !original.entityNo().equals(sku.getEntityNo()))) return "STOCK_OWNER_CONFLICT";
            actual.add(new StockPort.SkuQty(row.getSkuNo(), row.getQty(), row.getSourceStoreNo(),
                    row.getFulfillmentRef(), row.getLifecycleVersion()));
        }
        if (rows.stream().map(PrdStockLock::getStatus).distinct().count() != 1) return "MIXED_STOCK_DISPOSITION";
        return original.manifestHash().equals(StockDispositionContract.manifestHash(
            StockDispositionContract.needs(actual)))
                ? null : "STOCK_MANIFEST_COVERAGE";
    }

    private RowProof rowProof(PrdStockLock row, long originalVersion, long linkVersion) {
        return new RowProof(row.getId(), row.getLockNo(), row.getSkuNo(), row.getSourceStoreNo(), row.getStoreNo(),
                row.getFulfillmentRef(), row.getLifecycleVersion(), Boolean.TRUE.equals(row.getPresale()), row.getQty(),
                row.getSourceManifestHash(), row.getConfirmationOperationNo(), originalVersion, row.getVersion(),
                PrdStockLock.CONFIRMED.equals(row.getStatus()) ? "CONSUMED" : "ALLOCATED", linkVersion, List.of());
    }

    private boolean compatible(RetentionLookup lookup, ExecutionOriginalEvidence evidence) {
        var f = evidence.facts();
        var ref = lookup.reference();
        return ref.equals(evidence.reference()) && lookup.stockOriginal().equals(f.stockOriginal())
                && lookup.confirmationOperationNo().equals(f.stockConfirmationOperationNo())
                && f.grantState() == GrantState.PREPARED && f.unitState(
                    ) == ExecutionOriginalEvidence.UnitState.RESERVED
                && ref.executionNo().equals(f.currentExecutionNo()) && ref.attemptNo() == f.unitAttemptNo()
                && f.suspension() == null && f.continuity().state() == ContinuityState.READY_UNCHANGED;
    }

    @Override
    public Receipt releaseAfterNoEffect(ReleaseAfterNoEffect request) {
        outside();
        // No original resource-seal owner exists in B1. Missing proof never clears a link or releases a balance.
        return receipt(request.lookup(), platform(request.lookup()) ? Outcome.PENDING : Outcome.UNAVAILABLE,
                "RESOURCE_SEAL_PROVIDER_UNAVAILABLE", List.of());
    }

    @Override
    public Receipt acknowledgeIssued(AcknowledgeIssued request) {
        outside();
        var lookup = request.lookup();
        if (!platform(lookup)) return receipt(lookup, Outcome.UNAVAILABLE, "STOCK_AUTHORITY_UNAVAILABLE", List.of());
        if (!installed()) return receipt(lookup, Outcome.UNAVAILABLE, "RETENTION_SCHEMA_UNAVAILABLE", List.of());
        return tx(() -> acknowledgeLocked(request));
    }

    private Receipt acknowledgeLocked(AcknowledgeIssued request) {
        var lookup = request.lookup();
        var current = observeLocked(lookup);
        if (current.outcome() != Outcome.RETAINED) return current;
        var owner = current.owners().getFirst();
        if (owner.rows().stream().anyMatch(row -> !"CONSUMED".equals(row.disposition()))) {
            return receipt(lookup, Outcome.PENDING, "ORIGINAL_NOT_CONSUMED", List.of());
        }
        String issueObservationHash = ExecutionStockRetentionContract.issueObservationHash(lookup, owner.rows());
        if (!request.expectedIssueObservationHash().equals(issueObservationHash)) {
            return receipt(lookup, Outcome.CONFLICT, "ISSUE_OBSERVATION_CONFLICT", List.of());
        }
        String observation = json.writeValueAsString(List.of(lookup,
            ExecutionStockRetentionContract.issueRows(owner.rows())));
        var exact = links.findExactOriginalSet(lookup.retentionNo());
        for (var link : exact) {
            if (link.getIssueObservationHash() != null || link.getIssueObservationJson() != null) {
                require(issueObservationHash.equals(link.getIssueObservationHash())
                        && observation.equals(link.getIssueObservationJson()));
            } else require(links.recordIssuedObservation(link, observation, issueObservationHash) == 1);
        }
        return observeLocked(lookup);
    }

    @Override
    public PlatformExecutionStockRetentionComponent.ComponentReceipt retainComponent(
            PlatformExecutionStockRetentionComponent.ComponentRetain request) {
        outside();
        var component = request.component();
        component.validate();
        var lookup = component.original();
        if (!installed()) return componentReceipt(component, Outcome.UNAVAILABLE, "RETENTION_SCHEMA_UNAVAILABLE");
        var provider = evidence.getIfAvailable();
        if (provider == null) return componentReceipt(component, Outcome.UNAVAILABLE, "EXECUTION_EVIDENCE_UNAVAILABLE");
        var original = provider.queryOriginalEvidence(new ExecutionEvidenceLookup(lookup.reference()));
        if (!ExecutionStockRetentionAggregateContract.compatible(lookup, original)
                || !component.sourceConfirmationNo().equals(original.facts().confirmationNo())) {
            return componentReceipt(component, Outcome.PENDING, "EXECUTION_ORIGINAL_UNPROVEN");
        }
        // Last external call before this HUMAN owner mutation is actual current SELF, including DUAL.
        var actor = provider.authorizeCurrent(new ExecutionEvidenceLookup(lookup.reference()));
        actor.validate(lookup.reference());
        var current = DelegatedIdentityContext.current();
        require(actor.actorNo().equals(current.actorNo()) && actor.scope().tenantNo().equals(current.tenantNo()));
        return retainComponentLocked(component, original, human(actor));
    }

    @Override
    public PlatformExecutionStockRetentionComponent.ComponentReceipt continueAcceptedRetention(
            PlatformExecutionStockRetentionComponent.AcceptedRetention request) {
        outside();
        var component = request.lookup().component();
        component.validate();
        if (!installed()) return componentReceipt(component, Outcome.UNAVAILABLE, "RETENTION_SCHEMA_UNAVAILABLE");
        var authorityProvider = intents.getIfAvailable();
        var sourceProvider = evidence.getIfAvailable();
        if (authorityProvider == null || sourceProvider == null) {
            return componentReceipt(component, Outcome.UNAVAILABLE, "HOST_ACCEPTED_INTENT_UNAVAILABLE");
        }
        var target = new AcceptedComponentLookup(component.original(), request.acceptedIntentNo(),
                request.acceptedIntentHash(), ComponentKind.PLATFORM, component.componentNo(),
                        component.componentHash());
        var authority = authorityProvider.queryAcceptedComponent(target);
        if (authority == null) return componentReceipt(component, Outcome.UNAVAILABLE,
                "HOST_ACCEPTED_INTENT_UNAVAILABLE");
        if (authority.outcome() != Outcome.RETAINED) {
            return componentReceipt(component, authority.outcome(), authority.reason());
        }
        authority.validate(target);
        var original = sourceProvider.queryOriginalEvidence(
                new ExecutionEvidenceLookup(component.original().reference()));
        if (!ExecutionStockRetentionAggregateContract.compatible(component.original(), original)
                || !component.sourceConfirmationNo().equals(original.facts().confirmationNo())) {
            return componentReceipt(component, Outcome.PENDING, "EXECUTION_ORIGINAL_UNPROVEN");
        }
        return retainComponentLocked(component, original, accepted(authority));
    }

    private PlatformExecutionStockRetentionComponent.ComponentReceipt retainComponentLocked(
            PlatformExecutionStockRetentionComponent.ComponentDescriptor component, ExecutionOriginalEvidence original,
            Provenance provenance) {
        Receipt result;
        try {
            result = tx(() -> retainLocked(component.original(), original, provenance));
        } catch (DuplicateKeyException collision) {
            result = tx(() -> observeLocked(component.original()));
        }
        return bound(component, result);
    }

    @Override
    public PlatformExecutionStockRetentionComponent.ComponentReceipt queryOriginalComponent(
            PlatformExecutionStockRetentionComponent.ComponentLookup request) {
        outside();
        var component = request.component();
        component.validate();
        if (!installed()) return componentReceipt(component, Outcome.UNAVAILABLE, "RETENTION_SCHEMA_UNAVAILABLE");
        return bound(component, tx(() -> observeLocked(component.original())));
    }

    @Override
    public PlatformExecutionStockRetentionComponent.ComponentReceipt acknowledgeIssuedComponent(
            PlatformExecutionStockRetentionComponent.ComponentIssued request) {
        outside();
        var component = request.lookup().component();
        component.validate();
        if (!installed()) return componentReceipt(component, Outcome.UNAVAILABLE, "RETENTION_SCHEMA_UNAVAILABLE");
        return bound(component, tx(() -> acknowledgeLocked(new AcknowledgeIssued(component.original(),
                request.expectedIssueObservationHash()))));
    }

    @Override
    public PlatformExecutionStockRetentionComponent.ComponentReceipt releaseComponent(
            PlatformExecutionStockRetentionComponent.ComponentRelease request) {
        outside();
        request.lookup().component().validate();
        return componentReceipt(request.lookup().component(), Outcome.PENDING, "RESOURCE_SEAL_PROVIDER_UNAVAILABLE");
    }

    private PlatformExecutionStockRetentionComponent.ComponentReceipt bound(
            PlatformExecutionStockRetentionComponent.ComponentDescriptor component, Receipt result) {
        if (result.outcome() == Outcome.RETAINED) {
            // Exact persisted parent and source confirmation, including an adopted pre-existing V1 PLATFORM pin.
            var exact = DataScopeContext.executeWithoutScope(() -> links.findExactOriginalSet(
                    component.original().retentionNo()));
            if (exact.isEmpty() || exact.stream().anyMatch(link -> !component.sourceConfirmationNo()
                    .equals(link.getSourceConfirmationNo()))) {
                return componentReceipt(component, Outcome.CONFLICT, "SOURCE_CONFIRMATION_CONFLICT");
            }
            var first = exact.getFirst();
            var payload = json.readTree(first.getAcceptedAuthorityJson());
            if ("EXECUTION_STOCK_ACCEPTED_COMPONENT_AUTHORITY_V2".equals(payload.path("schema").asString())) {
                var authority = json.readValue(first.getAcceptedAuthorityJson(), AcceptedComponentAuthority.class);
                authority.validate(new AcceptedComponentLookup(component.original(),
                        authority.target().acceptedIntentNo(), authority.target().acceptedIntentHash(),
                        ComponentKind.PLATFORM, component.componentNo(), component.componentHash()));
                require(authority.authorityHash().equals(first.getAcceptedAuthorityHash()));
            } else {
                var human = json.readValue(first.getAcceptedAuthorityJson(), CurrentActor.class);
                human.validate(component.original().reference());
                require(human.authorityHash().equals(first.getAcceptedAuthorityHash()));
            }
        }
        var value = PlatformExecutionStockRetentionComponent.receipt(component, result);
        value.validate();
        return value;
    }

    private PlatformExecutionStockRetentionComponent.ComponentReceipt componentReceipt(
            PlatformExecutionStockRetentionComponent.ComponentDescriptor component, Outcome outcome, String reason) {
        return PlatformExecutionStockRetentionComponent.receipt(component,
                receipt(component.original(), outcome, reason, List.of()));
    }

    private boolean installed() {
        return schema.installation() == ExecutionStockRetentionSchemaVerifier.Installation.INSTALLED;
    }

    private static boolean platform(RetentionLookup request) {
        return "PLATFORM".equals(request.stockOriginal().authority());
    }

    private <T> T tx(Supplier<T> operation) {
        return DataScopeContext.executeWithoutScope(() -> writes.execute(status -> operation.get()));
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("execution retention owner IO must begin outside a transaction");
        }
    }

    private static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }

    /** Complete typed aggregate receipt. An unavailable component never receives an invented successful owner proof. */
    public static Receipt receipt(RetentionLookup lookup, Outcome outcome, String reason, List<OwnerProof> owners) {
        String roster = ExecutionEvidenceContract.hash("EXECUTION_STOCK_OWNER_ROSTER_V1",
                List.of(lookup.stockOriginal().authority(), lookup.reference().scope(), lookup.stockOriginal(
                    ).orderNo()));
        var result = new Receipt("EXECUTION_STOCK_RETENTION_RECEIPT_V1", lookup.reference().referenceHash(),
                lookup.retentionNo(), lookup.requestHash(), lookup.stockOriginal().authority(), roster, owners,
                outcome, reason, Instant.now(), null);
        return new Receipt(result.schema(), result.referenceHash(), result.retentionNo(), result.requestHash(),
                result.frozenAuthority(), result.ownerRosterHash(), result.owners(), result.outcome(), result.reason(),
                result.observedAt(), ExecutionEvidenceContract.digest("EXECUTION_STOCK_RETENTION_RECEIPT_V1",
                    result, "receiptHash"));
    }
}
