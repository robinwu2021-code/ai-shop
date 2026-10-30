package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.dto.RedemptionContracts;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimRedemptionItem;
import ai.neargo.shop.commerce.entity.CimRedemptionLink;
import ai.neargo.shop.commerce.entity.CimRedemptionPreview;
import ai.neargo.shop.commerce.entity.CimRedemptionReversal;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.mapper.RedemptionItemMapper;
import ai.neargo.shop.commerce.mapper.RedemptionLinkMapper;
import ai.neargo.shop.commerce.mapper.RedemptionPreviewMapper;
import ai.neargo.shop.commerce.mapper.RedemptionReversalMapper;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.ChannelRedemptionPolicy;
import ai.neargo.shop.commerce.service.ChannelRedemptionService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;
import ai.neargo.shop.spi.commerce.ChannelRedemptionGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.commerce.CommerceGateway.Capability;
import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionPort;
import ai.neargo.shop.spi.entitlement.ExternalCertificateEvidencePort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * External issuer effects and original completed-business consumption remain independently
 * journaled; no local asset/payment is fabricated.
 */
public final class ChannelRedemptionServiceImpl
        implements ChannelRedemptionService, ChannelRecoveryPort, ExternalCertificateEvidencePort {
    private final CatalogPersistence db;
    private final RedemptionPreviewMapper previews;
    private final RedemptionLinkMapper links;
    private final RedemptionItemMapper items;
    private final RedemptionReversalMapper reversals;
    private final ResourceMappingMapper mappings;
    private final ChannelResourceMappingPort mappingAuthority;
    private final ChannelCertificateHandleStore handles;
    private final Supplier<List<ExternalCertificateConsumptionPort>> sourceOwners;
    private final Map<String, ChannelRedemptionGateway> gateways;
    private final Clock clock;

    public ChannelRedemptionServiceImpl(
            CatalogPersistence db,
            RedemptionPreviewMapper previews,
            RedemptionLinkMapper links,
            RedemptionItemMapper items,
            RedemptionReversalMapper reversals,
            ResourceMappingMapper mappings,
            ChannelResourceMappingPort mappingAuthority,
            ChannelCertificateHandleStore handles,
            Supplier<List<ExternalCertificateConsumptionPort>> sourceOwners,
            List<ChannelRedemptionGateway> gateways,
            Clock clock) {
        this.db = db;
        this.previews = previews;
        this.links = links;
        this.items = items;
        this.reversals = reversals;
        this.mappings = mappings;
        this.mappingAuthority = mappingAuthority;
        this.handles = handles;
        this.sourceOwners = sourceOwners;
        this.clock = clock;
        var index = new HashMap<String, ChannelRedemptionGateway>();
        for (var gateway : gateways)
            require(index.putIfAbsent(gateway.profileNo(), gateway) == null);
        this.gateways = Map.copyOf(index);
    }

    private record Ticket(String reference) {}

    private record Claim(
            CimOperation op,
            long attempt,
            long fence,
            boolean query,
            ChannelRedemptionGateway gateway,
            ChannelRedemptionGateway.Verify verification,
            ChannelRedemptionGateway.Action action) {}

    @Override
    public String workflow() {
        return "CHANNEL_REDEMPTION";
    }

    private ExternalCertificateConsumptionPort owner() {
        var owners = sourceOwners.get();
        if (owners.size() != 1)
            throw new UnsupportedOperationException(
                    "Original external certificate consumption owner unavailable");
        return owners.getFirst();
    }

    private ExternalCertificateConsumptionPort.Scope scope(String entity, String store) {
        return new ExternalCertificateConsumptionPort.Scope("MAIN", entity, store);
    }

    @Override
    public RedemptionContracts.Previewed preview(
            String entity,
            String store,
            String actor,
            RedemptionContracts.Preview request,
            String key) {
        outside();
        require(
                request != null
                        && request.sourceConsumptionRef() != null
                        && request.sourceConsumptionRef().orderItemId() > 0);
        text(request.connectionNo(), 32);
        text(request.controlledCertificateRef(), 32);
        text(request.sourceConsumptionRef().subOrderNo(), 32);
        var handleScope =
                new ChannelCertificateHandleStore.Scope(entity, store, request.connectionNo());
        var controlled = handles.read(handleScope, request.controlledCertificateRef());
        require(
                controlled != null
                        && handleScope.equals(controlled.scope())
                        && "SEALED".equals(controlled.status())
                        && controlled.encryptedBlobId() != null
                        && controlled.encryptedBlobId() > 0);
        var ticket =
                db.commands.execute(
                        entity,
                        store,
                        actor,
                        "PREVIEW_REDEMPTION",
                        key,
                        request,
                        Ticket.class,
                        () -> queuePreview(entity, store, actor, request),
                        Ticket::reference);
        recoverPreview(entity, ticket.reference());
        var preview = db.commands.query(() -> previewRow(entity, store, ticket.reference(), false));
        if (preview.getVerificationJson() != null
                && preview.getSourceSkuRef() != null
                && preview.getAuthorizationJson() == null) {
            var authorization =
                    owner().authorize(
                                    scope(entity, store),
                                    request.sourceConsumptionRef(),
                                    preview.getPreviewNo(),
                                    preview.getPreviewHash());
            var certificate =
                    db.json
                            .readValue(
                                    preview.getVerificationJson(),
                                    ChannelRedemptionGateway.Verified.class)
                            .certificate();
            require(
                    authorization != null
                            && authorization
                                    .certificateIdentityHash()
                                    .equals(certificate.certificateIdentityHash())
                            && authorization.unit().equals(certificate.unit())
                            && authorization.quantity().compareTo(certificate.remainingQuantity())
                                    <= 0
                            && authorization.expiresAt().isAfter(clock.instant())
                            && !authorization.expiresAt().isAfter(certificate.validUntil()));
            ChannelRedemptionPolicy.quantity(authorization.quantity(), false);
            ChannelRedemptionPolicy.hash(authorization.sourceHash());
            db.commands.atomic(
                    () -> {
                        var current = previewRow(entity, store, ticket.reference(), true);
                        require(
                                current.getPreviewHash().equals(authorization.previewHash())
                                        && current.getPreviewNo().equals(authorization.previewRef())
                                        && scope(entity, store).equals(authorization.scope())
                                        && "USE".equals(authorization.purpose())
                                        && "RESERVED".equals(authorization.status()));
                        previews.update(
                                Wrappers.<CimRedemptionPreview>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", current.getId())
                                        .isNull("authorization_json")
                                        .set(
                                                "source_authorization_ref",
                                                authorization.authorizationRef())
                                        .set(
                                                "authorization_json",
                                                db.json.writeValueAsString(authorization))
                                        .set("status", "READY")
                                        .setSql("version=version+1"));
                        return null;
                    });
        }
        return db.commands.query(
                () -> previewView(previewRow(entity, store, ticket.reference(), false)));
    }

    private Ticket queuePreview(
            String entity, String store, String actor, RedemptionContracts.Preview request) {
        var c = db.connection(entity, store, request.connectionNo(), true, false);
        var grant = grant(c, null);
        require(
                gateways.containsKey(c.connection().getProfileNo())
                        && gateways.get(c.connection().getProfileNo())
                                .capabilities()
                                .contains(Capability.REDEMPTION));
        String number = BizKey.next("CIRP"), operation = BizKey.next("CIOP");
        var verification =
                new ChannelRedemptionGateway.Verify(
                        operation, gatewayScope(c, grant), request.controlledCertificateRef());
        var row = audit(new CimRedemptionPreview(), actor);
        row.setPreviewNo(number);
        row.setEntityNo(entity);
        row.setStoreNo(store);
        row.setConnectionNo(request.connectionNo());
        row.setHandleNo(request.controlledCertificateRef());
        row.setBusinessJson(db.json.writeValueAsString(request.sourceConsumptionRef()));
        row.setOperationNo(operation);
        row.setRequestJson(db.json.writeValueAsString(verification));
        row.setStatus("QUEUED");
        previews.insert(row);
        insertOperation(
                c,
                actor,
                operation,
                "CERTIFICATE_VERIFY",
                number,
                row.getVersion().toString(),
                PricingSnapshotHash.of(verification));
        return new Ticket(number);
    }

    private ChannelRedemptionGateway.Scope gatewayScope(
            CatalogPersistence.Context c, CimAuthorizationRevision grant) {
        return new ChannelRedemptionGateway.Scope(
                c.connection().getEntityNo(),
                c.connection().getStoreNo(),
                c.connection().getConnectionNo(),
                c.connection().getProfileNo(),
                c.account().getExternalAccountRef(),
                c.connection().getExternalStoreRef(),
                c.connection().getActiveRevisionNo(),
                grant.getAuthorizationNo(),
                grant.getCredentialRef());
    }

    private CimAuthorizationRevision grant(
            CatalogPersistence.Context c, ChannelRedemptionGateway.Scope original) {
        require(
                "ACTIVE".equals(c.account().getStatus())
                        && "ACTIVE".equals(c.connection().getStatus())
                        && c.account().getActiveAuthorizationNo() != null);
        if (original != null)
            require(
                    original.profileNo().equals(c.connection().getProfileNo())
                            && original.profileNo().equals(c.account().getProfileNo())
                            && original.authorizationNo()
                                    .equals(c.account().getActiveAuthorizationNo())
                            && original.connectionRevisionNo()
                                    .equals(c.connection().getActiveRevisionNo())
                            && original.externalAccountRef()
                                    .equals(c.account().getExternalAccountRef())
                            && original.externalStoreRef()
                                    .equals(c.connection().getExternalStoreRef()));
        var grant =
                db.authorizations.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", c.account().getEntityNo())
                                .eq("account_no", c.account().getAccountNo())
                                .eq("authorization_no", c.account().getActiveAuthorizationNo()));
        require(
                grant != null
                        && "GRANTED".equals(grant.getStatus())
                        && grant.getCredentialRef() != null
                        && (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(now()))
                        && (original == null
                                || original.credentialRef().equals(grant.getCredentialRef())));
        var scopes = db.json.readTree(grant.getGrantedScopes());
        require(scopes.isArray());
        boolean permitted = false;
        for (var scope : scopes)
            if (scope.isString() && "REDEMPTION".equals(scope.asString())) permitted = true;
        require(permitted);
        return grant;
    }

    /**
     * Freezes the actual preview/link revision or original consumption authorization source hash.
     */
    private void insertOperation(
            CatalogPersistence.Context c,
            String actor,
            String number,
            String type,
            String business,
            String businessRevision,
            String hash) {
        text(businessRevision, 128);
        var op = audit(new CimOperation(), actor);
        op.setOperationNo(number);
        op.setEntityNo(c.account().getEntityNo());
        op.setStoreNo(c.connection().getStoreNo());
        op.setConnectionNo(c.connection().getConnectionNo());
        op.setAccountNo(c.account().getAccountNo());
        op.setProfileNo(c.connection().getProfileNo());
        op.setDestination("CHANNEL");
        op.setOperationType(type);
        op.setBusinessRef(business);
        op.setBusinessRevision(businessRevision);
        // A consumption and its reversal refer to the same business but are distinct effects.
        // Freeze the operation kind and original revision into the semantic identity as well.
        op.setSemanticKey(PricingSnapshotHash.of(List.of(type, business, businessRevision)));
        op.setRequestHash(hash);
        op.setRequestRef(type + ":" + business);
        op.setStatus("QUEUED");
        op.setCurrentAttemptNo(0L);
        op.setFence(0L);
        op.setNextRecoveryAt(now());
        op.setTargetScopeKind("CONNECTION");
        op.setTargetScopeKey(ChannelIdentity.hash(c.connection().getChannelStoreKey()));
        db.operations.insert(op);
    }

    private void recoverPreview(String entity, String number) {
        var row =
                db.commands.query(
                        () ->
                                previews.selectOne(
                                        Wrappers.<CimRedemptionPreview>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", entity)
                                                .eq("preview_no", number)));
        require(row != null);
        var op =
                db.commands.query(
                        () ->
                                db.operations.selectOne(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", entity)
                                                .eq("operation_no", row.getOperationNo())));
        recover(op);
    }

    @Override
    public OperationVO redeem(
            String entity,
            String store,
            String actor,
            RedemptionContracts.Redeem request,
            String key) {
        outside();
        require(request != null);
        text(request.previewRef(), 32);
        text(request.sourceAuthorizationRef(), 32);
        ChannelRedemptionPolicy.hash(request.expectedHash());
        return db.commands.execute(
                entity,
                store,
                actor,
                "REDEEM_CERTIFICATE",
                key,
                request,
                OperationVO.class,
                () -> {
                    var before = previewRow(entity, store, request.previewRef(), false);
                    var c = db.connection(entity, store, before.getConnectionNo(), true, false);
                    var preview = previewRow(entity, store, request.previewRef(), true);
                    require(
                            "READY".equals(preview.getStatus())
                                    && request.expectedHash().equals(preview.getPreviewHash())
                                    && request.sourceAuthorizationRef()
                                            .equals(preview.getSourceAuthorizationRef()));
                    var verified =
                            db.json.readValue(
                                    preview.getVerificationJson(),
                                    ChannelRedemptionGateway.Verified.class);
                    var original =
                            db.json.readValue(
                                    preview.getRequestJson(),
                                    ChannelRedemptionGateway.Verify.class);
                    grant(c, original.scope());
                    ChannelRedemptionPolicy.verified(original, verified, clock);
                    var auth =
                            db.json.readValue(
                                    preview.getAuthorizationJson(),
                                    ExternalCertificateConsumptionPort.Authorization.class);
                    require(
                            auth.expiresAt().isAfter(clock.instant())
                                    && "RESERVED".equals(auth.status())
                                    && request.sourceAuthorizationRef()
                                            .equals(auth.authorizationRef()));
                    String number = BizKey.next("CIRD"), operation = BizKey.next("CIOP");
                    var action =
                            new ChannelRedemptionGateway.Action(
                                    operation,
                                    original.scope(),
                                    preview.getPreviewNo(),
                                    original.operationNo(),
                                    auth.certificateIdentityHash(),
                                    auth.authorizationRef(),
                                    auth.sourceHash(),
                                    auth.quantity(),
                                    auth.unit(),
                                    verified.certificate().originalReceiptRef(),
                                    null);
                    var row = audit(new CimRedemptionLink(), actor);
                    row.setRedemptionNo(number);
                    row.setEntityNo(entity);
                    row.setStoreNo(store);
                    row.setConnectionNo(preview.getConnectionNo());
                    row.setPreviewNo(preview.getPreviewNo());
                    row.setSourceAuthorizationRef(auth.authorizationRef());
                    row.setSourceHash(auth.sourceHash());
                    row.setCertificateIdentityHash(auth.certificateIdentityHash());
                    row.setQuantity(auth.quantity());
                    row.setUnit(auth.unit());
                    row.setOperationNo(operation);
                    row.setRequestJson(db.json.writeValueAsString(action));
                    row.setStatus("QUEUED");
                    links.insert(row);
                    var item = audit(new CimRedemptionItem(), actor);
                    item.setItemNo(BizKey.next("CIRI"));
                    item.setEntityNo(entity);
                    item.setRedemptionNo(number);
                    item.setSourceHash(auth.sourceHash());
                    item.setSourceConsumptionRef(db.json.writeValueAsString(auth.business()));
                    item.setQuantity(auth.quantity());
                    item.setUnit(auth.unit());
                    items.insert(item);
                    insertOperation(
                            c,
                            actor,
                            operation,
                            "CERTIFICATE_REDEEM",
                            number,
                            auth.sourceHash(),
                            PricingSnapshotHash.of(action));
                    previews.update(
                            Wrappers.<CimRedemptionPreview>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", preview.getId())
                                    .set("status", "USED")
                                    .setSql("version=version+1"));
                    return ChannelOperationServiceImpl.view(
                            db.operations.selectOne(
                                    Wrappers.<CimOperation>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("operation_no", operation)),
                            null);
                },
                OperationVO::operationNo);
    }

    @Override
    public int recoverDue(int limit) {
        outside();
        require(limit >= 1 && limit <= 100);
        int completed = 0;
        var due =
                db.commands.query(
                        () ->
                                db.operations.selectList(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .in(
                                                        "operation_type",
                                                        "CERTIFICATE_VERIFY",
                                                        "CERTIFICATE_REDEEM",
                                                        "CERTIFICATE_REVERSE")
                                                .in("status", "QUEUED", "RUNNING", "UNKNOWN")
                                                .le("next_recovery_at", now())
                                                .orderByAsc("next_recovery_at", "id")
                                                .last("LIMIT " + limit)));
        for (var op : due) if (recover(op)) completed++;
        var pending =
                db.commands.query(
                        () ->
                                links.selectList(
                                        Wrappers.<CimRedemptionLink>query()
                                                .eq("tenant_no", "MAIN")
                                                .in(
                                                        "status",
                                                        "PLATFORM_CONFIRMED_SOURCE_PENDING",
                                                        "PLATFORM_REJECTED_SOURCE_PENDING")
                                                .orderByAsc("id")
                                                .last("LIMIT " + limit)));
        for (var row : pending)
            try {
                var source =
                        owner().reconcile(
                                        scope(row.getEntityNo(), row.getStoreNo()),
                                        row.getSourceAuthorizationRef());
                if ("CONFIRMED".equals(source.status()) || "REJECTED".equals(source.status()))
                    db.commands.atomic(
                            () -> {
                                var current =
                                        link(
                                                row.getEntityNo(),
                                                row.getStoreNo(),
                                                row.getRedemptionNo(),
                                                true);
                                require(
                                        current.getSourceHash().equals(source.sourceHash())
                                                && current.getQuantity()
                                                                .compareTo(source.quantity())
                                                        == 0);
                                links.update(
                                        Wrappers.<CimRedemptionLink>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", current.getId())
                                                .in(
                                                        "status",
                                                        "PLATFORM_CONFIRMED_SOURCE_PENDING",
                                                        "PLATFORM_REJECTED_SOURCE_PENDING")
                                                .set(
                                                        "status",
                                                        "CONFIRMED".equals(source.status())
                                                                ? "SUCCEEDED"
                                                                : "REJECTED")
                                                .set("source_decision_ref", source.decisionRef())
                                                .setSql("version=version+1"));
                                return null;
                            });
            } catch (RuntimeException unavailable) {
                /* Original source remains pending; never fake its confirmation. */
            }
        var reversePending =
                db.commands.query(
                        () ->
                                reversals.selectList(
                                        Wrappers.<CimRedemptionReversal>query()
                                                .eq("tenant_no", "MAIN")
                                                .in(
                                                        "status",
                                                        "PLATFORM_CONFIRMED_SOURCE_PENDING",
                                                        "PLATFORM_REJECTED_SOURCE_PENDING")
                                                .orderByAsc("id")
                                                .last("LIMIT " + limit)));
        for (var row : reversePending)
            try {
                var source =
                        owner().reconcile(
                                        scope(row.getEntityNo(), row.getStoreNo()),
                                        row.getSourceAuthorizationRef());
                if (Set.of("CONFIRMED", "REJECTED").contains(source.status()))
                    db.commands.atomic(
                            () -> {
                                var original =
                                        link(
                                                row.getEntityNo(),
                                                row.getStoreNo(),
                                                row.getRedemptionNo(),
                                                true);
                                var current =
                                        reversal(
                                                row.getEntityNo(),
                                                row.getStoreNo(),
                                                row.getReversalNo(),
                                                true);
                                require(
                                        current.getSourceHash().equals(source.sourceHash())
                                                && current.getQuantity()
                                                                .compareTo(source.quantity())
                                                        == 0);
                                reversals.update(
                                        Wrappers.<CimRedemptionReversal>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", current.getId())
                                                .in(
                                                        "status",
                                                        "PLATFORM_CONFIRMED_SOURCE_PENDING",
                                                        "PLATFORM_REJECTED_SOURCE_PENDING")
                                                .set(
                                                        "status",
                                                        "CONFIRMED".equals(source.status())
                                                                ? "SUCCEEDED"
                                                                : "REJECTED")
                                                .set("source_decision_ref", source.decisionRef())
                                                .setSql("version=version+1"));
                                links.update(
                                        Wrappers.<CimRedemptionLink>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", original.getId())
                                                .eq("status", "REVERSING")
                                                .set(
                                                        "status",
                                                        "CONFIRMED".equals(source.status())
                                                                ? "REVERSED"
                                                                : "REVERSAL_REJECTED")
                                                .setSql("version=version+1"));
                                return null;
                            });
            } catch (RuntimeException unavailable) {
                /* Original reversal decision remains pending. */
            }
        return completed;
    }

    private boolean recover(CimOperation candidate) {
        if (candidate == null
                || !Set.of("QUEUED", "RUNNING", "UNKNOWN").contains(candidate.getStatus()))
            return false;
        Claim claim = null;
        try {
            ExternalCertificateConsumptionPort.Authorization source = null;
            if ("CERTIFICATE_REDEEM".equals(candidate.getOperationType())
                    && "QUEUED".equals(candidate.getStatus())) {
                var row =
                        db.commands.query(
                                () ->
                                        link(
                                                candidate.getEntityNo(),
                                                candidate.getStoreNo(),
                                                candidate.getBusinessRef(),
                                                false));
                var preview =
                        db.commands.query(
                                () ->
                                        previewRow(
                                                row.getEntityNo(),
                                                row.getStoreNo(),
                                                row.getPreviewNo(),
                                                false));
                var originalVerify =
                        db.json.readValue(
                                preview.getRequestJson(), ChannelRedemptionGateway.Verify.class);
                var verified =
                        db.json.readValue(
                                preview.getVerificationJson(),
                                ChannelRedemptionGateway.Verified.class);
                ChannelRedemptionPolicy.verified(originalVerify, verified, clock);
                require(preview.getSourceSkuRef().equals(originalSku(originalVerify, verified)));
                source =
                        owner().freeze(
                                        scope(row.getEntityNo(), row.getStoreNo()),
                                        row.getSourceAuthorizationRef());
                require(
                        "PENDING".equals(source.status())
                                && row.getSourceHash().equals(source.sourceHash()));
            }
            if ("CERTIFICATE_REVERSE".equals(candidate.getOperationType())
                    && "QUEUED".equals(candidate.getStatus())) {
                var row =
                        db.commands.query(
                                () ->
                                        reversal(
                                                candidate.getEntityNo(),
                                                candidate.getStoreNo(),
                                                candidate.getBusinessRef(),
                                                false));
                source =
                        owner().freeze(
                                        scope(row.getEntityNo(), row.getStoreNo()),
                                        row.getSourceAuthorizationRef());
                require(
                        "PENDING".equals(source.status())
                                && "REVERSE".equals(source.purpose())
                                && row.getSourceHash().equals(source.sourceHash()));
            }
            var nativeSource = source;
            claim = db.commands.atomic(() -> claim(candidate, nativeSource));
            if (claim == null) return false;
            if (claim.verification() != null) {
                var verified = claim.gateway().verifyCertificate(claim.verification());
                ChannelRedemptionPolicy.verified(claim.verification(), verified, clock);
                String sku = null;
                if (verified.status() == ChannelRedemptionGateway.Status.CONFIRMED)
                    sku = originalSku(claim.verification(), verified);
                var current = claim;
                var sourceSku = sku;
                return db.commands.atomic(() -> finishVerification(current, verified, sourceSku));
            }
            boolean reversing = claim.action().originalRedemptionOperationNo() != null;
            var result =
                    reversing
                            ? (claim.query()
                                    ? claim.gateway().queryReversal(claim.action())
                                    : claim.gateway().reverseCertificate(claim.action()))
                            : (claim.query()
                                    ? claim.gateway().queryRedemption(claim.action())
                                    : claim.gateway().redeemCertificate(claim.action()));
            ChannelRedemptionPolicy.result(claim.action(), result);
            var current = claim;
            return db.commands.atomic(() -> finishAction(current, result));
        } catch (RuntimeException unknown) {
            if (claim != null) {
                var failed = claim;
                db.commands.atomic(
                        () ->
                                failed.verification() != null
                                        ? finishVerification(failed, null, null)
                                        : finishAction(failed, null));
            } else
                db.commands.atomic(
                        () -> {
                            db.operations.update(
                                    Wrappers.<CimOperation>update()
                                            .eq("tenant_no", "MAIN")
                                            .eq("id", candidate.getId())
                                            .eq("version", candidate.getVersion())
                                            .eq("status", "QUEUED")
                                            .set("next_recovery_at", now().plusMinutes(1)));
                            return null;
                        });
            return false;
        }
    }

    private String originalSku(
            ChannelRedemptionGateway.Verify request, ChannelRedemptionGateway.Verified result) {
        var certificate = result.certificate();
        var candidates =
                db.commands.query(
                        () ->
                                mappings.selectList(
                                        Wrappers.<CimResourceMapping>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", request.scope().entityNo())
                                                .eq("store_no", request.scope().storeNo())
                                                .eq("connection_no", request.scope().connectionNo())
                                                .eq("object_type", "SKU")
                                                .eq(
                                                        "external_ref",
                                                        certificate.productIdentity().externalRef())
                                                .isNotNull("active_revision_no")
                                                .last("LIMIT 2")));
        if (candidates.size() != 1)
            throw new UnsupportedOperationException(
                    "Original certificate product mapping unavailable");
        var mapping = candidates.getFirst();
        var actual =
                mappingAuthority.resolveHistorical(
                        new ChannelResourceMappingPort.Reference(
                                request.scope().entityNo(),
                                request.scope().storeNo(),
                                request.scope().connectionNo(),
                                mapping.getMappingNo(),
                                mapping.getActiveRevisionNo()));
        require(
                actual != null
                        && actual.objectType() == ObjectType.SKU
                        && actual.identity().equals(certificate.productIdentity()));
        return actual.sourceRef();
    }

    private Claim claim(
            CimOperation candidate, ExternalCertificateConsumptionPort.Authorization source) {
        var c =
                db.connection(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getConnectionNo(),
                        true,
                        false);
        var op = operation(candidate.getEntityNo(), candidate.getOperationNo());
        if (!Set.of("QUEUED", "RUNNING", "UNKNOWN").contains(op.getStatus())
                || op.getNextRecoveryAt() == null
                || op.getNextRecoveryAt().isAfter(now())
                || op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(now())) return null;
        var gateway = gateways.get(op.getProfileNo());
        require(gateway != null);
        boolean query = !"QUEUED".equals(op.getStatus());
        ChannelRedemptionGateway.Verify verify = null;
        ChannelRedemptionGateway.Action action = null;
        if ("CERTIFICATE_VERIFY".equals(op.getOperationType())) {
            var preview = previewRow(op.getEntityNo(), op.getStoreNo(), op.getBusinessRef(), true);
            verify =
                    db.json.readValue(
                            preview.getRequestJson(), ChannelRedemptionGateway.Verify.class);
            // Verification recovery reads sensitive material again, so every such read requires its
            // actual current grant.
            grant(c, verify.scope());
            require(op.getRequestHash().equals(PricingSnapshotHash.of(verify)));
        } else {
            String payload;
            if ("CERTIFICATE_REVERSE".equals(op.getOperationType())) {
                var before =
                        reversal(op.getEntityNo(), op.getStoreNo(), op.getBusinessRef(), false);
                link(op.getEntityNo(), op.getStoreNo(), before.getRedemptionNo(), true);
                payload =
                        reversal(op.getEntityNo(), op.getStoreNo(), op.getBusinessRef(), true)
                                .getRequestJson();
            } else
                payload =
                        link(op.getEntityNo(), op.getStoreNo(), op.getBusinessRef(), true)
                                .getRequestJson();
            action = db.json.readValue(payload, ChannelRedemptionGateway.Action.class);
            require(op.getRequestHash().equals(PricingSnapshotHash.of(action)));
            if (!query) {
                grant(c, action.scope());
                require(
                        source != null
                                && source.authorizationRef().equals(action.sourceAuthorizationRef())
                                && scope(op.getEntityNo(), op.getStoreNo()).equals(source.scope())
                                && source.certificateIdentityHash()
                                        .equals(action.certificateIdentityHash())
                                && source.unit().equals(action.unit())
                                && source.expiresAt().isAfter(clock.instant())
                                && (action.originalRedemptionOperationNo() == null
                                                ? "USE"
                                                : "REVERSE")
                                        .equals(source.purpose())
                                && source.sourceHash().equals(action.sourceHash())
                                && source.quantity().compareTo(action.quantity()) == 0);
            }
        }
        long attempt = Math.addExact(op.getCurrentAttemptNo(), 1),
                fence = Math.addExact(op.getFence(), 1);
        var journal = audit(new CimOperationAttempt(), "SYSTEM");
        journal.setAttemptRef(BizKey.next("CIAT"));
        journal.setEntityNo(op.getEntityNo());
        journal.setOperationNo(op.getOperationNo());
        journal.setAttemptNo(attempt);
        journal.setFence(fence);
        journal.setAttemptKind(verify != null || query ? "QUERY" : "SEND");
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
                                        .set("current_attempt_no", attempt)
                                        .set("fence", fence)
                                        .set("lease_until", now().plusMinutes(2))
                                        .set("next_recovery_at", now().plusMinutes(2))
                                        .setSql("version=version+1"))
                        == 1);
        return new Claim(op, attempt, fence, query, gateway, verify, action);
    }

    private boolean finishVerification(
            Claim claim, ChannelRedemptionGateway.Verified result, String sku) {
        var op =
                lockedClaim(
                        claim,
                        result == null
                                ? null
                                : result.certificate() == null
                                        ? null
                                        : result.certificate().originalReceiptRef(),
                        result == null ? null : result.status().name());
        if (op == null) return false;
        var preview = previewRow(op.getEntityNo(), op.getStoreNo(), op.getBusinessRef(), true);
        boolean success =
                result != null && result.status() == ChannelRedemptionGateway.Status.CONFIRMED;
        boolean rejected =
                result != null && result.status() == ChannelRedemptionGateway.Status.REJECTED;
        String status = success ? "SUCCEEDED" : rejected ? "FAILED" : "UNKNOWN";
        var update =
                Wrappers.<CimRedemptionPreview>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", preview.getId())
                        .set("status", success ? "VERIFIED" : status);
        if (success)
            update.set("verification_json", db.json.writeValueAsString(result))
                    .set("source_sku_ref", sku)
                    .set(
                            "preview_hash",
                            PricingSnapshotHash.of(
                                    List.of(
                                            claim.verification(),
                                            result.certificate(),
                                            sku,
                                            db.json.readValue(
                                                    preview.getBusinessJson(),
                                                    ExternalCertificateConsumptionPort.Business
                                                            .class))));
        previews.update(update.setSql("version=version+1"));
        complete(claim, status, success ? result.certificate().originalReceiptRef() : null);
        return success || rejected;
    }

    private boolean finishAction(Claim claim, ChannelRedemptionGateway.Result result) {
        if (claim.action().originalRedemptionOperationNo() != null)
            return finishReversal(claim, result);
        var op =
                lockedClaim(
                        claim,
                        result == null ? null : result.originalReceiptRef(),
                        result == null ? null : result.status().name());
        if (op == null) return false;
        var row = link(op.getEntityNo(), op.getStoreNo(), op.getBusinessRef(), true);
        boolean success =
                result != null && result.status() == ChannelRedemptionGateway.Status.CONFIRMED;
        boolean rejected =
                result != null && result.status() == ChannelRedemptionGateway.Status.REJECTED;
        String status = success ? "SUCCEEDED" : rejected ? "FAILED" : "UNKNOWN";
        links.update(
                Wrappers.<CimRedemptionLink>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", row.getId())
                        .set(
                                "status",
                                success
                                        ? "PLATFORM_CONFIRMED_SOURCE_PENDING"
                                        : rejected ? "PLATFORM_REJECTED_SOURCE_PENDING" : "UNKNOWN")
                        .set(
                                "original_receipt_ref",
                                result == null ? null : result.originalReceiptRef())
                        .set(
                                "result_json",
                                result == null ? null : db.json.writeValueAsString(result))
                        .setSql("version=version+1"));
        complete(claim, status, result == null ? null : result.originalReceiptRef());
        return success || rejected;
    }

    private boolean finishReversal(Claim claim, ChannelRedemptionGateway.Result result) {
        var op =
                lockedClaim(
                        claim,
                        result == null ? null : result.originalReceiptRef(),
                        result == null ? null : result.status().name());
        if (op == null) return false;
        var before = reversal(op.getEntityNo(), op.getStoreNo(), op.getBusinessRef(), false);
        link(op.getEntityNo(), op.getStoreNo(), before.getRedemptionNo(), true);
        var row = reversal(op.getEntityNo(), op.getStoreNo(), op.getBusinessRef(), true);
        boolean success =
                result != null && result.status() == ChannelRedemptionGateway.Status.CONFIRMED;
        boolean rejected =
                result != null && result.status() == ChannelRedemptionGateway.Status.REJECTED;
        String status = success ? "SUCCEEDED" : rejected ? "FAILED" : "UNKNOWN";
        reversals.update(
                Wrappers.<CimRedemptionReversal>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", row.getId())
                        .set(
                                "status",
                                success
                                        ? "PLATFORM_CONFIRMED_SOURCE_PENDING"
                                        : rejected ? "PLATFORM_REJECTED_SOURCE_PENDING" : "UNKNOWN")
                        .set(
                                "original_receipt_ref",
                                result == null ? null : result.originalReceiptRef())
                        .set(
                                "result_json",
                                result == null ? null : db.json.writeValueAsString(result))
                        .setSql("version=version+1"));
        complete(claim, status, result == null ? null : result.originalReceiptRef());
        return success || rejected;
    }

    private CimRedemptionReversal reversal(
            String entity, String store, String number, boolean lock) {
        var query =
                Wrappers.<CimRedemptionReversal>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("store_no", store)
                        .eq("reversal_no", number);
        if (lock) query.last("FOR UPDATE");
        var row = reversals.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private CimOperation lockedClaim(Claim claim, String receipt, String outcome) {
        var original = claim.op();
        db.connection(
                original.getEntityNo(),
                original.getStoreNo(),
                original.getConnectionNo(),
                true,
                false);
        var op = operation(original.getEntityNo(), original.getOperationNo());
        if (op.getFence() != claim.fence() || op.getCurrentAttemptNo() != claim.attempt()) {
            if (receipt != null)
                db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", op.getEntityNo())
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", claim.attempt())
                                .isNull("late_evidence_ref")
                                .set("late_evidence_ref", receipt)
                                .set("late_outcome", outcome));
            return null;
        }
        return op;
    }

    private void complete(Claim claim, String status, String receipt) {
        db.attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", claim.op().getEntityNo())
                        .eq("operation_no", claim.op().getOperationNo())
                        .eq("attempt_no", claim.attempt())
                        .isNull("finished_at")
                        .set("finished_at", now())
                        .set("outcome", status)
                        .set("response_ref", receipt));
        db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", claim.op().getId())
                        .eq("fence", claim.fence())
                        .set("status", status)
                        .set("lease_until", null)
                        .set(
                                "next_recovery_at",
                                "UNKNOWN".equals(status) ? now().plusSeconds(30) : null)
                        .set("original_evidence_ref", receipt)
                        .setSql("version=version+1"));
    }

    private CimOperation operation(String entity, String no) {
        var row =
                db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("operation_no", no)
                                .last("FOR UPDATE"));
        require(row != null);
        return row;
    }

    private CimRedemptionPreview previewRow(String entity, String store, String no, boolean lock) {
        text(no, 32);
        var query =
                Wrappers.<CimRedemptionPreview>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("store_no", store)
                        .eq("preview_no", no);
        if (lock) query.last("FOR UPDATE");
        var row = previews.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private CimRedemptionLink link(String entity, String store, String no, boolean lock) {
        text(no, 32);
        var query =
                Wrappers.<CimRedemptionLink>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("store_no", store)
                        .eq("redemption_no", no);
        if (lock) query.last("FOR UPDATE");
        var row = links.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private RedemptionContracts.Previewed previewView(CimRedemptionPreview row) {
        var authorization =
                row.getAuthorizationJson() == null
                        ? null
                        : db.json.readValue(
                                row.getAuthorizationJson(),
                                ExternalCertificateConsumptionPort.Authorization.class);
        String status =
                "READY".equals(row.getStatus())
                                && authorization != null
                                && !authorization.expiresAt().isAfter(clock.instant())
                        ? "EXPIRED"
                        : row.getStatus();
        return new RedemptionContracts.Previewed(
                row.getPreviewNo(),
                status,
                row.getPreviewHash(),
                row.getSourceAuthorizationRef(),
                authorization == null ? null : authorization.quantity(),
                authorization == null ? null : authorization.unit(),
                authorization == null ? null : authorization.expiresAt());
    }

    @Override
    public Verified verified(
            ExternalCertificateConsumptionPort.Scope scope, String previewRef, String hash) {
        return db.commands.query(
                () -> {
                    require("MAIN".equals(scope.tenantNo()));
                    var preview = previewRow(scope.entityNo(), scope.storeNo(), previewRef, false);
                    require(
                            hash.equals(preview.getPreviewHash())
                                    && preview.getVerificationJson() != null
                                    && preview.getSourceSkuRef() != null);
                    var certificate =
                            db.json
                                    .readValue(
                                            preview.getVerificationJson(),
                                            ChannelRedemptionGateway.Verified.class)
                                    .certificate();
                    require(certificate.validUntil().isAfter(clock.instant()));
                    return new Verified(
                            scope,
                            previewRef,
                            hash,
                            certificate.certificateIdentityHash(),
                            preview.getSourceSkuRef(),
                            certificate.externalOrderRef(),
                            certificate.remainingQuantity(),
                            certificate.unit(),
                            certificate.validUntil(),
                            certificate.originalReceiptRef());
                });
    }

    @Override
    public Decision decision(
            ExternalCertificateConsumptionPort.Scope scope, String authorizationRef) {
        return db.commands.query(
                () -> {
                    require("MAIN".equals(scope.tenantNo()));
                    var row =
                            links.selectOne(
                                    Wrappers.<CimRedemptionLink>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", scope.entityNo())
                                            .eq("store_no", scope.storeNo())
                                            .eq("source_authorization_ref", authorizationRef));
                    String identity, sourceHash, unit, resultJson, requestJson;
                    BigDecimal quantity;
                    if (row != null) {
                        identity = row.getCertificateIdentityHash();
                        sourceHash = row.getSourceHash();
                        unit = row.getUnit();
                        quantity = row.getQuantity();
                        resultJson = row.getResultJson();
                        requestJson = row.getRequestJson();
                    } else {
                        var reversal =
                                reversals.selectOne(
                                        Wrappers.<CimRedemptionReversal>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", scope.entityNo())
                                                .eq("store_no", scope.storeNo())
                                                .eq("source_authorization_ref", authorizationRef));
                        require(reversal != null);
                        identity = reversal.getCertificateIdentityHash();
                        sourceHash = reversal.getSourceHash();
                        unit = reversal.getUnit();
                        quantity = reversal.getQuantity();
                        resultJson = reversal.getResultJson();
                        requestJson = reversal.getRequestJson();
                    }
                    String status = "UNKNOWN", receipt = null;
                    if (resultJson != null) {
                        var result =
                                db.json.readValue(
                                        resultJson, ChannelRedemptionGateway.Result.class);
                        var action =
                                db.json.readValue(
                                        requestJson, ChannelRedemptionGateway.Action.class);
                        ChannelRedemptionPolicy.result(action, result);
                        status = result.status().name();
                        receipt = result.originalReceiptRef();
                    }
                    return new Decision(
                            scope,
                            authorizationRef,
                            identity,
                            sourceHash,
                            quantity,
                            unit,
                            status,
                            receipt);
                });
    }

    @Override
    public RedemptionContracts.Redemption getRedemption(String entity, String store, String no) {
        return db.commands.query(
                () -> {
                    var row = link(entity, store, no, false);
                    return new RedemptionContracts.Redemption(
                            row.getRedemptionNo(),
                            row.getStoreNo(),
                            row.getConnectionNo(),
                            row.getStatus(),
                            row.getOperationNo(),
                            row.getQuantity(),
                            row.getUnit(),
                            row.getSourceAuthorizationRef(),
                            row.getSourceDecisionRef(),
                            row.getOriginalReceiptRef(),
                            row.getVersion());
                });
    }

    @Override
    public OperationVO reverse(
            String entity,
            String store,
            String actor,
            String number,
            RedemptionContracts.Reverse request,
            String key) {
        outside();
        require(request != null && request.version() != null);
        text(request.sourceReversalAuthorizationRef(), 32);
        text(request.reason(), 255);
        String endpoint = "REVERSE_CERTIFICATE:" + number;
        var replay =
                db.commands.replay(entity, store, actor, endpoint, key, request, OperationVO.class);
        if (replay.isPresent()) return replay.get();
        var original = db.commands.query(() -> link(entity, store, number, false));
        require(
                original.getVersion().equals(request.version())
                        && "SUCCEEDED".equals(original.getStatus()));
        var owners = sourceOwners.get();
        if (owners.size() > 1)
            throw new IllegalStateException(
                    "Duplicate original external certificate consumption owners");
        var readiness =
                owners.isEmpty()
                        ? new ExternalCertificateConsumptionPort.ReversalReadiness(
                                "UNSUPPORTED", "ORIGINAL_CONSUMPTION_OWNER_UNAVAILABLE", null)
                        : owners.getFirst()
                                .reversalReadiness(
                                        scope(entity, store),
                                        original.getSourceAuthorizationRef(),
                                        request.sourceReversalAuthorizationRef());
        require(
                readiness != null
                        && Set.of("READY", "WAITING_SOURCE", "UNSUPPORTED")
                                .contains(readiness.status()));
        if (!"READY".equals(readiness.status())) {
            require(
                    readiness.authorization() == null
                            && readiness.reasonCode() != null
                            && readiness.reasonCode().matches("[A-Z_]{1,64}"));
            return db.commands.execute(
                    entity,
                    store,
                    actor,
                    endpoint,
                    key,
                    request,
                    OperationVO.class,
                    () -> {
                        var c =
                                db.connection(
                                        entity, store, original.getConnectionNo(), true, false);
                        var current = link(entity, store, number, true);
                        require(
                                current.getVersion().equals(request.version())
                                        && "SUCCEEDED".equals(current.getStatus()));
                        String operationNo = BizKey.next("CIOP");
                        insertOperation(
                                c,
                                actor,
                                operationNo,
                                "CERTIFICATE_REVERSE",
                                number,
                                current.getVersion().toString(),
                                PricingSnapshotHash.of(request));
                        db.operations.update(
                                Wrappers.<CimOperation>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("entity_no", entity)
                                        .eq("operation_no", operationNo)
                                        .set("status", "WAITING_SOURCE")
                                        .set("reason_code", readiness.reasonCode())
                                        .set("next_recovery_at", null)
                                        .setSql("version=version+1"));
                        var waiting =
                                db.operations.selectOne(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", entity)
                                                .eq("operation_no", operationNo));
                        return new OperationVO(
                                waiting.getOperationNo(),
                                waiting.getOperationType(),
                                waiting.getStatus(),
                                waiting.getAccountNo(),
                                null,
                                null,
                                readiness.reasonCode(),
                                waiting.getVersion());
                    },
                    OperationVO::operationNo);
        }
        var source = readiness.authorization();
        require(
                "RESERVED".equals(source.status())
                        && "REVERSE".equals(source.purpose())
                        && original.getSourceAuthorizationRef()
                                .equals(source.originalAuthorizationRef())
                        && source.expiresAt().isAfter(clock.instant())
                        && original.getQuantity().compareTo(source.quantity()) == 0);
        return db.commands.execute(
                entity,
                store,
                actor,
                endpoint,
                key,
                request,
                OperationVO.class,
                () -> {
                    var c = db.connection(entity, store, original.getConnectionNo(), true, false);
                    var grant = grant(c, null);
                    var current = link(entity, store, number, true);
                    require(
                            current.getVersion().equals(request.version())
                                    && "SUCCEEDED".equals(current.getStatus()));
                    var originalAction =
                            db.json.readValue(
                                    current.getRequestJson(),
                                    ChannelRedemptionGateway.Action.class);
                    String reversalNo = BizKey.next("CIRV"), operationNo = BizKey.next("CIOP");
                    var action =
                            new ChannelRedemptionGateway.Action(
                                    operationNo,
                                    gatewayScope(c, grant),
                                    current.getPreviewNo(),
                                    originalAction.originalVerificationOperationNo(),
                                    current.getCertificateIdentityHash(),
                                    source.authorizationRef(),
                                    source.sourceHash(),
                                    source.quantity(),
                                    source.unit(),
                                    originalAction.originalVerificationReceiptRef(),
                                    current.getOperationNo());
                    var row = audit(new CimRedemptionReversal(), actor);
                    row.setReversalNo(reversalNo);
                    row.setRedemptionNo(number);
                    row.setEntityNo(entity);
                    row.setStoreNo(store);
                    row.setConnectionNo(current.getConnectionNo());
                    row.setSourceAuthorizationRef(source.authorizationRef());
                    row.setSourceHash(source.sourceHash());
                    row.setCertificateIdentityHash(source.certificateIdentityHash());
                    row.setQuantity(source.quantity());
                    row.setUnit(source.unit());
                    row.setOperationNo(operationNo);
                    row.setRequestJson(db.json.writeValueAsString(action));
                    row.setStatus("QUEUED");
                    reversals.insert(row);
                    insertOperation(
                            c,
                            actor,
                            operationNo,
                            "CERTIFICATE_REVERSE",
                            reversalNo,
                            source.sourceHash(),
                            PricingSnapshotHash.of(action));
                    links.update(
                            Wrappers.<CimRedemptionLink>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", current.getId())
                                    .eq("status", "SUCCEEDED")
                                    .set("status", "REVERSING")
                                    .setSql("version=version+1"));
                    return ChannelOperationServiceImpl.view(
                            db.operations.selectOne(
                                    Wrappers.<CimOperation>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("operation_no", operationNo)),
                            null);
                },
                OperationVO::operationNo);
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Original certificate owners and HTTP require external transaction boundary");
    }
}
