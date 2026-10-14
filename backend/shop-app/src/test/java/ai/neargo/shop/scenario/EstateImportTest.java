package ai.neargo.shop.scenario;

import ai.neargo.shop.community.service.CommunityAdminService;
import ai.neargo.shop.community.service.CommunityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 地图小区批量建档（TDD-深圳小区批量导入与自动匹配 · M2）。
 *
 * <p>这个接口一次动几千行，而它的错误**要到买家那一端才看得见**：
 * 建出一个坐标为空的小区，那个小区的人永远搜不到货，且没有任何报错。
 * 所以这一组守的都是「不许悄悄发生」的事。
 */
@SpringBootTest
@ActiveProfiles("test")
class EstateImportTest {

    /** 用一个不可能与真数据撞的区划前缀 */
    private static final String REGION = "998801";
    private static final String P1 = "TESTPOI0001";
    private static final String P2 = "TESTPOI0002";

    @Autowired
    private CommunityAdminService admin;
    @Autowired
    private CommunityService communityService;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM cmt_community WHERE origin_code IN (?,?)", P1, P2);
    }

    private static CommunityAdminService.EstateIn e(String no, String name, int lat, int lng) {
        return new CommunityAdminService.EstateIn(no, name, name + " 路 1 号", lat, lng);
    }

    @Test
    @DisplayName("★★★ 默认是**试算**，而且默认建成 CLOSED —— 一次动几千行的接口，默认值要在安全那一边")
    void dryRunByDefaultAndClosedByDefault() {
        var dry = admin.importEstates(REGION, null, true,
                List.of(e(P1, "试算小区", 22600000, 114000000)), "T");
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.created()).as("试算也要把「会新建几条」算出来，否则试算等于没试").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cmt_community WHERE origin_code = ?", Integer.class, P1))
                .as("试算却写了库 —— 那这个参数是句空话").isZero();

        admin.importEstates(REGION, null, false, List.of(e(P1, "试算小区", 22600000, 114000000)), "T");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cmt_community WHERE origin_code = ?", String.class, P1))
                .as("默认就 OPEN 的话，买家会被匹配到自家小区、然后看到一屏空货架")
                .isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("★★★ 重跑不产生重复 —— 重复的聚落会让同一个坐标落进两个围栏")
    void rerunIsIdempotent() {
        var first = admin.importEstates(REGION, "CLOSED", false,
                List.of(e(P1, "甲小区", 22600000, 114000000)), "T");
        var again = admin.importEstates(REGION, "CLOSED", false,
                List.of(e(P1, "甲小区改了名", 22600001, 114000001)), "T");

        assertThat(first.created()).isEqualTo(1);
        assertThat(again.created()).as("重扫一次就多一条 = 几千条重复").isZero();
        assertThat(again.updated()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cmt_community WHERE origin_code = ?", Integer.class, P1))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT name FROM cmt_community WHERE origin_code = ?", String.class, P1))
                .as("重跑要把改过的名字更新过来，否则地图上改了名这儿永远是旧的")
                .isEqualTo("甲小区改了名");
    }

    @Test
    @DisplayName("★★★ 同名同址的多个 POI 只建一条 —— 高德对一个小区常给东门/西门各一条，poiId 各不相同")
    void sameNameNearbyPoisCreateOneCommunity() {
        /*
         * 线上「百花公寓」就是这个形状：两个 poiId、名字一字不差、相距约 256 米，
         * 同一分钟由 SYSTEM 导进来。origin_code 查重只认「同一个 POI 导两次」，
         * 认不出「一个小区有好几个 POI」，于是一个小区在库里有两份档案。
         * 商家的经营范围指着其中一份、买家选中另一份时，可见性按聚落号精确比对，
         * 就是 0 件商品 —— 而界面上两份长得一模一样。
         */
        var r = admin.importEstates(REGION, "CLOSED", false, List.of(
                e(P1, "百花测试公寓", 22553488, 114092370),
                e(P2, "百花测试公寓", 22551194, 114092200)), "T");

        assertThat(r.created()).as("同一个小区的两个 POI 只该建一条").isEqualTo(1);
        assertThat(r.deduped()).as("去重掉的要单独计数，否则导一个区根本不知道地图给重了多少").isEqualTo(1);
        assertThat(r.skipped()).as("去重不是「数据不合格」，不该混进 skipped").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cmt_community WHERE origin_code IN (?,?)", Integer.class, P1, P2))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 名字差一点就不并 —— 龙华真数据里「景华新村东区/西区」间距才几百米，是两个小区")
    void nearbyButDifferentNamesStaySeparate() {
        /*
         * 守的是去重**不许过头**。DenseEstateMatchTest 用的龙华真实数据里，
         * 一公里内有 179 个小区、最小间距 0 米（两个 POI 落在同一个点上）却是不同小区，
         * 名字全是「景华新村 / 景华新村东区 / 景华新村西区 / 景华新村南区」这种。
         * 所以判据只能是**名字完全相同**，不能是「名字相近」或「离得近」——
         * 漏合只是多一条待治理，错合是把两个真实小区并成一个，那个小区的人直接找不到自己家。
         */
        var r = admin.importEstates(REGION, "CLOSED", false, List.of(
                e(P1, "景华测试新村东区", 22654965, 114024485),
                e(P2, "景华测试新村西区", 22654592, 114022763)), "T");

        assertThat(r.created()).as("名字不同就是两个小区，离得再近也不能并").isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 同名但隔得远的不并 —— 一个区里两个同名小区是重名，不是重复")
    void sameNameFarApartStaySeparate() {
        var r = admin.importEstates(REGION, "CLOSED", false, List.of(
                e(P1, "幸福测试花园", 22600000, 114000000),
                e(P2, "幸福测试花园", 22700000, 114100000)), "T");

        assertThat(r.created()).as("隔着十几公里的同名小区是重名，并了是事故").isEqualTo(2);
    }

    @Test
    @DisplayName("★★★ 没坐标的**跳过**，不是建一个空坐标的 —— 那种小区的人永远搜不到货且不报错")
    void skipsItemsWithoutCoords() {
        var r = admin.importEstates(REGION, "CLOSED", false, List.of(
                new CommunityAdminService.EstateIn(P1, "没坐标的", "某路", null, null),
                e(P2, "有坐标的", 22600000, 114000000)), "T");
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.created()).as("对照量：有坐标的那条要真的建出来，否则这条用例什么也没证明").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cmt_community WHERE origin_code = ?", Integer.class, P1))
                .isZero();
    }

    @Test
    @DisplayName("★★★ 重跑**不覆盖状态** —— 否则重扫一次就是一场没人察觉的批量关城")
    void rerunDoesNotResetStatus() {
        admin.importEstates(REGION, "CLOSED", false, List.of(e(P1, "甲小区", 22600000, 114000000)), "T");
        admin.openMapCommunities(REGION, "T");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cmt_community WHERE origin_code = ?", String.class, P1))
                .isEqualTo("OPEN");

        admin.importEstates(REGION, "CLOSED", false, List.of(e(P1, "甲小区", 22600000, 114000000)), "T");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cmt_community WHERE origin_code = ?", String.class, P1))
                .as("重扫把开着的城关回去了 —— 而界面上没有任何人做过这个动作")
                .isEqualTo("OPEN");
    }

    @Test
    @DisplayName("★★★ 批量开城的前缀**不许为空** —— 空前缀在 likeRight 下匹配一切")
    void emptyPrefixIsRefused() {
        assertThatThrownBy(() -> admin.openMapCommunities("", "T"))
                .as("空串放行 = 把全国所有地图聚落一次开城").isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> admin.openMapCommunities(null, "T"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("★★ 建出来的围栏是 300 米，不是小区默认的 1000 —— 深圳的小区挨得很近")
    void fenceIsTightForMapEstates() {
        admin.importEstates(REGION, "CLOSED", false, List.of(e(P1, "甲小区", 22600000, 114000000)), "T");
        assertThat(jdbc.queryForObject(
                "SELECT fence_radius FROM cmt_community WHERE origin_code = ?", Integer.class, P1))
                .as("1000 米会让一个坐标同时落进十几个围栏").isEqualTo(300);
    }

    @Test
    @DisplayName("★★★ 开城之后买家才匹配得到 —— 这一步没做的话导入等于没导")
    void onlyOpenedOnesAreMatchable() {
        admin.importEstates(REGION, "CLOSED", false, List.of(e(P1, "甲小区", 22600000, 114000000)), "T");
        assertThat(communityService.resolve(22600000, 114000000, false).innermostNo())
                .as("CLOSED 的聚落就匹配得到 = 导入这一步没法安全地先行").isNotEqualTo(
                        jdbc.queryForObject("SELECT community_no FROM cmt_community WHERE origin_code = ?",
                                String.class, P1));

        admin.openMapCommunities(REGION, "T");
        String no = jdbc.queryForObject(
                "SELECT community_no FROM cmt_community WHERE origin_code = ?", String.class, P1);
        assertThat(communityService.resolve(22600000, 114000000, false).innermostNo())
                .as("开了城还匹配不到，那开城这一步是白做的").isEqualTo(no);
    }
}
