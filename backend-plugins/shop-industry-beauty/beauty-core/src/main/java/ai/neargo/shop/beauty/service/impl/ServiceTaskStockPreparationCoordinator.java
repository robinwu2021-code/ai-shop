package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.ServiceTaskPreparationVO;
import ai.neargo.shop.beauty.entity.BtyServiceExecutionStockIntent;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.ServiceExecutionStockIntentSchemaVerifier;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl.StockOriginal;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationPort;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Local durable original relation composes quantity and real HOST pins, without owning either
 * balance.
 */
public final class ServiceTaskStockPreparationCoordinator {
    private final AppointmentPersistence db;
    private final BeautyMappers.ServiceExecutionStockIntentMapper rows;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final ServiceExecutionStockIntentSchemaVerifier schema;
    private final ServiceExecutionAccess access;
    private final ObjectProvider<ServiceTaskExecutionServiceImpl> execution;
    private final ObjectProvider<ServiceExecutionPreparationPort> bridge;
    private final Clock clock;

    public ServiceTaskStockPreparationCoordinator(
            AppointmentPersistence db,
            BeautyMappers.ServiceExecutionStockIntentMapper rows,
            BeautyMappers.ServiceSubmissionMapper submissions,
            ServiceExecutionStockIntentSchemaVerifier schema,
            ServiceExecutionAccess access,
            ObjectProvider<ServiceTaskExecutionServiceImpl> execution,
            ObjectProvider<ServiceExecutionPreparationPort> bridge,
            Clock clock) {
        this.db = db;
        this.rows = rows;
        this.submissions = submissions;
        this.schema = schema;
        this.access = access;
        this.execution = execution;
        this.bridge = bridge;
        this.clock = clock;
    }

