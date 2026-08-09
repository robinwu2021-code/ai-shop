package ai.neargo.shop.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 部署隔离的守卫（模块优化实施步骤 S8 / diagrams/deploy-topology.svg）。
 *
 * <p><b>这条测试断言的是 404，不是 401——差别是全部意义所在。</b>
 * 401 表示「路由在，只是你没登录」：接口仍然暴露在网络上，
 * 剩下的全部安全性都压在鉴权这一层不出错上。
 * 404 表示「这台机器上根本没有这个接口」，攻击面是零。
 *
 * <p>只在 ops 部署上验证，因为方向是不对称的：ops 跑在内网、权限最高
 * （改费率、批提现、封商家），它绝不能捎带上 C 端与 B 端的面。
 */
@SpringBootTest
@ActiveProfiles("ops-only")
@DisplayName("部署隔离：ops 部署不含 C/B 端路由")
class DeploymentProfileTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        // 不套安全过滤器：要验的是「路由存不存在」，套上过滤器就分不清
        // 404 是路由没有还是被过滤器提前挡了
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("/mp/** 与 /biz/** 在 ops 部署上不存在（404，不是 401）")
    void consumerAndMerchantRoutesAbsent() throws Exception {
        for (String path : new String[]{"/mp/user/profile", "/mp/catalog/goods", "/biz/order"}) {
            int status = mvc().perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(status)
                    .as("%s 在 ops 部署上应当不存在。401 说明路由还在，只是没登录——"
                            + "那意味着接口仍暴露在网络上", path)
                    .isEqualTo(404);
        }
    }

    @Test
    @DisplayName("/ops/** 的路由在（登录入口可达）")
    void operatorRoutesPresent() throws Exception {
        int status = mvc().perform(get("/ops/staff")).andReturn().getResponse().getStatus();
        assertThat(status).as("ops 部署必须有 /ops 路由，否则这个部署没有意义").isNotEqualTo(404);
    }
}
