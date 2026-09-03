package ai.neargo.shop.scenario;

import ai.neargo.shop.spi.platform.BannedWordPort;
import ai.neargo.shop.support.TestLogin;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台禁售词（商品①）。
 *
 * <p>三条断言各守一件事：
 *
 * <ol>
 *   <li><b>大小写不敏感。</b>配了 `iphone` 拦不住 `iPhone` 的话，
 *       这道闸对任何认真想绕过它的人都不存在 —— 而拦不住的那次<b>不会留下痕迹</b>。</li>
 *   <li><b>加完当场生效。</b>缓存不失效的话，运营加完词、自己去试还是拦得住旧的、
 *       拦不住新的，会以为没保存上，然后再加一遍。</li>
 *   <li><b>报错点名那个词。</b>「你的标题里有违禁词」对商家没有用 ——
 *       事后驳回之所以低效，一半原因就是理由是人手写的，读完还是不知道改哪儿。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsBannedWordFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private BannedWordPort port;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 加完当场生效、且大小写不敏感 —— 配了 iphone 拦不住 iPhone 等于没这道闸")
    void addTakesEffectAtOnceAndIsCaseInsensitive() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        String probe = "zzprobeword" + (System.nanoTime() % 100000);

        assertThat(port.firstHit("这是一个 " + probe + " 的标题"))
                .as("加之前就已经命中 —— 那后面那条断言证明不了是这次加的词起的作用")
                .isEmpty();

        String body = mvc().perform(post("/ops/banned-word")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"word\":\"" + probe.toUpperCase() + "\",\"reason\":\"探针\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        Long id = idOf(json.readTree(body).get("data"), probe);

        try {
            // **当场**：不等 60 秒缓存过期
            assertThat(port.firstHit("这是一个 " + probe + " 的标题"))
                    .as("加完没当场生效 —— 运营会以为没保存上，然后再加一遍")
                    .isPresent();
            // 大小写：存的是小写，查的是原文，两边都要转
            assertThat(port.firstHit("THIS IS " + probe.toUpperCase()))
                    .as("配了小写拦不住大写 —— 这道闸对任何想绕过它的人都不存在")
                    .isPresent();
            assertThat(port.firstHit("这是一个 " + probe + " 的标题").orElseThrow().reason())
                    .as("不给理由的话，商家收到的还是一句「有违禁词」")
                    .isEqualTo("探针");
        } finally {
            mvc().perform(post("/ops/banned-word/" + id + "/remove")
                    .header("Authorization", "Bearer " + token));
        }
        // 删完也要当场失效，否则「删了还拦着」同样说不清
        assertThat(port.firstHit("这是一个 " + probe + " 的标题"))
                .as("删完还拦着 —— 缓存没失效")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 空词与超长词都拒 —— 一整句话配进去永远不会命中，等于一条假规则")
    void rejectsEmptyAndOverlongWords() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        for (String bad : new String[]{"", "   ", "x".repeat(65)}) {
            mvc().perform(post("/ops/banned-word")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"word\":\"" + bad + "\"}"))
                    .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        }
    }

    private static Long idOf(JsonNode rows, String word) {
        for (JsonNode r : rows) {
            if (word.equalsIgnoreCase(r.get("word").asString())) {
                return r.get("id").asLong();
            }
        }
        throw new AssertionError("加进去的词没出现在返回的列表里：" + word);
    }
}
