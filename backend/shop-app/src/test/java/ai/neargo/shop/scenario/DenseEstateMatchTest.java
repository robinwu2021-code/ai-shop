package ai.neargo.shop.scenario;

import ai.neargo.shop.community.service.CommunityAdminService;
import ai.neargo.shop.community.service.CommunityService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「站在自家小区门口，匹配到的是自家小区」—— <b>用龙华真实数据里最密的那一片验</b>。
 *
 * <p>为什么不能拿造出来的坐标验：<b>真实密度比设计时的假设密得多</b>。
 * 2026-09-17 扫下来的龙华 2783 个小区里，最密的一片<b>一公里内有 179 个</b>，
 * 相邻小区间距中位数 902 米、<b>最小 0 米</b>（两个 POI 落在同一个点上）。
 * 而围栏给的是 300 米 —— 一个坐标会同时落进十几个围栏，
 * 靠「同档比距离」把最近那个挑出来。这条规则在稀疏数据上永远是对的，
 * 只有在这种密度下才试得出来。
 *
 * <p>下面那 40 条是从那一片里按距离取的真数据（名字、坐标一字未改）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DenseEstateMatchTest {

    private static final String REGION = "998802";

    @Autowired
    private CommunityAdminService admin;
    @Autowired
    private CommunityService communityService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final List<CommunityAdminService.EstateIn> SAMPLE = List.of(
            new CommunityAdminService.EstateIn("B0KR67ZE2T", "景龙新邨东区", "龙华镇佳华商场正对面", 22657090, 114021387),
            new CommunityAdminService.EstateIn("B02F38IFYD", "景龙新村", "龙华镇佳华商场正对面", 22656769, 114021215),
            new CommunityAdminService.EstateIn("B0KR6RXH0I", "景龙新邨西区", "龙华镇佳华商场正对面", 22656570, 114021090),
            new CommunityAdminService.EstateIn("B0FFI92VRS", "康怡苑", "三联路与景龙路交叉口西北40米", 22658016, 114020346),
            new CommunityAdminService.EstateIn("B0LD4XQISX", "益深宿舍", "三联路与东环一路交叉口西南160米", 22658112, 114023386),
            new CommunityAdminService.EstateIn("B0FFFH0CJX", "景乐新村北区", "三联路与联弓路交叉口东北140米", 22659194, 114022269),
            new CommunityAdminService.EstateIn("B02F37VK62", "三联山咀头小区", "联步路东环一路", 22657120, 114023985),
            new CommunityAdminService.EstateIn("B0FFFDTWE8", "景乐新村南区", "东环一路", 22658623, 114023513),
            new CommunityAdminService.EstateIn("B02F37VKEF", "宾馆花园", "龙华街道龙园社区龙华人民路4232号", 22658728, 114019211),
            new CommunityAdminService.EstateIn("B0LDXX9STN", "一米晨公寓", "山咀头路与山咀头村一区五巷交叉口东100米", 22655439, 114023659),
            new CommunityAdminService.EstateIn("B0LKZ1GDP7", "京维蒂索T", "宝华路与景龙中环路交叉口东北120米", 22654556, 114020178),
            new CommunityAdminService.EstateIn("B0FFM9TCQ2", "景华新村西区", "山咀头路与山咀头村一区五巷交叉口南140米", 22654592, 114022763),
            new CommunityAdminService.EstateIn("B0FFFRLVG7", "三联山咀头小区一区", "山咀头村一区三巷12栋", 22656155, 114024474),
            new CommunityAdminService.EstateIn("B0FFF8GJHG", "景乐新村", "东环一路与三联路交叉口南60米", 22658869, 114024027),
            new CommunityAdminService.EstateIn("B02F37VKDQ", "金庸阁二期", "龙发路454号", 22654650, 114019244),
            new CommunityAdminService.EstateIn("B0FFG6M5GA", "龙华荔园新村", "龙发路245号", 22655752, 114018077),
            new CommunityAdminService.EstateIn("B02F37VKEA", "景华新村", "华龙路靠近首脑美容美发化妆美甲培训学校(龙华校区)", 22654588, 114023917),
            new CommunityAdminService.EstateIn("B0FFI00EHQ", "海澜之家(龙华人民路店)", "人民路4244号(花园大厦首层右侧)", 22659186, 114018372),
            new CommunityAdminService.EstateIn("B0IBFR19OB", "景华新村东区", "山咀头村一区五巷与三联四路交叉口西南180米", 22654965, 114024485),
            new CommunityAdminService.EstateIn("B02F37WBRB", "美丽·AAA花园", "龙华街道人民路(龙华地铁站C口步行360米)", 22653583, 114020568),
            new CommunityAdminService.EstateIn("B0L30RLRTT", "龙华荔园新村西区", "景龙中环路与公园路交叉口南100米", 22655555, 114017575),
            new CommunityAdminService.EstateIn("B0LR9BORGS", "智慧城", "东环一路与三联一路交叉口南120米", 22660775, 114022935),
            new CommunityAdminService.EstateIn("B0L31SVAM5", "伦敦堡(龙华店)", "三联四路与山咀头村一区五巷交叉口西北60米", 22656275, 114025625),
            new CommunityAdminService.EstateIn("B0MU57TGL4", "于意花园", "公园路与景龙中环路交叉口西南20米", 22656286, 114017143),
            new CommunityAdminService.EstateIn("B0FFHUHDRF", "屿里", "龙华利金购物广场B1栋02a", 22653055, 114020532),
            new CommunityAdminService.EstateIn("B0LRJ4GF9I", "景华1号岗亭", "景南街与龙华和平路交叉口西北80米", 22653661, 114023902),
            new CommunityAdminService.EstateIn("B0FFLEWBQ4", "龙华市监局宿舍楼", "龙华街道龙园社区沿河路7号", 22658590, 114017196),
            new CommunityAdminService.EstateIn("B0FFG7MTZE", "花园新村", "龙园花园路与联康三路交叉口东40米", 22660798, 114019317),
            new CommunityAdminService.EstateIn("B0FFHQ7DSU", "花园新村南区", "振翔路与龙园花园路交叉口南20米", 22660423, 114018608),
            new CommunityAdminService.EstateIn("B0FFFVIEUH", "三联山咀头小区二区", "荔园新村", 22656312, 114025889),
            new CommunityAdminService.EstateIn("B0FFF39WRP", "煜丰泽花园广场", "龙观路415号", 22661348, 114021058),
            new CommunityAdminService.EstateIn("B02F38JL1W", "双桥花园", "龙华弓村东环一路", 22658730, 114025699),
            new CommunityAdminService.EstateIn("B0MA4CUU8G", "龙股宿舍楼", "龙华和平路与龙华人民路交叉口西120米", 22652875, 114022475),
            new CommunityAdminService.EstateIn("B0FFF6VEQW", "弘诚阁", "龙发路227(龙华地铁站C口步行410米)", 22653221, 114018976),
            new CommunityAdminService.EstateIn("B0IBFRZSHC", "景华新村南区", "龙华和平路与建通路交叉口西120米", 22654409, 114025282),
            new CommunityAdminService.EstateIn("B0L09OE4OS", "创富港龙华利金城", "景龙中环路与宝华路交叉口东南180米", 22652634, 114020574),
            new CommunityAdminService.EstateIn("B0LB4CKT06", "龙华股份有限公司商住楼", "龙华和平路与龙华人民路交叉口西100米", 22652709, 114022666),
            new CommunityAdminService.EstateIn("B0MRHRER67", "中信上品2栋", "花园大道与联康一路交叉口西20米", 22660270, 114017850),
            new CommunityAdminService.EstateIn("B0LBLCOWWK", "原地税宿舍", "龙华人民路与龙华和平路交叉口西60米", 22652725, 114023025),
            new CommunityAdminService.EstateIn("B0LDCHTKWG", "蓝莓座", "龙华和平路与龙华人民路交叉口西180米", 22652503, 114022012));

    @BeforeAll
    void seed() {
        cleanup();
        admin.importEstates(REGION, "OPEN", false, SAMPLE, "T");
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM cmt_community WHERE region_code = ?", REGION);
    }

    @Test
    @DisplayName("★★★ 站在每个小区的坐标上，匹配到的就是它自己 —— 一公里内 179 个小区的那一片")
    void standingAtAnEstateMatchesThatEstate() {
        int hit = 0;
        StringBuilder miss = new StringBuilder();
        for (var e : SAMPLE) {
            String no = jdbc.queryForObject(
                    "SELECT community_no FROM cmt_community WHERE origin_code = ?",
                    String.class, e.originCode());
            var ctx = communityService.resolve(e.latE6(), e.lngE6(), false);
            if (no.equals(ctx.innermostNo())) {
                hit++;
            } else {
                miss.append("; ").append(e.name()).append(" -> ").append(ctx.innermostName());
            }
        }
        /*
         * **要求全中，不留额度。**
         *
         * 一开始写的是「≥ 37/40」，理由是「样本里可能有坐标相同的两条，谁赢看扫表顺序」。
         * 去数了一遍：**这 40 条里坐标相同的有 0 对**，最近的一对相距 26 米
         * （景龙新村 ↔ 景龙新邨西区）。实测也是 40/40。
         * 既然没有天然不可判的情形，那 3 条额度就是纯粹的松弛 ——
         * 它不会保护任何东西，只会在某天真的退化成 38/40 时一声不吭。
         *
         * 跌下去的症状是：买家站在自家楼下却被归到隔壁小区，商品池跟着换成别人那套。
         */
        assertThat(hit).as("命中 " + hit + "/" + SAMPLE.size() + "，没命中的：" + miss)
                .isEqualTo(SAMPLE.size());
    }

    @Test
    @DisplayName("★★★ 围栏 300 米买到的是这个：离最近小区 350 米的人**不算住在里面**")
    void fenceKeepsFarBuyersOut() {
        /*
         * ⚠️ **这条判据换过两次，前两版都被实测推翻了。记在这儿免得再走一遍。**
         *
         * 第一版理由：「1000 米会让一个坐标落进十几个围栏、选错」。
         *   → 消融验伪：围栏改回 1000 米，上面那条仍然 40/40。
         *     「同档之间比距离」早就把最近的挑出来了，围栏多大都不影响**选谁**。
         *
         * 第二版：随手往北挪 500 米，断言落不进任何围栏。
         *   → 也错：那一片一公里内有 179 个小区，**挪到哪儿都在某个围栏里**。
         *
         * 第三版（这一版）：先量出「到最近小区多远」的分布，再挑一个真落在
         * 两个取值之间的点。龙华全区随机站位实测：到最近小区中位数 666 米，
         * **22% 落在 300~1000 米这一带** —— 那 22% 就是两个取值的全部差别：
         *   · 1000 米：判成「你住在 XX 小区」（其实在 350 米外）
         *   · 300 米：走「最近的取货点 · 约 350 米」
         * 货是同一批（绑的是同一个聚落），差别只在**那句话是不是真的**。
         */
        var ctx = communityService.resolve(22661860, 114026157, false);
        assertThat(ctx.innermostNo())
                .as("350 米外被判成「住在这个小区里」—— 顶栏会显示一个他并不在的小区名")
                .isNull();
        assertThat(ctx.nearestDistanceM())
                .as("那就该走「最近的聚落」那一级，并把距离说出来").isBetween(300, 900);
    }

    @Test
    @DisplayName("★★★ 对照量：这一片确实密到会重叠 —— 否则上一条用例什么也没证明")
    void theSampleIsActuallyDense() {
        var first = SAMPLE.get(0);
        long within300 = SAMPLE.stream()
                .filter(e -> ai.neargo.shop.common.Geo.meters(
                        e.latE6(), e.lngE6(), first.latE6(), first.lngE6()) <= 300)
                .count();
        assertThat(within300)
                .as("300 米内只有它自己的话，围栏根本不重叠，上一条用例测的是稀疏场景")
                .isGreaterThan(1L);
    }
}
