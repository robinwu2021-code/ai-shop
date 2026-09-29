package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.entity.ElcQuote;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.mapper.ElecMappers.QuoteMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierMapper;
import ai.neargo.shop.elec.service.ElecSupplierService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 通知补齐：供应商报价 / 拒绝之后买家与运营群各收到什么；库存快到期的站内信。
 * 对账表见 docs/technical/TDD-元器件-通知补齐.md §5 —— 方法名前的 ACn 就是那张表的行。
 */
@SpringBootTest(classes = ElecApplication.class)
@ActiveProfiles("test")
@Import(FakeMainSystem.Config.class)
class ElecNotifyFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private FakeMainSystem main;
    @Autowired
    private RecordingAlerts alerts;
    @Autowired
    private QuoteMapper quoteMapper;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private ElecSupplierService supplierService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("AC1 AC2 ★★★ 供应商首次报价：买家收到「有新报价」、运营群看到真名与价；改价只进群、不再打扰买家")
    void ac1ac2_supplierQuoteNotifiesBuyerAndOps() throws Exception {
        Sup s = supplier("12600950001", "报价通知电子", "型号,数量\nNTQ100,500\n");
        String buyer = main.consumer("12600950002");
        String rfqNo = rfq(buyer, "NTQ100", 800);
        String dispatchNo = firstDispatch(s.token);

        data(post("/elec/b/rfq/" + dispatchNo + "/quote"), s.token,
                "{\"priceE6\":2000000,\"qtyAvailable\":500,\"leadDays\":0,\"cond\":\"ORIGINAL\"}");

        List<ElecInternal.QuotedNotice> offers = offersFor(rfqNo, ElecInternal.RESULT_OFFER);
        assertThat(offers).as("买家收到「有新报价」").hasSize(1);
        assertThat(offers.get(0).userNo()).isEqualTo(main.userNoOf(buyer));
        assertThat(offers.get(0).summary()).isEqualTo("NTQ100");
        assertThat(offers.get(0).page()).isEqualTo("pkg-elec/pages/rfq/index?rfqNo=" + rfqNo);
        assertThat(quoteOf(dispatchNo).getBuyerNotifiedAt()).as("送到了就记下时间").isNotNull();

        List<RecordingAlerts.Sent> ops = alerts.about(rfqNo).stream()
                .filter(a -> a.kind().equals("ELEC_QUOTE")).toList();
        assertThat(ops).hasSize(1);
        assertThat(ops.get(0).markdown())
                .contains("**供应商报价**").contains("报价通知电子").contains("12600950001")
                .contains("¥2").contains("含税").contains("现货").contains("原装原包")
                .contains("只够 500 / 800").as("数量不够要提醒运营再找一家")
                .contains("这一行目前 1 家报了价");

        data(post("/elec/b/rfq/" + dispatchNo + "/quote"), s.token, "{\"priceE6\":1900000,\"qtyAvailable\":800}");
        assertThat(offersFor(rfqNo, ElecInternal.RESULT_OFFER)).as("改价不再打扰买家").hasSize(1);
        assertThat(alerts.about(rfqNo).stream().filter(a -> a.markdown().contains("**供应商改价**")))
                .as("但运营群要知道改了价").hasSize(1);
    }

    @Test
    @DisplayName("AC3 AC4 ★★★ 两家都拒：第一家拒时不说「没货」（还有人没回话）；第二家拒完，买家收到「一项暂无货源」、群里标红")
    void ac3ac4_lineAllDeclined() throws Exception {
        Sup a = supplier("12600950003", "拒绝一号电子", "型号,数量\nNTD200,100\n");
        Sup b = supplier("12600950004", "拒绝二号电子", "型号,数量\nNTD200,300\n");
        String buyer = main.consumer("12600950005");
        String rfqNo = rfq(buyer, "NTD200", 50);

        data(post("/elec/b/rfq/" + firstDispatch(a.token) + "/decline"), a.token, "{\"reason\":\"NO_STOCK\"}");
        assertThat(offersFor(rfqNo, ElecInternal.RESULT_LINE_NO_OFFER)).as("B 还没回话，先不说没货").isEmpty();
        assertThat(alerts.about(rfqNo)).anyMatch(s -> s.kind().equals("ELEC_DECLINE")
                && s.markdown().contains("拒绝一号电子") && s.markdown().contains("没货"));

        String bDispatch = firstDispatch(b.token);
        data(post("/elec/b/rfq/" + bDispatch + "/decline"), b.token, "{\"reason\":\"PRICE\"}");
        List<ElecInternal.QuotedNotice> none = offersFor(rfqNo, ElecInternal.RESULT_LINE_NO_OFFER);
        assertThat(none).as("两家都拒了：告诉买家").hasSize(1);
        assertThat(none.get(0).summary()).isEqualTo("NTD200");
        RecordingAlerts.Sent loud = alerts.about(rfqNo).stream()
                .filter(s -> s.kind().equals("ELEC_LINE_ALL_DECLINED")).findFirst().orElseThrow();
        assertThat(loud.markdown()).startsWith("<font color=\"warning\">**⚠ 整行都被拒，要人工找货**</font>")
                .contains("价格做不了");

        data(post("/elec/b/rfq/" + bDispatch + "/decline"), b.token, "{\"reason\":\"PRICE\"}");
        assertThat(offersFor(rfqNo, ElecInternal.RESULT_LINE_NO_OFFER)).as("重复点拒绝不是新的事").hasSize(1);
    }

    @Test
    @DisplayName("AC3 ★★ 有一家报了价、另一家拒：不算整行被拒")
    void ac3_oneQuoteOneDecline() throws Exception {
        Sup a = supplier("12600950006", "有价电子", "型号,数量\nNTE300,100\n");
        Sup b = supplier("12600950007", "没货电子", "型号,数量\nNTE300,100\n");
        String buyer = main.consumer("12600950008");
        String rfqNo = rfq(buyer, "NTE300", 10);
        data(post("/elec/b/rfq/" + firstDispatch(a.token) + "/quote"), a.token, "{\"priceE6\":1000000,\"qtyAvailable\":10}");
        data(post("/elec/b/rfq/" + firstDispatch(b.token) + "/decline"), b.token, "{\"reason\":\"NO_STOCK\"}");
        assertThat(offersFor(rfqNo, ElecInternal.RESULT_LINE_NO_OFFER)).isEmpty();
        assertThat(alerts.about(rfqNo)).noneMatch(s -> s.kind().equals("ELEC_LINE_ALL_DECLINED"));
    }

    @Test
    @DisplayName("AC5 ★★★ 库存 3 天内到期：发一条站内信（kind=EXPIRING，落到库存页）；同一周不再发；10 天后到期的不发")
    void ac5_expiryReminder() throws Exception {
        Sup soon = supplier("12600950009", "快到期电子", "型号,数量\nNTX400,1\nNTX401,2\n");
        Sup later = supplier("12600950010", "还早电子", "型号,数量\nNTX500,1\n");
        setValidUntil(soon.no, LocalDate.now().plusDays(2));
        setValidUntil(later.no, LocalDate.now().plusDays(10));

        supplierService.remindExpiring();
        List<ElecInternal.SupplierNotice> mine = expiryFor(soon.token);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).body()).startsWith("2 行库存最早");
        assertThat(mine.get(0).page()).isEqualTo("pkg-elec/pages/stocks/index?filter=EXPIRING");
        assertThat(expiryFor(later.token)).as("10 天后才到期：不提醒").isEmpty();

        supplierService.remindExpiring();
        assertThat(expiryFor(soon.token)).as("一周最多一条").hasSize(1);
    }

    @Test
    @DisplayName("AC5 ★★ 主系统挂了没送到：把占位还回去，明天还会再试（而不是等一周）")
    void ac5_failedReminderRetriesTomorrow() throws Exception {
        Sup s = supplier("12600950011", "重试电子", "型号,数量\nNTY600,1\n");
        setValidUntil(s.no, LocalDate.now().plusDays(1));
        main.down = true;
        try {
            supplierService.remindExpiring();
        } finally {
            main.down = false;
        }
        assertThat(supplierOf(s.no).getExpiryRemindedAt()).as("没送到：占位还回去").isNull();
        supplierService.remindExpiring();
        assertThat(expiryFor(s.token)).hasSize(1);
        assertThat(supplierOf(s.no).getExpiryRemindedAt()).isNotNull();
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

    private String rfq(String buyer, String mpn, long qty) throws Exception {
        return data(post("/elec/c/rfq"), buyer, "{\"lines\":[{\"mpn\":\"" + mpn + "\",\"qty\":" + qty + "}]}")
                .get("rfqNo").asString();
    }

    private String firstDispatch(String supplierToken) throws Exception {
        JsonNode list = data(get("/elec/b/rfq"), supplierToken, null);
        assertThat(list).as("库里有这个料号：询价自动派给了他").isNotEmpty();
        return list.get(0).get("dispatchNo").asString();
    }

    private List<ElecInternal.QuotedNotice> offersFor(String rfqNo, String result) {
        return main.notices().stream().filter(n -> n.rfqNo().equals(rfqNo) && n.result().equals(result)).toList();
    }

    private List<ElecInternal.SupplierNotice> expiryFor(String supplierToken) {
        String userNo = main.userNoOf(supplierToken);
        return main.supplierNotices().stream()
                .filter(n -> n.userNo().equals(userNo) && ElecInternal.KIND_EXPIRING.equals(n.kind())).toList();
    }

    private ElcQuote quoteOf(String dispatchNo) {
        return quoteMapper.selectOne(Wrappers.<ElcQuote>lambdaQuery().eq(ElcQuote::getDispatchNo, dispatchNo));
    }

    private ElcSupplier supplierOf(String supplierNo) {
        return supplierMapper.selectOne(Wrappers.<ElcSupplier>lambdaQuery().eq(ElcSupplier::getSupplierNo, supplierNo));
    }

    private void setValidUntil(String supplierNo, LocalDate d) {
        stockMapper.update(null, Wrappers.<ElcStock>lambdaUpdate().eq(ElcStock::getSupplierNo, supplierNo)
                .set(ElcStock::getValidUntil, d));
    }

    private JsonNode data(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        JsonNode r = json.readTree(mvc().perform(req).andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.path("data");
    }
}
