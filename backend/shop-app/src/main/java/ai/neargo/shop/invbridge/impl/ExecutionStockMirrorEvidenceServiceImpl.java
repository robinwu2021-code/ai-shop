package ai.neargo.shop.invbridge.impl;

import ai.neargo.shop.invbridge.ExecutionStockMirrorEvidenceService;
import ai.neargo.shop.invbridge.InvMirrorEvent;
import ai.neargo.shop.invbridge.mapper.ExecutionRetentionMirrorMapper;
import ai.neargo.shop.invbridge.entity.ExecutionRetentionMirrorEvent;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.runtime.OutboxFingerprint;
import ai.neargo.shop.invbridge.mapper.ExecutionRetentionRouteMapper;
import ai.neargo.shop.invbridge.policy.ExecutionRetentionRouteSchemaVerifier;
import ai.neargo.shop.spi.product.*;
import ai.neargo.shop.spi.product.ExecutionStockMirrorEvidencePort.*;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.*;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.StockDispositionPort.Need;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Historical scope producer; current managed flags, ACLs and delivery markers never select the roster. */
@Service
public final class ExecutionStockMirrorEvidenceServiceImpl implements ExecutionStockMirrorEvidenceService {
    private final ExecutionRetentionMirrorMapper events;
    private final ObjectMapper json;
    private final ObjectProvider<StockDispositionPort> stock;
    private final ExecutionRetentionRouteMapper routes;
    private final ExecutionRetentionRouteSchemaVerifier schema;

    public ExecutionStockMirrorEvidenceServiceImpl(ExecutionRetentionMirrorMapper events, ObjectMapper json,
            ObjectProvider<StockDispositionPort> stock, ExecutionRetentionRouteMapper routes,
            ExecutionRetentionRouteSchemaVerifier schema) {
        this.events = events;
        this.json = json;
        this.stock = stock;
        this.routes = routes;
        this.schema = schema;
    }

    @Override
    public MirrorOriginalProof freeze(RetentionLookup lookup, ExecutionOriginalEvidence core) {
        outside();
        require(ExecutionStockRetentionAggregateContract.compatible(lookup, core), "CORE_ORIGINAL_UNPROVEN");
        var provider = stock.getIfAvailable();
        require(provider != null, "PLATFORM_ORIGINAL_UNAVAILABLE");
        var observation = provider.observeOriginal(new StockDispositionPort.Lookup(lookup.stockOriginal(),
                lookup.confirmationOperationNo()));
        require(observation != null && observation.original().equals(lookup.stockOriginal())
                && observation.operationNo().equals(lookup.confirmationOperationNo())
                && observation.outcome() == StockDispositionPort.Outcome.APPLIED && !observation.lines().isEmpty()
                && (observation.state() == StockDispositionPort.CurrentState.ALLOCATED
                        || observation.state() == StockDispositionPort.CurrentState.CONSUMED),
                "PLATFORM_ORIGINAL_UNPROVEN");
        var events = candidates(lookup);
        var needs = validateEvents(lookup, events, observation.state() == StockDispositionPort.CurrentState.CONSUMED);
        var required = lifecycle(lookup, needs, observation.state() == StockDispositionPort.CurrentState.CONSUMED);
        var result = new MirrorOriginalProof("EXECUTION_STOCK_MIRROR_ORIGINAL_V2", lookup,
                core.facts().confirmationNo(), lookup.reference().referenceHash(), lookup.requestHash(),
                ExecutionStockRetentionContract.originalHash(lookup.stockOriginal()), lookup.stockOriginal().manifestHash(),
                needs, lookup.stockOriginal().lifecycleVersion(), required, events, observation.observationHash(),
                ExecutionStockMirrorEvidencePort.subsetHash(lookup, needs), null).signed();
        result.validate();
        return result;
    }

    @Override
    public MirrorOriginalProof queryOriginal(InventoryExecutionStockRetentionPort.ComponentLookup request) {
        outside();
        if (schema.installation() != ExecutionRetentionRouteSchemaVerifier.Installation.INSTALLED) return null;
        var component = request.component();
        var lookup = component.original();
        var row = DataScopeContext.executeWithoutScope(() -> routes.find(lookup.reference().scope().tenantNo(),
                ExecutionStockRetentionAggregateContract.routeNo(lookup)));
        if (row == null) return null;
        var route = json.readValue(row.getRouteJson(), RouteOriginalV2.class);
        route.validate();
        require(route.routeHash().equals(row.getRouteHash()) && route.original().equals(lookup), "ROUTE_CONFLICT");
        var saved = route.components().stream().filter(c -> c.componentKind() == ComponentKind.INVENTORY
                && c.applicability() == Applicability.REQUIRED && c.componentNo().equals(component.componentNo())
                && c.componentHash().equals(component.componentHash())).findFirst().orElse(null);
        if (saved == null || saved.mirrorOriginal() == null) return null;
        var proof = saved.mirrorOriginal();
        validateFrozen(proof);
        InventoryExecutionStockRetentionContract.validateMirrorBinding(component, proof);
        return proof;
    }

