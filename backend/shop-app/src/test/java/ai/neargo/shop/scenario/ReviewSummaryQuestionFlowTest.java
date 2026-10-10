package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 口碑那一批（§3.3）：评分概览、评价筛选与分页、商品问答。
 *
 * <p>三件事此前的样子：
 * <ul>
 *   <li>评价列表返回**全部** VISIBLE 行，没有上限也没有筛选；</li>
 *   <li>没有任何地方给得出平均分与星级分布 —— 详情页想显示「4.6 分」只能自己遍历；</li>
 *   <li>问答的表、运营端的回答界面、审核流程都齐了，<b>而买家没有入口把问题提进来</b>，
 *       于是那一屏永远是空的。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewSummaryQuestionFlowTest {

    /** 独占号段（撞号会把别人的单卷进来） */
    private static final String PHONE = "13000340001";
    private static final String GOODS = "G0002";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ 评分概览：平均分、星级分布、有图条数都对得上；分布之和 = 总数")
    void summaryAddsUp() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100000);
        seedReview(suffix + "a", 5, "[\"a.jpg\"]");
        seedReview(suffix + "b", 5, "[]");
        seedReview(suffix + "c", 2, "[]");

        JsonNode s = goodsDetail().get("reviewSummary");
        assertThat(s).as("详情里没有评分概览 —— 首屏那一行「4.6 分」只能靠端上自己遍历").isNotNull();

        int total = s.get("total").asInt();
        int sum = 0;
        for (JsonNode d : s.get("dist")) {
            sum += d.asInt();
        }
        assertThat(sum).as("星级分布之和与总数对不上 —— 那张分布图是错的").isEqualTo(total);
        assertThat(total).isGreaterThanOrEqualTo(3);
        assertThat(s.get("withImages").asInt()).isGreaterThanOrEqualTo(1);
        // 三条里有一条 2 星，平均分必定低于 5：这条挡住「平均分恒等于 5」那种假实现
        assertThat(s.get("avg").asDouble()).isBetween(1.0, 4.9);
    }

    @Test
    @DisplayName("★★★ 筛选与分页真的收窄了结果 —— 此前它返回全部行且没有上限")
    void filterAndPagingNarrow() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100000);
        seedReview(suffix + "g1", 5, "[]");
        seedReview(suffix + "g2", 5, "[\"b.jpg\"]");
        seedReview(suffix + "b1", 1, "[]");

        int all = reviews("", 1, 50).size();
        assertThat(all).isGreaterThanOrEqualTo(3);

        for (JsonNode r : reviews("BAD", 1, 50)) {
            assertThat(r.get("rating").asInt()).as("差评筛出了高分").isLessThanOrEqualTo(2);
        }
        for (JsonNode r : reviews("IMAGE", 1, 50)) {
            assertThat(r.get("images")).as("有图筛出了没图的").isNotEmpty();
        }

        // 分页：一页 1 条，第二页与第一页不是同一条
        var p1 = reviews("", 1, 1);
        var p2 = reviews("", 2, 1);
        assertThat(p1).hasSize(1);
        assertThat(p2).hasSize(1);
        assertThat(p1.get(0).get("reviewNo").asString())
                .as("翻页翻回了同一条").isNotEqualTo(p2.get(0).get("reviewNo").asString());
    }

    @Test
    @DisplayName("★★ 不认识的筛选词按全部处理 —— 筛选是便利，不该因为传错一个词把整页打空")
    void unknownFilterFallsBackToAll() throws Exception {
        seedReview(String.valueOf(System.nanoTime() % 100000) + "u", 4, "[]");
        assertThat(reviews("NOPE", 1, 50)).isNotEmpty();
    }

    @Test
    @DisplayName("★★★ 买家能提问、问答落到商品上；没回答的不给别人看")
    void askThenAnswered() throws Exception {
        String token = login();
        JsonNode r = json.readTree(mvc().perform(post("/mp/question")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + GOODS + "\",\"content\":\"这个甜吗\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        String questionNo = r.get("data").get("questionNo").asString();
        assertThat(r.get("data").get("goodsNo").asString())
                .as("没落到商品上 —— 只按 sku 存的话，同一件货的问答会按规格散开").isEqualTo(GOODS);

        // 还没回答：商品页不该显示它（一排没人答的问题比没有问答区更糟）
        assertThat(questionNos()).doesNotContain(questionNo);

        // 运营回答之后才出现
        jdbc.update("UPDATE cnt_question SET status = 'ANSWERED', answer = '很甜', "
                + "answered_by = 'OP', answered_at = ? WHERE question_no = ?",
                System.currentTimeMillis(), questionNo);
        assertThat(questionNos()).as("答完了还不给看，那这一屏永远是空的").contains(questionNo);
    }

    @Test
    @DisplayName("★★ 匿名不能提问 —— 运营回答时要能回到问的那个人")
    void anonymousCannotAsk() throws Exception {
        int status = mvc().perform(post("/mp/question")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + GOODS + "\",\"content\":\"探针\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 直接落一条可见评价：走接口要先有一单已完成的订单，而这里要测的是读那一侧。
     *
     * <p>状态写 {@code PASSED} —— 「可见」在库里就是这个词
     * （{@code ReviewServiceImpl.VISIBLE = "PASSED"}）。写成 VISIBLE 不会报错，
     * 只是查不出来：列表恒为空，而三条断言会一起说「数量不够」，指不到真因。
     */
    private void seedReview(String suffix, int rating, String imagesJson) {
        jdbc.update("INSERT INTO rvw_review (review_no, sub_order_no, order_no, goods_no, entity_no, "
                        + "user_no, nickname, rating, content, images, status, tenant_no, "
                        + "created_at, updated_at, deleted) "
                        + "VALUES (?, ?, ?, ?, 'M0001', 'U-RS', '测试', ?, '还行', ?, 'PASSED', 'MAIN', "
                        + "NOW(), NOW(), 0)",
                "RV-RS-" + suffix, "SUB-RS-" + suffix, "SO-RS-" + suffix, GOODS, rating, imagesJson);
    }

    private java.util.List<JsonNode> reviews(String filter, int page, int size) throws Exception {
        String body = mvc().perform(get("/mp/review")
                        .param("goodsNo", GOODS).param("filter", filter)
                        .param("page", String.valueOf(page)).param("size", String.valueOf(size)))
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<JsonNode>();
        json.readTree(body).get("data").forEach(out::add);
        return out;
    }

    private java.util.List<String> questionNos() throws Exception {
        String body = mvc().perform(get("/mp/goods/" + GOODS + "/question").param("limit", "50"))
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<String>();
        json.readTree(body).get("data").forEach(n -> out.add(n.get("questionNo").asString()));
        return out;
    }

    private JsonNode goodsDetail() throws Exception {
        String body = mvc().perform(get("/mp/goods/" + GOODS))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private String login() throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, PHONE);
    }
}
