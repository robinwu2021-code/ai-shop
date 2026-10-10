package ai.neargo.shop.merchant.reach;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.geo.GeoPolygon;
import ai.neargo.shop.geo.ReachGeoProps;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.spi.reach.ConsumerProfile;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 {@code area_no} 取多边形并缓存解析结果（ADR-034）。只在<b>边界 cell 命中</b>时被问到，量很小。
 *
 * <p>缓存 {@code Optional.empty()} 表示「这条取不到或解析不了」：
 * 一条坏数据不该让每次请求都重查一遍库、也不该让整家店消失或变成全平台 —— 判不出来就不放行，并 WARN 一次。
 *
 * <p>几何不变则 {@code area_no} 不变（ref_code 是几何指纹，{@code replaceAreas} 沿用原号），
 * 改了几何就是新项、新号 —— 所以这里<b>不需要失效</b>：缓存键天然随几何变化。
 */
@Slf4j
@Component
public class PolygonCache implements PolygonProbe {

    private final MerchantMappers.ServiceAreaMapper areaMapper;
    private final ReachGeoProps props;
    private final ConcurrentHashMap<String, Optional<GeoPolygon>> cache = new ConcurrentHashMap<>();

    public PolygonCache(MerchantMappers.ServiceAreaMapper areaMapper, ReachGeoProps props) {
        this.areaMapper = areaMapper;
        this.props = props;
    }

    @Override
    public boolean covers(String areaNo, ConsumerProfile profile) {
        if (areaNo == null || !profile.hasCoords()) {
            return false;
        }
        return cache.computeIfAbsent(areaNo, this::load)
                .map(g -> g.covers(profile.latE6(), profile.lngE6()))
                .orElse(false);
    }

    private Optional<GeoPolygon> load(String areaNo) {
        MchServiceArea row = DataScopeContext.executeWithoutScope(() -> areaMapper.selectOne(
                Wrappers.<MchServiceArea>lambdaQuery()
                        .select(MchServiceArea::getAreaNo, MchServiceArea::getGeometry)
                        .eq(MchServiceArea::getAreaNo, areaNo)
                        .last("limit 1")));
        if (row == null || row.getGeometry() == null || row.getGeometry().isBlank()) {
            log.warn("[reach] 范围项 {} 命中了网格但取不到几何，按「不覆盖」处理", areaNo);
            return Optional.empty();
        }
        try {
            return Optional.of(GeoPolygon.parse(row.getGeometry(), props.getPolygonMaxVertices()));
        } catch (IllegalArgumentException e) {
            log.warn("[reach] 范围项 {} 的多边形几何解析失败，按「不覆盖」处理：{}", areaNo, e.getMessage());
            return Optional.empty();
        }
    }
}
