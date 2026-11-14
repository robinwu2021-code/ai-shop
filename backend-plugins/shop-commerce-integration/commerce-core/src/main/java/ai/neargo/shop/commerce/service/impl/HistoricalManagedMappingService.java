package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.entity.CimCatalogSnapshot;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimOrderLink;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.entity.CimSyncRun;
import ai.neargo.shop.commerce.entity.CimSyncRunItem;
import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.CatalogResourceIndex;
import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Parent;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort.Reference;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.SourceContext;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * Published historical identity is proved by its original terminal receipt, never by the current
 * mapping pointer.
 */
public final class HistoricalManagedMappingService {
    private final MappingPersistence data;
    private final Clock clock;

    public HistoricalManagedMappingService(MappingPersistence data, Clock clock) {
        this.data = data;
        this.clock = clock;
    }

    /**
     * A per-order resolution session shares one immutable catalog receipt graph; it has no
     * cross-request cache.
     */
    public Session session(CimOrderLink order, Document document) {
        return new Session(order, document);
    }

    /**
     * Full historical publication is accompanied by its actual terminal supplier operation and
     * receipt.
     */
    public ChannelOrderSourceEvidencePort.PublishedCatalog publishedCatalog(
            CimOrderLink order, String hash, ChannelOrderSourceEvidencePort.Query query) {
        return data.db.commands.query(
                () -> {
                    require(
                            query != null
                                    && order.getEntityNo().equals(query.entityNo())
                                    && order.getStoreNo().equals(query.storeNo())
                                    && order.getImportRef().equals(query.importNo())
                                    && order.getSourceOwner().equals(query.owner().name()));
                    var document = data.db.publishedCatalog(order, hash).document();
                    var original = origin(order, document);
                    return new ChannelOrderSourceEvidencePort.PublishedCatalog(
                            query,
                            hash,
                            document,
                            original.snapshot().getSnapshotNo(),
                            original.run().getRunNo(),
                            original.operation().getOperationNo(),
                            original.attempt().getAttemptRef(),
                            original.attempt().getResponseRef());
                });
    }

    private record Origin(
            CimCatalogSnapshot snapshot,
            CimSyncRun run,
            CimOperation operation,
            CimOperationAttempt attempt,
            List<Member> members) {}

    private record Member(
            CatalogResourceIndex.Resource resource, Identity identity, CimSyncRunItem receipt) {}

    private record Request(String catalogHash, String runNo, ObjectType type, Identity identity) {}

    public final class Session {
        private final CimOrderLink order;
        private final Document document;
        private Origin origin;

        private Session(CimOrderLink order, Document document) {
            this.order = order;
            this.document = document;
        }

        private Member member(ObjectType type, Identity identity) {
            if (origin == null) origin = data.db.commands.query(() -> origin(order, document));
            var matches =
                    origin.members().stream()
                            .filter(
                                    member ->
                                            member.resource().key().type() == type
                                                    && member.identity().equals(identity))
                            .toList();
            require(matches.size() == 1);
            return matches.getFirst();
        }

        /**
         * The original published parent graph selects the source; a manually active row cannot
         * redefine that past graph.
         */
        public CatalogResourceIndex.Resource resource(ObjectType type, Identity identity) {
            return member(type, identity).resource();
        }

