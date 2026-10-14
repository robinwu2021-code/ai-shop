package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * <b>登录之后不该比游客看得少。</b>
 *
 * <p>这条闸门守的是一整族缺陷，它们的共同点是**登录才犯、游客正常、零报错**：
 *
 * <p>C 端会话的数据域是 {@code DataScopeSpec.of(SELF, {userNo})}（写死在 LoginUser 里），
 * 而 23 张登记过数据域的表里有 18 张**只有 MERCHANT 锚点、没有 SELF**
 * （prd_goods、prd_sku、mch_entity、mch_store、prd_store_price、pmt_coupon…）。
 * 锚点找不到时拦截器是 <b>fail-closed</b>：拼出来的是 {@code 1=0}，不是放行。
 *
 * <p>于是任何一个 C 端读，只要没显式 {@code DataScopeContext.executeWithoutScope}，
 * 对**登录用户**就是空的 —— 而游客身上根本没有数据域，一切正常。
 * 2026-08-29 实测到的第一批：登录后店铺页 10404、评分 10404、
 * 「我买过的商家」永远是空列表（a8d3802c）。
 *
 * <p><b>为什么现有用例没发现</b>：C 端这些用例发的都是游客请求（不带 Authorization），
 * 数据域这一层从头到尾没被触发过。测的是没有会话的那一半，而线上常态是登录着。
 *
 * <p><b>判据是症状，不是实现</b>：不去数哪个 mapper 少包了 executeWithoutScope
 * —— 那要顺着控制器逐条追可达性，而且漏一条就等于没查。这里直接比对
 * 「同一个接口，游客拿到东西 / 登录拿不到」，缺陷长什么样就断言什么样。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConsumerScopeParityTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /**
     * 游客能看的 C 端读。种子号写死（G0001/M0001/ST-M0001…）——
     * 用「随便取第一条」的话，那一条恰好为空时这条用例会自己变成永远通过。
     */
    private static final List<String> PUBLIC_READS = List.of(
            "/mp/goods",
            "/mp/goods/promoted",
            "/mp/goods/G0001",
            "/mp/goods/G0001/sku-price",
            "/mp/merchant",
            "/mp/merchant/promoted",
            "/mp/merchant/M0001",
            "/mp/merchant/M0001/score",
            "/mp/store/M0001",
            "/mp/store/M0001/frequent",
            "/mp/topics",
            "/mp/topics/TP0001/goods",
            "/mp/category/tree",
            "/mp/coupon",
            "/mp/community",
            "/mp/pickup/PP0001",
            "/mp/search/hot",
            "/mp/search/suggest?keyword=米",
            "/mp/goods?communityNo=CM001",
            "/mp/goods?keyword=米",
            "/mp/merchant?communityNo=CM001",
            "/mp/merchant?keyword=老张",
            "/mp/community/nearby?latE6=30539183&lngE6=104059518",
            "/mp/community/C0001",
            "/mp/community/regions",
            "/mp/regions",
            "/mp/topics/TP0002/goods",
            "/mp/store/by-code?code=ST-M0001",
            "/mp/goods/G0002",
            "/mp/goods/G0002/sku-price",
            "/mp/merchant/M0002",
            "/mp/merchant/M0002/score",
            "/mp/store/M0002",
            "/mp/help/faq");

    @Test
    @DisplayName("★★★ 登录之后不该比游客看得少 —— 数据域 fail-closed 会让 C 端读静默变空")
    void loggedInSeesAtLeastWhatGuestSees() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13500139001");

        List<String> broken = new ArrayList<>();
        int compared = 0;

        for (String path : PUBLIC_READS) {
            JsonNode guest = body(path, null);
            JsonNode mine = body(path, token);
            if (guest == null || mine == null) {
                continue;   // 接口本身报错，不是这条用例要说的事
            }
            int guestCode = guest.get("code").asInt();
            if (guestCode != 0 || isEmpty(guest.get("data"))) {
                continue;   // 游客这边就没东西，比不出来
            }
            compared++;
            int myCode = mine.get("code").asInt();
            if (myCode != 0) {
                broken.add(path + " → 游客 code=0，登录 code=" + myCode);
            } else if (isEmpty(mine.get("data"))) {
                broken.add(path + " → 游客有数据，登录是空的");
            }
        }

        assertThat(broken)
                .as("这些接口登录之后反而看不到东西 —— 十有八九是某个 C 端读漏了 "
                        + "DataScopeContext.executeWithoutScope，"
                        + "而它读的那张表只有 MERCHANT 锚点、没有 SELF")
                .isEmpty();

        /*
         * **对照量本身也要验非零。** 少了这一条，种子一变（或路径写错）就会让
         * 每一条都走上面的 continue，于是「一条都没坏」——而它其实一条都没验。
         * 这个仓库在别处栽过同样的跟头：数字对了，但那个数字不说明问题。
         */
        assertThat(compared)
                .as("真正比对上的接口不足 20 条（表里 34 条）—— 种子空了或路径写错了，"
                        + "这条用例此刻大半在空转。别直接把这个数调小：先看是哪几条不再返回数据了")
                .isGreaterThanOrEqualTo(20);
    }

    /** 空的判定：null / 空数组 / 空对象 / 分页 records 为空，都算「没东西」 */
    private static boolean isEmpty(JsonNode data) {
        if (data == null || data.isNull()) {
            return true;
        }
        if (data.isArray()) {
            return data.isEmpty();
        }
        JsonNode records = data.get("records");
        if (records != null && records.isArray()) {
            return records.isEmpty();
        }
        return data.isObject() && data.isEmpty();
    }

    private JsonNode body(String path, String token) throws Exception {
        var req = get(path);
        if (token != null) {
            req = req.header("Authorization", "Bearer " + token);
        }
        String raw = mvc().perform(req).andReturn().getResponse().getContentAsString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonNode n = json.readTree(raw);
        return n.get("code") == null ? null : n;
    }
}
