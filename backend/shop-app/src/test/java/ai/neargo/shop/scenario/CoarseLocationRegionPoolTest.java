package ai.neargo.shop.scenario;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 粗定位这一级：**位置不明 ≠ 看全平台的货**（TDD-C端位置选择-地址取代自提点 · M5，判据 8/9）。
 *
 * <p>改造前：模糊定位解析不出聚落 → 端上不带任何筛选条件去要商品 → 拿回**全平台**的货。
 * 那不是一个决定，是过滤被跳过的副作用，而它在界面上与「这就是你这儿的货」长得一模一样。
 *
 * <p>改造后：模糊坐标仍然不匹配聚落（5 公里误差配 1000 米围栏，出来的是噪音），
 * 但落得准**区县**，商品池就按那个区筛。
 *
 * <p><b>为什么要自己插种子</b>：测试库里一件商品、一行区划都没有 ——
 * 不插的话「按区筛出来是空的」与「按区筛没生效」在断言上完全一样，这条用例什么也证明不了。
 * 插进去的每一行在 {@link #cleanup()} 里原样删掉（库是全量测试共用的）。
 */
@SpringBootTest
@ActiveProfiles("test")
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class CoarseLocationRegionPoolTest {

    /*
     * **区划码全用合成的。** 第一版借了西湖区（330106）与演示社区的坐标，
     *单独跑是绿的、全量跑直接 DuplicateKey —— 全量下 sys_region 里有真数据，
     * 而单独跑时那张表是空的。借真码还有第二个坑：`byCoords` 取的是**最近的**村，
     * 真数据里附近有村时，插进去的那条未必赢。
     *
     * 所以：自己造一套不可能撞的码，村的坐标就压在探测点上（距离 0，除非真有村在同一点）。
     */
    private static final String DISTRICT = "998877";
    /** **对照区**。它底下的货必须不出现在上面那个区的结果里 */
    private static final String OTHER_DISTRICT = "998866";
    /** 一个开放社区都没有的区 —— 「按区筛出来是空」的那一格 */
    private static final String EMPTY_DISTRICT = "997700";
    private static final String DISTRICT_NAME = "位置兜底测试区";

    /**
     * 探测点。村与 {@link #C_IN} 都插在这个点上。
     *
     * <p><b>挑一个荒无人烟的坐标</b>（新疆东部），不用演示社区那一带 ——
     * 「最近的聚落是哪个」对**全库**求最小值，而演示种子 C0001/C0002 就在杭州那个点上，
     * 放在一起时最近的那个是谁取决于扫表顺序。第一版就栽在这儿：
     * 断言写的是自己那个社区，跑出来是别人的，而报错长得像「围栏判定坏了」。
     */
    private static final int LAT_E6 = 45000000;
    private static final int LNG_E6 = 95000000;
    /** 约 20 公里外 —— 落不进围栏（1000 米），但在默认上限（50 公里）之内 */
    private static final int LAT_E6_20KM = LAT_E6 + 180000;
    /** 约 550 公里外 —— 超出默认上限 */
    private static final int LAT_E6_FAR = LAT_E6 + 5000000;
    /** 对照区的聚落挪开 3 个经度（约 235 公里）：它不该在任何一条用例里赢得「最近」 */
    private static final int LNG_E6_OUT = LNG_E6 + 3000000;

    private static final String C_IN = "CT5R01";
    private static final String C_OUT = "CT5R02";
    private static final String G_IN = "GT5R01";
    private static final String G_OUT = "GT5R02";
    /**
     * 两件货共同的标题前缀。**断言一律带上它。**
     *
     * <p>不带的话，对照量那一条要在「不筛」的整份目录里找这两件 ——
     * 而排序按销量、我的货销量是 0，全量跑时它们根本进不了前 50 页。
     * 那种失败与「按区筛坏了」长得一模一样。
     */
    private static final String MARK = "GT5R";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    void seed() {
        cleanup(); // 上一次跑崩在中途时留下的残骸，先清干净再插

        // 区划：区 → 街道 → 村。byCoords 认的是**有坐标的村**，再沿 parent 上溯到街道
        region(DISTRICT, null, "DISTRICT", DISTRICT_NAME, null, null);
        region(DISTRICT + "001", DISTRICT, "STREET", "兜底测试街道", null, null);
        region(DISTRICT + "001001", DISTRICT + "001", "VILLAGE", "兜底测试村", LAT_E6, LNG_E6);

        // 两个聚落，分属两个区。**对照组**：没有它，「筛没筛」看不出来
        community(C_IN, "按区筛-本区", DISTRICT, LAT_E6, LNG_E6);
        community(C_OUT, "按区筛-别区", OTHER_DISTRICT, LAT_E6, LNG_E6_OUT);
        goods(G_IN);
        goods(G_OUT);
        pool(C_IN, G_IN);
        pool(C_OUT, G_OUT);
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM prd_community_pool WHERE goods_no IN (?,?)", G_IN, G_OUT);
        jdbc.update("DELETE FROM prd_goods WHERE goods_no IN (?,?)", G_IN, G_OUT);
        jdbc.update("DELETE FROM cmt_community WHERE community_no IN (?,?)", C_IN, C_OUT);
        jdbc.update("DELETE FROM sys_region WHERE region_code IN (?,?,?)",
                DISTRICT, DISTRICT + "001", DISTRICT + "001001");
    }

    private void region(String code, String parent, String level, String name, Integer lat, Integer lng) {
        jdbc.update("INSERT INTO sys_region (region_code, parent_code, level, name, "
                        + "created_at, updated_at, lat_e6, lng_e6) VALUES (?,?,?,?,NOW(),NOW(),?,?)",
                code, parent, level, name, lat, lng);
    }

    private void community(String no, String name, String regionCode, int latE6, int lngE6) {
        jdbc.update("INSERT INTO cmt_community (community_no, name, status, region_code, "
                        + "lat_e6, lng_e6, created_at, updated_at) VALUES (?,?,'OPEN',?,?,?,NOW(),NOW())",
                no, name, regionCode, latE6, lngE6);
    }

    private void goods(String no) {
        jdbc.update("INSERT INTO prd_goods (goods_no, entity_no, title, type, on_sale, audit_status, "
                        + "created_at, updated_at) VALUES (?,'MT5R00',?, 'NORMAL', 1, 'APPROVED', NOW(), NOW())",
                no, no);
    }

    private void pool(String communityNo, String goodsNo) {
        jdbc.update("INSERT INTO prd_community_pool (community_no, goods_no, entity_no, "
                + "created_at, updated_at) VALUES (?,?,'MT5R00',NOW(),NOW())", communityNo, goodsNo);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private JsonNode data(String url) throws Exception {
        String body = mvc().perform(get(url))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private List<String> goodsNos(String url) throws Exception {
        JsonNode records = data(url).get("records");
        return java.util.stream.StreamSupport.stream(records.spliterator(), false)
                .map(n -> n.get("goodsNo").asString()).toList();
    }

    @Test
    @DisplayName("★★★ 判据 8：模糊定位落到区，商品池就按那个区筛 —— 别的区的货不出现")
    void coarseLocationFiltersByDistrict() throws Exception {

        /*
         * **对照量先验非零**：不带任何条件时两件货都在。
         * 少了这一句，下面那条断言在「库里本来就只有一件货」时也会绿，
         * 而那种绿什么也没证明。
         */
        List<String> unfiltered = goodsNos("/mp/goods?size=50&keyword=" + MARK);
        assertThat(unfiltered).as("对照量本身要成立：不筛的时候两个区的货都在")
                .contains(G_IN, G_OUT);

        List<String> byRegion = goodsNos("/mp/goods?size=50&keyword=" + MARK + "&regionCode=" + DISTRICT);
        assertThat(byRegion).as("按区筛之后本区的货没了 = 筛错了").contains(G_IN);
        assertThat(byRegion).as("别的区的货还在 = 按区筛根本没生效，看到的仍是全平台")
                .doesNotContain(G_OUT);
    }

    @Test
    @DisplayName("★★★ 判据 8 对照：精确定位的结果是按区结果的**子集**")
    void preciseResultIsSubsetOfDistrictResult() throws Exception {
        List<String> byCommunity = goodsNos("/mp/goods?size=50&keyword=" + MARK + "&communityNo=" + C_IN);
        List<String> byRegion = goodsNos("/mp/goods?size=50&keyword=" + MARK + "&regionCode=" + DISTRICT);
        assertThat(byCommunity).as("精确那一级本身要非空，否则这条用例什么也没比").isNotEmpty();
        assertThat(byRegion).as("精确定位看得到的货，按区看不到 = 两级的口径对不上")
                .containsAll(byCommunity);
    }

    @Test
    @DisplayName("★★★ 这个区一个开放社区都没有 → 空，**不是**回落成全平台")
    void districtWithoutCommunitiesIsEmptyNotEverything() throws Exception {
        assertThat(goodsNos("/mp/goods?size=50&keyword=" + MARK + "&regionCode=" + EMPTY_DISTRICT))
                .as("没铺货的区回落成了全平台 —— 那正是改造前那个缺陷本身")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 精确定位压过粗的：两个都传时按 communityNo 筛")
    void communityWins() throws Exception {
        assertThat(goodsNos("/mp/goods?size=50&keyword=" + MARK + "&communityNo=" + C_OUT
                + "&regionCode=" + DISTRICT))
                .as("粗的那个覆盖了精确的结论").containsExactly(G_OUT);
    }

    @Test
    @DisplayName("★★★ 模糊坐标：仍不给聚落（那是噪音），但要给出所在区县")
    void coarseResolveStillGivesTheDistrict() throws Exception {
        JsonNode ctx = data("/mp/location/resolve?latE6=" + LAT_E6 + "&lngE6=" + LNG_E6 + "&coarse=true");
        assertThat(ctx.get("innermostNo").isNull())
                .as("模糊坐标给了 innermost = 把 5 公里误差伪装成了精确匹配").isTrue();
        assertThat(ctx.get("regionCode").asString())
                .as("区县也不给的话，端上除了「全平台」无处可去 —— 那就是改造前").isEqualTo(DISTRICT);
        assertThat(ctx.get("regionName").asString())
                .as("顶栏要说明白「当前按 XX 区在看」，只给一串码等于没说").isEqualTo(DISTRICT_NAME);
    }

    @Test
    @DisplayName("★★★ M6 判据 1：围栏外 20 公里 → 给出最近的已开通聚落，端上据此默认归属")
    void outsideAnyFenceStillGetsTheNearestCommunity() throws Exception {
        /*
         * 冷启动期「不在任何围栏里」是**常态**：全市只有一两个聚落。
         * 此前这一支只回落到按区筛，而那个区往往一个聚落都没有 —— 首页就是空的。
         */
        JsonNode ctx = data("/mp/location/resolve?latE6=" + LAT_E6_20KM + "&lngE6=" + LNG_E6);
        assertThat(ctx.get("innermostNo").isNull())
                .as("20 公里外落进了围栏 = 围栏判定坏了，这条用例的前提不成立").isTrue();
        assertThat(ctx.get("nearestNo").asString())
                .as("围栏外就什么都不给 —— 那正是首页空掉的原因").isEqualTo(C_IN);
        assertThat(ctx.get("nearestName").asString()).isNotBlank();
        int m = ctx.get("nearestDistanceM").asInt();
        assertThat(m).as("距离要是真算出来的 —— 顶栏靠它说「约 N 公里」").isBetween(15000, 25000);
    }

    @Test
    @DisplayName("★★★ M6 判据 3：落进围栏时**不给** nearest —— 两个主语迟早会被选错")
    void insideAFenceGivesNoNearest() throws Exception {
        JsonNode ctx = data("/mp/location/resolve?latE6=" + LAT_E6 + "&lngE6=" + LNG_E6);
        assertThat(ctx.get("innermostNo").asString()).isEqualTo(C_IN);
        assertThat(ctx.get("nearestNo").isNull())
                .as("落进围栏了还给「最近的」，端上就有两个主语").isTrue();
    }

    @Test
    @DisplayName("★★★ M6 判据 4：超出上限 → 不给归属，但**距离照给**")
    void beyondTheCapGivesDistanceButNoBinding() throws Exception {
        // 往北挪 5 个纬度 ≈ 550 公里，远超默认的 50 公里上限
        JsonNode ctx = data("/mp/location/resolve?latE6=" + LAT_E6_FAR + "&lngE6=" + LNG_E6);
        assertThat(ctx.get("nearestNo").isNull())
                .as("五百公里外也给默认归属 = 让人看一屏送不到的货").isTrue();
        assertThat(ctx.get("nearestDistanceM").asInt())
                .as("连距离都不给，端上就只能写一句干巴巴的「还没开通」").isGreaterThan(400000);
    }

    @Test
    @DisplayName("★★ 判据 9：连坐标都没有 → 区县为 null，端上据此走空态要位置")
    void noCoordsMeansNoDistrict() throws Exception {
        JsonNode ctx = data("/mp/location/resolve?coarse=true");
        assertThat(ctx.get("regionCode").isNull())
                .as("没坐标却编出一个区 = 把一屏别处的货说成「你这儿的」").isTrue();
        assertThat(ctx.get("nearestNo").isNull())
                .as("没坐标却算得出「最近的」= 那个数是编的").isTrue();
        assertThat(ctx.get("nearestDistanceM").asInt())
                .as("算不出距离要给 -1，不是 0 —— 0 会被显示成「0 米」").isEqualTo(-1);
    }
}
