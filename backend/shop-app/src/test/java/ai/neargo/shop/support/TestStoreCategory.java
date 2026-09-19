package ai.neargo.shop.support;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 给门店开一个经营类目 —— 建商品前的那一步（TDD-门店经营类目 规则 1）。
 *
 * <p><b>为什么要有它</b>：商品的类目不在当前门店的经营类目里，保存与上架都会被拒
 * （{@code GOODS_CATEGORY_NOT_IN_STORE}）。改版前是「不在就自动加进去」，
 * 于是 26 个场景类的建品夹具从来不需要这一步 —— 规则一收紧，它们在建品那一行一起断了。
 * 断的原因不是规则错了，是夹具没照着真实流程走：店主要先加经营类目，再建商品。
 *
 * <p>与 {@link TestLogin} 同一个取舍：静态方法而不是基类，各测试类的
 * {@code @SpringBootTest} 配置保持各自的样子。
 *
 * <p><b>幂等</b>：已经开着就什么都不做；只追加，不改已有那几项的显示名与顺序。
 */
public final class TestStoreCategory {

    private TestStoreCategory() {
    }

    /** 给令牌的<b>默认店</b>开一个经营类目 —— 不带 {@code X-Store-No} 的建品请求落在默认店上 */
    public static void open(MockMvc mvc, ObjectMapper json, String token, String categoryNo) throws Exception {
        open(mvc, json, token, defaultStore(mvc, json, token), categoryNo);
    }

    /** 给指定门店开一个经营类目 */
    public static void open(MockMvc mvc, ObjectMapper json, String token, String storeNo, String categoryNo)
            throws Exception {
        JsonNode current = json.readTree(mvc.perform(get("/biz/store/" + storeNo + "/categories")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data");
        StringBuilder items = new StringBuilder();
        for (JsonNode c : current) {
            if (categoryNo.equals(c.get("categoryNo").asString())) {
                return;
            }
            items.append("{\"categoryNo\":\"").append(c.get("categoryNo").asString()).append('"');
            JsonNode name = c.get("displayName");
            if (name != null && !name.isNull()) {
                items.append(",\"displayName\":").append(json.writeValueAsString(name.asString()));
            }
            items.append("},");
        }
        items.append("{\"categoryNo\":\"").append(categoryNo).append("\"}");
        String body = mvc.perform(post("/biz/store/" + storeNo + "/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + items + "]}"))
                .andReturn().getResponse().getContentAsString();
        int code = json.readTree(body).get("code").asInt();
        if (code != 0) {
            throw new IllegalStateException("开经营类目 " + categoryNo + " 失败：" + body);
        }
    }

    private static String defaultStore(MockMvc mvc, ObjectMapper json, String token) throws Exception {
        JsonNode stores = json.readTree(mvc.perform(get("/biz/store/list")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data");
        for (JsonNode s : stores) {
            if (s.path("isDefault").asBoolean(false)) {
                return s.get("storeNo").asString();
            }
        }
        if (stores == null || stores.isEmpty()) {
            throw new IllegalStateException("这个令牌名下没有门店");
        }
        return stores.get(0).get("storeNo").asString();
    }
}
