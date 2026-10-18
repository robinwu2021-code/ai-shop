package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import ai.neargo.shop.promotion.entity.PmtActivityReservation;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityReservationMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityBudgetMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityParticipantMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.policy.ActivityEligibilityPolicy;
import ai.neargo.shop.promotion.policy.PromotionFeatureGate;
import ai.neargo.shop.promotion.service.ActivityParticipationService;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.promotion.ActivityParticipationOutcomePort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ConfirmParticipation;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationView;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReleaseParticipation;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.State;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** One core transaction owns reservation, global budget and personal capacity. */
@Service
public class ActivityParticipationServiceImpl implements ActivityParticipationService {
    private static final Logger log = LoggerFactory.getLogger(ActivityParticipationServiceImpl.class);
    private final ActivityMapper heads;
    private final PmtActivityVersionMapper versions;
    private final PmtActivityReservationMapper reservations;
    private final PmtActivityBudgetMapper budgets;
    private final PmtActivityParticipantMapper participants;
    private final ActivityEligibilityPolicy eligibility;
    private final PromotionFeatureGate features;
    private final ObjectProvider<ActivityParticipationOutcomePort> outcomes;
    private final ObjectProvider<ActivityParticipationService> self;
    private final ObjectMapper json;
    public ActivityParticipationServiceImpl(ActivityMapper heads, PmtActivityVersionMapper versions,
            PmtActivityReservationMapper reservations, PmtActivityBudgetMapper budgets,
            PmtActivityParticipantMapper participants, ActivityEligibilityPolicy eligibility,
            PromotionFeatureGate features, ObjectProvider<ActivityParticipationOutcomePort> outcomes,
            ObjectProvider<ActivityParticipationService> self, ObjectMapper json) {
        this.heads=heads; this.versions=versions; this.reservations=reservations; this.budgets=budgets;
        this.participants=participants; this.eligibility=eligibility; this.features=features;
        this.outcomes=outcomes; this.self=self; this.json=json;
    }
    @Override @Transactional("transactionManager")
    public ParticipationPermit reserve(EntitlementContext ctx, ReserveActivity cmd) {
        // Stable source identity excludes revisions; a revised request cannot consume capacity twice.
        String hash = ActivityCommandDeduplicator.hash(json.writeValueAsString(cmd));
        var replay = reservations.selectOne(Wrappers.<PmtActivityReservation>lambdaQuery()
                .eq(PmtActivityReservation::getTenantNo, ctx.tenantNo())
                .eq(PmtActivityReservation::getSourceKey, cmd.sourceRef().grantKey()));
        if (replay != null) {
            if (!hash.equals(replay.getPayloadHash()) || "RELEASED".equals(replay.getStatus())) conflict();
            return json.readValue(replay.getPermitSnapshot(), ParticipationPermit.class);
        }
        features.requireActivity();
        source(cmd.sourceRef()).validateSource(ctx, cmd);
        var head = DataScopeContext.executeWithoutScope(() -> heads.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                .eq(PmtActivity::getTenantNo, ctx.tenantNo()).eq(PmtActivity::getActivityNo, cmd.activityNo())
                .eq(PmtActivity::getEntityNo, cmd.entityNo()).isNull(PmtActivity::getArchivedAt).last("FOR UPDATE")));
        if (head == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var budget = budgets.lock(ctx.tenantNo(), cmd.activityNo());
        if (budget == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var old = reservations.lockSource(ctx.tenantNo(), cmd.sourceRef().grantKey());
        if (old != null) {
            if (!hash.equals(old.getPayloadHash()) || "RELEASED".equals(old.getStatus())) conflict();
            return json.readValue(old.getPermitSnapshot(), ParticipationPermit.class);
        }
        if (!"RUNNING".equals(head.getStatus()) || !cmd.versionNo().equals(head.getCurrentVersionNo())) conflict();
        var version = versions.selectOne(Wrappers.<PmtActivityVersion>lambdaQuery()
                .eq(PmtActivityVersion::getTenantNo, ctx.tenantNo()).eq(PmtActivityVersion::getActivityNo, cmd.activityNo())
                .eq(PmtActivityVersion::getVersionNo, cmd.versionNo()));
        if (version == null || !Objects.equals(budget.getCurrency(), cmd.currency())) conflict();
        var snapshot = json.readValue(version.getRuleSnapshot(), ActivityVersionServiceImpl.PublishedSnapshot.class);
        var d = snapshot.definition();
        eligibility.requireEligible(d, snapshot.audienceRuleSnapshot(), cmd, Instant.now());
        Long pay = null, bonus = null; RedemptionScope scope = d.storeParticipationScope();
        long amount = 0;
        if ("RECHARGE".equals(d.scene())) {
            var tier = d.rule().tiers().stream().filter(t -> t.tierNo().equals(cmd.tierNo())).findFirst()
                    .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
            pay = Math.multiplyExact(tier.payAmountMinor(), cmd.quantity());
            bonus = Math.multiplyExact(tier.bonusAmountMinor(), cmd.quantity());
            amount = bonus; scope = tier.redemptionScope();
        } else {
            if (cmd.tierNo() != null) throw BizException.of(ErrorCode.BAD_REQUEST);
            for (var benefit : d.rule().benefits()) if (benefit.amountMinor() != null)
                amount = Math.addExact(amount, Math.multiplyExact(benefit.amountMinor(), cmd.quantity()));
        }
        changed(budgets.reserve(ctx.tenantNo(),cmd.activityNo(),cmd.quantity(),amount));
        participants.ensure(ctx.tenantNo(),cmd.activityNo(),cmd.userNo());
        changed(participants.reserve(ctx.tenantNo(),cmd.activityNo(),cmd.userNo(),cmd.quantity(),budget.getPerUserLimit()));
        var permit = new ParticipationPermit("AR"+UUID.randomUUID().toString().replace("-",""),cmd.sourceRef(),
                cmd.activityNo(),cmd.versionNo(),cmd.tierNo(),cmd.userNo(),cmd.entityNo(),cmd.storeNo(),cmd.currency(),
                pay,bonus,d.rule().benefits().stream().map(b -> b.templateVersionNo()).filter(Objects::nonNull).toList(),
                scope,version.getRuleHash(),Instant.now());
        var row = new PmtActivityReservation(); row.setTenantNo(ctx.tenantNo()); row.setReservationNo(permit.reservationNo());
        row.setSourceKey(cmd.sourceRef().grantKey()); row.setActivityNo(cmd.activityNo());row.setVersionNo(cmd.versionNo());
        row.setTierNo(cmd.tierNo());row.setUserNo(cmd.userNo());row.setEntityNo(cmd.entityNo());row.setStoreNo(cmd.storeNo());
        row.setCurrency(cmd.currency());row.setQuotaUnits(cmd.quantity());row.setBudgetMinor(amount);row.setStatus("RESERVED");
        row.setPermitSnapshot(json.writeValueAsString(permit));row.setPayloadHash(hash);
        row.setVersion(0L); row.setDeleted(0); row.setCreatedBy(ctx.actorNo());row.setUpdatedBy(ctx.actorNo());
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));row.setUpdatedAt(row.getCreatedAt());
        row.setDeadline(row.getCreatedAt().plusMinutes(15));changed(reservations.insert(row));
        return permit;
    }
    @Override @Transactional("transactionManager")
    public ParticipationView confirm(EntitlementContext ctx, ConfirmParticipation cmd) {
        var fact = source(cmd.sourceRef()).outcome(ctx,cmd.sourceRef());
        if (fact == null || fact.state()!=ActivityParticipationOutcomePort.State.COMMITTED
                || !Objects.equals(ctx.tenantNo(), fact.tenantNo())
                || !Objects.equals(cmd.payFactNo(),fact.factNo())) conflict();
        return settle(ctx,cmd.reservationNo(),cmd.sourceRef(),cmd.payFactNo(),true, fact.binding());
    }
    @Override @Transactional("transactionManager")
    public ParticipationView release(EntitlementContext ctx, ReleaseParticipation cmd) {
        var fact=source(cmd.sourceRef()).outcome(ctx,cmd.sourceRef());
        if(fact==null || fact.state()!=ActivityParticipationOutcomePort.State.ABORTED
                || !Objects.equals(ctx.tenantNo(), fact.tenantNo())
                || !Objects.equals(cmd.payAbortNo(),fact.factNo())) conflict();
        return settle(ctx,cmd.reservationNo(),cmd.sourceRef(),cmd.payAbortNo(),false, fact.binding());
    }
    private ParticipationView settle(EntitlementContext ctx,String no,SourceRef ref,String fact,boolean commit, ReserveActivity binding) {
        // Read identity without a lock, then follow budget -> reservation ordering on every mutation.
        var identity=reservations.selectOne(Wrappers.<PmtActivityReservation>lambdaQuery()
                .eq(PmtActivityReservation::getTenantNo,ctx.tenantNo()).eq(PmtActivityReservation::getReservationNo,no));
        if(identity==null) throw BizException.of(ErrorCode.NOT_FOUND);
        if(budgets.lock(ctx.tenantNo(),identity.getActivityNo())==null) conflict();
        var row=reservations.lock(ctx.tenantNo(),no);
        var permit=json.readValue(row.getPermitSnapshot(),ParticipationPermit.class);
        if(!ref.equals(permit.sourceRef()) || binding == null
                || !ActivityCommandDeduplicator.hash(json.writeValueAsString(binding)).equals(row.getPayloadHash())) conflict();
        String status=commit?"COMMITTED":"RELEASED";
        if(status.equals(row.getStatus())) {
            if(!Objects.equals(fact,row.getPayFactNo())) conflict(); return view(row,ref);
        }
        if(!"RESERVED".equals(row.getStatus())) conflict();
        changed(reservations.settle(ctx.tenantNo(),no,status,fact));
        changed(budgets.settle(ctx.tenantNo(),row.getActivityNo(),row.getQuotaUnits(),row.getBudgetMinor(),
                commit?row.getQuotaUnits():0,commit?row.getBudgetMinor():0));
        changed(participants.settle(ctx.tenantNo(),row.getActivityNo(),row.getUserNo(),row.getQuotaUnits(),commit?row.getQuotaUnits():0));
        row.setStatus(status); row.setPayFactNo(fact);return view(row,ref);
    }
    @Override @Transactional("transactionManager")
    public ParticipationView query(EntitlementContext ctx,SourceRef ref) {
        var row=reservations.lockSource(ctx.tenantNo(),ref.grantKey());
        if(row==null) return new ParticipationView(null,State.NOT_FOUND,ref,0,0,null,null);
        if(!ref.equals(json.readValue(row.getPermitSnapshot(),ParticipationPermit.class).sourceRef())) conflict();
        return view(row,ref);
    }
    private ParticipationView view(PmtActivityReservation row,SourceRef ref) {
        var state="COMMITTED".equals(row.getStatus())?State.CONFIRMED:State.valueOf(row.getStatus());
        boolean held=state==State.RESERVED;
        return new ParticipationView(row.getReservationNo(),state,ref,held?row.getQuotaUnits():0,
                held?row.getBudgetMinor():0,row.getCurrency(),state==State.CONFIRMED?row.getPayFactNo():null);
    }
    private ActivityParticipationOutcomePort source(SourceRef ref) {
        var matching=outcomes.orderedStream().filter(p->p.supports(ref)).toList();
        if(matching.size()!=1) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return matching.getFirst();
    }
    @Override public int recoverPending(int limit) {
        if(limit<1 || limit>1000) throw BizException.of(ErrorCode.BAD_REQUEST);
        int count=0;
        for (var row : reservations.pending(limit)) {
            boolean settled = false;
            try {
                var permit = json.readValue(row.getPermitSnapshot(), ParticipationPermit.class);
                var ctx = new EntitlementContext(row.getTenantNo(), "SYSTEM",
                        "reconcile-" + row.getReservationNo(), row.getPayloadHash());
                var fact = source(permit.sourceRef()).outcome(ctx, permit.sourceRef());
                if (fact != null && fact.state() == ActivityParticipationOutcomePort.State.COMMITTED) {
                    self.getObject().confirm(ctx, new ConfirmParticipation(
                            row.getReservationNo(), permit.sourceRef(), fact.factNo()));
                    settled = true;
                } else if (fact != null && fact.state() == ActivityParticipationOutcomePort.State.ABORTED) {
                    self.getObject().release(ctx, new ReleaseParticipation(
                            row.getReservationNo(), permit.sourceRef(), fact.factNo()));
                    settled = true;
                }
                if (settled) count++;
            } catch (RuntimeException invalidOrUnavailable) {
                // Do not log exception messages: malformed snapshots may include private payload text.
                log.warn("Activity reconciliation deferred tenant={} reservation={}",
                        row.getTenantNo(), row.getReservationNo());
            } finally {
                if (!settled) reservations.defer(row.getTenantNo(), row.getReservationNo(),
                        LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
            }
        }
        return count;
    }
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
    private static void changed(int n){if(n!=1) conflict();}
}
