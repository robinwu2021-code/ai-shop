package ai.neargo.shop.paybridge.impl;
import ai.neargo.shop.promotion.migration.LegacyCouponOriginRow;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.MAX_PAYLOAD_BYTES;
import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import org.springframework.beans.factory.ObjectProvider;


import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.entity.MktUserCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtCouponScope;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.paybridge.mapper.LegacyCouponFenceMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Snapshot;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Source barrier and command audit are one core transaction. No remote operation runs under this lock. */
@Service
public class LegacyCouponSourceFenceServiceImpl implements LegacyCouponSourceFenceService {
    private static final int MAX_INSTANCES = 5000;
    private static final int MAX_HISTORY = 100000;
    private final LegacyCouponFenceMapper locks;
    private final CouponMappers.CouponMapper legacyTemplates;
    private final CouponMappers.UserCouponMapper legacyInstances;
    private final PromotionMappers.CouponMapper promoTemplates;
    private final PromotionMappers.UserCouponMapper promoInstances;
    private final PromotionMappers.ApplyMapper applications;
    private final PromotionMappers.CouponScopeMapper scopes;
    private final TradeMappers.OrderMapper orders;
    private final TradeMappers.SubOrderMapper subs;
    private final ActivityCommandDeduplicator dedup;
    private final ObjectMapper json;
    private final String watermarkKey;
    private final ObjectProvider<MerchantQueryPort> merchants;
    private final ObjectProvider<MerchantScopeFactsPort> merchantFacts;

    public LegacyCouponSourceFenceServiceImpl(LegacyCouponFenceMapper locks, CouponMappers.CouponMapper legacyTemplates,
            CouponMappers.UserCouponMapper legacyInstances, PromotionMappers.CouponMapper promoTemplates,
            PromotionMappers.UserCouponMapper promoInstances, PromotionMappers.ApplyMapper applications,
            PromotionMappers.CouponScopeMapper scopes,
            TradeMappers.OrderMapper orders, TradeMappers.SubOrderMapper subs, ActivityCommandDeduplicator dedup,
            ObjectMapper json, @Value("${shop.core.entitlement.migration-watermark-key:}") String watermarkKey,
            ObjectProvider<MerchantQueryPort> merchants,
            ObjectProvider<MerchantScopeFactsPort> merchantFacts) {
        this.locks = locks; this.legacyTemplates = legacyTemplates; this.legacyInstances = legacyInstances;
        this.promoTemplates = promoTemplates; this.promoInstances = promoInstances; this.applications = applications;
        this.scopes = scopes; this.merchants=merchants; this.merchantFacts=merchantFacts;
        this.orders = orders; this.subs = subs; this.dedup = dedup; this.json = json; this.watermarkKey = watermarkKey;
    }

    @Autowired
    @Qualifier("dataSource")
    private DataSource checkoutSource;

