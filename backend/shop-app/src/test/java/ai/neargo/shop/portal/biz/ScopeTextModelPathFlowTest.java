package ai.neargo.shop.portal.biz;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.product.GoodsVisionPort.ScopeExtract;
import ai.neargo.shop.spi.product.GoodsVisionPort.ScopePlace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大模型那条路：模型给的整条行政路径 → 逐级查库认码（TDD-经营范围文字录入 §7）。
 *
 * <p>**不连真模型**：直接构造它的返回（{@link ScopeExtract}），测的是我们这边的认码 ——
 * 那才是「码从哪儿来」的唯一闸门。模型输出质量另用样本实跑，记在 TDD。
 *
 * <p>区划用编出来的树（省码 98）；真实码（山西/运城、广东/深圳）只补缺、收尾只删自己补的。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScopeTextModelPathFlowTest {

    @Autowired
    private ai.neargo.shop.platform.RegionService regionService;

    @Autowired
    private ai.neargo.shop.community.service.CommunityService communityService;

    @Autowired
    private ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper regionMapper;

    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final List<String> regions = new ArrayList<>();
    private final List<String> communities = new ArrayList<>();

    @BeforeEach
    void seed() {
        region("98", null, "PROVINCE", "模型测试省");
        region("9801", "98", "CITY", "丙城市");
        region("9802", "98", "CITY", "丁城市");
        region("980101", "9801", "DISTRICT", "桃源区");
        region("980201", "9802", "DISTRICT", "桃源区");   // 同名，靠上级区分
        community("SMX-ESTATE", "银杏花园", "980101", null);
        community("SMX-B3", "3栋", "980101", "SMX-ESTATE");
        region("14", null, "PROVINCE", "山西省");
        region("1408", "14", "CITY", "运城市");
        region("44", null, "PROVINCE", "广东省");
        region("4403", "44", "CITY", "深圳市");
    }

    @AfterEach
    void cleanup() {
        regions.forEach(c -> jdbc.update("delete from sys_region where region_code = ?", c));
        communities.forEach(c -> jdbc.update("delete from cmt_community where community_no = ?", c));
        regions.clear();
        communities.clear();
    }

    private BizRegionController.ParseVO run(boolean unlimited, List<ScopePlace> places, List<String> unclear) {
        var resolver = new ScopeTextResolver(regionService, communityService, null, null);
        return BizRegionController.fromModel(new ScopeExtract(unlimited, places, unclear), resolver);
    }

    private static ScopePlace inc(String text, String... path) {
        return new ScopePlace(List.of(path), "INCLUDE", text, false);
    }

    @Test
    @DisplayName("★★★ 店主原话「深圳，山西运城，广东等」：模型给的路径逐级认准，source=llm")
    void ownerSentence() {
        var r = run(false, List.of(
                inc("深圳", "广东省", "深圳市"),
                inc("山西运城", "山西省", "运城市"),
                inc("广东", "广东省")), List.of());
        assertThat(r.source()).isEqualTo("llm");
        assertThat(r.items()).extracting(BizRegionController.ParsedArea::refCode).containsExactly("4403", "1408", "44");
        assertThat(r.items().get(1).name()).isEqualTo("山西省 / 运城市");
        assertThat(r.items().get(1).phrase()).isEqualTo("山西运城");
        assertThat(r.unmatched()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 同名区靠上级分开：丙城的桃源区与丁城的桃源区各认各的")
    void parentDisambiguates() {
        var r = run(false, List.of(inc("丁城桃源区", "模型测试省", "丁城市", "桃源区")), List.of());
        assertThat(r.items()).extracting(BizRegionController.ParsedArea::refCode).containsExactly("980201");
        assertThat(r.ambiguous()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 路径中间断了 → 整条认不出，绝不退回上一级（否则「不存在的小区」会放大成整个区）")
    void brokenPathIsUnmatchedNotWidened() {
        var r = run(false, List.of(inc("桃源区不存在花园", "模型测试省", "丙城市", "桃源区", "不存在花园")), List.of());
        assertThat(r.items()).as("不能把整个桃源区塞进去").isEmpty();
        assertThat(r.unmatched()).containsExactly("桃源区不存在花园");
    }

    @Test
    @DisplayName("★★ 小区与楼栋接在区划后面：只在那个区里找；放错区就认不出")
    void estateAndBuildingUnderRegion() {
        var ok = run(false, List.of(new ScopePlace(List.of("模型测试省", "丙城市", "桃源区", "银杏花园", "3栋"),
                "EXCLUDE", "银杏花园3栋", false)), List.of());
        assertThat(ok.items()).extracting(BizRegionController.ParsedArea::mode, BizRegionController.ParsedArea::refCode)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("EXCLUDE", "SMX-B3"));

        var wrongDistrict = run(false, List.of(inc("丁城银杏花园", "模型测试省", "丁城市", "桃源区", "银杏花园")), List.of());
        assertThat(wrongDistrict.items()).as("银杏花园在丙城，不在丁城").isEmpty();
        assertThat(wrongDistrict.unmatched()).containsExactly("丁城银杏花园");
    }

    @Test
    @DisplayName("★★ guess 原样带出（端上默认不勾）；模型拿不准的 unclear 进 unmatched；不限照传")
    void guessAndUnclear() {
        var r = run(true, List.of(new ScopePlace(List.of("新疆维吾尔自治区"), "EXCLUDE", "偏远地区", true)),
                List.of("老城区"));
        assertThat(r.unlimited()).isTrue();
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).guess()).isTrue();
        assertThat(r.items().get(0).refCode()).isEqualTo("65");
        assertThat(r.unmatched()).containsExactly("老城区");
    }

    @Test
    @DisplayName("★★ 只给一段、又同名多处 → 候选，不替店主选")
    void singleSegmentAmbiguous() {
        var r = run(false, List.of(inc("桃源区", "桃源区")), List.of());
        assertThat(r.items()).isEmpty();
        assertThat(r.ambiguous()).hasSize(1);
        assertThat(r.ambiguous().get(0).candidates()).extracting(BizRegionController.Candidate::refCode)
                .containsExactlyInAnyOrder("980101", "980201");
    }

    /** 已存在（别的用例造过）就不动它，收尾也不删 */
    private void region(String code, String parent, String level, String name) {
        Integer n = jdbc.queryForObject("select count(*) from sys_region where region_code = ?", Integer.class, code);
        if (n != null && n > 0) {
            return;
        }
        var r = new ai.neargo.shop.platform.entity.SysRegion();
        r.setRegionCode(code);
        r.setParentCode(parent);
        r.setLevel(level);
        r.setName(name);
        r.setEnabled(true);
        r.setSort(0);
        regionMapper.insert(r);
        regions.add(code);
    }

    private void community(String no, String name, String regionCode, String parentNo) {
        jdbc.update("delete from cmt_community where community_no = ?", no);
        DataScopeContext.executeWithoutScope(() -> {
            var c = new ai.neargo.shop.community.entity.CmtCommunity();
            c.setCommunityNo(no);
            c.setName(name);
            c.setStatus("OPEN");
            c.setRegionCode(regionCode);
            c.setParentNo(parentNo);
            if (parentNo != null) {
                c.setKind(ai.neargo.shop.community.entity.CmtCommunity.KIND_BUILDING);
            }
            c.setFenceRadius(300);
            return communityMapper.insert(c);
        });
        communities.add(no);
    }
}
