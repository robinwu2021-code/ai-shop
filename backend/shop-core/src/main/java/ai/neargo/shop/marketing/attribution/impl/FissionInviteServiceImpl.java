package ai.neargo.shop.marketing.attribution.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.marketing.attribution.FissionInviteService;
import ai.neargo.shop.marketing.attribution.entity.MktFissionCampaign;
import ai.neargo.shop.marketing.attribution.entity.MktFissionInvite;
import ai.neargo.shop.marketing.attribution.entity.MktAttributionRule;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.FissionCampaignMapper;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.FissionInviteMapper;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.AttributionRuleMapper;
import ai.neargo.shop.marketing.coupon.CouponService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** 见 {@link FissionInviteService}。 */
@Service
public class FissionInviteServiceImpl implements FissionInviteService {

    private static final Logger log = LoggerFactory.getLogger(FissionInviteServiceImpl.class);

    /** 新客因子，与 AttributionRuleServiceImpl 的 FACTORS 同一套取值 */
    private static final String F_DEVICE = "DEVICE";
    private static final String F_PHONE = "PHONE";

    private final FissionInviteMapper inviteMapper;
    private final FissionCampaignMapper campaignMapper;
    private final AttributionRuleMapper ruleMapper;
    private final CouponService couponService;

    public FissionInviteServiceImpl(FissionInviteMapper inviteMapper,
                                    FissionCampaignMapper campaignMapper,
                                    AttributionRuleMapper ruleMapper,
                                    CouponService couponService) {
        this.inviteMapper = inviteMapper;
        this.campaignMapper = campaignMapper;
        this.ruleMapper = ruleMapper;
        this.couponService = couponService;
    }

    @Override
    @Transactional
    public void record(String fissionNo, String inviterNo, String inviteeNo,
                       String deviceId, String phoneTail) {
        if (blank(fissionNo) || blank(inviterNo) || blank(inviteeNo)) {
            return;
        }
        /*
         * **自己邀自己直接不落行。** 它不是「非新客」那一类（那类要落行让运营看得见），
         * 而是压根不成立的一次邀请 —— 落进去只会让 invitedCount 虚高。
         */
        if (inviterNo.equals(inviteeNo)) {
            return;
        }
        MktFissionCampaign campaign = DataScopeContext.executeWithoutScope(() ->
                campaignMapper.selectOne(Wrappers.<MktFissionCampaign>lambdaQuery()
                        .eq(MktFissionCampaign::getFissionNo, fissionNo).last("limit 1")));
        if (campaign == null || !Boolean.TRUE.equals(campaign.getEnabled())) {
            return;
        }
        /*
         * **幂等靠先查一次 + 唯一键兜底**，两道都要：
         * 先查挡住绝大多数重复（省掉一次异常），唯一键挡住并发那一次 ——
         * 只有前者的话，同一个人同时点两下邀请链接会落两行、发两次奖。
         */
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                inviteMapper.exists(Wrappers.<MktFissionInvite>lambdaQuery()
                        .eq(MktFissionInvite::getFissionNo, fissionNo)
                        .eq(MktFissionInvite::getInviteeNo, inviteeNo)));
        if (exists) {
            return;
        }

        boolean isNew = isNewUser(deviceId, phoneTail);
        MktFissionInvite row = new MktFissionInvite();
        row.setFissionNo(fissionNo);
        row.setInviterNo(inviterNo);
        row.setInviteeNo(inviteeNo);
        row.setDeviceId(deviceId);
        row.setPhoneTail(phoneTail);
        row.setIsNewUser(isNew ? 1 : 0);
        row.setRewarded(0);

