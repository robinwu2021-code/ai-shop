package ai.neargo.shop.marketing.attribution.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.IsoTime;
import ai.neargo.shop.marketing.attribution.FissionService;
import ai.neargo.shop.marketing.attribution.entity.MktFissionCampaign;
import ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.FissionCampaignMapper;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers.CouponMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** {@link FissionService} 实现。 */
@Service
public class FissionServiceImpl implements FissionService {

    private final FissionCampaignMapper campaignMapper;
    private final CouponMapper couponMapper;
    private final ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.FissionInviteMapper
            inviteMapper;

    public FissionServiceImpl(
            FissionCampaignMapper campaignMapper, CouponMapper couponMapper,
            ai.neargo.shop.marketing.attribution.mapper.AttributionMappers.FissionInviteMapper
                    inviteMapper) {
        this.campaignMapper = campaignMapper;
        this.couponMapper = couponMapper;
        this.inviteMapper = inviteMapper;
    }

    @Override
    public List<CampaignVO> list(boolean enabledOnly) {
        var w = Wrappers.<MktFissionCampaign>lambdaQuery()
                .eq(enabledOnly, MktFissionCampaign::getEnabled, true)
                .orderByDesc(MktFissionCampaign::getId);
        return DataScopeContext.executeWithoutScope(() -> campaignMapper.selectList(w))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public CampaignVO save(SaveCommand cmd, String operatorNo) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 券模板必须存在。
         *
         * 不校验的后果不是保存失败，是**发奖那一刻才失败** —— 而那时用户
         * 已经被邀请进来了，平台既发不出奖也解释不清。
         */
        MktCoupon coupon = DataScopeContext.executeWithoutScope(() ->
                couponMapper.selectOne(Wrappers.<MktCoupon>lambdaQuery()
                        .eq(MktCoupon::getCouponNo, cmd.couponNo()).last("limit 1")));
        if (coupon == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        int inviter = nz(cmd.inviterCount());
        int invitee = nz(cmd.inviteeCount());
        // 两边都是 0 = 一张券都不发，那这个活动存在的意义是什么
        if (inviter < 0 || invitee < 0 || inviter + invitee == 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        MktFissionCampaign row = cmd.fissionNo() == null || cmd.fissionNo().isBlank()
                ? null : require(cmd.fissionNo());
        boolean fresh = row == null;
        if (fresh) {
            row = new MktFissionCampaign();
            row.setFissionNo(BizKey.next(BizKey.FISSION));
            row.setEnabled(false);
            row.setInvitedCount(0);
            row.setConvertedCount(0);
        }
        row.setName(cmd.name().trim());
        // 奖励只能是券（ADR-004：不用现金买增长）—— 不从入参取，免得端上传别的值
        row.setRewardType(MktFissionCampaign.COUPON);
        row.setCouponNo(cmd.couponNo());
        row.setInviterCount(inviter);
        row.setInviteeCount(invitee);
        MktFissionCampaign toSave = row;
        DataScopeContext.executeWithoutScope(() ->
                fresh ? campaignMapper.insert(toSave) : campaignMapper.updateById(toSave));
        return toVO(row);
    }

    @Override
    @Transactional
    public CampaignVO setEnabled(String fissionNo, boolean enabled, String operatorNo) {
        MktFissionCampaign row = require(fissionNo);
        if (enabled) {
            /*
             * 启用时再查一次券：建活动到启用之间券可能已被停用或结束。
             * 指向一个停用券的活动会在发奖那一刻失败 —— 用户被邀请来了却拿不到东西。
             */
            MktCoupon coupon = DataScopeContext.executeWithoutScope(() ->
                    couponMapper.selectOne(Wrappers.<MktCoupon>lambdaQuery()
                            .eq(MktCoupon::getCouponNo, row.getCouponNo()).last("limit 1")));
            if (coupon == null || !MktCoupon.ACTIVE.equals(coupon.getStatus())) {
                throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
            }
        }
        row.setEnabled(enabled);
        DataScopeContext.executeWithoutScope(() -> campaignMapper.updateById(row));
        return toVO(row);
    }

    private MktFissionCampaign require(String fissionNo) {
        MktFissionCampaign row = DataScopeContext.executeWithoutScope(() ->
                campaignMapper.selectOne(Wrappers.<MktFissionCampaign>lambdaQuery()
                        .eq(MktFissionCampaign::getFissionNo, fissionNo).last("limit 1")));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private CampaignVO toVO(MktFissionCampaign c) {
        /*
         * **两个计数从台账现算，不读实体上那两列。**
         *
         * <p>实体上的 invitedCount / convertedCount 注释写着自己是「台账的聚合快照」，
         * 而实际只在新建活动时 set(0)、**再没有一处递增** —— 运营端「邀请有礼」
         * 那两列因此恒为 0，而 0 既像「还没人参加」又像「坏了」。
         *
         * <p>不改成「每处写台账时递增快照」的理由：那会留下第二个真源，
         * 漏更新一处就长期偏差，并发还要加锁；而实体注释自己都写着
         * 「真值以台账为准，对不上时以台账重算」—— 那就别留那个会对不上的东西。
         * 活动是几十条级别，两次 COUNT 换「永远不会错」很划算。
         */
        long invited = countInvites(c.getFissionNo(), false);
        long converted = countInvites(c.getFissionNo(), true);
        return new CampaignVO(c.getFissionNo(), c.getName(), c.getRewardType(), c.getCouponNo(),
                nz(c.getInviterCount()), nz(c.getInviteeCount()),
                Boolean.TRUE.equals(c.getEnabled()),
                (int) invited, (int) converted,
                IsoTime.toIso(c.getCreatedAt()));
    }

    /** 台账计数。{@code convertedOnly} = 只数已回填首单的（= 转化）。 */
    private long countInvites(String fissionNo, boolean convertedOnly) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                inviteMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.marketing.attribution.entity.MktFissionInvite>lambdaQuery()
                        .eq(ai.neargo.shop.marketing.attribution.entity.MktFissionInvite::getFissionNo, fissionNo)
                        .isNotNull(convertedOnly,
                                ai.neargo.shop.marketing.attribution.entity.MktFissionInvite::getOrderNo)));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
