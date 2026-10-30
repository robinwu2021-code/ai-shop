package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.instant;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.dto.MappingContracts.MappingRevisionVO;
import ai.neargo.shop.commerce.dto.MappingContracts.ResourceMappingVO;
import ai.neargo.shop.commerce.entity.CimConnectionRevision;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.entity.CimResourceMappingRevision;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingRevisionMapper;
import ai.neargo.shop.commerce.policy.CatalogResourceIndex;
import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.SourceContext;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 只访问cim映射与本域上下文；外部身份与原业务资源均通过合同证明。 */
public final class MappingPersistence {
    final CatalogPersistence db;
    final ResourceMappingMapper mappings;
    final ResourceMappingRevisionMapper revisions;
    final Map<String, ChannelResourceIdentityGateway> gateways = new HashMap<>();

    public MappingPersistence(
            CatalogPersistence db,
            ResourceMappingMapper mappings,
            ResourceMappingRevisionMapper revisions,
            List<ChannelResourceIdentityGateway> gateways) {
        this.db = db;
        this.mappings = mappings;
        this.revisions = revisions;
        for (var g : gateways)
            if (this.gateways.put(g.profileNo(), g) != null)
                throw new IllegalStateException("Duplicate resource identity gateway profile");
    }

    record Context(
            CatalogPersistence.Context base,
            CimResourceMapping mapping,
            CimResourceMappingRevision latest) {}

    record Snapshot(
            ObjectType objectType,
            Identity identity,
            Owner sourceOwner,
            String sourceRef,
            String sourceRevision,
            String salesOfferRef,
            Validation sourceProof,
            ChannelResourceIdentityGateway.Proof externalProof,
            @JsonInclude(JsonInclude.Include.NON_NULL) SourceContext sourceContext,
            @JsonInclude(JsonInclude.Include.NON_NULL) HistoricalEvidence historicalEvidence) {
        Snapshot(
                ObjectType objectType,
                Identity identity,
                Owner sourceOwner,
                String sourceRef,
                String sourceRevision,
                String salesOfferRef,
                Validation sourceProof,
                ChannelResourceIdentityGateway.Proof externalProof,
                SourceContext sourceContext) {
            this(
                    objectType,
                    identity,
                    sourceOwner,
                    sourceRef,
                    sourceRevision,
                    salesOfferRef,
                    sourceProof,
                    externalProof,
                    sourceContext,
                    null);
        }

        Snapshot(
                ObjectType objectType,
                Identity identity,
                Owner sourceOwner,
                String sourceRef,
                String sourceRevision,
                String salesOfferRef,
                Validation sourceProof,
                ChannelResourceIdentityGateway.Proof externalProof) {
            this(
                    objectType,
                    identity,
                    sourceOwner,
                    sourceRef,
                    sourceRevision,
                    salesOfferRef,
                    sourceProof,
                    externalProof,
                    null,
                    null);
        }
    }

    record HistoricalEvidence(
            String basis,
            String catalogHash,
            String snapshotNo,
            String runNo,
            String operationNo,
            long attemptNo,
            String attemptReceiptRef,
            String itemReceiptRef,
            String itemNo) {}

