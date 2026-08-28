package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 平台端物流（TDD-运营端履约调度 §4.6 / §4.7，P-5.2）：运单 · 运费模板 · 运力档案。
 *
 * <p>与姊妹类 {@code OpsFulfillmentFlowTest}（P-5.1 调度那一半）分工：那个问
 * 「看板数字与真实订单是不是同一份事实」，这个问 <b>「三页上的每一条闸是不是真的关得住」</b>。
 * 每条闸挡的都不是「显示不对」，而是<b>订单发不出去 / 轨迹对不上</b>：
 * <ol>
 *   <li><b>运单记录从真实订单补齐</b> —— 快递履约且已回填单号的子单才建；没回填的不建。
 *       撤掉补齐这一段，{@code /ops/shipments} 会永远是空的，
 *       而接口 200、页面「暂无数据」，控制台一条错误都没有</li>
 *   <li><b>换单号三条闸</b>：已签收不许改（把一条已完成的轨迹指向别处）、
 *       同承运商不许重号（两单轨迹搅在一起）、原因必填（之后对不上时唯一的线索）</li>
 *   <li><b>默认运费模板恰好一个、且不可归档</b> —— 归档之后新商家没有模板可用</li>
 *   <li><b>运力四条闸</b>：优先级不可撞、没配密钥不可启用、有在途单不可停用、
 *       不可停掉最后一家</li>
 * </ol>
 *
 * <h2>为什么要 {@code @Order}</h2>
 *
 * <p>「不可停掉最后一家」这条闸要求目标运力名下<b>一张在途单都没有</b> ——
 * 因为停用校验先看在途单（30011）再看是不是最后一家（30012）。
 * 而运单记录是<b>读时补齐</b>的：本类只要读过一次 {@code /ops/shipments}，
 * 库里所有已发货的快递单就都挂到了当时优先级最高的那家（SF）名下。
 * 所以这一条必须排在任何运单用例之前跑，否则它会拿到 30011 —— 那不是缺陷，是用例自己踩了自己。
 *
 * <p>手机号段 {@code 126006xxxxx}（商户）/ {@code 130006xxxxx}（买家）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpsLogisticsFlowTest {

    /** 与 M9aOpsFlowTest 同一个：stub 回调的签名，配在 application-test.yml */
    private static final String STUB_SECRET = "stub-secret";

    /** 种子里的默认运费模板（schema-test.sql）。本类会改默认位，跑完必须还原成它。 */
    private static final String SEED_TEMPLATE = "FT0001";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

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

    /**
     * 运力档案与默认运费模板都是<b>全平台单例配置</b>。本类会停用运力、挪默认位，
     * 跑完必须原样放回去 —— 否则后面的用例（以及并行跑的邻居类）看到的
     * 「默认模板」「有几家启用的运力」是本类造出来的那一份。
     */
    @AfterEach
    void restoreGlobalSingletons() throws Exception {
        setEnabledRaw("SF", true);
        setEnabledRaw("JD", true);
        saveCarrierRaw("SF", "顺丰速运", 1, "17:00", 48);
        saveCarrierRaw("JD", "京东物流", 2, "16:30", 72);
        saveTemplateRaw("{\"templateNo\":\"" + SEED_TEMPLATE + "\",\"name\":\"默认运费模板\","
                + "\"firstWeightGram\":1000,\"firstFee\":800,\"addWeightGram\":500,\"addFee\":200,"
                + "\"freeThreshold\":9900,\"isDefault\":true,\"outOfRange\":["
                + "{\"region\":\"新疆维吾尔自治区\",\"action\":\"SURCHARGE\",\"surcharge\":2000},"
                + "{\"region\":\"西藏自治区\",\"action\":\"REJECT\",\"surcharge\":0}]}");
    }

    // ---------------------------------------------------------------- 运力：最后一家（必须最先跑）

    @Test
    @Order(1)
    @DisplayName("★ 不能停掉最后一家启用的运力 —— 全停之后快递单无处可下")
    void cannotDisableTheLastEnabledCarrier() throws Exception {
        /*
         * 先停 SF。此刻本类还没读过 /ops/shipments，库里一条运单记录都没有，
         * 所以 SF 名下没有在途单，这一步该成功。
         * 它要是返回 30011，说明有别的路径先把运单补齐了 —— 断言会当场说清楚。
         */
        assertThat(codeOf(setEnabledRaw("SF", false)))
                .as("此刻不该有任何在途单，停用 SF 应当成功（拿到 30011 = 运单已被别处补齐）")
                .isZero();

        // 现在只剩 JD 一家启用的。停掉它 = 之后所有快递单都发不出去
        assertThat(codeOf(setEnabledRaw("JD", false))).isEqualTo(30012);

        // 闸没有把 JD 的状态改坏 —— 拒绝之后它必须还是启用的
        assertThat(carrier("JD").get("enabled").asBoolean())
                .as("被拒的停用不能留下副作用").isTrue();
    }

    // ---------------------------------------------------------------- 运单：读时补齐（§4.6）

    @Test
    @Order(2)
    @DisplayName("★ 运单记录从真实订单补齐：回填了单号的才建，没回填的不建，重复读不重复建")
    void shipmentsMaterializeFromRealOrders() throws Exception {
        String biz = merchant("12600060001", "物流·运单补齐店");
        String goodsNo = listedGoods(biz, 50);
        String skuNo = firstSku(goodsNo);

        String shipped = expressOrder("13000060001", goodsNo, skuNo);
        String notShipped = expressOrder("13000060002", goodsNo, skuNo);
        assertThat(codeOf(shipRaw(biz, shipped, "SFLOG-A1"))).isZero();

        JsonNode page = shipmentsPage();
        JsonNode row = rowByOrder(page, shipped);
        assertThat(row).as("快递履约 + 已回填单号的子单必须补出一条运单记录").isNotNull();
        assertThat(row.get("carrier").asString())
                .as("承运商取当时优先级最高的启用运力并快照").isEqualTo("SF");
        assertThat(row.get("waybillNo").asString()).isEqualTo("SFLOG-A1");
        // 订单已推进到履约中 → 运单在途。状态由订单状态推导，一期不接承运商回传
        assertThat(row.get("status").asString()).isEqualTo("IN_TRANSIT");
        /*
         * 平台侧主键不是快递单号。分成两个键是因为换单号时运单记录必须还是同一条，
         * 否则轨迹会断在换单那一刻。
         */
        assertThat(row.get("shipmentNo").asString()).isNotEqualTo("SFLOG-A1");
        assertThat(row.get("traces")).as("traces 是必填数组，端上直接 map").isNotNull();

        assertThat(rowByOrder(page, notShipped))
                .as("没回填单号的不建 —— 那种单还没发货，建出来是一条永远没有轨迹的空记录").isNull();

        // 幂等：再读一次既不多一条，也不会因为唯一键炸掉
        JsonNode again = shipmentsPage();
        assertThat(countByOrder(again, shipped)).as("读时补齐必须幂等").isEqualTo(1);
        assertThat(rowByOrder(again, shipped).get("shipmentNo").asString())
                .isEqualTo(row.get("shipmentNo").asString());

        // 筛选：承运商、状态、关键字三条都真的收敛了结果
        assertThat(rowByOrder(shipmentsPage("carrier", "SF"), shipped)).isNotNull();
        assertThat(rowByOrder(shipmentsPage("carrier", "JD"), shipped))
                .as("按 JD 筛不该筛出 SF 的单").isNull();
        assertThat(rowByOrder(shipmentsPage("status", "DELIVERED"), shipped)).isNull();
        assertThat(rowByOrder(shipmentsPage("keyword", "SFLOG-A1"), shipped)).isNotNull();
    }

    // ---------------------------------------------------------------- 运单：换单号三条闸（§4.7）

    @Test
    @Order(3)
    @DisplayName("★ 换运单号三条闸：原因必填 · 同承运商不许重号 · 已签收不许改；换号本身进轨迹")
    void waybillGates() throws Exception {
        String biz = merchant("12600060010", "物流·换单号店");
        String goodsNo = listedGoods(biz, 50);
        String skuNo = firstSku(goodsNo);

        String a = expressOrder("13000060011", goodsNo, skuNo);
        String b = expressOrder("13000060012", goodsNo, skuNo);
        shipRaw(biz, a, "SFLOG-B1");
        shipRaw(biz, b, "SFLOG-B2");

        JsonNode page = shipmentsPage();
        String shipA = rowByOrder(page, a).get("shipmentNo").asString();
        String shipB = rowByOrder(page, b).get("shipmentNo").asString();
        assertThat(rowByOrder(page, a).get("carrier").asString())
                .isEqualTo(rowByOrder(page, b).get("carrier").asString());

        // 原因必填：之后对不上时这是唯一线索，而用户可能已拿着旧号在查件
        assertThat(codeOf(waybillRaw(shipA, "SFLOG-B9", "   "))).isEqualTo(10400);
        // 单号本身也不能空 —— 空号的「已换单」比不换更糟
        assertThat(codeOf(waybillRaw(shipA, "  ", "打错了"))).isEqualTo(10400);

        // 同承运商下不许重号：两单的轨迹会搅在一起，之后谁也说不清
        assertThat(codeOf(waybillRaw(shipA, "SFLOG-B2", "手抄成隔壁那单的号")))
                .isEqualTo(30007);

        // 合法换号
        JsonNode changed = data(waybillRaw(shipA, "SFLOG-B1X", "面单打印重了"));
        assertThat(changed.get("waybillNo").asString()).isEqualTo("SFLOG-B1X");
        /*
         * 换号本身要进轨迹。不写的话，之后看到的是一条凭空换了单号的运单 ——
         * 而「为什么变了」正是客服第一个要回答的问题。
         */
        List<String> texts = traceTexts(changed);
        assertThat(texts).as("换号必须留下一条轨迹：" + texts)
                .anySatisfy(t -> assertThat(t).contains("SFLOG-B1").contains("SFLOG-B1X")
                        .contains("面单打印重了"));

        // 换完之后被腾出来的旧号可以给别人用（唯一性看的是当下，不是历史）
        assertThat(codeOf(waybillRaw(shipB, "SFLOG-B1", "接手了对方的面单")))
                .isZero();

        // 已签收（DELIVERED）不许改 —— 等于把一条已完成的轨迹指向别处
        assertThat(codeOf(deliveredRaw(biz, a))).isZero();
        JsonNode afterDelivered = rowByOrder(shipmentsPage(), a);
        assertThat(afterDelivered.get("status").asString())
                .as("订单完成后运单状态必须跟着到 DELIVERED，否则这条闸永远轮不到生效")
                .isEqualTo("DELIVERED");
        assertThat(codeOf(waybillRaw(shipA, "SFLOG-B1Z", "还想再改一次"))).isEqualTo(30006);
        // 被拒之后单号不能被改掉
        assertThat(rowByOrder(shipmentsPage(), a).get("waybillNo").asString())
                .isEqualTo("SFLOG-B1X");
    }

    // ---------------------------------------------------------------- 运费模板（§4.7）

    @Test
    @Order(4)
    @DisplayName("★ 运费模板：默认恰好一个 · 默认不可归档 · 同区域不可重复 · 负值拒绝 · 归档要看得见")
    void freightTemplateGates() throws Exception {
        List<JsonNode> initial = templates(true);
        assertThat(defaultsOf(initial)).as("默认模板必须恰好一个，否则「默认是哪个」没有答案")
                .hasSize(1);

        // 默认模板不能归档：归档之后新商家没有模板可用
        assertThat(codeOf(archiveRaw(SEED_TEMPLATE))).isEqualTo(30008);

        // 负数运费存下去之后算价会得出负运费 —— 那是白送还倒贴
        assertThat(codeOf(saveTemplateRaw(templateBody(null, "负运费模板", -1, false, "")))).isEqualTo(10400);
        // 同一区域两条规则时，命中哪条取决于顺序 —— 那是随机行为，不是配置
        assertThat(codeOf(saveTemplateRaw(templateBody(null, "重复超区模板", 800, false,
                "{\"region\":\"浙江省\",\"action\":\"SURCHARGE\",\"surcharge\":500},"
                        + "{\"region\":\"浙江省\",\"action\":\"REJECT\",\"surcharge\":0}"))))
                .isEqualTo(10400);
        assertThat(byNo(templates(true), null, "负运费模板"))
                .as("被拒的保存不能落库").isNull();

        // 新建并设为默认 —— 旧的那个必须被摘掉
        JsonNode created = data(saveTemplateRaw(templateBody(null, "物流·新默认模板", 1200, true,
                "{\"region\":\"海南省\",\"action\":\"SURCHARGE\",\"surcharge\":1500}")));
        String no = created.get("templateNo").asString();
        assertThat(created.get("isDefault").asBoolean()).isTrue();
        assertThat(created.get("outOfRange")).hasSize(1);

        List<JsonNode> afterDefault = templates(true);
        assertThat(defaultsOf(afterDefault)).hasSize(1);
        assertThat(defaultsOf(afterDefault).get(0).get("templateNo").asString()).isEqualTo(no);
        assertThat(byNo(afterDefault, SEED_TEMPLATE, null).get("isDefault").asBoolean())
                .as("设新默认时旧的必须被摘掉").isFalse();

        // 它现在是默认的，同样归不了档
        assertThat(codeOf(archiveRaw(no))).isEqualTo(30008);

        // 把默认换回种子模板，新的这条才能归档
        assertThat(codeOf(saveTemplateRaw("{\"templateNo\":\"" + SEED_TEMPLATE
                + "\",\"name\":\"默认运费模板\",\"firstWeightGram\":1000,\"firstFee\":800,"
                + "\"addWeightGram\":500,\"addFee\":200,\"freeThreshold\":9900,\"isDefault\":true,"
                + "\"outOfRange\":[]}"))).isZero();
        assertThat(codeOf(archiveRaw(no))).isZero();

        // 归档不是删除：默认列表里没有它，showArchived=true 才出现，且带得回归档时刻
        assertThat(byNo(templates(false), no, null)).as("归档后不该出现在默认列表").isNull();
        JsonNode archived = byNo(templates(true), no, null);
        assertThat(archived).as("归档的模板必须还看得见 —— 硬删会把历史订单的运费依据一起抹掉")
                .isNotNull();
        assertThat(archived.get("archivedAt").isNull()).isFalse();

        // 取消归档能回来
        assertThat(codeOf(unarchiveRaw(no))).isZero();
        assertThat(byNo(templates(false), no, null)).isNotNull();
        // 别把它留给后面的用例
        archiveRaw(no);
    }

    // ---------------------------------------------------------------- 运力其余三条闸（§4.7）

    @Test
    @Order(5)
    @DisplayName("★ 运力三条闸：优先级不可撞 · 没配密钥不可启用 · 有在途单不可停用")
    void carrierGates() throws Exception {
        List<JsonNode> carriers = carriers();
        assertThat(carriers).hasSizeGreaterThanOrEqualTo(3);
        // 按优先级升序 —— 页面上的顺序就是真实的选取顺序
        for (int i = 1; i < carriers.size(); i++) {
            assertThat(carriers.get(i).get("priority").asInt())
                    .isGreaterThanOrEqualTo(carriers.get(i - 1).get("priority").asInt());
        }
        // 密钥本身不入契约，哪怕脱敏 —— 只有「配没配」这个布尔
        assertThat(carrier("YTO").get("apiKeyConfigured").asBoolean()).isFalse();
        assertThat(carriers.get(0).has("apiKey")).isFalse();

        // 优先级撞了之后「先选哪家」由数据库返回顺序决定 —— 那是随机的
        assertThat(codeOf(saveCarrierRaw("SF", "顺丰速运", 2, "17:00", 48)))
                .isEqualTo(30009);
        assertThat(carrier("SF").get("priority").asInt())
                .as("被拒的保存不能留下副作用").isEqualTo(1);

        // 合法保存能落
        assertThat(data(saveCarrierRaw("SF", "顺丰速运", 1, "18:30", 36))
                .get("slaHours").asInt()).isEqualTo(36);
        assertThat(carrier("SF").get("pickupCutoff").asString()).isEqualTo("18:30");

        // 没配密钥就启用 = 单发出去、回传接不回来，而问题要到查件时才暴露
        assertThat(codeOf(setEnabledRaw("YTO", true))).isEqualTo(30010);
        assertThat(carrier("YTO").get("enabled").asBoolean()).isFalse();

        // 造一张真的在途单（下单 → 付款 → 发货回填单号 → 读时补齐）
        String biz = merchant("12600060020", "物流·在途单店");
        String goodsNo = listedGoods(biz, 50);
        String skuNo = firstSku(goodsNo);
        String sub = expressOrder("13000060021", goodsNo, skuNo);
        shipRaw(biz, sub, "SFLOG-C1");
        assertThat(rowByOrder(shipmentsPage(), sub).get("status").asString()).isEqualTo("IN_TRANSIT");

        // 还有在途单不能停：那些单的轨迹会就此断掉
        assertThat(codeOf(setEnabledRaw("SF", false))).isEqualTo(30011);
        assertThat(carrier("SF").get("enabled").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- 装配

    private JsonNode shipmentsPage(String... kv) throws Exception {
        var req = get("/ops/shipments").param("size", "500");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            req = req.param(kv[i], kv[i + 1]);
        }
        return pageOf(req);
    }

    private JsonNode rowByOrder(JsonNode page, String subOrderNo) {
        for (JsonNode r : page.get("records")) {
            if (subOrderNo.equals(r.get("orderNo").asString())) {
                return r;
            }
        }
        return null;
    }

    private int countByOrder(JsonNode page, String subOrderNo) {
        int n = 0;
        for (JsonNode r : page.get("records")) {
            if (subOrderNo.equals(r.get("orderNo").asString())) {
                n++;
            }
        }
        return n;
    }

    private List<String> traceTexts(JsonNode shipment) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : shipment.get("traces")) {
            out.add(t.get("text").asString());
        }
        return out;
    }

    private String waybillRaw(String shipmentNo, String waybillNo, String reason) throws Exception {
        return mvc().perform(post("/ops/shipments/" + shipmentNo + "/waybill")
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"waybillNo\":\"" + waybillNo + "\",\"reason\":\"" + reason + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    // -------- 运费模板

    private List<JsonNode> templates(boolean showArchived) throws Exception {
        JsonNode page = pageOf(get("/ops/freight-templates")
                .param("showArchived", String.valueOf(showArchived)).param("size", "200"));
        List<JsonNode> out = new ArrayList<>();
        page.get("records").forEach(out::add);
        return out;
    }

    private List<JsonNode> defaultsOf(List<JsonNode> rows) {
        return rows.stream().filter(r -> r.get("isDefault").asBoolean()).toList();
    }

    /** 按模板号或名称找一条，找不到给 null。 */
    private JsonNode byNo(List<JsonNode> rows, String templateNo, String name) {
        for (JsonNode r : rows) {
            if (templateNo != null && templateNo.equals(r.get("templateNo").asString())) {
                return r;
            }
            if (name != null && name.equals(r.get("name").asString())) {
                return r;
            }
        }
        return null;
    }

    private String templateBody(String templateNo, String name, long firstFee, boolean isDefault,
                                String ranges) {
        return "{" + (templateNo == null ? "" : "\"templateNo\":\"" + templateNo + "\",")
                + "\"name\":\"" + name + "\",\"firstWeightGram\":1000,\"firstFee\":" + firstFee
                + ",\"addWeightGram\":500,\"addFee\":200,\"freeThreshold\":9900,"
                + "\"isDefault\":" + isDefault + ",\"outOfRange\":[" + ranges + "]}";
    }

    private String saveTemplateRaw(String body) throws Exception {
        return mvc().perform(post("/ops/freight-templates")
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private String archiveRaw(String templateNo) throws Exception {
        return mvc().perform(post("/ops/freight-templates/" + templateNo + "/archive")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andReturn().getResponse().getContentAsString();
    }

    private String unarchiveRaw(String templateNo) throws Exception {
        return mvc().perform(post("/ops/freight-templates/" + templateNo + "/unarchive")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andReturn().getResponse().getContentAsString();
    }

    // -------- 运力

    private List<JsonNode> carriers() throws Exception {
        String body = mvc().perform(get("/ops/fulfillment/carriers")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        List<JsonNode> out = new ArrayList<>();
        json.readTree(body).get("data").forEach(out::add);
        return out;
    }

    private JsonNode carrier(String code) throws Exception {
        for (JsonNode c : carriers()) {
            if (code.equals(c.get("carrier").asString())) {
                return c;
            }
        }
        throw new AssertionError("运力档案里没有 " + code);
    }

    private String saveCarrierRaw(String code, String name, int priority, String cutoff, int sla)
            throws Exception {
        return mvc().perform(put("/ops/fulfillment/carriers/" + code)
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"priority\":" + priority
                                + ",\"pickupCutoff\":\"" + cutoff + "\",\"slaHours\":" + sla + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String setEnabledRaw(String code, boolean enabled) throws Exception {
        return mvc().perform(post("/ops/fulfillment/carriers/" + code + "/enabled")
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":" + enabled + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    // -------- 分页壳 / 响应体

    /**
     * 分页壳 {@code {records,total}}。运营端列表页按它渲染 ——
     * 返回裸数组会被当成空页：<b>接口 200、数据几十条、页面「暂无数据」</b>。
     */
    private JsonNode pageOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + opsLogin()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("records")).as("运营端列表必须是分页壳，不是裸数组：" + body).isNotNull();
        assertThat(data.get("total").asLong())
                .as("total 与 records 对不上，列表页会翻不动")
                .isGreaterThanOrEqualTo(data.get("records").size());
        return data;
    }

    private int codeOf(String body) {
        return json.readTree(body).get("code").asInt();
    }

    private JsonNode data(String body) {
        JsonNode root = json.readTree(body);
        if (root.get("code").asInt() != 0) {
            throw new AssertionError("期望成功，实际：" + body);
        }
        return root.get("data");
    }

    // -------- 真实链路：下单 → 付款 → 发货回填单号

    /**
     * 一单快递履约的单，付款后停在待发货。
     *
     * <p><b>必须打支付回调</b>：只调 {@code /pay} 的话单还在 WAIT_PAY，发货会被状态机拒 ——
     * 而那是测试写错了不是代码错了。
     */
    private String expressOrder(String phone, String goodsNo, String skuNo) throws Exception {
        String buyer = login(phone);
        mvc().perform(post("/mp/user/community").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0001\"}"));

        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "log-" + phone)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"EXPRESS\",\"addressId\":null,\"items\":[{\"goodsNo\":\""
                                + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();

        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-log-" + phone
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));

        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + buyer))
                .andReturn().getResponse().getContentAsString();
        JsonNode row = json.readTree(list).get("data").get("records").get(0);
        assertThat(row.get("status").asString())
                .as("没打支付回调的话单会停在 WAIT_PAY").isEqualTo("PAID");
        return row.get("orderNo").asString();
    }

    /** 商家发货并回填快递单号 —— 运单记录的来源就是这一步。 */
    private String shipRaw(String bizToken, String subOrderNo, String expressNo) throws Exception {
        return mvc().perform(post("/biz/order/" + subOrderNo + "/ship")
                        .header("Authorization", "Bearer " + bizToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expressNo\":\"" + expressNo + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String deliveredRaw(String bizToken, String subOrderNo) throws Exception {
        return mvc().perform(post("/biz/order/" + subOrderNo + "/delivered")
                        .header("Authorization", "Bearer " + bizToken))
                .andReturn().getResponse().getContentAsString();
    }

    private String listedGoods(String token, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"物流测试品\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":" + stock + "}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));
        return goodsNo;
    }

    private String firstSku(String goodsNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("skus").get(0).get("skuNo").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