    /** Complete frozen event bytes remain verifiable even if a later approved source cleanup removes old rows. */
    @Override
    public void validateFrozen(MirrorOriginalProof proof) {
        outside();
        proof.validate();
        boolean consumed = proof.requiredLifecycleEvents().contains(InvMirrorEvent.ISSUE);
        require(validateEvents(proof.original(), proof.events(), consumed).equals(proof.expectedNeeds()),
                "FROZEN_MIRROR_CONFLICT");
        var provider = stock.getIfAvailable();
        require(provider != null, "PLATFORM_ORIGINAL_UNAVAILABLE");
        var actual = provider.observeOriginal(new StockDispositionPort.Lookup(proof.original().stockOriginal(),
                proof.original().confirmationOperationNo()));
        require(actual != null && actual.outcome() == StockDispositionPort.Outcome.APPLIED
                && actual.original().equals(proof.original().stockOriginal()) && !actual.lines().isEmpty()
                && (actual.state() == StockDispositionPort.CurrentState.ALLOCATED
                        || actual.state() == StockDispositionPort.CurrentState.CONSUMED), "PLATFORM_ORIGINAL_UNPROVEN");
        var current = candidates(proof.original());
        if (!current.isEmpty()) {
            // Validate every new equivalent candidate, but never replace accepted bytes/oldest physical audit roster.
            var all = new ArrayList<>(proof.events());
            var ids = new HashSet<Long>();
            proof.events().forEach(e -> ids.add(e.id()));
            for (var event : current) {
                var saved = proof.events().stream().filter(e -> e.id() == event.id()).findFirst();
                if (saved.isPresent()) require(saved.get().equals(event), "IMMUTABLE_EVENT_CHANGED");
                else if (ids.add(event.id())) all.add(event);
            }
            require(validateEvents(proof.original(), all,
                    actual.state() == StockDispositionPort.CurrentState.CONSUMED).equals(proof.expectedNeeds()),
                    "NEW_MIRROR_SCOPE_CONFLICT");
        } else if (actual.state() == StockDispositionPort.CurrentState.CONSUMED) {
            require(validateEvents(proof.original(), proof.events(), true).equals(proof.expectedNeeds()),
                    "HISTORICAL_ISSUE_MISSING");
        }
    }

    private List<MirrorEventProof> candidates(RetentionLookup lookup) {
        var refs = new ArrayList<String>();
        refs.add(lookup.stockOriginal().orderNo());
        lookup.stockOriginal().needs().stream().map(Need::fulfillmentRef).distinct().forEach(refs::add);
        return DataScopeContext.executeWithoutScope(() -> events.candidates(refs)).stream()
                .map(this::event).toList();
    }

    private MirrorEventProof event(ExecutionRetentionMirrorEvent row) {
        String payload = row.getPayload();
        // MyBatis SqlTimestampTypeHandler uses getTimestamp, exactly as JdbcOutboxStore's JDBC reader.
        Instant created = row.getCreatedAt().toInstant();
        var occurred = row.getOccurredAt();
        var proof = new MirrorEventProof(row.getId(), row.getEventNo(), row.getAggregateType(),
                row.getAggregateId(), row.getEventType(), payload, OutboxFingerprint.sha256(payload),
                created, row.getSourceSystem(), row.getSourceEventNo(), row.getSchemaVersion(), row.getPayloadHash(),
                row.getStreamKey(), row.getStreamSequence(), row.getRoutingMode(),
                occurred == null ? null : occurred.toInstant(), null, OutboxFingerprint.legacyChecksum(json,
                        row.getAggregateType(), row.getAggregateId(), row.getEventType(), payload, created));
        return legacySigned(proof);
    }

