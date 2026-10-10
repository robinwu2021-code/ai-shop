package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollCommand;
import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollRule;
import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollmentVO;
import ai.neargo.shop.promotion.dto.PlatformVOs.PlatformActivityVO;
import ai.neargo.shop.promotion.dto.PlatformVOs.PlatformDraft;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtEnrollment;
import ai.neargo.shop.promotion.entity.PmtEnrollmentGoods;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.EnrollmentGoodsMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.EnrollmentMapper;
import ai.neargo.shop.promotion.service.PlatformActivityService;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link PlatformActivityService} 的实现。见接口注释。
 *
 * <h2>数据域</h2>
 * <ul>
 *   <li>平台活动行（{@code entity_no = 'PLATFORM'}）不属于任何商家：读它与占它的预算一律绕域，
 *       边界靠显式的 {@code owner = PLATFORM} 条件 —— 不绕的话，配了「只看某商家」的运营
 *       会把「占预算」算成 0 行，报成「超出预算」。</li>
 *   <li>报名单按商家登记了数据域：<b>运营读报名走域</b>（只看某商家的运营只看到那家的报名），
 *       商家读报名显式钉死 {@code entity_no = 自己}。</li>
 * </ul>
 */
@Service
public class PlatformActivityServiceImpl implements PlatformActivityService {

