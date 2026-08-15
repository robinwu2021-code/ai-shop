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

    public FissionServiceImpl(FissionCampaignMapper campaignMapper, CouponMapper couponMapper) {
        this.campaignMapper = campaignMapper;
        this.couponMapper = couponMapper;
    }

    @Override
    public List<CampaignVO> list(boolean enabledOnly) {
        var w = Wrappers.<MktFissionCampaign>lambdaQuery()
                .eq(enabledOnly, MktFissionCampaign::getEnabled, true)
                .orderByDesc(MktFissionCampaign::getId);
        return DataScopeContext.executeWithoutScope(() -> campaignMapper.selectList(w))
                .stream().map(FissionServiceImpl::toVO).toList();
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

    private static CampaignVO toVO(MktFissionCampaign c) {
        return new CampaignVO(c.getFissionNo(), c.getName(), c.getRewardType(), c.getCouponNo(),
                nz(c.getInviterCount()), nz(c.getInviteeCount()),
                Boolean.TRUE.equals(c.getEnabled()),
                nz(c.getInvitedCount()), nz(c.getConvertedCount()),
                IsoTime.toIso(c.getCreatedAt()));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
