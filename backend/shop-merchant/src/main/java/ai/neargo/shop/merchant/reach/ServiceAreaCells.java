package ai.neargo.shop.merchant.reach;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.geo.GeoPolygon;
import ai.neargo.shop.geo.ReachGeoProps;
import ai.neargo.shop.geo.S2Cover;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchServiceAreaCell;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 多边形范围项的 S2 网格派生行：唯一的写入方（ADR-034）。
 *
 * <p>保存多边形时 {@link #rebuild} 先删该项的旧网格再按当前几何重算；删范围项时 {@link #purge} 级联清；
 * 参数（级别区间/上限）改了或要修数据时 {@link #rebuildAll} 全量重算 —— 派生表本来就该能从 geometry 重建。
 *
 * <p>写入一律 {@code executeWithoutScope}：调用方是 B 端会话（SELF 维度），接上数据域就是 1=0（见记忆「B 端直查带域表读写皆哑」）。
 */
@Slf4j
@Service
public class ServiceAreaCells {

    private final MerchantMappers.ServiceAreaCellMapper cellMapper;
    private final MerchantMappers.ServiceAreaMapper areaMapper;
    private final ReachGeoProps props;

    public ServiceAreaCells(MerchantMappers.ServiceAreaCellMapper cellMapper,
                            MerchantMappers.ServiceAreaMapper areaMapper,
                            ReachGeoProps props) {
        this.cellMapper = cellMapper;
        this.areaMapper = areaMapper;
        this.props = props;
    }

    /**
     * 按当前几何重算这一条 POLYGON 范围项的网格（先删后插，同事务）。
     *
     * @return 写入的 cell 行数
     */
    @Transactional
    public int rebuild(MchServiceArea polygonRow, GeoPolygon polygon) {
        purge(List.of(polygonRow.getAreaNo()));
        List<S2Cover.Cell> cells = S2Cover.cover(polygon,
                props.getS2MinLevel(), props.getS2MaxLevel(), props.getS2MaxCells());
        for (S2Cover.Cell c : cells) {
            MchServiceAreaCell row = new MchServiceAreaCell();
            row.setAreaNo(polygonRow.getAreaNo());
            row.setEntityNo(polygonRow.getEntityNo());
            row.setStoreNo(polygonRow.getStoreNo());
            row.setMode(polygonRow.getMode());
            row.setCellId(c.token());
            row.setS2Level(c.level());
            row.setBoundary(c.boundary());
            DataScopeContext.executeWithoutScope(() -> cellMapper.insert(row));
        }
        return cells.size();
    }

    /** **物理删**这些范围项的全部网格行。范围项被删或几何改了（指纹变 = 新项）时同事务调用 */
    public void purge(Collection<String> areaNos) {
        if (areaNos == null || areaNos.isEmpty()) {
            return;
        }
        DataScopeContext.executeWithoutScope(() -> cellMapper.purgeByAreaNos(areaNos));
    }

    /**
     * 全量重算所有 POLYGON 范围项的网格。几何解析失败的那一条跳过并 WARN（含 area_no）——
     * 一条坏数据不能让整批重算失败，也不能让它变成全平台。
     *
     * @return 重算成功的范围项数
     */
    @Transactional
    public int rebuildAll() {
        List<MchServiceArea> polygons = DataScopeContext.executeWithoutScope(() ->
                areaMapper.selectList(Wrappers.<MchServiceArea>lambdaQuery()
                        .eq(MchServiceArea::getLevel, MchServiceArea.LEVEL_POLYGON)));
        int ok = 0;
        for (MchServiceArea row : polygons) {
            try {
                rebuild(row, GeoPolygon.parse(row.getGeometry(), props.getPolygonMaxVertices()));
                ok++;
            } catch (IllegalArgumentException e) {
                log.warn("[reach] 范围项 {} 的多边形几何解析失败，网格未重算：{}", row.getAreaNo(), e.getMessage());
            }
        }
        log.info("[reach] 多边形网格全量重算：{}/{} 条", ok, polygons.size());
        return ok;
    }
}
