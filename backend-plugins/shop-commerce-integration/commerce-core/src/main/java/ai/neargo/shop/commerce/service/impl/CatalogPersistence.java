package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.CatalogContracts.CatalogScopeVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncPolicyVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncRunVO;
import ai.neargo.shop.commerce.entity.CimAccount;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimCatalogScope;
import ai.neargo.shop.commerce.entity.CimCatalogSnapshot;
import ai.neargo.shop.commerce.entity.CimCatalogSource;
import ai.neargo.shop.commerce.entity.CimConnectionRevision;
import ai.neargo.shop.commerce.entity.CimProductProfile;
import ai.neargo.shop.commerce.entity.CimStoreConnection;
import ai.neargo.shop.commerce.entity.CimSyncPolicy;
import ai.neargo.shop.commerce.entity.CimSyncRun;
import ai.neargo.shop.commerce.mapper.CommerceMappers.AccountMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.AuthorizationRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.CatalogScopeMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.CatalogSnapshotMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.CatalogSourceMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ConnectionRevisionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationAttemptMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OperationMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ProductProfileMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.StoreConnectionMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SyncPolicyMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SyncRunItemMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.SyncRunMapper;
import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.ValidatedSource;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.CommerceGateway;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** 仅本域持久化共享组件；原业务通过显式Port读取，不访问商品订单表。 */
public final class CatalogPersistence {
    final CatalogScopeMapper scopes;
    final CatalogSourceMapper sources;
    final SyncPolicyMapper policies;
    final CatalogSnapshotMapper snapshots;
    final SyncRunMapper runs;
    final SyncRunItemMapper items;
    final StoreConnectionMapper connections;
    final ConnectionRevisionMapper revisions;
    final AccountMapper accounts;
    final AuthorizationRevisionMapper authorizations;
    final ProductProfileMapper profiles;
    final OperationMapper operations;
    final OperationAttemptMapper attempts;
    final CommerceCommands commands;
    final ObjectMapper json = new ObjectMapper();
    final Supplier<ChannelSourceValidationPort> authority;
    final Map<Owner, ChannelCatalogSourcePort> owners = new HashMap<>();
    final Map<String, ChannelCatalogGateway> gateways = new HashMap<>();

    public CatalogPersistence(
            CatalogScopeMapper scopes,
            CatalogSourceMapper sources,
            SyncPolicyMapper policies,
            CatalogSnapshotMapper snapshots,
            SyncRunMapper runs,
            SyncRunItemMapper items,
            StoreConnectionMapper connections,
            ConnectionRevisionMapper revisions,
            AccountMapper accounts,
            AuthorizationRevisionMapper authorizations,
            ProductProfileMapper profiles,
            OperationMapper operations,
            OperationAttemptMapper attempts,
            CommerceCommands commands,
            Supplier<ChannelSourceValidationPort> authority,
            List<ChannelCatalogSourcePort> owners,
            List<ChannelCatalogGateway> gateways) {
        this.scopes = scopes;
        this.sources = sources;
        this.policies = policies;
        this.snapshots = snapshots;
        this.runs = runs;
        this.items = items;
        this.connections = connections;
        this.revisions = revisions;
        this.accounts = accounts;
        this.authorizations = authorizations;
        this.profiles = profiles;
        this.operations = operations;
        this.attempts = attempts;
        this.commands = commands;
        this.authority = authority;
        for (var port : owners)
            if (this.owners.put(port.owner(), port) != null)
                throw new IllegalStateException("Duplicate catalog source owner");
        for (var gateway : gateways)
            if (this.gateways.put(gateway.profileNo(), gateway) != null)
                throw new IllegalStateException("Duplicate catalog gateway profile");
    }

    static <T extends BaseEntity> T audit(T row, String actor) {
        row.setTenantNo("MAIN");
        row.setCreatedAt(now());
        row.setUpdatedAt(now());
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setVersion(0L);
        row.setDeleted(0);
        return row;
    }

