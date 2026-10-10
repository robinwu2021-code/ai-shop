package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.geo.GeoPolygon;
import ai.neargo.shop.geo.ReachGeoProps;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchServiceAreaCell;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.reach.ServiceAreaCells;
import ai.neargo.shop.merchant.service.MerchantStoreService;
import ai.neargo.shop.merchant.service.MerchantStoreService.AreaCommand;
import ai.neargo.shop.merchant.service.MerchantStoreService.SaveCommand;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 保存地图多边形范围与显式「全平台不限」（ADR-034 AC1/AC7/AC12/AC15）。
 *
 * <p>多边形的 {@code refCode} 由服务端用几何指纹覆写，于是「同一片范围重复保存」会沿用原
 * {@code area_no} —— 那条沿用机制是子集引用（{@code mch_channel_area}）不落空的前提：
 * 2026-09-28 修过一次「每次保存都换新号、子集引用全落空、门店从买家端整家消失而提示保存成功」。
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceAreaPolygonFlowTest {

    @Autowired
    private MerchantStoreService storeService;
    @Autowired
    private MerchantMappers.MchEntityMapper entityMapper;
    @Autowired
    private MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private MerchantMappers.ServiceAreaMapper areaMapper;
    @Autowired
    private MerchantMappers.ServiceAreaCellMapper cellMapper;
    @Autowired
    private ServiceAreaCells cells;
    @Autowired
    private ReachGeoProps props;

    /** 约 1 km × 1 km */
    private static final String SQUARE =
            "[[114000000,22500000],[114010000,22500000],[114010000,22510000],[114000000,22510000]]";
    /** 把北边界往外推 0.005° 的另一片 */
    private static final String BIGGER =
            "[[114000000,22500000],[114010000,22500000],[114010000,22515000],[114000000,22515000]]";

    @Test
    @DisplayName("★★★ 保存多边形：落 geometry、refCode 是几何指纹、派生网格且内部与边界两类都有")
    void savingPolygonDerivesCells() {
        String m = merchant();
        storeService.save(m, store(m), cmd(polygon(SQUARE)));

        List<MchServiceArea> rows = areasOf(m);
        assertThat(rows).hasSize(1);
        MchServiceArea row = rows.get(0);
        assertThat(row.getLevel()).isEqualTo(MchServiceArea.LEVEL_POLYGON);
        assertThat(row.getGeometry()).as("几何要落库，否则重建不出网格").isNotBlank();
        assertThat(row.getRefCode())
                .as("refCode = 几何指纹")
                .isEqualTo(GeoPolygon.parse(SQUARE, props.getPolygonMaxVertices()).fingerprint())
                .hasSize(32);
        assertThat(row.getStatus()).as("多边形自助生效，不送审").isEqualTo(MchServiceArea.ACTIVE);

        List<MchServiceAreaCell> grid = cellsOf(row.getAreaNo());
        assertThat(grid).as("要派生网格行").isNotEmpty();
        assertThat(grid).anyMatch(c -> Boolean.TRUE.equals(c.getBoundary()));
        assertThat(grid).anyMatch(c -> Boolean.FALSE.equals(c.getBoundary()));
        assertThat(grid).allSatisfy(c -> {
            assertThat(c.getS2Level()).isBetween(props.getS2MinLevel(), props.getS2MaxLevel());
            assertThat(c.getStoreNo()).isEqualTo(row.getStoreNo());
            assertThat(c.getMode()).isEqualTo(MchServiceArea.MODE_INCLUDE);
        });
    }

    @Test
    @DisplayName("★★★ 同一片范围重复保存：area_no 沿用不变（子集引用不落空的前提）")
    void sameGeometryKeepsAreaNo() {
        String m = merchant();
        String st = store(m);
        storeService.save(m, st, cmd(polygon(SQUARE)));
        String first = areasOf(m).get(0).getAreaNo();

        storeService.save(m, st, cmd(polygon(SQUARE)));
        List<MchServiceArea> after = areasOf(m);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getAreaNo()).isEqualTo(first);
        assertThat(cellsOf(first)).as("网格重算后仍挂在同一个 area_no 上").isNotEmpty();
    }

    @Test
    @DisplayName("★★★ 改了几何：指纹变 = 新项，旧项与它的网格一起清掉，不留孤儿")
    void changingGeometryRebuildsAndPurgesOld() {
        String m = merchant();
        String st = store(m);
        storeService.save(m, st, cmd(polygon(SQUARE)));
        String oldAreaNo = areasOf(m).get(0).getAreaNo();
        int oldCells = cellsOf(oldAreaNo).size();
        assertThat(oldCells).isPositive();

        storeService.save(m, st, cmd(polygon(BIGGER)));
        List<MchServiceArea> after = areasOf(m);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getAreaNo()).as("几何变了就是新项").isNotEqualTo(oldAreaNo);
        assertThat(cellsOf(oldAreaNo)).as("旧网格必须清掉 —— 不清会让消费者命中一片已经不存在的范围").isEmpty();
        assertThat(cellsOf(after.get(0).getAreaNo())).isNotEmpty();
    }

    @Test
    @DisplayName("★★★ 删掉多边形项：网格级联清空")
    void removingPolygonPurgesCells() {
        String m = merchant();
        String st = store(m);
        storeService.save(m, st, cmd(polygon(SQUARE)));
        String areaNo = areasOf(m).get(0).getAreaNo();
        assertThat(cellsOf(areaNo)).isNotEmpty();

        // 换成一条小区级范围：多边形项没了
        storeService.save(m, st, cmd(new AreaCommand(MchServiceArea.LEVEL_COMMUNITY, "CM001")));
        assertThat(areasOf(m)).extracting(MchServiceArea::getLevel)
                .containsExactly(MchServiceArea.LEVEL_COMMUNITY);
        assertThat(cellsOf(areaNo)).isEmpty();
    }

    @Test
    @DisplayName("★★★ 非法多边形被拒：返回可读的 SERVICE_AREA_POLYGON_INVALID，且什么都没落库")
    void invalidPolygonIsRejected() {
        String m = merchant();
        String st = store(m);
        for (String bad : List.of("[[0,0],[1,1]]", "[[200000000,0],[1,1],[2,2]]", "not json")) {
            assertThatThrownBy(() -> storeService.save(m, st, cmd(polygon(bad))))
                    .as("应拒：%s", bad)
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).errorCode())
                    .isEqualTo(ErrorCode.SERVICE_AREA_POLYGON_INVALID);
        }
        assertThat(areasOf(m)).as("拒掉之后不该留下半条范围").isEmpty();
    }

    @Test
    @DisplayName("★★ 「全平台不限」：refCode 固定为 *；传成 EXCLUDE 直接拒")
    void unlimitedItem() {
        String m = merchant();
        String st = store(m);
        storeService.save(m, st, cmd(new AreaCommand(MchServiceArea.LEVEL_UNLIMITED, "端上乱传的值")));
        List<MchServiceArea> rows = areasOf(m);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRefCode()).isEqualTo(MchServiceArea.UNLIMITED_REF);
        assertThat(rows.get(0).getGeometry()).isNull();

        assertThatThrownBy(() -> storeService.save(m, st,
                cmd(new AreaCommand(MchServiceArea.LEVEL_UNLIMITED, "*", MchServiceArea.MODE_EXCLUDE))))
                .as("「排除全部」没有意义，写进去的后果是整店对谁都不可见、且看不出原因")
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ rebuildAll 能从 geometry 重建网格（参数变更/修数据的出口，AC15）")
    void rebuildAllRegeneratesFromGeometry() {
        String m = merchant();
        storeService.save(m, store(m), cmd(polygon(SQUARE)));
        String areaNo = areasOf(m).get(0).getAreaNo();
        int before = cellsOf(areaNo).size();
        assertThat(before).isPositive();

        // 手工把网格清空，模拟「参数改了 / 派生数据坏了」
        DataScopeContext.executeWithoutScope(() -> cellMapper.purgeByAreaNos(List.of(areaNo)));
        assertThat(cellsOf(areaNo)).isEmpty();

        assertThat(cells.rebuildAll()).isPositive();
        assertThat(cellsOf(areaNo)).as("重建后网格行数与原来一致").hasSize(before);
    }

    @Test
    @DisplayName("★★ 回显带 geometry，端上才画得出那片范围")
    void profileEchoesGeometry() {
        String m = merchant();
        String st = store(m);
        storeService.save(m, st, cmd(polygon(SQUARE)));
        var areas = storeService.profile(m, st).serviceAreas();
        assertThat(areas).hasSize(1);
        assertThat(areas.get(0).level()).isEqualTo(MchServiceArea.LEVEL_POLYGON);
        assertThat(areas.get(0).geometry()).isNotBlank();
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static AreaCommand polygon(String geometry) {
        return new AreaCommand(MchServiceArea.LEVEL_POLYGON, null, MchServiceArea.MODE_INCLUDE, geometry);
    }

    private static SaveCommand cmd(AreaCommand... areas) {
        return new SaveCommand(null, null, null, null, null, null, null, null, null, null,
                List.of(areas), null, null);
    }

    private String merchant() {
        MchEntity e = new MchEntity();
        e.setEntityNo(BizKey.next(BizKey.MERCHANT));
        e.setName("多边形范围测试");
        e.setStatus(MchEntity.ACTIVE);
        e.setFulfillmentReach("SHIPPING");
        DataScopeContext.executeWithoutScope(() -> entityMapper.insert(e));
        return e.getEntityNo();
    }

    private String store(String entityNo) {
        MchStore st = new MchStore();
        st.setEntityNo(entityNo);
        st.setStoreNo(BizKey.next(BizKey.STORE));
        st.setName("多边形范围测试店");
        st.setStatus(MchStore.ACTIVE);
        st.setIsDefault(true);
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(st));
        return st.getStoreNo();
    }

    private List<MchServiceArea> areasOf(String entityNo) {
        return DataScopeContext.executeWithoutScope(() -> areaMapper.selectList(
                Wrappers.<MchServiceArea>lambdaQuery().eq(MchServiceArea::getEntityNo, entityNo)));
    }

    private List<MchServiceAreaCell> cellsOf(String areaNo) {
        return DataScopeContext.executeWithoutScope(() -> cellMapper.selectList(
                Wrappers.<MchServiceAreaCell>lambdaQuery().eq(MchServiceAreaCell::getAreaNo, areaNo)));
    }
}
