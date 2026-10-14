package ai.neargo.shop.scenario;

import ai.neargo.shop.marketing.slot.entity.MktContentSlot;
import ai.neargo.shop.marketing.slot.mapper.SlotMappers.ContentSlotMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内容位 → 首页推荐位。
 *
 * <p><b>这条链路此前是断的</b>：C 端首页那一屏按销量倒序兜底（页面上写的是「推荐」），
 * 运营端那一页在 mock 上点得动、后端一行都没有。所以这里每条用例都盯着
 * <b>「配了之后 C 端到底变没变」</b>，而不是「位子存下来了没有」——
 * 存下来但没人读，正是这块之前的样子。
 *
 * <p>内容位是**全平台共享**的状态：留一行没删，别的用例请求首页时拿到的就是我的配置。
 * 所以每个用例跑完都清干净（{@link #cleanup()}）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ContentSlotFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ContentSlotMapper slotMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 建过的位子一律删掉 —— 见类注释。 */
    @AfterEach
    void cleanup() {
        slotMapper.delete(Wrappers.<MktContentSlot>lambdaQuery().likeRight(MktContentSlot::getTitle, "★测试"));
    }

    @Test
    @DisplayName("★★★ 配了内容位，首页就按运营给的顺序出货；没配仍按销量兜底（不能空）")
    void curatedSlotDecidesHomeOrder() throws Exception {
        String ops = opsLogin();

        // 对照量本身要非零：兜底如果本来就是空的，下面「变了」证明不了任何事
        List<String> fallback = promoted("C0001");
        assertThat(fallback).as("★ 兜底就是空的 —— 那这条用例什么也没测到").isNotEmpty();

        // 刻意选一个与销量序不同的顺序：位子若没生效，结果会与 fallback 一样
        List<String> curated = List.of("G0004", "G0002");
        createSlot(ops, "★测试 首页楼层", curated, List.of());

        assertThat(promoted("C0001"))
                .as("★ 配了内容位，首页还是按销量出货 —— 那这块配置就是个摆设")
                .containsExactlyElementsOf(curated);
    }

    /**
     * <b>关掉要即刻生效。</b>出了问题（推错货、价格标错）运营要的是「现在就下」，
     * 而不是去改一个下线时间再等它到点。
     */
    @Test
    @DisplayName("★★ 关掉内容位，首页立刻回到兜底 —— 不等下线时间")
    void disabledSlotFallsBackImmediately() throws Exception {
        String ops = opsLogin();
        List<String> fallback = promoted("C0001");
        String slotNo = createSlot(ops, "★测试 待关闭", List.of("G0004", "G0002"), List.of());
        assertThat(promoted("C0001")).isNotEqualTo(fallback);

        mvc().perform(post("/ops/content-slots/" + slotNo + "/enabled")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(promoted("C0001")).as("★ 关了还在展示").containsExactlyElementsOf(fallback);
    }

    /**
     * <b>投放社区不命中就当没配。</b>不然运营给「翡翠城」配的楼层会出现在全平台的首页上，
     * 而他自己看到的（在自己那个社区）是对的 —— 这类错没人会发现。
     */
    @Test
    @DisplayName("★★ 位子限定了社区：别的社区看不到它，走自己的兜底")
    void communityScopedSlotMissesOtherCommunities() throws Exception {
        String ops = opsLogin();
        List<String> fallbackC1 = promoted("C0001");
        createSlot(ops, "★测试 只投翡翠城", List.of("G0004", "G0002"), List.of("C0002"));

        assertThat(promoted("C0002")).as("投的那个社区没生效").containsExactly("G0004", "G0002");
        assertThat(promoted("C0001")).as("★ 只投一个社区的位子出现在了别的社区").containsExactlyElementsOf(fallbackC1);
    }

    /**
     * <b>三条硬校验。</b>都不能只做端上提示 —— 端上拦住的只是老实人，
     * 而这三种坏配置在首页上的表现都是「一块空白」，没有任何报错。
     */
    @Test
    @DisplayName("★★ 空楼层 / 打错的货号 / 下线不晚于上线，都存不进去")
    void badSlotsAreRejected() throws Exception {
        String ops = opsLogin();
        long before = slotMapper.selectCount(Wrappers.emptyWrapper());

        saveRaw(ops, body("★测试 空楼层", List.of(), List.of(), "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        saveRaw(ops, body("★测试 错货号", List.of("G0004", "G9999"), List.of(), "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        saveRaw(ops, body("★测试 排期反了", List.of("G0004"), List.of(), "2026-10-01T00:00:00Z", "2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));

        assertThat(slotMapper.selectCount(Wrappers.emptyWrapper()))
                .as("★ 被拒的配置却落库了 —— 首页上那一格会静默变空")
                .isEqualTo(before);
    }

    /**
     * <b>货下架了就跳过它</b>，而不是在首页上留一个点不开的坑。
     * 运营配位子那天它还在售，下架是商家自己的动作，谁也不会回头去改内容位。
     */
    @Test
    @DisplayName("★★ 配了的货全下架：首页回兜底，不留空")
    void offSaleGoodsFallBackInsteadOfBlank() throws Exception {
        String ops = opsLogin();
        List<String> fallback = promoted("C0001");
        // G9998 不存在，所以走 saveSlot 的校验是过不了的 —— 这里直接种一条脏配置，
        // 模拟「配的时候在售、后来下架/删除」这个真实过程
        MktContentSlot dirty = new MktContentSlot();
        dirty.setSlotNo("SL-TEST-STALE");
        dirty.setTitle("★测试 全下架");
        dirty.setKind(MktContentSlot.HOME_FLOOR);
        dirty.setSortNo(0);
        dirty.setGoodsNos("[\"G9998\"]");
        dirty.setOnlineAt(System.currentTimeMillis() - 86_400_000L);
        dirty.setOfflineAt(System.currentTimeMillis() + 86_400_000L);
        dirty.setEnabled(true);
        slotMapper.insert(dirty);

        assertThat(promoted("C0001"))
                .as("★ 一条过期配置就把首页第一屏清空了")
                .containsExactlyElementsOf(fallback);
    }

    /**
     * <b>「清空」也要真的发生。</b>MyBatis-Plus 的 updateById 跳过 null 字段 ——
     * 把投放社区删干净后如果还写 null，那句 set 压根不生成：
     * 运营看到「保存成功」，而库里还是原来那几个社区，首页照旧只在那边出现。
     */
    @Test
    @DisplayName("★★ 把投放社区删干净：改完就是全部社区，而不是「看着改了其实没改」")
    void clearingCommunityScopeActuallyClears() throws Exception {
        String ops = opsLogin();
        List<String> fallback = promoted("C0001");
        String slotNo = createSlot(ops, "★测试 先只投一个社区", List.of("G0004", "G0002"), List.of("C0002"));
        assertThat(promoted("C0001")).as("前提不成立：限定社区时它就不该出现在 C0001").containsExactlyElementsOf(fallback);

        // 同一个位子改成不限社区
        saveRaw(ops, json.writeValueAsString(java.util.Map.of(
                        "slotNo", slotNo, "title", "★测试 先只投一个社区", "kind", "HOME_FLOOR", "sort", 0,
                        "goodsNos", List.of("G0004", "G0002"), "communityNos", List.<String>of(),
                        "onlineAt", "2026-01-01T00:00:00Z", "offlineAt", "2030-01-01T00:00:00Z", "enabled", true)))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(promoted("C0001"))
                .as("★ 社区限定没被清掉 —— 运营看到的是「已保存」，而首页没变")
                .containsExactly("G0004", "G0002");
    }

    // ---------------------------------------------------------------- helpers

    /** C 端首页推荐位现在出的货号，按展示顺序。 */
    private List<String> promoted(String communityNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/promoted").param("communityNo", communityNo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> out = new ArrayList<>();
        for (var g : json.readTree(body).get("data")) {
            out.add(g.get("goodsNo").asString());
        }
        return out;
    }

    private String createSlot(String ops, String title, List<String> goodsNos, List<String> communityNos)
            throws Exception {
        String body = saveRaw(ops, body(title, goodsNos, communityNos,
                        "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("slotNo").asString();
    }

    private org.springframework.test.web.servlet.ResultActions saveRaw(String ops, String body) throws Exception {
        return mvc().perform(post("/ops/content-slots")
                .header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String body(String title, List<String> goodsNos, List<String> communityNos,
                        String onlineAt, String offlineAt) {
        return json.writeValueAsString(java.util.Map.of(
                "title", title, "kind", "HOME_FLOOR", "sort", 0,
                "goodsNos", goodsNos, "communityNos", communityNos,
                "onlineAt", onlineAt, "offlineAt", offlineAt, "enabled", true));
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
