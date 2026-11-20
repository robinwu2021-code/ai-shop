package ai.neargo.shop.scenario;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * B 端提前判权开到「拦截」档，把 {@link BizRoleForbiddenFlowTest} 的全部用例原样再跑一遍
 * （TDD-网关层与统一权限-开发计划 D3）。
 *
 * <p>那个类用真的受限账号验证「该挡的挡住、该放的放行、被挡时是 70006」。提前判权承诺的是不改变这些结论，
 * 只是把拒绝提前到网关 —— 所以最直接的证据就是同一批断言在这一档下照样成立。
 */
@SpringBootTest(properties = "shop.gateway.biz.precheck=enforce")
@ActiveProfiles("test")
@DisplayName("网关 · B 端提前判权（拦截）：店员越权的全部用例结论不变")
class BizRoleForbiddenUnderPrecheckTest extends BizRoleForbiddenFlowTest {

    @Test
    @DisplayName("★★★ 没权限且请求体不合法：得到 70006 而不是参数错误 —— 说明拦在读请求体之前")
    void deniedBeforeTheBodyIsRead() throws Exception {
        Staff clerk = staffWithRoles("18600259001", "提前判权店", "18600259002", "CLERK");
        forbidden(clerk, post("/biz/goods/save").contentType(MediaType.APPLICATION_JSON).content("{ 这不是合法的 JSON"));
    }
}
