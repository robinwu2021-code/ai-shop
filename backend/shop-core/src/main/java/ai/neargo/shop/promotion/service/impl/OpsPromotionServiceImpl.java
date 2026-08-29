package ai.neargo.shop.promotion.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.OpsPromotionVOs.OpsActivityVO;
import ai.neargo.shop.promotion.dto.OpsPromotionVOs.OpsCouponVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityAudience;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityAudienceMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.service.OpsPromotionService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class OpsPromotionServiceImpl implements OpsPromotionService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OpsPromotionServiceImpl.class);

    /** 单张优惠超过它就标出来。50 元 —— 社区生鲜的客单价在几十块，这个数已经很大了 */
    private static final long HIGH_VALUE_MINOR = 5_000L;
    /** 剩余不足这个比例就提醒 */
    private static final double TIGHT = 0.1d;

    private final CouponMapper couponMapper;
    private final ActivityMapper activityMapper;
    private final ActivityAudienceMapper audienceMapper;
    private final MerchantQueryPort merchantPort;
    private final AuditLogPort auditLogPort;

    public OpsPromotionServiceImpl(CouponMapper couponMapper, ActivityMapper activityMapper,
                                   ActivityAudienceMapper audienceMapper,
                                   MerchantQueryPort merchantPort, AuditLogPort auditLogPort) {
        this.couponMapper = couponMapper;
        this.activityMapper = activityMapper;
        this.audienceMapper = audienceMapper;
        this.merchantPort = merchantPort;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public List<OpsCouponVO> coupons(String entityNo) {
        /*
         * 接数据域（2026-08-29）。
         *
         * 此前这里写着「运营会话的维度在 pmt_* 上找不到锚点，不绕就是空列表」——
         * **那句话已经不成立**：`pmt_coupon` 早已登记了 MERCHANT 锚点（entity_no）。
         * 留着绕过的后果是「给这个运营配了只看某商家」在这一页上完全不生效，
         * 而页面上没有任何线索说明这一点。
         *
         * COMMUNITY / PICKUP 两个维度确实在这张表上没有锚点（fail-closed → 空列表），
         * 但没有任何角色同时持有社区/自提点数据域与 marketing:* —— 见
         * ops-data-scope.test.ts 的 ANCHOR_WAIVED 那段判据。
         */
        return couponMapper.selectList(Wrappers.<PmtCoupon>lambdaQuery()
                        .eq(entityNo != null && !entityNo.isBlank(),
                                PmtCoupon::getEntityNo, entityNo)
                        .orderByDesc(PmtCoupon::getId))
                .stream().map(this::vo).toList();
    }

    @Override
    public List<OpsActivityVO> activities(String entityNo) {
        return DataScopeContext.executeWithoutScope(() ->
                activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                                .eq(entityNo != null && !entityNo.isBlank(),
                                        PmtActivity::getEntityNo, entityNo)
                                .orderByDesc(PmtActivity::getId))
                        .stream().map(this::vo).toList());
    }

    @Override
    @Transactional
    public OpsActivityVO stop(String activityNo, String reason, String operatorNo) {
        if (reason == null || reason.trim().length() < 4) {
            // 平台停掉商家的活动是单方面动作，不给理由等于让他去猜
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return DataScopeContext.executeWithoutScope(() -> {
            PmtActivity a = activityMapper.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                    .eq(PmtActivity::getActivityNo, activityNo).last("limit 1"));
            if (a == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            if (PmtActivity.ENDED.equals(a.getStatus())) {
                throw BizException.of(ErrorCode.ACTIVITY_ENDED_IMMUTABLE);
            }
            a.setStatus(PmtActivity.ENDED);
            /*
             * ended_reason 记 MANUAL 而不是新造一个「平台停的」——
             * 商家那边的界面已经会显示它，多一个取值就要多改一处端上文案。
             * 「谁停的、为什么」在审计里，那才是要追责时看的地方。
             */
            a.setEndedReason(PmtActivity.ENDED_MANUAL);
            activityMapper.updateById(a);
            auditLogPort.record("ACTIVITY_FORCE_STOP", activityNo,
                    "平台强制停止，原因：" + reason.trim());
            log.info("[活动] {} 被平台停止 by {}，原因：{}", activityNo, operatorNo, reason);
            return vo(a);
        });
    }

    private OpsCouponVO vo(PmtCoupon c) {
        long per = switch (c.getBenefitMode()) {
            case PmtCoupon.CASH -> nz(c.getBenefitValue());
            case PmtCoupon.PERCENT -> nz(c.getBenefitCapMinor());
            default -> 0L;
        };
        Long exposure = c.getTotalCount() == null ? null : c.getTotalCount() * per;

        /*
         * 异常标记：**平台要在出事之前看见**。
         * 这几条都不阻止商家（建券那一步已经拦过一轮硬性的），只是排在一起时能一眼看出
         * 「谁家的券可能会失控」—— 商家自己看不出来，他只看得到他那一张。
         */
        List<String> flags = new ArrayList<>();
        if (nz(c.getBudgetMinor()) <= 0) {
            flags.add("NO_BUDGET");
        }
        if (c.getTotalCount() == null) {
            flags.add("UNLIMITED");
        }
        if (per >= HIGH_VALUE_MINOR) {
            flags.add("HIGH_VALUE");
        }
        if (c.getTotalCount() != null && c.getTotalCount() > 0
                && (c.getTotalCount() - nz(c.getReceivedCount())) <= c.getTotalCount() * TIGHT) {
            flags.add("NEARLY_OUT");
        }
        return new OpsCouponVO(c.getCouponNo(), c.getEntityNo(), entityName(c.getEntityNo()),
                c.getTitle(), c.getBenefitMode(), nz(c.getBenefitValue()), c.getBenefitCapMinor(),
                c.getTotalCount(), nz(c.getReceivedCount()), c.getBudgetMinor(),
                exposure, c.getStatus(), flags);
    }

    private OpsActivityVO vo(PmtActivity a) {
        int audiences = Math.toIntExact(nzL(audienceMapper.selectCount(
                Wrappers.<PmtActivityAudience>lambdaQuery()
                        .eq(PmtActivityAudience::getActivityNo, a.getActivityNo()))));
        List<String> flags = new ArrayList<>();
        boolean capped = a.getQuota() != null || nz(a.getBudgetMinor()) > 0;
        if (PmtActivity.ALWAYS_ON.equals(a.getScheduleType()) && !capped) {
            // 建活动那一步已经拦住了新的，但存量或改过的可能漏网
            flags.add("ALWAYS_ON_UNCAPPED");
        }
        if (a.getQuota() != null && a.getQuota() > 0
                && (a.getQuota() - nz(a.getQuotaUsed())) <= a.getQuota() * TIGHT) {
            flags.add("QUOTA_NEARLY_OUT");
        }
        if (PmtActivity.ENDED_QUOTA.equals(a.getEndedReason())) {
            flags.add("ENDED_BY_QUOTA");
        }
        return new OpsActivityVO(a.getActivityNo(), a.getEntityNo(), entityName(a.getEntityNo()),
                a.getName(), a.getTriggerType(), a.getBenefitType(), a.getScheduleType(),
                a.getQuota(), nz(a.getQuotaUsed()), a.getBudgetMinor(), nz(a.getBudgetUsedMinor()),
                audiences, a.getStatus(), a.getEndedReason(), flags);
    }

    private String entityName(String entityNo) {
        return entityNo == null ? null
                : merchantPort.find(entityNo).map(MerchantQueryPort.MerchantBrief::merchantName)
                        .orElse(entityNo);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nzL(Long v) {
        return v == null ? 0L : v;
    }
}
