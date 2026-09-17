package ai.neargo.shop.scenario;

import ai.neargo.shop.community.service.CommunityAdminService;
import ai.neargo.shop.community.service.CommunityService;
import ai.neargo.shop.config.MapCommunityOpenRunner;
import ai.neargo.shop.product.service.MerchantGoodsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一次性开城 + 重建商品池。
 *
 * <p>这一组守的是「**开城之后买家真的匹配得到**」与「**顺序不能反**」——
 * 后者失手不报错，症状是买家匹配到自家小区、然后看到一屏空货架，
 * 与「开城没生效」长得一模一样。
 */
@SpringBootTest
@ActiveProfiles("test")
class MapCommunityOpenRunnerTest {

    private static final String REGION = "998804";
    private static final String POI = "OPENRUNNER01";
    private static final int LAT = 22610000;
    private static final int LNG = 114010000;

    @Autowired
    private CommunityAdminService admin;
    @Autowired
    private CommunityService communityService;
    @Autowired
    private MerchantGoodsService goods;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM cmt_community WHERE origin_code = ?", POI);
    }

    private String seedClosed() {
        admin.importEstates(REGION, "CLOSED", false, List.of(
                new CommunityAdminService.EstateIn(POI, "开城跑批小区", "某路 1 号", LAT, LNG)), "T");
        return jdbc.queryForObject(
                "SELECT community_no FROM cmt_community WHERE origin_code = ?", String.class, POI);
    }

    @Test
    @DisplayName("★★★ 开城之后买家才匹配得到 —— 开城前匹配不到，这是对照量")
    void opensAndBecomesMatchable() {
        String no = seedClosed();
        assertThat(communityService.resolve(LAT, LNG, false).innermostNo())
                .as("还关着就匹配得到 = 导入这一步没法安全地先行").isNotEqualTo(no);

        new MapCommunityOpenRunner(admin, goods, REGION).run(null);

        assertThat(communityService.resolve(LAT, LNG, false).innermostNo())
                .as("开完城还匹配不到，那这一跑是白跑的").isEqualTo(no);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cmt_community WHERE origin_code = ?", String.class, POI))
                .isEqualTo("OPEN");
    }

    @Test
    @DisplayName("★★★ 空前缀**拒绝**，而且不许把失败咽掉当成「开了 0 个」")
    void emptyPrefixOpensNothing() {
        String no = seedClosed();
        new MapCommunityOpenRunner(admin, goods, "  ").run(null);
        /*
         * 空前缀在 likeRight 下匹配一切 —— 放行就是把全国地图聚落一次开城。
         * service 那层会抛，这里要确认 runner 没把它咽掉之后还照样改了东西。
         */
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cmt_community WHERE origin_code = ?", String.class, POI))
                .as("空前缀居然开了城").isEqualTo("CLOSED");
        assertThat(communityService.resolve(LAT, LNG, false).innermostNo()).isNotEqualTo(no);
    }

    @Test
    @DisplayName("★★ 重跑不出错：已经开着的再跑一次是 0 个，且不把它关回去")
    void rerunIsHarmless() {
        String no = seedClosed();
        new MapCommunityOpenRunner(admin, goods, REGION).run(null);
        new MapCommunityOpenRunner(admin, goods, REGION).run(null);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cmt_community WHERE origin_code = ?", String.class, POI))
                .as("第二次把开着的关回去了 = 一次没人察觉的批量关城").isEqualTo("OPEN");
        assertThat(communityService.resolve(LAT, LNG, false).innermostNo()).isEqualTo(no);
    }
}
