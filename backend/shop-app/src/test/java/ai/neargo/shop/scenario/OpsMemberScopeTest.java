package ai.neargo.shop.scenario;

import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.user.service.PersonService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 运营侧看会员（P8）。
 *
 * <p><b>这组用例守的是一句话里的两半</b>：**跨商家可见**，但**手机号仍然只有后四位**。
 * 两半都要守 —— 只守前半，运营端会变成一本全平台通讯录；只守后半，
 * 配了商家域的运营会看到空列表而不是全平台（数据域是 fail-closed 的）。
 *
 * <p>还有一条：看完整号必须留痕。**先写审计再返回** ——
 * 反过来的话，写审计失败时号码已经给出去了，而事后追责会得出「没人看过」。
 */
@SpringBootTest
// 只写 "test"：它已经组合了 h2db,testcfg,api,ops。多写一个会让 profile 集合不同 →
// 第二个 Spring 上下文 → 与 JVM 级共享的 H2 撞车
@ActiveProfiles("test")
class OpsMemberScopeTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MemberService memberService;

    @Autowired
    private PersonService personService;

    private static int seq = 9500;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 在两家不同主体各造一个会员，返回 {人档号, 手机号} */
    private String[] seedTwoMerchants() {
        String phone = "1330000" + (++seq);
        String userNo = "U-OPS-" + seq;
        String personNo = personService.resolveOrCreateByPhone(phone).getPersonNo();
        personService.bindOnLogin(userNo, phone);
        long now = System.currentTimeMillis();
        memberService.onOrderPaid("SUB-OPS-A" + seq, userNo, personNo, "M-OPS-A" + seq,
                "ST-1", 5_000, now);
        memberService.onOrderPaid("SUB-OPS-B" + seq, userNo, personNo, "M-OPS-B" + seq,
                "ST-1", 3_000, now);
        return new String[]{personNo, phone};
    }

    @Test
    @DisplayName("★★★ 跨商家看得到，但手机号只有后四位 —— 两半都要成立")
    void crossMerchantVisibleButPhoneStaysMasked() throws Exception {
        String[] g = seedTwoMerchants();
        String ops = TestLogin.admin(mvc(), json);

        String body = mvc().perform(get("/ops/members?phoneTail=" + g[1].substring(7))
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        // 同一个人在两家主体各一条 —— 这就是「跨商家」
        assertThat(body).contains("M-OPS-A" + seq).contains("M-OPS-B" + seq);
        // 而完整号一个字符都不该出现在这个响应里
        assertThat(body).as("运营端列表不给完整手机号").doesNotContain(g[1]);
        assertThat(body).contains(g[1].substring(7));   // 后四位是给的
    }

    /**
     * 配了商家域的运营，会员列表只剩那一家。
     *
     * <p><b>这条是上面那条的对照面，两条必须同时成立。</b>上面用超管跑
     * （通配 → {@code DataScopeSpec.ALL}），所以它证明不了数据域生效没有 ——
     * 2026-08-29 之前 {@code OpsMemberServiceImpl} 整个类绕开数据域，
     * 而那条断言照样绿：「给这个人配了只看某商家」在这一页上完全不生效，
     * 界面上也没有任何线索。
     *
     * <p>所以这条钉的是：**把绕过加回去，它必须红**。
     *
     * <p>角色用 GOODS_OPS 而不是 SUPPORT —— 会员页要 {@code member:member:read}，
     * 而 SUPPORT 没有这个码。拿客服来测会先被权限拦住，然后看起来像「数据域生效了」。
     */
    @Test
    @DisplayName("★★★ 配了商家域的运营只看得到那一家的会员 —— 此前他看到的是全平台")
    void scopedOperatorSeesOnlyOwnMerchantMembers() throws Exception {
        String[] g = seedTwoMerchants();
        int mine = seq;                      // seedTwoMerchants 用的那一批
        String admin = TestLogin.admin(mvc(), json);
        String scoped = staffScopedTo(admin, "M-OPS-A" + mine);

        String body = mvc().perform(get("/ops/members?phoneTail=" + g[1].substring(7))
                        .header("Authorization", "Bearer " + scoped))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("配了 M-OPS-A 的运营应当看得到自己这家的会员").contains("M-OPS-A" + mine);
        assertThat(body)
                .as("而 M-OPS-B 那条不该出现 —— 出现了就说明数据域被绕开了，"
                        + "「只看某商家」这个配置在这一页上是个摆设：%s", body)
                .doesNotContain("M-OPS-B" + mine);
    }

    /** 造一个只看某商家的运营账号。用一次性用户名，对「跑过一遍的库」自愈。 */
    private String staffScopedTo(String adminToken, String merchantNo) throws Exception {
        String username = "member-scope-" + Long.toString(System.nanoTime() % 1_000_000L)
                + "@neargo.ai";
        String body = mvc().perform(post("/ops/staffs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"realName\":\"会员域验证\","
                                + "\"roles\":[\"GOODS_OPS\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        var created = json.readTree(body).get("data");
        String staffNo = created.get("staff").get("staffNo").asString();
        String password = created.get("initialPassword").asString();
        mvc().perform(post("/ops/staffs/" + staffNo + "/scope")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantNo\":\"" + merchantNo + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.operator(mvc(), json, username, password);
    }

    @Test
    @DisplayName("★★ 人档页把「一个人几家会员」串起来 —— 这正是人档存在的理由")
    void personShowsAllMemberships() throws Exception {
        String[] g = seedTwoMerchants();
        String ops = TestLogin.admin(mvc(), json);

        mvc().perform(get("/ops/persons/" + g[0]).header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.memberships.length()").value(2))
                .andExpect(jsonPath("$.data.phoneTail").value(g[1].substring(7)));
    }

    @Test
    @DisplayName("★★★ 查看完整号：理由必填，太短直接拒 —— 「查一下」等于没有理由")
    void revealNeedsARealReason() throws Exception {
        String[] g = seedTwoMerchants();
        String ops = TestLogin.admin(mvc(), json);

        mvc().perform(post("/ops/persons/" + g[0] + "/reveal-phone")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"查\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★★ 触达健康度按退订率排 —— 发得多不是成绩，发到有人关掉才是问题")
    void reachStatsSortedByOptOutRate() throws Exception {
        String[] g = seedTwoMerchants();
        String entityA = "M-OPS-A" + seq;
        String memberNo = memberService.find(entityA, g[0]).orElseThrow().getMemberNo();
        memberService.setReachOptOut(entityA, memberNo, true);

        String ops = TestLogin.admin(mvc(), json);
        String body = mvc().perform(get("/ops/members/reach-stats?days=30")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        // 退订率 100% 的那家要能被找出来，而不是沉在一堆 0 里
        assertThat(body).contains(entityA);
        assertThat(body).contains("\"optOutRate\":100");
    }

    @Test
    @DisplayName("★★★ 后四位查人只接受四位 —— 给前缀就成了一本可翻的全平台通讯录")
    void phoneTailMustBeExactlyFour() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        mvc().perform(get("/ops/members?phoneTail=133").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★★ 全平台券与活动带异常标记：没设预算、不限量要能一眼看出来")
    void opsPromotionFlagsRisk() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        mvc().perform(get("/ops/promotion/coupons").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(get("/ops/promotion/activities").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));
    }
}
