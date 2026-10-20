package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.config.PromotionFeatureProperties;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityBudget;
import ai.neargo.shop.promotion.entity.PmtActivityParticipant;
import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityBudgetMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityParticipantMapper;
import ai.neargo.shop.promotion.mapper.UnifiedOrderActivityAccountingMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.service.MarketingEffectProjector;
import ai.neargo.shop.promotion.service.UnifiedOrderActivityPricingService;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.spi.marketing.ActivityOrderFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;

/** Unified order discounts use published facts, never mutable drafts or legacy group/flash-price paths. */
@Service
public class UnifiedOrderActivityPricingServiceImpl implements UnifiedOrderActivityPricingService {
    // The current trade checkout is single-tenant MAIN; never discover an owner across tenants.
    private static final String CHECKOUT_TENANT = "MAIN";
    private final ActivityMapper heads;
    private final PmtActivityVersionMapper versions;
    private final PmtActivityBudgetMapper budgets;
    private final PmtActivityParticipantMapper people;
    private final UnifiedOrderActivityAccountingMapper accounting;
    private final ApplyMapper applies;
    private final ActivityOrderFactsPort orders;
    private final MemberQueryPort members;
    private final PromotionFeatureProperties flags;
    private final ObjectMapper json;
    private final MarketingEffectProjector effects;

    public UnifiedOrderActivityPricingServiceImpl(ActivityMapper heads, PmtActivityVersionMapper versions,
            PmtActivityBudgetMapper budgets, PmtActivityParticipantMapper people,
            UnifiedOrderActivityAccountingMapper accounting, ApplyMapper applies, ActivityOrderFactsPort orders,
            MemberQueryPort members, PromotionFeatureProperties flags,
            ObjectMapper json, MarketingEffectProjector effects) {
        this.heads=heads; this.versions=versions; this.budgets=budgets; this.people=people;
        this.accounting=accounting; this.applies=applies; this.orders=orders;
        this.members=members; this.flags=flags; this.json=json; this.effects=effects;
    }

