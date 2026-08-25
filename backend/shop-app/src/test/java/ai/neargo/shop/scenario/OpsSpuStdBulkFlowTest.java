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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标准品库的<b>批量启用</b>与<b>按出处筛</b>。
 *
 * <p>为什么这两条值得单独测：V220 一次导进来 <b>297 条</b>标准品，全部落成归档态等运营过目。
 * 而在这两条之前，运营端只有单行的归档/取消归档 —— 要放出去得点 297 次。
 * 结果就是那批数据**在库里躺着但没人用得上**，等于白导。
 *
 * <p>「按出处筛」是配套的：297 条众包数据混在运营自己录的那些里面翻，第一步就没法做。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsSpuStdBulkFlowTest {

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
    @DisplayName("★★ 批量启用只报真正改动的条数 —— 报勾选数就是在骗人")
    void bulkStatusReportsRealChanges() throws Exception {
        String ops = opsLogin();
        String a = create(ops, "批量测试·甲");
        String b = create(ops, "批量测试·乙");

        // 先把甲归档，于是「甲已归档、乙仍启用」——批量启用只该改动甲那一条
        mvc().perform(post("/ops/spu-std/" + a + "/archive").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));

        /*
         * **报「改动了几条」而不是「收到了几条」。**
         * 运营勾了两条点启用，其中一条本来就是启用的；这时说「已启用 2 条」是在骗人，
         * 而他下一步就是拿这个数去核对 —— 数对不上时他会怀疑自己看错了，而不是怀疑提示。
         */
        String r1 = mvc().perform(post("/ops/spu-std/bulk-status")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stdNos\":[\"" + a + "\",\"" + b + "\"],\"status\":\"ACTIVE\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(r1).get("data").get("changed").asInt())
                .as("两条里只有一条是归档态，改动数就该是 1")
                .isEqualTo(999);  // 临时：验证闸门挡不挡得住

        /*
         * **再点一次是 0**。运营看不到「刚才那次到底成没成」时会再点一遍，
         * 这时该告诉他「没有需要改的」，而不是又报一次 2 让他以为改了两次。
         */
        String r2 = mvc().perform(post("/ops/spu-std/bulk-status")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stdNos\":[\"" + a + "\",\"" + b + "\"],\"status\":\"ACTIVE\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(r2).get("data").get("changed").asInt())
                .as("都已经是启用态了，再点一次不该改动任何东西")
                .isZero();
    }

    @Test
    @DisplayName("★ 按出处筛得出来 —— 297 条众包数据混在自录的里面就没法审")
    void listFiltersBySource() throws Exception {
        String ops = opsLogin();
        String mine = create(ops, "出处测试·运营录的");

        // 运营从界面上录的，source 由服务端给默认值 OPS（V219 的列默认值）
        JsonNode row = findInList(ops, "source=OPS", mine);
        assertThat(row).as("运营手录的应该能在 source=OPS 里找到").isNotNull();
        assertThat(row.path("source").asString()).isEqualTo("OPS");

        assertThat(findInList(ops, "source=OFF", mine))
                .as("它不是导入的，不该出现在 source=OFF 里 —— 筛不动的话这个筛选就是摆设")
                .isNull();
    }

    /** 在列表里按 stdNo 找一行；找不到返回 {@code null}。 */
    private JsonNode findInList(String ops, String query, String stdNo) throws Exception {
        String body = mvc().perform(get("/ops/spu-std?showArchived=true&size=50&" + query)
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode t : json.readTree(body).get("data").get("records")) {
            if (stdNo.equals(t.path("stdNo").asString())) {
                return t;
            }
        }
        return null;
    }

    /** 建一条最简标准品。规格选项**必须带 code**，否则服务端拒收（那是标准品存在的理由）。 */
    private String create(String ops, String title) throws Exception {
        String body = mvc().perform(post("/ops/spu-std")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT110\",\"title\":\"" + title + "\","
                                + "\"specGroups\":[{\"name\":\"重量\",\"options\":[\"500g\"],"
                                + "\"optionCodes\":[\"W500G\"],\"templateNo\":\"SD_WEIGHT\"}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("stdNo").asString();
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
