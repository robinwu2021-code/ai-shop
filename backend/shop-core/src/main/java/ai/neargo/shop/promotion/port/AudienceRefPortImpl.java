package ai.neargo.shop.promotion.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityAudience;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtCouponIssue;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityAudienceMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponIssueMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.spi.marketing.AudienceRefPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 标签 / 人群被谁引用（{@link AudienceRefPort}）。
 *
 * <p>绕开数据域读写，<b>每条查询都显式带 entity_no</b>：营销表的数据域维度与会员域不同，
 * 不绕开的话从会员页进来的这一次查询会查出空 —— 于是「0 处在用」，停用确认框不弹，
 * 而那正是这个接口存在的理由。与 {@code ActivityServiceImpl#list} 同一写法。
 */
@Component
public class AudienceRefPortImpl implements AudienceRefPort {

    /** 发券记录只列最近这么多条：人群详情页要的是「用过」，不是完整账本 */
    private static final int ISSUE_LIMIT = 20;

    private final ActivityAudienceMapper audienceMapper;
    private final ActivityMapper activityMapper;
    private final CouponIssueMapper issueMapper;
    private final CouponMapper couponMapper;

    public AudienceRefPortImpl(ActivityAudienceMapper audienceMapper, ActivityMapper activityMapper,
                               CouponIssueMapper issueMapper, CouponMapper couponMapper) {
        this.audienceMapper = audienceMapper;
        this.activityMapper = activityMapper;
        this.issueMapper = issueMapper;
        this.couponMapper = couponMapper;
    }

    @Override
    public List<AudienceRef> activitiesUsing(String entityNo, String type, String value) {
        return DataScopeContext.executeWithoutScope(() -> {
            Set<String> activityNos = audienceMapper.selectList(Wrappers.<PmtActivityAudience>lambdaQuery()
                            .eq(PmtActivityAudience::getEntityNo, entityNo)
                            .eq(PmtActivityAudience::getAudienceType, type)
                            .eq(PmtActivityAudience::getAudienceValue, value))
                    .stream().map(PmtActivityAudience::getActivityNo).collect(Collectors.toSet());
            if (activityNos.isEmpty()) {
                return List.of();
            }
            return activityMapper.selectList(Wrappers.<PmtActivity>lambdaQuery()
                            .eq(PmtActivity::getEntityNo, entityNo)
                            .in(PmtActivity::getActivityNo, activityNos)
                            .ne(PmtActivity::getStatus, PmtActivity.ENDED)
                            .isNull(PmtActivity::getArchivedAt)
                            .orderByDesc(PmtActivity::getId))
                    .stream()
                    .map(a -> new AudienceRef(AudienceRef.ACTIVITY, a.getActivityNo(), a.getName(),
                            a.getStatus(), null))
                    .toList();
        });
    }

    @Override
    public List<AudienceRef> couponIssuesUsing(String entityNo, String segmentNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            List<PmtCouponIssue> issues = issueMapper.selectList(Wrappers.<PmtCouponIssue>lambdaQuery()
                    .eq(PmtCouponIssue::getEntityNo, entityNo)
                    .eq(PmtCouponIssue::getSegmentNo, segmentNo)
                    .orderByDesc(PmtCouponIssue::getIssuedAt)
                    .last("limit " + ISSUE_LIMIT));
            if (issues.isEmpty()) {
                return List.of();
            }
            Map<String, String> titles = new HashMap<>();
            couponMapper.selectList(Wrappers.<PmtCoupon>lambdaQuery()
                            .eq(PmtCoupon::getEntityNo, entityNo)
                            .in(PmtCoupon::getCouponNo, issues.stream().map(PmtCouponIssue::getCouponNo)
                                    .collect(Collectors.toSet())))
                    .forEach(c -> titles.putIfAbsent(c.getCouponNo(), c.getTitle()));
            Function<PmtCouponIssue, AudienceRef> ref = i -> new AudienceRef(AudienceRef.COUPON_ISSUE,
                    i.getIssueNo(), titles.get(i.getCouponNo()), null, i.getIssuedAt());
            return issues.stream().map(ref).toList();
        });
    }

    @Override
    public int retargetTag(String entityNo, String fromTagNo, String toTagNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            List<PmtActivityAudience> rows = audienceMapper.selectList(Wrappers.<PmtActivityAudience>lambdaQuery()
                    .eq(PmtActivityAudience::getEntityNo, entityNo)
                    .eq(PmtActivityAudience::getAudienceType, PmtActivityAudience.TAG)
                    .eq(PmtActivityAudience::getAudienceValue, fromTagNo));
            for (PmtActivityAudience r : rows) {
                boolean hasTarget = audienceMapper.exists(Wrappers.<PmtActivityAudience>lambdaQuery()
                        .eq(PmtActivityAudience::getActivityNo, r.getActivityNo())
                        .eq(PmtActivityAudience::getAudienceType, PmtActivityAudience.TAG)
                        .eq(PmtActivityAudience::getAudienceValue, toTagNo));
                if (hasTarget) {
                    // 两个标签都在受众里：删掉源那一行，否则唯一键挡住改指
                    audienceMapper.hardDeleteById(r.getId());
                } else {
                    r.setAudienceValue(toTagNo);
                    audienceMapper.updateById(r);
                }
            }
            return rows.size();
        });
    }
}