        /*
         * **非新客照样落行、但不发奖**（表结构注释里的原话）。
         * 不落行的话，运营只会看到一个莫名其妙偏低的 invitedCount，
         * 而「邀了 100 个只有 3 个算数」这件事在数据里看不见。
         */
        if (isNew) {
            grantReward(campaign, row);
        }
        try {
            DataScopeContext.executeWithoutScope(() -> inviteMapper.insert(row));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发下的第二次：唯一键 uk_fission_invitee 挡住了，这不是异常情况
            log.debug("[裂变] 重复邀请被唯一键挡下 fission={} invitee={}", fissionNo, inviteeNo);
        }
    }

    /**
     * 发奖。**失败只记不抛** —— 表结构给 reward_error 留了一列就是为此：
     * 券停用、预算耗尽都是常态，让它去回滚一次已经完成的注册，代价方向完全反了。
     */
    private void grantReward(MktFissionCampaign campaign, MktFissionInvite row) {
        if (!MktFissionCampaign.COUPON.equals(campaign.getRewardType())
                || blank(campaign.getCouponNo())) {
            return;
        }
        try {
            int inviter = nz(campaign.getInviterCount());
            int invitee = nz(campaign.getInviteeCount());
            for (int i = 0; i < inviter; i++) {
                couponService.grantTo(row.getInviterNo(), campaign.getCouponNo());
            }
            for (int i = 0; i < invitee; i++) {
                couponService.grantTo(row.getInviteeNo(), campaign.getCouponNo());
            }
            row.setRewarded(inviter + invitee > 0 ? 1 : 0);
        } catch (RuntimeException e) {
            row.setRewarded(0);
            // 截断到列宽（255）：写不进去的话这一列就永远是空的，而它是唯一的线索
            String msg = String.valueOf(e.getMessage());
            row.setRewardError(msg.length() > 200 ? msg.substring(0, 200) : msg);
            log.warn("[裂变] 发奖失败 fission={} inviter={} 券={} 原因={}",
                    campaign.getFissionNo(), row.getInviterNo(), campaign.getCouponNo(), msg);
        }
    }

    /**
     * 新客判定。**因子由运营配**（归因规则里的 newUserFactors，取值 DEVICE / PHONE）——
     * 不是我在这里写死一套。
     *
     * <p>「一个因子都没选 = 所有人都是新客」这件事在配置那一层就被
     * {@code ATTRIBUTION_FACTOR_REQUIRED} 拦住了，所以这里读到空集时
     * **按不是新客处理**（不发奖），而不是反过来 —— 配置缺失时宁可少发，
     * 不可无限发。
     */
    private boolean isNewUser(String deviceId, String phoneTail) {
        Set<String> factors = factors();
        if (factors.isEmpty()) {
            return false;
        }
        if (factors.contains(F_DEVICE) && !blank(deviceId) && seenBefore(
                Wrappers.<MktFissionInvite>lambdaQuery().eq(MktFissionInvite::getDeviceId, deviceId))) {
            return false;
        }
        if (factors.contains(F_PHONE) && !blank(phoneTail) && seenBefore(
                Wrappers.<MktFissionInvite>lambdaQuery().eq(MktFissionInvite::getPhoneTail, phoneTail))) {
            return false;
        }
        return true;
    }

    private boolean seenBefore(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MktFissionInvite> w) {
        return Boolean.TRUE.equals(DataScopeContext.executeWithoutScope(() -> inviteMapper.exists(w)));
    }

    private Set<String> factors() {
        MktAttributionRule rule = DataScopeContext.executeWithoutScope(() ->
                ruleMapper.selectOne(Wrappers.<MktAttributionRule>lambdaQuery()
                        .eq(MktAttributionRule::getRuleKey, MktAttributionRule.MAIN).last("limit 1")));
        String raw = rule == null ? null : rule.getNewUserFactors();
        if (blank(raw)) {
            return Set.of();
        }
        return Set.copyOf(List.of(raw.split(",")).stream().map(String::trim)
                .filter(x -> !x.isEmpty()).toList());
    }

    @Override
    @Transactional
    public void onFirstOrder(String userNo, String orderNo) {
        if (blank(userNo) || blank(orderNo)) {
            return;
        }
        /*
         * **只回填还没有首单的行。** 第二单不该覆盖第一单 —— 那会把「转化」
         * 悄悄变成「最近一单」，而两者在报表上长得一模一样。
         */
        List<MktFissionInvite> rows = DataScopeContext.executeWithoutScope(() ->
                inviteMapper.selectList(Wrappers.<MktFissionInvite>lambdaQuery()
                        .eq(MktFissionInvite::getInviteeNo, userNo)
                        .isNull(MktFissionInvite::getOrderNo)));
        for (MktFissionInvite row : rows) {
            row.setOrderNo(orderNo);
            DataScopeContext.executeWithoutScope(() -> inviteMapper.updateById(row));
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
