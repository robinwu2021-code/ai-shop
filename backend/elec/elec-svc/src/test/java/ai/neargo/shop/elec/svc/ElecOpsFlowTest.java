package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.entity.ElcQuote;
import ai.neargo.shop.elec.entity.ElcSearchDaily;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.mapper.ElecMappers.QuoteMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SearchDailyMapper;
import ai.neargo.shop.elec.entity.ElcStockBatch;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 运营端四页的后端：供应商、料号与库存、基础数据、询报价。
 * 对账表见 docs/technical/TDD-元器件-运营端接口.md §5 —— 每个方法名前的 ACn 就是那张表的行。
 */
@SpringBootTest(classes = ElecApplication.class)
@ActiveProfiles("test")
@Import(FakeMainSystem.Config.class)
class ElecOpsFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private FakeMainSystem main;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private QuoteMapper quoteMapper;
    @Autowired
    private SearchDailyMapper dailyMapper;
    @Autowired
    private StockBatchMapper batchMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private String ops() {
        return main.operator(ElecInternal.OPS_PERMS.toArray(String[]::new));
    }

    // ── 供应商 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC1 AC2 ★★ 列表带在售数、快到期数、最近上传；按公司名找得到；详情看得到他的库存")
    void ac1ac2_supplierListAndDetail() throws Exception {
        Sup s = supplier("12600940001", "列表测试电子", "型号,数量\nOPA1X,100\nOPA1Y,200\n");
        stockMapper.update(null, Wrappers.<ElcStock>lambdaUpdate()
                .eq(ElcStock::getSupplierNo, s.no).eq(ElcStock::getMpnRaw, "OPA1Y")
                .set(ElcStock::getValidUntil, LocalDate.now().plusDays(3)));
        String ops = ops();

        JsonNode rows = data(get("/elec/ops/supplier").param("keyword", "列表测试"), ops, null);
        assertThat(rows).hasSize(1);
        JsonNode r = rows.get(0);
        assertThat(r.get("supplierNo").asString()).isEqualTo(s.no);
        assertThat(r.get("onCount").asInt()).isEqualTo(2);
        assertThat(r.get("expiringCount").asInt()).as("3 天后到期的那行").isEqualTo(1);
        assertThat(absent(r.get("lastUploadAt"))).as("传过一次").isFalse();
        assertThat(r.get("contactPhone").asString()).as("运营端给完整号码").isEqualTo("12600940001");

        assertThat(data(get("/elec/ops/supplier").param("keyword", "_"), ops, null).size())
                .as("「_」不能当通配符把所有人拉出来 —— 去掉后是空关键字 = 不筛，但不会报错")
                .isGreaterThanOrEqualTo(1);

        JsonNode d = data(get("/elec/ops/supplier/" + s.no), ops, null);
        assertThat(d.get("onCount").asInt()).isEqualTo(2);
        assertThat(d.get("expiredCount").asInt()).isZero();
        assertThat(d.get("dispatch").get("days").asInt()).isEqualTo(30);

        JsonNode stock = data(get("/elec/ops/supplier/" + s.no + "/stock").param("filter", "EXPIRING"), ops, null);
        assertThat(stock).hasSize(1);
        assertThat(stock.get(0).get("mpn").asString()).isEqualTo("OPA1Y");
    }

    @Test
    @DisplayName("AC3 ★★★ 暂停：他的货当场从买家面消失、他不能再用；理由必填；恢复后货回来")
    void ac3_suspendHidesStockFromBuyers() throws Exception {
        Sup s = supplier("12600940002", "暂停测试电子", "型号,数量,单价\nOPA3A,500,1.0\n");
        String ops = ops();
        assertThat(buyerMarket("OPA3A")).as("暂停前买家看得到").isNotNull();

        assertThat(call(post("/elec/ops/supplier/" + s.no + "/suspend"), ops, "{\"reason\":\"\"}")
                .get("code").asInt()).as("理由必填").isEqualTo(10400);

        JsonNode d = data(post("/elec/ops/supplier/" + s.no + "/suspend"), ops, "{\"reason\":\"报价屡次不兑现\"}");
        assertThat(d.get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(d.get("suspendReason").asString()).isEqualTo("报价屡次不兑现");
        assertThat(buyerMarket("OPA3A")).as("暂停后买家面上没有他的货 —— 投影必须当场重算").isNull();
        assertThat(call(get("/elec/b/stock"), s.token, null).get("code").asInt())
                .as("他自己也用不了了").isEqualTo(90004);

        JsonNode back = data(post("/elec/ops/supplier/" + s.no + "/resume"), ops, null);
        assertThat(back.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(back.get("suspendReason").asString()).as("理由留着：上次为什么停过").isEqualTo("报价屡次不兑现");
        assertThat(buyerMarket("OPA3A")).as("恢复后货回来").isNotNull();
    }

    @Test
    @DisplayName("AC4 ★★ 运营代改资料：与供应商自己改同一套校验")
    void ac4_opsUpdatesProfile() throws Exception {
        Sup s = supplier("12600940003", "改资料电子", "型号,数量\nOPA4A,1\n");
        String ops = ops();
        JsonNode d = data(put("/elec/ops/supplier/" + s.no), ops, "{\"city\":\"深圳\",\"contactName\":\"王工\"}");
        assertThat(d.get("city").asString()).isEqualTo("深圳");
        assertThat(d.get("companyName").asString()).as("没给的字段不动").isEqualTo("改资料电子");
        assertThat(call(put("/elec/ops/supplier/" + s.no), ops, "{\"contactPhone\":\"123\"}").get("code").asInt())
                .as("手机号格式同样挡").isEqualTo(10400);
    }

    // ── 料号与库存 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC5 ★★★ 运营搜料号与买家同一套命中，但不计入买家需求 —— 对照：买家搜一次就记上")
    void ac5_opsSearchDoesNotLogDemand() throws Exception {
        supplier("12600940005", "搜索测试电子", "型号,数量\nOPA5ZZ,300\n");
        JsonNode rows = data(get("/elec/ops/part").param("q", "OPA5Z"), ops(), null);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("match").asString()).isEqualTo("PREFIX");
        assertThat(rows.get(0).get("supplierCnt").asInt()).isEqualTo(1);
        assertThat(rows.get(0).get("totalQty").asLong()).as("精确数量，不是档位").isEqualTo(300L);
        assertThat(demand("OPA5Z")).as("运营搜的不算需求").isZero();

        data(get("/elec/c/part").param("keyword", "OPA5Z"), null, null);
        assertThat(demand("OPA5Z")).as("对照：量具本身是活的 —— 买家搜一次就记上").isEqualTo(1);
    }

    @Test
    @DisplayName("AC6 ★★★ 料号详情「谁有货」：真名、电话、阶梯价、起订量都在")
    void ac6_partDetailShowsTiersAndMoq() throws Exception {
        supplier("12600940006", "阶梯价电子", "型号,品牌,数量,起订量,1+,100+\nOPA6A,TI,1000,10,1.50,1.20\n");
        String partNo = data(get("/elec/ops/part").param("q", "OPA6A"), ops(), null).get(0).get("partNo").asString();
        JsonNode d = data(get("/elec/ops/part/" + partNo), ops(), null);
        assertThat(d.get("part").get("mfrCode").asString()).isEqualTo("TI");
        JsonNode src = d.get("sources").get(0);
        assertThat(src.get("companyName").asString()).isEqualTo("阶梯价电子");
        assertThat(src.get("contactPhone").asString()).isEqualTo("12600940006");
        assertThat(src.get("moq").asInt()).isEqualTo(10);
        assertThat(src.get("tiers")).hasSize(2);
        assertThat(src.get("tiers").get(1).get("minQty").asLong()).isEqualTo(100L);
        assertThat(src.get("tiers").get(1).get("priceE6").asLong()).isEqualTo(1_200_000L);
        assertThat(absent(src.get("validUntil"))).isFalse();
    }

    @Test
    @DisplayName("AC7 ★★ 库存行查询：按供应商、按料号开头、按到期状态")
    void ac7_stockQuery() throws Exception {
        Sup s = supplier("12600940007", "库存查询电子", "型号,数量\nOPA7A,1\nOPA7B,2\n");
        stockMapper.update(null, Wrappers.<ElcStock>lambdaUpdate()
                .eq(ElcStock::getSupplierNo, s.no).eq(ElcStock::getMpnRaw, "OPA7B")
                .set(ElcStock::getValidUntil, LocalDate.now().minusDays(1)));
        String ops = ops();
        JsonNode all = data(get("/elec/ops/stock").param("supplierNo", s.no), ops, null);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).get("companyName").asString()).isEqualTo("库存查询电子");

        JsonNode byMpn = data(get("/elec/ops/stock").param("q", "OPA7A"), ops, null);
        assertThat(byMpn).hasSize(1);
        JsonNode expired = data(get("/elec/ops/stock").param("supplierNo", s.no).param("filter", "EXPIRED"), ops, null);
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).get("stock").get("status").asString()).isEqualTo("EXPIRED");
    }

    // ── 基础数据 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC8 ★★★ 加厂牌：自己的代码与名字登成别名，「厂牌不明」里写着它的库存当场认过去；代码重复 90013")
    void ac8_createMfrRegistersOwnNames() throws Exception {
        supplier("12600940008", "新厂牌电子", "型号,品牌,数量\nOPA8A,Zhixin Wei,50\n");
        String ops = ops();
        assertThat(opsPart("OPA8A").get("mfrCode").asString()).as("建厂牌之前认不出").isEqualTo("UNKNOWN");

        JsonNode m = data(post("/elec/ops/mfr"), ops,
                "{\"mfrCode\":\"zxwt\",\"nameEn\":\"Zhixin Wei\",\"nameCn\":\"智芯微测试\"}");
        assertThat(m.get("mfrCode").asString()).as("代码统一大写").isEqualTo("ZXWT");
        assertThat(m.get("aliasCnt").asInt()).as("代码、英文名、中文名三种写法").isEqualTo(3);
        assertThat(opsPart("OPA8A").get("mfrCode").asString()).as("建完当场认过去").isEqualTo("ZXWT");

        assertThat(call(post("/elec/ops/mfr"), ops, "{\"mfrCode\":\"ZXWT\",\"nameEn\":\"Dup\"}").get("code").asInt())
                .isEqualTo(90013);
        assertThat(call(post("/elec/ops/mfr"), ops, "{\"mfrCode\":\"a b\",\"nameEn\":\"Bad\"}").get("code").asInt())
                .as("代码只许大写字母数字").isEqualTo(10400);
    }

    @Test
    @DisplayName("AC9 AC10 ★★★ 认不出的厂牌：按行数排、给建议；补成别名后既有库存当场改认、从列表消失、买家那边不再分两条")
    void ac9ac10_unknownMfrBecomesAlias() throws Exception {
        supplier("12600940009", "写法一电子", "型号,品牌,数量\nOPZ5566KLM,Texas Instrument,10\n");
        supplier("12600940010", "写法二电子", "型号,品牌,数量\nOPZ5566KLM,TEXAS INSTRUMENT Inc.,20\n");
        String ops = ops();

        JsonNode unknown = data(get("/elec/ops/mfr/unknown"), ops, null);
        JsonNode ti = find(unknown, "aliasNorm", "TEXASINSTRUMENT");
        assertThat(ti).as("两种原文规范化后是同一种写法").isNotNull();
        assertThat(ti.get("rowCnt").asInt()).isEqualTo(2);
        assertThat(ti.get("supplierCnt").asInt()).as("第二家的写法也数进来了（按料号数只看得到第一家）").isEqualTo(2);
        assertThat(ti.get("suggestCode").asString()).isEqualTo("TI");

        JsonNode r = data(post("/elec/ops/mfr/TI/alias"), ops, "{\"alias\":\"Texas Instrument\"}");
        assertThat(r.get("aliasNorm").asString()).isEqualTo("TEXASINSTRUMENT");
        assertThat(r.get("movedRows").asInt()).isEqualTo(2);

        assertThat(find(data(get("/elec/ops/mfr/unknown"), ops, null), "aliasNorm", "TEXASINSTRUMENT"))
                .as("补完就从列表消失 —— 不改认的话它会一直在，运营以为没点上").isNull();
        JsonNode parts = data(get("/elec/ops/part").param("q", "OPZ5566KLM"), ops, null);
        assertThat(parts).as("并掉的 UNKNOWN 料号不再出现").hasSize(1);
        assertThat(parts.get(0).get("mfrCode").asString()).isEqualTo("TI");
        assertThat(parts.get(0).get("totalQty").asLong()).isEqualTo(30L);

        JsonNode mid = data(get("/elec/c/part").param("keyword", "5566KLM"), null, null).get("hits");
        assertThat(mid).as("买家搜中段：只有一条，没有「厂牌不明」的空重影（分段键跟着删了）").hasSize(1);
        assertThat(mid.get(0).get("mfrKnown").asBoolean()).isTrue();

        supplier("12600940011", "后来者电子", "型号,品牌,数量\nOPZ5566KLM,texas instrument,5\n");
        assertThat(data(get("/elec/ops/part").param("q", "OPZ5566KLM"), ops, null)).as("下次上传直接认得出").hasSize(1);
    }

    @Test
    @DisplayName("AC9 ★★ 别名已指向别家：90014，不静默改指向；同一家再加一次是幂等；UNKNOWN 不许挂别名")
    void ac9_aliasTaken() throws Exception {
        String ops = ops();
        JsonNode taken = call(post("/elec/ops/mfr/ST/alias"), ops, "{\"alias\":\"Texas Instruments\"}");
        assertThat(taken.get("code").asInt()).isEqualTo(90014);
        assertThat(taken.get("msg").asString()).as("告诉他指向了谁").contains("TI");

        assertThat(data(post("/elec/ops/mfr/TI/alias"), ops, "{\"alias\":\"Texas Instruments\"}")
                .get("movedRows").asInt()).isZero();
        assertThat(call(post("/elec/ops/mfr/UNKNOWN/alias"), ops, "{\"alias\":\"Foo\"}").get("code").asInt())
                .isEqualTo(10400);
        assertThat(data(get("/elec/ops/mfr/TI/alias"), ops, null).toString()).contains("TEXASINSTRUMENTS");
    }

    // ── 询报价 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC11 AC12 ★★★ 手工指派 → 供应商收到并报价 → 运营看到真名原价与备注，买家那边仍是匿名")
    void ac11ac12_dispatchQuoteAndOpsView() throws Exception {
        Sup s = supplier("12600940012", "指派测试电子", "型号,数量\nOPA12Z,1\n");
        String buyer = main.consumer("12600940013");
        String rfqNo = data(post("/elec/c/rfq"), buyer, "{\"lines\":[{\"mpn\":\"OPA12NONE\",\"qty\":100}]}")
                .get("rfqNo").asString();
        String ops = ops();

        assertThat(call(post("/elec/ops/rfq/" + rfqNo + "/line/1/dispatch"), ops,
                "{\"supplierNos\":[\"NO_SUCH\"]}").get("code").asInt()).as("号输错了挡住").isEqualTo(10400);
        assertThat(call(post("/elec/ops/rfq/" + rfqNo + "/line/9/dispatch"), ops,
                "{\"supplierNos\":[\"" + s.no + "\"]}").get("code").asInt()).as("没有这一行").isEqualTo(10404);

        JsonNode d = data(post("/elec/ops/rfq/" + rfqNo + "/line/1/dispatch"), ops,
                "{\"supplierNos\":[\"" + s.no + "\"]}");
        assertThat(d.get("dispatchCnt").asInt()).isEqualTo(1);
        JsonNode offer = d.get("lines").get(0).get("offers").get(0);
        assertThat(offer.get("companyName").asString()).isEqualTo("指派测试电子");
        assertThat(offer.get("via").asString()).isEqualTo("OPS");
        assertThat(offer.get("dispatchStatus").asString()).isEqualTo("SENT");
        assertThat(main.supplierNotices()).as("派完通知他").isNotEmpty();

        // 供应商那一侧：这条路（带状态筛选的 SQL）此前没有任何测试走到过
        JsonNode mine = data(get("/elec/b/rfq").param("status", "SENT"), s.token, null);
        assertThat(mine).hasSize(1);
        assertThat(mine.toString()).as("供应商看不到买家").doesNotContain("12600940013").doesNotContain(rfqNo);
        String dispatchNo = mine.get(0).get("dispatchNo").asString();
        data(post("/elec/b/rfq/" + dispatchNo + "/quote"), s.token,
                "{\"priceE6\":2000000,\"qtyAvailable\":100,\"remark\":\"加我微信 wx123\"}");

        JsonNode after = data(get("/elec/ops/rfq/" + rfqNo), ops, null);
        JsonNode o = after.get("lines").get(0).get("offers").get(0);
        assertThat(o.get("dispatchStatus").asString()).isEqualTo("QUOTED");
        assertThat(o.get("priceE6").asLong()).as("供应商原价").isEqualTo(2_000_000L);
        assertThat(o.get("buyerPriceE6").asLong()).as("买家看到的价不低于原价").isGreaterThanOrEqualTo(2_000_000L);
        assertThat(o.get("remark").asString()).as("备注只给平台看").isEqualTo("加我微信 wx123");
        assertThat(after.get("respondedCnt").asInt()).isEqualTo(1);
        assertThat(after.get("offerCnt").asInt()).isEqualTo(1);

        JsonNode list = data(get("/elec/ops/rfq"), ops, null);
        JsonNode row = find(list, "rfqNo", rfqNo);
        assertThat(row.get("respondedCnt").asInt()).as("列表页也有计数").isEqualTo(1);
        assertThat(row.get("lines").get(0).get("offers")).as("列表页不带明细").isEmpty();

        String buyerView = data(get("/elec/c/rfq/" + rfqNo), buyer, null).toString();
        assertThat(buyerView).as("买家那边：没有真名、没有备注").doesNotContain("指派测试电子").doesNotContain("wx123");

        data(post("/elec/ops/supplier/" + s.no + "/suspend"), ops, "{\"reason\":\"测试暂停\"}");
        assertThat(call(post("/elec/ops/rfq/" + rfqNo + "/line/1/dispatch"), ops,
                "{\"supplierNos\":[\"" + s.no + "\"]}").get("code").asInt())
                .as("暂停中的不能派：他收到通知点进来却报不了价").isEqualTo(10400);
    }

    @Test
    @DisplayName("AC13 ★★ 报价记录：按供应商查；过了有效期的显示成 EXPIRED，按 EXPIRED 也筛得出来")
    void ac13_quoteRecords() throws Exception {
        Sup s = supplier("12600940014", "报价记录电子", "型号,数量\nOPA13Z,1\n");
        String buyer = main.consumer("12600940015");
        String rfqNo = data(post("/elec/c/rfq"), buyer, "{\"lines\":[{\"mpn\":\"OPA13NONE\",\"qty\":5}]}")
                .get("rfqNo").asString();
        String ops = ops();
        data(post("/elec/ops/rfq/" + rfqNo + "/line/1/dispatch"), ops, "{\"supplierNos\":[\"" + s.no + "\"]}");
        String dispatchNo = data(get("/elec/b/rfq"), s.token, null).get(0).get("dispatchNo").asString();
        data(post("/elec/b/rfq/" + dispatchNo + "/quote"), s.token, "{\"priceE6\":900000,\"qtyAvailable\":5}");

        JsonNode rows = data(get("/elec/ops/quote").param("supplierNo", s.no), ops, null);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(rows.get(0).get("companyName").asString()).isEqualTo("报价记录电子");
        assertThat(data(get("/elec/ops/quote").param("supplierNo", s.no).param("status", "EXPIRED"), ops, null))
                .isEmpty();

        quoteMapper.update(null, Wrappers.<ElcQuote>lambdaUpdate().eq(ElcQuote::getSupplierNo, s.no)
                .set(ElcQuote::getValidUntil, LocalDate.now().minusDays(1)));
        JsonNode expired = data(get("/elec/ops/quote").param("supplierNo", s.no).param("status", "EXPIRED"), ops, null);
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(data(get("/elec/ops/quote").param("supplierNo", s.no).param("status", "ACTIVE"), ops, null))
                .as("过期的不再算有效").isEmpty();
    }

    // ── 权限 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC14 ★★★ 五个码各管各的：只有料号权限的看不到供应商；只读的不能暂停；没有基础数据码的动不了厂牌")
    void ac14_permsAreSeparate() throws Exception {
        String partOnly = main.operator(ElecInternal.PERM_PART_READ);
        String supRead = main.operator(ElecInternal.PERM_SUPPLIER_READ);
        String rfqRead = main.operator(ElecInternal.PERM_RFQ_READ);

        assertThat(code(get("/elec/ops/part").param("q", "OPA"), partOnly)).isZero();
        assertThat(code(get("/elec/ops/supplier"), partOnly)).isEqualTo(10403);
        assertThat(code(get("/elec/ops/stock"), supRead)).as("库存行查询归料号码管").isEqualTo(10403);
        assertThat(code(get("/elec/ops/supplier"), supRead)).isZero();
        assertThat(call(post("/elec/ops/supplier/X/suspend"), supRead, "{\"reason\":\"xx\"}").get("code").asInt())
                .as("只读的不能暂停（先判权限，所以不是 404）").isEqualTo(10403);
        assertThat(code(get("/elec/ops/mfr"), partOnly)).isEqualTo(10403);
        assertThat(code(get("/elec/ops/quote"), rfqRead)).isZero();
        assertThat(call(post("/elec/ops/rfq/X/line/1/dispatch"), rfqRead, "{\"supplierNos\":[\"S\"]}")
                .get("code").asInt()).as("指派要报价码").isEqualTo(10403);
    }

    // ── 造数与调用 ──────────────────────────────────────────────────────────

    // ── 上传记录与表头别名（TDD-元器件-库存上传二期）───────────────────────

    @Test
    @DisplayName("ac19d ac22 ★★★ 运营看某家的上传记录；下载原件带原名；原件清理后回 90018；没权限 401/403")
    void ac19d_ac22_opsSeesBatchesAndDownloadsOriginal() throws Exception {
        Sup s = supplier("12600949101", "记录查看电子", "型号,数量\nOPB1X,100\n");
        String ops = ops();
        JsonNode list = data(get("/elec/ops/supplier/" + s.no + "/batch"), ops, null);
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("status").asString()).isEqualTo("APPLIED");
        assertThat(list.get(0).get("fileAvailable").asBoolean()).isTrue();
        String bn = list.get(0).get("batchNo").asString();

        MockHttpServletResponse res = raw(get("/elec/ops/supplier/" + s.no + "/batch/" + bn + "/file"), ops);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("Content-Disposition")).contains("filename*=UTF-8''s.csv");
        assertThat(res.getContentAsString(StandardCharsets.UTF_8)).startsWith("型号,数量");

        batchMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<ElcStockBatch>lambdaUpdate()
                .eq(ElcStockBatch::getBatchNo, bn).set(ElcStockBatch::getFilePurgedAt, LocalDateTime.now()));
        assertThat(raw(get("/elec/ops/supplier/" + s.no + "/batch/" + bn + "/file"), ops)
                .getContentAsString()).contains("90018");
        assertThat(data(get("/elec/ops/supplier/" + s.no + "/batch"), ops, null).get(0).get("fileAvailable")
                .asBoolean()).isFalse();

        String noPerm = main.operator(ElecInternal.PERM_RFQ_READ);
        assertThat(call(get("/elec/ops/supplier/" + s.no + "/batch"), noPerm, null).get("code").asInt()).isNotZero();
    }

    @Test
    @DisplayName("ac23 ★★★ 学到的别名按写法聚合给运营看；提升为全局之后，别家同样写法直接认得")
    void ac23_promoteLearnedAliasToGlobal() throws Exception {
        // 一家手工指定「货号」是料号并上架 → 学成他自己的
        String token = main.consumer("12600949102");
        data(post("/elec/b/supplier"), token, "{\"companyName\":\"学写法电子\"}");
        JsonNode pv = upload(token, "货号,数量\nOPC1X,5\n");
        assertThat(pv.get("status").asString()).isEqualTo("NEED_MAPPING");
        data(post("/elec/b/stock/batch/" + pv.get("batchNo").asString() + "/remap"), token,
                "{\"columns\":{\"MPN\":0,\"QTY\":1}}");
        data(post("/elec/b/stock/batch/" + pv.get("batchNo").asString() + "/apply"), token, null);

        String ops = ops();
        JsonNode learned = data(get("/elec/ops/header-alias").param("scope", "LEARNED").param("keyword", "货号"),
                ops, null);
        assertThat(learned.size()).isEqualTo(1);
        assertThat(learned.get(0).get("field").asString()).isEqualTo("MPN");
        assertThat(learned.get(0).get("supplierCount").asInt()).isEqualTo(1);

        // 别家还不认得
        String other = main.consumer("12600949103");
        data(post("/elec/b/supplier"), other, "{\"companyName\":\"别家电子\"}");
        assertThat(upload(other, "货号,数量\nOPC2X,5\n").get("status").asString()).isEqualTo("NEED_MAPPING");

        JsonNode g = data(post("/elec/ops/header-alias"), ops, "{\"alias\":\"货号\",\"field\":\"MPN\"}");
        assertThat(g.get("source").asString()).isEqualTo("OPS");
        assertThat(upload(other, "货号,数量\nOPC2X,5\n").get("status").asString()).as("提升后当场生效")
                .isEqualTo("PARSED");

        // 停用之后又不认了
        data(put("/elec/ops/header-alias/" + g.get("id").asLong()), ops, "{\"status\":\"DISABLED\"}");
        assertThat(upload(other, "货号,数量\nOPC2X,5\n").get("status").asString()).isEqualTo("NEED_MAPPING");
        assertThat(call(post("/elec/ops/header-alias"), ops, "{\"alias\":\"货号\",\"field\":\"NOPE\"}")
                .get("code").asInt()).isEqualTo(10400);
    }

    private JsonNode upload(String token, String csv) throws Exception {
        String body = mvc().perform(multipart("/elec/b/stock/upload")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        assertThat(r.get("code").asInt()).as(body).isZero();
        return r.get("data");
    }

    private MockHttpServletResponse raw(MockHttpServletRequestBuilder req, String token) throws Exception {
        req.header("Authorization", "Bearer " + token);
        return mvc().perform(req).andReturn().getResponse();
    }

    private record Sup(String token, String no) {
    }

    /** 入驻 + 上传一张 CSV 并确认上架 */
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

    /** 买家搜这个料号时，第一条的买家面投影；搜不到或没有投影为 null */
    private JsonNode buyerMarket(String mpn) throws Exception {
        JsonNode hits = data(get("/elec/c/part").param("keyword", mpn), null, null).get("hits");
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        JsonNode m = hits.get(0).get("market");
        return absent(m) ? null : m;
    }

    private JsonNode opsPart(String mpn) throws Exception {
        return data(get("/elec/ops/part").param("q", mpn), ops(), null).get(0);
    }

    private int demand(String keyword) {
        List<ElcSearchDaily> rows = new ArrayList<>(dailyMapper.selectList(Wrappers.<ElcSearchDaily>lambdaQuery()
                .eq(ElcSearchDaily::getKeyword, keyword)));
        return rows.stream().mapToInt(r -> r.getSearchCnt() == null ? 0 : r.getSearchCnt()).sum();
    }

    private static JsonNode find(JsonNode arr, String field, String value) {
        for (JsonNode n : arr) {
            if (value.equals(n.path(field).asString())) {
                return n;
            }
        }
        return null;
    }

    private static boolean absent(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
    }

    private int code(MockHttpServletRequestBuilder req, String token) throws Exception {
        return call(req, token, null).get("code").asInt();
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
