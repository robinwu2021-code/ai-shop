package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.geo.GeoPolygon;
import ai.neargo.shop.geo.ReachGeoProps;
import ai.neargo.shop.geo.S2Cover;
import ai.neargo.shop.merchant.entity.MchServiceArea;
import ai.neargo.shop.merchant.entity.MchServiceAreaCell;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.reach.DbHitFinder;
import ai.neargo.shop.merchant.reach.InMemoryHitFinder;
import ai.neargo.shop.merchant.reach.StoreHits;
import ai.neargo.shop.merchant.reach.StoreItems;
import ai.neargo.shop.spi.reach.ConsumerProfile;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两种命中查找器必须给出相同结果（ADR-034 AC14）。
 *
 * <p>DB 查找器用索引点查（{@code ref_code IN (祖先码/聚落号)}、{@code cell_id IN (token)}），
 * 内存查找器对一家店的范围项做同样的集合成员判断。它们是同一个语义的两种机制 ——
 * 任何一边改了语义，这条就红。种子数据直接经 Mapper 写入，门店/主体号带本次运行的唯一后缀，不与别的用例串。
 */
@SpringBootTest
@ActiveProfiles("test")
class HitFinderParityTest {

    @Autowired
    private MerchantMappers.ServiceAreaMapper areaMapper;
    @Autowired
    private MerchantMappers.ServiceAreaCellMapper cellMapper;
    @Autowired
    private DbHitFinder dbFinder;
    @Autowired
    private ReachGeoProps props;

    private String entity;
    private String storeA;   // 框区县 440304
    private String storeB;   // 点名小区 + 排除其一栋楼 + 一条待审的市级（不该算）
    private String storeC;   // 一个纳入多边形 + 其中一小块排除多边形

    private String communityB = "CM-PAR-" + suffix();
    private String buildingB2 = communityB + "-B2";

    /** 约 1 km × 1 km：lng 114.00→114.01，lat 22.50→22.51 */
    private static final String SQUARE =
            "[[114000000,22500000],[114010000,22500000],[114010000,22510000],[114000000,22510000]]";
    /** 方块里的一小块：lng 114.003→114.005，lat 22.503→22.505 */
    private static final String HOLE =
            "[[114003000,22503000],[114005000,22503000],[114005000,22505000],[114003000,22505000]]";

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    @BeforeEach
    void seed() {
        String s = suffix();
        entity = "E-PAR-" + s;
        storeA = "ST-PAR-A-" + s;
        storeB = "ST-PAR-B-" + s;
        storeC = "ST-PAR-C-" + s;
        communityB = "CM-PAR-" + s;
        buildingB2 = communityB + "-B2";

        area(storeA, MchServiceArea.LEVEL_DISTRICT, "440304", MchServiceArea.MODE_INCLUDE, MchServiceArea.ACTIVE, null);
        area(storeB, MchServiceArea.LEVEL_COMMUNITY, communityB, MchServiceArea.MODE_INCLUDE, MchServiceArea.ACTIVE, null);
        area(storeB, MchServiceArea.LEVEL_COMMUNITY, buildingB2, MchServiceArea.MODE_EXCLUDE, MchServiceArea.ACTIVE, null);
        area(storeB, MchServiceArea.LEVEL_CITY, "1408", MchServiceArea.MODE_INCLUDE, MchServiceArea.PENDING, null);
        polygon(storeC, SQUARE, MchServiceArea.MODE_INCLUDE);
        polygon(storeC, HOLE, MchServiceArea.MODE_EXCLUDE);
    }

    @Test
    @DisplayName("★★★ 六种画像下，DB 点查与内存查找逐店逐项相等")
    void dbAndInMemoryAgree() {
        List<ConsumerProfile> profiles = List.of(
                profile("440304001", null, null, null, null),                 // 区内、无小区无坐标 → A
                profile("330106001", communityB, null, null, null),           // 点名小区、杭州码 → B(b1)，待审市级不算
                profile("330106001", buildingB2, communityB, null, null),     // 那栋楼：纳入经父、排除直接命中 → B(b1 + 排除 b2)
                profile(null, null, null, 22508000, 114008000),               // 方块内、洞外 → C 纳入
                profile(null, null, null, 22504000, 114004000),               // 洞内 → C 纳入 + 排除
                profile("440304", null, null, 22600000, 114100000));          // 坐标在远处、码在区内 → A

        List<String> stores = List.of(storeA, storeB, storeC);
        for (ConsumerProfile p : profiles) {
            Map<String, StoreHits> db = new LinkedHashMap<>(dbFinder.find(p, null));
            db.keySet().retainAll(stores);
            Map<String, StoreHits> mem = new LinkedHashMap<>();
            for (String st : stores) {
                StoreHits h = InMemoryHitFinder.hitsFor(items(st), p);
                if (!isEmpty(h)) {
                    mem.put(st, h);
                }
            }
            assertThat(db).as("画像 %s：DB 与内存结果要逐店相等", p).isEqualTo(mem);
        }
    }

