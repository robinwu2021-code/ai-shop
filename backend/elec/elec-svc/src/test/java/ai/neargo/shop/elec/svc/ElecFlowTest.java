package ai.neargo.shop.elec.svc;

import ai.neargo.shop.elec.entity.ElcRfq;
import ai.neargo.shop.elec.entity.ElcSearchDaily;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.mapper.ElecMappers.RfqMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 元器件第一步的整条链路：供应商入驻 → 上传库存（预览 → 确认）→ 买家查料号 → 询价。
 *
 * <p>跑的是<b>独立进程</b>（ElecApplication）：真的令牌过滤器、真的独立库（H2 跑 db/elec 重放出来的迁移）。
 * 只有「网络对面的主系统」是替身（{@link FakeMainSystem}，顶在 HTTP 客户端那一层）。
 *
 * <p>每个用例用自己的料号前缀与手机号：H2 库在同一次 mvn 里是共享的。
 */
@SpringBootTest(classes = ElecApplication.class)
@ActiveProfiles("test")
@Import(FakeMainSystem.Config.class)
class ElecFlowTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private FakeMainSystem main;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private RfqMapper rfqMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ── 供应商入驻 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 没绑手机号不能入驻供应商 —— 平台要打得通他的电话")
    void registerNeedsPhone() throws Exception {
        String wxOnly = main.consumerWithoutPhone();
        JsonNode r = call(post("/elec/b/supplier"), wxOnly, "{\"companyName\":\"无号电子\",\"contactName\":\"甲\"}");
        assertThat(r.get("code").asInt()).isEqualTo(90001);
    }

    @Test
    @DisplayName("★★★ 点一下就成为供应商（不填表）；联系手机是绑定的号；之后补资料；再点一次被拒")
    void oneClickSupplierThenProfile() throws Exception {
        String user = main.consumer("12600910001");
        assertThat(absent(data(get("/elec/b/supplier"), user))).as("还没成为供应商时 data 是 null，不是 404").isTrue();

        JsonNode s = data(post("/elec/b/supplier"), user);
        assertThat(s.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(s.get("contactPhone").asString()).as("联系手机默认是绑定的号").isEqualTo("12600910001");
        assertThat(absent(s.path("companyName"))).as("公司名可以之后再补").isTrue();
        assertThat(s.get("maskCode").asString()).startsWith("S-");

        JsonNode after = data(put("/elec/b/supplier"), user,
                "{\"companyName\":\"深圳测试电子有限公司\",\"kind\":\"AGENT\",\"city\":\"深圳\"}");
        assertThat(after.get("companyName").asString()).isEqualTo("深圳测试电子有限公司");
        assertThat(after.get("kind").asString()).isEqualTo("AGENT");
        assertThat(data(get("/elec/b/supplier"), user).get("city").asString()).isEqualTo("深圳");

        assertThat(call(post("/elec/b/supplier"), user, null).get("code").asInt()).isEqualTo(90003);
    }

    @Test
    @DisplayName("★★ 企业微信没配时入驻照样成功，notified_at 留空 —— 没送到的在库里查得出来")
    void notificationFailureDoesNotRollBack() throws Exception {
        String user = main.consumer("12600910002");
        String no = data(post("/elec/b/supplier"), user,
                "{\"companyName\":\"没通知电子\",\"contactName\":\"李工\"}").get("supplierNo").asString();
        ElcSupplier row = supplierMapper.selectOne(Wrappers.<ElcSupplier>lambdaQuery()
                .eq(ElcSupplier::getSupplierNo, no));
        assertThat(row).as("入驻落库了").isNotNull();
        assertThat(row.getNotifiedAt()).as("测试里没配 webhook，送不到就该是空").isNull();
    }

    // ── 上传库存 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 上传预览一行库存都不动；确认之后买家搜得到，且买家的响应里没有任何供应商信息")
    void uploadPreviewApplyThenBuyerSeesBandsOnly() throws Exception {
        String p = prefix();
        String user = supplier("12600910003", "保密电子有限公司");
        String csv = "型号,品牌,数量,批号,封装,单价\n"
                + p + "A1,TI,12345,2338,SOT-23,6.2\n"
                + p + "A2,德州仪器,800,23+,QFN,\n"
                + "电阻,,100,,,\n"
                + p + "A3,TI,abc,,,\n";
        JsonNode pv = upload(user, "库存.csv", csv.getBytes(StandardCharsets.UTF_8), "MERGE");
        assertThat(pv.get("rowTotal").asInt()).isEqualTo(4);
        assertThat(pv.get("rowValid").asInt()).isEqualTo(2);
        assertThat(pv.get("toInsert").asInt()).isEqualTo(2);
        assertThat(pv.get("problems").toString()).contains("MPN_INVALID").contains("QTY_INVALID");
        assertThat(pv.get("columns").get("MPN").asInt()).isZero();
        assertThat(countStock(p)).as("预览阶段一行库存都不许写").isZero();

        JsonNode applied = data(post("/elec/b/stock/batch/" + pv.get("batchNo").asString() + "/apply"), user);
        assertThat(applied.get("status").asString()).isEqualTo("APPLIED");
        assertThat(countStock(p)).isEqualTo(2);

        String raw = mvc().perform(get("/elec/c/part").param("keyword", p.toLowerCase() + "-a"))
                .andReturn().getResponse().getContentAsString();
        JsonNode hits = json.readTree(raw).get("data").get("hits");
        assertThat(hits.size()).as("小写加横杠也要搜得到（规范化之后比较）").isEqualTo(2);
        JsonNode a1 = hits.get(0);
        assertThat(a1.get("mpn").asString()).isEqualTo(p + "A1");
        assertThat(a1.get("mfrName").asString()).isEqualTo("德州仪器");
        assertThat(a1.get("market").get("qtyBand").asString()).isEqualTo("B10K");
        assertThat(a1.get("market").get("sourceBand").asString()).isEqualTo("ONE");
        assertThat(a1.get("market").get("dcYearMax").asInt()).isEqualTo(2023);
        // 6.2 元 + 8% = 6.696 元
        assertThat(a1.get("market").get("priceFromE6").asLong()).isEqualTo(6_696_000L);
        assertThat(hits.get(1).get("mfrName").asString()).as("两种厂牌写法认到同一家").isEqualTo("德州仪器");
        assertThat(absent(hits.get(1).get("market").path("priceFromE6"))).as("没报价的就是空").isTrue();

        // 买家看到的整份响应里，不能出现供应商的任何可识别信息，也不能有精确数量与批号。
        // 先摘掉 partNo：它是时间戳编号，碰巧含 2338 这类数字会让下面的断言误红
        hits.forEach(h -> ((tools.jackson.databind.node.ObjectNode) h).remove("partNo"));
        assertThat(raw).doesNotContain("保密电子").doesNotContain("12600910003");
        assertThat(hits.toString())
                .doesNotContain("supplierNo").doesNotContain("maskCode")
                .doesNotContain("12345").doesNotContain("2338");
    }

    @Test
    @DisplayName("★★★ xlsx 也认：共享字符串、数字格、表头不在第一行")
    void xlsxUpload() throws Exception {
        String p = prefix();
        String user = supplier("12600910004", "表格电子");
        byte[] xlsx = xlsx(new String[][]{
                {"2026 年 9 月库存表"},
                {"P/N", "Brand", "QTY", "D/C"},
                {p + "X1", "ST", "10000", "2410"},
                {p + "X2", "Murata", "5K", ""}});
        JsonNode pv = upload(user, "stock.xlsx", xlsx, "MERGE");
        assertThat(pv.get("rowValid").asInt()).isEqualTo(2);
        assertThat(pv.get("headers").toString()).contains("P/N");
        data(post("/elec/b/stock/batch/" + pv.get("batchNo").asString() + "/apply"), user);

        JsonNode hit = hits(p + "X2").get(0);
        assertThat(hit.get("mfrName").asString()).isEqualTo("村田");
        assertThat(hit.get("market").get("qtyBand").asString()).as("5K = 5000").isEqualTo("B1K");
    }

    @Test
    @DisplayName("★★★ 全量替换：表里没有的行下架，预览里先把要下架的列出来；下架后买家看不到")
    void replaceDelistsMissingRows() throws Exception {
        String p = prefix();
        String user = supplier("12600910005", "替换电子");
        applyCsv(user, "型号,数量\n" + p + "R1,100\n" + p + "R2,200\n", "MERGE");

        JsonNode pv = upload(user, "b.csv", ("型号,数量\n" + p + "R1,150\n").getBytes(StandardCharsets.UTF_8),
                "REPLACE");
        assertThat(pv.get("toDelist").asInt()).isEqualTo(1);
        assertThat(pv.get("delistSample").toString()).contains(p + "R2");
        assertThat(pv.get("toUpdate").asInt()).isEqualTo(1);
        data(post("/elec/b/stock/batch/" + pv.get("batchNo").asString() + "/apply"), user);

        JsonNode r2 = hits(p + "R2").get(0);
        assertThat(absent(r2.path("market"))).as("下架了，料号还在但没货").isTrue();
        JsonNode r1 = hits(p + "R1").get(0);
        assertThat(r1.get("market").get("qtyBand").asString()).isEqualTo("B100");
    }

    @Test
    @DisplayName("★★ 全量替换传了一张没有有效行的表：拒绝确认，不许把他的库存全部下架")
    void replaceWithNoValidRowsIsRefused() throws Exception {
        String p = prefix();
        String user = supplier("12600910006", "空表电子");
        applyCsv(user, "型号,数量\n" + p + "E1,100\n", "MERGE");
        JsonNode pv = upload(user, "empty.csv", "型号,数量\n电阻,abc\n".getBytes(StandardCharsets.UTF_8), "REPLACE");
        JsonNode r = call(post("/elec/b/stock/batch/" + pv.get("batchNo").asString() + "/apply"), user, null);
        assertThat(r.get("code").asInt()).isEqualTo(90009);
        assertThat(countStock(p)).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 增量上传里空着的价格格子 = 不改：原价与它的含税口径都不动")
    void blankCellKeepsOldValue() throws Exception {
        String p = prefix();
        String user = supplier("12600910007", "空格电子");
        // 第一次：未税 1.5 元
        JsonNode first = uploadWith(user, "型号,数量,单价\n" + p + "K1,100,1.5\n", "MERGE", false);
        data(post("/elec/b/stock/batch/" + first.get("batchNo").asString() + "/apply"), user);
        // 第二次：价格空着，这张表按含税（默认）上传
        applyCsv(user, "型号,数量,单价\n" + p + "K1,300,\n", "MERGE");
        ElcStock row = stockMapper.selectOne(Wrappers.<ElcStock>lambdaQuery().likeRight(ElcStock::getMpnNorm, p));
        assertThat(row.getQty()).isEqualTo(300L);
        assertThat(row.getPriceE6()).as("价格格子空着，保留原价").isEqualTo(1_500_000L);
        /*
         * 光验价格不够：updateById 本来就跳过 null，价格那一半即使没有「空格子不改」的分支也保得住
         * （消融时这里没变红，才补了下面这句）。真正会出错的是口径 ——
         * 价没改、口径却跟着这张表变成含税，1.5 元未税就悄悄成了 1.5 元含税，买家看到的参考价少一成三。
         */
        assertThat(row.getTaxIncluded()).as("没改价，就不许改这个价的含税口径").isFalse();
    }

    @Test
    @DisplayName("★★ GBK 编码的 csv（中文版 Excel 另存为的默认）表头也认得出")
    void gbkCsv() throws Exception {
        String p = prefix();
        String user = supplier("12600910008", "编码电子");
        JsonNode pv = upload(user, "gbk.csv",
                ("型号,库存数量\n" + p + "G1,100\n").getBytes(Charset.forName("GBK")), "MERGE");
        assertThat(pv.get("rowValid").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ .xls 与没有表头的表直接说清楚，不是 500")
    void unreadableFiles() throws Exception {
        String user = supplier("12600910009", "格式电子");
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0};
        assertThat(uploadRaw(user, "old.xls", ole, "MERGE").get("code").asInt()).isEqualTo(90005);
        assertThat(uploadRaw(user, "x.csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), "MERGE")
                .get("code").asInt()).isEqualTo(90006);
    }

    @Test
    @DisplayName("★★ 不是供应商的人不能上传")
    void nonSupplierCannotUpload() throws Exception {
        String user = main.consumer("12600910010");
        assertThat(uploadRaw(user, "a.csv", "型号,数量\nX123,1\n".getBytes(StandardCharsets.UTF_8), "MERGE")
                .get("code").asInt()).isEqualTo(90002);
    }

    @Test
    @DisplayName("★★★ 库存到期后买家就看不到了（读的时候重算，不靠定时任务）；续期后又回来")
    void expiredStockHiddenThenRenewed() throws Exception {
        String p = prefix();
        String user = supplier("12600910011", "到期电子");
        applyCsv(user, "型号,数量\n" + p + "T1,5000\n", "MERGE");
        assertThat(absent(hits(p).get(0).path("market"))).isFalse();

        // 把这行库存改成昨天到期，并把投影的到期时刻拨到过去 —— 模拟「过了一个月没人管」
        ElcStock patch = new ElcStock();
        patch.setValidUntil(LocalDate.now().minusDays(1));
        stockMapper.update(patch, Wrappers.<ElcStock>lambdaUpdate().likeRight(ElcStock::getMpnNorm, p));
        expireMarketOf(p);

        assertThat(absent(hits(p).get(0).path("market")))
                .as("到期的库存不能再给买家看").isTrue();
        JsonNode expired = data(get("/elec/b/stock").param("filter", "EXPIRED"), user);
        assertThat(expired.get(0).get("status").asString()).isEqualTo("EXPIRED");

        JsonNode renewed = data(post("/elec/b/stock/renew"), user);
        assertThat(renewed.get("renewed").asInt()).isEqualTo(1);
        assertThat(absent(hits(p).get(0).path("market"))).isFalse();
    }

    // ── 询价 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 询价：游客 401，没绑手机号 90001，绑了就能提交并在「我的询价」里看到")
    void rfqNeedsLoginAndPhone() throws Exception {
        String p = prefix();
        String body = "{\"lines\":[{\"mpn\":\"" + p + "Q1\",\"qty\":2000}],\"needInvoice\":\"VAT_SPECIAL\"}";

        int anon = mvc().perform(post("/elec/c/rfq").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
        assertThat(anon).isEqualTo(401);

        String wxOnly = main.consumerWithoutPhone();
        assertThat(call(post("/elec/c/rfq"), wxOnly, body).get("code").asInt()).isEqualTo(90001);

        String buyer = main.consumer("12600910012");
        JsonNode rfq = data(post("/elec/c/rfq"), buyer, body);
        assertThat(rfq.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(rfq.get("contactPhone").asString()).as("回给买家的是掩码号").doesNotContain("12600910012");
        assertThat(rfq.get("lines").get(0).get("qty").asLong()).isEqualTo(2000L);

        ElcRfq row = rfqMapper.selectOne(Wrappers.<ElcRfq>lambdaQuery()
                .eq(ElcRfq::getRfqNo, rfq.get("rfqNo").asString()));
        assertThat(row.getContactPhone()).as("库里存完整号码，运营要打").isEqualTo("12600910012");

        JsonNode mine = data(get("/elec/c/rfq"), buyer);
        assertThat(mine.get(0).get("rfqNo").asString()).isEqualTo(rfq.get("rfqNo").asString());

        String other = main.consumer("12600910013");
        assertThat(call(get("/elec/c/rfq/" + rfq.get("rfqNo").asString()), other, null).get("code").asInt())
                .as("别人的询价单是 404，不告诉他存在").isEqualTo(10404);
    }

    @Test
    @DisplayName("★★ 询价行数与数量越界时拒绝，文案里带上限")
    void rfqLinesValidated() throws Exception {
        String buyer = main.consumer("12600910014");
        JsonNode r = call(post("/elec/c/rfq"), buyer, "{\"lines\":[{\"mpn\":\"ABC123\",\"qty\":0}]}");
        assertThat(r.get("code").asInt()).isEqualTo(90010);
        assertThat(r.get("msg").asString()).contains("50");
        assertThat(call(post("/elec/c/rfq"), buyer, "{\"lines\":[]}").get("code").asInt()).isEqualTo(90010);
    }

    // ── 阶梯价与货品口径 ────────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 阶梯价：表头就是数量档（1-99 / 100+ / 1000+）时逐档收下；买家看到的起价是最便宜那档，并写明从多少起")
    void priceTiersFromHeaders() throws Exception {
        String p = prefix();
        String user = supplier("12600910020", "阶梯电子");
        String csv = "型号,数量,1-99,100-999,1000+\n" + p + "T1,5000,8.20,7.35,6.85\n";
        applyCsv(user, csv, "MERGE");

        JsonNode mine = data(get("/elec/b/stock"), user).get(0);
        assertThat(mine.get("tiers").size()).isEqualTo(3);
        assertThat(mine.get("tiers").get(0).get("minQty").asLong()).isEqualTo(1L);
        assertThat(mine.get("tiers").get(0).get("priceE6").asLong()).isEqualTo(8_200_000L);
        assertThat(mine.get("tiers").get(2).get("minQty").asLong()).isEqualTo(1000L);
        assertThat(mine.get("priceE6").asLong()).as("冗余出来的是最低档（minQty 最小那档）").isEqualTo(8_200_000L);

        JsonNode market = hits(p + "T1").get(0).get("market");
        // 最便宜的一档是 6.85（从 1000 起）；加价 8% → 7.398
        assertThat(market.get("priceFromE6").asLong()).isEqualTo(7_398_000L);
        assertThat(market.get("priceFromQty").asLong())
                .as("有了阶梯价就必须说这个价从多少起，否则按 10 片来询的人会觉得被坑").isEqualTo(1000L);
    }

    @Test
    @DisplayName("★★★ 货况、包装、交期、货源地各种写法都认得出；买家面只给「有哪些货况」与「有没有现货」")
    void conditionPackingLead() throws Exception {
        String p = prefix();
        String user = supplier("12600910021", "货况电子");
        String csv = "型号,数量,单价,品质,包装,交期,货源地\n"
                + p + "C1,500,6.20,全新原装,编带,现货,深圳\n"
                + p + "C2,800,5.10,原装散新,剪切带,7天,香港\n";
        applyCsv(user, csv, "MERGE");

        JsonNode mine = data(get("/elec/b/stock").param("keyword", p + "C1"), user).get(0);
        assertThat(mine.get("cond").asString()).isEqualTo("ORIGINAL");
        assertThat(mine.get("packing").asString()).isEqualTo("REEL");
        assertThat(mine.get("leadDays").asInt()).isZero();
        assertThat(mine.get("region").asString()).isEqualTo("深圳");

        JsonNode m1 = hits(p + "C1").get(0).get("market");
        assertThat(m1.get("spot").asBoolean()).isTrue();
        assertThat(m1.get("leadDaysMin").asInt()).isZero();
        assertThat(m1.get("conds").toString()).contains("ORIGINAL");
        JsonNode m2 = hits(p + "C2").get(0).get("market");
        assertThat(m2.get("spot").asBoolean()).as("7 天交期不是现货").isFalse();
        assertThat(m2.get("leadDaysMin").asInt()).isEqualTo(7);
        assertThat(m2.get("conds").toString()).contains("LOOSE").doesNotContain("ORIGINAL");
    }

    @Test
    @DisplayName("★★★ 美元报价换算成人民币含税再给买家 —— 不换的话 $6 会被当成 ¥6 显示")
    void foreignCurrencyConverted() throws Exception {
        String p = prefix();
        String user = supplier("12600910022", "美元电子");
        applyCsv(user, "型号,数量,单价,币种\n" + p + "U1,100,1.00,USD\n", "MERGE");
        long shown = hits(p + "U1").get(0).get("market").get("priceFromE6").asLong();
        // $1 × 7.1 = ¥7.1，再加 8% 加价 = ¥7.668
        assertThat(shown).isEqualTo(7_668_000L);
        assertThat(shown).as("没换算的话会是 ¥1 出头").isGreaterThan(5_000_000L);
    }

    @Test
    @DisplayName("★★ 未税价换算成含税再比：同一个数字，未税的那条其实更贵")
    void untaxedPriceIsConverted() throws Exception {
        String p = prefix();
        String user = supplier("12600910023", "未税电子");
        JsonNode pv = uploadWith(user, "型号,数量,单价\n" + p + "N1,100,10.00\n", "MERGE", false);
        data(post("/elec/b/stock/batch/" + pv.get("batchNo").asString() + "/apply"), user);
        // ¥10 未税 → ×1.13 = ¥11.3，再加 8% = ¥12.204
        assertThat(hits(p + "N1").get(0).get("market").get("priceFromE6").asLong()).isEqualTo(12_204_000L);
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 中段也搜得到：只记得「F103C8」「C8T6」的采购能找到 STM32F103C8T6；开头一致的排在中段一致之前")
    void containsSearch() throws Exception {
        String p = prefix();
        String user = supplier("12600910015", "中段电子");
        // 料号里带上唯一前缀，中段那截也带上唯一标记，免得撞到别的用例的料号
        String full = p + "F103C8T6";
        applyCsv(user, "型号,品牌,数量\n" + full + ",ST,500\n", "MERGE");
        // 从料号中间的数字段搜起（去掉开头的 ZT），靠的是字母/数字交界处切出来的分段键
        JsonNode byMid = search(p.substring(2) + "F103", false);
        assertThat(byMid.get("hits").get(0).get("mpn").asString()).isEqualTo(full);
        assertThat(byMid.get("hits").get(0).get("match").asString()).isEqualTo("CONTAINS");

        JsonNode byHead = search(full, false);
        assertThat(byHead.get("hits").get(0).get("match").asString()).isEqualTo("EXACT");
    }

    @Test
    @DisplayName("★★★ 输入里带厂牌就按厂牌过滤：「TI xxx」只出 TI 的，「ST xxx」只出 ST 的")
    void brandInQueryFilters() throws Exception {
        String p = prefix();
        String user = supplier("12600910016", "双厂牌电子");
        applyCsv(user, "型号,品牌,数量\n" + p + "B1,TI,100\n" + p + "B1,ST,200\n", "MERGE");
        assertThat(search(p + "B1", false).get("hits").size()).as("同一料号串两家厂牌，都出").isEqualTo(2);
        JsonNode ti = search("TI " + p.toLowerCase() + "b1", false);
        assertThat(ti.get("mfrFilter").asString()).isEqualTo("德州仪器");
        assertThat(ti.get("hits").size()).isEqualTo(1);
        assertThat(ti.get("hits").get(0).get("mfrName").asString()).isEqualTo("德州仪器");
    }

    @Test
    @DisplayName("★★★ 多敲了卷带后缀也不给一片空白：退到更短的前缀，标成近似")
    void nearFallback() throws Exception {
        String p = prefix();
        String user = supplier("12600910017", "近似电子");
        applyCsv(user, "型号,数量\n" + p + "N1,100\n", "MERGE");
        JsonNode r = search(p + "N1TR", false);
        assertThat(r.get("nearFrom").asString()).isEqualTo(p + "N1");
        assertThat(r.get("hits").get(0).get("match").asString()).isEqualTo("NEAR");
        assertThat(absent(search(p + "N1", false).path("nearFrom"))).as("原词就命中时不是近似").isTrue();
    }

    @Test
    @DisplayName("★★★ 有货的排在没货的前面（同一命中档内）")
    void inStockRanksFirst() throws Exception {
        String p = prefix();
        String user = supplier("12600910018", "排序电子");
        // S2 有货；S1 上架后被全量替换下架 → 料号还在但没货
        applyCsv(user, "型号,数量\n" + p + "S1,100\n", "MERGE");
        applyCsv(user, "型号,数量\n" + p + "S2,100\n", "REPLACE");
        JsonNode hits = search(p + "S", false).get("hits");
        assertThat(hits.get(0).get("mpn").asString()).isEqualTo(p + "S2");
        assertThat(absent(hits.get(1).path("market"))).isTrue();
    }

    @Test
    @DisplayName("★★★ 批量查：粘一列料号（可带厂牌与数量），逐行给出库里有没有")
    void batchLookup() throws Exception {
        String p = prefix();
        String user = supplier("12600910019", "批量电子");
        applyCsv(user, "型号,品牌,数量\n" + p + "L1,TI,100\n" + p + "L2,TI,100\n" + p + "L2,ST,100\n", "MERGE");
        String text = p + "L1 2000\n" + p + "L2\nST " + p + "L2 50\n" + p + "L9\n备注：尽快\n";
        JsonNode lines = data(get("/elec/c/part/lookup").param("text", text), null);
        assertThat(lines.size()).isEqualTo(5);
        assertThat(lines.get(0).get("match").asString()).isEqualTo("EXACT");
        assertThat(lines.get(0).get("qty").asLong()).isEqualTo(2000L);
        assertThat(lines.get(1).get("match").asString()).as("两家厂牌又没写哪家").isEqualTo("AMBIGUOUS");
        assertThat(lines.get(2).get("match").asString()).as("写了厂牌就确定了").isEqualTo("EXACT");
        assertThat(lines.get(2).get("hit").get("mfrName").asString()).isEqualTo("意法半导体");
        assertThat(lines.get(3).get("match").asString()).isEqualTo("NONE");
        assertThat(lines.get(4).get("match").asString()).as("不是料号的行").isEqualTo("NONE");
    }

    @Test
    @DisplayName("★★ 搜索需求按天记：没结果的也记（那是平台该去找的货）；边打字的提示不记")
    void demandLogged() throws Exception {
        String miss = prefix() + "ZZ";
        search(miss, false);
        search(miss, false);
        search(miss, true);
        ElcSearchDaily row = dailyMapper.selectOne(Wrappers.<ElcSearchDaily>lambdaQuery()
                .eq(ElcSearchDaily::getKeyword, miss).eq(ElcSearchDaily::getStatDate, LocalDate.now()));
        assertThat(row.getSearchCnt()).as("提示那一次不算").isEqualTo(2);
        assertThat(row.getZeroCnt()).isEqualTo(2);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 每个用例一个料号前缀，互不干扰 */
    private static String prefix() {
        return "ZT" + (System.nanoTime() % 1_000_000) + "N" + SEQ.incrementAndGet();
    }

    private String supplier(String phone, String company) throws Exception {
        String user = main.consumer(phone);
        data(post("/elec/b/supplier"), user, "{\"companyName\":\"" + company + "\",\"contactName\":\"测\"}");
        return user;
    }

    private void applyCsv(String user, String csv, String mode) throws Exception {
        JsonNode pv = upload(user, "s.csv", csv.getBytes(StandardCharsets.UTF_8), mode);
        data(post("/elec/b/stock/batch/" + pv.get("batchNo").asString() + "/apply"), user);
    }

    private JsonNode upload(String user, String name, byte[] bytes, String mode) throws Exception {
        JsonNode r = uploadRaw(user, name, bytes, mode);
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.get("data");
    }

    private JsonNode uploadWith(String user, String csv, String mode, boolean taxIncluded) throws Exception {
        String body = mvc().perform(multipart("/elec/b/stock/upload")
                        .file(new MockMultipartFile("file", "t.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("mode", mode).param("taxIncluded", String.valueOf(taxIncluded))
                        .header("Authorization", "Bearer " + user))
                .andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.get("data");
    }

    private JsonNode uploadRaw(String user, String name, byte[] bytes, String mode) throws Exception {
        String body = mvc().perform(multipart("/elec/b/stock/upload")
                        .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                        .param("mode", mode)
                        .header("Authorization", "Bearer " + user))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode search(String keyword, boolean suggest) throws Exception {
        return data(get("/elec/c/part").param("keyword", keyword).param("suggest", String.valueOf(suggest)), null);
    }

    private JsonNode hits(String keyword) throws Exception {
        return search(keyword, false).get("hits");
    }

    @Autowired
    private ai.neargo.shop.elec.mapper.ElecMappers.SearchDailyMapper dailyMapper;

    private long countStock(String prefix) {
        return stockMapper.selectCount(Wrappers.<ElcStock>lambdaQuery().likeRight(ElcStock::getMpnNorm, prefix)
                .eq(ElcStock::getStatus, ElcStock.STATUS_ON));
    }

    @Autowired
    private ai.neargo.shop.elec.mapper.ElecMappers.PartMarketMapper marketMapper;
    @Autowired
    private ai.neargo.shop.elec.mapper.ElecMappers.PartMapper partMapper;

    private void expireMarketOf(String prefix) {
        for (var part : partMapper.selectList(Wrappers.<ai.neargo.shop.elec.entity.ElcPart>lambdaQuery()
                .likeRight(ai.neargo.shop.elec.entity.ElcPart::getMpnNorm, prefix))) {
            var m = new ai.neargo.shop.elec.entity.ElcPartMarket();
            m.setNextExpiryAt(java.time.LocalDateTime.now().minusMinutes(1));
            marketMapper.update(m, Wrappers.<ai.neargo.shop.elec.entity.ElcPartMarket>lambdaUpdate()
                    .eq(ai.neargo.shop.elec.entity.ElcPartMarket::getPartNo, part.getPartNo()));
        }
    }

    private JsonNode data(MockHttpServletRequestBuilder req, String token) throws Exception {
        return data(req, token, null);
    }

    private JsonNode data(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        JsonNode r = call(req, token, body);
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.path("data");
    }

    /** 响应里省略的字段与显式 null 都算「没有」（响应格式规范 §6：可选字段无值就省略） */
    private static boolean absent(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
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

    /** 最小的 xlsx：工作簿 + 关系 + 共享字符串 + 一张表。数字格走 n、文字走共享字符串 */
    static byte[] xlsx(String[][] rows) throws Exception {
        java.util.List<String> shared = new java.util.ArrayList<>();
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.length; r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < rows[r].length; c++) {
                String v = rows[r][c];
                String ref = (char) ('A' + c) + String.valueOf(r + 1);
                if (v.isEmpty()) {
                    continue;
                }
                if (v.matches("\\d+")) {
                    sheet.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
                } else {
                    shared.add(v);
                    sheet.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(shared.size() - 1)
                            .append("</v></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        StringBuilder sst = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        shared.forEach(s -> sst.append("<si><t>").append(s).append("</t></si>"));
        sst.append("</sst>");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            zipEntry(z, "xl/workbook.xml", "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/"
                    + "spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                    + "relationships\"><sheets><sheet name=\"库存\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            zipEntry(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas."
                    + "openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"worksheet\" "
                    + "Target=\"worksheets/sheet1.xml\"/></Relationships>");
            zipEntry(z, "xl/sharedStrings.xml", sst.toString());
            zipEntry(z, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return bo.toByteArray();
    }

    private static void zipEntry(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
