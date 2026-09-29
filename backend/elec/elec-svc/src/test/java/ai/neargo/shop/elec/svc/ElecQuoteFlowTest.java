package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.entity.ElcRfq;
import ai.neargo.shop.elec.mapper.ElecMappers.RfqMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 询价有结果：运营报价 → 微信通知买家 → 买家接受 → 平台成交。以及运营端的权限、主系统挂了时的表现。
 */
@SpringBootTest(classes = ElecApplication.class)
@ActiveProfiles("test")
@Import(FakeMainSystem.Config.class)
class ElecQuoteFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private FakeMainSystem main;
    @Autowired
    private RfqMapper rfqMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ── 报价与通知 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 运营报价 → 买家收到通知（落到询价详情页）→ 买家看得到报价 → 接受 → 状态已接受")
    void quoteNotifyAccept() throws Exception {
        String buyer = main.consumer("12600920001");
        String rfqNo = submit(buyer, "{\"lines\":[{\"mpn\":\"QT100A\",\"qty\":2000},{\"mpn\":\"QT100B\",\"qty\":50}]}");
        String ops = main.operator(ElecInternal.PERM_RFQ_READ, ElecInternal.PERM_RFQ_QUOTE);

        JsonNode quoted = data(post("/elec/ops/rfq/" + rfqNo + "/quote"), ops, """
                {"validDays":3,"note":"原装现货，含税含运",
                 "lines":[{"lineNo":1,"priceE6":6500000,"qty":2000,"dcYear":2025,"leadDays":0}]}""");
        assertThat(quoted.get("status").asString()).isEqualTo("QUOTED");
        assertThat(quoted.get("buyerNotified").asBoolean()).as("主系统收下了通知").isTrue();

        ElecInternal.QuotedNotice n = main.notices().stream().filter(x -> x.rfqNo().equals(rfqNo)).findFirst()
                .orElseThrow(() -> new AssertionError("没有通知买家"));
        assertThat(n.userNo()).isEqualTo(main.userNoOf(buyer));
        assertThat(n.result()).isEqualTo("QUOTED");
        assertThat(n.summary()).isEqualTo("QT100A 等 2 项");
        assertThat(n.page()).isEqualTo("pkg-elec/rfq/index?rfqNo=" + rfqNo);

        JsonNode view = data(get("/elec/c/rfq/" + rfqNo), buyer, null);
        assertThat(view.get("status").asString()).isEqualTo("QUOTED");
        assertThat(view.get("quoteNote").asString()).isEqualTo("原装现货，含税含运");
        assertThat(view.get("lines").get(0).get("quote").get("priceE6").asLong()).isEqualTo(6_500_000L);
        assertThat(absent(view.get("lines").get(1).path("quote"))).as("没报的行就是没找到货").isTrue();
        assertThat(view.toString()).as("买家看到的报价里没有任何供应商").doesNotContain("supplierNo")
                .doesNotContain("companyName");

        JsonNode accepted = data(post("/elec/c/rfq/" + rfqNo + "/accept"), buyer, null);
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(call(post("/elec/c/rfq/" + rfqNo + "/accept"), buyer, null).get("code").asInt())
                .as("接受过了不能再接受").isEqualTo(90011);
        assertThat(call(post("/elec/ops/rfq/" + rfqNo + "/quote"), ops,
                "{\"lines\":[{\"lineNo\":1,\"priceE6\":1}]}").get("code").asInt())
                .as("买家接受之后不能再改价").isEqualTo(90011);
    }

    @Test
    @DisplayName("★★★ 报价过了有效期：买家看到「已过期」，接受被拒")
    void expiredQuote() throws Exception {
        String buyer = main.consumer("12600920002");
        String rfqNo = submit(buyer, "{\"lines\":[{\"mpn\":\"QT200A\",\"qty\":10}]}");
        String ops = main.operator(ElecInternal.PERM_RFQ_READ, ElecInternal.PERM_RFQ_QUOTE);
        data(post("/elec/ops/rfq/" + rfqNo + "/quote"), ops,
                "{\"validDays\":1,\"lines\":[{\"lineNo\":1,\"priceE6\":1000000}]}");

        ElcRfq patch = new ElcRfq();
        patch.setQuoteValidUntil(LocalDate.now().minusDays(1));
        rfqMapper.update(patch, Wrappers.<ElcRfq>lambdaUpdate().eq(ElcRfq::getRfqNo, rfqNo));

        assertThat(data(get("/elec/c/rfq/" + rfqNo), buyer, null).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(call(post("/elec/c/rfq/" + rfqNo + "/accept"), buyer, null).get("code").asInt()).isEqualTo(90012);
    }

    @Test
    @DisplayName("★★★ 找不到货也是结果：以「暂无货源」关单，买家同样收到通知")
    void noSourceNotifies() throws Exception {
        String buyer = main.consumer("12600920003");
        String rfqNo = submit(buyer, "{\"lines\":[{\"mpn\":\"QT300A\",\"qty\":10}]}");
        String ops = main.operator(ElecInternal.PERM_RFQ_READ, ElecInternal.PERM_RFQ_QUOTE);
        JsonNode closed = data(post("/elec/ops/rfq/" + rfqNo + "/close"), ops, "{\"reason\":\"NO_SOURCE\"}");
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(main.notices()).anyMatch(n -> n.rfqNo().equals(rfqNo) && n.result().equals("NO_SOURCE"));
        assertThat(data(get("/elec/c/rfq/" + rfqNo), buyer, null).get("closeReason").asString()).isEqualTo("NO_SOURCE");
    }

    @Test
    @DisplayName("★★ 改价时没再列出的行要清掉旧报价（updateById 跳过 null，这一步不能靠它）")
    void requoteClearsDroppedLines() throws Exception {
        String buyer = main.consumer("12600920004");
        String rfqNo = submit(buyer, "{\"lines\":[{\"mpn\":\"QT400A\",\"qty\":1},{\"mpn\":\"QT400B\",\"qty\":1}]}");
        String ops = main.operator(ElecInternal.PERM_RFQ_READ, ElecInternal.PERM_RFQ_QUOTE);
        data(post("/elec/ops/rfq/" + rfqNo + "/quote"), ops,
                "{\"lines\":[{\"lineNo\":1,\"priceE6\":100},{\"lineNo\":2,\"priceE6\":200}]}");
        data(post("/elec/ops/rfq/" + rfqNo + "/quote"), ops, "{\"lines\":[{\"lineNo\":1,\"priceE6\":150}]}");
        JsonNode lines = data(get("/elec/c/rfq/" + rfqNo), buyer, null).get("lines");
        assertThat(lines.get(0).get("quote").get("priceE6").asLong()).isEqualTo(150L);
        assertThat(absent(lines.get(1).path("quote"))).as("第二行这次没报，旧价不能还挂着").isTrue();
    }

    @Test
    @DisplayName("★★ 运营端：详情里看得到库里谁有货（买家面看不到的那一段，只在这里）")
    void opsDetailShowsSources() throws Exception {
        String sup = main.consumer("12600920005");
        data(post("/elec/b/supplier"), sup, "{\"companyName\":\"来源电子\"}");
        String csv = "型号,数量,单价\nQT500A,888,1.2\n";
        String body = mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/elec/b/stock/upload")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "s.csv", "text/csv",
                                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + sup))
                .andReturn().getResponse().getContentAsString();
        String batch = json.readTree(body).get("data").get("batchNo").asString();
        data(post("/elec/b/stock/batch/" + batch + "/apply"), sup, null);

        String buyer = main.consumer("12600920006");
        String rfqNo = submit(buyer, "{\"lines\":[{\"mpn\":\"QT500A\",\"qty\":100}]}");
        String ops = main.operator(ElecInternal.PERM_RFQ_READ);
        JsonNode d = data(get("/elec/ops/rfq/" + rfqNo), ops, null);
        assertThat(d.get("contactPhone").asString()).as("运营端给完整号码").isEqualTo("12600920006");
        assertThat(d.get("lines").get(0).get("sources").get(0).get("companyName").asString()).isEqualTo("来源电子");
        assertThat(d.get("lines").get(0).get("sources").get(0).get("qty").asLong()).isEqualTo(888L);
    }

    // ── 权限与认令牌 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 运营端：只读权限不能报价；C 端令牌打运营端 401；运营令牌打 C 端也不算登录")
    void opsPermissions() throws Exception {
        String buyer = main.consumer("12600920007");
        String rfqNo = submit(buyer, "{\"lines\":[{\"mpn\":\"QT600A\",\"qty\":1}]}");
        String readOnly = main.operator(ElecInternal.PERM_RFQ_READ);
        String none = main.operator();

        assertThat(call(get("/elec/ops/rfq"), readOnly, null).get("code").asInt()).isZero();
        assertThat(call(post("/elec/ops/rfq/" + rfqNo + "/quote"), readOnly,
                "{\"lines\":[{\"lineNo\":1,\"priceE6\":1}]}").get("code").asInt()).isEqualTo(10403);
        assertThat(call(get("/elec/ops/rfq"), none, null).get("code").asInt()).isEqualTo(10403);
        assertThat(status(get("/elec/ops/rfq"), buyer)).as("C 端令牌不认").isEqualTo(401);
        assertThat(status(get("/elec/c/rfq"), readOnly)).as("运营令牌在 C 端不算登录").isEqualTo(401);
    }

    @Test
    @DisplayName("★★★ 主系统挂了：要登录的接口 503（不是 401 —— 401 会让端上清掉令牌），查料号照常")
    void mainSystemDown() throws Exception {
        String buyer = main.consumer("12600920008");
        main.down = true;
        try {
            // 换一个没进过缓存的令牌：进过缓存的 60 秒内照常可用，那正是缓存的用途
            String fresh = main.consumerWithoutPhone();
            assertThat(status(get("/elec/c/rfq"), fresh)).isEqualTo(503);
            assertThat(status(get("/elec/c/part").param("keyword", "QT"), fresh)).as("查料号匿名照查").isEqualTo(200);
            assertThat(status(get("/elec/c/part").param("keyword", "QT"), null)).isEqualTo(200);
        } finally {
            main.down = false;
        }
        assertThat(status(get("/elec/c/rfq"), buyer)).isEqualTo(200);
    }

    @Test
    @DisplayName("★★ 令牌缓存：同一令牌 60 秒内只问主系统一次；主系统说过期的回 10402 而不是 10401")
    void sessionCacheAndExpiry() throws Exception {
        String buyer = main.consumer("12600920009");
        int before = main.sessionCalls.get();
        status(get("/elec/c/rfq"), buyer);
        status(get("/elec/c/rfq"), buyer);
        status(get("/elec/c/rfq"), buyer);
        assertThat(main.sessionCalls.get() - before).isEqualTo(1);

        JsonNode r = call(get("/elec/c/rfq"), "ctk_not-a-real-session", null);
        assertThat(r.get("code").asInt()).isEqualTo(10402);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private String submit(String buyer, String body) throws Exception {
        return data(post("/elec/c/rfq"), buyer, body).get("rfqNo").asString();
    }

    private static boolean absent(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
    }

    private int status(MockHttpServletRequestBuilder req, String token) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return mvc().perform(req).andReturn().getResponse().getStatus();
    }

    private JsonNode data(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        JsonNode r = call(req, token, body);
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.path("data");
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return json.readTree(mvc().perform(req).andReturn().getResponse().getContentAsString());
    }
}
