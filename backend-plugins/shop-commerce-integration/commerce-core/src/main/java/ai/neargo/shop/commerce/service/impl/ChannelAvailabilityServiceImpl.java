package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.dto.AvailabilityContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimAvailabilityPublication;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.mapper.AvailabilityPublicationMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.service.ChannelAvailabilityService;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway.Publication;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway.Result;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway.Scope;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway.Status;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Facts;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Kind;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Query;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.State;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An independent publication journal, never an inventory ledger. All owner/network calls are
 * outside CIM transactions.
 */
public final class ChannelAvailabilityServiceImpl
        implements ChannelAvailabilityService, ChannelRecoveryPort {
    private final CatalogPersistence db;
    private final AvailabilityPublicationMapper publications;
    private final ResourceMappingMapper mappings;
    private final ChannelResourceMappingPort mappingAuthority;
    private final Supplier<List<ChannelAvailabilitySourcePort>> sources;
    private final Map<String, ChannelAvailabilityGateway> gateways;
    private final Clock clock;
    private final Supplier<ChannelConnectionIdentityPort> identities;

    public ChannelAvailabilityServiceImpl(
            CatalogPersistence db,
            AvailabilityPublicationMapper publications,
            ResourceMappingMapper mappings,
            ChannelResourceMappingPort mappingAuthority,
            Supplier<List<ChannelAvailabilitySourcePort>> sources,
            List<ChannelAvailabilityGateway> gateways,
            Clock clock) {
        this(db, publications, mappings, mappingAuthority, sources, gateways, clock, () -> null);
    }

    public ChannelAvailabilityServiceImpl(
            CatalogPersistence db,
            AvailabilityPublicationMapper publications,
            ResourceMappingMapper mappings,
            ChannelResourceMappingPort mappingAuthority,
            Supplier<List<ChannelAvailabilitySourcePort>> sources,
            List<ChannelAvailabilityGateway> gateways,
            Clock clock,
            Supplier<ChannelConnectionIdentityPort> identities) {
        this.identities = identities;
        this.db = db;
        this.publications = publications;
        this.mappings = mappings;
        this.mappingAuthority = mappingAuthority;
        this.sources = sources;
        this.clock = clock;
        var index = new HashMap<String, ChannelAvailabilityGateway>();
        for (var gateway : gateways)
            require(index.putIfAbsent(gateway.profileNo(), gateway) == null);
        this.gateways = Map.copyOf(index);
    }

    @Override
    public String workflow() {
        return "CHANNEL_AVAILABILITY";
    }

    private record Claim(
            CimOperation op,
            CimAvailabilityPublication row,
            Publication payload,
            long attempt,
            long fence,
            boolean query,
            ChannelAvailabilityGateway gateway) {}

    private record StoreScope(
            ChannelConnectionIdentityPort.Identity identity,
            String revisionNo,
            Owner owner,
            String routingRef,
            String authorizationNo,
            String credentialRef) {}

    private record StoreAuthority(
            StoreScope expected, ChannelConnectionIdentityPort.Proof identity, Facts facts) {}

    @Override
    public OperationVO publishStore(
            String entity,
            String store,
            String actor,
            AvailabilityContracts.StorePublish request,
            String key) {
        outside();
        require(request != null && request.mode() != null);
        text(request.connectionNo(), 32);
        var replay =
                db.commands.replay(
                        entity,
                        store,
                        actor,
                        "PUBLISH_STORE_AVAILABILITY",
                        key,
                        request,
                        OperationVO.class);
        if (replay.isPresent()) return replay.get();
        var proof = storeAuthority(entity, store, request);
        return db.commands.execute(
                entity,
                store,
                actor,
                "PUBLISH_STORE_AVAILABILITY",
                key,
                request,
                OperationVO.class,
                () -> persistStore(entity, store, actor, request, proof),
                OperationVO::operationNo);
    }

    private StoreScope storeScope(CatalogPersistence.Context c) {
        var p =
                db.profiles.selectOne(
                        Wrappers.<CimProductProfile>query()
                                .eq("tenant_no", "MAIN")
                                .eq("profile_no", c.connection().getProfileNo()));
        require(
                p != null
                        && "VERIFIED".equals(p.getStatus())
                        && Objects.equals(p.getProfileNo(), c.account().getProfileNo())
                        && Integer.valueOf(1).equals(p.getIdentitySchemaVersion())
                        && p.getIdentityNamespace() != null
                        && Set.of("GLOBAL", "ACCOUNT").contains(p.getStoreIdentityScope()));
        var owner = Owner.valueOf(c.revision().getSourceOwner());
        require(owner == Owner.FOOD || owner == Owner.RETAIL);
        var grant = grant(c, c.account().getActiveAuthorizationNo(), null);
        require(
                c.connection()
                        .getChannelStoreKey()
                        .equals(
                                ChannelIdentity.storeKey(
                                        p.getIdentityNamespace(),
                                        "ACCOUNT".equals(p.getStoreIdentityScope()),
                                        c.account().getExternalAccountRef(),
                                        c.connection().getExternalStoreRef())));
        return new StoreScope(
                new ChannelConnectionIdentityPort.Identity(
                        c.account().getEntityNo(),
                        c.connection().getStoreNo(),
                        c.account().getAccountNo(),
                        p.getProfileNo(),
                        c.account().getExternalAccountRef(),
                        c.connection().getExternalStoreRef(),
                        p.getIdentityNamespace(),
                        "ACCOUNT".equals(p.getStoreIdentityScope()),
                        c.account().getVersion(),
                        p.getVersion()),
                c.connection().getActiveRevisionNo(),
                owner,
                c.revision().getSourceRoutingRef(),
                grant.getAuthorizationNo(),
                grant.getCredentialRef());
    }

    private StoreAuthority storeAuthority(
            String entity, String store, AvailabilityContracts.StorePublish request) {
        outside();
        var expected =
                db.commands.query(
                        () ->
                                storeScope(
                                        db.connection(
                                                entity,
                                                store,
                                                request.connectionNo(),
                                                false,
                                                false)));
        var verifier = identities.get();
        if (verifier == null)
            throw new UnsupportedOperationException("ORIGINAL_STORE_IDENTITY_UNSUPPORTED");
        var proof = verifier.verify(expected.identity());
        identity(expected.identity(), proof);
        var facts =
                capture(
                        new Query(
                                entity,
                                store,
                                Kind.STORE,
                                store,
                                expected.owner(),
                                request.mode()));
        var after =
                db.commands.query(
                        () ->
                                storeScope(
                                        db.connection(
                                                entity,
                                                store,
                                                request.connectionNo(),
                                                false,
                                                false)));
        require(expected.equals(after));
        identity(expected.identity(), proof);
        fresh(facts, facts.data().query());
        return new StoreAuthority(expected, proof, facts);
    }

    private void identity(
            ChannelConnectionIdentityPort.Identity expected,
            ChannelConnectionIdentityPort.Proof proof) {
        require(
                proof != null
                        && proof.status() == ChannelConnectionIdentityPort.Status.VERIFIED
                        && expected.equals(proof.identity())
                        && proof.verifiedAt() != null
                        && !proof.verifiedAt().isAfter(clock.instant())
                        && proof.validUntil() != null
                        && proof.validUntil().isAfter(clock.instant()));
        text(proof.receiptRef(), 128);
    }

    private OperationVO persistStore(
            String entity,
            String store,
            String actor,
            AvailabilityContracts.StorePublish request,
            StoreAuthority proof) {
        var c = db.connection(entity, store, request.connectionNo(), true, false);
        require(proof.expected().equals(storeScope(c)));
        identity(proof.expected().identity(), proof.identity());
        fresh(
                proof.facts(),
                new Query(
                        entity,
                        store,
                        Kind.STORE,
                        store,
                        proof.expected().owner(),
                        request.mode()));
        var gateway = gateways.get(c.connection().getProfileNo());
        require(
                gateway != null
                        && gateway.supportsAvailability(Kind.STORE)
                        && gateway.supportsStoreMode(request.mode()));
        String target =
                ChannelIdentity.hash(
                        c.connection().getChannelStoreKey() + ":STORE:" + request.mode());
        require(
                db.operations.selectCount(
                                Wrappers.<CimOperation>query()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", entity)
                                        .eq("operation_type", "AVAILABILITY_PUBLISH")
                                        .eq("target_scope_key", target)
                                        .in("status", "QUEUED", "RUNNING", "UNKNOWN"))
                        == 0);
        var previous =
                publications.selectList(
                        Wrappers.<CimAvailabilityPublication>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("target_scope_key", target)
                                .orderByDesc("generation")
                                .last("LIMIT 1"));
        long generation =
                previous.isEmpty() ? 1 : Math.addExact(previous.getFirst().getGeneration(), 1);
        String operation = BizKey.next("CIOP"), number = BizKey.next("CIAV");
        var payload =
                new Publication(
                        operation,
                        generation,
                        new Scope(
                                entity,
                                store,
                                c.connection().getConnectionNo(),
                                c.connection().getProfileNo(),
                                c.account().getExternalAccountRef(),
                                c.connection().getExternalStoreRef(),
                                proof.expected().revisionNo(),
                                proof.expected().authorizationNo(),
                                proof.expected().credentialRef()),
                        new ChannelResourceIdentityGateway.Identity(
                                c.connection().getExternalStoreRef(), List.of()),
                        null,
                        null,
                        proof.facts().data(),
                        proof.facts().originalReceiptRef(),
                        proof.identity());
        // Superseded generations stop polling; all future owner checks run through this same
        // recovery contributor.
        publications.update(
                Wrappers.<CimAvailabilityPublication>update()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("target_scope_key", target)
                        .set("next_source_check_at", null));
        var row = audit(new CimAvailabilityPublication(), actor);
        row.setPublicationNo(number);
        row.setAvailabilityKind("STORE");
        row.setNextSourceCheckAt(nextCheck(payload.source()));
        row.setGeneration(generation);
        row.setTargetScopeKey(target);
        row.setEntityNo(entity);
        row.setStoreNo(store);
        row.setConnectionNo(request.connectionNo());
        row.setSourceHash(PricingSnapshotHash.of(payload.source()));
        row.setPayloadJson(db.json.writeValueAsString(payload));
        row.setOperationNo(operation);
        row.setStatus("QUEUED");
        publications.insert(row);
        var op = audit(new CimOperation(), actor);
        op.setOperationNo(operation);
        op.setEntityNo(entity);
        op.setStoreNo(store);
        op.setConnectionNo(request.connectionNo());
        op.setAccountNo(c.account().getAccountNo());
        op.setProfileNo(c.connection().getProfileNo());
        op.setDestination("CHANNEL");
        op.setOperationType("AVAILABILITY_PUBLISH");
        op.setBusinessRef(number);
        op.setBusinessRevision(payload.source().sourceRevision());
        op.setSemanticKey(ChannelIdentity.hash(number));
        op.setRequestHash(PricingSnapshotHash.of(payload));
        op.setRequestRef("AVAILABILITY:" + number);
        op.setStatus("QUEUED");
        op.setCurrentAttemptNo(0L);
        op.setFence(0L);
        op.setNextRecoveryAt(now());
        op.setTargetScopeKind("CONNECTION");
        op.setTargetScopeKey(target);
        db.operations.insert(op);
        return ChannelOperationServiceImpl.view(op, null);
    }

    private boolean sameStoreAuthority(Publication payload, StoreScope current) {
        return payload.storeIdentityProof() != null
                && payload.storeIdentityProof().identity().equals(current.identity())
                && payload.scope().connectionRevisionNo().equals(current.revisionNo())
                && payload.scope().authorizationNo().equals(current.authorizationNo())
                && payload.scope().credentialRef().equals(current.credentialRef())
                && payload.source().query().owner() == current.owner();
    }

    private void invalidateQueued(CimOperation op, CimAvailabilityPublication row, String reason) {
        require(
                db.operations.update(
                                Wrappers.<CimOperation>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", op.getId())
                                        .eq("status", "QUEUED")
                                        .set("status", "FAILED")
                                        .set("reason_code", reason)
                                        .set("next_recovery_at", null)
                                        .setSql("version=version+1"))
                        == 1);
        publications.update(
                Wrappers.<CimAvailabilityPublication>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", row.getId())
                        .set("status", "FAILED")
                        .set(
                                "next_source_check_at",
                                "STORE".equals(row.getAvailabilityKind())
                                        ? LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                                        : null)
                        .setSql("version=version+1"));
    }

    private LocalDateTime nextCheck(ChannelAvailabilitySourcePort.Data source) {
        Instant next = clock.instant().plusSeconds(30);
        if (source.nextChangeAt() != null
                && source.nextChangeAt().isAfter(clock.instant())
                && source.nextChangeAt().isBefore(next)) next = source.nextChangeAt();
        return LocalDateTime.ofInstant(next, ZoneOffset.UTC);
    }

    private int refreshStores(int limit) {
        var due =
                db.commands.query(
                        () ->
                                publications.selectList(
                                        Wrappers.<CimAvailabilityPublication>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("availability_kind", "STORE")
                                                .in("status", "SUCCEEDED", "FAILED")
                                                .le("next_source_check_at", now())
                                                .orderByAsc("next_source_check_at", "id")
                                                .last("LIMIT " + limit)));
        int created = 0;
        for (var row : due)
            try {
                var payload = db.json.readValue(row.getPayloadJson(), Publication.class);
                var request =
                        new AvailabilityContracts.StorePublish(
                                row.getConnectionNo(), payload.source().query().storeMode());
                var proof = storeAuthority(row.getEntityNo(), row.getStoreNo(), request);
                if (row.getSourceHash().equals(PricingSnapshotHash.of(proof.facts().data()))
                        && sameStoreAuthority(payload, proof.expected())) {
                    db.commands.atomic(
                            () -> {
                                db.connection(
                                        row.getEntityNo(),
                                        row.getStoreNo(),
                                        row.getConnectionNo(),
                                        true,
                                        false);
                                boolean latest =
                                        publications.selectCount(
                                                        Wrappers.<CimAvailabilityPublication>query()
                                                                .eq("tenant_no", "MAIN")
                                                                .eq("entity_no", row.getEntityNo())
                                                                .eq(
                                                                        "target_scope_key",
                                                                        row.getTargetScopeKey())
                                                                .gt(
                                                                        "generation",
                                                                        row.getGeneration()))
                                                == 0;
                                publications.update(
                                        Wrappers.<CimAvailabilityPublication>update()
                                                .eq("id", row.getId())
                                                .eq("version", row.getVersion())
                                                .set(
                                                        "next_source_check_at",
                                                        latest
                                                                ? nextCheck(proof.facts().data())
                                                                : null)
                                                .setSql("version=version+1"));
                                return null;
                            });
                    continue;
                }
                db.commands.execute(
                        row.getEntityNo(),
                        row.getStoreNo(),
                        "SYSTEM",
                        "PUBLISH_STORE_AVAILABILITY",
                        "STORE_REFRESH:" + row.getPublicationNo(),
                        request,
                        OperationVO.class,
                        () -> {
                            var publication =
                                    persistStore(
                                            row.getEntityNo(),
                                            row.getStoreNo(),
                                            "SYSTEM",
                                            request,
                                            proof);
                            publications.update(
                                    Wrappers.<CimAvailabilityPublication>update()
                                            .eq("tenant_no", "MAIN")
                                            .eq("id", row.getId())
                                            .set("next_source_check_at", null));
                            return publication;
                        },
                        OperationVO::operationNo);
                created++;
            } catch (RuntimeException unavailable) {
                db.commands.atomic(
                        () -> {
                            publications.update(
                                    Wrappers.<CimAvailabilityPublication>update()
                                            .eq("id", row.getId())
                                            .eq("version", row.getVersion())
                                            .set(
                                                    "next_source_check_at",
                                                    LocalDateTime.ofInstant(
                                                            clock.instant().plusSeconds(30),
                                                            ZoneOffset.UTC))
                                            .setSql("version=version+1"));
                            return null;
                        });
            }
        return created;
    }

    @Override
    public OperationVO publishStock(
            String entity,
            String store,
            String actor,
            AvailabilityContracts.Publish request,
            String key) {
        outside();
        require(request != null);
        text(request.connectionNo(), 32);
        text(request.mappingNo(), 32);
        text(request.mappingRevisionNo(), 32);
        var replay =
                db.commands.replay(
                        entity,
                        store,
                        actor,
                        "PUBLISH_AVAILABILITY",
                        key,
                        request,
                        OperationVO.class);
        if (replay.isPresent()) return replay.get();
        var mapping = resolve(entity, store, request);
        var facts = capture(new Query(entity, store, Kind.STOCK, mapping.sourceRef()));
        return db.commands.execute(
                entity,
                store,
                actor,
                "PUBLISH_AVAILABILITY",
                key,
                request,
                OperationVO.class,
                () -> persist(entity, store, actor, request, mapping, facts),
                OperationVO::operationNo);
    }

    private ChannelResourceMappingPort.Resolution resolve(
            String entity, String store, AvailabilityContracts.Publish request) {
        outside();
        var value =
                mappingAuthority.resolveHistorical(
                        new ChannelResourceMappingPort.Reference(
                                entity,
                                store,
                                request.connectionNo(),
                                request.mappingNo(),
                                request.mappingRevisionNo()));
        require(
                value != null
                        && request.mappingNo().equals(value.mappingNo())
                        && request.mappingRevisionNo().equals(value.revisionNo())
                        && value.objectType() == ChannelResourceIdentityGateway.ObjectType.SKU
                        && value.identity() != null);
        text(value.identity().externalRef(), 128);
        text(value.sourceRef(), 128);
        return value;
    }

    private Facts capture(Query query) {
        outside();
        var providers =
                sources.get().stream()
                        .filter(
                                p ->
                                        p.kind() == query.kind()
                                                && (query.kind() != Kind.STORE
                                                        || p.owner() == query.owner()))
                        .toList();
        if (providers.isEmpty())
            throw new UnsupportedOperationException("ORIGINAL_AVAILABILITY_OWNER_UNSUPPORTED");
        require(providers.size() == 1);
        var facts = providers.getFirst().capture(query);
        fresh(facts, query);
        return facts;
    }

    private void fresh(Facts facts, Query query) {
        require(
                facts != null
                        && facts.data() != null
                        && query.equals(facts.data().query())
                        && facts.observedAt() != null
                        && !facts.observedAt().isAfter(clock.instant())
                        && facts.validUntil() != null
                        && facts.validUntil().isAfter(clock.instant())
                        && !facts.validUntil().isAfter(facts.observedAt().plusSeconds(30)));
        text(facts.originalReceiptRef(), 256);
        text(facts.data().sourceRevision(), 128);
        if (query.kind() == Kind.STORE) {
            require(
                    query.owner() != null
                            && query.storeMode() != null
                            && query.storeNo().equals(query.resourceRef())
                            && Set.of(State.OPEN, State.CLOSED, State.SUSPENDED)
                                    .contains(facts.data().state())
                            && facts.data().quantity() == null
                            && facts.data().unit() == null
                            && (facts.data().nextChangeAt() == null
                                    || facts.data().nextChangeAt().isAfter(clock.instant())
                                            && !facts.validUntil()
                                                    .isAfter(facts.data().nextChangeAt())));
            return;
        }
        require(facts.data().state() == State.QUANTITY);
        text(facts.data().quantity(), 32);
        text(facts.data().unit(), 32);
        require(facts.data().quantity().matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?"));
        require(new BigDecimal(facts.data().quantity()).signum() >= 0);
    }

    private CimResourceMapping currentMapping(
            String entity, String store, String connection, String mapping, String revision) {
        var row =
                mappings.selectOne(
                        Wrappers.<CimResourceMapping>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("store_no", store)
                                .eq("connection_no", connection)
                                .eq("mapping_no", mapping)
                                .last("FOR UPDATE"));
        require(row != null && revision.equals(row.getActiveRevisionNo()));
        return row;
    }

    private CimAuthorizationRevision grant(
            CatalogPersistence.Context c, String authorization, String credential) {
        require(
                "ACTIVE".equals(c.account().getStatus())
                        && "ACTIVE".equals(c.connection().getStatus())
                        && authorization.equals(c.account().getActiveAuthorizationNo()));
        var grant =
                db.authorizations.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", c.account().getEntityNo())
                                .eq("account_no", c.account().getAccountNo())
                                .eq("authorization_no", authorization));
        require(
                grant != null
                        && "GRANTED".equals(grant.getStatus())
                        && grant.getCredentialRef() != null
                        && (credential == null || credential.equals(grant.getCredentialRef()))
                        && (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(now())));
        var scopes = db.json.readTree(grant.getGrantedScopes());
        require(scopes.isArray());
        boolean allowed = false;
        for (var scope : scopes)
            if (scope.isString() && "AVAILABILITY".equals(scope.asString())) allowed = true;
        require(allowed);
        return grant;
    }

    private OperationVO persist(
            String entity,
            String store,
            String actor,
            AvailabilityContracts.Publish request,
            ChannelResourceMappingPort.Resolution mapping,
            Facts facts) {
        var c = db.connection(entity, store, request.connectionNo(), true, false);
        var active =
                currentMapping(
                        entity,
                        store,
                        request.connectionNo(),
                        request.mappingNo(),
                        request.mappingRevisionNo());
        require(active.getExternalRef().equals(mapping.identity().externalRef()));
        fresh(facts, new Query(entity, store, Kind.STOCK, mapping.sourceRef()));
        var authorization = grant(c, c.account().getActiveAuthorizationNo(), null);
        String target =
                ChannelIdentity.hash(
                        c.connection().getChannelStoreKey()
                                + ":STOCK:"
                                + active.getExternalIdentityHash());
        // Never send a newer value while an older request may still be executing at the provider.
        require(
                db.operations.selectCount(
                                Wrappers.<CimOperation>query()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", entity)
                                        .eq("operation_type", "AVAILABILITY_PUBLISH")
                                        .eq("target_scope_key", target)
                                        .in("status", "QUEUED", "RUNNING", "UNKNOWN"))
                        == 0);
        String operation = BizKey.next("CIOP"), number = BizKey.next("CIAV");
        var previous =
                publications.selectList(
                        Wrappers.<CimAvailabilityPublication>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("target_scope_key", target)
                                .orderByDesc("generation")
                                .last("LIMIT 1"));
        long generation =
                previous.isEmpty() ? 1 : Math.addExact(previous.getFirst().getGeneration(), 1);
        var payload =
                new Publication(
                        operation,
                        generation,
                        new Scope(
                                entity,
                                store,
                                c.connection().getConnectionNo(),
                                c.connection().getProfileNo(),
                                c.account().getExternalAccountRef(),
                                c.connection().getExternalStoreRef(),
                                c.connection().getActiveRevisionNo(),
                                authorization.getAuthorizationNo(),
                                authorization.getCredentialRef()),
                        mapping.identity(),
                        mapping.mappingNo(),
                        mapping.revisionNo(),
                        facts.data(),
                        facts.originalReceiptRef());
        var gateway = gateways.get(payload.scope().profileNo());
        require(gateway != null && gateway.supportsAvailability(Kind.STOCK));
        var row = audit(new CimAvailabilityPublication(), actor);
        row.setPublicationNo(number);
        row.setAvailabilityKind("STOCK");
        row.setGeneration(generation);
        row.setTargetScopeKey(target);
        row.setEntityNo(entity);
        row.setStoreNo(store);
        row.setConnectionNo(request.connectionNo());
        row.setMappingNo(mapping.mappingNo());
        row.setMappingRevisionNo(mapping.revisionNo());
        row.setSourceHash(PricingSnapshotHash.of(facts.data()));
        row.setPayloadJson(db.json.writeValueAsString(payload));
        row.setOperationNo(operation);
        row.setStatus("QUEUED");
        publications.insert(row);
        var op = audit(new CimOperation(), actor);
        op.setOperationNo(operation);
        op.setEntityNo(entity);
        op.setStoreNo(store);
        op.setConnectionNo(request.connectionNo());
        op.setAccountNo(c.account().getAccountNo());
        op.setProfileNo(c.connection().getProfileNo());
        op.setDestination("CHANNEL");
        op.setOperationType("AVAILABILITY_PUBLISH");
        op.setBusinessRef(number);
        op.setBusinessRevision(facts.data().sourceRevision());
        op.setSemanticKey(ChannelIdentity.hash(number));
        op.setRequestHash(PricingSnapshotHash.of(payload));
        op.setRequestRef("AVAILABILITY:" + number);
        op.setStatus("QUEUED");
        op.setCurrentAttemptNo(0L);
        op.setFence(0L);
        op.setNextRecoveryAt(now());
        op.setTargetScopeKind("CONNECTION");
        op.setTargetScopeKey(target);
        db.operations.insert(op);
        return ChannelOperationServiceImpl.view(op, null);
    }

    @Override
    public int recoverDue(int limit) {
        outside();
        require(limit >= 1 && limit <= 100);
        int refreshed = refreshStores(limit);
        var due =
                db.commands.query(
                        () ->
                                db.operations.selectList(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("operation_type", "AVAILABILITY_PUBLISH")
                                                .in("status", "QUEUED", "RUNNING", "UNKNOWN")
                                                .le("next_recovery_at", now())
                                                .orderByAsc("next_recovery_at", "id")
                                                .last("LIMIT " + limit)));
        int completed = refreshed;
        for (var candidate : due) {
            Claim claim = null;
            try {
                Facts fresh = null;
                StoreAuthority storeAuthority = null;
                if ("QUEUED".equals(candidate.getStatus())) {
                    var row =
                            db.commands.query(
                                    () ->
                                            row(
                                                    candidate.getEntityNo(),
                                                    candidate.getBusinessRef(),
                                                    false));
                    var payload = db.json.readValue(row.getPayloadJson(), Publication.class);
                    if (payload.source().query().kind() == Kind.STORE) {
                        storeAuthority =
                                storeAuthority(
                                        candidate.getEntityNo(),
                                        candidate.getStoreNo(),
                                        new AvailabilityContracts.StorePublish(
                                                candidate.getConnectionNo(),
                                                payload.source().query().storeMode()));
                        fresh = storeAuthority.facts();
                    } else {
                        var resolved =
                                resolve(
                                        candidate.getEntityNo(),
                                        candidate.getStoreNo(),
                                        new AvailabilityContracts.Publish(
                                                candidate.getConnectionNo(),
                                                row.getMappingNo(),
                                                row.getMappingRevisionNo()));
                        require(
                                resolved.identity().equals(payload.identity())
                                        && resolved.sourceRef()
                                                .equals(payload.source().query().resourceRef()));
                        fresh = capture(payload.source().query());
                    }
                }
                var proof = fresh;
                var storeProof = storeAuthority;
                claim = db.commands.atomic(() -> claim(candidate, proof, storeProof));
                if (claim == null) continue;
                var result =
                        claim.query()
                                ? claim.gateway().queryAvailability(claim.payload())
                                : claim.gateway().publishAvailability(claim.payload());
                var current = claim;
                if (db.commands.atomic(() -> finish(current, result))) completed++;
            } catch (RuntimeException unavailable) {
                if (claim != null) {
                    var failed = claim;
                    db.commands.atomic(() -> finish(failed, null));
                } else defer(candidate);
            }
        }
        return completed;
    }

    private Claim claim(CimOperation candidate, Facts facts, StoreAuthority storeProof) {
        var c =
                db.connection(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getConnectionNo(),
                        true,
                        false);
        var op = operation(candidate.getEntityNo(), candidate.getOperationNo());
        var row = row(candidate.getEntityNo(), candidate.getBusinessRef(), true);
        if (!Set.of("QUEUED", "RUNNING", "UNKNOWN").contains(op.getStatus())
                || op.getNextRecoveryAt() == null
                || op.getNextRecoveryAt().isAfter(now())
                || op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(now())) return null;
        var payload = db.json.readValue(row.getPayloadJson(), Publication.class);
        require(
                op.getRequestHash().equals(PricingSnapshotHash.of(payload))
                        && op.getOperationNo().equals(payload.operationNo()));
        var gateway = gateways.get(op.getProfileNo());
        require(gateway != null);
        boolean query = !"QUEUED".equals(op.getStatus());
        if (!query) {
            if (payload.source().query().kind() == Kind.STORE) {
                require(storeProof != null && storeProof.expected().equals(storeScope(c)));
                identity(storeProof.expected().identity(), storeProof.identity());
                if (!sameStoreAuthority(payload, storeProof.expected())) {
                    invalidateQueued(op, row, "SOURCE_ROUTE_CHANGED");
                    return null;
                }
                require(
                        payload.identity()
                                        .externalRef()
                                        .equals(c.connection().getExternalStoreRef())
                                && payload.source().query().owner()
                                        == Owner.valueOf(c.revision().getSourceOwner()));
            } else {
                currentMapping(
                        op.getEntityNo(),
                        op.getStoreNo(),
                        op.getConnectionNo(),
                        payload.mappingNo(),
                        payload.mappingRevisionNo());
            }
            fresh(facts, payload.source().query());
            if (!row.getSourceHash().equals(PricingSnapshotHash.of(facts.data()))) {
                invalidateQueued(op, row, "SOURCE_CHANGED");
                return null;
            }
            require(
                    payload.scope()
                            .connectionRevisionNo()
                            .equals(c.connection().getActiveRevisionNo()));
            grant(c, payload.scope().authorizationNo(), payload.scope().credentialRef());
            require(gateway.supportsAvailability(payload.source().query().kind()));
            if (payload.source().query().kind() == Kind.STORE)
                require(gateway.supportsStoreMode(payload.source().query().storeMode()));
        }
        long attempt = Math.addExact(op.getCurrentAttemptNo(), 1),
                fence = Math.addExact(op.getFence(), 1);
        var journal = audit(new CimOperationAttempt(), "SYSTEM");
        journal.setAttemptRef(BizKey.next("CIAT"));
        journal.setEntityNo(op.getEntityNo());
        journal.setOperationNo(op.getOperationNo());
        journal.setAttemptNo(attempt);
        journal.setFence(fence);
        journal.setAttemptKind(query ? "QUERY" : "SEND");
        journal.setProviderRequestRef(op.getOperationNo());
        journal.setStartedAt(now());
        journal.setOutcome("RUNNING");
        db.attempts.insert(journal);
        require(
                db.operations.update(
                                Wrappers.<CimOperation>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", op.getId())
                                        .eq("fence", op.getFence())
                                        .set("status", "RUNNING")
                                        .set("fence", fence)
                                        .set("current_attempt_no", attempt)
                                        .set("lease_until", now().plusMinutes(2))
                                        .set("next_recovery_at", now().plusMinutes(2))
                                        .setSql("version=version+1"))
                        == 1);
        return new Claim(op, row, payload, attempt, fence, query, gateway);
    }

    private boolean finish(Claim claim, Result result) {
        var p = claim.payload();
        db.connection(
                p.scope().entityNo(), p.scope().storeNo(), p.scope().connectionNo(), true, false);
        var op = operation(p.scope().entityNo(), p.operationNo());
        var row = row(op.getEntityNo(), claim.row().getPublicationNo(), true);
        var a =
                db.attempts.selectOne(
                        Wrappers.<CimOperationAttempt>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", op.getEntityNo())
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", claim.attempt())
                                .last("FOR UPDATE"));
        require(a != null);
        boolean valid =
                result != null
                        && result.status() != null
                        && op.getOperationNo().equals(result.operationNo())
                        && op.getRequestHash().equals(result.publicationHash());
        if (valid && result.status() != Status.UNKNOWN)
            try {
                text(result.originalReceiptRef(), 256);
            } catch (RuntimeException bad) {
                valid = false;
            }
        if (op.getFence() != claim.fence() || op.getCurrentAttemptNo() != claim.attempt()) {
            if (valid && result.originalReceiptRef() != null)
                db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", a.getId())
                                .isNull("late_evidence_ref")
                                .set("late_evidence_ref", result.originalReceiptRef())
                                .set("late_outcome", result.status().name()));
            return false;
        }
        boolean terminal = valid && result.status() != Status.UNKNOWN;
        String status =
                terminal
                        ? (result.status() == Status.CONFIRMED ? "SUCCEEDED" : "FAILED")
                        : "UNKNOWN";
        String receipt = valid ? result.originalReceiptRef() : null;
        db.attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", a.getId())
                        .isNull("finished_at")
                        .set("finished_at", now())
                        .set("outcome", status)
                        .set("response_ref", receipt));
        db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", op.getId())
                        .eq("fence", claim.fence())
                        .set("status", status)
                        .set("lease_until", null)
                        .set("original_evidence_ref", receipt)
                        .set("next_recovery_at", terminal ? null : now().plusSeconds(30))
                        .setSql("version=version+1"));
        publications.update(
                Wrappers.<CimAvailabilityPublication>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", row.getId())
                        .set(
                                "next_source_check_at",
                                p.source().query().kind() == Kind.STORE && terminal
                                        ? nextCheck(p.source())
                                        : null)
                        .set("status", status)
                        .set("external_evidence_ref", receipt)
                        .setSql("version=version+1"));
        return terminal;
    }

    private CimOperation operation(String entity, String number) {
        var op =
                db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("operation_no", number)
                                .last("FOR UPDATE"));
        require(op != null);
        return op;
    }

    private CimAvailabilityPublication row(String entity, String number, boolean lock) {
        var q =
                Wrappers.<CimAvailabilityPublication>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("publication_no", number);
        if (lock) q.last("FOR UPDATE");
        var row = publications.selectOne(q);
        require(row != null);
        return row;
    }

    private void defer(CimOperation candidate) {
        db.commands.atomic(
                () -> {
                    db.operations.update(
                            Wrappers.<CimOperation>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", candidate.getEntityNo())
                                    .eq("operation_no", candidate.getOperationNo())
                                    .eq("version", candidate.getVersion())
                                    .eq("status", "QUEUED")
                                    .set("next_recovery_at", now().plusMinutes(1))
                                    .setSql("version=version+1"));
                    return null;
                });
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Availability owner/network calls require external boundary");
    }
}
