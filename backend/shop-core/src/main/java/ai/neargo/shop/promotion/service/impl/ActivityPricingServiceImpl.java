package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import ai.neargo.shop.promotion.entity.PmtActivityRule;
import ai.neargo.shop.promotion.entity.PmtEnrollment;
import ai.neargo.shop.promotion.entity.PmtEnrollmentGoods;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.policy.ActivityRuleValidator;
import ai.neargo.shop.spi.marketing.CounterCampaignContract;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import java.time.Instant;
import java.time.LocalTime;
import ai.neargo.shop.promotion.entity.PmtActivityAudience;
import ai.neargo.shop.promotion.entity.PmtActivityGoods;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityAudienceMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityGoodsMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.promotion.service.UnifiedOrderActivityPricingService;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.member.MemberQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

@Service
public class ActivityPricingServiceImpl implements ActivityPricingService {

    @Autowired
    @Qualifier("sqlSessionFactory")
    private SqlSessionFactory counterSessions;

    private void currentCounterReads() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(checkoutSource)) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        var session = SqlSessionUtils.getSqlSession(counterSessions);
        try {
            session.clearCache();
        } finally {
            SqlSessionUtils.closeSqlSession(session, counterSessions);
        }
    }

    @Autowired
    @Qualifier("dataSource")
    private DataSource checkoutSource;

    @Autowired
    private PmtActivityVersionMapper counterVersions;
    @Autowired
    private ObjectMapper counterJson;

    @Override
    public void rejectUnsupportedCounterChoices(List<CampaignPort.MerchantAmount> groups,
            Map<String, String> choices) {
        rejectUnsupportedCounterChoices(groups, choices, System.currentTimeMillis());
    }

    @Override
    public void rejectUnsupportedCounterChoices(List<CampaignPort.MerchantAmount> groups,
            Map<String, String> choices, long ownerNowMillis) {
        var canonical = CounterCampaignContract.canonicalGroups(groups);
        var choice = CounterCampaignContract.resolveChoices(canonical, choices).getFirst();
        probeCounter(canonical.getFirst(), choice.mode(), choice.activityNo(), ownerNowMillis, false);
    }

    @Override
    public void rejectUnsupportedCounterPriceEffects(List<CampaignPort.MerchantAmount> groups, long ownerNowMillis) {
        probeCounter(CounterCampaignContract.canonicalGroups(groups).getFirst(), "NONE", null,
                ownerNowMillis, true);
    }

    private void probeCounter(CampaignPort.MerchantAmount group, String mode, String explicit,
            long now, boolean mandatoryOnly) {
        currentCounterReads();
        try {
            DataScopeContext.executeWithoutScope(() -> {
                var heads = activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getTenantNo, "MAIN").eq(PmtActivity::getDeleted, 0)
                        .eq(PmtActivity::getEntityNo, group.merchantNo()));
                var storeIds = new HashSet<>(activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getTenantNo, "MAIN").eq(PmtActivity::getDeleted, 0)
                        .eq(PmtActivity::getEntityNo, group.merchantNo())
                        .eq(PmtActivity::getStoreNo, group.storeNo())).stream().map(PmtActivity::getId).toList());
                for (var head : heads) {
                    if (head.getStoreNo() != null && !head.getStoreNo().isBlank()
                            && !storeIds.contains(head.getId())) {
                        continue;
                    }
                    if (!mandatoryOnly && explicit != null && explicit.equals(head.getActivityNo())) {
                        throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
                    }
                    requireCounterEffect(head, group, now, mandatoryOnly || !"AUTO".equals(mode));
                }
                if (enrollmentMapper == null || enrollmentGoodsMapper == null) {
                    throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
                }
                // Capacity and audience are not evidence of objective irrelevance.
                var enrollments = enrollmentMapper.selectList(Wrappers.<PmtEnrollment>lambdaQuery()
                        .eq(PmtEnrollment::getTenantNo, "MAIN").eq(PmtEnrollment::getDeleted, 0)
                        .eq(PmtEnrollment::getEntityNo, group.merchantNo())
                        .eq(PmtEnrollment::getStatus, PmtEnrollment.APPROVED));
                for (var enrollment : enrollments) {
                    var enrolled = enrollmentGoodsMapper.selectList(Wrappers.<PmtEnrollmentGoods>lambdaQuery()
                            .eq(PmtEnrollmentGoods::getTenantNo, "MAIN").eq(PmtEnrollmentGoods::getDeleted, 0)
                            .eq(PmtEnrollmentGoods::getEnrollmentNo, enrollment.getEnrollmentNo()))
                            .stream().map(PmtEnrollmentGoods::getGoodsNo).toList();
                    var lines = group.lines().stream().filter(l -> enrolled.contains(l.goodsNo())).toList();
                    if (lines.isEmpty()) {
                        continue;
                    }
                    long amount = 0;
                    int qty = 0;
                    for (var line : lines) {
                        amount = Math.addExact(amount, line.amount());
                        qty = Math.addExact(qty, line.qty());
                    }
                    var head = activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                            .eq(PmtActivity::getTenantNo, "MAIN").eq(PmtActivity::getDeleted, 0)
                            .eq(PmtActivity::getOwner, PmtActivity.OWNER_PLATFORM)
                            .eq(PmtActivity::getActivityNo, enrollment.getActivityNo()));
                    if (head == null) {
                        throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
                    }
                    if (!mandatoryOnly && explicit != null && explicit.equals(head.getActivityNo())) {
                        throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
                    }
                    requireCounterEffect(head, new CampaignPort.MerchantAmount(group.merchantNo(), amount, qty,
                            group.storeNo(), lines, false), now, mandatoryOnly || !"AUTO".equals(mode));
                }
                return null;
            });
        } catch (BizException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
    }

    private void requireCounterEffect(PmtActivity head, CampaignPort.MerchantAmount group,
            long now, boolean mandatoryOnly) {
        if (!PmtActivity.RUNNING.equals(head.getStatus()) || head.getArchivedAt() != null) {
            return;
        }
        if (head.getCurrentVersionNo() != null && head.getDraftSnapshot() != null) {
            var version = counterVersions.selectOne(Wrappers.<PmtActivityVersion>lambdaQuery()
                    .eq(PmtActivityVersion::getTenantNo, "MAIN").eq(PmtActivityVersion::getDeleted, 0)
                    .eq(PmtActivityVersion::getActivityNo, head.getActivityNo())
                    .eq(PmtActivityVersion::getVersionNo, head.getCurrentVersionNo()));
            if (version == null) {
                throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
            }
            ActivityVersionServiceImpl.PublishedSnapshot snapshot =
                    counterJson.readerFor(ActivityVersionServiceImpl.PublishedSnapshot.class)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(version.getRuleSnapshot());
            publishedCounterEffect(snapshot.definition(), group, now, mandatoryOnly);
            return;
        }
        if (!head.isActiveAt(now, MARKET_ZONE)) {
            return;
        }
        var scopeRows = goodsMapper.selectList(Wrappers.<PmtActivityGoods>lambdaQuery()
                .eq(PmtActivityGoods::getTenantNo, "MAIN").eq(PmtActivityGoods::getDeleted, 0)
                .eq(PmtActivityGoods::getActivityNo, head.getActivityNo()));
        boolean scopeHit = scopeRows.isEmpty() || scopeRows.stream().anyMatch(row ->
                PmtActivityGoods.ALL.equals(row.getScopeType())
                        || PmtActivityGoods.CATEGORY.equals(row.getScopeType())
                        || PmtActivityGoods.GOODS.equals(row.getScopeType())
                        && group.lines().stream().anyMatch(line -> row.getRefNo().equals(line.goodsNo())));
        if (!scopeHit) {
            return;
        }
        if (scopeRows.stream().anyMatch(row -> !List.of("ALL", "CATEGORY", "GOODS")
                .contains(row.getScopeType()))) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        if (PmtActivity.BENEFIT_COMBO.equals(head.getBenefitType())) {
            if (ruleMapper == null) {
                throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
            }
            if (counterComboEffect(head, group, mandatoryOnly)) {
                throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
            }
            return;
        }
        boolean mandatory = PmtActivity.BENEFIT_PRICE.equals(head.getBenefitType())
                || PmtActivity.BENEFIT_GIFT.equals(head.getBenefitType());
        if (mandatory && scopeRows.isEmpty()) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        if (mandatory || !mandatoryOnly && (cutTriggerHits(head, group)
                && (!PmtActivity.BENEFIT_CUT.equals(head.getBenefitType())
                        || nz(head.getBenefitAmountMinor()) > 0)
                || !List.of("NONE", "AMOUNT", "QTY").contains(head.getTriggerType() == null
                        ? "NONE" : head.getTriggerType()))) {
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        }
    }

    private boolean counterComboEffect(PmtActivity head, CampaignPort.MerchantAmount group, boolean mandatoryOnly) {
        var rows = ruleMapper.selectList(Wrappers.<PmtActivityRule>lambdaQuery()
                .eq(PmtActivityRule::getTenantNo, "MAIN").eq(PmtActivityRule::getDeleted, 0)
                .eq(PmtActivityRule::getActivityNo, head.getActivityNo()).orderByAsc(PmtActivityRule::getSeq));
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        var parameters = rows.stream().map(row -> counterJson.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(row.getParams())).toList();
        var lines = group.lines();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var params = parameters.get(index);
            if (!params.isObject()) {
                throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
            }
            if ("CONDITION".equals(row.getKind()) && "GOODS".equals(row.getRuleType())) {
                var goods = params.get("goodsNos");
                if (goods == null || !goods.isArray() || goods.isEmpty()) {
                    throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
                }
                var refs = new HashSet<String>();
                for (var value : goods) {
                    if (!value.isString() || value.asString().isBlank()
                            || !value.asString().equals(value.asString().strip())) {
                        throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
                    }
                    refs.add(value.asString());
                }
                lines = lines.stream().filter(line -> refs.contains(line.goodsNo())).toList();
            }
        }
        if (lines.isEmpty()) {
            return false;
        }
        long amount = 0;
        int quantity = 0;
        for (var line : lines) {
            amount = Math.addExact(amount, line.amount());
            quantity = Math.addExact(quantity, line.qty());
        }
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var params = parameters.get(index);
            if ("CONDITION".equals(row.getKind())) {
                switch (row.getRuleType()) {
                    case "GOODS" -> { }
                    case "AMOUNT" -> {
                        if (amount < counterPositive(params, "amountMinor")) {
                            return false;
                        }
                    }
                    case "QTY" -> {
                        if (quantity < counterPositive(params, "n")) {
                            return false;
                        }
                    }
                    default -> throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
                }
            } else if (!"BENEFIT".equals(row.getKind())) {
                throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
            }
        }
        long off = 0;
        boolean effect = false;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var params = parameters.get(index);
            if (!"BENEFIT".equals(row.getKind())) {
                continue;
            }
            switch (row.getRuleType()) {
                case "CUT" -> off = Math.addExact(off, Math.min(counterPositive(params, "amountMinor"), amount - off));
                case "PERCENT" -> {
                    var bp = params.get("bp");
                    if (bp == null || !bp.isIntegralNumber() || bp.longValue() < 0 || bp.longValue() > 10000) {
                        throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
                    }
                    long cut = Math.multiplyExact(amount - off, 10000 - bp.longValue()) / 10000;
                    off = Math.addExact(off, Math.min(cut, counterPositive(params, "capMinor")));
                }
                case "POINTS" -> effect |= counterPositive(params, "n") > 0 && !mandatoryOnly;
                case "PRICE", "GIFT" -> effect = true;
                case "COUPON", "ENTITLEMENT" -> effect |= !mandatoryOnly;
                default -> throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
            }
        }
        return effect || !mandatoryOnly && off > 0;
    }

    private static long counterPositive(JsonNode params, String field) {
        var value = params.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        return value.longValue();
    }

    private void publishedCounterEffect(UnifiedActivityDefinition definition, CampaignPort.MerchantAmount group,
            long nowMillis, boolean mandatoryOnly) {
        try {
            new ActivityRuleValidator().validate(definition);
        } catch (RuntimeException malformed) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        var scope = definition.storeParticipationScope();
        if (!"ALL".equals(scope.mode()) && !scope.refs().contains(group.storeNo())) {
            return;
        }
        var now = Instant.ofEpochMilli(nowMillis);
        var schedule = definition.schedule();
        if (now.isBefore(schedule.startAt()) || schedule.endAt() != null && !now.isBefore(schedule.endAt())) {
            return;
        }
        if ("RECURRING".equals(schedule.type())) {
            var local = now.atZone(ZoneId.of(schedule.zone()));
            if (!schedule.weekdays().contains(local.getDayOfWeek().getValue())
                    || local.toLocalTime().isBefore(LocalTime.parse(schedule.from()))
                    || !local.toLocalTime().isBefore(LocalTime.parse(schedule.to()))) {
                return;
            }
        }
        var trigger = definition.rule().trigger();
        if ("AMOUNT".equals(trigger.type()) && group.goodsAmount() < trigger.amountMinor()
                || "QTY".equals(trigger.type()) && group.goodsQty() < trigger.quantity()) {
            return;
        }
        boolean effect = false;
        for (var benefit : definition.rule().benefits()) {
            boolean mandatory = List.of("PRICE", "GIFT").contains(benefit.kind());
            if ((mandatory || !mandatoryOnly) && (!"CUT".equals(benefit.kind())
                    || benefit.amountMinor() == null || benefit.amountMinor() > 0)) {
                effect = true;
            }
        }
        if (effect || !mandatoryOnly && !definition.rule().tiers().isEmpty()) {
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        }
    }

    @Override
    public void rejectUnsupportedCounterPriceEffects(Collection<String> goodsNos) {
        if (goodsNos == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (goodsNos.isEmpty()) {
            return;
        }
        DataScopeContext.executeWithoutScope(() -> {
            long now = System.currentTimeMillis();
            var references = goodsMapper.selectList(Wrappers.<PmtActivityGoods>lambdaQuery()
                    .eq(PmtActivityGoods::getTenantNo, "MAIN").eq(PmtActivityGoods::getDeleted, 0)
                    .in(PmtActivityGoods::getRefNo, goodsNos));
            for (var reference : references) {
                var head = activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getTenantNo, "MAIN").eq(PmtActivity::getDeleted, 0)
                        .eq(PmtActivity::getActivityNo, reference.getActivityNo()));
                if (head != null && head.getArchivedAt() == null && head.isActiveAt(now, MARKET_ZONE)
                        && (head.getCurrentVersionNo() != null
                                || List.of("PRICE", "GIFT", "COMBO").contains(head.getBenefitType()))) {
                    throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
                }
            }
            return null;
        });
    }

    @Override
    public void requireOrdinarySelection(String buyerNo, CampaignPort.Discount selected) {
        boolean locked = TransactionSynchronizationManager.isActualTransactionActive();
        if (locked && !TransactionSynchronizationManager.hasResource(checkoutSource)) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
        DataScopeContext.executeWithoutScope(
                () -> {
                    for (var selectedRow :
                            selected.applied().stream()
                                    .sorted(
                                            Comparator.comparing(
                                                    CampaignPort.AppliedActivity::activityNo))
                                    .toList()) {
                        var row =
                                activityMapper.selectOne(
                                        Wrappers.<PmtActivity>lambdaQuery()
                                                .eq(PmtActivity::getTenantNo, "MAIN")
                                                .eq(
                                                        PmtActivity::getActivityNo,
                                                        selectedRow.activityNo())
                                                .last(locked ? "FOR UPDATE" : ""));
                        boolean ordinary =
                                row != null
                                        && (row.getCurrentVersionNo() == null
                                                ? PmtActivity.BENEFIT_CUT.equals(
                                                        row.getBenefitType())
                                                : unifiedOrders != null
                                                        && unifiedOrders.supportsOrdinarySelection(
                                                                row.getActivityNo()));
                        if (!ordinary) {
                            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
                        }
                    }
                });
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ActivityPricingServiceImpl.class);

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    private final ActivityMapper activityMapper;
    private final ActivityAudienceMapper audienceMapper;
    private final ActivityGoodsMapper goodsMapper;
    private final MemberQueryPort memberPort;
    /** 活动效果按 promo_no 聚合，读的就是这张表 —— 与券的每一次使用同一张 */
    private final ApplyMapper applyMapper;

    private UnifiedOrderActivityPricingService unifiedOrders;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUnifiedOrders(UnifiedOrderActivityPricingService unifiedOrders) {
        this.unifiedOrders = unifiedOrders;
    }


    /**
     * 平台活动的报名单与报名的货（P3）。setter 注入：切片测试里没有它们时，平台活动不参与算价，
     * 其余行为与加这一段之前逐字相同。
     */
    private ai.neargo.shop.promotion.mapper.PromotionMappers.EnrollmentMapper enrollmentMapper;
    private ai.neargo.shop.promotion.mapper.PromotionMappers.EnrollmentGoodsMapper enrollmentGoodsMapper;

    /** 自己组合的行（P3b）。setter 注入，缺了组合活动不参与算价 */
    private ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityRuleMapper ruleMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuleMapper(ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEnrollmentMappers(ai.neargo.shop.promotion.mapper.PromotionMappers.EnrollmentMapper enrollmentMapper,
                                     ai.neargo.shop.promotion.mapper.PromotionMappers.EnrollmentGoodsMapper enrollmentGoodsMapper) {
        this.enrollmentMapper = enrollmentMapper;
        this.enrollmentGoodsMapper = enrollmentGoodsMapper;
    }

    /**
     * 券名只在 {@link #appliedOf} 里用到（订单详情回查那一条券叫什么）。
     * **setter 注入**：切片测试里没有它时，券那一条就不给名字，其余行为一字不差。
     */
    private ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper couponMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCouponMapper(ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper couponMapper) {
        this.couponMapper = couponMapper;
    }

    /** 券那一行反查模板号用（见 nameOfPromo）。setter 注入，理由同 couponMapper */
    private ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper userCouponMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUserCouponMapper(ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper m) {
        this.userCouponMapper = m;
    }

    public ActivityPricingServiceImpl(ActivityMapper activityMapper,
                                      ActivityAudienceMapper audienceMapper,
                                      ActivityGoodsMapper goodsMapper,
                                      MemberQueryPort memberPort,
                                      ApplyMapper applyMapper) {
        this.applyMapper = applyMapper;
        this.activityMapper = activityMapper;
        this.audienceMapper = audienceMapper;
        this.goodsMapper = goodsMapper;
        this.memberPort = memberPort;
    }

    @Override
    public CampaignPort.Discount autoDiscount(String userNo,
                                              List<CampaignPort.MerchantAmount> groups) {
        // 与顾客「一个都没选」同一条路：每家店取最优（CampaignPort.pick 里写着取法）
        return CampaignPort.pick(candidates(userNo, groups), java.util.Map.of());
    }

    /**
     * 商品页的活动标签。<b>只列全主体的</b>（门店级活动要知道顾客在哪家店下单，商品页不知道）；
     * 只列减钱类（满额减 / 满件减 / 无门槛减）—— 组合、特价、买赠各有各的露出。
     */
    @Override
    public List<CampaignPort.ActivityTag> activityTags(String merchantNo) {
        List<CampaignPort.ActivityTag> out = new ArrayList<>();
        if (merchantNo == null) {
            return out;
        }
        for (PmtActivity a : live(merchantNo, null, System.currentTimeMillis())) {
            if (!PmtActivity.BENEFIT_CUT.equals(a.getBenefitType())) {
                continue;
            }
            String trig = a.getTriggerType() == null ? PmtActivity.TRIGGER_NONE : a.getTriggerType();
            if (!PmtActivity.TRIGGER_NONE.equals(trig) && !PmtActivity.TRIGGER_AMOUNT.equals(trig)
                    && !PmtActivity.TRIGGER_QTY.equals(trig)) {
                continue;
            }
            boolean newOnly = DataScopeContext.executeWithoutScope(() -> audienceMapper.selectList(
                    Wrappers.<PmtActivityAudience>lambdaQuery().eq(PmtActivityAudience::getActivityNo, a.getActivityNo())))
                    .stream().anyMatch(r -> PmtActivityAudience.NON_MEMBER.equals(r.getAudienceType()));
            out.add(new CampaignPort.ActivityTag(a.getActivityNo(), a.getName(), nz(a.getBenefitAmountMinor()),
                    PmtActivity.TRIGGER_AMOUNT.equals(trig) ? nz(a.getTriggerAmountMinor()) : 0L,
                    PmtActivity.TRIGGER_QTY.equals(trig) && a.getTriggerQty() != null ? a.getTriggerQty() : 0,
                    newOnly));
        }
        return out;
    }

    @Override
    public boolean ownsActivity(String activityNo) {
        Long n = DataScopeContext.executeWithoutScope(() -> activityMapper.selectCount(
                Wrappers.<PmtActivity>lambdaQuery().eq(PmtActivity::getActivityNo, activityNo)));
        return n != null && n > 0;
    }

    /**
     * 每家店命中的<b>全部</b>活动（优惠券全链路梳理 批 2）。
     *
     * <p>此前这里只算出每家店「最优的那一个」—— 顾客没法说「这家店的满减我不要」。
     * 现在先列全、再由 {@link CampaignPort#pick} 按选择（或默认最优）挑一个。
     * 候选的顺序就是原来比较的顺序（本店活动在前、平台活动在后），
     * 所以没人选的时候挑出来的与改造前一模一样。
     *
     * <p>只送积分、不减钱的组合（金额 0）也列进来：没有别的活动时它照样生效。
     */
    @Override
    public List<CampaignPort.AppliedActivity> candidates(String userNo,
                                                         List<CampaignPort.MerchantAmount> groups) {
        List<CampaignPort.AppliedActivity> out = new ArrayList<>();
        if (groups == null || groups.isEmpty()) {
            return out;
        }
        long now = System.currentTimeMillis();
        for (CampaignPort.MerchantAmount g : groups) {
            for (PmtActivity a : live(g.merchantNo(), g.storeNo(), now)) {
                if (PmtActivity.BENEFIT_COMBO.equals(a.getBenefitType())) {
                    if (!audienceHits(a, g.merchantNo(), userNo)) {
                        continue;
                    }
                    ComboHit h = comboHit(a, g);
                    if (h != null && (g.pointsRewardsEnabled() || h.points() == 0)
                            && (h.off() > 0 || h.points() > 0)) {
                        out.add(new CampaignPort.AppliedActivity(a.getActivityNo(), g.merchantNo(),
                                h.off(), 1, 0L, null, a.getName()).atStore(g.storeNo()));
                    }
                    continue;
                }
                if (!PmtActivity.BENEFIT_CUT.equals(a.getBenefitType())
                        || !cutTriggerHits(a, g) || !audienceHits(a, g.merchantNo(), userNo)) {
                    continue;
                }
                // 减不能超过商品额：券那边同一条规矩，否则会减出负数
                long off = Math.min(nz(a.getBenefitAmountMinor()), g.goodsAmount());
                if (off > 0) {
                    out.add(new CampaignPort.AppliedActivity(a.getActivityNo(), g.merchantNo(),
                            off, 1, 0L, null, a.getName()).atStore(g.storeNo()));
                }
            }
            /*
             * 平台活动（P3）与商家活动**同类取最优**：同样的钱，谁出资不影响买家该减多少。
             * 平台活动只按报名里那几件货的小计判门槛与封顶。
             */
            for (PlatformHit h : platformHits(g, now)) {
                if (h.off() > 0) {
                    out.add(new CampaignPort.AppliedActivity(h.activity().getActivityNo(), g.merchantNo(),
                            h.off(), 1, h.platformMinor(), h.enrollmentNo(), h.activity().getName())
                            .atStore(g.storeNo()));
                }
            }
        }
        if (unifiedOrders != null) out.addAll(unifiedOrders.candidates(userNo, groups));
        return out;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void release(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return;
        }
        var rows = DataScopeContext.executeWithoutScope(() -> applyMapper.selectList(
                Wrappers.<ai.neargo.shop.promotion.entity.PmtApply>lambdaQuery()
                        .eq(ai.neargo.shop.promotion.entity.PmtApply::getOrderNo, orderNo)
                        .eq(ai.neargo.shop.promotion.entity.PmtApply::getPromoType,
                                CampaignPort.AppliedDiscount.ACTIVITY)));
        for (var r : rows.stream().sorted(java.util.Comparator.comparing(PmtApply::getPromoNo)
                .thenComparing(r -> r.getStoreNo() == null ? "" : r.getStoreNo())).toList()) {
            if (unifiedOrders != null && unifiedOrders.release(r)) continue;
            long amount = r.getAmountMinor() == null ? 0L : r.getAmountMinor();
            /*
             * 配额按「份」退，一单占一份（与 commit 那边的 qty 同一口径）。
             * **用 SQL 直接减并且不许减成负数** —— 并发下两次关单撞在一起时，
             * 负数配额会让这个活动此后谁都抢不到，而它看起来完全正常。
             */
            DataScopeContext.executeWithoutScope(() -> activityMapper.update(null,
                    Wrappers.<PmtActivity>lambdaUpdate()
                            .eq(PmtActivity::getActivityNo, r.getPromoNo())
                            .apply("quota_used > 0")
                            .setSql("quota_used = quota_used - 1")
                            .setSql("budget_used_minor = GREATEST(budget_used_minor - "
                                    + amount + ", 0)")));
            /*
             * 作废这一行。**幂等就在这里**：逻辑删之后下次查不到，也就不会再退一次。
             * 不物理删：这张表是对账与活动效果的账本，删了就查不出「当时发生过什么」。
             */
            DataScopeContext.executeWithoutScope(() -> applyMapper.deleteById(r.getId()));
        }
    }

    @Override
    public java.util.List<CampaignPort.AppliedDiscount> appliedOf(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return java.util.List.of();
        }
        var rows = DataScopeContext.executeWithoutScope(() -> applyMapper.selectList(
                Wrappers.<ai.neargo.shop.promotion.entity.PmtApply>lambdaQuery()
                        .eq(ai.neargo.shop.promotion.entity.PmtApply::getOrderNo, orderNo)));
        java.util.List<CampaignPort.AppliedDiscount> out = new ArrayList<>();
        for (var r : rows) {
            if (r.getRevertedAt() != null) continue;
            long amount = r.getAmountMinor() == null ? 0L : r.getAmountMinor();
            if (amount <= 0) {
                // 只送积分的组合也会记一行（减 0）—— 那不是「优惠依据」，别列出来
                continue;
            }
            String name = nameOfPromo(r.getPromoType(), r.getPromoNo());
            if (name == null || name.isBlank()) {
                // 名字取不到就不给这一条：占位说法与真名字长得一样，读的人分不出
                continue;
            }
            out.add(new CampaignPort.AppliedDiscount(
                    CampaignPort.AppliedDiscount.COUPON.equals(r.getPromoType())
                            ? CampaignPort.AppliedDiscount.COUPON
                            : CampaignPort.AppliedDiscount.ACTIVITY,
                    name, amount, r.getEntityNo(), r.getFunder()));
        }
        return out;
    }

    /** 活动名 / 券名。查不到给空 —— 调用方据此跳过这一条 */
    private String nameOfPromo(String type, String no) {
        if (no == null || no.isBlank()) {
            return null;
        }
        if (CampaignPort.AppliedDiscount.COUPON.equals(type)) {
            if (couponMapper == null) {
                return null;
            }
            /*
             * **券那一行记的是用户持有的那张的号**（CouponAllocServiceImpl 写 promoNo = userCouponNo），
             * 不是券模板号。此前直接拿它去查模板，一条都查不到 —— 于是新券在订单详情里
             * 永远说不出名字、整行被跳过（批 3 发现）。先按持有的那张反查模板号。
             */
            var held = userCouponMapper == null ? null
                    : DataScopeContext.executeWithoutScope(() -> userCouponMapper.selectOne(
                            Wrappers.<ai.neargo.shop.promotion.entity.PmtUserCoupon>lambdaQuery()
                                    .eq(ai.neargo.shop.promotion.entity.PmtUserCoupon::getUserCouponNo, no)
                                    .last("limit 1")));
            final String couponNo = held != null ? held.getCouponNo() : no;
            var c = DataScopeContext.executeWithoutScope(() -> couponMapper.selectOne(
                    Wrappers.<ai.neargo.shop.promotion.entity.PmtCoupon>lambdaQuery()
                            .eq(ai.neargo.shop.promotion.entity.PmtCoupon::getCouponNo, couponNo)
                            .last("limit 1")));
            return c == null ? null : c.getTitle();
        }
        var a = DataScopeContext.executeWithoutScope(() -> activityMapper.selectOne(
                Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getActivityNo, no).last("limit 1")));
        return a == null ? null : a.getName();
    }

    /**
     * 自己组合在这一家上的命中。
     *
     * @param off    买家少付多少（减钱与打折按顺序叠加，不超过作用范围的小计）
     * @param points 付款后额外送的积分
     */
    private record ComboHit(long off, long points) {
    }

    /**
     * 条件全部满足才生效（原型 s11「条件 · 全部满足」）。有「指定商品」时，
     * 件数与金额都按那几件货的小计判、优惠也只作用在它们上；没有就按整家的货。
     * 老调用方不传逐件明细时，带「指定商品」的组合不生效（不退回按整单判）。
     */
    private ComboHit comboHit(PmtActivity a, CampaignPort.MerchantAmount g) {
        if (ruleMapper == null) {
            return null;
        }
        List<ai.neargo.shop.promotion.entity.PmtActivityRule> rules = DataScopeContext.executeWithoutScope(() ->
                ruleMapper.selectList(Wrappers.<ai.neargo.shop.promotion.entity.PmtActivityRule>lambdaQuery()
                        .eq(PmtActivityRule::getActivityNo, a.getActivityNo())
                        .orderByAsc(PmtActivityRule::getSeq)));
        var items = rules.stream().map(ActivityServiceImpl::readParams).toList();
        long amount = g.goodsAmount();
        int qty = g.goodsQty();
        var scope = items.stream().filter(r -> "CONDITION".equals(r.kind()) && "GOODS".equals(r.type()))
                .findFirst().orElse(null);
        if (scope != null) {
            if (g.lines() == null || g.lines().isEmpty()) {
                return null;
            }
            java.util.Set<String> goods = new java.util.HashSet<>(scope.goodsNos() == null ? List.of() : scope.goodsNos());
            amount = 0L;
            qty = 0;
            for (CampaignPort.GoodsLine l : g.lines()) {
                if (goods.contains(l.goodsNo())) {
                    amount += l.amount();
                    qty += l.qty();
                }
            }
            if (amount <= 0) {
                return null;
            }
        }
        for (var r : items) {
            if (!"CONDITION".equals(r.kind())) {
                continue;
            }
            if ("AMOUNT".equals(r.type()) && amount < nz(r.amountMinor())) {
                return null;
            }
            if ("QTY".equals(r.type()) && qty < (r.n() == null ? 0 : r.n())) {
                return null;
            }
        }
        long off = 0L;
        long points = 0L;
        for (var r : items) {
            if (!"BENEFIT".equals(r.kind())) {
                continue;
            }
            switch (r.type()) {
                case "CUT" -> off += Math.min(nz(r.amountMinor()), amount - off);
                case "PERCENT" -> {
                    long cut = (amount - off) * (10_000 - (r.bp() == null ? 10_000 : r.bp())) / 10_000;
                    off += Math.min(cut, nz(r.capMinor()));
                }
                case "POINTS" -> points += r.n() == null ? 0 : r.n();
                default -> { }
            }
        }
        return new ComboHit(Math.max(0, Math.min(off, amount)), points);
    }

    @Override
    public long bonusPoints(String orderNo, String merchantNo) {
        if (ruleMapper == null || orderNo == null) {
            return 0L;
        }
        List<String> promos = DataScopeContext.executeWithoutScope(() -> applyMapper.selectList(
                        Wrappers.<PmtApply>lambdaQuery()
                                .eq(PmtApply::getOrderNo, orderNo)
                                .eq(PmtApply::getEntityNo, merchantNo)
                                .eq(PmtApply::getPromoType, PmtApply.ACTIVITY)
                                .isNull(PmtApply::getRevertedAt)))
                .stream().map(PmtApply::getPromoNo).distinct().toList();
        long total = 0L;
        for (String activityNo : promos) {
            total += DataScopeContext.executeWithoutScope(() -> ruleMapper.selectList(
                            Wrappers.<ai.neargo.shop.promotion.entity.PmtActivityRule>lambdaQuery()
                                    .eq(PmtActivityRule::getActivityNo, activityNo)
                                    .eq(PmtActivityRule::getKind, "BENEFIT")
                                    .eq(PmtActivityRule::getRuleType, "POINTS")))
                    .stream().map(ActivityServiceImpl::readParams)
                    .mapToLong(r -> r.n() == null ? 0 : r.n()).sum();
        }
        return total;
    }

    /**
     * 平台活动在这一家上的命中（P3 · 详细设计 §1.6）。
     *
     * @param off           买家这一单少付多少（按报名货的小计封顶）
     * @param platformMinor 其中平台出的部分 = off × 出资比例（向下取整，零头归商家）
     */
    private record PlatformHit(PmtActivity activity, String enrollmentNo, long off, long platformMinor) {
    }

    /**
     * 这家店<b>已通过、还有份数</b>的报名里，此刻在跑的平台活动能减多少。
     *
     * <p>生效范围是报名里的货：门槛按那几件货的小计判（{@code MerchantAmount.lines}）。
     * 老调用方不传逐件明细时这里返回空 —— 平台活动不生效，而不是退回按整单判
     * （那会让「买一件报名的货加一堆别的货」凑够满减）。
     *
     * <p>绕数据域读报名，边界靠 {@code entity_no = 这一单的商家}（买家会话下不绕就恒为空）。
     */
    private List<PlatformHit> platformHits(CampaignPort.MerchantAmount g, long now) {
        if (enrollmentMapper == null || g.lines() == null || g.lines().isEmpty()) {
            return List.of();
        }
        List<ai.neargo.shop.promotion.entity.PmtEnrollment> es = DataScopeContext.executeWithoutScope(() ->
                enrollmentMapper.selectList(Wrappers.<ai.neargo.shop.promotion.entity.PmtEnrollment>lambdaQuery()
                        .eq(ai.neargo.shop.promotion.entity.PmtEnrollment::getEntityNo, g.merchantNo())
                        .eq(ai.neargo.shop.promotion.entity.PmtEnrollment::getStatus,
                                ai.neargo.shop.promotion.entity.PmtEnrollment.APPROVED)
                        .apply("quota_used < quota")));
        List<PlatformHit> out = new ArrayList<>();
        for (var e : es) {
            PmtActivity a = DataScopeContext.executeWithoutScope(() ->
                    activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                            .eq(PmtActivity::getActivityNo, e.getActivityNo())
                            .eq(PmtActivity::getOwner, PmtActivity.OWNER_PLATFORM)
                            .last("limit 1")));
            if (a == null || !PmtActivity.BENEFIT_CUT.equals(a.getBenefitType()) || !a.isActiveAt(now, MARKET_ZONE)) {
                continue;
            }
            java.util.Set<String> goods = DataScopeContext.executeWithoutScope(() ->
                            enrollmentGoodsMapper.selectList(Wrappers.<ai.neargo.shop.promotion.entity.PmtEnrollmentGoods>lambdaQuery()
                                    .eq(ai.neargo.shop.promotion.entity.PmtEnrollmentGoods::getEnrollmentNo, e.getEnrollmentNo())))
                    .stream().map(ai.neargo.shop.promotion.entity.PmtEnrollmentGoods::getGoodsNo)
                    .collect(java.util.stream.Collectors.toSet());
            long amount = 0L;
            int qty = 0;
            for (CampaignPort.GoodsLine l : g.lines()) {
                if (goods.contains(l.goodsNo())) {
                    amount += l.amount();
                    qty += l.qty();
                }
            }
            if (amount <= 0) {
                continue;
            }
            CampaignPort.MerchantAmount scoped = new CampaignPort.MerchantAmount(g.merchantNo(), amount, qty, g.storeNo());
            if (!cutTriggerHits(a, scoped)) {
                continue;
            }
            long off = Math.min(nz(a.getBenefitAmountMinor()), amount);
            int bp = a.getPlatformShareBp() == null ? 0 : a.getPlatformShareBp();
            out.add(new PlatformHit(a, e.getEnrollmentNo(), off, off * bp / 10_000));
        }
        return out;
    }

    /**
     * 减钱类活动的门槛判定。<b>三种触发，各判各的字段</b>：
     *
     * <ul>
     *   <li>{@code AMOUNT} —— 满额减，看金额</li>
     *   <li>{@code QTY} —— 满件减，看件数（不含赠品，见 {@code MerchantAmount.goodsQty}）</li>
     *   <li>{@code NONE} —— 无门槛立减，恒命中</li>
     * </ul>
     *
     * <p><b>其余触发一律跳过而不是放行</b>：{@code GOODS × CUT}「买这几件货减 X」
     * 这一侧拿到的是按商家汇总后的金额，没有逐件明细，减在哪一件上无从摊分。
     * 放行的话它会变成一个整单减 —— 商家选了三件货，结果全店都减，且不报错。
     * 存那一侧已在 {@code ActivityServiceImpl.assertSane} 里直接拒绝这个组合。
     */
    private boolean cutTriggerHits(PmtActivity a, CampaignPort.MerchantAmount g) {
        return switch (a.getTriggerType() == null ? PmtActivity.TRIGGER_NONE : a.getTriggerType()) {
            case PmtActivity.TRIGGER_AMOUNT -> g.goodsAmount() >= nz(a.getTriggerAmountMinor());
            case PmtActivity.TRIGGER_QTY -> nz(a.getTriggerQty()) > 0
                    && g.goodsQty() >= nz(a.getTriggerQty());
            case PmtActivity.TRIGGER_NONE -> true;
            default -> false;
        };
    }

    @Override
    public Map<String, Long> flashPrices(String userNo, Collection<String> goodsNos) {
        return flashPrices(userNo, goodsNos, Map.of(), false);
    }

    @Override
    public Map<String, Long> ordinaryFlashPrices(
            String userNo, Collection<String> goodsNos, Map<String, Long> competingPrices) {
        return flashPrices(userNo, goodsNos, competingPrices, true);
    }

    private Map<String, Long> flashPrices(
            String userNo,
            Collection<String> goodsNos,
            Map<String, Long> competingPrices,
            boolean ordinary) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new HashMap<>(competingPrices);
        var unsupportedWinners = new HashSet<String>();
        for (PmtActivity a : liveByGoods(goodsNos, PmtActivity.BENEFIT_PRICE)) {
            /*
             * ★ **团购的成团价不是限时特价**（2026-09-18）。
             *
             * 两者的优惠都是 `BENEFIT_PRICE`，只有触发分得开。不判触发的话，
             * 商家建一个团购活动会让**所有人**按成团价买到 ——
             * 不用凑人数、不用进团，团购这件事整个失去意义。
             *
             * <b>而它一个字都不报</b>：价照样算得出来，订单照样成，
             * 只有对账时才看得出每一单都少收了钱。
             * 2026-09-18 被 groupPriceMustBeatOriginPrice 撞出来 ——
             * 那条用例里商品原价与成团价一路相等，正是这条在悄悄改价。
             *
             * 成团价只在**团里**生效，由开团那条链（mkt_group_buy）自己算。
             */
            if (PmtActivity.TRIGGER_GROUP.equals(a.getTriggerType())) {
                continue;
            }
            if (!audienceHits(a, a.getEntityNo(), userNo)) {
                continue;
            }
            for (String goodsNo : goodsOf(a.getActivityNo())) {
                if (!goodsNos.contains(goodsNo)) {
                    continue;
                }
                long price = nz(a.getBenefitAmountMinor());
                // **取最低价**：与满减「取最优」同一侧 —— 都往对用户有利的方向走
                long previous = out.getOrDefault(goodsNo, Long.MAX_VALUE);
                if (ordinary && price <= previous) {
                    boolean supported =
                            PmtActivity.TRIGGER_NONE.equals(a.getTriggerType())
                                    || PmtActivity.TRIGGER_GOODS.equals(a.getTriggerType());
                    if (!supported) {
                        unsupportedWinners.add(goodsNo);
                    } else if (price < previous) {
                        unsupportedWinners.remove(goodsNo);
                    }
                }
                out.merge(goodsNo, price, Math::min);
            }
        }
        if (!unsupportedWinners.isEmpty()) {
            throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        }
        return out;
    }

    @Override
    public Map<String, CampaignPort.GiftRule> giftRules(String userNo, Collection<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return Map.of();
        }
        Map<String, CampaignPort.GiftRule> out = new HashMap<>();
        for (PmtActivity a : liveByGoods(goodsNos, PmtActivity.BENEFIT_GIFT)) {
            if (!audienceHits(a, a.getEntityNo(), userNo)) {
                continue;
            }
            int buyN = nz(a.getTriggerQty());
            int giftM = nz(a.getBenefitQty());
            if (buyN <= 0 || giftM <= 0) {
                continue;
            }
            for (String goodsNo : goodsOf(a.getActivityNo())) {
                if (!goodsNos.contains(goodsNo)) {
                    continue;
                }
                CampaignPort.GiftRule cur = out.get(goodsNo);
                // 送得最多的那个赢 —— 同样往对用户有利的一侧走
                if (cur == null || giftM > cur.giftM()) {
                    out.put(goodsNo, new CampaignPort.GiftRule(buyN, giftM));
                }
            }
        }
        return out;
    }

    @Override
    @Transactional
    public void commit(String userNo, String orderNo, CampaignPort.Discount discount) {
        if (discount == null || discount.applied().isEmpty()) {
            return;
        }
        // Stable lock ordering across a multi-merchant order avoids cross-activity deadlocks.
        for (CampaignPort.AppliedActivity it : discount.applied().stream()
                .sorted(java.util.Comparator.comparing(CampaignPort.AppliedActivity::activityNo)
                        .thenComparing(a -> a.storeNo() == null ? "" : a.storeNo())).toList()) {
            if (unifiedOrders != null && unifiedOrders.commit(userNo, orderNo, it)) continue;
            if (it.enrollmentNo() != null) {
                commitPlatform(userNo, orderNo, it);
                continue;
            }
            int affected = DataScopeContext.executeWithoutScope(() ->
                    activityMapper.update(null, Wrappers.<PmtActivity>lambdaUpdate()
                            .eq(PmtActivity::getActivityNo, it.activityNo())
                            // 限量为空 = 不限；有限量时必须还装得下这一单
                            .and(w -> w.isNull(PmtActivity::getQuota)
                                    .or().apply("quota_used + {0} <= quota", it.qty()))
                            .setSql("quota_used = quota_used + " + it.qty())
                            .setSql("budget_used_minor = budget_used_minor + "
                                    + it.amountMinor())));
            if (affected == 0) {
                /*
                 * 没抢到最后一份。**不抛异常**：订单已经按算价时的金额落库了，
                 * 这时候翻脸会让用户付了钱却下不成单 —— 那比多发一份的损失大得多。
                 * 记 WARN，让运营看到「超发了几份」：那是限量与并发之间必然存在的
                 * 一点点重叠，不是错误。
                 */
                log.warn("[活动] 限量已满仍命中：活动 {} 订单 {} 多发 {} 份",
                        it.activityNo(), orderNo, it.qty());
                continue;
            }
            /*
             * 记一行优惠发生记录。**与券共用 pmt_apply** ——
             * 「这一单命中了什么」只有一处答案，活动效果与券对账才不会各算各的。
             * 金额是算价时的结果，不在这里重算（重算依赖的规则会变）。
             */
            PmtApply row = new PmtApply();
            row.setApplyNo(BizKey.next(BizKey.PROMO_APPLY));
            row.setPromoType(PmtApply.ACTIVITY);
            row.setPromoNo(it.activityNo());
            row.setUserNo(userNo);
            row.setEntityNo(it.merchantNo());
            row.setOrderNo(orderNo);
            row.setRedeemMode(ai.neargo.shop.promotion.entity.PmtCoupon.REDEEM_ORDER);
            row.setAmountMinor(it.amountMinor());
            // 店铺活动的出资方恒为商家 —— 平台不出这个钱
            row.setFunder(ai.neargo.shop.promotion.entity.PmtCoupon.BY_MERCHANT);
            row.setAppliedAt(System.currentTimeMillis());
            DataScopeContext.executeWithoutScope(() -> applyMapper.insert(row));

            // 扣完正好到量：把活动收尾，并说清为什么停 —— 商家问「怎么停了」要有答案
            PmtActivity a = DataScopeContext.executeWithoutScope(() ->
                    activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                            .eq(PmtActivity::getActivityNo, it.activityNo()).last("limit 1")));
            if (a != null && a.getQuota() != null && nz(a.getQuotaUsed()) >= a.getQuota()) {
                DataScopeContext.executeWithoutScope(() ->
                        activityMapper.update(null, Wrappers.<PmtActivity>lambdaUpdate()
                                .eq(PmtActivity::getActivityNo, it.activityNo())
                                .eq(PmtActivity::getStatus, PmtActivity.RUNNING)
                                .set(PmtActivity::getStatus, PmtActivity.ENDED)
                                .set(PmtActivity::getEndedReason, PmtActivity.ENDED_QUOTA)));
                log.info("[活动] {} 到量自动结束", it.activityNo());
            }
        }
    }

    /**
     * 平台活动这一单用掉的份数与钱（P3）。
     *
     * <p>限量扣在<b>报名单</b>上（商家报了多少份就是多少份），带条件的 UPDATE；平台花掉的钱记到活动的
     * {@code budget_used_minor}。{@code pmt_apply} 按出资方拆两行（平台、商家各一行），
     * 与子单 {@code discount_platform} / {@code discount_merchant} 对得上 —— 结算按子单那两列走。
     * 没抢到最后一份时与商家活动同一个处理：不抛，记 WARN。
     */
    private void commitPlatform(String userNo, String orderNo, CampaignPort.AppliedActivity it) {
        int affected = DataScopeContext.executeWithoutScope(() -> enrollmentMapper.update(null,
                Wrappers.<ai.neargo.shop.promotion.entity.PmtEnrollment>lambdaUpdate()
                        .eq(ai.neargo.shop.promotion.entity.PmtEnrollment::getEnrollmentNo, it.enrollmentNo())
                        .apply("quota_used + {0} <= quota", it.qty())
                        .setSql("quota_used = quota_used + " + it.qty())));
        if (affected == 0) {
            log.warn("[平台活动] 报名份数已满仍命中：报名 {} 订单 {}", it.enrollmentNo(), orderNo);
            return;
        }
        DataScopeContext.executeWithoutScope(() -> activityMapper.update(null, Wrappers.<PmtActivity>lambdaUpdate()
                .eq(PmtActivity::getActivityNo, it.activityNo())
                .setSql("budget_used_minor = budget_used_minor + " + it.platformMinor())));
        long merchantPart = it.amountMinor() - it.platformMinor();
        if (it.platformMinor() > 0) {
            insertApply(userNo, orderNo, it, it.platformMinor(), ai.neargo.shop.promotion.entity.PmtCoupon.BY_PLATFORM);
        }
        if (merchantPart > 0) {
            insertApply(userNo, orderNo, it, merchantPart, ai.neargo.shop.promotion.entity.PmtCoupon.BY_MERCHANT);
        }
    }

    private void insertApply(String userNo, String orderNo, CampaignPort.AppliedActivity it, long amount, String funder) {
        PmtApply row = new PmtApply();
        row.setApplyNo(BizKey.next(BizKey.PROMO_APPLY));
        row.setPromoType(PmtApply.ACTIVITY);
        row.setPromoNo(it.activityNo());
        row.setUserNo(userNo);
        row.setEntityNo(it.merchantNo());
        row.setOrderNo(orderNo);
        row.setRedeemMode(ai.neargo.shop.promotion.entity.PmtCoupon.REDEEM_ORDER);
        row.setAmountMinor(amount);
        row.setFunder(funder);
        row.setAppliedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> applyMapper.insert(row));
    }

    /**
     * 这家店此刻在跑的活动。
     *
     * <p>绕开数据域：算价时的会话是**买家**（SELF），而 {@code pmt_activity}
     * 按 entity_no 登记 —— 不绕的话查出来恒为空，表现是「所有活动都不生效」，
     * 而接口成功、日志干净。
     */
    private List<PmtActivity> live(String entityNo, String storeNo, long now) {
        List<PmtActivity> all = DataScopeContext.executeWithoutScope(() ->
                activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getEntityNo, entityNo)
                        .eq(PmtActivity::getStatus, PmtActivity.RUNNING)));
        List<PmtActivity> out = new ArrayList<>();
        for (PmtActivity a : all) {
            /*
             * 门店级活动只对那家店生效；全主体活动对谁都生效。
             * **storeNo 为空时只有全主体活动生效** —— 不是「所有门店活动都生效」，
             * 那会让一家店的开业满减减到别家店的单上。
             */
            if (a.getStoreNo() != null && !a.getStoreNo().equals(storeNo)) {
                continue;
            }
            if (a.getDraftSnapshot() != null || !a.isActiveAt(now, MARKET_ZONE) || !a.hasQuotaLeft()) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    /** 按商品反查在跑的活动 —— `idx_pmt_goods_ref` 就是为这条路建的 */
    private List<PmtActivity> liveByGoods(Collection<String> goodsNos, String benefitType) {
        long now = System.currentTimeMillis();
        List<PmtActivityGoods> refs = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PmtActivityGoods>lambdaQuery()
                        .eq(PmtActivityGoods::getScopeType, PmtActivityGoods.GOODS)
                        .in(PmtActivityGoods::getRefNo, goodsNos)));
        List<PmtActivity> out = new ArrayList<>();
        for (String activityNo : refs.stream().map(PmtActivityGoods::getActivityNo)
                .distinct().toList()) {
            PmtActivity a = DataScopeContext.executeWithoutScope(() ->
                    activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                            .eq(PmtActivity::getActivityNo, activityNo).last("limit 1")));
            if (a == null || !benefitType.equals(a.getBenefitType())) {
                continue;
            }
            if (a.getDraftSnapshot() != null || !a.isActiveAt(now, MARKET_ZONE) || !a.hasQuotaLeft()) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    private List<String> goodsOf(String activityNo) {
        return DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PmtActivityGoods>lambdaQuery()
                                .eq(PmtActivityGoods::getActivityNo, activityNo))
                        .stream().map(PmtActivityGoods::getRefNo).toList());
    }

    /**
     * 他在不在这个活动的受众里。
     *
     * <p><b>一行受众都没有 = 对所有人生效</b>。这条默认值让存量活动迁过来之后
     * 行为逐分不变 —— 「空 = 谁都不给」会让所有老活动在上线那一刻集体失效。
     *
     * <p>多行之间是<b>或</b>：受众是「这些人都可以」，不是「必须同时满足」。
     * 与人群里的标签取交集刚好相反 —— 那边是筛人（收窄），这边是圈人（放宽）。
     */
    private boolean audienceHits(PmtActivity a, String entityNo, String userNo) {
        List<PmtActivityAudience> rules = DataScopeContext.executeWithoutScope(() ->
                audienceMapper.selectList(Wrappers.<PmtActivityAudience>lambdaQuery()
                        .eq(PmtActivityAudience::getActivityNo, a.getActivityNo())));
        if (rules.isEmpty()) {
            return true;
        }
        MemberQueryPort.MemberSnapshot me = memberPort.judge(entityNo, userNo);
        for (PmtActivityAudience r : rules) {
            boolean hit = switch (r.getAudienceType()) {
                case PmtActivityAudience.NON_MEMBER -> !me.member();
                case PmtActivityAudience.LEVEL -> me.member()
                        && r.getAudienceValue().equals(me.level());
                case PmtActivityAudience.SOURCE -> me.member()
                        && r.getAudienceValue().equals(me.source());
                case PmtActivityAudience.TAG -> me.tagNos().contains(r.getAudienceValue());
                // 有快照按快照（发布那一刻的人群），没有的是存量行，按人群此刻的条件（AC-9）
                case PmtActivityAudience.SEGMENT -> r.getRuleSnapshot() != null
                        ? memberPort.matchesRule(entityNo, userNo, r.getRuleSnapshot())
                        : me.segmentNos().contains(r.getAudienceValue());
                default -> false;
            };
            if (hit) {
                return true;
            }
        }
        return false;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
