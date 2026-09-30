package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 选报价：一行只成交一家。对账表见 docs/technical/TDD-元器件-选报价规则.md §5。
 */
@SpringBootTest(classes = ElecApplication.class)
@ActiveProfiles("test")
@Import(FakeMainSystem.Config.class)
class ElecChooseFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private FakeMainSystem main;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("AC1 AC2 AC3 ★★★ 选了 A 再选 B：90011，B 收不到「被选中」；B 那边看到「未被选中」，也不能再改价；没报的 C 不能再报")
    void ac1ac2ac3_oneWinnerPerLine() throws Exception {
        Sup a = supplier("12600970001", "选中甲电子", "型号,数量\nCHA100,100\n");
        Sup b = supplier("12600970002", "选中乙电子", "型号,数量\nCHA100,100\n");
        Sup c = supplier("12600970003", "选中丙电子", "型号,数量\nCHA100,100\n");
        String buyer = main.consumer("12600970004");
        String rfqNo = data(post("/elec/c/rfq"), buyer, "{\"lines\":[{\"mpn\":\"CHA100\",\"qty\":10}]}")
                .get("rfqNo").asString();
        String bDispatch = firstDispatch(b.token);
        data(post("/elec/b/rfq/" + firstDispatch(a.token) + "/quote"), a.token, "{\"priceE6\":1000000,\"qtyAvailable\":10}");
        data(post("/elec/b/rfq/" + bDispatch + "/quote"), b.token, "{\"priceE6\":1100000,\"qtyAvailable\":10}");

        JsonNode offers = data(get("/elec/c/rfq/" + rfqNo), buyer, null).get("lines").get(0).get("offers");
        assertThat(offers).hasSize(2);
        String cheap = offers.get(0).get("offerNo").asString();   // 按买家价升序：A 更便宜
        String dear = offers.get(1).get("offerNo").asString();
        int acceptedBefore = acceptedNotices();

        data(post("/elec/c/rfq/" + rfqNo + "/line/1/accept"), buyer, "{\"offerNo\":\"" + cheap + "\"}");
        assertThat(code(post("/elec/c/rfq/" + rfqNo + "/line/1/accept"), buyer, "{\"offerNo\":\"" + dear + "\"}"))
                .as("一行只能成交一家").isEqualTo(90011);
        assertThat(acceptedNotices() - acceptedBefore).as("只有 A 收到「被选中」").isEqualTo(1);

        JsonNode bQuote = data(get("/elec/b/rfq/" + bDispatch), b.token, null).get("myQuote");
        assertThat(bQuote.get("status").asString()).as("B 看得到结果，不再挂着等待").isEqualTo("NOT_CHOSEN");
        assertThat(code(post("/elec/b/rfq/" + bDispatch + "/quote"), b.token, "{\"priceE6\":900000,\"qtyAvailable\":10}"))
                .as("这一行成交了，B 不能再改价").isEqualTo(90011);
        assertThat(code(post("/elec/b/rfq/" + firstDispatch(c.token) + "/quote"), c.token,
                "{\"priceE6\":800000,\"qtyAvailable\":10}")).as("没报过的 C 也不能再报").isEqualTo(90011);

        JsonNode after = data(get("/elec/c/rfq/" + rfqNo), buyer, null).get("lines").get(0).get("offers");
        assertThat(after).as("买家那边只剩选中的那一条").hasSize(1);
    }

    @Test
    @DisplayName("AC4 ★★★ 平台与供应商不能在同一行都成交：先选了供应商报价，再接受平台整单 → 90011")
    void ac4_supplierThenPlatform() throws Exception {
        Sup a = supplier("12600970005", "先选电子", "型号,数量\nCHB200,100\n");
        String buyer = main.consumer("12600970006");
        String rfqNo = data(post("/elec/c/rfq"), buyer, "{\"lines\":[{\"mpn\":\"CHB200\",\"qty\":10}]}")
                .get("rfqNo").asString();
        data(post("/elec/b/rfq/" + firstDispatch(a.token) + "/quote"), a.token, "{\"priceE6\":1000000,\"qtyAvailable\":10}");
        String offerNo = data(get("/elec/c/rfq/" + rfqNo), buyer, null).get("lines").get(0).get("offers").get(0)
                .get("offerNo").asString();
        data(post("/elec/c/rfq/" + rfqNo + "/line/1/accept"), buyer, "{\"offerNo\":\"" + offerNo + "\"}");

        String ops = main.operator(ElecInternal.PERM_RFQ_READ, ElecInternal.PERM_RFQ_QUOTE);
        data(post("/elec/ops/rfq/" + rfqNo + "/quote"), ops,
                "{\"validDays\":3,\"lines\":[{\"lineNo\":1,\"priceE6\":900000,\"qty\":10}]}");
        assertThat(code(post("/elec/c/rfq/" + rfqNo + "/accept"), buyer, null)).isEqualTo(90011);
    }

    @Test
    @DisplayName("AC4 ★★★ 反过来：平台整单已接受，再选供应商报价 → 90011")
    void ac4_platformThenSupplier() throws Exception {
        Sup a = supplier("12600970007", "后选电子", "型号,数量\nCHC300,100\n");
        String buyer = main.consumer("12600970008");
        String rfqNo = data(post("/elec/c/rfq"), buyer, "{\"lines\":[{\"mpn\":\"CHC300\",\"qty\":10}]}")
                .get("rfqNo").asString();
        data(post("/elec/b/rfq/" + firstDispatch(a.token) + "/quote"), a.token, "{\"priceE6\":1000000,\"qtyAvailable\":10}");
        String ops = main.operator(ElecInternal.PERM_RFQ_READ, ElecInternal.PERM_RFQ_QUOTE);
        data(post("/elec/ops/rfq/" + rfqNo + "/quote"), ops,
                "{\"validDays\":3,\"lines\":[{\"lineNo\":1,\"priceE6\":900000,\"qty\":10}]}");
        data(post("/elec/c/rfq/" + rfqNo + "/accept"), buyer, null);

        JsonNode offers = data(get("/elec/c/rfq/" + rfqNo), buyer, null).get("lines").get(0).get("offers");
        String supplierOffer = null;
        for (JsonNode o : offers) {
            if ("SUPPLIER".equals(o.get("from").asString())) {
                supplierOffer = o.get("offerNo").asString();
            }
        }
        assertThat(supplierOffer).isNotNull();
        assertThat(code(post("/elec/c/rfq/" + rfqNo + "/line/1/accept"), buyer, "{\"offerNo\":\"" + supplierOffer + "\"}"))
                .isEqualTo(90011);
    }

    // ── 造数与调用 ──────────────────────────────────────────────────────────

    private record Sup(String token, String no) {
    }

    private Sup supplier(String phone, String company, String csv) throws Exception {
        String token = main.consumer(phone);
        String no = data(post("/elec/b/supplier"), token, "{\"companyName\":\"" + company + "\"}")
                .get("supplierNo").asString();
        String body = mvc().perform(multipart("/elec/b/stock/upload")
                        .file(new MockMultipartFile("file", "s.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode up = json.readTree(body);
        assertThat(up.get("code").asInt()).as(body).isZero();
        data(post("/elec/b/stock/batch/" + up.get("data").get("batchNo").asString() + "/apply"), token, null);
        return new Sup(token, no);
    }

    private String firstDispatch(String supplierToken) throws Exception {
        JsonNode list = data(get("/elec/b/rfq"), supplierToken, null);
        assertThat(list).isNotEmpty();
        return list.get(0).get("dispatchNo").asString();
    }

    private int acceptedNotices() {
        return (int) main.supplierNotices().stream()
                .filter(n -> ElecInternal.KIND_ACCEPTED.equals(n.kind())).count();
    }

    private int code(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        return call(req, token, body).get("code").asInt();
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
