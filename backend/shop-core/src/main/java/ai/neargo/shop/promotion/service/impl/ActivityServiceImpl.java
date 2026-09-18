package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.dto.ActivityVOs.AudienceItem;
import ai.neargo.shop.promotion.dto.ActivityVOs.ConflictVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityAudience;
import ai.neargo.shop.promotion.entity.PmtActivityGoods;
import ai.neargo.shop.promotion.entity.RecurringRule;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityAudienceMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityGoodsMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class ActivityServiceImpl implements ActivityService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ActivityServiceImpl.class);

    /**
     * 排期按市场时区判。
     *
     * <p>「每周三 8 点到 20 点」说的是<b>顾客那边的周三</b>，而服务器可能在别的时区。
     * 差 8 小时就意味着周三早上八点的活动在真正的周三还没开始，
     * 而商家看到的状态是「进行中」。
     */
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    private final ActivityMapper activityMapper;
    private final ActivityAudienceMapper audienceMapper;
    private final ActivityGoodsMapper goodsMapper;

    public ActivityServiceImpl(ActivityMapper activityMapper, ActivityAudienceMapper audienceMapper,
                               ActivityGoodsMapper goodsMapper) {
        this.activityMapper = activityMapper;
        this.audienceMapper = audienceMapper;
        this.goodsMapper = goodsMapper;
    }

    @Override
    public List<ActivityVO> list(String entityNo, boolean includeEnded) {
        return DataScopeContext.executeWithoutScope(() ->
                        activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                                .eq(PmtActivity::getEntityNo, entityNo)
                                .ne(!includeEnded, PmtActivity::getStatus, PmtActivity.ENDED)
                                .isNull(PmtActivity::getArchivedAt)
                                .orderByDesc(PmtActivity::getId)))
                .stream().map(this::vo).toList();
    }

    @Override
    public ActivityVO detail(String entityNo, String activityNo) {
        return vo(require(entityNo, activityNo));
    }

    @Override
    @Transactional
    public ActivityVO save(String entityNo, ActivityDraft d, String operatorNo) {
        PmtActivity a = d.activityNo() == null || d.activityNo().isBlank()
                ? null : require(entityNo, d.activityNo());
        boolean create = a == null;
        if (create) {
            a = new PmtActivity();
            a.setActivityNo(BizKey.next(BizKey.PROMO_ACTIVITY));
            a.setEntityNo(entityNo);
            a.setQuotaUsed(0);
            a.setBudgetUsedMinor(0L);
            a.setStatus(PmtActivity.RUNNING);
        } else if (PmtActivity.ENDED.equals(a.getStatus())) {
            // 已结束的不能改：时段已过、限量已用，改完只会立刻又结束一次
            throw BizException.of(ErrorCode.ACTIVITY_ENDED_IMMUTABLE);
        }
        apply(a, d);
        assertSane(a, d);
        assertNoGroupOverlap(a, d);

        if (create) {
            activityMapper.insert(a);
        } else {
            activityMapper.updateById(a);
        }
        saveAudiences(entityNo, a.getActivityNo(), d.audiences());
        saveGoods(entityNo, a.getActivityNo(), d.goodsNos());
        log.info("[活动] {} {} by {}", create ? "建" : "改", a.getActivityNo(), operatorNo);
        return vo(a);
    }

    private void apply(PmtActivity a, ActivityDraft d) {
        a.setName(d.name() == null ? null : d.name().trim());
        a.setGoal(d.goal());
        a.setStoreNo(blank(d.storeNo()) ? null : d.storeNo());
        a.setTriggerType(blank(d.triggerType()) ? PmtActivity.TRIGGER_NONE : d.triggerType());
        a.setTriggerAmountMinor(d.triggerAmountMinor());
        a.setTriggerQty(d.triggerQty());
        a.setBenefitType(d.benefitType());
        a.setBenefitAmountMinor(d.benefitAmountMinor());
        a.setBenefitQty(d.benefitQty());
        a.setBenefitRef(d.benefitRef());
        a.setScheduleType(blank(d.scheduleType()) ? PmtActivity.ONE_OFF : d.scheduleType());
        a.setStartAt(d.startAt());
        a.setEndAt(d.endAt());
        a.setScheduleRule(d.scheduleRule());
        a.setQuota(d.quota());
        a.setBudgetMinor(d.budgetMinor());
    }

    /**
     * <b>一件货同时只能在一个团购活动里</b>（2026-09-18 店主定）。
     *
     * <p><b>拦在服务端，不是靠端上那个 {@code /biz/activity-conflicts}</b> ——
     * 那一条是给界面提前提示用的建议，绕开它（旧版本客户端、或直接调接口）
     * 照样存得进去。而这里的代价是不对称的：拦住了商家改一下就行，
     * 放过去是买家看到一个价、付另一个价。
     *
     * <p><b>只管团购之间</b>。团购与限时特价撞在一起是另一件事
     *（那要算「哪个价优先」），这一轮不碰 —— 见方案 §6.2：
     * 「暂时」的意思是这条限制可以将来放开，但放开时要先想清楚两个价怎么显示。
     *
     * <p>改自己不算撞：编辑一个已有的团购活动时要把它自己排除掉，
     * 否则第二次保存必失败，而报错说的是「这件货已经在别的团里」。
     */
    private void assertNoGroupOverlap(PmtActivity a, ActivityDraft d) {
        if (!PmtActivity.TRIGGER_GROUP.equals(a.getTriggerType())
                || d.goodsNos() == null || d.goodsNos().isEmpty()) {
            return;
        }
        for (ConflictVO c : conflicts(a.getEntityNo(), d.goodsNos())) {
            if (c.activityNo().equals(a.getActivityNo())) {
                continue;   // 改自己
            }
            PmtActivity other = activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                    .eq(PmtActivity::getActivityNo, c.activityNo()).last("limit 1"));
            if (other != null && PmtActivity.TRIGGER_GROUP.equals(other.getTriggerType())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }

    /** 建活动时的全部硬校验。每一条堵的都是「上线之后没人能补救」的事 */
    private void assertSane(PmtActivity a, ActivityDraft d) {
        if (blank(a.getName()) || blank(a.getBenefitType())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        switch (a.getTriggerType()) {
            case PmtActivity.TRIGGER_AMOUNT -> {
                if (nz(a.getTriggerAmountMinor()) <= 0) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            case PmtActivity.TRIGGER_QTY -> {
                if (nz(a.getTriggerQty()) <= 0) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            case PmtActivity.TRIGGER_GROUP -> {
                /*
                 * **成团人数至少 2。** 1 个人不叫团 —— 放过去的话它就是一个
                 * 谁买都生效的降价，而界面上写着「团购」，商家以为自己在攒人。
                 *
                 * **成团价必须是改单价。** 团购的优惠只可能是「这件货便宜多少」，
                 * 配成满减或送券的话，算价那一侧根本不知道该怎么用它，
                 * 而它不会报错 —— 团照样成，价照样是原价。
                 */
                if (nz(a.getTriggerQty()) < 2
                        || !PmtActivity.BENEFIT_PRICE.equals(a.getBenefitType())) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            default -> { /* NONE 与 GOODS 没有额外参数 */ }
        }
        switch (a.getBenefitType()) {
            case PmtActivity.BENEFIT_CUT, PmtActivity.BENEFIT_PRICE -> {
                if (nz(a.getBenefitAmountMinor()) <= 0) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            case PmtActivity.BENEFIT_GIFT -> {
                if (nz(a.getBenefitQty()) <= 0 || blank(a.getBenefitRef())) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            case PmtActivity.BENEFIT_COUPON -> {
                if (blank(a.getBenefitRef())) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * **长期活动必须有限量或预算。**
         *
         * 没有结束时间又没有上限 = 永久敞口。商家建的时候想的是「一直有这个优惠」，
         * 不是「无论花多少」—— 这两句话在他心里是一回事，在账上不是。
         */
        boolean capped = a.getQuota() != null || nz(a.getBudgetMinor()) > 0;
        if (PmtActivity.ALWAYS_ON.equals(a.getScheduleType()) && !capped) {
            throw BizException.of(ErrorCode.ACTIVITY_ALWAYS_ON_NEEDS_CAP);
        }
        /*
         * **改单价与送商品必须有限量**：这两种的单次成本由商品决定，
         * 不设上限时敞口随销量走 —— 卖得越好亏得越多，而那正是最难叫停的时刻。
         */
        boolean itemCost = PmtActivity.BENEFIT_PRICE.equals(a.getBenefitType())
                || PmtActivity.BENEFIT_GIFT.equals(a.getBenefitType());
        if (itemCost && a.getQuota() == null) {
            throw BizException.of(ErrorCode.ACTIVITY_QUOTA_REQUIRED);
        }
        if (itemCost && (d.goodsNos() == null || d.goodsNos().isEmpty())) {
            // 全店改价那叫调价，走商品编辑；活动改价必须指定商品
            throw BizException.of(ErrorCode.ACTIVITY_GOODS_REQUIRED);
        }

        if (PmtActivity.ONE_OFF.equals(a.getScheduleType())) {
            if (nz(a.getStartAt()) <= 0 || nz(a.getEndAt()) <= nz(a.getStartAt())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        if (PmtActivity.RECURRING.equals(a.getScheduleType())) {
            RecurringRule r = RecurringRule.parse(a.getScheduleRule());
            // 规则读不出来 = 全天生效，那不是商家的本意。堵在保存这一步
            if (r.weekdays().isEmpty() && r.from() == null && r.to() == null) {
                throw BizException.of(ErrorCode.ACTIVITY_RECURRING_RULE_INVALID);
            }
        }
        if (a.getQuota() != null && a.getQuota() < nz(a.getQuotaUsed())) {
            throw BizException.of(ErrorCode.ACTIVITY_QUOTA_BELOW_USED);
        }
    }

    /** 受众整批换掉：增量在「删掉一个标签」上一定会漏 */
    private void saveAudiences(String entityNo, String activityNo, List<AudienceItem> items) {
        audienceMapper.delete(Wrappers.<PmtActivityAudience>lambdaQuery()
                .eq(PmtActivityAudience::getActivityNo, activityNo));
        if (items == null) {
            return;
        }
        for (AudienceItem it : items) {
            if (it == null || blank(it.type()) || blank(it.value())) {
                continue;
            }
            PmtActivityAudience row = new PmtActivityAudience();
            row.setActivityNo(activityNo);
            row.setEntityNo(entityNo);
            row.setAudienceType(it.type());
            row.setAudienceValue(it.value());
            audienceMapper.insert(row);
        }
    }

    private void saveGoods(String entityNo, String activityNo, List<String> goodsNos) {
        // **物理删**，不是逻辑删 —— 唯一键不含 deleted，逻辑删会让第二次保存撞键。
        // 见 ActivityGoodsMapper.hardDeleteByActivity 的注释
        goodsMapper.hardDeleteByActivity(activityNo);
        if (goodsNos == null) {
            return;
        }
        for (String no : goodsNos.stream().filter(x -> !blank(x)).distinct().toList()) {
            PmtActivityGoods row = new PmtActivityGoods();
            row.setActivityNo(activityNo);
            row.setEntityNo(entityNo);
            row.setScopeType(PmtActivityGoods.GOODS);
            row.setRefNo(no);
            goodsMapper.insert(row);
        }
    }

    @Override
    @Transactional
    public ActivityVO setStatus(String entityNo, String activityNo, String status) {
        PmtActivity a = require(entityNo, activityNo);
        if (PmtActivity.ENDED.equals(a.getStatus())) {
            // 已结束不可复活：ended_reason 会被覆盖，商家再也查不到当初为什么停
            throw BizException.of(ErrorCode.ACTIVITY_ENDED_IMMUTABLE);
        }
        if (!PmtActivity.RUNNING.equals(status) && !PmtActivity.PAUSED.equals(status)
                && !PmtActivity.ENDED.equals(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        a.setStatus(status);
        if (PmtActivity.ENDED.equals(status)) {
            a.setEndedReason(PmtActivity.ENDED_MANUAL);
        }
        activityMapper.updateById(a);
        return vo(a);
    }

    @Override
    public List<ConflictVO> conflicts(String entityNo, List<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return List.of();
        }
        List<PmtActivityGoods> hits = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                Wrappers.<PmtActivityGoods>lambdaQuery()
                        .eq(PmtActivityGoods::getEntityNo, entityNo)
                        .eq(PmtActivityGoods::getScopeType, PmtActivityGoods.GOODS)
                        .in(PmtActivityGoods::getRefNo, goodsNos)));
        List<ConflictVO> out = new ArrayList<>();
        for (PmtActivityGoods g : hits) {
            PmtActivity a = DataScopeContext.executeWithoutScope(() ->
                activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                    .eq(PmtActivity::getActivityNo, g.getActivityNo()).last("limit 1")));
            // 只报还在跑的：已结束的活动不构成冲突，报出来只会让人以为要处理
            if (a == null || !PmtActivity.RUNNING.equals(a.getStatus())) {
                continue;
            }
            out.add(new ConflictVO(g.getRefNo(), a.getActivityNo(), a.getName(),
                    a.getBenefitType()));
        }
        return out;
    }

    private ActivityVO vo(PmtActivity a) {
        List<AudienceItem> audiences = DataScopeContext.executeWithoutScope(() ->
                        audienceMapper.selectList(Wrappers.<PmtActivityAudience>lambdaQuery()
                                .eq(PmtActivityAudience::getActivityNo, a.getActivityNo())))
                .stream().map(x -> new AudienceItem(x.getAudienceType(), x.getAudienceValue()))
                .toList();
        List<String> goods = DataScopeContext.executeWithoutScope(() ->
                        goodsMapper.selectList(Wrappers.<PmtActivityGoods>lambdaQuery()
                                .eq(PmtActivityGoods::getActivityNo, a.getActivityNo())))
                .stream().map(PmtActivityGoods::getRefNo).toList();
        Integer left = a.getQuota() == null ? null
                : Math.max(0, a.getQuota() - nz(a.getQuotaUsed()));
        Long exposure = a.getQuota() == null ? null : a.getQuota() * perUse(a);
        return new ActivityVO(a.getActivityNo(), a.getName(), a.getGoal(), a.getStoreNo(),
                a.getTriggerType(), a.getTriggerAmountMinor(), a.getTriggerQty(),
                a.getBenefitType(), a.getBenefitAmountMinor(), a.getBenefitQty(),
                a.getBenefitRef(), a.getScheduleType(), a.getStartAt(), a.getEndAt(),
                a.getScheduleRule(), a.getQuota(), nz(a.getQuotaUsed()), left,
                a.getBudgetMinor(), nz(a.getBudgetUsedMinor()), exposure,
                audiences, goods, a.getStatus(), a.getEndedReason(),
                a.isActiveAt(System.currentTimeMillis(), MARKET_ZONE) && a.hasQuotaLeft());
    }

    /** 单次优惠。改单价那种算不出来（要看原价），保守记 0 —— 敞口以限量为准 */
    private long perUse(PmtActivity a) {
        return PmtActivity.BENEFIT_CUT.equals(a.getBenefitType())
                ? nz(a.getBenefitAmountMinor()) : 0L;
    }

    private PmtActivity require(String entityNo, String activityNo) {
        PmtActivity a = DataScopeContext.executeWithoutScope(() ->
                activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                .eq(PmtActivity::getEntityNo, entityNo)
                .eq(PmtActivity::getActivityNo, activityNo).last("limit 1")));
        if (a == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return a;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
