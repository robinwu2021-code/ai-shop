package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.dto.MappingContracts.CreateResourceMappingReq;
import ai.neargo.shop.commerce.dto.MappingContracts.MappingRevisionVO;
import ai.neargo.shop.commerce.dto.MappingContracts.ResourceMappingVO;
import ai.neargo.shop.commerce.dto.MappingContracts.ReviseResourceMappingReq;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.entity.CimResourceMappingRevision;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ResourceMappingService;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/** 公共写入只保存IMPORTED草稿；失效当前指针但保留历史，不让旧核验覆盖新意图。 */
public final class ResourceMappingServiceImpl implements ResourceMappingService {
    private final MappingPersistence data;

    public ResourceMappingServiceImpl(MappingPersistence data) {
        this.data = data;
    }

    public ResourceMappingVO createMapping(
            String e, String store, String actor, CreateResourceMappingReq req, String key) {
        require(req != null);
        data.identity(req.objectType(), req.externalIdentity());
        return data.db.commands.execute(
                e,
                store,
                actor,
                "MAPPING_CREATE",
                key,
                req,
                ResourceMappingVO.class,
                () -> {
                    var c = data.db.connection(e, store, req.connectionNo(), true);
                    data.source(
                            c,
                            req.objectType(),
                            req.sourceOwner(),
                            req.sourceRef(),
                            req.sourceRevision(),
                            req.salesOfferRef(),
                            req.sourceContext());
                    var hash = data.hash(c, req.objectType(), req.externalIdentity());
                    require(
                            data.mappings.selectCount(
                                            Wrappers.<CimResourceMapping>query()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("connection_no", req.connectionNo())
                                                    .eq("object_type", req.objectType().name())
                                                    .eq("external_identity_hash", hash))
                                    == 0);
                    var m = audit(new CimResourceMapping(), actor);
                    m.setMappingNo(BizKey.next("CIMP"));
                    m.setEntityNo(e);
                    m.setStoreNo(store);
                    m.setConnectionNo(req.connectionNo());
                    m.setObjectType(req.objectType().name());
                    m.setExternalRef(req.externalIdentity().externalRef());
                    m.setExternalIdentityHash(hash);
                    m.setExternalIdentityJson(
                            data.db.json.writeValueAsString(req.externalIdentity()));
                    m.setLatestRevisionNo(BizKey.next("CIMR"));
                    m.setNextVerificationAt(now());
                    data.mappings.insert(m);
                    data.append(
                            m,
                            1,
                            m.getLatestRevisionNo(),
                            req.sourceOwner(),
                            req.sourceRef(),
                            req.sourceRevision(),
                            req.salesOfferRef(),
                            req.sourceContext(),
                            c.connection().getActiveRevisionNo(),
                            null,
                            "MANUAL_CREATE",
                            null,
                            null,
                            actor);
                    return data.view(m);
                },
                ResourceMappingVO::mappingNo);
    }