    Context scoped(String entity, String store, String no, boolean lock) {
        text(entity, 32);
        text(store, 32);
        text(no, 32);
        var m =
                mappings.selectOne(
                        Wrappers.<CimResourceMapping>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("store_no", store)
                                .eq("mapping_no", no));
        if (m == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var base = db.connection(entity, store, m.getConnectionNo(), lock, false);
        if (lock)
            m =
                    mappings.selectOne(
                            Wrappers.<CimResourceMapping>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("store_no", store)
                                    .eq("mapping_no", no)
                                    .last("FOR UPDATE"));
        require(m != null);
        return new Context(base, m, revision(entity, no, m.getLatestRevisionNo()));
    }

    CimResourceMappingRevision revision(String entity, String mapping, String revision) {
        text(revision, 32);
        var row =
                revisions.selectOne(
                        Wrappers.<CimResourceMappingRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("mapping_no", mapping)
                                .eq("revision_no", revision));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    Identity identity(CimResourceMapping m) {
        return db.json.readValue(m.getExternalIdentityJson(), Identity.class);
    }

    void identity(ObjectType type, Identity value) {
        require(
                type != null
                        && value != null
                        && value.parents() != null
                        && value.parents().size() <= 8);
        text(value.externalRef(), 128);
        var seen = new HashSet<String>();
        for (var p : value.parents()) {
            require(p != null && p.objectType() != null);
            text(p.externalRef(), 128);
            require(seen.add(p.objectType() + ":" + p.externalRef()));
        }
    }

    String hash(CatalogPersistence.Context c, ObjectType type, Identity identity) {
        // The stable channel shop key already contains the server-owned verified protocol
        // namespace.
        var parts =
                new StringBuilder(ChannelIdentity.part(c.connection().getChannelStoreKey()))
                        .append(ChannelIdentity.part(type.name()));
        for (var p : identity.parents())
            parts.append(ChannelIdentity.part(p.objectType().name()))
                    .append(ChannelIdentity.part(p.externalRef()));
        return ChannelIdentity.hash(
                parts.append(ChannelIdentity.part(identity.externalRef())).toString());
    }

    void source(
            CatalogPersistence.Context c,
            ObjectType type,
            Owner owner,
            String ref,
            String rev,
            String offer) {
        source(c, type, owner, ref, rev, offer, null);
    }

    void source(
            CatalogPersistence.Context c,
            ObjectType type,
            Owner owner,
            String ref,
            String rev,
            String offer,
            SourceContext context) {
        require(
                type != null
                        && "LOCAL".equals(c.revision().getCatalogAuthority())
                        && owner != null
                        && owner.name().equals(c.revision().getSourceOwner()));
        text(ref, 128);
        text(rev, 128);
        if (needsOffer(type)) text(offer, 128);
        else require(offer == null);
        CatalogSourceTransportPolicy.validateSource(
                new ChannelSourceValidationPort.Source(
                        c.connection().getEntityNo(),
                        c.connection().getStoreNo(),
                        owner,
                        ChannelSourceValidationPort.Kind.valueOf(type.name()),
                        ref,
                        rev,
                        offer,
                        context));
        if (context != null) require(context.mode() == ReadMode.CURRENT);
    }

    static boolean needsOffer(ObjectType type) {
        return type == ObjectType.OFFER
                || type == ObjectType.SKU
                || type == ObjectType.OPTION_GROUP
                || type == ObjectType.OPTION
                || type == ObjectType.BUNDLE_GROUP
                || type == ObjectType.BUNDLE_MEMBER;
    }

    SourceContext context(CimResourceMappingRevision row) {
        require(ChannelIdentity.hash(row.getMappingSnapshot()).equals(row.getContentHash()));
        return db.json.readValue(row.getMappingSnapshot(), Snapshot.class).sourceContext();
    }

    Validation validate(Context c, CimResourceMappingRevision row) {
        return validate(c, row, false);
    }

    Validation validate(Context c, CimResourceMappingRevision row, boolean historical) {
        var type = ObjectType.valueOf(c.mapping().getObjectType());
        var owner = Owner.valueOf(row.getSourceOwner());
        var frozen =
                db.revisions.selectOne(
                        Wrappers.<CimConnectionRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", c.mapping().getEntityNo())
                                .eq("connection_no", c.mapping().getConnectionNo())
                                .eq("revision_no", row.getConnectionRevisionNo()));
        require(frozen != null);
        var original =
                new CatalogPersistence.Context(
                        c.base().account(), c.base().connection(), frozen, null);
        source(
                original,
                type,
                owner,
                row.getSourceRef(),
                row.getSourceRevisionRef(),
                row.getSalesOfferRef(),
                context(row));
        var requested = context(row);
        if (historical && owner == Owner.FOOD) {
            require(requested != null || type == ObjectType.MENU);
            requested =
                    new SourceContext(
                            requested == null ? row.getSourceRef() : requested.menuRef(),
                            requested == null ? null : requested.optionGroupRef(),
                            ReadMode.HISTORICAL,
                            requested == null ? null : requested.variantSkuRef(),
                            requested == null ? null : requested.bundleGroupRef());
        }
        var port = db.authority.get();
        require(port != null);
        var result =
                port.validate(
                        new ChannelSourceValidationPort.Source(
                                c.mapping().getEntityNo(),
                                c.mapping().getStoreNo(),
                                owner,
                                ChannelSourceValidationPort.Kind.valueOf(type.name()),
                                row.getSourceRef(),
                                row.getSourceRevisionRef(),
                                row.getSalesOfferRef(),
                                requested));
        require(
                result != null
                        && result.status() == ChannelSourceValidationPort.Status.VALID
                        && row.getSourceRef().equals(result.canonicalRef())
                        && row.getSourceRevisionRef().equals(result.canonicalRevision())
                        && Objects.equals(row.getSalesOfferRef(), result.canonicalSalesOfferRef())
                        && Objects.equals(requested, result.canonicalContext())
                        && result.contentHash() != null
                        && result.contentHash().matches("[a-f0-9]{64}")
                        && result.validUntil() != null
                        && result.validUntil().isAfter(Instant.now()));
        text(result.ownerReceiptNo(), 128);
        return result;
    }

    CimResourceMappingRevision append(
            CimResourceMapping m,
            long sequence,
            String no,
            Owner owner,
            String ref,
            String sourceRev,
            String offer,
            String connectionRevision,
            String authorization,
            String reason,
            Validation sourceProof,
            ChannelResourceIdentityGateway.Proof externalProof,
            String actor) {
        return append(
                m,
                sequence,
                no,
                owner,
                ref,
                sourceRev,
                offer,
                sourceProof == null ? null : sourceProof.canonicalContext(),
                connectionRevision,
                authorization,
                reason,
                sourceProof,
                externalProof,
                actor);
    }

    CimResourceMappingRevision append(
            CimResourceMapping m,
            long sequence,
            String no,
            Owner owner,
            String ref,
            String sourceRev,
            String offer,
            SourceContext context,
            String connectionRevision,
            String authorization,
            String reason,
            Validation sourceProof,
            ChannelResourceIdentityGateway.Proof externalProof,
            String actor) {
        return append(
                m,
                sequence,
                no,
                owner,
                ref,
                sourceRev,
                offer,
                context,
                "IMPORTED",
                connectionRevision,
                authorization,
                reason,
                sourceProof,
                externalProof,
                actor);
    }

    CimResourceMappingRevision append(
            CimResourceMapping m,
            long sequence,
            String no,
            Owner owner,
            String ref,
            String sourceRev,
            String offer,
            SourceContext context,
            String origin,
            String connectionRevision,
            String authorization,
            String reason,
            Validation sourceProof,
            ChannelResourceIdentityGateway.Proof externalProof,
            String actor) {
        require("IMPORTED".equals(origin) || "MANAGED".equals(origin));
        var row = audit(new CimResourceMappingRevision(), actor);
        row.setRevisionNo(no);
        row.setEntityNo(m.getEntityNo());
        row.setMappingNo(m.getMappingNo());
        // Callers hold the mapping lock. Historical append-only revisions do not change its latest
        // working pointer.
        var last =
                revisions.selectOne(
                        Wrappers.<CimResourceMappingRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", m.getEntityNo())
                                .eq("mapping_no", m.getMappingNo())
                                .orderByDesc("revision")
                                .last("LIMIT 1"));
        row.setRevision(last == null ? 1 : Math.addExact(last.getRevision(), 1));
        row.setSourceOwner(owner.name());
        row.setSourceRef(ref);
        row.setSourceRevisionRef(sourceRev);
        row.setSalesOfferRef(offer);
        row.setMappingOrigin(origin);
        row.setValidationStatus(sourceProof == null ? "DRAFT" : "VERIFIED");
        row.setConnectionRevisionNo(connectionRevision);
        row.setAuthorizationNo(authorization);
        row.setReason(reason);
        row.setMappingSnapshot(
                db.json.writeValueAsString(
                        new Snapshot(
                                ObjectType.valueOf(m.getObjectType()),
                                identity(m),
                                owner,
                                ref,
                                sourceRev,
                                offer,
                                sourceProof,
                                externalProof,
                                context)));
        row.setContentHash(ChannelIdentity.hash(row.getMappingSnapshot()));
        if (sourceProof != null) {
            row.setSourceContentHash(sourceProof.contentHash());
            row.setValidationReceiptRef(sourceProof.ownerReceiptNo());
            row.setExternalEvidenceRef(externalProof.evidenceRef());
            row.setEffectiveAt(now());
            var until =
                    sourceProof.validUntil().isBefore(externalProof.validUntil())
                            ? sourceProof.validUntil()
                            : externalProof.validUntil();
            row.setValidationExpiresAt(LocalDateTime.ofInstant(until, ZoneOffset.UTC));
        }
        revisions.insert(row);
        return row;
    }

    /**
     * A committed publication receipt proves past identity; it is never represented as a fresh
     * platform identity query.
     */
    CimResourceMappingRevision appendHistorical(
            CimResourceMapping mapping,
            String revisionNo,
            CatalogResourceIndex.Resource resource,
            String connectionRevision,
            Validation proof,
            HistoricalEvidence evidence) {
        var row =
                append(
                        mapping,
                        0,
                        revisionNo,
                        resource.owner(),
                        resource.sourceRef(),
                        resource.revision(),
                        resource.salesOfferRef(),
                        resource.context(),
                        "MANAGED",
                        connectionRevision,
                        null,
                        "HISTORICAL_PUBLICATION:" + evidence.runNo(),
                        null,
                        null,
                        "SYSTEM");
        row.setMappingSnapshot(
                db.json.writeValueAsString(
                        new Snapshot(
                                resource.key().type(),
                                identity(mapping),
                                resource.owner(),
                                resource.sourceRef(),
                                resource.revision(),
                                resource.salesOfferRef(),
                                proof,
                                null,
                                resource.context(),
                                evidence)));
        row.setContentHash(ChannelIdentity.hash(row.getMappingSnapshot()));
        row.setValidationStatus("VERIFIED");
        row.setSourceContentHash(proof.contentHash());
        row.setValidationReceiptRef(proof.ownerReceiptNo());
        row.setExternalEvidenceRef(evidence.itemReceiptRef());
        row.setEffectiveAt(now());
        row.setValidationExpiresAt(LocalDateTime.ofInstant(proof.validUntil(), ZoneOffset.UTC));
        require(revisions.updateById(row) == 1);
        return row;
    }

    MappingRevisionVO view(CimResourceMappingRevision r) {
        return new MappingRevisionVO(
                r.getRevisionNo(),
                r.getRevision(),
                Owner.valueOf(r.getSourceOwner()),
                r.getSourceRef(),
                r.getSourceRevisionRef(),
                r.getSalesOfferRef(),
                r.getMappingOrigin(),
                r.getValidationStatus(),
                r.getContentHash(),
                r.getEffectiveAt() == null ? null : instant(r.getEffectiveAt()),
                context(r));
    }

    ResourceMappingVO view(CimResourceMapping m) {
        return new ResourceMappingVO(
                m.getMappingNo(),
                m.getStoreNo(),
                m.getConnectionNo(),
                ObjectType.valueOf(m.getObjectType()),
                identity(m),
                view(revision(m.getEntityNo(), m.getMappingNo(), m.getLatestRevisionNo())),
                m.getActiveRevisionNo() == null
                        ? null
                        : view(
                                revision(
                                        m.getEntityNo(),
                                        m.getMappingNo(),
                                        m.getActiveRevisionNo())),
                m.getVersion());
    }
}
