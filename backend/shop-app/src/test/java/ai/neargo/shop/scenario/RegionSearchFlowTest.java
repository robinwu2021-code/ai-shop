package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.platform.RegionService;
import ai.neargo.shop.platform.entity.SysRegion;
import ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨级搜索：**任何一级都要搜得到**（经营范围＝任意一级的并集）。
 *
 * <p>这组用例守的是两个真实发生过的形态，两个都不报错、都只表现为「搜不到」：
 *
 * <ol>
 *   <li><b>省根本不在查询里</b>。曾经写死 {@code in(level, CITY, DISTRICT, STREET)}，
 *       理由是「没人按省框范围」—— 而走快递的商家框的就是省。他打「山西」，
 *       一条也没有，只能从全国列表一级级点下去。</li>
 *   <li><b>细的那一级把配额吃光</b>。曾经是一个 {@code LIMIT 20} 加
 *       {@code ORDER BY level DESC}，而字符串降序是 STREET &gt; PROVINCE &gt; DISTRICT &gt; CITY，
 *       于是二十条街道占满列表，「运城市」进不来 —— 界面上看着就像「这个市不存在」。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class RegionSearchFlowTest {

    private static final String KW = "运城";

    @Autowired
    private RegionService regionService;

    @Autowired
    private RegionMapper regionMapper;

    @BeforeEach
    void seed() {
        // 山西 → 运城市 → 盐湖区 → 一堆带「运城」字样的街道（模拟细粒度刷屏）
        region("14", null, "PROVINCE", "山西省");
        region("1408", "14", "CITY", "运城市");
        region("140802", "1408", "DISTRICT", "盐湖区");
        region("140803", "1408", "DISTRICT", "运城开发区");
        for (int i = 1; i <= 12; i++) {
            region("1408020" + (i < 10 ? "0" + i : i), "140802", "STREET", "运城街道" + i);
        }
    }

    @Test
    @DisplayName("★★ 搜「运城」：市与区都在结果里 —— 不再被十几条同名街道挤掉")
    void quotaKeepsCoarseLevels() {
        List<RegionService.RegionVO> hits = regionService.search(KW, 24, null, null);

        assertThat(hits).extracting(RegionService.RegionVO::name).contains("运城市");
        assertThat(hits).extracting(RegionService.RegionVO::level).contains("CITY", "DISTRICT", "STREET");
        // 盐湖区不带「运城」二字，本来就不该命中 —— 命中的是同市的「运城开发区」
        assertThat(hits).extracting(RegionService.RegionVO::name).doesNotContain("盐湖区");
        // 街道有 12 条，配额是 8 —— 多的那几条必须让位给粗粒度，而不是反过来
        assertThat(hits.stream().filter(h -> "STREET".equals(h.level())).count()).isLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("★★ 省能搜到，而且一个字就够 —— 「京」「晋」本身就是完整的省级说法")
    void provinceIsSearchable() {
        assertThat(regionService.search("山西", 24, null, null))
                .extracting(RegionService.RegionVO::name).contains("山西省");
        assertThat(regionService.search("山", 24, null, null))
                .extracting(RegionService.RegionVO::name).contains("山西省");
    }

    @Test
    @DisplayName("★ 完全同名的排在只是「包含」的前面 —— 搜「运城」要的是运城市，不是「幸福运城苑」")
    void exactBeatsContains() {
        region("140899", "1408", "DISTRICT", "幸福运城苑区");

        List<RegionService.RegionVO> districts = regionService.search(KW, 24, null, null).stream()
                .filter(h -> "DISTRICT".equals(h.level()))
                .toList();
        assertThat(districts).isNotEmpty();
        // 「幸福运城苑区」只是包含，必须排在同级的前缀命中「运城开发区」之后
        List<RegionService.RegionVO> again = regionService.search(KW, 24, null, null).stream()
                .filter(h -> "DISTRICT".equals(h.level()))
                .toList();
        assertThat(indexOfName(again, "运城开发区")).isLessThan(indexOfName(again, "幸福运城苑区"));
    }

    @Test
    @DisplayName("★ 空关键词不返回全表 —— 那会让选择器在没输入时就下发几万行")
    void blankReturnsNothing() {
        assertThat(regionService.search("", 24, null, null)).isEmpty();
        assertThat(regionService.search(null, 24, null, null)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static int indexOfName(List<RegionService.RegionVO> rows, String name) {
        for (int i = 0; i < rows.size(); i++) {
            if (name.equals(rows.get(i).name())) {
                return i;
            }
        }
        return -1;
    }

    /** 幂等造行：同一个码再来一次就更新，免得每个用例各自清表 */
    private void region(String code, String parent, String level, String name) {
        DataScopeContext.executeWithoutScope(() -> {
            SysRegion exist = regionMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<SysRegion>lambdaQuery().eq(SysRegion::getRegionCode, code).last("limit 1"));
            SysRegion row = exist == null ? new SysRegion() : exist;
            row.setRegionCode(code);
            row.setParentCode(parent);
            row.setLevel(level);
            row.setName(name);
            row.setEnabled(true);
            row.setAuditStatus("APPROVED");
            return exist == null ? regionMapper.insert(row) : regionMapper.updateById(row);
        });
    }
}