    public List<CampaignPort.AppliedActivity> candidates(String userNo, List<CampaignPort.MerchantAmount> groups) {
        if (!flags.getActivity().isEnabled() || userNo == null) return List.of();
        return DataScopeContext.executeWithoutScope(() -> {
            List<CampaignPort.AppliedActivity> result = new ArrayList<>();
            for (var group : groups) {
                if (group.storeNo() == null) continue; // new protocol requires an actual fulfillment store
                for (var head : heads.selectList(Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getTenantNo,CHECKOUT_TENANT)
                        .eq(PmtActivity::getEntityNo, group.merchantNo()).eq(PmtActivity::getScene,"ORDER")
                        .eq(PmtActivity::getStatus,"RUNNING").isNotNull(PmtActivity::getCurrentVersionNo)
                        .isNotNull(PmtActivity::getDraftSnapshot).isNull(PmtActivity::getArchivedAt))) {
                    var snapshot = snapshot(head);
                    long off = discount(snapshot.definition(), group, Instant.now());
                    if (off > 0 && audience(snapshot, head.getEntityNo(), userNo) && capacity(head, userNo, off)) {
                        result.add(new CampaignPort.AppliedActivity(head.getActivityNo(), group.merchantNo(),
                                off, 1, 0L, null, snapshot.definition().name()).atStore(group.storeNo()));
                    }
                }
            }
            return result;
        });
    }

    /** Returns true for unified activities, including rejected unified requests (which throw). */
    @Transactional(value="transactionManager", propagation=Propagation.MANDATORY)
    public boolean commit(String userNo, String orderNo, CampaignPort.AppliedActivity selected) {
        return DataScopeContext.executeWithoutScope(() -> {
            var preflight = head(selected.activityNo(), false);
            if (preflight == null || preflight.getDraftSnapshot() == null) return false;
            var snapshot=snapshot(preflight);
            var sub=orders.find(preflight.getTenantNo(),orderNo,preflight.getEntityNo(),selected.storeNo());
            boolean audienceMatches=audience(snapshot,preflight.getEntityNo(),userNo);
            // Cross-owner reads finish before taking promotion resource locks.
            var head = head(selected.activityNo(), true);
            if (head==null || !Objects.equals(head.getCurrentVersionNo(),preflight.getCurrentVersionNo())
                    || !Objects.equals(head.getEntityNo(),preflight.getEntityNo())) unavailable();
            if (!"ORDER".equals(head.getScene()) || !Objects.equals(head.getEntityNo(),selected.merchantNo())
                    || selected.storeNo()==null || selected.qty()!=1 || selected.platformMinor()!=0
                    || selected.enrollmentNo()!=null) unavailable();
            // The head lock serializes retries, including a retry after a cancellation.
            var existing = applies.selectList(Wrappers.<PmtApply>lambdaQuery()
                    .eq(PmtApply::getTenantNo,head.getTenantNo()).eq(PmtApply::getPromoType,PmtApply.ACTIVITY)
                    .eq(PmtApply::getPromoNo,head.getActivityNo()).eq(PmtApply::getOrderNo,orderNo)
                    .eq(PmtApply::getStoreNo,selected.storeNo()));
            if (!existing.isEmpty()) {
                var prior=existing.getFirst();
                if (prior.getRevertedAt()!=null || !Objects.equals(prior.getUserNo(),userNo)
                        || !Objects.equals(prior.getAmountMinor(),selected.amountMinor())) unavailable();
                return true;
            }
            if (!flags.getActivity().isEnabled() || !"RUNNING".equals(head.getStatus())
                    || head.getArchivedAt()!=null) unavailable();
            if (sub==null || !"WAIT_PAY".equals(sub.status()) || !Objects.equals(userNo,sub.userNo())) unavailable();
            long off=discount(snapshot.definition(), new CampaignPort.MerchantAmount(selected.merchantNo(),
                    sub.goodsAmountMinor(),sub.goodsQuantity(),selected.storeNo()),Instant.now());
            if (off<=0 || off!=selected.amountMinor() || !audienceMatches) unavailable();
            var budget=budgets.lock(head.getTenantNo(),head.getActivityNo());
            if (budget==null || !Objects.equals(budget.getCurrency(),snapshot.definition().currency())) unavailable();
            people.ensure(head.getTenantNo(),head.getActivityNo(),userNo);
            changed(budgets.reserve(head.getTenantNo(),head.getActivityNo(),1,off));
            changed(people.reserve(head.getTenantNo(),head.getActivityNo(),userNo,1,budget.getPerUserLimit()));
            changed(budgets.settle(head.getTenantNo(),head.getActivityNo(),1,off,1,off));
            changed(people.settle(head.getTenantNo(),head.getActivityNo(),userNo,1,1));
            var row=new PmtApply(); row.setTenantNo(head.getTenantNo());
            row.setApplyNo(BizKey.next(BizKey.PROMO_APPLY)); row.setPromoType(PmtApply.ACTIVITY);
            row.setPromoNo(head.getActivityNo()); row.setOrderNo(orderNo); row.setSubOrderNo(sub.subOrderNo());
            row.setUserNo(userNo); row.setEntityNo(selected.merchantNo()); row.setStoreNo(selected.storeNo());
            row.setAmountMinor(off); row.setRedeemMode(PmtCoupon.REDEEM_ORDER);
            row.setFunder(PmtCoupon.BY_MERCHANT); row.setAppliedAt(System.currentTimeMillis());
            changed(applies.insert(row));
            effects.recordOrderDiscount(head.getTenantNo(),head.getActivityNo(),row.getApplyNo(),head.getEntityNo(),
                    selected.storeNo(),off,snapshot.definition().currency(),Instant.ofEpochMilli(row.getAppliedAt()));
            return true;
        });
    }

    @Transactional(value="transactionManager", propagation=Propagation.MANDATORY)
    public boolean release(PmtApply row) {
        return DataScopeContext.executeWithoutScope(() -> {
            var preflight=head(row.getPromoNo(),false);
            if (preflight==null || preflight.getDraftSnapshot()==null) return false;
            var sub=orders.find(preflight.getTenantNo(),row.getOrderNo(),row.getEntityNo(),row.getStoreNo());
            var head=head(row.getPromoNo(),true);
            if (head==null || !Objects.equals(head.getCurrentVersionNo(),preflight.getCurrentVersionNo())) unavailable();
            var current=applies.selectById(row.getId());
            if (current==null || current.getRevertedAt()!=null) return true;
            if (sub==null || !"CANCELLED".equals(sub.status())
                    || !Objects.equals(sub.subOrderNo(),current.getSubOrderNo())) unavailable();
            budgets.lock(head.getTenantNo(),head.getActivityNo());
            changed(accounting.reverseBudget(head.getTenantNo(),head.getActivityNo(),current.getAmountMinor()));
            changed(accounting.reverseParticipant(head.getTenantNo(),head.getActivityNo(),current.getUserNo()));
            current.setRevertedAt(System.currentTimeMillis()); changed(applies.updateById(current));
            return true;
        });
    }

    private PmtActivity head(String no,boolean lock) {
        return heads.selectOne(Wrappers.<PmtActivity>lambdaQuery().eq(PmtActivity::getTenantNo,CHECKOUT_TENANT)
                .eq(PmtActivity::getActivityNo,no)
                .last(lock ? "FOR UPDATE" : ""));
    }
    private ActivityVersionServiceImpl.PublishedSnapshot snapshot(PmtActivity head) {
        var version=versions.selectOne(Wrappers.<PmtActivityVersion>lambdaQuery()
                .eq(PmtActivityVersion::getTenantNo,head.getTenantNo())
                .eq(PmtActivityVersion::getActivityNo,head.getActivityNo())
                .eq(PmtActivityVersion::getVersionNo,head.getCurrentVersionNo()));
        if (version==null) throw BizException.of(ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
        return json.readValue(version.getRuleSnapshot(),ActivityVersionServiceImpl.PublishedSnapshot.class);
    }
    private boolean audience(ActivityVersionServiceImpl.PublishedSnapshot snapshot,String entity,String user) {
        return snapshot.audienceRuleSnapshot()==null || members.matchesRule(entity,user,snapshot.audienceRuleSnapshot());
    }
    private boolean capacity(PmtActivity head,String user,long off) {
        var b=budgets.selectOne(Wrappers.<PmtActivityBudget>lambdaQuery()
                .eq(PmtActivityBudget::getTenantNo,head.getTenantNo()).eq(PmtActivityBudget::getActivityNo,head.getActivityNo()));
        if (b==null || (b.getQuotaLimit()!=null && b.getQuotaLimit()-b.getQuotaHeld()-b.getQuotaUsed()<1)
                || (b.getBudgetLimitMinor()!=null && b.getBudgetLimitMinor()-b.getBudgetHeldMinor()-b.getBudgetUsedMinor()<off)) return false;
        var p=people.selectOne(Wrappers.<PmtActivityParticipant>lambdaQuery()
                .eq(PmtActivityParticipant::getTenantNo,head.getTenantNo()).eq(PmtActivityParticipant::getActivityNo,head.getActivityNo())
                .eq(PmtActivityParticipant::getUserNo,user));
        return p==null || b.getPerUserLimit()-p.getHeldCount()-p.getUsedCount()>0;
    }
    /** Existing checkout has no currency context; only its actual CNY fixed-cut contract is executable. */
    public static boolean supportsDefinition(UnifiedActivityDefinition definition) {
        return "ORDER".equals(definition.scene()) && "CNY".equals(definition.currency())
                && definition.rule().tiers().isEmpty() && !definition.rule().benefits().isEmpty()
                && definition.rule().benefits().stream().allMatch(benefit -> "CUT".equals(benefit.kind()))
                && List.of("NONE", "AMOUNT", "QTY").contains(definition.rule().trigger().type());
    }

    /** Fixed cuts are the only unified effect this adapter owns; no flash/gift/group widening. */
    static long discount(UnifiedActivityDefinition d,CampaignPort.MerchantAmount group,Instant now) {
        if (!supportsDefinition(d) || group.goodsAmount()<=0 || group.storeNo()==null) return 0;
        var scope=d.storeParticipationScope();
        if (!"ALL".equals(scope.mode()) && !("STORES".equals(scope.mode()) && scope.refs().contains(group.storeNo()))) return 0;
        var schedule=d.schedule();
        if (now.isBefore(schedule.startAt()) || (schedule.endAt()!=null && !now.isBefore(schedule.endAt()))) return 0;
        if ("RECURRING".equals(schedule.type())) {
            var local=now.atZone(ZoneId.of(schedule.zone()));
            if (!schedule.weekdays().contains(local.getDayOfWeek().getValue())
                    || local.toLocalTime().isBefore(LocalTime.parse(schedule.from()))
                    || !local.toLocalTime().isBefore(LocalTime.parse(schedule.to()))) return 0;
        }
        var trigger=d.rule().trigger();
        boolean matches=switch(trigger.type()) {
            case "NONE" -> true;
            case "AMOUNT" -> trigger.amountMinor()!=null && group.goodsAmount()>=trigger.amountMinor();
            case "QTY" -> trigger.quantity()!=null && group.goodsQty()>=trigger.quantity();
            default -> false;
        };
        if (!matches) return 0;
        long off=0;
        for (var b:d.rule().benefits()) off=Math.addExact(off,Objects.requireNonNull(b.amountMinor()));
        return Math.min(off,group.goodsAmount());
    }
    private static void changed(int count) { if(count!=1) unavailable(); }
    private static void unavailable() { throw BizException.of(ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE); }
}