    static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    static Instant instant(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC);
    }

    static void require(boolean ok) {
        CatalogDocumentPolicy.require(ok);
    }

    static void text(String s, int n) {
        CommerceValidation.text(s, n);
    }

    record Context(
            CimAccount account,
            CimStoreConnection connection,
            CimConnectionRevision revision,
            CimCatalogScope scope) {}

    Context connection(String entity, String store, String no, boolean lock) {
        return connection(entity, store, no, lock, true);
    }

    /** 本域历史只读可使用已改路由的连接；当前目录写入仍要求LOCAL。 */
    Context connection(String entity, String store, String no, boolean lock, boolean requireLocal) {
        text(entity, 32);
        text(store, 32);
        text(no, 32);
        var c =
                connections.selectOne(
                        Wrappers.<CimStoreConnection>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("store_no", store)
                                .eq("connection_no", no));
        if (c == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var a =
                accounts.selectOne(
                        Wrappers.<CimAccount>query()
                                .eq("tenant_no", "MAIN")
                                .eq("account_no", c.getAccountNo())
                                .eq("entity_no", entity)
                                .last(lock ? "FOR UPDATE" : ""));
        require(a != null);
        if (lock)
            c =
                    connections.selectOne(
                            Wrappers.<CimStoreConnection>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("connection_no", no)
                                    .eq("entity_no", entity)
                                    .eq("store_no", store)
                                    .last("FOR UPDATE"));
        require(c != null && a.getAccountNo().equals(c.getAccountNo()));
        var r =
                revisions.selectOne(
                        Wrappers.<CimConnectionRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("revision_no", c.getActiveRevisionNo())
                                .eq("entity_no", entity)
                                .eq("connection_no", no));
        require(r != null && (!requireLocal || "LOCAL".equals(r.getCatalogAuthority())));
        return new Context(a, c, r, null);
    }

    Context scope(String entity, String store, String no, boolean lock) {
        text(no, 32);
        var s =
                scopes.selectOne(
                        Wrappers.<CimCatalogScope>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("store_no", store)
                                .eq("scope_no", no));
        if (s == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var c = connection(entity, store, s.getConnectionNo(), lock);
        if (lock)
            s =
                    scopes.selectOne(
                            Wrappers.<CimCatalogScope>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("scope_no", no)
                                    .eq("entity_no", entity)
                                    .eq("store_no", store)
                                    .last("FOR UPDATE"));
        require(s != null && "ACTIVE".equals(s.getStatus()));
        return new Context(c.account(), c.connection(), c.revision(), s);
    }

    CimSyncRun run(String e, String store, String no, boolean lock) {
        text(no, 32);
        var r =
                runs.selectOne(
                        Wrappers.<CimSyncRun>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", e)
                                .eq("store_no", store)
                                .eq("run_no", no)
                                .last(lock ? "FOR UPDATE" : ""));
        if (r == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return r;
    }

    List<SourceReq> sourceRows(String no) {
        return sources
                .selectList(
                        Wrappers.<CimCatalogSource>query()
                                .eq("tenant_no", "MAIN")
                                .eq("scope_no", no)
                                .eq("status", "ACTIVE")
                                .orderByAsc("priority", "source_ref"))
                .stream()
                .map(
                        s ->
                                new SourceReq(
                                        Owner.valueOf(s.getSourceOwner()),
                                        s.getSourceRef(),
                                        s.getSelectionRevision(),
                                        s.getPriority()))
                .toList();
    }

    List<ValidatedSource> validate(Context c, List<SourceReq> req) {
        require(req != null && !req.isEmpty() && req.size() <= 50);
        var seen = new HashSet<String>();
        var result = new ArrayList<ValidatedSource>();
        var port = authority.get();
        require(port != null);
        Owner owner = Owner.valueOf(c.revision().getSourceOwner());
        for (var s : req) {
            require(
                    s != null
                            && s.sourceOwner() == owner
                            && s.priority() != null
                            && s.priority() >= 0
                            && s.priority() <= 10000);
            text(s.sourceRef(), 128);
            text(s.selectionRevision(), 128);
            require(seen.add(s.sourceRef()));
            ChannelSourceValidationPort.Validation v;
            try {
                v =
                        port.validate(
                                new ChannelSourceValidationPort.Source(
                                        c.connection().getEntityNo(),
                                        c.connection().getStoreNo(),
                                        owner,
                                        owner == Owner.FOOD
                                                ? ChannelSourceValidationPort.Kind.MENU
                                                : ChannelSourceValidationPort.Kind.SELECTION,
                                        s.sourceRef(),
                                        s.selectionRevision()));
            } catch (RuntimeException failure) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            require(
                    v != null
                            && v.status() == ChannelSourceValidationPort.Status.VALID
                            && s.sourceRef().equals(v.canonicalRef())
                            && s.selectionRevision().equals(v.canonicalRevision())
                            && v.contentHash() != null
                            && v.contentHash().matches("[a-f0-9]{64}")
                            && v.validUntil() != null
                            && v.validUntil().isAfter(Instant.now()));
            text(v.ownerReceiptNo(), 128);
            result.add(
                    new ValidatedSource(
                            owner, s.sourceRef(), s.selectionRevision(), s.priority(), v));
        }
        return List.copyOf(result);
    }

    void evidenceFresh(List<ValidatedSource> proofs) {
        for (var p : proofs) require(p.proof().validUntil().isAfter(Instant.now()));
    }

    void unchanged(Context before, Context after) {
        require(
                Objects.equals(
                        before.connection().getActiveRevisionNo(),
                        after.connection().getActiveRevisionNo()));
        if (before.scope() != null)
            require(Objects.equals(before.scope().getVersion(), after.scope().getVersion()));
    }

    CimAuthorizationRevision ready(Context c) {
        var grant = authorized(c);
        gateway(c);
        return grant;
    }

    /** 授权事实与具体能力实现分别核验，资源只读查询不依赖目录发布。 */
    CimAuthorizationRevision authorized(Context c) {
        require(
                "ACTIVE".equals(c.connection().getStatus())
                        && "ACTIVE".equals(c.account().getStatus()));
        var p =
                profiles.selectOne(
                        Wrappers.<CimProductProfile>query()
                                .eq("tenant_no", "MAIN")
                                .eq("profile_no", c.connection().getProfileNo()));
        require(p != null && "VERIFIED".equals(p.getStatus()));
        var a =
                authorizations.selectOne(
                        Wrappers.<CimAuthorizationRevision>query()
                                .eq("tenant_no", "MAIN")
                                .eq("authorization_no", c.account().getActiveAuthorizationNo())
                                .eq("account_no", c.account().getAccountNo())
                                .eq("entity_no", c.account().getEntityNo()));
        require(
                a != null
                        && "GRANTED".equals(a.getStatus())
                        && a.getExpiresAt() != null
                        && a.getExpiresAt().isAfter(now())
                        && a.getCredentialRef() != null);
        require(json.readTree(a.getGrantedScopes()).isArray());
        boolean granted = false;
        for (var n : json.readTree(a.getGrantedScopes()))
            if (n.isString() && "CATALOG".equals(n.asString())) granted = true;
        require(granted);
        return a;
    }

    ChannelCatalogGateway gateway(Context c) {
        var g = gateways.get(c.connection().getProfileNo());
        require(g != null && g.capabilities().contains(CommerceGateway.Capability.CATALOG));
        return g;
    }

    void writeSources(String entity, String scope, List<SourceReq> req) {
        sources.update(
                Wrappers.<CimCatalogSource>update()
                        .eq("tenant_no", "MAIN")
                        .eq("scope_no", scope)
                        .set("status", "ARCHIVED"));
        for (var s : req) {
            var row =
                    sources.selectOne(
                            Wrappers.<CimCatalogSource>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("scope_no", scope)
                                    .eq("source_owner", s.sourceOwner().name())
                                    .eq("source_ref", s.sourceRef()));
            if (row == null) {
                row = audit(new CimCatalogSource(), "SYSTEM");
                row.setSourceNo(BizKey.next("CICS"));
                row.setEntityNo(entity);
                row.setScopeNo(scope);
                row.setSourceOwner(s.sourceOwner().name());
                row.setSourceRef(s.sourceRef());
                row.setSelectionRevision(s.selectionRevision());
                row.setPriority(s.priority());
                row.setStatus("ACTIVE");
                sources.insert(row);
            } else
                sources.update(
                        Wrappers.<CimCatalogSource>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", row.getId())
                                .set("selection_revision", s.selectionRevision())
                                .set("priority", s.priority())
                                .set("status", "ACTIVE"));
        }
    }

    CimSyncPolicy policy(String scope) {
        var p =
                policies.selectOne(
                        Wrappers.<CimSyncPolicy>query()
                                .eq("tenant_no", "MAIN")
                                .eq("scope_no", scope));
        require(p != null);
        return p;
    }

    CatalogScopeVO view(CimCatalogScope s) {
        return new CatalogScopeVO(
                s.getScopeNo(),
                s.getStoreNo(),
                s.getConnectionNo(),
                s.getName(),
                sourceRows(s.getScopeNo()),
                s.getDesiredSnapshotNo(),
                s.getLastConfirmedSnapshotNo(),
                s.getRemoteConsistency(),
                s.getGeneration(),
                s.getActiveOperationNo(),
                s.getVersion());
    }

    SyncPolicyVO view(CimSyncPolicy p) {
        return new SyncPolicyVO(
                p.getPolicyNo(),
                p.getScopeNo(),
                p.getEnabled(),
                p.getOnPublish(),
                p.getOnAvailability(),
                p.getScheduledCheck(),
                p.getDebounceSeconds(),
                p.getRecoveryProfile(),
                p.getVersion());
    }

    SyncRunVO view(CimSyncRun r) {
        var s =
                scopes.selectOne(
                        Wrappers.<CimCatalogScope>query()
                                .eq("tenant_no", "MAIN")
                                .eq("scope_no", r.getScopeNo()));
        return new SyncRunVO(
                r.getRunNo(),
                r.getScopeNo(),
                r.getConnectionNo(),
                r.getSnapshotNo(),
                r.getRunType(),
                r.getTriggerType(),
                r.getStatus(),
                r.getGeneration(),
                r.getOperationNo(),
                s.getDesiredSnapshotNo(),
                s.getLastConfirmedSnapshotNo(),
                s.getRemoteConsistency(),
                r.getFailureCode(),
                r.getVersion());
    }

    CimCatalogSnapshot snapshot(String entity, String scope, String no) {
        text(no, 32);
        var s =
                snapshots.selectOne(
                        Wrappers.<CimCatalogSnapshot>query()
                                .eq("tenant_no", "MAIN")
                                .eq("entity_no", entity)
                                .eq("scope_no", scope)
                                .eq("snapshot_no", no));
        if (s == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return s;
    }

    Document document(CimCatalogSnapshot s) {
        return json.readValue(s.getPayloadJson(), Document.class);
    }

    void validSnapshot(Context c, CimCatalogSnapshot s) {
        require(
                s.getExpiresAt().isAfter(now())
                        && s.getScopeVersion().equals(c.scope().getVersion())
                        && s.getConnectionRevisionNo()
                                .equals(c.connection().getActiveRevisionNo()));
    }

    void revalidate(Context c, CimCatalogSnapshot s) {
        validSnapshot(c, s);
        var old = document(s).sources();
        var fresh =
                validate(
                        c,
                        old.stream()
                                .map(
                                        v ->
                                                new SourceReq(
                                                        v.owner(),
                                                        v.sourceRef(),
                                                        v.selectionRevision(),
                                                        v.priority()))
                                .toList());
        for (int i = 0; i < old.size(); i++)
            require(old.get(i).proof().contentHash().equals(fresh.get(i).proof().contentHash()));
    }
}
