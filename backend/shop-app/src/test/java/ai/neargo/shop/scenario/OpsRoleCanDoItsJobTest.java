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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 每个运营角色能不能做完自己的本职工作 —— **一条此前完全没有的检查**。
 *
 * <p>现有测试查的是「越权会不会被拒」，方向只有一个。反方向
 * 「本职工作会不会被自己的权限卡住」从来没测过，于是出了这个：
 *
 * <p>BD 的本职是入驻审核。审核通过前**必须先选覆盖社区**（否则商家上架后
 * 对谁都不可见），而 `/ops/communities` 当时挂在 `INDUSTRY_MANAGE` 下 ——
 * 那个权限的注释明确写着「不给 BD」。结果：
 * **BD 打开审核抽屉，「覆盖小区」下一个选项都没有，而不选又通不过。**
 * 页面上还看不出是权限问题（列表就是空的，没有任何提示）。
 *
 * <p>根因是读写权限没分开。这个文件守的就是「分开之后别再合回去」。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsRoleCanDoItsJobTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ BD 能读社区列表 —— 不能读就审核不了商家，那是他的本职工作")
    void bdCanReadCommunitiesBecauseAuditNeedsThem() throws Exception {
        mvc().perform(get("/ops/communities?page=1&size=50")
                        .header("Authorization", "Bearer " + login("bd", "bd123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("BD 仍然不能**改**社区 —— 读写分开，不是把写权限也一起给了")
    void bdStillCannotWriteCommunities() throws Exception {
        // 用真实存在的写端点：开关城。它决定一整片区域能不能获客，正是不该给 BD 的那类
        mvc().perform(post("/ops/communities/C0001/open")
                        .header("Authorization", "Bearer " + login("bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opened\":false}"))
                .andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    @DisplayName("商品运营能读社区 —— 他要按社区看商品池")
    void goodsOpsCanReadCommunities() throws Exception {
        mvc().perform(get("/ops/communities?page=1&size=50")
                        .header("Authorization", "Bearer " + login("goods", "goods123")))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String login(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
