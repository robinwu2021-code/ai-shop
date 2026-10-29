package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.entity.CimSyncRun;
import ai.neargo.shop.commerce.entity.CimSyncRunItem;
import ai.neargo.shop.commerce.policy.CatalogResourceIndex;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelRecoveryPort;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Parent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Only persisted terminal publication receipts may create MANAGED drafts. Public input has no
 * access to this path.
 */
public final class ManagedMappingProjectionService implements ChannelRecoveryPort {
    private final MappingPersistence data;

    public ManagedMappingProjectionService(MappingPersistence data) {
        this.data = data;
    }

    @Override
    public String workflow() {
        return "MANAGED_MAPPING_PROJECTION";
    }

    /**
     * The existing sole recovery runner retries missing identities without repeating publication.
     */
    @Override
    public int recoverDue(int limit) {
        require(limit >= 1 && limit <= 100);
        var due =
                data.db.commands.query(
                        () ->
                                data.db.runs.selectList(
                                        Wrappers.<CimSyncRun>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("mapping_projection_status", "PENDING")
                                                .in("status", "SUCCEEDED", "PARTIAL")
                                                .orderByAsc("id")
                                                .last("LIMIT " + limit)));
        int count = 0;
        for (var candidate : due) if (data.db.commands.atomic(() -> project(candidate))) count++;
        return count;
    }