        /**
         * Missing current mappings can be appended as historical evidence without changing
         * active/latest working pointers.
         */
        public Reference resolve(ObjectType type, Identity identity) {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException(
                        "Historical owner verification requires an external transaction boundary");
            var member = member(type, identity);
            var resource = member.resource();
            var request =
                    new Request(
                            origin.snapshot().getContentHash(),
                            origin.run().getRunNo(),
                            type,
                            identity);
            String key = ChannelIdentity.hash(data.db.json.writeValueAsString(request));
            var replay =
                    data.db.commands.replay(
                            order.getEntityNo(),
                            order.getStoreNo(),
                            "SYSTEM",
                            "MANAGED_HISTORICAL_MAPPING",
                            key,
                            request,
                            Reference.class);
            if (replay.isPresent()) return replay.get();
            var context = resource.context();
            var historical =
                    new SourceContext(
                            context == null ? resource.sourceRef() : context.menuRef(),
                            context == null ? null : context.optionGroupRef(),
                            ReadMode.HISTORICAL,
                            context == null ? null : context.variantSkuRef(),
                            context == null ? null : context.bundleGroupRef());
            var query =
                    new Source(
                            order.getEntityNo(),
                            order.getStoreNo(),
                            resource.owner(),
                            Kind.valueOf(type.name()),
                            resource.sourceRef(),
                            resource.revision(),
                            resource.salesOfferRef(),
                            historical);
            var authority = data.db.authority.get();
            require(authority != null);
            var proof = authority.validate(query);
            CatalogSourceTransportPolicy.validateProof(query, proof, clock);
            require(proof.status() == Status.VALID);
            var originalSource =
                    document.sources().stream()
                            .filter(
                                    source ->
                                            source.owner() == resource.owner()
                                                    && source.sourceRef()
                                                            .equals(historical.menuRef())
                                                    && source.selectionRevision()
                                                            .equals(resource.revision()))
                            .toList();
            require(
                    originalSource.size() == 1
                            && originalSource
                                    .getFirst()
                                    .proof()
                                    .contentHash()
                                    .equals(proof.contentHash()));
            return data.db.commands.execute(
                    order.getEntityNo(),
                    order.getStoreNo(),
                    "SYSTEM",
                    "MANAGED_HISTORICAL_MAPPING",
                    key,
                    request,
                    Reference.class,
                    () -> persist(order, origin, member, proof),
                    Reference::revisionNo);
        }
    }

    private Origin origin(CimOrderLink order, Document document) {
        String hash = CatalogDocumentPolicy.hash(document, data.db.json);
        var snapshot = data.db.publishedCatalog(order, hash).snapshot();
        require(hash.equals(CatalogDocumentPolicy.hash(data.db.document(snapshot), data.db.json)));
        var run =
                data.db.runs.selectOne(
                        Wrappers.<CimSyncRun>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("store_no", order.getStoreNo())
                                .eq("connection_no", order.getConnectionNo())
                                .eq("snapshot_no", snapshot.getSnapshotNo())
                                .in("status", "SUCCEEDED", "PARTIAL")
                                .orderByDesc("id")
                                .last("LIMIT 1"));
        require(run != null && run.getRequestHash().equals(hash));
        var op =
                data.db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("store_no", order.getStoreNo())
                                .eq("connection_no", order.getConnectionNo())
                                .eq("operation_no", run.getOperationNo()));
        require(
                op != null
                        && "CATALOG_REPLACE".equals(op.getOperationType())
                        && op.getStatus().equals(run.getStatus())
                        && op.getOriginalEvidenceRef() != null);
        var attempt =
                data.db.attempts.selectOne(
                        Wrappers.<CimOperationAttempt>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", op.getCurrentAttemptNo()));
        require(
                attempt != null
                        && attempt.getFinishedAt() != null
                        && attempt.getOutcome().equals(run.getStatus())
                        && Objects.equals(attempt.getResponseRef(), op.getOriginalEvidenceRef()));
        var items =
                data.db.items.selectList(
                        Wrappers.<CimSyncRunItem>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("run_no", run.getRunNo())
                                .eq("attempt_no", op.getCurrentAttemptNo())
                                .eq("result", "SUCCEEDED"));
        var members = new ArrayList<Member>();
        var parents = new HashMap<CatalogResourceIndex.Key, Identity>();
        for (var resource : CatalogResourceIndex.resources(document)) {
            var matching =
                    items.stream()
                            .filter(
                                    item ->
                                            resource.key()
                                                            .type()
                                                            .name()
                                                            .equals(item.getObjectType())
                                                    && ChannelIdentity.hash(resource.key().value())
                                                            .equals(item.getObjectKeyHash())
                                                    && resource.sourceRef()
                                                            .equals(item.getSourceRef())
                                                    && item.getExternalRef() != null
                                                    && item.getEvidenceRef() != null)
                            .toList();
            require(matching.size() <= 1);
            if (matching.isEmpty()
                    || resource.parents().stream().anyMatch(parent -> !parents.containsKey(parent)))
                continue;
            var receipt = matching.getFirst();
            var identity =
                    new Identity(
                            receipt.getExternalRef(),
                            resource.parents().stream()
                                    .map(
                                            parent ->
                                                    new Parent(
                                                            parent.type(),
                                                            parents.get(parent).externalRef()))
                                    .toList());
            data.identity(resource.key().type(), identity);
            parents.put(resource.key(), identity);
            members.add(new Member(resource, identity, receipt));
        }
        require(!members.isEmpty());
        if ("SUCCEEDED".equals(run.getStatus()))
            require(members.size() == CatalogResourceIndex.resources(document).size());
        return new Origin(snapshot, run, op, attempt, List.copyOf(members));
    }

