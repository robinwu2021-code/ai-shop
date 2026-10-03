package ai.neargo.shop.scenario;

import ai.neargo.shop.platform.PlatformConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 运营端的开关**到得了买家侧**（TDD-C 端裂变与商家招募 §4.1）。
 *
 * <p><b>此前到不了</b>：`/mp/config/bootstrap` 只发 yml 里那几个开关，而运营端那一屏改的是
 * 平台开关 —— 两边互不相干。于是「运营后台改一下」对 C 端的任何行为都不成立，
 * 要改只能改 yml 重启；而端上那几个判断干脆是编译期的，连重启都救不了，得重新发版。
 *
 * <p>这条链现在承载着一件要紧的事：`merchant.apply.mp-visible` 决定小程序上显不显示
 * 商家入驻入口 —— 那是一条<b>可能让整包被驳回</b>的入口（微信按类目审，自营类目的包里
 * 出现招商/入驻可能被判平台型经营）。做成开关就是为了真被驳回时能立刻关掉止血，
 * 不用重新发版、不用重新提审。**所以这条链断了比开关本身更要命。**
 */
@SpringBootTest
@ActiveProfiles("test")
class BootstrapFlagsFlowTest {

    private static final String FLAG = "merchant.apply.mp-visible";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private PlatformConfigService platformConfig;

    /** 开关是**共享配置**：改了不还原，后面的用例读到的是我改过的值 */
    private Boolean original;

    @AfterEach
    void restore() {
        if (original != null) {
            platformConfig.saveFeatureFlag(FLAG, original, 0, "TEST");
            original = null;
        }
    }

    @Test
    @DisplayName("★★★ 入驻开关默认开着 —— 拍板要小程序上能注册商家")
    void flagOnByDefault() throws Exception {
        JsonNode features = bootstrap().get("features");
        assertThat(features.has(FLAG))
                .as("运营端的开关根本没进 bootstrap —— 那「后台关一下就止血」是句空话")
                .isTrue();
        assertThat(features.get(FLAG).asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★★ 运营关掉它，买家侧**下一次冷启动就拿到 false** —— 这是唯一的止血手段")
    void turningItOffReachesBuyers() throws Exception {
        original = bootstrap().get("features").get(FLAG).asBoolean();

        platformConfig.saveFeatureFlag(FLAG, false, 0, "TEST");
        assertThat(bootstrap().get("features").get(FLAG).asBoolean())
                .as("关了却还发 true —— 被驳回时只能靠重新发版，那要几天")
                .isFalse();

        // 再开回来：止血之后要能恢复，单向的开关等于一次性的
        platformConfig.saveFeatureFlag(FLAG, true, 0, "TEST");
        assertThat(bootstrap().get("features").get(FLAG).asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★ yml 里的开关仍然在 —— 合流不是替换")
    void ymlFlagsStillThere() throws Exception {
        assertThat(bootstrap().get("features").has("points"))
                .as("合流把 yml 那几个挤掉了").isTrue();
    }

    @Test
    @DisplayName("★★ 游客可取 —— 冷启动时还没有登录态")
    void anonymousCanRead() throws Exception {
        int status = mvc().perform(get("/mp/config/bootstrap")).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode bootstrap() throws Exception {
        String body = mvc().perform(get("/mp/config/bootstrap"))
                .andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        assertThat(r.get("code").asInt()).as(body).isZero();
        return r.get("data");
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }
}