    private MirrorEventProof legacySigned(MirrorEventProof event) {
        String legacy = ExecutionEvidenceContract.hash("EXECUTION_STOCK_MIRROR_LEGACY_ENVELOPE_V2",
                List.of(event.id(), event.eventNo(), event.aggregateType(), event.aggregateId(), event.eventType(),
                        event.payload(), event.rawPayloadSha256(), event.createdAt()));
        return new MirrorEventProof(event.id(), event.eventNo(), event.aggregateType(), event.aggregateId(),
                event.eventType(), event.payload(), event.rawPayloadSha256(), event.createdAt(), event.sourceSystem(),
                event.sourceEventNo(), event.schemaVersion(), event.payloadHash(), event.streamKey(),
                event.streamSequence(), event.routingMode(), event.occurredAt(), legacy, event.adoptionChecksum());
    }

    private void envelope(MirrorEventProof event, JsonNode payload) {
        require(event.id() > 0 && event.eventNo() != null && !event.eventNo().isBlank()
                && "INVENTORY".equals(event.aggregateType()) && event.createdAt() != null
                && OutboxFingerprint.sha256(event.payload()).equals(event.rawPayloadSha256())
                && legacySigned(event).legacyEnvelopeHash().equals(event.legacyEnvelopeHash())
                && OutboxFingerprint.legacyChecksum(json, event.aggregateType(), event.aggregateId(), event.eventType(),
                        event.payload(), event.createdAt()).equals(event.adoptionChecksum()), "MIRROR_ENVELOPE_CONFLICT");
        var metadata = java.util.Arrays.asList(event.sourceSystem(), event.sourceEventNo(), event.schemaVersion(),
                event.payloadHash(), event.streamKey(), event.streamSequence(), event.routingMode(), event.occurredAt());
        if (metadata.stream().allMatch(Objects::isNull)) return;
        require(metadata.stream().noneMatch(Objects::isNull) && "PLATFORM".equals(event.sourceSystem())
                && event.eventNo().equals(event.sourceEventNo()) && event.schemaVersion() == 1
                && event.streamSequence() > 0 && Set.of("DELIVERY", "ARCHIVE_ONLY").contains(event.routingMode()),
                "PARTIAL_UNIFIED_MIRROR_METADATA");
        String order = text(payload, "orderNo", true);
        String stream = order == null || order.isBlank() ? "INVENTORY:" + event.aggregateId() : "ORDER:" + order;
        require(stream.equals(event.streamKey()), "MIRROR_STREAM_CONFLICT");
        String hash = OutboxFingerprint.fingerprint(json, new OutboxEnvelope(event.eventNo(), event.sourceSystem(),
                event.sourceEventNo(), event.aggregateType(), event.aggregateId(), event.eventType(), event.schemaVersion(),
                event.streamKey(), event.streamSequence(), event.payload(), event.occurredAt()));
        require(hash.equals(event.payloadHash()), "MIRROR_FINGERPRINT_CONFLICT");
    }

    private List<Need> validateEvents(RetentionLookup lookup, List<MirrorEventProof> events, boolean consumed) {
        List<Need> subset = null;
        var seen = new HashSet<Long>();
        var lifecycle = new HashSet<String>();
        var issued = new HashSet<String>();
        for (var event : events.stream().sorted(java.util.Comparator.comparingLong(MirrorEventProof::id)).toList()) {
            require(seen.add(event.id()), "DUPLICATE_PHYSICAL_EVENT");
            var body = strictTree(event.payload());
            require(body.isObject() && event.eventType().equals(text(body, "type", false))
                    && event.aggregateId().equals(text(body, "ref", false)) && body.path("items").isArray(),
                    "MIRROR_PAYLOAD_CONFLICT");
            envelope(event, body);
            String type = event.eventType();
            String root = lookup.stockOriginal().orderNo();
            String order = text(body, "orderNo", true);
            if (InvMirrorEvent.ISSUE.equals(type)) {
                require(body.path("items").isEmpty() && lookup.stockOriginal().needs().stream()
                        .anyMatch(n -> n.fulfillmentRef().equals(event.aggregateId())), "MIRROR_ISSUE_ASSOCIATION");
                if (order == null || order.isBlank()) uniqueFulfillment(lookup, event.aggregateId());
                else require(root.equals(order), "MIRROR_ORDER_CONFLICT");
                issued.add(event.aggregateId());
            } else {
                require(root.equals(event.aggregateId()) && root.equals(order), "MIRROR_ORDER_CONFLICT");
                if (InvMirrorEvent.RESERVE.equals(type)) {
                    var current = needs(lookup, body.path("items"));
                    if (subset == null) subset = current;
                    else require(subset.equals(current), "CONFLICTING_RESERVE_SCOPE");
                } else {
                    require((InvMirrorEvent.COMMIT.equals(type) || InvMirrorEvent.ALLOCATE.equals(type))
                            && body.path("items").isEmpty(), "CONTRADICTORY_MIRROR_EVENT");
                    require(type.equals(lookup.stockOriginal().lifecycleVersion() == 1
                            ? InvMirrorEvent.COMMIT : InvMirrorEvent.ALLOCATE), "MIRROR_LIFECYCLE_CONFLICT");
                    lifecycle.add(type);
                }
            }
        }
        require(subset != null && lifecycle.contains(lookup.stockOriginal().lifecycleVersion() == 1
                ? InvMirrorEvent.COMMIT : InvMirrorEvent.ALLOCATE), "HISTORICAL_MIRROR_MISSING");
        if (consumed && lookup.stockOriginal().lifecycleVersion() == 2) {
            require(issued.containsAll(subset.stream().map(Need::fulfillmentRef).toList()), "HISTORICAL_ISSUE_MISSING");
        }
        return subset;
    }