    private Reference persist(CimOrderLink order, Origin origin, Member member, Validation proof) {
        var c =
                data.db.connection(
                        order.getEntityNo(),
                        order.getStoreNo(),
                        order.getConnectionNo(),
                        true,
                        false);
        require(proof.validUntil().isAfter(clock.instant()));
        var original =
                data.db.runs.selectOne(
                        Wrappers.<CimSyncRun>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("run_no", origin.run().getRunNo()));
        require(
                original != null
                        && original.getStatus().equals(origin.run().getStatus())
                        && original.getOperationNo().equals(origin.operation().getOperationNo()));
        var resource = member.resource();
        String identityHash = data.hash(c, resource.key().type(), member.identity());
        var mapping =
                data.mappings.selectOne(
                        Wrappers.<CimResourceMapping>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", order.getEntityNo())
                                .eq("store_no", order.getStoreNo())
                                .eq("connection_no", order.getConnectionNo())
                                .eq("object_type", resource.key().type().name())
                                .eq("external_identity_hash", identityHash)
                                .last("FOR UPDATE"));
        String revisionNo = BizKey.next("CIMR");
        boolean created = mapping == null;
        if (created) {
            mapping = audit(new CimResourceMapping(), "SYSTEM");
            mapping.setMappingNo(BizKey.next("CIMP"));
            mapping.setEntityNo(order.getEntityNo());
            mapping.setStoreNo(order.getStoreNo());
            mapping.setConnectionNo(order.getConnectionNo());
            mapping.setObjectType(resource.key().type().name());
            mapping.setExternalRef(member.identity().externalRef());
            mapping.setExternalIdentityHash(identityHash);
            mapping.setExternalIdentityJson(data.db.json.writeValueAsString(member.identity()));
            mapping.setLatestRevisionNo(revisionNo);
            data.mappings.insert(mapping);
        } else require(data.identity(mapping).equals(member.identity()));
        var evidence =
                new MappingPersistence.HistoricalEvidence(
                        "TERMINAL_PUBLICATION",
                        origin.snapshot().getContentHash(),
                        origin.snapshot().getSnapshotNo(),
                        origin.run().getRunNo(),
                        origin.operation().getOperationNo(),
                        origin.attempt().getAttemptNo(),
                        origin.attempt().getResponseRef(),
                        member.receipt().getEvidenceRef(),
                        member.receipt().getItemNo());
        var revision =
                data.appendHistorical(
                        mapping,
                        revisionNo,
                        resource,
                        origin.snapshot().getConnectionRevisionNo(),
                        proof,
                        evidence);
        require(proof.validUntil().isAfter(clock.instant()));
        return new Reference(
                order.getEntityNo(),
                order.getStoreNo(),
                order.getConnectionNo(),
                mapping.getMappingNo(),
                revision.getRevisionNo());
    }
}
