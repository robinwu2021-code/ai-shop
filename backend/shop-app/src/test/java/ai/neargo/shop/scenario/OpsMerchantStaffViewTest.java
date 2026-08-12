package ai.neargo.shop.scenario;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 运营端看商家的人员与授权（只读）。
 *
 * <p>这一页存在的理由是客服电话：「我们店的配送员看不到订单」——
 * 在此之前运营只能让老板自己截图，而问题往往正是
 * <b>「他以为授了、其实没授」</b>，截图里看不出这一点。
 *
 * <p>所以这里钉住的两条是<b>能看到什么</b>与<b>不能做什么</b>：
 * 看得到姓名与完整的登录手机号（那是员工的登录用户名，客服要核对的正是它），
 * 但<b>没有任何写端点</b> —— 谁能进这家店是商家的雇佣关系。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsMerchantStaffViewTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 运营看得到商家的人、门店角色，以及他用哪个号登录")
    void opsSeesStaffAndTheirLoginPhone() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String merchantNo = anyMerchantNo(admin);

        String body = mvc().perform(get("/ops/merchants/" + merchantNo + "/staff")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        // 至少有老板一个人 —— 一家通过审核的商家不可能一个员工都没有
        assertThat(json.readTree(body).get("data").size()).isGreaterThan(0);
        /*
         * **手机号不脱敏**（2026-08-12 拍板）：它就是员工的登录用户名，
         * 客服要回答的第一个问题往往正是「他到底是用哪个号登进去的」——
         * 一个 `138****8000` 回答不了它。
         */
        assertThat(body).as("要给完整号，它是登录用户名").contains("\"loginPhone\":\"1");
    }

    @Test
    @DisplayName("★★★ 平台不能改商家的授权 —— 这一段刻意只有读，没有写")
    void noWriteEndpointForMerchantStaff() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String merchantNo = anyMerchantNo(admin);

        /*
         * 谁能进这家店是商家的雇佣关系；平台替商家改授权，
         * 等于平台替商家决定谁能动他的钱。要处置该商家走封禁 ——
         * 那是另一个层级的动作，有单独的权限码与审计。
         *
         * 这条断言防的是<b>以后有人顺手加一个写端点</b>：加了它就变红。
         */
        mvc().perform(post("/ops/merchants/" + merchantNo + "/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mchAccountNo\":\"X\",\"role\":\"MANAGER\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("这条路径上不该有写端点")
                        .isIn(404, 405));
    }

    @Test
    @DisplayName("★★ 没有 merchant:merchant:read 的角色看不到 —— 员工名单本身是商家的信息")
    void needsMerchantReadPerm() throws Exception {
        String merchantNo = anyMerchantNo(opsLogin("admin", "admin123"));
        // finance 只管钱，不该顺带拿到全平台商家的员工名单
        String finance = opsLogin("finance", "finance123");

        mvc().perform(get("/ops/merchants/" + merchantNo + "/staff")
                        .header("Authorization", "Bearer " + finance))
                .andExpect(jsonPath("$.code").value(10403));
    }

    private String anyMerchantNo(String adminToken) throws Exception {
        String body = mvc().perform(get("/ops/merchants").header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").get(0).get("merchantNo").asString();
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
