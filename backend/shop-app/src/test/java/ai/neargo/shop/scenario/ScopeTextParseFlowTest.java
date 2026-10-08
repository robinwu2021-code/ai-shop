package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.portal.biz.BizRegionController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经营范围文字录入：一句话 → 建议的范围项（TDD-经营范围文字录入 AC1–AC4）。
 *
 * <p>区划用一棵**编出来的**树（省码 99，真实国标里没有），不碰共享种子；省级用真实简称
 * （新疆/西藏走简称表，不读库）。收尾删掉本组造的所有行。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScopeTextParseFlowTest {

    @Autowired
    private BizRegionController controller;

    @Autowired
    private ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper regionMapper;

    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;

    private static final List<String> CODES = List.of("99", "9901", "9902", "990101", "990102", "990201");
    private static final List<String> COMMUNITIES = List.of("STX-ESTATE", "STX-B3", "STX-B5");

    @BeforeEach
    void seed() {
        cleanup();
        region("99", null, "PROVINCE", "测试文字省");
        region("9901", "99", "CITY", "甲城市");
        region("9902", "99", "CITY", "乙城市");
        region("990101", "9901", "DISTRICT", "鹿鸣区");
        region("990102", "9901", "DISTRICT", "松林区");
        region("990201", "9902", "DISTRICT", "鹿鸣区");   // 与甲城市的鹿鸣区同名
        community("STX-ESTATE", "云杉花园", "990102", null);
        community("STX-B3", "3栋", "990102", "STX-ESTATE");
        community("STX-B5", "5栋", "990102", "STX-ESTATE");
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 物理删除：mapper 的 delete 是逻辑删除，行还在，下一条用例插同一个码会撞唯一键 */
    @AfterEach
    void cleanup() {
        for (String c : CODES) {
            jdbc.update("delete from sys_region where region_code = ?", c);
        }
        for (String c : COMMUNITIES) {
            jdbc.update("delete from cmt_community where community_no = ?", c);
        }
    }

    private BizRegionController.ParseVO parse(String text) {
        return controller.parse(new BizRegionController.ParseReq(text, null, null));
    }

    @Test
    @DisplayName("★★★ AC1「全国发货，新疆、西藏不发」→ 不限 + 两条省级排除")
    void unlimitedMinusProvinces() {
        var r = parse("全国发货，新疆、西藏不发");
        assertThat(r.unlimited()).isTrue();
        assertThat(r.items()).extracting(BizRegionController.ParsedArea::mode, BizRegionController.ParsedArea::level,
                        BizRegionController.ParsedArea::refCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("EXCLUDE", "PROVINCE", "65"),
                        org.assertj.core.groups.Tuple.tuple("EXCLUDE", "PROVINCE", "54"));
        assertThat(r.items().get(0).name()).isEqualTo("新疆维吾尔自治区");
        assertThat(r.unmatched()).isEmpty();
        assertThat(r.ambiguous()).isEmpty();
    }

    @Test
    @DisplayName("★★ 粘连的省「新疆西藏青海不发货」→ 三条省级排除")
    void gluedProvinces() {
        var r = parse("新疆西藏青海不发货");
        assertThat(r.items()).extracting(BizRegionController.ParsedArea::refCode).containsExactly("65", "54", "63");
        assertThat(r.items()).allMatch(a -> "EXCLUDE".equals(a.mode()));
    }

    @Test
    @DisplayName("★★★ AC2 唯一的区直接认准，带整条路径（与选择器存的 name 同形）")
    void uniqueDistrict() {
        var r = parse("松林区");
        assertThat(r.items()).hasSize(1);
        var a = r.items().get(0);
        assertThat(a.mode()).isEqualTo("INCLUDE");
        assertThat(a.level()).isEqualTo("DISTRICT");
        assertThat(a.refCode()).isEqualTo("990102");
        assertThat(a.name()).isEqualTo("测试文字省 / 甲城市 / 松林区");
    }

    @Test
    @DisplayName("★★★ AC2 同名两处 → 列候选，不替店主猜；带上级说「甲城鹿鸣区」→ 唯一认准")
    void sameNameGivesCandidatesUnlessQualified() {
        var r = parse("鹿鸣区");
        assertThat(r.items()).as("同名时不能挑一个塞进去").isEmpty();
        assertThat(r.ambiguous()).hasSize(1);
        assertThat(r.ambiguous().get(0).candidates()).extracting(BizRegionController.Candidate::refCode)
                .containsExactlyInAnyOrder("990101", "990201");

        var q = parse("甲城鹿鸣区");
        assertThat(q.ambiguous()).isEmpty();
        assertThat(q.items()).extracting(BizRegionController.ParsedArea::refCode).containsExactly("990101");
    }

    @Test
    @DisplayName("★★★ AC3 「云杉花园3栋不送」→ 那一栋的排除；「云杉花园」本身可纳入")
    void buildingUnderEstate() {
        var r = parse("云杉花园都送，云杉花园3栋不送");
        assertThat(r.items()).extracting(BizRegionController.ParsedArea::mode, BizRegionController.ParsedArea::refCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("INCLUDE", "STX-ESTATE"),
                        org.assertj.core.groups.Tuple.tuple("EXCLUDE", "STX-B3"));
        assertThat(r.items().get(1).name()).as("路径 + 楼栋名").isEqualTo("测试文字省 / 甲城市 / 松林区 / 3栋");
    }

    @Test
    @DisplayName("★★ AC4 认不出的短语原样退回；不做包含式模糊命中")
    void unmatchedKeptVerbatim() {
        var r = parse("偏远地区不送，松林");
        assertThat(r.unmatched()).as("「偏远」没有对应的区划").contains("偏远");
        // 「松林」+ 后缀「区」= 松林区：差一个行政后缀算同名
        assertThat(r.items()).extracting(BizRegionController.ParsedArea::refCode).containsExactly("990102");
        var fuzzy = parse("松");
        assertThat(fuzzy.items()).as("单字不能模糊命中松林区").isEmpty();
    }

    @Test
    @DisplayName("★★ 同一个地方说两遍，后说的方向赢，只出一条")
    void laterDirectionWins() {
        var r = parse("松林区，松林区不送");
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).mode()).isEqualTo("EXCLUDE");
    }

    private void region(String code, String parent, String level, String name) {
        var r = new ai.neargo.shop.platform.entity.SysRegion();
        r.setRegionCode(code);
        r.setParentCode(parent);
        r.setLevel(level);
        r.setName(name);
        r.setEnabled(true);
        r.setSort(0);
        regionMapper.insert(r);
    }

    private void community(String no, String name, String regionCode, String parentNo) {
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
    }
}