    @Override
    public SourceSystem ordinarySource(String tenant, String buyer, String no) {
        return DataScopeContext.executeWithoutScope(() -> {
            var origins = ordinaryOrigins(tenant, buyer, no);
            return origins.promo() != null ? SourceSystem.PMT : SourceSystem.MKT_LEGACY;
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SourceSystem lockOrdinarySource(String tenant, String buyer, String no) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(checkoutSource)) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        return DataScopeContext.executeWithoutScope(() -> {
            var before = ordinaryOrigins(tenant, buyer, no);
            // Same source owner and same lock order as transition/requireWritable.
            if (before.legacy() != null) {
                requireWritable(tenant, SourceSystem.MKT_LEGACY, before.legacy().getTemplateNo(), false);
            }
            if (before.promo() != null) {
                requireWritable(tenant, SourceSystem.PMT, before.promo().getTemplateNo(), false);
            }
            var locked = ordinaryOrigins(tenant, buyer, no);
            return locked.promo() != null ? SourceSystem.PMT : SourceSystem.MKT_LEGACY;
        });
    }

    private OrdinaryOrigins ordinaryOrigins(String tenant, String user, String no) {
        ContractChecks.require("MAIN".equals(tenant));
        ContractChecks.id(user);
        ContractChecks.id(no);
        var pmt = locks.promoOrigin(tenant, user, no);
        var mkt = locks.legacyOrigin(tenant, user, no);
        if (pmt == null && mkt == null) {
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        }
        requireOrdinaryOrigin(pmt);
        requireOrdinaryOrigin(mkt);
        if (pmt != null && mkt != null && !Objects.equals(pmt.getEntityNo(), mkt.getEntityNo())) {
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        }
        return new OrdinaryOrigins(pmt, mkt);
    }

    private void requireOrdinaryOrigin(LegacyCouponOriginRow row) {
        if (row == null) {
            return;
        }
        if (!Boolean.TRUE.equals(row.getRootPresent())
                || !Integer.valueOf(0).equals(row.getRootDeleted())
                || !Integer.valueOf(0).equals(row.getInstanceDeleted())
                || !Set.of("OPEN", "ABORT_DECIDED").contains(row.getRootState())
                || row.getInstanceEntityNo() != null
                        && !row.getInstanceEntityNo().equals(row.getEntityNo())) {
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        }
    }

    private record OrdinaryOrigins(LegacyCouponOriginRow promo, LegacyCouponOriginRow legacy) {}

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public FenceView transition(EntitlementContext ctx, SourceIdentity source, Command command) {
        check(ctx, source);
        if (command.action() != Action.ABORT && (watermarkKey.isBlank() || watermarkKey.length() < 32)) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
        return DataScopeContext.executeWithoutScope(() -> {
            var root = requiredRoot(ctx.tenantNo(), source);
            if (root.deleted() || !"MERCHANT".equals(root.funder()) || root.entity() == null) {
                throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            }
            var actor = new ActivityActor(ctx.tenantNo(), root.entity(), ctx.actorNo(), null);
            return dedup.execute(actor, "LEGACY_FENCE_" + command.action(), command.requestKey(),
                    List.of(source, command), source.sourceNo(), FenceAudit.class, () -> {
                        apply(ctx, source, root, command);
                        var result = view(requiredRoot(ctx.tenantNo(), source));
                        return new FenceAudit(result, command.action(), ctx.actorNo(), command.reason());
                    }).result();
        });
    }
    private void apply(EntitlementContext ctx, SourceIdentity source, Root root, Command command) {
        if (root.revision() != command.expectedRevision()) throw BizException.of(ErrorCode.CONFLICT);
        String state;
        String watermark = root.watermark();
        Long frozenRevision = root.frozenRevision();
        switch (command.action()) {
            case BEGIN_DRAIN -> {
                if (!Set.of("OPEN", "ABORT_DECIDED").contains(root.state())
                        || command.batchNo().equals(root.batch())) throw BizException.of(ErrorCode.CONFLICT);
                state = "DRAINING"; watermark = null; frozenRevision = null;
            }
            case FREEZE -> {
                if (!"DRAINING".equals(root.state()) || !command.batchNo().equals(root.batch())) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                watermark = frozenWatermark(ctx.tenantNo(), source, root);
                frozenRevision = Math.addExact(root.revision(), 1);
                state = "FROZEN";
            }
            case ABORT -> {
                if (!Set.of("DRAINING", "FROZEN").contains(root.state()) || !command.batchNo().equals(root.batch())) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                state = "ABORT_DECIDED";
            }
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int affected = source.sourceSystem() == SourceSystem.MKT_LEGACY
                ? legacyTemplates.update(null, changes(root, state, command.batchNo(), watermark, frozenRevision, ctx.actorNo()))
                : promoTemplates.update(null, changes(root, state, command.batchNo(), watermark, frozenRevision, ctx.actorNo()));
        if (affected != 1) throw BizException.of(ErrorCode.CONFLICT);
    }

    private <T> UpdateWrapper<T> changes(Root root, String state, String batch, String watermark,
                                           Long frozenRevision, String actor) {
        return new UpdateWrapper<T>().eq("tenant_no", root.tenant()).eq("id", root.id()).eq("version", root.revision())
                .set("migration_state", state).set("migration_batch_no", batch).set("migration_watermark", watermark)
                .set("migration_frozen_revision", frozenRevision).setSql("version=version+1")
                .set("updated_by", actor).set("updated_at", LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    @Transactional
    public FenceView state(EntitlementContext ctx, SourceIdentity source) {
        check(ctx, source);
        return DataScopeContext.executeWithoutScope(() -> view(requiredRoot(ctx.tenantNo(), source)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWritable(String tenant, SourceSystem source, String no, boolean originalRelease) {
        ContractChecks.id(tenant);
        var identity = new SourceIdentity(source, SourceKind.TEMPLATE, no);
        if (source == SourceSystem.PAY) throw BizException.of(ErrorCode.BAD_REQUEST);
        DataScopeContext.executeWithoutScope(() -> {
            var root = root(tenant, identity);
            // An orphan original release retains old behavior. Such a source cannot be frozen/imported (preflight blocks it).
            if (root == null && originalRelease) return;
            if (root == null) throw BizException.of(ErrorCode.NOT_FOUND);
            if (!Set.of("OPEN", "ABORT_DECIDED").contains(root.state())
                    && !(originalRelease && "DRAINING".equals(root.state()))) throw BizException.of(ErrorCode.CONFLICT);
        });
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Snapshot exportFrozen(EntitlementContext ctx, Query query) {
        ContractChecks.present(query);
        check(ctx,query.source());
        if(watermarkKey.isBlank() || watermarkKey.length()<32)
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return DataScopeContext.executeWithoutScope(() -> {
            var root=requiredRoot(ctx.tenantNo(),query.source());
            if(root.deleted() || !"MERCHANT".equals(root.funder()) || root.entity()==null
                    || !"FROZEN".equals(root.state()) || !query.batchNo().equals(root.batch())
                    || !Long.valueOf(query.frozenRevision()).equals(root.frozenRevision())
                    || !query.watermark().equals(root.watermark())) throw BizException.of(ErrorCode.CONFLICT);
            var captured=captureSource(ctx.tenantNo(),query.source(),root,true);
            if(!query.watermark().equals(captured.watermark())) throw BizException.of(ErrorCode.CONFLICT);
            return new Snapshot(1,query.source(),root.entity(),query.batchNo(),query.frozenRevision(),
                    captured.watermark(),captured.contentHash(),captured.payload());
        });
    }
    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Snapshot exportManifest(EntitlementContext ctx,Query query) {
        ContractChecks.present(query); check(ctx,query.source());
        if(watermarkKey.isBlank() || watermarkKey.length()<32)
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return DataScopeContext.executeWithoutScope(() -> manifestLocked(ctx,query,requiredRoot(ctx.tenantNo(),query.source())));
    }
    private Snapshot manifestLocked(EntitlementContext ctx,Query query,Root root) {
            if(root.deleted() || !"MERCHANT".equals(root.funder()) || root.entity()==null
                    || !"FROZEN".equals(root.state()) || !query.batchNo().equals(root.batch())
                    || !Long.valueOf(query.frozenRevision()).equals(root.frozenRevision())
                    || !query.watermark().equals(root.watermark())) throw BizException.of(ErrorCode.CONFLICT);
            if(!query.watermark().equals(captureSource(ctx.tenantNo(),query.source(),root,false).watermark()))
                throw BizException.of(ErrorCode.CONFLICT);
            boolean old=query.source().sourceSystem()==SourceSystem.MKT_LEGACY;
            List<?> instances=old?locks.manifestLegacyInstances(ctx.tenantNo(),root.no(),MAX_INSTANCES+1)
                    :locks.manifestPromoInstances(ctx.tenantNo(),root.no(),MAX_INSTANCES+1);
            List<?> issueRows=old?locks.manifestLegacyIssues(ctx.tenantNo(),root.no(),MAX_HISTORY+1)
                    :locks.manifestPromoIssues(ctx.tenantNo(),root.no(),MAX_HISTORY+1);
            var history=old?List.<PmtApply>of():locks.manifestPromoHistory(ctx.tenantNo(),root.no(),MAX_HISTORY+1);
            var scopeRows=old?List.<PmtCouponScope>of():locks.manifestPromoScopes(ctx.tenantNo(),root.no(),MAX_INSTANCES+1);
            if(instances.size()>MAX_INSTANCES || issueRows.size()>MAX_HISTORY || history.size()>MAX_HISTORY
                    || scopeRows.size()>MAX_INSTANCES) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            var boundOrders=new java.util.TreeSet<String>();
            for(var item:instances) {
                if(item instanceof MktUserCoupon row && "USED".equals(row.getStatus()) && row.getOrderNo()!=null)
                    boundOrders.add(row.getOrderNo());
                if(item instanceof PmtUserCoupon row && row.getOrderNo()!=null) boundOrders.add(row.getOrderNo());
            }
            for(var item:history) if(item.getRevertedAt()==null && item.getOrderNo()!=null) boundOrders.add(item.getOrderNo());
            boundOrders.forEach(no -> checkDrained(ctx.tenantNo(),no));
            var document=new TreeMap<String,Object>();
            document.put("manifestVersion",2); document.put("fenceWatermarkKind","VISIBLE_V1");
            document.put("source",query.source()); document.put("batchNo",query.batchNo());
            document.put("template",new TreeMap<>(json.convertValue(root.legacy()!=null?root.legacy():root.promo(),
                    new TypeReference<Map<String,Object>>() { })));
            document.put("instances",instances); document.put("issues",issueRows);
            document.put("applications",history); document.put("scopes",scopeRows); document.put("historyAvailable",!old);
            var facts=new TreeMap<String,Object>();
            facts.put("version",1);
            facts.put("legacyProgramReferenceCount",old?locks.legacyProgramReferences(ctx.tenantNo(),root.no()):0L);
            var merchant=merchants.getIfAvailable();
            var merchantOwner=merchantFacts.getIfAvailable();
            boolean active=merchantOwner!=null && merchantOwner.entityActive(ctx.tenantNo(),root.entity());
            facts.put("entityActive",active);
            facts.put("market",active && merchant!=null ? merchant.marketOf(root.entity()) : null);
            facts.put("peerTemplateCount",old?locks.peerPromoTemplates(ctx.tenantNo(),root.no())
                    :locks.peerLegacyTemplates(ctx.tenantNo(),root.no()));
            facts.put("peerInstanceCount",old?locks.peerPromoInstances(ctx.tenantNo(),root.no())
                    :locks.peerLegacyInstances(ctx.tenantNo(),root.no()));
            document.put("assessmentFacts",facts);
            String payload=boundedManifest(document);
            try {
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
                return new Snapshot(1,query.source(),root.entity(),query.batchNo(),query.frozenRevision(),query.watermark(),hash,payload);
            } catch(GeneralSecurityException failure) { throw new IllegalStateException("Manifest digest unavailable"); }
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void commitSources(EntitlementContext ctx,LegacyCouponMigrationPlan plan) {
        ContractChecks.present(plan);
        if(!ctx.tenantNo().equals(plan.tenantNo()) || !"MAIN".equals(ctx.tenantNo())
                || !plan.sourceBundleHash().equals(ai.neargo.shop.promotion.service.ActivityCommandDeduplicator.hash(json.writeValueAsString(plan.sources()))))
            throw BizException.of(ErrorCode.CONFLICT);
        if(watermarkKey.isBlank() || watermarkKey.length()<32) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        DataScopeContext.executeWithoutScope(()->{
            var locked=new java.util.LinkedHashMap<LegacyCouponMigrationPlan.Source,Root>();
            for(var source:plan.sources().stream().sorted(java.util.Comparator.comparing(
                    (LegacyCouponMigrationPlan.Source source)->source.sourceSystem()+":"+source.sourceTemplateNo())).toList()) {
                check(ctx,source.identity());locked.put(source,requiredRoot(ctx.tenantNo(),source.identity()));
            }
            for(var entry:locked.entrySet()) {
                var source=entry.getKey();var root=entry.getValue();
                if(!plan.entityNo().equals(root.entity())) throw BizException.of(ErrorCode.CONFLICT);
                if(root.source()==SourceSystem.MKT_LEGACY && locks.legacyProgramReferences(ctx.tenantNo(),root.no())>0)
                    throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                var snapshot=manifestLocked(ctx,new Query(source.identity(),source.batchNo(),source.frozenRevision(),source.watermark()),root);
                if(!source.contentHash().equals(snapshot.contentHash())) throw BizException.of(ErrorCode.CONFLICT);
            }
            for(var root:locked.values()) {
                int changed=root.source()==SourceSystem.MKT_LEGACY
                        ?legacyTemplates.update(null,changes(root,"COMMIT_DECIDED",root.batch(),root.watermark(),root.frozenRevision(),ctx.actorNo()))
                        :promoTemplates.update(null,changes(root,"COMMIT_DECIDED",root.batch(),root.watermark(),root.frozenRevision(),ctx.actorNo()));
                if(changed!=1) throw BizException.of(ErrorCode.CONFLICT);
            }
        });
    }
    private String boundedManifest(Map<String,Object> document) {
        var bytes=new ByteArrayOutputStream();
        boolean[] overflow={false};
        var sink=new OutputStream() {
            private void room(int addition) {
                if(addition>MAX_PAYLOAD_BYTES-bytes.size()) {
                    overflow[0]=true; throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                }
            }
            @Override public void write(int value) { room(1); bytes.write(value); }
            @Override public void write(byte[] values,int offset,int length) { room(length); bytes.write(values,offset,length); }
        };
        try { json.writeValue(sink,document); }
        catch(RuntimeException failure) {
            if(overflow[0]) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            throw failure;
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
    private record CapturedSource(String watermark,String contentHash,String payload) {
        @Override public String toString() { return "CapturedSource[payload=<redacted>]"; }
    }
    private String frozenWatermark(String tenant,SourceIdentity source,Root root) {
        return captureSource(tenant,source,root,false).watermark();
    }
    private CapturedSource captureSource(String tenant, SourceIdentity source, Root root,boolean export) {
        var records = new ArrayList<Object>();
        var boundOrders = new ArrayList<String>();
        var instanceNos = new ArrayList<String>();
        if (source.sourceSystem() == SourceSystem.MKT_LEGACY) {
            var rows = legacyInstances.selectList(Wrappers.<MktUserCoupon>lambdaQuery()
                    .eq(MktUserCoupon::getTenantNo, tenant).eq(MktUserCoupon::getCouponNo, source.sourceNo())
                    .orderByAsc(MktUserCoupon::getId).last("LIMIT " + (MAX_INSTANCES + 1)));
            if (rows.size() > MAX_INSTANCES) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            records.addAll(rows);
            rows.forEach(row -> {
                if ("USED".equals(row.getStatus()) && row.getOrderNo() != null) boundOrders.add(row.getOrderNo());
            });
        } else {
            var rows = promoInstances.selectList(Wrappers.<PmtUserCoupon>lambdaQuery()
                    .eq(PmtUserCoupon::getTenantNo, tenant).eq(PmtUserCoupon::getCouponNo, source.sourceNo())
                    .orderByAsc(PmtUserCoupon::getId).last("LIMIT " + (MAX_INSTANCES + 1)));
            if (rows.size() > MAX_INSTANCES) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            records.addAll(rows);
            rows.forEach(row -> {
                instanceNos.add(row.getUserCouponNo());
                if (row.getOrderNo() != null) boundOrders.add(row.getOrderNo());
            });
        }
        var history = instanceNos.isEmpty() ? List.<PmtApply>of() : applications.selectList(
                Wrappers.<PmtApply>lambdaQuery().eq(PmtApply::getTenantNo, tenant).eq(PmtApply::getPromoType, "COUPON")
                        .in(PmtApply::getPromoNo, instanceNos).orderByAsc(PmtApply::getId).last("LIMIT " + (MAX_HISTORY + 1)));
        if (history.size() > MAX_HISTORY) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        history.forEach(row -> {
            if (row.getRevertedAt() == null && row.getOrderNo() != null) boundOrders.add(row.getOrderNo());
        });
        for (String orderNo : boundOrders.stream().distinct().sorted().toList()) checkDrained(tenant, orderNo);
        var scopeRows = source.sourceSystem() == SourceSystem.MKT_LEGACY ? List.<PmtCouponScope>of() : scopes.selectList(
                Wrappers.<PmtCouponScope>lambdaQuery().eq(PmtCouponScope::getTenantNo, tenant)
                        .eq(PmtCouponScope::getCouponNo, source.sourceNo()).orderByAsc(PmtCouponScope::getId)
                        .last("LIMIT " + (MAX_INSTANCES + 1)));
        if (scopeRows.size() > MAX_INSTANCES) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        Object template = root.legacy() != null ? root.legacy() : root.promo();
        Map<String, Object> definition = new TreeMap<>(json.convertValue(template, new TypeReference<Map<String, Object>>() { }));
        for (String key : List.of("migrationState", "migrationBatchNo", "migrationWatermark", "migrationFrozenRevision",
                "version", "updatedAt", "updatedBy", "archivedAt")) definition.remove(key);
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(watermarkKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var digest=MessageDigest.getInstance("SHA-256");
            var bytes=export?new ByteArrayOutputStream():null;
            var sink = new OutputStream() {
                private void checkSize(int addition) {
                    if(export && addition>MAX_PAYLOAD_BYTES-bytes.size())
                        throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                }
                @Override public void write(int value) {
                    checkSize(1); mac.update((byte)value); digest.update((byte)value);
                    if(export) bytes.write(value);
                }
                @Override public void write(byte[] values,int offset,int length) {
                    checkSize(length); mac.update(values,offset,length); digest.update(values,offset,length);
                    if(export) bytes.write(values,offset,length);
                }
            };
            // Same bytes as the frozen HMAC; export rejects overflow instead of silently truncating.
            json.writeValue(sink,List.of("LEGACY_SOURCE_V1",source,root.batch(),definition,records,history,scopeRows));
            return new CapturedSource(HexFormat.of().formatHex(mac.doFinal()),
                    export?HexFormat.of().formatHex(digest.digest()):null,
                    export?bytes.toString(StandardCharsets.UTF_8):null);
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Migration watermark unavailable", failure); }
    }
    private void checkDrained(String tenant, String orderNo) {
        var order = orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo, tenant)
                .eq(OrdOrder::getOrderNo, orderNo));
        if (order == null || !Set.of(OrdOrder.PAID, OrdOrder.CANCELLED, OrdOrder.CLOSED).contains(order.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var children = subs.selectList(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo, tenant)
                .eq(OrdSubOrder::getOrderNo, orderNo));
        if (children.isEmpty() || children.stream().anyMatch(row ->
                !Set.of("COMPLETED", "CANCELLED", "REFUNDED").contains(row.getStatus()))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private static void check(EntitlementContext context, SourceIdentity source) {
        ContractChecks.present(context); ContractChecks.present(source);
        if (!"MAIN".equals(context.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if (source.sourceSystem() == SourceSystem.PAY || source.sourceKind() != SourceKind.TEMPLATE) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
    private Root requiredRoot(String tenant, SourceIdentity source) {
        var root = root(tenant, source);
        if (root == null || !source.sourceNo().equals(root.no())) throw BizException.of(ErrorCode.NOT_FOUND);
        return root;
    }
    private Root root(String tenant, SourceIdentity source) {
        if (source.sourceSystem() == SourceSystem.MKT_LEGACY) {
            var row = locks.lockLegacy(tenant, source.sourceNo());
            return row == null ? null : new Root(source.sourceSystem(), row.getTenantNo(), row.getId(), row.getCouponNo(),
                    row.getEntityNo(), row.getFunder(), row.getVersion(), row.getMigrationState(), row.getMigrationBatchNo(),
                    row.getMigrationWatermark(), row.getMigrationFrozenRevision(), Integer.valueOf(1).equals(row.getDeleted()), row, null);
        }
        var row = locks.lockPromo(tenant, source.sourceNo());
        return row == null ? null : new Root(source.sourceSystem(), row.getTenantNo(), row.getId(), row.getCouponNo(),
                row.getEntityNo(), row.getFunder(), row.getVersion(), row.getMigrationState(), row.getMigrationBatchNo(),
                row.getMigrationWatermark(), row.getMigrationFrozenRevision(), Integer.valueOf(1).equals(row.getDeleted()), null, row);
    }
    private static FenceView view(Root root) {
        return new FenceView(root.source(), root.no(), root.entity(), root.batch(), root.state(), root.watermark(),
                root.frozenRevision(), root.revision());
    }
    private record Root(SourceSystem source, String tenant, Long id, String no, String entity, String funder,
                        long revision, String state, String batch, String watermark, Long frozenRevision,
                        boolean deleted, MktCoupon legacy, PmtCoupon promo) { }
}
