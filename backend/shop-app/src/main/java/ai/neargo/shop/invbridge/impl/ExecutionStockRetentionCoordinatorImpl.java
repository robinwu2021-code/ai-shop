package ai.neargo.shop.invbridge.impl;

import ai.neargo.shop.invbridge.ExecutionStockRetentionCoordinator;
import ai.neargo.shop.invbridge.ExecutionStockMirrorEvidenceService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.invbridge.entity.PrdExecutionRetentionRoute;
import ai.neargo.shop.invbridge.mapper.ExecutionRetentionRouteMapper;
import ai.neargo.shop.invbridge.policy.ExecutionRetentionRouteSchemaVerifier;
import ai.neargo.shop.spi.product.*;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.*;
import ai.neargo.shop.spi.product.ExecutionStockRetentionIntentPort.*;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.*;
import ai.neargo.shop.spi.product.ExecutionStockMirrorEvidencePort.MirrorOriginalProof;
import ai.neargo.shop.spi.trade.*;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort.CurrentActor;
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

/** Durable conservative intent, independent owner calls and exact observational query. No start or release owner. */
@Service
public final class ExecutionStockRetentionCoordinatorImpl implements ExecutionStockRetentionCoordinator {
    private final ExecutionRetentionRouteMapper routes;
    private final ExecutionRetentionRouteSchemaVerifier schema;
    private final ObjectProvider<ServiceExecutionEvidencePort> evidence;
    private final ObjectProvider<PlatformExecutionStockRetentionComponent> platform;
    private final ObjectProvider<InventoryExecutionStockRetentionPort> inventory;
    private final ExecutionStockMirrorEvidenceService mirrors;
    private final ObjectMapper json;
    private final TransactionTemplate writes;

