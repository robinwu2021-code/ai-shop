package ai.neargo.shop.scenario;

import ai.neargo.shop.content.entity.CntPost;
import ai.neargo.shop.content.entity.CntQuestion;
import ai.neargo.shop.content.mapper.ContentMappers.PostMapper;
import ai.neargo.shop.content.mapper.ContentMappers.QuestionMapper;
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

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 内容与素材（P-15.1 / P-15.2）。
 *
 * <p>这个域的业务规则原本只写在 ops-web 的契约注释里，后端零端点。
 * 这里守的就是那些注释 —— 它们不是前端的实现细节，每一条都带着理由：
 *
 * <ul>
 *   <li>「批量 + 风险内容 = 事故」→ 命中风险词的不进批量，且**整批拒绝不是跳过**</li>
 *   <li>「内容已经露出过，退回待审等于假装没发生过」→ PASSED 只能去 OFFLINE</li>
 *   <li>「让改动这件事本身留下痕迹」→ 已回答的不能再答</li>
 *   <li>「下架商品进了榜，用户点进去是空页」→ 人工榜的商品必须在售</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsContentFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private QuestionMapper questionMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 审核

    @Test
    @DisplayName("★★★ 命中风险词的内容不进批量，且**整批拒绝** —— 静默跳过会让人以为全过了")
    void riskyPostBlocksWholeBatch() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String clean = seedPost("干净内容", "[]");
        String risky = seedPost("风险内容", "[\"加微信\"]");

        call("/ops/contents/posts/batch-pass", admin,
                "{\"postNos\":[\"" + clean + "\",\"" + risky + "\"]}", 10431);

        // 整批拒绝 = 干净的那条也**没有**被通过。只跳过风险条的话，
        // 调用方拿到「成功」，而风险内容还躺在待审里没人再看
        mvc().perform(get("/ops/contents/posts").param("status", "PENDING")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.data.records[?(@.postNo=='" + clean + "')]").exists());

        // 只传干净的就能过
        call("/ops/contents/posts/batch-pass", admin, "{\"postNos\":[\"" + clean + "\"]}", 0);
    }

    @Test
    @DisplayName("★★★ PASSED 只能去 OFFLINE —— 退回待审等于假装内容没露出过")
    void passedCannotGoBackToPending() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String postNo = seedPost("流转测试", "[]");

        call("/ops/contents/posts/" + postNo + "/decide", admin, "{\"to\":\"PASSED\"}", 0);
        // 退回待审：拒绝
        call("/ops/contents/posts/" + postNo + "/decide", admin, "{\"to\":\"PENDING\"}", 10432);
        // 下架不写原因：拒绝（原因原样回作者）
        call("/ops/contents/posts/" + postNo + "/decide", admin, "{\"to\":\"OFFLINE\"}", 10430);
        call("/ops/contents/posts/" + postNo + "/decide", admin,
                "{\"to\":\"OFFLINE\",\"remark\":\"含导流信息\"}", 0);
        // 下架之后是终态
        call("/ops/contents/posts/" + postNo + "/decide", admin,
                "{\"to\":\"PASSED\",\"remark\":\"恢复\"}", 10432);
    }

    @Test
    @DisplayName("驳回必须写原因")
    void rejectNeedsReason() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String postNo = seedPost("驳回测试", "[]");
        call("/ops/contents/posts/" + postNo + "/decide", admin, "{\"to\":\"REJECTED\"}", 10430);
        call("/ops/contents/posts/" + postNo + "/decide", admin,
                "{\"to\":\"REJECTED\",\"remark\":\"图片模糊\"}", 0);
    }

    // ---------------------------------------------------------------- 问答

    @Test
    @DisplayName("★★ 已回答的不能再答 —— 要改先隐藏，让改动本身留下痕迹")
    void answeredCannotBeAnsweredAgain() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String qNo = seedQuestion("这个几斤装？");

        call("/ops/contents/questions/" + qNo + "/answer", admin, "{\"answer\":\"10 斤\"}", 0);
        call("/ops/contents/questions/" + qNo + "/answer", admin, "{\"answer\":\"5 斤\"}", 10433);

        // 隐藏后可以重新回答 —— 而隐藏这一步留下了原因
        call("/ops/contents/questions/" + qNo + "/hide", admin, "{\"reason\":\"答错了要重答\"}", 0);
        call("/ops/contents/questions/" + qNo + "/answer", admin, "{\"answer\":\"5 斤\"}", 0);
    }

    @Test
    @DisplayName("隐藏必须写原因")
    void hideNeedsReason() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String qNo = seedQuestion("加个微信？");
        call("/ops/contents/questions/" + qNo + "/hide", admin, "{\"reason\":\"\"}", 10430);
    }

    // ---------------------------------------------------------------- 榜单

    @Test
    @DisplayName("★★★ 人工榜里不在售的商品被拒 —— 下架商品进了榜，用户点进去是空页")
    void manualRankingRejectsOfflineSku() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/contents/rankings", admin,
                "{\"name\":\"热销\",\"kind\":\"MANUAL\",\"size\":5,"
                        + "\"manualSkus\":[\"SK-NOT-EXIST\"],\"enabled\":true}", 10435);
        // 在售的可以
        call("/ops/contents/rankings", admin,
                "{\"name\":\"热销\",\"kind\":\"MANUAL\",\"size\":5,"
                        + "\"manualSkus\":[\"SK0001\"],\"enabled\":true}", 0);
    }

    @Test
    @DisplayName("★★ 非人工榜带条目直接拒 —— 传了就是调用方理解错了，静默忽略会让他一直以为配上了")
    void nonManualRankingCannotPinSkus() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/contents/rankings", admin,
                "{\"name\":\"销量榜\",\"kind\":\"SALES\",\"size\":5,"
                        + "\"manualSkus\":[\"SK0001\"],\"enabled\":true}", 10436);
        call("/ops/contents/rankings", admin,
                "{\"name\":\"销量榜\",\"kind\":\"SALES\",\"size\":5,\"enabled\":true}", 0);
    }

    @Test
    @DisplayName("人工榜条目数不能超过容量")
    void manualRankingRespectsSize() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/contents/rankings", admin,
                "{\"name\":\"小榜\",\"kind\":\"MANUAL\",\"size\":1,"
                        + "\"manualSkus\":[\"SK0001\",\"SK0004\"],\"enabled\":true}", 10434);
    }

    // ---------------------------------------------------------------- 素材

    @Test
    @DisplayName("★★ 限定投放却没有投放对象被拒 —— 保存成功却谁都看不到")
    void scopedMaterialNeedsRefs() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/materials", admin,
                "{\"title\":\"春节海报\",\"kind\":\"POSTER\",\"scope\":\"COMMUNITY\","
                        + "\"scopeRefs\":[]}", 10437);
        call("/ops/materials", admin,
                "{\"title\":\"春节海报\",\"kind\":\"POSTER\",\"scope\":\"COMMUNITY\","
                        + "\"scopeRefs\":[\"CM001\"]}", 0);
        // 全量投放不需要对象
        call("/ops/materials", admin,
                "{\"title\":\"通用文案\",\"kind\":\"COPY\",\"scope\":\"ALL\"}", 0);
    }

    // ---------------------------------------------------------------- 权限与形状

    @Test
    @DisplayName("★★ 客服没有 content:govern —— 他不该能改首页榜单")
    void supportCannotGovernContent() throws Exception {
        String support = opsLogin("support", "support123");
        mvc().perform(get("/ops/contents/posts").header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.code").value(10403));
        call("/ops/contents/rankings", support,
                "{\"name\":\"x\",\"kind\":\"SALES\",\"size\":5}", 10403);
    }

    @Test
    @DisplayName("★★ 三个列表是分页包、榜单是裸数组 —— 形状要逐条对，不能统一包一层")
    void listShapesMatchContract() throws Exception {
        String admin = opsLogin("admin", "admin123");
        for (String url : java.util.List.of("/ops/contents/posts", "/ops/contents/questions",
                "/ops/materials")) {
            mvc().perform(get(url).header("Authorization", "Bearer " + admin))
                    .andExpect(jsonPath("$.data.records").exists())
                    .andExpect(jsonPath("$.data.total").exists());
        }
        mvc().perform(get("/ops/contents/rankings").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ---------------------------------------------------------------- 助手

    private String seedPost(String title, String riskHitsJson) {
        CntPost p = new CntPost();
        p.setPostNo("PT-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        p.setAuthorType(CntPost.USER);
        p.setAuthorName("邻居张三");
        p.setTitle(title);
        p.setContent(title);
        p.setCommunityNo("CM001");
        p.setRiskHits(riskHitsJson);
        p.setStatus(CntPost.PENDING);
        p.setLikeCount(0);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        postMapper.insert(p);
        return p.getPostNo();
    }

    private String seedQuestion(String content) {
        CntQuestion q = new CntQuestion();
        q.setQuestionNo("QA-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        q.setSkuNo("SK0001");
        q.setSkuTitle("五常大米 10斤装");
        q.setContent(content);
        q.setAskedBy("U-DEMO-1");
        q.setStatus(CntQuestion.PENDING);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());
        questionMapper.insert(q);
        return q.getQuestionNo();
    }

    private void call(String path, String token, String body, int expectCode) throws Exception {
        mvc().perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(expectCode));
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