    private List<Need> needs(RetentionLookup lookup, JsonNode items) {
        var values = new ArrayList<StockPort.SkuQty>();
        for (var item : items) {
            require(item.isObject() && item.path("qty").isIntegralNumber()
                    && item.path("qty").canConvertToInt() && item.path("qty").asInt() > 0
                    && item.path("lifecycleVersion").isIntegralNumber()
                    && item.path("lifecycleVersion").asInt() == lookup.stockOriginal().lifecycleVersion(),
                    "MIRROR_ITEM_SHAPE_CONFLICT");
            String store = text(item, "storeNo", false);
            require(lookup.reference().scope().storeNo().equals(store), "MIRROR_STORE_CONFLICT");
            values.add(new StockPort.SkuQty(text(item, "skuNo", false), item.path("qty").asInt(), store,
                    text(item, "fulfillmentRef", false), lookup.stockOriginal().lifecycleVersion()));
        }
        List<Need> result = values.isEmpty() ? List.of() : StockDispositionContract.needs(values);
        for (var need : result) require(lookup.stockOriginal().needs().stream().anyMatch(parent ->
                parent.skuNo().equals(need.skuNo()) && Objects.equals(parent.sourceStoreNo(), need.sourceStoreNo())
                && parent.fulfillmentRef().equals(need.fulfillmentRef()) && parent.quantity() >= need.quantity()),
                "MIRROR_SUBSET_CONFLICT");
        return result;
    }

    private void uniqueFulfillment(RetentionLookup lookup, String fulfillment) {
        var owners = DataScopeContext.executeWithoutScope(() -> events.fulfillmentOwners(fulfillment));
        require(!owners.isEmpty() && owners.stream().allMatch(row -> row.getId() != null && row.getId() > 0
                && lookup.stockOriginal().tenantNo().equals(row.getTenantNo())
                && lookup.stockOriginal().orderNo().equals(row.getLockNo())
                && lookup.reference().scope().storeNo().equals(row.getSourceStoreNo())
                && lookup.stockOriginal().manifestHash().equals(row.getSourceManifestHash())
                && lookup.confirmationOperationNo().equals(row.getConfirmationOperationNo())
                && Integer.valueOf(lookup.stockOriginal().lifecycleVersion()).equals(row.getLifecycleVersion())),
                "AMBIGUOUS_LEGACY_FULFILLMENT");
    }

    private static List<String> lifecycle(RetentionLookup lookup, List<Need> needs, boolean consumed) {
        var result = new ArrayList<String>();
        result.add(InvMirrorEvent.RESERVE);
        result.add(lookup.stockOriginal().lifecycleVersion() == 1 ? InvMirrorEvent.COMMIT : InvMirrorEvent.ALLOCATE);
        if (consumed && lookup.stockOriginal().lifecycleVersion() == 2 && !needs.isEmpty()) result.add(InvMirrorEvent.ISSUE);
        return List.copyOf(result);
    }

    private JsonNode strictTree(String raw) {
        return json.reader().with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
    }

    private static String text(JsonNode node, String field, boolean optional) {
        var value = node.get(field);
        if (optional && (value == null || value.isNull())) return null;
        require(value != null && value.isString() && (optional || !value.asString().isBlank()), "MIRROR_STRING_SHAPE");
        return value.asString();
    }
    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("mirror evidence IO must begin outside a transaction");
        }
    }
}