    private boolean project(CimSyncRun candidate) {
        var c =
                data.db.scope(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getScopeNo(),
                        true);
        var run =
                data.db.run(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getRunNo(),
                        true);
        if (!"PENDING".equals(run.getMappingProjectionStatus())
                || !List.of("SUCCEEDED", "PARTIAL").contains(run.getStatus())) return false;
        var snapshot = data.db.snapshot(run.getEntityNo(), run.getScopeNo(), run.getSnapshotNo());
        if (!Objects.equals(c.scope().getGeneration(), run.getGeneration())
                || !Objects.equals(
                        c.connection().getActiveRevisionNo(), snapshot.getConnectionRevisionNo()))
            return mark(run, "SUPERSEDED");
        var document = data.db.json.readValue(snapshot.getPayloadJson(), Document.class);
        List<CatalogResourceIndex.Resource> resources;
        try {
            resources = CatalogResourceIndex.resources(document);
        } catch (IllegalArgumentException unsupported) {
            return mark(run, "UNSUPPORTED");
        }
        var operation =
                data.db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", run.getEntityNo())
                                .eq("operation_no", run.getOperationNo()));
        require(operation != null && operation.getStatus().equals(run.getStatus()));
        var results =
                data.db.items.selectList(
                        Wrappers.<CimSyncRunItem>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", run.getEntityNo())
                                .eq("run_no", run.getRunNo())
                                .eq("attempt_no", operation.getCurrentAttemptNo())
                                .eq("result", "SUCCEEDED"));
        var successful = new HashMap<CatalogResourceIndex.Key, CimSyncRunItem>();
        for (var resource : resources) {
            var matches =
                    results.stream()
                            .filter(
                                    r ->
                                            resource.key().type().name().equals(r.getObjectType())
                                                    && ChannelIdentity.hash(resource.key().value())
                                                            .equals(r.getObjectKeyHash())
                                                    && resource.sourceRef().equals(r.getSourceRef())
                                                    && r.getExternalRef() != null
                                                    && r.getEvidenceRef() != null)
                            .toList();
            require(matches.size() <= 1);
            if (!matches.isEmpty()) successful.put(resource.key(), matches.getFirst());
        }
        if ("SUCCEEDED".equals(run.getStatus()) && successful.size() != resources.size())
            return false;
        var identities = new HashMap<CatalogResourceIndex.Key, Identity>();
        boolean manualConflict = false;
        for (var resource : resources) {
            var result = successful.get(resource.key());
            if (result == null || !identities.keySet().containsAll(resource.parents())) continue;
            var parents =
                    resource.parents().stream()
                            .map(key -> new Parent(key.type(), identities.get(key).externalRef()))
                            .toList();
            var identity = new Identity(result.getExternalRef(), parents);
            identities.put(resource.key(), identity);
            data.identity(resource.key().type(), identity);
            String hash = data.hash(c, resource.key().type(), identity);
            var mapping =
                    data.mappings.selectOne(
                            Wrappers.<CimResourceMapping>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", run.getEntityNo())
                                    .eq("connection_no", run.getConnectionNo())
                                    .eq("object_type", resource.key().type().name())
                                    .eq("external_identity_hash", hash)
                                    .last("FOR UPDATE"));
            long sequence = 1;
            if (mapping == null) {
                mapping = audit(new CimResourceMapping(), "SYSTEM");
                mapping.setMappingNo(BizKey.next("CIMP"));
                mapping.setEntityNo(run.getEntityNo());
                mapping.setStoreNo(run.getStoreNo());
                mapping.setConnectionNo(run.getConnectionNo());
                mapping.setObjectType(resource.key().type().name());
                mapping.setExternalRef(identity.externalRef());
                mapping.setExternalIdentityHash(hash);
                mapping.setExternalIdentityJson(data.db.json.writeValueAsString(identity));
                mapping.setLatestRevisionNo(BizKey.next("CIMR"));
                mapping.setNextVerificationAt(now());
                data.mappings.insert(mapping);
            } else {
                var latest =
                        data.revision(
                                run.getEntityNo(),
                                mapping.getMappingNo(),
                                mapping.getLatestRevisionNo());
                if (!"MANAGED".equals(latest.getMappingOrigin())
                        || mapping.getVerificationOperationNo() != null) {
                    manualConflict = true;
                    continue;
                }
                if (Objects.equals(latest.getSourceRef(), resource.sourceRef())
                        && Objects.equals(latest.getSourceRevisionRef(), resource.revision())
                        && Objects.equals(latest.getSalesOfferRef(), resource.salesOfferRef())
                        && Objects.equals(data.context(latest), resource.context())
                        && ("DRAFT".equals(latest.getValidationStatus())
                                || Objects.equals(
                                        mapping.getActiveRevisionNo(), latest.getRevisionNo())))
                    continue;
                sequence = Math.addExact(latest.getRevision(), 1);
                mapping.setLatestRevisionNo(BizKey.next("CIMR"));
                require(
                        data.mappings.update(
                                        Wrappers.<CimResourceMapping>update()
                                                .eq("tenant_no", "MAIN")
                                                .eq("id", mapping.getId())
                                                .eq("version", mapping.getVersion())
                                                .set(
                                                        "latest_revision_no",
                                                        mapping.getLatestRevisionNo())
                                                .set("active_revision_no", null)
                                                .set("next_verification_at", now())
                                                .set("version", mapping.getVersion() + 1))
                                == 1);
            }
            data.append(
                    mapping,
                    sequence,
                    mapping.getLatestRevisionNo(),
                    resource.owner(),
                    resource.sourceRef(),
                    resource.revision(),
                    resource.salesOfferRef(),
                    resource.context(),
                    "MANAGED",
                    snapshot.getConnectionRevisionNo(),
                    null,
                    "TERMINAL_CATALOG:" + run.getRunNo(),
                    null,
                    null,
                    "SYSTEM");
        }
        if (document.replacement() != null && "SUCCEEDED".equals(run.getStatus())) {
            require(
                    "STORE".equals(c.scope().getRemoteScopeKey())
                            && operation.getOriginalEvidenceRef() != null
                            && !operation.getOriginalEvidenceRef().isBlank()
                            && operation.getCurrentAttemptNo() != null
                            && operation.getCurrentAttemptNo() > 0);
            var receipt =
                    data.db.attempts.selectOne(
                            Wrappers.<CimOperationAttempt>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", run.getEntityNo())
                                    .eq("operation_no", operation.getOperationNo())
                                    .eq("attempt_no", operation.getCurrentAttemptNo())
                                    .eq("outcome", "SUCCEEDED")
                                    .eq("response_ref", operation.getOriginalEvidenceRef()));
            require(receipt != null && receipt.getFinishedAt() != null);
            var retained = new HashSet<String>();
            for (var entry : identities.entrySet())
                retained.add(
                        entry.getKey().type().name()
                                + ":"
                                + data.hash(c, entry.getKey().type(), entry.getValue()));
            var active =
                    data.mappings.selectList(
                            Wrappers.<CimResourceMapping>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", run.getEntityNo())
                                    .eq("store_no", run.getStoreNo())
                                    .eq("connection_no", run.getConnectionNo())
                                    .isNotNull("active_revision_no")
                                    .last("LIMIT 10001 FOR UPDATE"));
            require(active.size() <= 10000);
            for (var prior : active)
                if (!retained.contains(
                        prior.getObjectType() + ":" + prior.getExternalIdentityHash())) {
                    var original =
                            data.revision(
                                    run.getEntityNo(),
                                    prior.getMappingNo(),
                                    prior.getActiveRevisionNo());
                    if (!"MANAGED".equals(original.getMappingOrigin())) manualConflict = true;
                    // Only the current pointer is retired after a real terminal whole-scope
                    // replacement receipt.
                    // Immutable source/mapping revisions remain available to historical orders and
                    // refunds.
                    require(
                            data.mappings.update(
                                            Wrappers.<CimResourceMapping>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", prior.getId())
                                                    .eq("version", prior.getVersion())
                                                    .set("active_revision_no", null)
                                                    .set("verification_operation_no", null)
                                                    .set("verification_lease_until", null)
                                                    .set("next_verification_at", null)
                                                    .setSql("version=version+1"))
                                    == 1);
                }
        }
        return mark(run, manualConflict ? "MAPPING_CONFLICT" : "DRAFTED");
    }

    private boolean mark(CimSyncRun run, String status) {
        return data.db.runs.update(
                        Wrappers.<CimSyncRun>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", run.getId())
                                .eq("mapping_projection_status", "PENDING")
                                .set("mapping_projection_status", status)
                                .set("version", run.getVersion() + 1))
                == 1;
    }
}
