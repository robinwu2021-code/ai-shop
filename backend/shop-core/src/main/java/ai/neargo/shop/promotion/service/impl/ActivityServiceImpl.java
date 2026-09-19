package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.dto.ActivityVOs.AudienceItem;
import ai.neargo.shop.promotion.dto.ActivityVOs.ConflictVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityRule;
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
    private final ai.neargo.shop.spi.product.GoodsQueryPort goodsPort;

    /** 自己组合的行（P3b）。setter 注入：没有它的切片测试里组合行不落库，其余行为不变 */
    private ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityRuleMapper ruleMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuleMapper(ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    public ActivityServiceImpl(ActivityMapper activityMapper, ActivityAudienceMapper audienceMapper,
                               ActivityGoodsMapper goodsMapper,
                               ai.neargo.shop.spi.product.GoodsQueryPort goodsPort) {
        this.activityMapper = activityMapper;
        this.audienceMapper = audienceMapper;
        this.goodsMapper = goodsMapper;
        this.goodsPort = goodsPort;
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
        /*
         * A6（原型 s35）：**开始了的活动只能改结束时间与上限**（份数、预算、每期份数）。
         * 已有订单按旧规则算过价，这时改门槛 / 优惠 / 商品 / 人群，同一个活动就有了两种价 ——
         * 对账时说不清哪一单按哪一版算。改规则请结束后另建。还没开始的活动照常全改。
         */
        String lockedBefore = create || !started(a) ? null : lockedSignature(vo(a));
        apply(a, d);
        if (lockedBefore != null && !lockedBefore.equals(lockedSignature(vo(a), d))) {
            throw BizException.of(ErrorCode.ACTIVITY_RULE_LOCKED);
        }
        assertSane(a, d);
        assertNoGroupOverlap(a, d);
        boolean started = !create && started(a);
        if (!started) {
            // 开始了的活动人群已锁（见上面的 lockedSignature），不必再判；没开始的每次保存都判
            assertAudienceNotEmpty(entityNo, d.audiences());
        }

        if (create) {
            activityMapper.insert(a);
        } else {
            activityMapper.updateById(a);
        }
        saveAudiences(entityNo, a.getActivityNo(), d.audiences(), started);
        saveGoods(entityNo, a.getActivityNo(), d.goodsNos());
        saveRules(a.getActivityNo(), PmtActivity.TRIGGER_COMBO.equals(a.getTriggerType()), d.rules());
        log.info("[活动] {} {} by {}", create ? "建" : "改", a.getActivityNo(), operatorNo);
        return vo(a);
    }

    /** 开始了没有：进行中或暂停着、且开始时刻已过（长期活动没有开始时刻，建好即开始） */
    private static boolean started(PmtActivity a) {
        boolean live = PmtActivity.RUNNING.equals(a.getStatus()) || PmtActivity.PAUSED.equals(a.getStatus());
        return live && (a.getStartAt() == null || a.getStartAt() <= System.currentTimeMillis());
    }

    /**
     * 开始之后<b>不许变</b>的那些字段拼成一串。结束时间、份数、预算、每期份数不在里面 —— 那是 A6 放开的四项。
     * 商品与人群按集合比（顺序无关），其余按值比。
     */
    private static String lockedSignature(ActivityVO v) {
        return lockedSignature(v, v.goodsNos(), v.audiences()) + "|" + rulesSig(v.rules());
    }

    private static String lockedSignature(ActivityVO v, ActivityDraft d) {
        boolean combo = PmtActivity.TRIGGER_COMBO.equals(v.triggerType());
        return lockedSignature(v, d.goodsNos(), d.audiences()) + "|" + rulesSig(combo ? d.rules() : List.of());
    }

    /** 组合行也是规则的一部分：开始之后改条件或优惠一样被锁（A6） */
    private static String rulesSig(List<ai.neargo.shop.promotion.dto.ActivityVOs.RuleItem> rules) {
        return rules == null ? "[]" : rules.stream().map(ActivityServiceImpl::writeRuleSig).toList().toString();
    }

    private static String writeRuleSig(ai.neargo.shop.promotion.dto.ActivityVOs.RuleItem r) {
        List<String> goods = r.goodsNos() == null ? List.of() : new java.util.TreeSet<>(r.goodsNos()).stream().toList();
        return r.kind() + ":" + r.type() + ":" + r.amountMinor() + ":" + r.n() + ":" + r.bp() + ":" + r.capMinor() + ":" + goods;
    }

    private static String lockedSignature(ActivityVO v, List<String> goods, List<AudienceItem> audiences) {
        java.util.TreeSet<String> g = new java.util.TreeSet<>(goods == null ? List.of() : goods);
        java.util.TreeSet<String> au = new java.util.TreeSet<>();
        (audiences == null ? List.<AudienceItem>of() : audiences).forEach(x -> au.add(x.type() + "=" + x.value()));
        return String.join("|", java.util.Arrays.asList(
                String.valueOf(v.name()), String.valueOf(v.storeNo()), String.valueOf(v.triggerType()),
                String.valueOf(v.triggerAmountMinor()), String.valueOf(v.triggerQty()),
                String.valueOf(v.benefitType()), String.valueOf(v.benefitAmountMinor()),
                String.valueOf(v.benefitQty()), String.valueOf(v.benefitRef()),
                String.valueOf(v.scheduleType()), String.valueOf(v.startAt()), String.valueOf(v.scheduleRule()),
                String.valueOf(v.cutoffTime()), String.valueOf(v.pickupOffset()), String.valueOf(v.pickupFrom()),
                String.valueOf(v.minQty()), String.valueOf(v.decideHours()), String.valueOf(v.groupHours()),
                g.toString(), au.toString()));
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
        /*
         * 集单 / 拼团参数**只在对应触发下落库**，其余玩法一律清空 ——
         * 否则把一个集单活动改成满减后，它身上还挂着截单时刻，
         * 而凡是「有截单时刻就当集单」的地方都会被它骗到（新值漏进老分支）。
         */
        if (PmtActivity.TRIGGER_COMBO.equals(a.getTriggerType())) {
            // 组合活动的条件与优惠都在 pmt_activity_rule 里：主表那一组清空，免得被当成某个旧玩法读
            a.setBenefitType(PmtActivity.BENEFIT_COMBO);
            a.setTriggerAmountMinor(null);
            a.setTriggerQty(null);
            a.setBenefitAmountMinor(null);
            a.setBenefitQty(null);
            a.setBenefitRef(null);
        }
        boolean cutoff = PmtActivity.TRIGGER_CUTOFF.equals(a.getTriggerType());
        a.setCutoffTime(cutoff ? blankToNull(d.cutoffTime()) : null);
        a.setPickupOffset(cutoff ? d.pickupOffset() : null);
        a.setPickupFrom(cutoff ? blankToNull(d.pickupFrom()) : null);
        a.setMinQty(cutoff ? d.minQty() : null);
        a.setPeriodQuota(cutoff ? d.periodQuota() : null);
        a.setDecideHours(cutoff ? d.decideHours() : null);
        a.setGroupHours(PmtActivity.TRIGGER_GROUP.equals(a.getTriggerType()) ? d.groupHours() : null);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** HH:mm，00:00–23:59 */
    private static boolean isClock(String v) {
        return v != null && v.matches("([01]\\d|2[0-3]):[0-5]\\d");
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
    /**
     * 自己组合（原型 s11）的硬校验：至少一个条件、至少一个优惠，每一行的参数成立。
     * 触发与优惠两列必须同时是 COMBO —— 只改一列的话，老代码按另一列走进某个旧分支（新值漏进老分支）。
     */
    private void assertCombo(PmtActivity a, ActivityDraft d) {
        if (!PmtActivity.TRIGGER_COMBO.equals(a.getTriggerType())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<ai.neargo.shop.promotion.dto.ActivityVOs.RuleItem> rules = d.rules() == null ? List.of() : d.rules();
        long conditions = rules.stream().filter(r -> PmtActivityRule.CONDITION.equals(r.kind())).count();
        long benefits = rules.stream().filter(r -> PmtActivityRule.BENEFIT.equals(r.kind())).count();
        if (conditions == 0 || benefits == 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        for (var r : rules) {
            boolean ok = switch (r.kind() + ":" + r.type()) {
                case "CONDITION:AMOUNT" -> r.amountMinor() != null && r.amountMinor() > 0;
                case "CONDITION:QTY" -> r.n() != null && r.n() > 0;
                case "CONDITION:GOODS" -> r.goodsNos() != null && !r.goodsNos().isEmpty();
                case "BENEFIT:CUT" -> r.amountMinor() != null && r.amountMinor() > 0;
                // 打折必须封顶：大额订单上不封顶的折扣是不可控的敞口（与折扣券同一条）
                case "BENEFIT:PERCENT" -> r.bp() != null && r.bp() >= 1000 && r.bp() < 10_000
                        && r.capMinor() != null && r.capMinor() > 0;
                case "BENEFIT:POINTS" -> r.n() != null && r.n() > 0;
                default -> false;
            };
            if (!ok) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }

    /** 整批换掉这个活动的组合行；不是组合活动时清空（从组合改成别的玩法，旧行不能留着） */
    private void saveRules(String activityNo, boolean combo, List<ai.neargo.shop.promotion.dto.ActivityVOs.RuleItem> rules) {
        if (ruleMapper == null) {
            return;
        }
        ruleMapper.hardDeleteByActivity(activityNo);
        if (!combo || rules == null) {
            return;
        }
        int seq = 0;
        for (var r : rules) {
            PmtActivityRule row = new PmtActivityRule();
            row.setActivityNo(activityNo);
            row.setKind(r.kind());
            row.setSeq(seq++);
            row.setRuleType(r.type());
            row.setParams(writeParams(r));
            ruleMapper.insert(row);
        }
    }

    private List<ai.neargo.shop.promotion.dto.ActivityVOs.RuleItem> rulesOf(String activityNo) {
        if (ruleMapper == null) {
            return List.of();
        }
        return DataScopeContext.executeWithoutScope(() -> ruleMapper.selectList(
                        Wrappers.<PmtActivityRule>lambdaQuery()
                                .eq(PmtActivityRule::getActivityNo, activityNo)
                                .orderByAsc(PmtActivityRule::getSeq)))
                .stream().map(ActivityServiceImpl::readParams).toList();
    }

    /** 参数按 JSON 存（每种行的字段不同）。只写有值的那几项 */
    static String writeParams(ai.neargo.shop.promotion.dto.ActivityVOs.RuleItem r) {
        StringBuilder b = new StringBuilder("{");
        java.util.function.BiConsumer<String, Object> put = (k, v) -> {
            if (v == null) {
                return;
            }
            if (b.length() > 1) {
                b.append(',');
            }
            b.append('"').append(k).append("\":");
            if (v instanceof List<?> l) {
                b.append('[').append(l.stream().map(x -> "\"" + String.valueOf(x).replace("\"", "") + "\"")
                        .collect(java.util.stream.Collectors.joining(","))).append(']');
            } else {
                b.append(v);
            }
        };
        put.accept("amountMinor", r.amountMinor());
        put.accept("n", r.n());
        put.accept("bp", r.bp());
        put.accept("capMinor", r.capMinor());
        put.accept("goodsNos", r.goodsNos());
        return b.append('}').toString();
    }

    static ai.neargo.shop.promotion.dto.ActivityVOs.RuleItem readParams(PmtActivityRule row) {
        String p = row.getParams() == null ? "" : row.getParams();
        List<String> goods = new java.util.ArrayList<>();
        java.util.regex.Matcher gm = java.util.regex.Pattern.compile("\"goodsNos\":\\[([^\\]]*)\\]").matcher(p);
        if (gm.find()) {
            for (String s : gm.group(1).split(",")) {
                String v = s.trim().replace("\"", "");
                if (!v.isEmpty()) {
                    goods.add(v);
                }
            }
        }
        return new ai.neargo.shop.promotion.dto.ActivityVOs.RuleItem(row.getKind(), row.getRuleType(),
                longOf(p, "amountMinor"), intOf(p, "n"), intOf(p, "bp"), longOf(p, "capMinor"),
                goods.isEmpty() ? null : goods);
    }

    private static Long longOf(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    private static Integer intOf(String json, String key) {
        Long v = longOf(json, key);
        return v == null ? null : v.intValue();
    }

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
            case PmtActivity.TRIGGER_CUTOFF -> {
                /*
                 * 社区集单（ADR-024）：
                 * - 截单时刻必填且合法 —— 算不出截单时刻，就没有「一期」
                 * - 优惠只能是改单价（集单价），理由与团购那条相同
                 * - P1 只支持每天一期：周期排期（每周几）的期怎么排还没定，存进来会按天开期，
                 *   与商家以为的「只在周三」不一致 —— 拦住比悄悄按天开好
                 * - 起订量、每期上限、处理时限只要填了就得是正数；提货偏移不能是负数
                 */
                if (!isClock(a.getCutoffTime())
                        || !PmtActivity.BENEFIT_PRICE.equals(a.getBenefitType())
                        || PmtActivity.RECURRING.equals(a.getScheduleType())
                        || (a.getPickupFrom() != null && !isClock(a.getPickupFrom()))
                        || (a.getPickupOffset() != null && a.getPickupOffset() < 0)
                        || (a.getMinQty() != null && a.getMinQty() <= 0)
                        || (a.getPeriodQuota() != null && a.getPeriodQuota() <= 0)
                        || (a.getDecideHours() != null && a.getDecideHours() <= 0)) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
                if (d.goodsNos() != null && !d.goodsNos().isEmpty()
                        && !goodsPort.presaleGoods(d.goodsNos()).isEmpty()) {
                    throw BizException.of(ErrorCode.GOODS_IN_PRESALE);
                }
            }
            default -> { /* NONE 与 GOODS 没有额外参数 */ }
        }
        /*
         * **算不出来的组合，存那一侧就拒**。
         *
         * 放行而定价无分支 = 一个存得下、列表上写着「进行中」、下单一分不减、
         * 且不报错的死活动。今天拦住它的是 B 端 `TYPES` 只给四个入口 ——
         * 靠前端不给按钮挡着的东西，换个调用方就没了。
         *
         * - `BENEFIT_COUPON`：发券有自己的一页，两处都能发会让人不知道去哪儿
         *   （这是 activity-edit 里已写下的决定，这里把它从「前端不给」变成「后端不收」）
         * - `GOODS × CUT`：`CampaignPort` 这一侧只有按商家汇总的金额，
         *   减在哪一件上无从摊分；要支持得先给 Port 加按商品的减免通道
         *
         * 哪天真要支持，红的是这一行 —— 正好提醒把定价那侧一起补上。
         */
        if (PmtActivity.BENEFIT_COUPON.equals(a.getBenefitType())
                || (PmtActivity.BENEFIT_CUT.equals(a.getBenefitType())
                        && PmtActivity.TRIGGER_GOODS.equals(a.getTriggerType()))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
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
            case PmtActivity.BENEFIT_COMBO -> assertCombo(a, d);
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

    /**
     * 受众整批换掉：增量在「删掉一个标签」上一定会漏。
     *
     * <p><b>人群项要抄一份当时的条件</b>（AC-9）：算价按快照判，商家之后改人群不会改掉这个活动的受众。
     * 开始了的活动<b>沿用旧快照</b> —— 它每次保存（改结束时间、加份数）也走这里，
     * 重抄一次就等于把受众悄悄换成了人群的最新条件。存量活动没有快照的，照旧留空（按人群号当场算）。
     */
    private void saveAudiences(String entityNo, String activityNo, List<AudienceItem> items,
                               boolean keepSnapshots) {
        java.util.Map<String, String> oldSnapshots = new java.util.HashMap<>();
        if (keepSnapshots) {
            for (PmtActivityAudience r : audienceMapper.selectList(Wrappers.<PmtActivityAudience>lambdaQuery()
                    .eq(PmtActivityAudience::getActivityNo, activityNo))) {
                if (r.getRuleSnapshot() != null) {
                    oldSnapshots.put(r.getAudienceType() + "=" + r.getAudienceValue(), r.getRuleSnapshot());
                }
            }
        }
        audienceMapper.hardDeleteByActivity(activityNo);
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
            if (PmtActivityAudience.SEGMENT.equals(it.type())) {
                row.setRuleSnapshot(keepSnapshots
                        ? oldSnapshots.get(it.type() + "=" + it.value())
                        : memberPort == null ? null : memberPort.segmentSnapshot(entityNo, it.value()));
            }
            audienceMapper.insert(row);
        }
    }

    /**
     * 受众此刻一个人都没有就不让发布（AC-10）。空受众 = 所有人、「非本店会员」数不出来 —— 这两种不判。
     */
    private void assertAudienceNotEmpty(String entityNo, List<AudienceItem> items) {
        if (items == null || memberPort == null) {
            return;
        }
        List<ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem> clean = items.stream()
                .filter(it -> it != null && !blank(it.type()) && !blank(it.value()))
                .map(it -> new ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem(it.type(), it.value()))
                .toList();
        if (clean.isEmpty()) {
            return;
        }
        var r = memberPort.resolve(entityNo, clean, null);
        if (r.countable() && r.matched() == 0) {
            throw BizException.of(ErrorCode.MEMBER_AUDIENCE_EMPTY);
        }
    }

    /** 受众判定与人群快照要问会员域。setter 注入，理由同 ruleMapper */
    private ai.neargo.shop.spi.member.MemberQueryPort memberPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMemberPort(ai.neargo.shop.spi.member.MemberQueryPort memberPort) {
        this.memberPort = memberPort;
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
                a.isActiveAt(System.currentTimeMillis(), MARKET_ZONE) && a.hasQuotaLeft(),
                a.getCutoffTime(), a.getPickupOffset(), a.getPickupFrom(),
                a.getMinQty(), a.getPeriodQuota(), a.getDecideHours(), a.getGroupHours(),
                PmtActivity.TRIGGER_COMBO.equals(a.getTriggerType()) ? rulesOf(a.getActivityNo()) : List.of());
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