    public ResourceMappingVO reviseMapping(
            String e,
            String store,
            String no,
            String actor,
            ReviseResourceMappingReq req,
            String key) {
        require(req != null && req.version() != null);
        text(req.reason(), 256);
        return data.db.commands.execute(
                e,
                store,
                actor,
                "MAPPING_REVISE:" + no,
                key,
                req,
                ResourceMappingVO.class,
                () -> {
                    var c = data.scoped(e, store, no, true);
                    require(req.version().equals(c.mapping().getVersion()));
                    data.source(
                            c.base(),
                            ObjectType.valueOf(c.mapping().getObjectType()),
                            req.sourceOwner(),
                            req.sourceRef(),
                            req.sourceRevision(),
                            req.salesOfferRef(),
                            req.sourceContext());
                    // Proof reads may still be in flight; terminate their local authority before
                    // replacing the draft.
                    if (c.mapping().getVerificationOperationNo() != null) {
                        var original =
                                data.db.operations.selectOne(
                                        Wrappers.<CimOperation>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq(
                                                        "operation_no",
                                                        c.mapping().getVerificationOperationNo())
                                                .last("FOR UPDATE"));
                        require(original != null);
                        data.db.operations.update(
                                Wrappers.<CimOperation>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", original.getId())
                                        .eq("status", "RUNNING")
                                        .set("status", "SUPERSEDED")
                                        .set("lease_until", null));
                        data.db.attempts.update(
                                Wrappers.<CimOperationAttempt>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("operation_no", original.getOperationNo())
                                        .isNull("finished_at")
                                        .set("finished_at", now())
                                        .set("outcome", "UNKNOWN")
                                        .set("error_code", "MAPPING_REVISED"));
                    }
                    var revision =
                            data.append(
                                    c.mapping(),
                                    Math.addExact(c.latest().getRevision(), 1),
                                    BizKey.next("CIMR"),
                                    req.sourceOwner(),
                                    req.sourceRef(),
                                    req.sourceRevision(),
                                    req.salesOfferRef(),
                                    req.sourceContext(),
                                    c.base().connection().getActiveRevisionNo(),
                                    null,
                                    req.reason(),
                                    null,
                                    null,
                                    actor);
                    require(
                            data.mappings.update(
                                            Wrappers.<CimResourceMapping>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", c.mapping().getId())
                                                    .eq("version", req.version())
                                                    .set("version", req.version() + 1)
                                                    .set("active_revision_no", null)
                                                    .set(
                                                            "latest_revision_no",
                                                            revision.getRevisionNo())
                                                    .set("verification_operation_no", null)
                                                    .set("verification_lease_until", null)
                                                    .set("next_verification_at", now())
                                                    .set("updated_by", actor)
                                                    .set("updated_at", now()))
                                    == 1);
                    return data.view(data.scoped(e, store, no, false).mapping());
                },
                ResourceMappingVO::mappingNo);
    }

    public PageData<ResourceMappingVO> listMappings(
            String e,
            String store,
            int page,
            int size,
            String connection,
            ObjectType type,
            String external,
            String source) {
        text(e, 32);
        text(store, 32);
        long offset = CommerceValidation.offset(page, size);
        if (connection != null) text(connection, 32);
        if (external != null) text(external, 128);
        if (source != null) text(source, 128);
        return data.db.commands.query(
                () -> {
                    var q =
                            Wrappers.<CimResourceMapping>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", e)
                                    .eq("store_no", store)
                                    .eq(connection != null, "connection_no", connection)
                                    .eq(
                                            type != null,
                                            "object_type",
                                            type == null ? null : type.name())
                                    .eq(external != null, "external_ref", external);
                    if (source != null)
                        q.exists(
                                "SELECT 1 FROM cim_resource_mapping_revision r WHERE"
                                    + " r.tenant_no=cim_resource_mapping.tenant_no AND"
                                    + " r.entity_no=cim_resource_mapping.entity_no AND"
                                    + " r.mapping_no=cim_resource_mapping.mapping_no AND"
                                    + " r.revision_no=cim_resource_mapping.latest_revision_no AND"
                                    + " r.deleted=0 AND r.source_ref={0}",
                                source);
                    long count = data.mappings.selectCount(q);
                    return PageData.of(
                            data
                                    .mappings
                                    .selectList(
                                            q.orderByAsc("id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(data::view)
                                    .toList(),
                            count,
                            page,
                            size);
                });
    }

    public PageData<MappingRevisionVO> listRevisions(
            String e, String store, String no, int page, int size) {
        long offset = CommerceValidation.offset(page, size);
        return data.db.commands.query(
                () -> {
                    data.scoped(e, store, no, false);
                    var q =
                            Wrappers.<CimResourceMappingRevision>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", e)
                                    .eq("mapping_no", no);
                    long count = data.revisions.selectCount(q);
                    return PageData.of(
                            data
                                    .revisions
                                    .selectList(
                                            q.orderByDesc("revision")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(data::view)
                                    .toList(),
                            count,
                            page,
                            size);
                });
    }
}
