package ai.neargo.shop.community.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.entity.CmtPickupPoint;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper;
import ai.neargo.shop.spi.user.PickupDirectoryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link PickupDirectoryPort} 实现：调度看板要的那份自提点名录。
 *
 * <p><b>社区名在这里 join 而不是让调用方再查一遍</b>：让 fulfillment 自己去查社区，
 * 要么它得依赖 community 域（边界破了），要么再开一个 Port ——
 * 而「这个点在哪个小区」本来就是自提点主数据的一部分。
 */
@Component
public class PickupDirectoryPortImpl implements PickupDirectoryPort {

    /** 常驻点。团粒度临时点（GROUP_INSTANCE）随团生灭，不进调度看板 */
    private static final String PERMANENT = "PERMANENT";

    private final PickupPointMapper pickupMapper;
    private final CommunityMapper communityMapper;

    public PickupDirectoryPortImpl(PickupPointMapper pickupMapper, CommunityMapper communityMapper) {
        this.pickupMapper = pickupMapper;
        this.communityMapper = communityMapper;
    }

    @Override
    public List<PickupRow> list(String communityNo) {
        /*
         * executeWithoutScope：运营会话的数据域按 tenant/merchant 裁剪，
         * 而自提点是**主数据**，平台调度看的就是全部。
         * 与 FulfillmentQueryPortImpl 同一个理由：过滤条件从「拦截器按会话维度」
         * 换成「方法参数 + 上层权限校验」，不是取消过滤。
         */
        List<CmtPickupPoint> points = DataScopeContext.executeWithoutScope(() -> {
            var w = Wrappers.<CmtPickupPoint>lambdaQuery().eq(CmtPickupPoint::getScope, PERMANENT);
            if (communityNo != null && !communityNo.isBlank()) {
                w.eq(CmtPickupPoint::getCommunityNo, communityNo);
            }
            // 归档的点不进调度看板：它已经不承接新货了，留在列表里只会让运营以为还要配车
            w.isNull(CmtPickupPoint::getArchivedAt).orderByAsc(CmtPickupPoint::getPickupNo);
            return pickupMapper.selectList(w);
        });
        if (points.isEmpty()) {
            return List.of();
        }

        List<String> communityNos = points.stream().map(CmtPickupPoint::getCommunityNo)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, String> names = communityNos.isEmpty() ? Map.of()
                : DataScopeContext.executeWithoutScope(() -> communityMapper.selectList(
                                Wrappers.<CmtCommunity>lambdaQuery().in(CmtCommunity::getCommunityNo, communityNos)))
                        .stream().collect(Collectors.toMap(CmtCommunity::getCommunityNo,
                                CmtCommunity::getName, (a, b) -> a));

        return points.stream().map(p -> new PickupRow(
                p.getPickupNo(), p.getName(), p.getCommunityNo(),
                // 社区被删/查不到时给空串而不是 null：看板上一列 null 会渲染成 "undefined"
                names.getOrDefault(p.getCommunityNo(), ""),
                p.getType(), p.getStatus())).toList();
    }
}
