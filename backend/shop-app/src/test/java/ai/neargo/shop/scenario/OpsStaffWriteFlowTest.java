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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 运营员工的三条写接口。
 *
 * <p>这三个控件在 ops-web 上一直是死的（登记在 `KNOWN_GAPS`）。
 * 补端点容易，难的是三条闸 —— 它们防的都是<b>「操作成功了，但后果不是你以为的那个」</b>：
 *
 * <ul>
 *   <li>停用了，而那个人还在里面点 —— 会话没踢，token 到期前照常用</li>
 *   <li>角色改成了一个后端没配过的码 —— 他能登录，导航一片空白，看不出原因</li>
 *   <li>给超管配了数据域 —— 配置页显示「已限定」，实际全量</li>
 * </ul>
 *
 * <p>三条都是「界面正常、语义错」的形状，靠人点是点不出来的。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsStaffWriteFlowTest {

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

    // ---------------------------------------------------------------- 启停

    @Test
    @DisplayName("★★★ 停用之后，那个人手里的 token 立刻失效 —— 不踢会话等于没停用")
    void disableKicksLiveSession() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String victim = opsLogin("support", "support123");
        // 停之前：能用
        mvc().perform(get("/ops/order").header("Authorization", "Bearer " + victim))
                .andExpect(jsonPath("$.code").value(0));

        String staffNo = staffNoOf(admin, "support");
        mvc().perform(post("/ops/staffs/" + staffNo + "/enabled")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        // 停之后：手里那张 token 立刻不认。只改库里的状态的话，
        // 他在 token 过期前照常操作，而按下停用的人以为生效了。
        //
        // 断的是 HTTP 401 而不是契约包里的 10401：会话被销毁后，
        // 请求在**过滤器层**就被拒了，根本走不到 GlobalExceptionHandler
        mvc().perform(get("/ops/order").header("Authorization", "Bearer " + victim))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isUnauthorized());

        // 也登不回来
        mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"support\",\"password\":\"support123\"}"))
                .andExpect(jsonPath("$.code").value(10401));

        /*
         * **还原**。整个测试套跑在同一个 JVM 与同一份 H2 上，种子账号是共享的 ——
         * 停用了不还原，后面所有 opsLogin("support") 的用例全部 10401，
         * 而它们与本用例毫无关系。这类「污染共享状态」的失败最费时间：
         * 报错指向的是别人的测试。
         */
        mvc().perform(post("/ops/staffs/" + staffNo + "/enabled")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★★ 不能停用自己 —— 把自己锁在门外之后只能去库里手改")
    void cannotDisableSelf() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String self = staffNoOf(admin, "admin");
        mvc().perform(post("/ops/staffs/" + self + "/enabled")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.code").value(10420));
    }

    // ---------------------------------------------------------------- 改角色

    @Test
    @DisplayName("★★★ 改成后端没配过的角色码被拒 —— 否则造出一个能登录但导航全空的账号")
    void unknownRoleIsRejected() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String staffNo = staffNoOf(admin, "goods");
        mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"NOT_A_ROLE\"}"))
                .andExpect(jsonPath("$.code").value(10421));
    }

    @Test
    @DisplayName("改成今晚新补的七个角色之一是可以的（它们已有权限配置）")
    void newlyConfiguredRoleIsAccepted() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String staffNo = staffNoOf(admin, "goods");
        mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"AUDITOR\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roles[0]").value("AUDITOR"))
                // perms 跟着换：改角色不换权限的话，这个操作只是改了个标签
                .andExpect(jsonPath("$.data.perms").isNotEmpty());

        // 还原成 GOODS_OPS —— 不还原的话，后面用 goods 账号审商品/类目的用例
        // 全都会 10403，而它们与本用例无关
        mvc().perform(post("/ops/staffs/" + staffNo + "/role")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"GOODS_OPS\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★★ 不能改自己的角色 —— 超管把自己降成客服就回不去了")
    void cannotChangeOwnRole() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String self = staffNoOf(admin, "admin");
        mvc().perform(post("/ops/staffs/" + self + "/role")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SUPPORT\"}"))
                .andExpect(jsonPath("$.code").value(10420));
    }

    // ---------------------------------------------------------------- 数据域

    @Test
    @DisplayName("★★ 给全量角色配数据域被拒 —— 存下来会显示「已限定」而实际全量")
    void scopeOnFullAccessRoleIsRejected() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String self = staffNoOf(admin, "admin");
        mvc().perform(post("/ops/staffs/" + self + "/scope")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantNo\":\"M0001\"}"))
                .andExpect(jsonPath("$.code").value(10422));
    }

    @Test
    @DisplayName("受限角色能配数据域，且空字符串归一成「不限定」")
    void scopeOnLimitedRole() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String staffNo = staffNoOf(admin, "bd");

        mvc().perform(post("/ops/staffs/" + staffNo + "/scope")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantNo\":\"M0001\",\"communityNo\":\"\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.merchantNo").value("M0001"))
                // 空字符串与 null 在库里必须长一样，否则「不限定」有两种写法，
                // 而按它裁剪的那一天，其中一种会被当成「限定到空字符串」
                .andExpect(jsonPath("$.data.communityNo").doesNotExist());

        // 清空（也是还原：BD 账号后面还有别的用例要用）
        mvc().perform(post("/ops/staffs/" + staffNo + "/scope")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.merchantNo").doesNotExist());

        /*
         * **回库里读一遍。** 上面那条断言看的是这次请求的返回体，而返回体是拿
         * 内存里那个已经置空的对象拼的 —— 清空到底有没有落库，它一个字也证明不了。
         *
         * 这不是假想：MyBatis-Plus 的 updateById 默认跳过 null 字段，于是
         * 「清空数据域」曾经生成一条不含这三列的 UPDATE，库里纹丝不动，
         * 而运营看到的是「已恢复不限定」。上面那条断言全程是绿的。
         *
         * 代价是后面所有用 bd 读带数据域接口的用例都会拿到 10404「数据不存在」——
         * 一个与数据域毫无字面关系的报错，而且只在本类先跑时才出现。
         * CategoryTreeFlowTest 那三条就这么红了很久，看起来像门面审核坏了。
         */
        String reread = mvc().perform(get("/ops/staffs?page=1&size=200")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        JsonNode bd = null;
        for (JsonNode r : json.readTree(reread).get("data").get("records")) {
            if (staffNo.equals(r.get("staffNo").asString())) {
                bd = r;
            }
        }
        org.assertj.core.api.Assertions.assertThat(bd).isNotNull();
        org.assertj.core.api.Assertions.assertThat(bd.get("merchantNo").isNull())
                .as("清空数据域没落库：库里还留着 M0001，而返回体说已清空")
                .isTrue();
    }

    // ---------------------------------------------------------------- 权限

    @Test
    @DisplayName("没有 staff:manage 的角色三条都调不动")
    void requiresStaffManage() throws Exception {
        String admin = opsLogin("admin", "admin123");
        String staffNo = staffNoOf(admin, "bd");
        String support = opsLogin("support", "support123");

        for (String path : new String[]{"/enabled", "/role", "/scope"}) {
            mvc().perform(post("/ops/staffs/" + staffNo + path)
                            .header("Authorization", "Bearer " + support)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(jsonPath("$.code").value(10403));
        }
    }

    // ---------------------------------------------------------------- 助手

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String staffNoOf(String adminToken, String username) throws Exception {
        String body = mvc().perform(get("/ops/staffs").header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode s : json.readTree(body).get("data").get("records")) {
            if (username.equals(s.get("username").asString())) {
                return s.get("staffNo").asString();
            }
        }
        throw new IllegalStateException("种子里没有 " + username);
    }
}