    public ExecutionStockRetentionCoordinatorImpl(
            ExecutionRetentionRouteMapper routes,
            ExecutionRetentionRouteSchemaVerifier schema,
            ObjectProvider<ServiceExecutionEvidencePort> evidence,
            ObjectProvider<PlatformExecutionStockRetentionComponent> platform,
            ObjectProvider<InventoryExecutionStockRetentionPort> inventory,
            ExecutionStockMirrorEvidenceService mirrors,
            ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.routes = routes;
        this.schema = schema;
        this.evidence = evidence;
        this.platform = platform;
        this.inventory = inventory;
        this.mirrors = mirrors;
        this.json = json;
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public AggregateReceiptV2 retainV2(RetainExecutionStock request) {
        outside();
        var original = request.lookup();
        if (!installed())
            return absent(original, Outcome.UNAVAILABLE, "RETENTION_ROUTE_SCHEMA_UNAVAILABLE");
        var provider = evidence.getIfAvailable();
        if (provider == null)
            return absent(original, Outcome.UNAVAILABLE, "EXECUTION_EVIDENCE_UNAVAILABLE");
        var core = provider.queryOriginalEvidence(new ExecutionEvidenceLookup(original.reference()));
        if (!ExecutionStockRetentionAggregateContract.compatible(original, core)) {
            return absent(original, Outcome.PENDING, "EXECUTION_ORIGINAL_UNPROVEN");
        }
        var row = find(original);
        RouteOriginalV2 route;
        try {
            if (row == null) {
                MirrorOriginalProof mirror = "DUAL".equals(original.stockOriginal().authority())
                        ? mirrors.freeze(original, core) : null;
                var roster = roster(original, core.facts().confirmationNo(), mirror);
                // Last external call before HUMAN acceptance. Nothing saved is treated as current permission.
                var actor = provider.authorizeCurrent(new ExecutionEvidenceLookup(original.reference()));
                actor.validate(original.reference());
                var identity = DelegatedIdentityContext.current();
                require(
                        actor.actorNo().equals(identity.actorNo())
                                && actor.scope().tenantNo().equals(identity.tenantNo()),
                        "CURRENT_SELF_CONFLICT");
                route = route(original, core, actor, roster);
                row = insert(route, core);
                route = read(row);
            } else {
                route = read(row);
                require(route.original().equals(original), "ROUTE_ORIGINAL_CONFLICT");
                validateMirrors(route);
                var actor = provider.authorizeCurrent(new ExecutionEvidenceLookup(original.reference()));
                actor.validate(original.reference());
                var identity = DelegatedIdentityContext.current();
                require(
                        actor.actorNo().equals(identity.actorNo())
                                && actor.scope().tenantNo().equals(identity.tenantNo()),
                        "CURRENT_SELF_CONFLICT");
            }
        } catch (IllegalArgumentException failure) {
            return absent(original, historicalFailure(failure), failure.getMessage());
        }
        if (!retainable(row)) return observe(row, route, Outcome.CONFLICT, "ROUTE_NOT_RETAINABLE");
        var observations = call(route, Operation.HUMAN, null);
        return persist(row, route, observations);
    }

    @Override
    public AggregateReceiptV2 queryOriginalV2(RetentionLookup original) {
        outside();
        if (!installed())
            return absent(original, Outcome.UNAVAILABLE, "RETENTION_ROUTE_SCHEMA_UNAVAILABLE");
        var row = find(original);
        if (row == null) return absent(original, Outcome.UNKNOWN, "RETENTION_ROUTE_UNKNOWN");
        try {
            var route = read(row);
            require(route.original().equals(original), "ROUTE_ORIGINAL_CONFLICT");
            return observe(row, route, null, null);
        } catch (IllegalArgumentException failure) {
            return absent(original, Outcome.CONFLICT, failure.getMessage());
        }
    }

    @Override
    public ExecutionStockRetentionPreparationObservation queryPreparationObservation(
            RetentionLookup original) {
        outside();
        if (!installed())
            return preparation(
                    original,
                    absent(original, Outcome.UNAVAILABLE, "RETENTION_ROUTE_SCHEMA_UNAVAILABLE"),
                    null);
        var row = find(original);
        if (row == null)
            return preparation(
                    original, absent(original, Outcome.UNKNOWN, "RETENTION_ROUTE_UNKNOWN"), null);
        // read validates the full real route/source/proof bytes before exporting only its
        // non-authorizing roster.
        var route = read(row);
        require(route.original().equals(original), "ROUTE_ORIGINAL_CONFLICT");
        return preparation(original, observe(row, route, null, null), route.components());
    }

    private ExecutionStockRetentionPreparationObservation preparation(
            RetentionLookup original, AggregateReceiptV2 receipt, List<ComponentRouteV2> roster) {
        var result =
                new ExecutionStockRetentionPreparationObservation(
                        ExecutionStockRetentionPreparationObservation.SCHEMA, receipt, roster);
        result.validate(original);
        return result;
    }

    @Override
    public AggregateReceiptV2 continueAcceptedRetentionV2(AcceptedRouteLookup request) {
        outside();
        var original = request.original();
        if (!installed())
            return absent(original, Outcome.UNAVAILABLE, "RETENTION_ROUTE_SCHEMA_UNAVAILABLE");
        var row = find(original);
        if (row == null) return absent(original, Outcome.UNKNOWN, "RETENTION_ROUTE_UNKNOWN");
        var route = read(row);
        if (!route.original().equals(original) || !route.routeNo().equals(request.routeNo())
                || !route.routeHash().equals(request.routeHash())
                || !route.acceptedIntentNo().equals(request.acceptedIntentNo())
                || !route.acceptedIntentHash().equals(request.acceptedIntentHash())) {
            return observe(row, route, Outcome.CONFLICT, "ACCEPTED_ROUTE_CONFLICT");
        }
        if (!retainable(row)) return observe(row, route, Outcome.CONFLICT, "ROUTE_NOT_RETAINABLE");
        // Providers independently resolve this persisted route and obtain fresh source originals before owner locks.
        return persist(row, route, call(route, Operation.CONTINUE, null));
    }

    @Override
    public AggregateReceiptV2 releaseAfterNoEffectV2(ReleaseAfterNoEffect request) {
        outside();
        var result = queryOriginalV2(request.lookup());
        if (result.routeHash() == null || result.outcome() == Outcome.CONFLICT) return result;
        return receipt(request.lookup(), result.routeNo(), result.routeHash(), result.ownerRosterHash(),
                result.components(), Outcome.PENDING, "RESOURCE_SEAL_PROVIDER_UNAVAILABLE");
    }

    @Override
    public AggregateReceiptV2 acknowledgeIssuedV2(AggregateIssuedV2 request) {
        outside();
        var original = request.original();
        if (!installed())
            return absent(original, Outcome.UNAVAILABLE, "RETENTION_ROUTE_SCHEMA_UNAVAILABLE");
        var row = find(original);
        if (row == null) return absent(original, Outcome.UNKNOWN, "RETENTION_ROUTE_UNKNOWN");
        var route = read(row);
        if (!route.original().equals(original) || !route.routeNo().equals(request.routeNo())
                || !route.routeHash().equals(request.routeHash())) {
            return observe(row, route, Outcome.CONFLICT, "ISSUED_ROUTE_CONFLICT");
        }
        var current = call(route, Operation.QUERY, null);
        if (current.stream().anyMatch(o -> o.status() != Outcome.RETAINED)) {
            return combine(row, route, current, null, null);
        }
        var required = route.components().stream().filter(c -> c.applicability() == Applicability.REQUIRED).toList();
        if (required.size() != request.expected().size()) {
            return combine(row, route, current, Outcome.CONFLICT, "ISSUE_EXPECTATION_COVERAGE");
        }
        for (int i = 0; i < required.size(); i++) {
            var component = required.get(i);
            var expected = request.expected().get(i);
            if (expected.componentKind() != component.componentKind()
                    || !expected.componentNo().equals(component.componentNo())
                    || !expected.componentHash().equals(component.componentHash())) {
                return combine(row, route, current, Outcome.CONFLICT, "ISSUE_EXPECTATION_CONFLICT");
            }
            var observation = current.stream().filter(o -> o.componentKind() == component.componentKind())
                    .findFirst().orElseThrow();
            String ownerHash;
            if (component.componentKind() == ComponentKind.PLATFORM) {
                var proof = observation.platform().proof();
                if (proof.rows().stream().anyMatch(r -> !"CONSUMED".equals(r.disposition()))) {
                    return combine(row, route, current, Outcome.PENDING, "ORIGINAL_NOT_CONSUMED");
                }
                ownerHash = ExecutionStockRetentionContract.issueObservationHash(original, proof.rows());
            } else {
                var proof = observation.inventory().proof();
                if (!"CONSUMED".equals(proof.disposition())) {
                    return combine(row, route, current, Outcome.PENDING, "ORIGINAL_NOT_CONSUMED");
                }
                ownerHash = InventoryExecutionStockRetentionContract.issueObservationHash(component.inventory(),
                        proof.heads(), proof.lines(), proof.outbounds());
            }
            if (!ownerHash.equals(expected.expectedIssueObservationHash())) {
                return combine(row, route, current, Outcome.CONFLICT, "ISSUE_OBSERVATION_CONFLICT");
            }
        }
        return persist(row, route, call(route, Operation.ACK, request));
    }

    /** Explicit bounded continuation only; no scheduler, task starter or reference bootstrap invokes it here. */
    @Override
    public ReconcileResult reconcileAcceptedRoutes(ReconcileCursor cursor, int limit) {
        outside();
        require(limit > 0 && limit <= 100, "RECONCILE_LIMIT");
        if (!installed()) return new ReconcileResult(List.of(), cursor);
        var after = cursor == null ? LocalDateTime.of(1970, 1, 1, 0, 0)
                : LocalDateTime.ofInstant(cursor.nextRetryAt(), ZoneOffset.UTC);
        var due =
                unscoped(
                        () ->
                                routes.dueGroup(
                                        now(),
                                        after,
                                        cursor == null ? 0 : cursor.id(),
                                        cursor != null && cursor.exhaustedGroup(),
                                        limit));
        var results = new ArrayList<AggregateReceiptV2>();
        var failures = new ArrayList<ReconcileFailure>();
        ReconcileCursor next = cursor;
        for (var row : due) {
            next =
                    new ReconcileCursor(
                            row.getNextRetryAt().toInstant(ZoneOffset.UTC),
                            row.getId(),
                            exhausted(row));
            if (exhausted(row)) {
                failures.add(new ReconcileFailure(row.getId(), "RETRY_REVISION_EXHAUSTED", false));
                continue;
            }
            try {
                var route = read(row);
                results.add(
                        continueAcceptedRetentionV2(
                                new AcceptedRouteLookup(
                                        route.routeNo(),
                                        route.routeHash(),
                                        route.acceptedIntentNo(),
                                        route.acceptedIntentHash(),
                                        route.original())));
            } catch (RuntimeException unavailable) {
                boolean deferred = false;
                try {
                    var timestamp = now();
                    // V606 requires epoch+revision CAS on every update; preserve original/receipt
                    // bytes and state.
                    deferred =
                            tx(() -> routes.deferFailure(row, timestamp.plusMinutes(5), timestamp))
                                    == 1;
                } catch (RuntimeException retryUnavailable) {
                    // Explicit failure below remains visible; a failed metadata write is never a
                    // verified receipt.
                }
                failures.add(
                        new ReconcileFailure(
                                row.getId(), "ACCEPTED_ROUTE_OBSERVATION_UNAVAILABLE", deferred));
            }
        }
        return new ReconcileResult(results, next, failures);
    }

    private static boolean exhausted(PrdExecutionRetentionRoute row) {
        return row.getRevision() >= Long.MAX_VALUE - 1
                || row.getObservationEpoch() >= Long.MAX_VALUE - 1
                || row.getVersion() >= Long.MAX_VALUE - 1;
    }

    @Override
    public AcceptedComponentAuthority queryAcceptedComponent(AcceptedComponentLookup request) {
        outside();
        if (!installed())
            return authority(
                    request, Outcome.UNAVAILABLE, "RETENTION_ROUTE_SCHEMA_UNAVAILABLE", null);
        var row = unscoped(() -> routes.findIntent(request.original().reference().scope().tenantNo(),
                request.acceptedIntentNo()));
        if (row == null)
            return authority(request, Outcome.UNKNOWN, "ACCEPTED_INTENT_UNKNOWN", null);
        var route = read(row);
        if (!route.original().equals(request.original())
                || !route.acceptedIntentHash().equals(request.acceptedIntentHash())
                || route.components().stream().noneMatch(c -> c.applicability() == Applicability.REQUIRED
                        && c.componentKind() == request.ownerKind() && c.componentNo().equals(request.componentNo())
                        && c.componentHash().equals(request.componentHash())) || !retainable(row)) {
            return authority(request, Outcome.CONFLICT, "ACCEPTED_COMPONENT_CONFLICT", null);
        }
        var result =
                new AcceptedComponentAuthority(
                                "EXECUTION_STOCK_ACCEPTED_COMPONENT_AUTHORITY_V2",
                                PURPOSE,
                                request,
                                Outcome.RETAINED,
                                null,
                                route,
                                row.getRevision(),
                                row.getObservationEpoch(),
                                null)
                        .signed();
        result.validate(request);
        return result;
    }

    private AcceptedComponentAuthority authority(AcceptedComponentLookup target, Outcome outcome, String reason,
            RouteOriginalV2 route) {
        return new AcceptedComponentAuthority(
                        "EXECUTION_STOCK_ACCEPTED_COMPONENT_AUTHORITY_V2",
                        PURPOSE,
                        target,
                        outcome,
                        reason,
                        route,
                        0,
                        0,
                        null)
                .signed();
    }

    private enum Operation { HUMAN, CONTINUE, QUERY, ACK }

    private List<ComponentObservationV2> call(RouteOriginalV2 route, Operation operation, AggregateIssuedV2 issued) {
        var result = new ArrayList<ComponentObservationV2>();
        for (var component : route.components()) {
            if (component.applicability() == Applicability.NOT_APPLICABLE) {
                try {
                    mirrors.validateFrozen(component.mirrorOriginal());
                    var proof = new HistoricalNotApplicableProof(component.mirrorOriginal(),
                            ExecutionStockRetentionAggregateContract.notApplicableHash(component.mirrorOriginal()));
                    proof.validate();
                    result.add(new ComponentObservationV2(component.componentKind(), component.componentNo(),
                            component.componentHash(), Outcome.RETAINED, null, null, proof));
                } catch (RuntimeException failure) {
                    result.add(new ComponentObservationV2(component.componentKind(), component.componentNo(),
                            component.componentHash(), historicalFailure(failure), null, null, null));
                }
                continue;
            }
            try {
                result.add(owner(route, component, operation, issued));
            } catch (RuntimeException ambiguous) {
                // A timeout after owner commit is not rollback evidence. Requery its real exact original.
                try {
                    result.add(owner(route, component, Operation.QUERY, null));
                } catch (RuntimeException unavailable) {
                    result.add(new ComponentObservationV2(component.componentKind(), component.componentNo(),
                            component.componentHash(), historicalFailure(unavailable), null, null, null));
                }
            }
        }
        return List.copyOf(result);
    }

    private ComponentObservationV2 owner(RouteOriginalV2 route, ComponentRouteV2 component, Operation operation,
            AggregateIssuedV2 issued) {
        if (component.componentKind() == ComponentKind.PLATFORM) {
            var provider = platform.getIfAvailable();
            if (provider == null) return pending(component);
            var lookup = new PlatformExecutionStockRetentionComponent.ComponentLookup(component.platform());
            var value = switch (operation) {
                case HUMAN -> provider.retainComponent(new PlatformExecutionStockRetentionComponent.ComponentRetain(
                        component.platform()));
                case CONTINUE -> provider.continueAcceptedRetention(
                        new PlatformExecutionStockRetentionComponent.AcceptedRetention(lookup, route.acceptedIntentNo(),
                                route.acceptedIntentHash()));
                case QUERY -> provider.queryOriginalComponent(lookup);
                case ACK -> provider.acknowledgeIssuedComponent(
                        new PlatformExecutionStockRetentionComponent.ComponentIssued(lookup, expected(issued,
                                component)));
            };
            require(
                    value != null && value.component().equals(component.platform()),
                    "PLATFORM_COMPONENT_CONFLICT");
            value.validate();
            return new ComponentObservationV2(component.componentKind(), component.componentNo(),
                    component.componentHash(), value.outcome(), value, null, null);
        }
        var provider = inventory.getIfAvailable();
        if (provider == null) return pending(component);
        var lookup = new InventoryExecutionStockRetentionPort.ComponentLookup(component.inventory());
        var value = switch (operation) {
            case HUMAN -> provider.retainComponent(new InventoryExecutionStockRetentionPort.ComponentRetain(
                    component.inventory()));
            case CONTINUE -> provider.continueAcceptedRetention(
                    new InventoryExecutionStockRetentionPort.AcceptedRetention(lookup, route.acceptedIntentNo(),
                            route.acceptedIntentHash()));
            case QUERY -> provider.queryOriginalComponent(lookup);
            case ACK -> provider.acknowledgeIssued(new InventoryExecutionStockRetentionPort.ComponentIssued(lookup,
                    expected(issued, component)));
        };
        require(
                value != null
                        && component.componentNo().equals(value.componentNo())
                        && component.componentHash().equals(value.componentHash())
                        && route.original()
                                .reference()
                                .referenceHash()
                                .equals(value.referenceHash())
                        && route.original().requestHash().equals(value.requestHash())
                        && InventoryExecutionStockRetentionContract.receiptSchema(
                                        component.inventory())
                                .equals(value.schema())
                        && value.receiptHash()
                                .equals(
                                        ExecutionEvidenceContract.digest(
                                                value.schema(), value, "receiptHash")),
                "INVENTORY_COMPONENT_CONFLICT");
        if (value.outcome() == Outcome.RETAINED) {
            require(value.proof() != null && value.proof().component().equals(component.inventory()),
                    "INVENTORY_PROOF_CONFLICT");
            value.proof().validate();
        }
        var normalized = new InventoryExecutionStockRetentionPort.ComponentReceipt(value.schema(), value.componentNo(),
                value.componentHash(), value.referenceHash(), value.requestHash(), value.outcome(), value.reason(),
                value.proof(), value.observedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                        value.receiptHash());
        return new ComponentObservationV2(component.componentKind(), component.componentNo(), component.componentHash(),
                value.outcome(), null, normalized, null);
    }

    private static String expected(AggregateIssuedV2 request, ComponentRouteV2 component) {
        return request.expected().stream().filter(e -> e.componentKind() == component.componentKind()
                && e.componentNo().equals(component.componentNo())
                        && e.componentHash().equals(component.componentHash()))
                .findFirst().orElseThrow().expectedIssueObservationHash();
    }
    private static Outcome historicalFailure(RuntimeException failure) {
        return List.of(
                                "CONFLICTING_RESERVE_SCOPE",
                                "NEW_MIRROR_SCOPE_CONFLICT",
                                "IMMUTABLE_EVENT_CHANGED",
                                "MIRROR_SUBSET_CONFLICT")
                        .contains(Objects.toString(failure.getMessage(), ""))
                ? Outcome.CONFLICT
                : Outcome.PENDING;
    }
    private static ComponentObservationV2 pending(ComponentRouteV2 component) {
        return new ComponentObservationV2(component.componentKind(), component.componentNo(), component.componentHash(),
                Outcome.PENDING, null, null, null);
    }

    private AggregateReceiptV2 persist(PrdExecutionRetentionRoute row, RouteOriginalV2 route,
            List<ComponentObservationV2> observations) {
        var result = combine(row, route, observations, null, null);
        String state = result.outcome() == Outcome.RETAINED ? "RETAINED"
                : result.outcome() == Outcome.CONFLICT ? "CONFLICT" : "RETAIN_PENDING";
        var timestamp = now();
        int changed = tx(() -> routes.observe(row, state, json.writeValueAsString(result), result.receiptHash(),
                timestamp.plusSeconds(30), timestamp));
        if (changed != 1) return queryOriginalV2(route.original());
        return result;
    }

    private AggregateReceiptV2 observe(PrdExecutionRetentionRoute row, RouteOriginalV2 route, Outcome forced,
            String reason) {
        return combine(row, route, call(route, Operation.QUERY, null), forced, reason);
    }

    private AggregateReceiptV2 combine(PrdExecutionRetentionRoute row, RouteOriginalV2 route,
            List<ComponentObservationV2> observations, Outcome forced, String reason) {
        Outcome outcome = forced;
        if (outcome == null) outcome = "CONFLICT".equals(row.getState())
                || observations.stream().anyMatch(o -> o.status() == Outcome.CONFLICT) ? Outcome.CONFLICT
                : observations.stream().allMatch(o -> o.status() == Outcome.RETAINED)
                        ? Outcome.RETAINED : Outcome.PENDING;
        if (List.of("RELEASE_PENDING", "RELEASED_NO_EFFECT").contains(row.getState())) {
            outcome = Outcome.PENDING;
            reason = "RESOURCE_SEAL_PROVIDER_UNAVAILABLE";
        }
        if (reason == null && outcome != Outcome.RETAINED) reason = "COMPONENT_ORIGINAL_NOT_PROVEN";
        return receipt(route.original(), route.routeNo(), route.routeHash(),
                ExecutionStockRetentionAggregateContract.rosterHash(route.components()), observations, outcome, reason);
    }

    private AggregateReceiptV2 absent(RetentionLookup original, Outcome outcome, String reason) {
        return receipt(original, ExecutionStockRetentionAggregateContract.routeNo(original), null, null, List.of(),
                outcome, reason);
    }
    private AggregateReceiptV2 receipt(RetentionLookup original, String routeNo, String routeHash, String roster,
            List<ComponentObservationV2> observations, Outcome outcome, String reason) {
        var value = new AggregateReceiptV2("EXECUTION_STOCK_AGGREGATE_RECEIPT_V2",
                original.reference().referenceHash(), original.retentionNo(), original.requestHash(), routeNo,
                        routeHash,
                original.stockOriginal().authority(), roster, observations, outcome, reason, Instant.now(), null);
        return new AggregateReceiptV2(value.schema(), value.referenceHash(), value.retentionNo(), value.requestHash(),
                routeNo, routeHash, value.frozenAuthority(), roster, observations, outcome, reason, value.observedAt(),
                ExecutionEvidenceContract.digest(value.schema(), value, "receiptHash"));
    }

    private List<ComponentRouteV2> roster(RetentionLookup original, String confirmation, MirrorOriginalProof mirror) {
        var result = new ArrayList<ComponentRouteV2>();
        if (!"INVENTORY".equals(original.stockOriginal().authority())) {
            var descriptor = PlatformExecutionStockRetentionComponent.descriptor(original, confirmation);
            result.add(new ComponentRouteV2(ComponentKind.PLATFORM, descriptor.componentNo(),
                    descriptor.componentHash(),
                    Applicability.REQUIRED, descriptor, null, null));
        }
        if (!"PLATFORM".equals(original.stockOriginal().authority())) {
            if (mirror != null && mirror.expectedNeeds().isEmpty()) {
                result.add(new ComponentRouteV2(ComponentKind.INVENTORY, mirror.subsetHash(),
                        ExecutionStockRetentionAggregateContract.notApplicableHash(mirror),
                                Applicability.NOT_APPLICABLE,
                        null, null, mirror));
            } else {
                var descriptor = mirror == null ? InventoryExecutionStockRetentionContract.nativeComponent(original,
                        confirmation) : InventoryExecutionStockRetentionContract.dualComponent(original,
                                confirmation, mirror);
                result.add(new ComponentRouteV2(ComponentKind.INVENTORY, descriptor.componentNo(),
                        descriptor.componentHash(),
                        Applicability.REQUIRED, null, descriptor, mirror));
            }
        }
        return List.copyOf(result);
    }

    private RouteOriginalV2 route(RetentionLookup original, ExecutionOriginalEvidence core, CurrentActor actor,
            List<ComponentRouteV2> roster) {
        String number = ExecutionStockRetentionAggregateContract.routeNo(original);
        String intent = ExecutionStockRetentionAggregateContract.intentNo(number);
        String intentHash = ExecutionStockRetentionAggregateContract.intentHash(original, core.facts().confirmationNo(),
                core.proofHash(), actor, roster);
        var value =
                new RouteOriginalV2(
                        "EXECUTION_STOCK_ROUTE_V2",
                        number,
                        original,
                        core.facts().confirmationNo(),
                        core.proofHash(),
                        intent,
                        intentHash,
                        original.stockOriginal().authority(),
                        actor,
                        roster,
                        null);
        value = new RouteOriginalV2(value.schema(), number, original, value.sourceConfirmationNo(),
                value.sourceEvidenceHash(), intent, intentHash, value.frozenAuthority(), actor, roster,
                ExecutionEvidenceContract.digest(value.schema(), value, "routeHash"));
        value.validate();
        return value;
    }

    private void validateMirrors(RouteOriginalV2 route) {
        for (var component : route.components()) {
            if (component.mirrorOriginal() != null) mirrors.validateFrozen(component.mirrorOriginal());
        }
    }

    private PrdExecutionRetentionRoute insert(RouteOriginalV2 route, ExecutionOriginalEvidence core) {
        var row = row(route, core);
        try {
            tx(() -> {
                require(routes.insert(row) == 1, "ROUTE_INSERT_CONFLICT");
                return row;
            });
        } catch (DuplicateKeyException collision) {
            // Exact reread only after the losing transaction has rolled back; never overwrite first acceptance pins.
            var winner = find(route.original());
            require(
                    winner != null && read(winner).original().equals(route.original()),
                    "PERMANENT_ROUTE_ASSOCIATION");
            return winner;
        }
        return row;
    }

    private PrdExecutionRetentionRoute find(RetentionLookup original) {
        return unscoped(() -> routes.find(original.reference().scope().tenantNo(),
                ExecutionStockRetentionAggregateContract.routeNo(original)));
    }

    private RouteOriginalV2 read(PrdExecutionRetentionRoute row) {
        var route = json.readValue(row.getRouteJson(), RouteOriginalV2.class);
        route.validate();
        var core = json.readValue(row.getSourceEvidenceJson(), ExecutionOriginalEvidence.class);
        core.validate();
        require(
                ExecutionStockRetentionAggregateContract.compatible(route.original(), core)
                        && core.proofHash().equals(route.sourceEvidenceHash()),
                "ROUTE_SOURCE_EVIDENCE_CONFLICT");
        var expected = row(route, core);
        // Compare every immutable persisted identity/evidence column, preserving exact JSON bytes on read.
        require(immutableBytes(row).equals(immutableBytes(expected)), "ROUTE_STORAGE_CONFLICT");
        require(row.getRevision() != null && row.getRevision() >= 0 && row.getObservationEpoch() != null
                && row.getObservationEpoch() >= 0 && row.getDeleted() == 0 && row.getVersion() >= 0,
                "ROUTE_REVISION_CONFLICT");
        if (row.getObservationsJson() != null || row.getObservationsHash() != null) {
            var observation = json.readValue(row.getObservationsJson(), AggregateReceiptV2.class);
            require(observation.routeHash().equals(route.routeHash())
                    && observation.requestHash().equals(route.original().requestHash())
                    && observation.receiptHash().equals(row.getObservationsHash())
                    && observation.receiptHash().equals(ExecutionEvidenceContract.digest(observation.schema(),
                            observation,
                            "receiptHash")), "ROUTE_OBSERVATION_CONFLICT");
        }
        return route;
    }

    private static boolean retainable(PrdExecutionRetentionRoute row) {
        return List.of("RETAIN_PENDING", "RETAINED").contains(row.getState());
    }
    private boolean installed() {
        return schema.installation() == ExecutionRetentionRouteSchemaVerifier.Installation.INSTALLED;
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private <T> T tx(Supplier<T> operation) { return unscoped(() -> writes.execute(status -> operation.get())); }
    private <T> T unscoped(Supplier<T> operation) { return DataScopeContext.executeWithoutScope(operation); }
    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "retention orchestration IO must begin outside a transaction");
        }
    }
    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }

    private PrdExecutionRetentionRoute row(RouteOriginalV2 route, ExecutionOriginalEvidence core) {
        var row = new PrdExecutionRetentionRoute();
        var original = route.original();
        var ref = original.reference();
        var mirror = route.components().stream().map(ComponentRouteV2::mirrorOriginal).filter(Objects::nonNull)
                .findFirst().orElse(null);
        row.setTenantNo(ref.scope().tenantNo());
        row.setEntityNo(ref.scope().entityNo());
        row.setStoreNo(ref.scope().storeNo());
        row.setExecutionNo(ref.executionNo());
        row.setUnitNo(ref.unitNo());
        row.setGrantNo(ref.grantNo());
        row.setAttemptNo(ref.attemptNo());
        row.setPreparedGrantRevision(ref.preparedGrantRevision());
        row.setRouteNo(route.routeNo());
        row.setRouteHash(route.routeHash());
        row.setRetentionNo(original.retentionNo());
        row.setRequestHash(original.requestHash());
        row.setReferenceHash(ref.referenceHash());
        row.setParentOriginalHash(ExecutionStockRetentionContract.originalHash(original.stockOriginal()));
        row.setParentManifestHash(original.stockOriginal().manifestHash());
        row.setStockConfirmationOperationNo(original.confirmationOperationNo());
        row.setSourceEvidenceHash(route.sourceEvidenceHash());
        row.setComponentRosterHash(ExecutionStockRetentionAggregateContract.rosterHash(route.components()));
        row.setAcceptedIntentNo(route.acceptedIntentNo());
        row.setAcceptedIntentHash(route.acceptedIntentHash());
        row.setOriginalHumanAuthorityHash(route.originalHumanAuthority().authorityHash());
        row.setResourceCommandHash(ref.plannedResourceCommandHash());
        row.setPlanHash(ref.planHash());
        row.setRouteSchema(route.schema());
        row.setReferenceSchema(ref.schema());
        row.setFrozenAuthority(route.frozenAuthority());
        row.setSourceConfirmationNo(route.sourceConfirmationNo());
        row.setResourceCommandNo(ref.plannedResourceCommandNo());
        row.setComponentRosterCount(route.components().size());
        row.setRouteJson(json.writeValueAsString(route));
        row.setReferenceJson(json.writeValueAsString(ref));
        row.setParentOriginalJson(json.writeValueAsString(original.stockOriginal()));
        row.setSourceEvidenceJson(json.writeValueAsString(core));
        row.setComponentRosterJson(json.writeValueAsString(route.components()));
        row.setAcceptedIntentJson(
                json.writeValueAsString(List.of(original, route.sourceConfirmationNo(),
                        route.sourceEvidenceHash(), route.originalHumanAuthority(), route.components())));
        row.setOriginalHumanAuthorityJson(json.writeValueAsString(route.originalHumanAuthority()));
        row.setMirrorOriginalJson(mirror == null ? null : json.writeValueAsString(mirror));
        row.setMirrorOriginalHash(mirror == null ? null : mirror.proofHash());
        row.setState("RETAIN_PENDING");
        row.setObservationEpoch(0L);
        row.setRevision(0L);
        row.setNextRetryAt(now());
        row.setCreatedAt(now());
        row.setCreatedBy(route.originalHumanAuthority().actorNo());
        row.setUpdatedAt(row.getCreatedAt());
        row.setUpdatedBy(row.getCreatedBy());
        row.setVersion(0L);
        row.setDeleted(0);
        return row;
    }

    private String immutableBytes(PrdExecutionRetentionRoute row) {
        var values = new java.util.TreeMap<String, Object>();
        values.put("tenant_no", row.getTenantNo());
        values.put("entity_no", row.getEntityNo());
        values.put("store_no", row.getStoreNo());
        values.put("execution_no", row.getExecutionNo());
        values.put("unit_no", row.getUnitNo());
        values.put("grant_no", row.getGrantNo());
        values.put("attempt_no", row.getAttemptNo());
        values.put("prepared_grant_revision", row.getPreparedGrantRevision());
        values.put("route_no", row.getRouteNo());
        values.put("route_hash", row.getRouteHash());
        values.put("retention_no", row.getRetentionNo());
        values.put("request_hash", row.getRequestHash());
        values.put("reference_hash", row.getReferenceHash());
        values.put("parent_original_hash", row.getParentOriginalHash());
        values.put("parent_manifest_hash", row.getParentManifestHash());
        values.put("stock_confirmation_operation_no", row.getStockConfirmationOperationNo());
        values.put("source_evidence_hash", row.getSourceEvidenceHash());
        values.put("component_roster_hash", row.getComponentRosterHash());
        values.put("accepted_intent_no", row.getAcceptedIntentNo());
        values.put("accepted_intent_hash", row.getAcceptedIntentHash());
        values.put("original_human_authority_hash", row.getOriginalHumanAuthorityHash());
        values.put("resource_command_hash", row.getResourceCommandHash());
        values.put("plan_hash", row.getPlanHash());
        values.put("route_schema", row.getRouteSchema());
        values.put("reference_schema", row.getReferenceSchema());
        values.put("frozen_authority", row.getFrozenAuthority());
        values.put("source_confirmation_no", row.getSourceConfirmationNo());
        values.put("resource_command_no", row.getResourceCommandNo());
        values.put("component_roster_count", row.getComponentRosterCount());
        values.put("route_json", row.getRouteJson());
        values.put("reference_json", row.getReferenceJson());
        values.put("parent_original_json", row.getParentOriginalJson());
        values.put("source_evidence_json", row.getSourceEvidenceJson());
        values.put("component_roster_json", row.getComponentRosterJson());
        values.put("accepted_intent_json", row.getAcceptedIntentJson());
        values.put("original_human_authority_json", row.getOriginalHumanAuthorityJson());
        values.put("mirror_original_json", row.getMirrorOriginalJson());
        values.put("mirror_original_hash", row.getMirrorOriginalHash());
        values.put("created_by", row.getCreatedBy());
        values.put("deleted", row.getDeleted());
        require(row.getCreatedAt() != null, "ROUTE_CREATED_TIME_MISSING");
        return json.writeValueAsString(values);
    }
}