    @Test
    @DisplayName("★★ 期望形状：区内只命中 A；楼栋画像在 B 上同时有纳入(经父)与排除；洞内在 C 上同时有纳入与排除")
    void expectedShapes() {
        Map<String, StoreHits> inDistrict = dbFinder.find(profile("440304001", null, null, null, null), null);
        assertThat(inDistrict).containsKey(storeA).doesNotContainKeys(storeB, storeC);
        assertThat(inDistrict.get(storeA).includeAreaNos()).hasSize(1);

        Map<String, StoreHits> atBuilding = dbFinder.find(profile("330106001", buildingB2, communityB, null, null), null);
        assertThat(atBuilding.get(storeB).includeAreaNos()).as("纳入经父聚落命中").hasSize(1);
        assertThat(atBuilding.get(storeB).excludeAreaNos()).as("排除直接命中那栋楼").hasSize(1);
        assertThat(atBuilding).doesNotContainKeys(storeA, storeC);

        Map<String, StoreHits> inHole = dbFinder.find(profile(null, null, null, 22504000, 114004000), null);
        StoreHits c = inHole.get(storeC);
        assertThat(c).isNotNull();
        assertThat(c.includeAreaNos().size() + c.boundaryIncludeAreaNos().size()).as("纳入多边形命中（内部或边界 cell）").isPositive();
        assertThat(c.excludeAreaNos().size() + c.boundaryExcludeAreaNos().size()).as("排除多边形命中（内部或边界 cell）").isPositive();

        Map<String, StoreHits> onlyB = dbFinder.find(profile("330106001", communityB, null, null, null), storeB);
        assertThat(onlyB).containsOnlyKeys(storeB);
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private ConsumerProfile profile(String regionCode, String communityNo, String parentNo, Integer lat, Integer lng) {
        return ConsumerProfile.of(regionCode, communityNo, parentNo, lat, lng, props.getS2MinLevel(), props.getS2MaxLevel());
    }

    private String area(String store, String level, String ref, String mode, String status, String geometry) {
        MchServiceArea row = new MchServiceArea();
        row.setAreaNo(BizKey.next(BizKey.SERVICE_AREA));
        row.setEntityNo(entity);
        row.setStoreNo(store);
        row.setLevel(level);
        row.setRefCode(ref);
        row.setMode(mode);
        row.setStatus(status);
        row.setSource("SELF");
        row.setGeometry(geometry);
        DataScopeContext.executeWithoutScope(() -> areaMapper.insert(row));
        return row.getAreaNo();
    }

    private void polygon(String store, String json, String mode) {
        GeoPolygon g = GeoPolygon.parse(json, props.getPolygonMaxVertices());
        String areaNo = area(store, MchServiceArea.LEVEL_POLYGON, g.fingerprint(), mode, MchServiceArea.ACTIVE, g.normalizedJson());
        for (S2Cover.Cell c : S2Cover.cover(g, props.getS2MinLevel(), props.getS2MaxLevel(), props.getS2MaxCells())) {
            MchServiceAreaCell cell = new MchServiceAreaCell();
            cell.setAreaNo(areaNo);
            cell.setEntityNo(entity);
            cell.setStoreNo(store);
            cell.setMode(mode);
            cell.setCellId(c.token());
            cell.setS2Level(c.level());
            cell.setBoundary(c.boundary());
            DataScopeContext.executeWithoutScope(() -> cellMapper.insert(cell));
        }
    }

    private StoreItems items(String store) {
        List<MchServiceArea> areas = DataScopeContext.executeWithoutScope(() -> areaMapper.selectList(
                Wrappers.<MchServiceArea>lambdaQuery().eq(MchServiceArea::getStoreNo, store)));
        List<MchServiceAreaCell> cells = DataScopeContext.executeWithoutScope(() -> cellMapper.selectList(
                Wrappers.<MchServiceAreaCell>lambdaQuery().eq(MchServiceAreaCell::getStoreNo, store)));
        return new StoreItems(store, areas, cells);
    }

    private static boolean isEmpty(StoreHits h) {
        return h.includeAreaNos().isEmpty() && h.excludeAreaNos().isEmpty()
                && h.boundaryIncludeAreaNos().isEmpty() && h.boundaryExcludeAreaNos().isEmpty();
    }
}
