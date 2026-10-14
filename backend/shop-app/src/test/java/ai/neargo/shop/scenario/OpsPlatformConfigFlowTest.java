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
 * 平台四类配置（P-17.1）。
 *
 * <p>守的是两件事：
 * <ul>
 *   <li><b>后端不能比 mock 宽</b> —— mock 上点不通的路径，指向真后端也该点不通。
 *       今晚一半缺陷源于两边各写一套，症状统一是「接口 200、页面空白」</li>
 *   <li><b>规则文案改一次要留下上一版</b> —— 用户同意的是某一版协议，
 *       覆盖了「他当时同意的是什么」就永远查不回来</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsPlatformConfigFlowTest {

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

    // ---------------------------------------------------------------- 皮肤

    @Test
    @DisplayName("★★ 非法皮肤被拒 —— 下发一个 C 端没有的皮肤，用户那边只会回落，而页面显示已下发")
    void illegalSkinRejected() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/appearance", admin, "{\"defaultSkin\":\"business\"}", 10400);
        call("/ops/appearance", admin, "{\"defaultSkin\":\"not-a-skin\"}", 10400);
        call("/ops/appearance", admin, "{\"defaultSkin\":\"fresh\"}", 0);
    }

    @Test
    @DisplayName("★★ 节日皮肤区间倒挂被拒 —— 否则它永远不生效，而页面看着是配好的")
    void festivalRangeMustBeForward() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/appearance", admin,
                "{\"defaultSkin\":\"fresh\",\"festivalSkin\":\"promo\","
                        + "\"festivalFrom\":\"2026-02-10\",\"festivalTo\":\"2026-02-01\"}", 10400);
    }

    // ---------------------------------------------------------------- 汇率

    @Test
    @DisplayName("★★★ 基准货币汇率不可改 —— 改了整套价格换算的原点就没了")
    void baseCurrencyRateIsLocked() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/markets/CN", admin, "{\"rate\":1.2,\"enabled\":true}", 10400);
        call("/ops/markets/CN", admin, "{\"rate\":1.0,\"enabled\":true}", 0);
        call("/ops/markets/CN", admin, "{\"rate\":0,\"enabled\":true}", 10400);
    }

    // ---------------------------------------------------------------- 规则文案

    @Test
    @DisplayName("★★★ 改文案会留下上一版 —— 纠纷时要能回答「他当时同意的是什么」")
    void ruleTextsKeepHistory() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/rule-texts", admin,
                "{\"refund\":\"第一版退款\",\"pickup\":\"自提\",\"weighDiff\":\"称重\"}", 0);
        call("/ops/rule-texts", admin,
                "{\"refund\":\"第二版退款\",\"pickup\":\"自提\",\"weighDiff\":\"称重\"}", 0);

        mvc().perform(get("/ops/rule-texts").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.data.refund").value("第二版退款"))
                .andExpect(jsonPath("$.data.version").value(2));
        mvc().perform(get("/ops/rule-texts/history").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.data[0].refund").value("第一版退款"))
                .andExpect(jsonPath("$.data[0].version").value(1));
    }

    @Test
    @DisplayName("三条文案都不能为空 —— C 端要展示给用户看")
    void ruleTextsCannotBeBlank() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/rule-texts", admin, "{\"refund\":\"\",\"pickup\":\"a\",\"weighDiff\":\"b\"}", 10400);
    }

    // ---------------------------------------------------------------- 开关

    @Test
    @DisplayName("灰度比例只收 0–100，且保存后读得回来")
    void featureFlagRollout() throws Exception {
        String admin = opsLogin("admin", "admin123");
        call("/ops/feature-flags/points", admin, "{\"enabled\":true,\"rolloutPercent\":101}", 10400);
        call("/ops/feature-flags/points", admin, "{\"enabled\":true,\"rolloutPercent\":30}", 0);
        /*
         * **按 key 找，不按下标取。** 从前这里写的是 `$.data[0]` —— 那断的是
         * 「points 排在开关列表第一条」，而这条用例要验的是「保存后读得回来」。
         * 两件事在只有一个开关时长得一模一样，多一个开关就分道扬镳：
         * CategoryTreeFlowTest 会往同一张表里落一行 `category.gate.enforce`，
         * 字典序排在 points 前面，于是这条用例在全量跑时红、单独跑时绿，
         * 而报错说的是「expected points but was category.gate.enforce」——
         * 听起来像开关存错了，其实开关一点没错。
         */
        String flags = mvc().perform(get("/ops/feature-flags").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        JsonNode points = null;
        for (JsonNode f : json.readTree(flags).get("data")) {
            if ("points".equals(f.get("key").asString())) {
                points = f;
            }
        }
        assertThat(points).as("保存过的开关必须出现在列表里").isNotNull();
        assertThat(points.get("rolloutPercent").asInt()).isEqualTo(30);
    }

    // ---------------------------------------------------------------- 权限

    @Test
    @DisplayName("★★ 没有 platform:config 改不动 —— 汇率不该和行业启停共用一把钥匙")
    void requiresPlatformConfig() throws Exception {
        // goods 有 industry:manage 之外的一堆码，但没有 platform:config
        String goods = opsLogin("goods", "goods123");
        call("/ops/markets/CN", goods, "{\"rate\":1.0,\"enabled\":true}", 10403);
        call("/ops/appearance", goods, "{\"defaultSkin\":\"fresh\"}", 10403);
    }

    // ---------------------------------------------------------------- 助手

    private void call(String path, String token, String body, int expectCode) throws Exception {
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
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