    /**
     * Only current HUMAN flow may bootstrap a reference and first accept this exact retention
     * original.
     */
    public ServiceTaskPreparationVO prepareAcceptedOriginal(ExecutionBinding binding, Grant grant) {
        DelegatedIdentityContext.outside();
        var current = access.requireSelf(binding.scope());
        execution.getObject().inspect(new StartIntentLookup(binding));
        schema.requireAvailable();
        if (grant == null || grant.state() != GrantState.PREPARED)
            return projection(binding, "QUANTITY_PENDING");
        require(binding.equals(grant.binding()));
        var provider = bridge.getIfAvailable();
        if (provider == null) return projection(binding, "PREPARATION_OWNER_UNAVAILABLE");
        var saved = find(binding);
        try {
            if (saved == null) {
                var before =
                        execution
                                .getObject()
                                .withStockOriginal(
                                        binding,
                                        original -> {
                                            requireReady(original, grant);
                                            return snapshot(original);
                                        });
                var reference = provider.referenceOriginal(new ExecutionLookup(binding));
                execution
                        .getObject()
                        .withStockOriginal(
                                binding,
                                original -> {
                                    requireReady(original, grant);
                                    requireReference(binding, original, reference, grant.grantNo());
                                    require(before.equals(snapshot(original)));
                                    return null;
                                });
                var evidence =
                        provider.queryOriginalEvidence(new ExecutionEvidenceLookup(reference));
                evidence.validate();
                require(reference.equals(evidence.reference()) && evidence.facts() != null);
                var request =
                        ExecutionStockRetentionContract.request(
                                reference,
                                evidence.facts().stockOriginal(),
                                evidence.facts().stockConfirmationOperationNo());
                require(
                        ExecutionStockRetentionAggregateContract.compatible(
                                request.lookup(), evidence));
                access.requireSame(binding.scope(), current);
                // Commit local exact intent before HUMAN retain IO; this is not accepted HOST
                // machine authority.
                try {
                    saved =
                            execution
                                    .getObject()
                                    .withStockOriginal(
                                            binding,
                                            original -> {
                                                requireReady(original, grant);
                                                requireReference(
                                                        binding,
                                                        original,
                                                        reference,
                                                        grant.grantNo());
                                                require(before.equals(snapshot(original)));
                                                var existing =
                                                        rows.lock(
                                                                binding.scope().entityNo(),
                                                                binding.scope().storeNo(),
                                                                binding.executionNo());
                                                if (existing != null) {
                                                    require(
                                                            request.lookup()
                                                                    .equals(read(existing)));
                                                    return existing;
                                                }
                                                var row =
                                                        row(
                                                                binding,
                                                                request.lookup(),
                                                                original,
                                                                evidence,
                                                                current.identity().actorNo());
                                                require(rows.insert(row) == 1);
                                                return row;
                                            });
                } catch (DuplicateKeyException collision) {
                    saved = find(binding);
                    require(saved != null && request.lookup().equals(read(saved)));
                }
            }
            var lookup = read(saved);
            var fenceBeforeIo =
                    execution
                            .getObject()
                            .withStockOriginal(
                                    binding, ServiceTaskStockPreparationCoordinator::snapshot);
            // The same original may already be accepted remotely after a lost response.
            var observed = provider.queryStockOriginal(lookup);
            requireReceipt(lookup, observed);
            access.requireSame(binding.scope(), current);
            execution.getObject().inspect(new StartIntentLookup(binding));
            if (observed.receipt().outcome() == Outcome.UNKNOWN) {
                var evidence =
                        provider.queryOriginalEvidence(
                                new ExecutionEvidenceLookup(lookup.reference()));
                require(ExecutionStockRetentionAggregateContract.compatible(lookup, evidence));
                access.requireSame(binding.scope(), current);
                execution
                        .getObject()
                        .withStockOriginal(
                                binding,
                                original -> {
                                    requireReady(original, grant);
                                    requireReference(
                                            binding, original, lookup.reference(), grant.grantNo());
                                    return null;
                                });
                try {
                    observed =
                            provider.retainStock(
                                    new RetainExecutionStock(
                                            lookup.reference(),
                                            lookup.stockOriginal(),
                                            lookup.confirmationOperationNo(),
                                            lookup.retentionNo(),
                                            lookup.requestHash()));
                } catch (RuntimeException ambiguous) {
                    // Exactly one observational ambiguity resolution; never replace the original or
                    // infer rollback.
                    observed = provider.queryStockOriginal(lookup);
                }
            }
            access.requireSame(binding.scope(), current);
            var evidence =
                    provider.queryOriginalEvidence(new ExecutionEvidenceLookup(lookup.reference()));
            access.requireSame(binding.scope(), current);
            return apply(
                    binding,
                    saved,
                    fenceBeforeIo,
                    evidence,
                    observed,
                    current.identity().actorNo());
        } catch (RuntimeException uncertain) {
            if (uncertain instanceof BizException denied
                    && (denied.errorCode() == ErrorCode.FORBIDDEN
                            || denied.errorCode() == ErrorCode.UNAUTHORIZED
                            || denied.errorCode() == ErrorCode.TOKEN_EXPIRED)) {
                throw denied;
            }
            return projection(
                    binding,
                    saved == null ? "HUMAN_REFERENCE_REQUIRED" : "AUTHENTICATED_ORIGINAL_RECOVERY");
        }
    }

    /**
     * Saved originals only: this method cannot mint a reference, accept retention or replay HUMAN
     * provenance.
     */
    public ServiceTaskPreparationVO queryAcceptedOriginal(ExecutionBinding binding) {
        DelegatedIdentityContext.outside();
        if (schema.installation()
                == ServiceExecutionStockIntentSchemaVerifier.Installation.ABSENT) {
            return missing(binding, "STOCK_SCHEMA_UNAVAILABLE");
        }
        schema.requireAvailable();
        var saved = find(binding);
        if (saved == null) return missing(binding, "HUMAN_REFERENCE_REQUIRED");
        var lookup = read(saved);
        var fenceBeforeIo =
                execution
                        .getObject()
                        .withStockOriginal(
                                binding, ServiceTaskStockPreparationCoordinator::snapshot);
        var provider = bridge.getIfAvailable();
        if (provider == null) return projection(binding, "PREPARATION_OWNER_UNAVAILABLE");
        try {
            var evidence =
                    provider.queryOriginalEvidence(new ExecutionEvidenceLookup(lookup.reference()));
            var receipt = provider.queryStockOriginal(lookup);
            return apply(binding, saved, fenceBeforeIo, evidence, receipt, "EXECUTION_RECOVERY");
        } catch (RuntimeException uncertain) {
            return projection(binding, "AUTHENTICATED_ORIGINAL_RECOVERY");
        }
    }