    private static final Logger log = LoggerFactory.getLogger(PlatformActivityServiceImpl.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<Integer> SHARES = Set.of(0, 5000, 10000);
    private static final Set<String> CUT_TRIGGERS = Set.of(
            PmtActivity.TRIGGER_NONE, PmtActivity.TRIGGER_AMOUNT, PmtActivity.TRIGGER_QTY);

    private final ActivityMapper activityMapper;
    private final EnrollmentMapper enrollmentMapper;
    private final EnrollmentGoodsMapper enrollmentGoodsMapper;
    private final MerchantQueryPort merchantPort;
    private final GoodsQueryPort goodsPort;
    private final ObjectMapper json;

    public PlatformActivityServiceImpl(ActivityMapper activityMapper, EnrollmentMapper enrollmentMapper,
                                       EnrollmentGoodsMapper enrollmentGoodsMapper,
                                       MerchantQueryPort merchantPort, GoodsQueryPort goodsPort,
                                       ObjectMapper json) {
        this.activityMapper = activityMapper;
        this.enrollmentMapper = enrollmentMapper;
        this.enrollmentGoodsMapper = enrollmentGoodsMapper;
        this.merchantPort = merchantPort;
        this.goodsPort = goodsPort;
        this.json = json;
    }

    // ================================================================ 运营

    @Override
    public List<PlatformActivityVO> opsList() {
        List<PmtActivity> all = DataScopeContext.executeWithoutScope(() ->
                activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getOwner, PmtActivity.OWNER_PLATFORM)
                        .orderByDesc(PmtActivity::getId)));
        Map<String, List<PmtEnrollment>> byActivity = enrollmentsOf(all.stream().map(PmtActivity::getActivityNo).toList());
        return all.stream().map(a -> vo(a, byActivity.getOrDefault(a.getActivityNo(), List.of()), null)).toList();
    }

    @Override
    @Transactional
    public PlatformActivityVO save(PlatformDraft d, String operatorNo) {
        PmtActivity a = d.activityNo() == null || d.activityNo().isBlank() ? null : requirePlatform(d.activityNo());
        boolean create = a == null;
        if (create) {
            a = new PmtActivity();
            a.setActivityNo(BizKey.next(BizKey.PROMO_ACTIVITY));
            a.setEntityNo(PmtActivity.PLATFORM_ENTITY);
            a.setOwner(PmtActivity.OWNER_PLATFORM);
            a.setQuotaUsed(0);
            a.setBudgetUsedMinor(0L);
            a.setEnrollReservedMinor(0L);
            a.setStatus(PmtActivity.DRAFT);
        } else if (PmtActivity.ENDED.equals(a.getStatus())) {
            throw BizException.of(ErrorCode.ACTIVITY_ENDED_IMMUTABLE);
        }
        boolean published = !create && !PmtActivity.DRAFT.equals(a.getStatus());
        if (published) {
            /*
             * 发布之后商家已经照着这套规则报名、算过「最多承担」：只放开报名截止与预算。
             * 预算不能低于已占的 —— 否则已通过的报名占着一笔不存在的钱。
             */
            if (d.budgetMinor() != null && d.budgetMinor() < nz(a.getEnrollReservedMinor())) {
                throw BizException.of(ErrorCode.ACTIVITY_RULE_LOCKED);
            }
            a.setEnrollDeadline(d.enrollDeadline());
            a.setBudgetMinor(d.budgetMinor());
        } else {
            apply(a, d);
            assertSane(a);
            if (d.publish()) {
                a.setStatus(PmtActivity.RUNNING);
            }
        }
        final PmtActivity row = a;
        if (create) {
            DataScopeContext.executeWithoutScope(() -> activityMapper.insert(row));
        } else {
            DataScopeContext.executeWithoutScope(() -> activityMapper.updateById(row));
        }
        log.info("[平台活动] {} {} by {}", create ? "建" : "改", a.getActivityNo(), operatorNo);
        return vo(a, enrollmentsOf(List.of(a.getActivityNo())).getOrDefault(a.getActivityNo(), List.of()), null);
    }

    private void apply(PmtActivity a, PlatformDraft d) {
        a.setName(d.name() == null ? null : d.name().trim());
        a.setTriggerType(d.triggerType() == null || d.triggerType().isBlank() ? PmtActivity.TRIGGER_NONE : d.triggerType());
        a.setTriggerAmountMinor(PmtActivity.TRIGGER_AMOUNT.equals(a.getTriggerType()) ? d.triggerAmountMinor() : null);
        a.setTriggerQty(PmtActivity.TRIGGER_QTY.equals(a.getTriggerType()) ? d.triggerQty() : null);
        a.setBenefitType(PmtActivity.BENEFIT_CUT);
        a.setBenefitAmountMinor(d.benefitAmountMinor());
        a.setScheduleType(PmtActivity.ONE_OFF);
        a.setStartAt(d.startAt());
        a.setEndAt(d.endAt());
        a.setEnrollDeadline(d.enrollDeadline());
        a.setPlatformShareBp(d.platformShareBp() == null ? 0 : d.platformShareBp());
        a.setBudgetMinor(d.budgetMinor());
        a.setEnrollRule(writeRule(d.enrollRule() == null ? EnrollRule.none() : d.enrollRule()));
    }

    /**
     * 与商家活动同一类硬校验，外加平台特有的三条。
     * P3a 只收减钱类（满减 / 满件减 / 立减）：改单价类（秒杀 / 特价）的补贴要跟着单价走，
     * 算价那一侧还没有把「单价差 × 出资比例」落进平台出资 —— 放出去的话平台那份钱没人记账。
     */
    private void assertSane(PmtActivity a) {
        if (a.getName() == null || a.getName().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!CUT_TRIGGERS.contains(a.getTriggerType()) || nz(a.getBenefitAmountMinor()) <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (PmtActivity.TRIGGER_AMOUNT.equals(a.getTriggerType())
                && nz(a.getTriggerAmountMinor()) <= nz(a.getBenefitAmountMinor())) {
            // 满 99 减 100 等于倒贴：门槛必须高于减的钱
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (PmtActivity.TRIGGER_QTY.equals(a.getTriggerType()) && (a.getTriggerQty() == null || a.getTriggerQty() < 1)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (a.getStartAt() == null || a.getEndAt() == null || a.getEndAt() <= a.getStartAt()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (a.getEnrollDeadline() == null || a.getEnrollDeadline() > a.getStartAt()) {
            // 报名要在活动开始前截止：开始之后再有人报进来，审核还没过，货已经在按原价卖了
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!SHARES.contains(a.getPlatformShareBp())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (a.getPlatformShareBp() > 0 && nz(a.getBudgetMinor()) <= 0) {
            // 平台出钱就要有上限：不设预算等于给所有报名的店开了一张空白支票
            throw BizException.of(ErrorCode.ACTIVITY_ALWAYS_ON_NEEDS_CAP);
        }
    }

    @Override
    public List<EnrollmentVO> enrollments(String activityNo, String status) {
        requirePlatform(activityNo);
        // 走数据域：只看某些商家的运营只看到那些商家的报名
        List<PmtEnrollment> rows = enrollmentMapper.selectList(Wrappers.<PmtEnrollment>lambdaQuery()
                .eq(PmtEnrollment::getActivityNo, activityNo)
                .eq(status != null && !status.isBlank(), PmtEnrollment::getStatus, status)
                .orderByAsc(PmtEnrollment::getId));
        return rows.stream().map(this::enrollmentVO).toList();
    }

    /*
     * 占预算与改状态是两条带条件的 UPDATE。后一条 0 行时**显式**把预算还回去再抛 ——
     * 抛异常本身也会让事务回滚，但显式还一次，读代码的人不用去推「这里有没有事务」。
     */
    @Override
    @Transactional
    public EnrollmentVO review(String enrollmentNo, boolean pass, String reason, String operatorNo) {
        PmtEnrollment e = enrollmentMapper.selectOne(Wrappers.<PmtEnrollment>lambdaQuery()
                .eq(PmtEnrollment::getEnrollmentNo, enrollmentNo).last("limit 1"));
        if (e == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!PmtEnrollment.SUBMITTED.equals(e.getStatus())) {
            // 两个运营同时审：后到的看到「已经审过了」，而不是以为自己的判断生效了
            throw BizException.of(ErrorCode.CONFLICT);
        }
        long now = System.currentTimeMillis();
        if (!pass) {
            if (reason == null || reason.isBlank()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            int moved = enrollmentMapper.update(null, Wrappers.<PmtEnrollment>lambdaUpdate()
                    .set(PmtEnrollment::getStatus, PmtEnrollment.REJECTED)
                    .set(PmtEnrollment::getRejectReason, reason.trim())
                    .set(PmtEnrollment::getReviewedBy, operatorNo)
                    .set(PmtEnrollment::getReviewedAt, now)
                    .eq(PmtEnrollment::getEnrollmentNo, enrollmentNo)
                    .eq(PmtEnrollment::getStatus, PmtEnrollment.SUBMITTED));
            if (moved == 0) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return enrollmentVO(reload(enrollmentNo));
        }
        long need = nz(e.getPlatformMaxMinor());
        /*
         * ★ 占平台预算（AC-14）：一条带条件的 UPDATE。「已占 + 这份 ≤ 预算」不成立就 0 行 → 拒。
         * 先读再判的写法在两个运营同时点通过时会两边都判「够」，然后一起把预算打穿。
         */
        int reserved = DataScopeContext.executeWithoutScope(() -> activityMapper.update(null,
                Wrappers.<PmtActivity>lambdaUpdate()
                        .setSql("enroll_reserved_minor = enroll_reserved_minor + " + need)
                        .eq(PmtActivity::getActivityNo, e.getActivityNo())
                        .eq(PmtActivity::getOwner, PmtActivity.OWNER_PLATFORM)
                        .and(w -> w.isNull(PmtActivity::getBudgetMinor)
                                .or().apply("enroll_reserved_minor + {0} <= budget_minor", need))));
        if (reserved == 0) {
            throw BizException.of(ErrorCode.ENROLLMENT_OVER_BUDGET);
        }
        int moved = enrollmentMapper.update(null, Wrappers.<PmtEnrollment>lambdaUpdate()
                .set(PmtEnrollment::getStatus, PmtEnrollment.APPROVED)
                .set(PmtEnrollment::getReviewedBy, operatorNo)
                .set(PmtEnrollment::getReviewedAt, now)
                .eq(PmtEnrollment::getEnrollmentNo, enrollmentNo)
                .eq(PmtEnrollment::getStatus, PmtEnrollment.SUBMITTED));
        if (moved == 0) {
            // 这一刻被别人审掉了 / 商家撤回了：占的预算还回去
            DataScopeContext.executeWithoutScope(() -> activityMapper.update(null,
                    Wrappers.<PmtActivity>lambdaUpdate()
                            .setSql("enroll_reserved_minor = enroll_reserved_minor - " + need)
                            .eq(PmtActivity::getActivityNo, e.getActivityNo())));
            throw BizException.of(ErrorCode.CONFLICT);
        }
        log.info("[平台活动] 通过报名 {} 占预算 {} by {}", enrollmentNo, need, operatorNo);
        return enrollmentVO(reload(enrollmentNo));
    }

    // ================================================================ 商家

    @Override
    public List<PlatformActivityVO> forMerchant(String entityNo, String tab) {
        List<PmtActivity> all = DataScopeContext.executeWithoutScope(() ->
                activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getOwner, PmtActivity.OWNER_PLATFORM)
                        .ne(PmtActivity::getStatus, PmtActivity.DRAFT)
                        .orderByDesc(PmtActivity::getId)));
        Map<String, PmtEnrollment> mine = mineOf(entityNo);
        long now = System.currentTimeMillis();
        List<PlatformActivityVO> out = new ArrayList<>();
        for (PmtActivity a : all) {
            PmtEnrollment m = mine.get(a.getActivityNo());
            String t = tabOf(a, m, now);
            if (tab == null || tab.isBlank() || tab.equals(t)) {
                out.add(vo(a, List.of(), m == null ? null : enrollmentVO(m)));
            }
        }
        return out;
    }

    /**
     * 可报名 = 还没截止且（没报过 / 被驳回 / 撤回了）；已报名 = 待审或已通过、活动没结束；其余已结束。
     * 被驳回的仍算「可报名」：改了货或份数可以再报一次（唯一键在活动 × 商家上，重报改的是同一行）。
     */
    private static String tabOf(PmtActivity a, PmtEnrollment m, long now) {
        boolean over = PmtActivity.ENDED.equals(a.getStatus()) || nz(a.getEndAt()) < now;
        if (over) {
            return "ENDED";
        }
        boolean live = m != null && (PmtEnrollment.SUBMITTED.equals(m.getStatus())
                || PmtEnrollment.APPROVED.equals(m.getStatus()));
        if (live) {
            return "ENROLLED";
        }
        return nz(a.getEnrollDeadline()) >= now ? "ENROLLABLE" : "ENDED";
    }

    @Override
    public PlatformActivityVO detailForMerchant(String entityNo, String activityNo) {
        PmtActivity a = requirePlatform(activityNo);
        if (PmtActivity.DRAFT.equals(a.getStatus())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        PmtEnrollment m = mineOf(entityNo).get(activityNo);
        return vo(a, List.of(), m == null ? null : enrollmentVO(m));
    }

    @Override
    @Transactional
    public EnrollmentVO enroll(String entityNo, String activityNo, EnrollCommand cmd) {
        PmtActivity a = requirePlatform(activityNo);
        long now = System.currentTimeMillis();
        if (!PmtActivity.RUNNING.equals(a.getStatus()) || nz(a.getEnrollDeadline()) < now) {
            throw BizException.of(ErrorCode.ENROLLMENT_CLOSED);
        }
        if (cmd == null || cmd.quota() <= 0 || cmd.goodsNos() == null || cmd.goodsNos().isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        EnrollRule rule = readRule(a.getEnrollRule());
        assertEligible(entityNo, cmd.goodsNos(), rule);

        long perPlatform = perOrderPlatform(a);
        long perMerchant = nz(a.getBenefitAmountMinor()) - perPlatform;
        PmtEnrollment e = mineOf(entityNo).get(activityNo);
        if (e != null && PmtEnrollment.APPROVED.equals(e.getStatus())) {
            // 通过之后预算已经按这份报名占了：改份数要先撤再报，不在这里静默改掉占用
            throw BizException.of(ErrorCode.CONFLICT);
        }
        boolean create = e == null;
        if (create) {
            e = new PmtEnrollment();
            e.setEnrollmentNo(BizKey.next(BizKey.PROMO_ENROLLMENT));
            e.setActivityNo(activityNo);
            e.setEntityNo(entityNo);
            e.setQuotaUsed(0);
        }
        e.setQuota(cmd.quota());
        e.setPlatformMaxMinor(perPlatform * cmd.quota());
        e.setMerchantMaxMinor(perMerchant * cmd.quota());
        e.setStatus(PmtEnrollment.SUBMITTED);
        e.setRejectReason(null);
        e.setReviewedBy(null);
        e.setReviewedAt(null);
        final PmtEnrollment row = e;
        if (create) {
            enrollmentMapper.insert(row);
        } else {
            // updateById 跳过 null 字段：驳回理由与审核人要显式清掉，否则重报之后还挂着上一次的驳回
            enrollmentMapper.update(null, Wrappers.<PmtEnrollment>lambdaUpdate()
                    .set(PmtEnrollment::getQuota, row.getQuota())
                    .set(PmtEnrollment::getPlatformMaxMinor, row.getPlatformMaxMinor())
                    .set(PmtEnrollment::getMerchantMaxMinor, row.getMerchantMaxMinor())
                    .set(PmtEnrollment::getStatus, PmtEnrollment.SUBMITTED)
                    .set(PmtEnrollment::getRejectReason, null)
                    .set(PmtEnrollment::getReviewedBy, null)
                    .set(PmtEnrollment::getReviewedAt, null)
                    .eq(PmtEnrollment::getEnrollmentNo, row.getEnrollmentNo())
                    .eq(PmtEnrollment::getEntityNo, entityNo));
        }
        enrollmentGoodsMapper.hardDeleteByEnrollment(row.getEnrollmentNo());
        for (String g : cmd.goodsNos().stream().distinct().toList()) {
            PmtEnrollmentGoods eg = new PmtEnrollmentGoods();
            eg.setEnrollmentNo(row.getEnrollmentNo());
            eg.setGoodsNo(g);
            enrollmentGoodsMapper.insert(eg);
        }
        return enrollmentVO(reload(row.getEnrollmentNo()));
    }

    /**
     * 报名门槛（s29「报名门槛」「类目」）。评分与违规看主体，类目看报的每一件货 ——
     * 货必须是自己的、在售的，否则报进来的是一件买不到的货。
     */
    private void assertEligible(String entityNo, List<String> goodsNos, EnrollRule rule) {
        var m = merchantPort.find(entityNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (rule.minRating() != null && (m.ratingCount() == 0 || m.rating() < rule.minRating())) {
            throw BizException.of(ErrorCode.ENROLLMENT_CLOSED);
        }
        if (rule.noViolation() && m.breachCount() > 0) {
            throw BizException.of(ErrorCode.ENROLLMENT_CLOSED);
        }
        for (String goodsNo : goodsNos) {
            var snap = goodsPort.snapshotOfGoods(goodsNo).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
            if (!entityNo.equals(snap.merchantNo()) || !snap.onSale()) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            List<String> cats = rule.categoryNos() == null ? List.of() : rule.categoryNos();
            if (!cats.isEmpty() && cats.stream().noneMatch(c -> snap.categoryNo() != null && snap.categoryNo().startsWith(c))) {
                throw BizException.of(ErrorCode.ENROLLMENT_CLOSED);
            }
        }
    }

    @Override
    public EnrollmentVO withdraw(String entityNo, String activityNo) {
        PmtEnrollment e = mineOf(entityNo).get(activityNo);
        if (e == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        int moved = enrollmentMapper.update(null, Wrappers.<PmtEnrollment>lambdaUpdate()
                .set(PmtEnrollment::getStatus, PmtEnrollment.WITHDRAWN)
                .eq(PmtEnrollment::getEnrollmentNo, e.getEnrollmentNo())
                .eq(PmtEnrollment::getEntityNo, entityNo)
                .eq(PmtEnrollment::getStatus, PmtEnrollment.SUBMITTED));
        if (moved == 0) {
            // 已通过的不能自己撤：预算已经为它占了，平台可能已经在宣传名单里放了这家店
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return enrollmentVO(reload(e.getEnrollmentNo()));
    }

    // ================================================================ 装配

    /** 每单平台最多补贴 = 每单优惠 × 出资比例（向下取整到分，零头归商家） */
    static long perOrderPlatform(PmtActivity a) {
        return nz(a.getBenefitAmountMinor()) * (a.getPlatformShareBp() == null ? 0 : a.getPlatformShareBp()) / 10_000;
    }

    private PlatformActivityVO vo(PmtActivity a, List<PmtEnrollment> es, EnrollmentVO mine) {
        long perPlatform = perOrderPlatform(a);
        int submitted = (int) es.stream().filter(e -> PmtEnrollment.SUBMITTED.equals(e.getStatus())).count();
        int approved = (int) es.stream().filter(e -> PmtEnrollment.APPROVED.equals(e.getStatus())).count();
        int rejected = (int) es.stream().filter(e -> PmtEnrollment.REJECTED.equals(e.getStatus())).count();
        return new PlatformActivityVO(a.getActivityNo(), a.getName(),
                a.getTriggerType(), a.getTriggerAmountMinor(), a.getTriggerQty(),
                a.getBenefitType(), a.getBenefitAmountMinor(),
                a.getStartAt(), a.getEndAt(), a.getEnrollDeadline(),
                a.getPlatformShareBp() == null ? 0 : a.getPlatformShareBp(), a.getBudgetMinor(),
                nz(a.getEnrollReservedMinor()),
                perPlatform, nz(a.getBenefitAmountMinor()) - perPlatform,
                readRule(a.getEnrollRule()), a.getStatus(), submitted, approved, rejected, mine);
    }

    private EnrollmentVO enrollmentVO(PmtEnrollment e) {
        List<String> goods = enrollmentGoodsMapper.selectList(Wrappers.<PmtEnrollmentGoods>lambdaQuery()
                        .eq(PmtEnrollmentGoods::getEnrollmentNo, e.getEnrollmentNo())
                        .orderByAsc(PmtEnrollmentGoods::getId))
                .stream().map(PmtEnrollmentGoods::getGoodsNo).toList();
        var m = merchantPort.find(e.getEntityNo());
        return new EnrollmentVO(e.getEnrollmentNo(), e.getActivityNo(), e.getEntityNo(),
                m.map(MerchantQueryPort.MerchantBrief::merchantName).orElse(""),
                goods, e.getQuota() == null ? 0 : e.getQuota(), e.getQuotaUsed() == null ? 0 : e.getQuotaUsed(),
                nz(e.getPlatformMaxMinor()), nz(e.getMerchantMaxMinor()),
                m.map(MerchantQueryPort.MerchantBrief::rating).orElse(0d),
                e.getStatus(), e.getRejectReason(), e.getReviewedAt(),
                e.getCreatedAt() == null ? 0L : e.getCreatedAt().atZone(ZONE).toInstant().toEpochMilli());
    }

    private Map<String, List<PmtEnrollment>> enrollmentsOf(List<String> activityNos) {
        if (activityNos.isEmpty()) {
            return Map.of();
        }
        return enrollmentMapper.selectList(Wrappers.<PmtEnrollment>lambdaQuery()
                        .in(PmtEnrollment::getActivityNo, activityNos)).stream()
                .collect(Collectors.groupingBy(PmtEnrollment::getActivityNo));
    }

    /** 这家店的全部报名，按活动号。显式钉死 entity_no，与会话的数据域无关 */
    private Map<String, PmtEnrollment> mineOf(String entityNo) {
        return DataScopeContext.executeWithoutScope(() -> enrollmentMapper.selectList(
                        Wrappers.<PmtEnrollment>lambdaQuery().eq(PmtEnrollment::getEntityNo, entityNo)))
                .stream()
                .sorted(Comparator.comparing(PmtEnrollment::getId))
                .collect(Collectors.toMap(PmtEnrollment::getActivityNo, e -> e, (x, y) -> y));
    }

    private PmtEnrollment reload(String enrollmentNo) {
        return DataScopeContext.executeWithoutScope(() -> enrollmentMapper.selectOne(
                Wrappers.<PmtEnrollment>lambdaQuery().eq(PmtEnrollment::getEnrollmentNo, enrollmentNo).last("limit 1")));
    }

    private PmtActivity requirePlatform(String activityNo) {
        PmtActivity a = DataScopeContext.executeWithoutScope(() -> activityMapper.selectOne(
                Wrappers.<PmtActivity>lambdaQuery()
                        .eq(PmtActivity::getActivityNo, activityNo)
                        .eq(PmtActivity::getOwner, PmtActivity.OWNER_PLATFORM)
                        .last("limit 1")));
        if (a == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return a;
    }

    private String writeRule(EnrollRule r) {
        try {
            return json.writeValueAsString(r);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private EnrollRule readRule(String s) {
        if (s == null || s.isBlank()) {
            return EnrollRule.none();
        }
        try {
            EnrollRule r = json.readValue(s, EnrollRule.class);
            return new EnrollRule(r.minRating(), r.noViolation(),
                    r.categoryNos() == null ? List.of() : r.categoryNos(),
                    r.cityCodes() == null ? List.of() : r.cityCodes());
        } catch (RuntimeException e) {
            log.warn("[平台活动] 报名门槛读不出来：{}", s);
            return EnrollRule.none();
        }
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
