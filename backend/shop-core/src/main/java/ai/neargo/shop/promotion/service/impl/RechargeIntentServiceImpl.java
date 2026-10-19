package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.promotion.dto.RechargeIntentCreate;
import ai.neargo.shop.promotion.dto.RechargeIntentPlan;
import ai.neargo.shop.promotion.dto.StoredValueRechargePrepared;
import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import ai.neargo.shop.promotion.entity.PmtRechargeIntent;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.mapper.PmtRechargeIntentMapper;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ReferenceQuery;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Core owns the immutable business source before reservation, breaking the permit/create dependency cycle. */
@Service
public class RechargeIntentServiceImpl implements RechargeIntentService {
    private final PmtRechargeIntentMapper intents;
    private final PmtActivityVersionMapper versions;
    private final ObjectProvider<ActivityParticipationPort> participation;
    private final CustomerIdentityPort customers;
    private final UserScopeFactsPort users;
    private final MerchantScopeFactsPort owners;
    private final MerchantQueryPort merchants;
    private final StoreDirectoryPort stores;
    private final MarketPort markets;
    private final ObjectMapper json;
    private final OutboxEventBus events;
    public RechargeIntentServiceImpl(PmtRechargeIntentMapper intents, PmtActivityVersionMapper versions,
            ObjectProvider<ActivityParticipationPort> participation, CustomerIdentityPort customers,
            UserScopeFactsPort users, MerchantScopeFactsPort owners, MerchantQueryPort merchants,
            StoreDirectoryPort stores, MarketPort markets, ObjectMapper json, OutboxEventBus events) {
        this.intents = intents; this.versions = versions; this.participation = participation;
        this.customers = customers; this.users = users; this.owners = owners; this.merchants = merchants;
        this.stores = stores; this.markets = markets; this.json = json; this.events = events;
    }
    @Override @Transactional("transactionManager")
    public RechargeIntentPlan begin(EntitlementContext ctx, String entity, RechargeIntentCreate c, String key) {
        ContractChecks.id(entity);
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        String identity = hash(json.writeValueAsString(List.of(ctx.tenantNo(), entity, ctx.actorNo(), key)));
        String no = "RI" + identity.substring(0, 60);
        String payload = hash(json.writeValueAsString(List.of(entity, ctx.actorNo(), c)));
        var prior = intents.find(ctx.tenantNo(), no);
        if (prior != null) return replay(prior, ctx, entity, payload);
        if (!owners.entityActive(ctx.tenantNo(), entity)
                || !owners.storesOwned(ctx.tenantNo(), entity, Set.of(c.storeNo()))) notFound();
        var store = stores.cards(List.of(c.storeNo())).get(c.storeNo());
        if (store == null || !store.active() || !entity.equals(store.entityNo())) notFound();
        String user = customers.resolveReference(ctx,
                new ReferenceQuery(entity, c.storeNo(), c.customerRef())).userNo();
        if (users.findActive(ctx.tenantNo(), user).isEmpty()) notFound();
        String currency = markets.find(merchants.marketOf(entity)).filter(MarketPort.MarketBrief::enabled)
                .orElseThrow(() -> BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE)).currency();
        var version = DataScopeContext.executeWithoutScope(() -> versions.selectOne(
                Wrappers.<PmtActivityVersion>lambdaQuery().eq(PmtActivityVersion::getTenantNo, ctx.tenantNo())
                        .eq(PmtActivityVersion::getActivityNo, c.activityNo())
                        .eq(PmtActivityVersion::getVersionNo, c.versionNo())));
        if (version == null) notFound();
        var d = json.readValue(version.getRuleSnapshot(),
                ActivityVersionServiceImpl.PublishedSnapshot.class).definition();
        if (!"RECHARGE".equals(d.scene()) || !currency.equals(d.currency()) || !d.rule().benefits().isEmpty()) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
        if (d.rule().tiers().stream().noneMatch(t -> t.tierNo().equals(c.tierNo()))) conflict();
        var source = new SourceRef("CORE", "RECHARGE", no, null, null, null, 0);
        var binding = new ReserveActivity(source, c.activityNo(), c.versionNo(), c.tierNo(), user, entity,
                c.storeNo(), currency, 1);
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        var original = new EntitlementContext(ctx.tenantNo(), ctx.actorNo(), identity, payload);
        var plan = new RechargeIntentPlan(no, original, source, "RC" + source.grantKey().substring(0, 60), binding,
                null, store.storeName(), now, now.plusSeconds(900));
        var row = new PmtRechargeIntent(); row.setTenantNo(ctx.tenantNo()); row.setIntentNo(no);
        row.setRechargeNo(plan.rechargeNo()); row.setEntityNo(entity); row.setOperatorNo(ctx.actorNo());
        row.setRequestKeyHash(hash(key)); row.setPayloadHash(payload); row.setStatus("PENDING");
        row.setPlanSnapshot(json.writeValueAsString(plan)); row.setCreatedBy(ctx.actorNo());
        row.setUpdatedBy(ctx.actorNo()); row.setCreatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        row.setUpdatedAt(row.getCreatedAt());
        int created = intents.insertIfAbsent(row); row = intents.lock(ctx.tenantNo(), no);
        if (row == null) conflict();
        if (created == 0) return replay(row, ctx, entity, payload);
        plan = plan.permitted(participation.getObject().reserve(original, binding));
        if (intents.save(ctx.tenantNo(), no, "PENDING", json.writeValueAsString(plan)) != 1) conflict();
        events.publish("RP" + source.grantKey().substring(0, 60), new StoredValueRechargePrepared(ctx.tenantNo(),
                no, entity, plan.rechargeNo(), now.toEpochMilli()));
        return plan;
    }
    @Override public RechargeIntentPlan find(String tenant, String no) { return read(intents.find(tenant, no)); }
    @Override public RechargeIntentPlan byRechargeNo(String tenant, String no) {
        return read(intents.byRecharge(tenant, no));
    }
    @Override @Transactional("transactionManager")
    public void markSettled(EntitlementContext ctx, String no) {
        var row = intents.lock(ctx.tenantNo(), no); var p = read(row);
        var actual = participation.getObject().query(ctx, p.source());
        if (!p.permit().reservationNo().equals(actual.reservationNo()) || !p.source().equals(actual.sourceRef())) {
            conflict();
        }
        String status = switch (actual.status()) {
            case CONFIRMED -> "CREDITED";
            case RELEASED -> "CANCELLED";
            default -> throw BizException.of(ErrorCode.CONFLICT);
        };
        if (status.equals(row.getStatus())) return;
        if (!"PENDING".equals(row.getStatus()) || !p.rechargeNo().equals(actual.confirmedFactNo())
                && actual.status() == ActivityParticipationPort.State.CONFIRMED) conflict();
        if (intents.save(ctx.tenantNo(), no, status, row.getPlanSnapshot()) != 1) conflict();
    }
    @Override public void validateParticipation(EntitlementContext ctx, ReserveActivity command) {
        var plan = find(ctx.tenantNo(), command.sourceRef().sourceNo()); requireSource(plan, command.sourceRef());
        if (!plan.context().equals(ctx) || !plan.participation().equals(command)) conflict();
    }
    @Override public void requireSource(RechargeIntentPlan p, SourceRef s) {
        if (!p.source().equals(s)) conflict();
    }
    private RechargeIntentPlan read(PmtRechargeIntent row) {
        if (row == null) notFound();
        return json.readValue(row.getPlanSnapshot(), RechargeIntentPlan.class);
    }
    private RechargeIntentPlan replay(PmtRechargeIntent row, EntitlementContext ctx, String entity, String payload) {
        if (!row.getOperatorNo().equals(ctx.actorNo()) || !row.getEntityNo().equals(entity)
                || !row.getPayloadHash().equals(payload)) conflict();
        var plan = read(row); plan.fact(); return plan;
    }
    private static String hash(String value) { return ActivityCommandDeduplicator.hash(value); }
    private static void notFound() { throw BizException.of(ErrorCode.NOT_FOUND); }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
