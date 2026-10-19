package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.BenefitClaimCommand;
import ai.neargo.shop.promotion.dto.ActivityBenefitDecided;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.promotion.dto.BenefitGrantPlan;
import ai.neargo.shop.promotion.entity.PmtBenefitIntent;
import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import ai.neargo.shop.promotion.mapper.PmtBenefitIntentMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.service.ActivityBenefitIntentService;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.ActivityParticipationService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** All catalog preflight precedes core locks. Each participation freezes its entire benefit bundle once. */
@Service
public class ActivityBenefitIntentServiceImpl implements ActivityBenefitIntentService {
    private final PmtBenefitIntentMapper intents;
    private final PmtActivityVersionMapper versions;
    private final ObjectProvider<ActivityParticipationService> participation;
    private final EntitlementCatalogPort catalog;
    private final UserScopeFactsPort users;
    private final MerchantScopeFactsPort merchants;
    private final StoreDirectoryPort stores;
    private final ObjectMapper json;
    private final OutboxEventBus events;
    public ActivityBenefitIntentServiceImpl(PmtBenefitIntentMapper intents, PmtActivityVersionMapper versions,
            ObjectProvider<ActivityParticipationService> participation, EntitlementCatalogPort catalog,
            UserScopeFactsPort users, MerchantScopeFactsPort merchants, StoreDirectoryPort stores, ObjectMapper json,
            OutboxEventBus events) {
        this.intents = intents; this.versions = versions; this.participation = participation; this.catalog = catalog;
        this.users = users; this.merchants = merchants; this.stores = stores; this.json = json; this.events = events;
    }
    @Override @Transactional
    public BenefitGrantPlan begin(EntitlementContext ctx, BenefitClaimCommand command, String key) {
        if (key == null || key.isBlank() || key.length() > 128) badRequest();
        String keyHash = hash(key);
        String intent = "BI" + hash(json.writeValueAsString(
                List.of(ctx.tenantNo(), ctx.actorNo(), key))).substring(0, 60);
        String payload = hash(json.writeValueAsString(command));
        var prior = intents.find(ctx.tenantNo(), intent);
        if (prior != null) return replay(prior, ctx, payload);
        if (users.findActive(ctx.tenantNo(), ctx.actorNo()).isEmpty()
                || !merchants.entityActive(ctx.tenantNo(), command.entityNo())
                || !merchants.storesOwned(ctx.tenantNo(), command.entityNo(), Set.of(command.storeNo()))) notFound();
        var store = stores.cards(List.of(command.storeNo())).get(command.storeNo());
        if (store == null || !store.active() || !command.entityNo().equals(store.entityNo())) notFound();
        boolean activity = command.activityNo() != null;
        var parent = new SourceRef("CORE", activity ? "ACTIVITY" : "CENTER", intent, null, null, null, 0);
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        var frozen = new ArrayList<GrantCommand>();
        String currency;
        ReserveActivity reserve = null;
        if (activity) {
            var version = versions.selectOne(Wrappers.<PmtActivityVersion>lambdaQuery()
                    .eq(PmtActivityVersion::getTenantNo, ctx.tenantNo())
                    .eq(PmtActivityVersion::getActivityNo, command.activityNo())
                    .eq(PmtActivityVersion::getVersionNo, command.activityVersionNo()));
            if (version == null) { notFound(); return null; }
            var definition = json.readValue(version.getRuleSnapshot(),
                    ActivityVersionServiceImpl.PublishedSnapshot.class).definition();
            if (!"ENTITLEMENT_GRANT".equals(definition.scene())
                    || !"NONE".equals(definition.rule().trigger().type())) unavailable();
            if (definition.rule().benefits().stream().noneMatch(b ->
                    command.templateVersionNo().equals(b.templateVersionNo()))) conflict();
            currency = definition.currency();
            int index = 0;
            for (var benefit : definition.rule().benefits()) {
                var right = catalog.getPublishedVersion(ctx.tenantNo(), command.entityNo(),
                        benefit.templateVersionNo());
                if (!currency.equals(right.definition().currency()) || "PAID_CARD".equals(right.definition().kind())
                        || !right.definition().allowedIssueModes().contains("ACTIVITY")) conflict();
                var child = child(parent, ++index);
                frozen.add(new GrantCommand(grantNo(child), child, null, right.versionNo(), ctx.actorNo(),
                        command.entityNo(), benefit.quantity(), right.snapshotHash(), null));
            }
            reserve = new ReserveActivity(parent, command.activityNo(), command.activityVersionNo(), null,
                    ctx.actorNo(), command.entityNo(), command.storeNo(), currency, 1);
        } else {
            var right = catalog.getPublishedVersion(ctx.tenantNo(), command.entityNo(), command.templateVersionNo());
            if ("PAID_CARD".equals(right.definition().kind())
                    || !right.definition().allowedIssueModes().contains("CENTER")) conflict();
            currency = right.definition().currency();
            frozen.add(new GrantCommand(grantNo(parent), parent, null, right.versionNo(), ctx.actorNo(),
                    command.entityNo(), 1, right.snapshotHash(), null));
        }
        if (frozen.isEmpty()) unavailable();
        var plan = new BenefitGrantPlan(intent, ctx, command, parent, reserve, null, currency, frozen, "PENDING", now);
        var row = new PmtBenefitIntent(); row.setTenantNo(ctx.tenantNo()); row.setIntentNo(intent);
        row.setUserNo(ctx.actorNo()); row.setRequestKeyHash(keyHash); row.setPayloadHash(payload);
        row.setStatus("PENDING"); row.setPlanSnapshot(json.writeValueAsString(plan));
        row.setCreatedBy(ctx.actorNo()); row.setUpdatedBy(ctx.actorNo());
        row.setCreatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC)); row.setUpdatedAt(row.getCreatedAt());
        int created = intents.insertIfAbsent(row);
        row = intents.lock(ctx.tenantNo(), intent);
        if (row == null) conflict();
        if (created == 0) return replay(row, ctx, payload);
        if (reserve != null) {
            var permit = participation.getObject().reserve(ctx, reserve);
            var withPermits = frozen.stream().map(c -> new GrantCommand(c.grantNo(), c.sourceRef(),
                    childPermit(permit, c.sourceRef()), c.templateVersionNo(), c.recipientUserNo(), c.entityNo(),
                    c.quantity(), c.benefitSnapshotHash(), null)).toList();
            plan = new BenefitGrantPlan(intent, ctx, command, parent, reserve, permit, currency,
                    withPermits, "PENDING", now);
            save(ctx, plan);
        }
        return plan;
    }
    @Override public BenefitGrantPlan mine(EntitlementContext ctx, String no) {
        var plan = find(ctx.tenantNo(), no);
        if (!plan.context().actorNo().equals(ctx.actorNo())) notFound();
        return plan;
    }
    @Override public BenefitGrantPlan find(String tenant, String no) {
        var row = intents.find(tenant, no);
        if (row == null) { notFound(); return null; }
        return json.readValue(row.getPlanSnapshot(), BenefitGrantPlan.class);
    }
    @Override @Transactional
    public BenefitGrantPlan decide(EntitlementContext ctx, String no, boolean commit) {
        var row = intents.lock(ctx.tenantNo(), no);
        if (row == null || !row.getUserNo().equals(ctx.actorNo())) notFound();
        var plan = json.readValue(row.getPlanSnapshot(), BenefitGrantPlan.class);
        String status = commit ? "COMMIT_DECIDED" : "ABORT_DECIDED";
        if (plan.status().equals(status) || plan.status().equals(commit ? "COMMITTED" : "ABORTED")) return plan;
        if (!plan.status().equals("PENDING")) conflict();
        var changed = withStatus(plan, status); save(ctx, changed);
        events.publish("BE" + plan.parentSource().grantKey().substring(0, 60),
                new ActivityBenefitDecided(ctx.tenantNo(), no, row.getUserNo(), plan.createdAt().toEpochMilli()));
        return changed;
    }
    @Override @Transactional
    public BenefitGrantPlan finish(EntitlementContext ctx, String no, boolean committed) {
        var row = intents.lock(ctx.tenantNo(), no);
        if (row == null || !row.getUserNo().equals(ctx.actorNo())) notFound();
        var plan = json.readValue(row.getPlanSnapshot(), BenefitGrantPlan.class);
        String terminal = committed ? "COMMITTED" : "ABORTED";
        if (plan.status().equals(terminal)) return plan;
        if (!plan.status().equals(committed ? "COMMIT_DECIDED" : "ABORT_DECIDED")) conflict();
        var changed = withStatus(plan, terminal); save(ctx, changed); return changed;
    }
    @Override public void validateParticipation(EntitlementContext ctx, ReserveActivity command) {
        var plan = find(ctx.tenantNo(), command.sourceRef().sourceNo());
        requireParent(plan, command.sourceRef());
        if (!command.equals(plan.participation()) || !"PENDING".equals(plan.status())) conflict();
    }
    @Override public void requireParent(BenefitGrantPlan plan, SourceRef source) {
        if (!plan.parentSource().equals(source)) conflict();
    }
    private BenefitGrantPlan replay(PmtBenefitIntent row, EntitlementContext ctx, String payload) {
        if (!row.getUserNo().equals(ctx.actorNo()) || !row.getPayloadHash().equals(payload)) conflict();
        return json.readValue(row.getPlanSnapshot(), BenefitGrantPlan.class);
    }
    private BenefitGrantPlan withStatus(BenefitGrantPlan p, String status) {
        return new BenefitGrantPlan(p.intentNo(), p.context(), p.request(), p.parentSource(), p.participation(),
                p.permit(), p.currency(), p.grants(), status, p.createdAt());
    }
    private void save(EntitlementContext ctx, BenefitGrantPlan p) {
        if (intents.save(ctx.tenantNo(), p.intentNo(), p.status(), json.writeValueAsString(p)) != 1) conflict();
    }
    private static SourceRef child(SourceRef parent, int index) {
        return new SourceRef("CORE", "ACTIVITY", parent.sourceNo(), Integer.toString(index), null, null, 0);
    }
    private static String grantNo(SourceRef source) { return "BG" + source.grantKey().substring(0, 60); }
    private static ParticipationPermit childPermit(ParticipationPermit p, SourceRef child) {
        return new ParticipationPermit(p.reservationNo(), child, p.activityNo(), p.versionNo(), p.tierNo(), p.userNo(),
                p.entityNo(), p.storeNo(), p.currency(), p.payAmountMinor(), p.bonusAmountMinor(), p.benefitRefs(),
                p.redemptionScope(), p.snapshotHash(), p.issuedAt());
    }
    private static String hash(String value) { return ActivityCommandDeduplicator.hash(value); }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
    private static void badRequest() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    private static void notFound() { throw BizException.of(ErrorCode.NOT_FOUND); }
    private static void unavailable() { throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
}