    /**
     * Real operation recovery resolves the saved START, then explicitly resumes this same human
     * original.
     */
    public ServiceTaskPreparationVO resumeAcceptedOriginal(
            String entity, String store, String actor, String number) {
        var binding = operationBinding(entity, store, actor, number);
        var prepared = execution.getObject().resumeTask(new StartIntentLookup(binding));
        return prepareAcceptedOriginal(binding, prepared.grant());
    }

    /**
     * GET uses persisted observations only; SELF/assignment still guard access to the stored START
     * original.
     */
    public ServiceTaskPreparationVO projectOperation(
            String entity, String store, String actor, String number) {
        var binding = operationBinding(entity, store, actor, number);
        return projection(binding, null);
    }

    private ExecutionBinding operationBinding(
            String entity, String store, String actor, String number) {
        var current = access.requireSelf(new ExecutionScope("MAIN", entity, store));
        if (!actor.equals(current.identity().actorNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        var source = db.read(() -> submissions.selectSubmission(entity, store, number));
        require(source != null && "START".equals(source.getAction()));
        var command =
                db.json.readValue(
                        source.getFrozenCommand(),
                        ServiceTaskExecutionServiceImpl.StartCommand.class);
        var binding = command.intent().binding();
        require(
                entity.equals(binding.scope().entityNo())
                        && store.equals(binding.scope().storeNo())
                        && number.equals(binding.submissionNo())
                        && source.getWorkOrderNo().equals(binding.workOrderNo()));
        execution.getObject().inspect(new StartIntentLookup(binding));
        return binding;
    }

    private ServiceTaskPreparationVO apply(
            ExecutionBinding binding,
            BtyServiceExecutionStockIntent expected,
            String fenceBeforeIo,
            ExecutionOriginalEvidence evidence,
            ExecutionStockRetentionPreparationObservation observation,
            String actor) {
        var lookup = read(expected);
        evidence.validate();
        require(lookup.reference().equals(evidence.reference()));
        requireReceipt(lookup, observation);
        var receipt = observation.receipt();
        return execution
                .getObject()
                .withStockOriginal(
                        binding,
                        original -> {
                            var row =
                                    rows.lock(
                                            binding.scope().entityNo(),
                                            binding.scope().storeNo(),
                                            binding.executionNo());
                            require(
                                    row != null
                                            && lookup.equals(read(row))
                                            && binding.submissionNo()
                                                    .equals(row.getSubmissionNo()));
                            requireReference(
                                    binding,
                                    original,
                                    lookup.reference(),
                                    lookup.reference().grantNo());
                            if (!fenceBeforeIo.equals(snapshot(original))) {
                                return view(
                                        binding,
                                        row,
                                        locallyReady(original)
                                                ? "OBSERVATION_SUPERSEDED"
                                                : "SOURCE_SUSPENDED");
                            }
                            if (!expected.getVersion().equals(row.getVersion())
                                    || !expected.getObservationEpoch()
                                            .equals(row.getObservationEpoch())) {
                                return view(binding, row, "OBSERVATION_SUPERSEDED");
                            }
                            boolean ready =
                                    locallyReady(original)
                                            && ExecutionStockRetentionAggregateContract.compatible(
                                                    lookup, evidence);
                            String state =
                                    !locallyReady(original)
                                            ? "SOURCE_SUSPENDED"
                                            : !ready
                                                    ? "STOCK_RETENTION_PENDING"
                                                    : receipt.outcome() == Outcome.RETAINED
                                                            ? "STOCK_RETAINED"
                                                            : receipt.outcome() == Outcome.CONFLICT
                                                                    ? "CONFLICT"
                                                                    : "STOCK_RETENTION_PENDING";
                            if (receipt.routeHash() != null) {
                                require(
                                        row.getRouteNo() == null
                                                || row.getRouteNo().equals(receipt.routeNo())
                                                        && row.getRouteHash()
                                                                .equals(receipt.routeHash()));
                                row.setRouteNo(receipt.routeNo());
                                row.setRouteHash(receipt.routeHash());
                            }
                            // UNKNOWN never erases a previously observed accepted route or a
                            // verified pinned receipt.
                            if (receipt.routeHash() != null || row.getRouteNo() == null) {
                                row.setReceiptJson(db.json.writeValueAsString(observation));
                                row.setReceiptHash(receipt.receiptHash());
                                row.setReceiptObservedAt(local(receipt.observedAt()));
                            }
                            row.setEvidenceJson(db.json.writeValueAsString(evidence));
                            row.setEvidenceHash(evidence.proofHash());
                            row.setEvidenceObservedAt(local(evidence.observedAt()));
                            row.setObservationState(state);
                            row.setPendingReason(
                                    "SOURCE_SUSPENDED".equals(state)
                                            ? "SOURCE_SUSPENDED"
                                            : receipt.outcome() == Outcome.UNKNOWN
                                                    ? "AUTHENTICATED_ORIGINAL_RECOVERY"
                                                    : "STOCK_RETAINED".equals(state)
                                                            ? null
                                                            : receipt.reason());
                            row.setSourceFenceRevision(original.fence().getSourceFenceRevision());
                            row.setUpdatedAt(local(clock.instant()));
                            row.setUpdatedBy(actor);
                            require(
                                    rows.observe(
                                                    row,
                                                    expected.getVersion(),
                                                    expected.getObservationEpoch(),
                                                    original.fence().getObservationEpoch(),
                                                    original.fence().getVersion())
                                            == 1);
                            return view(binding, row, null);
                        });
    }

    private BtyServiceExecutionStockIntent find(ExecutionBinding binding) {
        return db.read(
                () ->
                        rows.find(
                                binding.scope().entityNo(),
                                binding.scope().storeNo(),
                                binding.executionNo()));
    }

    private ServiceTaskPreparationVO projection(ExecutionBinding binding, String reason) {
        var installation = schema.installation();
        if (installation != ServiceExecutionStockIntentSchemaVerifier.Installation.INSTALLED) {
            return missing(
                    binding,
                    installation == ServiceExecutionStockIntentSchemaVerifier.Installation.CORRUPT
                            ? "STOCK_SCHEMA_CORRUPT"
                            : "STOCK_SCHEMA_UNAVAILABLE");
        }
        var row = find(binding);
        if (row == null)
            return missing(binding, reason == null ? "HUMAN_REFERENCE_REQUIRED" : reason);
        read(row);
        return execution
                .getObject()
                .withStockOriginal(
                        binding,
                        original ->
                                view(
                                        binding,
                                        row,
                                        locallyReady(original) ? reason : "SOURCE_SUSPENDED"));
    }

    private ServiceTaskPreparationVO missing(ExecutionBinding binding, String reason) {
        return execution
                .getObject()
                .withStockOriginal(
                        binding,
                        original -> {
                            String quantity =
                                    original.fence().getObservedGrantState() == null
                                            ? "PENDING"
                                            : original.fence().getObservedGrantState();
                            String state =
                                    !locallyReady(original)
                                            ? "SOURCE_SUSPENDED"
                                            : !"PREPARED".equals(quantity)
                                                    ? "QUANTITY_PENDING"
                                                    : "HUMAN_REFERENCE_REQUIRED".equals(reason)
                                                            ? "HUMAN_REFERENCE_REQUIRED"
                                                            : "STOCK_RETENTION_PENDING";
                            return progress(
                                    binding.submissionNo(),
                                    binding.workOrderNo(),
                                    binding.taskNo(),
                                    binding.executionNo(),
                                    quantity,
                                    state,
                                    "UNKNOWN",
                                    "SOURCE_SUSPENDED".equals(state) ? state : reason,
                                    null);
                        });
    }

    private ServiceTaskPreparationVO view(
            ExecutionBinding binding, BtyServiceExecutionStockIntent row, String reason) {
        return progress(
                binding.submissionNo(),
                binding.workOrderNo(),
                binding.taskNo(),
                binding.executionNo(),
                "PREPARED",
                "SOURCE_SUSPENDED".equals(reason)
                        ? "SOURCE_SUSPENDED"
                        : reason != null ? "STOCK_RETENTION_PENDING" : row.getObservationState(),
                row.getReceiptJson() == null
                        ? "UNKNOWN"
                        : db.json
                                .readValue(
                                        row.getReceiptJson(),
                                        ExecutionStockRetentionPreparationObservation.class)
                                .receipt()
                                .outcome()
                                .name(),
                reason == null ? row.getPendingReason() : reason,
                row.getReceiptObservedAt() == null
                        ? null
                        : row.getReceiptObservedAt().toInstant(ZoneOffset.UTC));
    }

    public static ServiceTaskPreparationVO progress(
            String submission,
            String work,
            String task,
            String execution,
            String quantity,
            String state,
            String stock,
            String reason,
            Instant observedAt) {
        return new ServiceTaskPreparationVO(
                submission,
                work,
                task,
                execution,
                state,
                quantity,
                stock,
                reason,
                "STOCK_RETAINED".equals(state)
                        ? null
                        : "HUMAN_REFERENCE_REQUIRED".equals(state)
                                ? "HUMAN_REFERENCE_REQUIRED"
                                : "AUTHENTICATED_ORIGINAL_RECOVERY",
                observedAt);
    }

    private RetentionLookup read(BtyServiceExecutionStockIntent row) {
        var ref = db.json.readValue(row.getReferenceJson(), ExecutionReference.class);
        var lookup = db.json.readValue(row.getLookupJson(), RetentionLookup.class);
        ref.validate();
        require(
                ref.equals(lookup.reference())
                        && row.getReferenceHash().equals(ref.referenceHash())
                        && row.getLookupHash()
                                .equals(
                                        ExecutionEvidenceContract.hash(
                                                "BEAUTY_STOCK_LOOKUP_BYTES_V1", lookup))
                        && row.getTenantNo().equals(ref.scope().tenantNo())
                        && row.getEntityNo().equals(ref.scope().entityNo())
                        && row.getStoreNo().equals(ref.scope().storeNo())
                        && row.getWorkOrderNo().equals(ref.workOrderNo())
                        && row.getTaskNo().equals(ref.taskNo())
                        && row.getFenceNo().equals(ref.sourceFenceNo())
                        && row.getExecutionNo().equals(ref.executionNo())
                        && row.getAttemptNo() == ref.attemptNo()
                        && row.getBindingHash().equals(ref.bindingHash())
                        && row.getSourceIntentHash().equals(ref.sourceIntentHash())
                        && row.getResourceCommandNo().equals(ref.plannedResourceCommandNo())
                        && row.getResourceCommandHash().equals(ref.plannedResourceCommandHash())
                        && row.getPlanHash().equals(ref.planHash())
                        && row.getRetentionNo().equals(lookup.retentionNo())
                        && row.getRequestHash().equals(lookup.requestHash())
                        && row.getVersion() >= 0
                        && row.getObservationEpoch() >= 0
                        && row.getSourceFenceRevision() > 0
                        && row.getDeleted() == 0
                        && row.getReferenceJson().equals(db.json.writeValueAsString(ref))
                        && row.getLookupJson().equals(db.json.writeValueAsString(lookup))
                        && (row.getRouteNo() == null) == (row.getRouteHash() == null)
                        && (row.getEvidenceJson() == null) == (row.getEvidenceHash() == null)
                        && (row.getReceiptJson() == null) == (row.getReceiptHash() == null));
        if (row.getEvidenceJson() != null) {
            var evidence =
                    db.json.readValue(row.getEvidenceJson(), ExecutionOriginalEvidence.class);
            evidence.validate();
            require(
                    ref.equals(evidence.reference())
                            && evidence.proofHash().equals(row.getEvidenceHash()));
        }
        if (row.getReceiptJson() != null) {
            var observation =
                    db.json.readValue(
                            row.getReceiptJson(),
                            ExecutionStockRetentionPreparationObservation.class);
            requireReceipt(lookup, observation);
            var receipt = observation.receipt();
            require(
                    receipt.receiptHash().equals(row.getReceiptHash())
                            && (receipt.routeHash() == null
                                    || Objects.equals(row.getRouteNo(), receipt.routeNo())
                                            && Objects.equals(
                                                    row.getRouteHash(), receipt.routeHash())));
        }
        return lookup;
    }

    private static void requireReceipt(
            RetentionLookup lookup, ExecutionStockRetentionPreparationObservation observation) {
        require(observation != null);
        observation.validate(lookup);
    }

    private static void requireReference(
            ExecutionBinding binding,
            StockOriginal original,
            ExecutionReference ref,
            String grantNo) {
        ref.validate();
        var command = original.command();
        var intent = command.intent();
        require(
                binding.scope().equals(ref.scope())
                        && binding.executionNo().equals(ref.executionNo())
                        && binding.taskNo().equals(ref.taskNo())
                        && binding.workOrderNo().equals(ref.workOrderNo())
                        && binding.attemptNo() == ref.attemptNo()
                        && grantNo.equals(ref.grantNo())
                        && ServiceExecutionContract.bindingHash(binding).equals(ref.bindingHash())
                        && binding.payloadHash().equals(ref.payloadHash())
                        && ExecutionEvidenceContract.bindingBytesHash(
                                        ServiceExecutionContract.bindingJson(binding))
                                .equals(ref.originalBindingBytesHash())
                        && ServiceExecutionContract.intentHash(intent)
                                .equals(ref.sourceIntentHash())
                        && binding.sourceFenceNo().equals(ref.sourceFenceNo())
                        && binding.sourceFenceRevision() == ref.sourceFenceRevision()
                        && binding.planHash().equals(ref.planHash())
                        && intent.resourceCommandNo().equals(ref.plannedResourceCommandNo())
                        && intent.resourceCommandHash().equals(ref.plannedResourceCommandHash())
                        && command.actionNo().equals(ref.prepareActionNo())
                        && command.actionHash().equals(ref.prepareActionHash())
                        && command.requestKeyHash().equals(ref.prepareKeyHash()));
    }

    private static boolean locallyReady(StockOriginal original) {
        return original.fence().getSuspensionIntentRevision() == null
                && original.currentIntent().suspension() == null
                && "READY_UNCHANGED".equals(original.currentIntent().continuity().state());
    }

    private static void requireReady(StockOriginal original, Grant grant) {
        require(
                locallyReady(original)
                        && "PREPARED".equals(original.fence().getObservedGrantState())
                        && grant.grantNo().equals(original.fence().getObservedGrantNo())
                        && grant.grantRevision() == original.fence().getObservedGrantRevision());
    }

    private static String snapshot(StockOriginal original) {
        var f = original.fence();
        return ServiceExecutionContract.canonical(
                List.of(
                        f.getVersion(),
                        f.getObservationEpoch(),
                        f.getSourceFenceRevision(),
                        original.currentIntent().continuity()));
    }

    private BtyServiceExecutionStockIntent row(
            ExecutionBinding binding,
            RetentionLookup lookup,
            StockOriginal original,
            ExecutionOriginalEvidence evidence,
            String actor) {
        var ref = lookup.reference();
        var row = new BtyServiceExecutionStockIntent();
        row.setTenantNo(binding.scope().tenantNo());
        row.setEntityNo(binding.scope().entityNo());
        row.setStoreNo(binding.scope().storeNo());
        row.setWorkOrderNo(binding.workOrderNo());
        row.setTaskNo(binding.taskNo());
        row.setFenceNo(binding.sourceFenceNo());
        row.setSubmissionNo(binding.submissionNo());
        row.setExecutionNo(binding.executionNo());
        row.setAttemptNo(binding.attemptNo());
        row.setBindingHash(ref.bindingHash());
        row.setSourceIntentHash(ref.sourceIntentHash());
        row.setResourceCommandNo(ref.plannedResourceCommandNo());
        row.setResourceCommandHash(ref.plannedResourceCommandHash());
        row.setPlanHash(ref.planHash());
        row.setReferenceHash(ref.referenceHash());
        row.setReferenceJson(db.json.writeValueAsString(ref));
        row.setLookupJson(db.json.writeValueAsString(lookup));
        row.setLookupHash(ExecutionEvidenceContract.hash("BEAUTY_STOCK_LOOKUP_BYTES_V1", lookup));
        row.setRetentionNo(lookup.retentionNo());
        row.setRequestHash(lookup.requestHash());
        row.setEvidenceJson(db.json.writeValueAsString(evidence));
        row.setEvidenceHash(evidence.proofHash());
        row.setEvidenceObservedAt(local(evidence.observedAt()));
        row.setObservationState("STOCK_RETENTION_PENDING");
        row.setPendingReason("AUTHENTICATED_ORIGINAL_RECOVERY");
        row.setSourceFenceRevision(original.fence().getSourceFenceRevision());
        row.setObservationEpoch(0L);
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(local(clock.instant()));
        row.setUpdatedAt(row.getCreatedAt());
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        return row;
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void require(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }
}
