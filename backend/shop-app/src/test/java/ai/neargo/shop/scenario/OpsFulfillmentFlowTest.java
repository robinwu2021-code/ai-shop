package ai.neargo.shop.scenario;

import ai.neargo.shop.fulfillment.dto.ArrivalBatchVO;
import ai.neargo.shop.fulfillment.dto.OverdueRuleVO;
import ai.neargo.shop.fulfillment.dto.RedeemStatVO;
import ai.neargo.shop.fulfillment.dto.SortingRowVO;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 平台端履约调度（TDD-运营端履约调度，P-5.1）。
 *
 * <p>这个类只问一件事：<b>平台看板上的数字，与真实订单是不是同一份事实</b>。
 * B-6.0 的原话是「都从同一份订单数据算，不另存计数器，否则总览说 3 单点进去只有 2 单」——
 * 所以这里全部走「真下单 → 真付款 → 真到货 → 真核销」，再拿看板去对：
 * <ol>
 *   <li><b>核销监控</b>：待核销 / 已核销 / 逾期三个数逐一对得上，且 {@code rate} 与三个数自洽</li>
 *   <li><b>逾期规则真的被消费</b>：同一批订单，宽限期 24 → 1，逾期数立刻变。
 *       撤掉那一行读取，这条必红</li>
 *   <li><b>没签收不分拣</b>：批次推到 SIGNED 之前，这个点在分拣汇总里不存在</li>
 *   <li><b>缺件数不是恒 0</b>：自提点上报之后，分拣行上的 shortQty 必须跟着变 ——
 *       一个永远不亮的红色徽标等于没有告警</li>
 * </ol>
 *
 * <h2>为什么走 HTTP 而不是直接调 {@code DispatchService}</h2>
 *
 * <p>分页壳是这一层独有的失败方式：运营端列表页按 {@code {records,total}} 渲染，
 * 返回裸数组会被当成空页 —— <b>接口 200、数据几十条、页面显示「暂无数据」，
 * 而控制台一条错误都没有</b>。直接调 Service 的话这一类缺陷一条都测不到。
 *
 * <p>{@code /ops/shipments}、{@code /ops/freight-templates}、
 * {@code /ops/fulfillment/carriers}（P-5.2 物流那一半）**本类不覆盖** ——
 * 它们在本轮由另一条并行会话现场落地，边写边测会测到半成品。缺口记在验收报告里。
 *
 * <p>手机号段 {@code 126005xxxxx}（商户）/ {@code 130005xxxxx}（买家），尾号 2xxxx 段归本类。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsFulfillmentFlowTest {

    private static final String STUB_SECRET = "stub-secret";
    private static final String PICKUP = "PP0001";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MchEntityMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper merchantStaffMapper;

    @Autowired
    private StatusLogMapper statusLogMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 逾期规则是**全平台单例**（sys_setting 一行）。本类会改它，跑完必须还原。 */
    @AfterEach
    void restoreOverdueRule() throws Exception {
        saveOverdueRuleRaw(OverdueRuleVO.POSTPONE, 24, 2);
    }

    // ---------------------------------------------------------------- 核销监控（5.1.3）与逾期规则（5.1.4）

    @Test
    @DisplayName("★ 核销监控的三个数与真实订单逐一对得上，宽限期一改逾期数立刻变")
    void redeemStatsFollowRealOrdersAndOverdueRule() throws Exception {
        String biz = merchant("12600520001", "履约·核销监控店");
        String goodsNo = listedGoods(biz, 50);
        String skuNo = firstSku(goodsNo);

        assertThat(codeOf(saveOverdueRuleRaw(OverdueRuleVO.POSTPONE, 24, 2))).isZero();
        RedeemStatVO before = redeem();

        Ordered o1 = placeAndPay("13000520011", goodsNo, skuNo, 1);
        Ordered o2 = placeAndPay("13000520012", goodsNo, skuNo, 1);
        Ordered o3 = placeAndPay("13000520013", goodsNo, skuNo, 1);

        /*
         * 刚付款：三单都在「货还没到点」，全部计待核销。
         * 增量而不是绝对值 —— 这个点上有别的用例留下的单，绝对值不可复现，
         * 而增量恰恰是「这三单被算进去了没有」这个问题的答案。
         */
        RedeemStatVO paid = redeem();
        assertThat(paid.pending() - before.pending()).isEqualTo(3);
        assertThat(paid.redeemed() - before.redeemed()).isZero();
        assertThat(paid.overdue() - before.overdue()).isZero();

        // 站长登记两单到货 —— 平台侧一条都不写订单状态，它只读
        String station = loginAsOwnerOf("M0001", "12600520002");
        markArrived(station, List.of(o1.subOrderNo(), o2.subOrderNo()));

        RedeemStatVO arrived = redeem();
        assertThat(arrived.pending() - before.pending())
                .as("刚到货还在宽限期内，仍是待核销而不是逾期").isEqualTo(3);
        assertThat(arrived.overdue() - before.overdue()).isZero();

        // 真核销一单
        assertThat(verify(station, o1.verifyCode())).isTrue();
        RedeemStatVO redeemed = redeem();
        assertThat(redeemed.redeemed() - before.redeemed()).isEqualTo(1);
        assertThat(redeemed.pending() - before.pending()).isEqualTo(2);

        /*
         * 把 o2 的「到货」时刻拨回 3 小时前 —— 这一单在点上放了三个小时。
         * 改的是**状态日志**（逾期判据的真源），不是 updated_at：
         * 那一列任何一次无关写入都会动，拿它当判据的话逾期数会被悄悄清零。
         */
        backdateArrival(o2.subOrderNo(), 3);

        assertThat(overdueRule().get("graceHours").asInt()).isEqualTo(24);
        RedeemStatVO lenient = redeem();
        assertThat(lenient.overdue() - before.overdue())
                .as("宽限 24 小时，放了 3 小时不算逾期").isZero();

        // **这就是「规则真的改到了消费它的地方」的那个点**
        assertThat(data(saveOverdueRuleRaw(OverdueRuleVO.POSTPONE, 1, 2))
                .get("graceHours").asInt()).isEqualTo(1);
        RedeemStatVO strict = redeem();
        assertThat(strict.overdue() - before.overdue())
                .as("宽限改成 1 小时，放了 3 小时的那一单必须变成逾期").isEqualTo(1);
        assertThat(strict.pending() - before.pending()).isEqualTo(1);
        assertThat(strict.redeemed() - before.redeemed()).isEqualTo(1);

        /*
         * 总览与明细自洽：rate 必须能由三个数算回来。
         * 另存一个 rate 计数器的话，这一条就是「总览说 3 单、点进去只有 2 单」的那种不一致。
         */
        int total = strict.pending() + strict.redeemed() + strict.overdue();
        assertThat(strict.rate()).isCloseTo((double) strict.redeemed() / total,
                org.assertj.core.data.Offset.offset(0.0001));

        // o3 还没到点，不可能逾期 —— 它一直在 pending 里
        assertThat(o3.subOrderNo()).isNotBlank();
    }

    @Test
    @DisplayName("逾期规则的三条闸：宽限 <1 小时、顺延 0 次、未知动作一律拒；合法值读写往返")
    void overdueRuleValidation() throws Exception {
        // 到点即作废必产生客诉 —— 这是规则不是建议
        assertThat(codeOf(saveOverdueRuleRaw(OverdueRuleVO.POSTPONE, 0, 2))).isEqualTo(30005);
        // 「顺延 0 次」名为顺延实为作废：界面上写着顺延，行为上是作废
        assertThat(codeOf(saveOverdueRuleRaw(OverdueRuleVO.POSTPONE, 6, 0))).isEqualTo(10400);
        assertThat(codeOf(saveOverdueRuleRaw("DELETE_IT", 6, 2))).isNotEqualTo(0);

        // VOID 不要求顺延次数（顺延上限只对 POSTPONE 有意义），但记录仍要能读回来
        JsonNode saved = data(saveOverdueRuleRaw(OverdueRuleVO.VOID, 6, 0));
        assertThat(saved.get("action").asString()).isEqualTo(OverdueRuleVO.VOID);
        assertThat(saved.get("graceHours").asInt()).isEqualTo(6);

        JsonNode reread = overdueRule();
        assertThat(reread.get("action").asString()).isEqualTo(OverdueRuleVO.VOID);
        assertThat(reread.get("graceHours").asInt()).isEqualTo(6);
        assertThat(reread.get("updatedBy").asString())
                .as("改这条规则会改变一批订单的命运，必须留下是谁改的").isNotBlank();
    }

    // ---------------------------------------------------------------- 批次（5.1.1）与分拣汇总（5.1.2）

    @Test
    @DisplayName("★ 批次件数现算自订单 · 跳步被拒 · 签收之后才进分拣 · 缺件上报后 shortQty 跟着变")
    void batchDispatchThenSorting() throws Exception {
        String biz = merchant("12600520010", "履约·分拣汇总店");
        String goodsNo = listedGoods(biz, 50);
        String skuNo = firstSku(goodsNo);

        ArrivalBatchVO before = todayBatch();
        int itemsBefore = before == null ? 0 : before.itemCount();

        // 一单 2 件 + 一单 1 件 = 3 件
        Ordered o1 = placeAndPay("13000520021", goodsNo, skuNo, 2);
        placeAndPay("13000520022", goodsNo, skuNo, 1);

        ArrivalBatchVO batch = todayBatch();
        assertThat(batch).as("有未完成自提单就必须补齐出批次行（读时补齐）").isNotNull();
        assertThat(batch.itemCount())
                .as("件数现算自订单，不落列 —— 落了就有第二份计数")
                .isEqualTo(itemsBefore + 3);
        assertThat(batch.merchantCount()).isGreaterThanOrEqualTo(1);
        assertThat(batch.vehicle()).isEqualTo("待派");
        assertThat(batch.status()).isEqualTo("PLANNED");
        assertThat(batch.pickupName()).isNotBlank();

        /*
         * 跳步：PLANNED 直接推到 SIGNED 等于宣称「车发过、货到过、站长签过」三件事都发生了 ——
         * 而其中至少两件没有，责任判定的依据就是这三步各自的时刻。
         */
        assertThat(codeOf(setBatchStatus(batch.batchNo(), "SIGNED"))).isEqualTo(30004);
        // 往回退同样不行
        assertThat(codeOf(setBatchStatus(batch.batchNo(), "PLANNED"))).isEqualTo(30004);

        // 没签收就不该出现在分拣汇总里 —— 「货到底交没交到点上」这条判据不能被跳过
        assertThat(sortingRow(skuNo))
                .as("批次还没签收，这个点不该进分拣视图").isNull();

        assertThat(data(setBatchStatus(batch.batchNo(), "DISPATCHED")).get("status").asString())
                .isEqualTo("DISPATCHED");
        assertThat(data(setBatchStatus(batch.batchNo(), "ARRIVED")).get("status").asString())
                .isEqualTo("ARRIVED");
        assertThat(data(setBatchStatus(batch.batchNo(), "SIGNED")).get("status").asString())
                .isEqualTo("SIGNED");

        SortingRowVO row = sortingRow(skuNo);
        assertThat(row).as("签收之后这个点才进分拣视图").isNotNull();
        assertThat(row.qty()).as("分拣件数与订单行数量一致").isEqualTo(3);
        assertThat(row.merchantName()).contains("分拣汇总店");
        assertThat(row.shortQty()).isZero();

        /*
         * 自提点上报缺件。此前 reportShortage 收下 skuNo 却原地丢掉，
         * shortQty 只能恒为 0 —— 页面上那个红色徽标永远不亮，
         * 而看的人会把「永远不亮」读成「今天没缺件」。
         */
        String station = loginAsOwnerOf("M0001", "12600520011");
        markArrived(station, List.of(o1.subOrderNo()));
        reportShortage(station, o1.subOrderNo(), skuNo, "SHORTAGE", "少了一袋");

        SortingRowVO afterReport = sortingRow(skuNo);
        assertThat(afterReport).isNotNull();
        assertThat(afterReport.shortQty())
                .as("上报之后缺件数必须跟着变，否则这一列永远是 0").isEqualTo(1);
        assertThat(afterReport.qty()).as("上报不改应到件数").isEqualTo(3);
    }

    // ---------------------------------------------------------------- 装配

    /** 核销监控里本点那一行。<b>顺带钉住分页壳</b> —— 裸数组会被前端当成空页。 */
    private RedeemStatVO redeem() throws Exception {
        JsonNode page = pageOf(get("/ops/fulfillment/redeem").param("pickupNo", PICKUP)
                .param("size", "100"));
        for (JsonNode r : page.get("records")) {
            if (PICKUP.equals(r.get("pickupNo").asString())) {
                return new RedeemStatVO(r.get("pickupNo").asString(), r.get("pickupName").asString(),
                        r.get("communityName").asString(), r.get("pending").asInt(),
                        r.get("redeemed").asInt(), r.get("overdue").asInt(), r.get("rate").asDouble());
            }
        }
        throw new AssertionError("核销监控里没有 " + PICKUP + "：" + page);
    }

    /** 今天这一批（到货日一期取下单日，计划到货时间是当天 08:00）。 */
    private ArrivalBatchVO todayBatch() throws Exception {
        String planned = LocalDate.now().atTime(8, 0).atZone(ZoneId.systemDefault())
                .toInstant().toString();
        JsonNode page = pageOf(get("/ops/fulfillment/batches").param("pickupNo", PICKUP)
                .param("size", "100"));
        for (JsonNode b : page.get("records")) {
            if (planned.equals(text(b, "planArriveAt"))) {
                return new ArrivalBatchVO(b.get("batchNo").asString(), b.get("status").asString(),
                        text(b, "communityNo"), text(b, "communityName"),
                        b.get("pickupNo").asString(), text(b, "pickupName"),
                        text(b, "planArriveAt"), text(b, "vehicle"),
                        b.get("itemCount").asInt(), b.get("merchantCount").asInt());
            }
        }
        return null;
    }

    private SortingRowVO sortingRow(String skuNo) throws Exception {
        JsonNode page = pageOf(get("/ops/fulfillment/sorting").param("pickupNo", PICKUP)
                .param("size", "200"));
        for (JsonNode r : page.get("records")) {
            if (skuNo.equals(r.get("skuNo").asString())) {
                return new SortingRowVO(r.get("pickupNo").asString(), text(r, "pickupName"),
                        r.get("skuNo").asString(), text(r, "title"), text(r, "merchantName"),
                        r.get("qty").asInt(), r.get("shortQty").asInt());
            }
        }
        return null;
    }

    /**
     * 分页壳：{@code {records,total}}。**total 必须与 records 对得上** ——
     * 一个恒为 0 的 total 会让列表页第二页永远点不出来。
     */
    private JsonNode pageOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + opsLogin()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("records")).as("运营端列表必须是分页壳，不是裸数组：" + body).isNotNull();
        assertThat(data.get("total").asLong())
                .as("total 与 records 对不上，列表页会翻不动").isGreaterThanOrEqualTo(data.get("records").size());
        return data;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    /** 推进批次状态，返回整个响应体（拒绝路径要读 code）。 */
    private String setBatchStatus(String batchNo, String status) throws Exception {
        return mvc().perform(post("/ops/fulfillment/batches/" + batchNo + "/status")
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String saveOverdueRuleRaw(String action, Integer graceHours, Integer maxPostpone)
            throws Exception {
        return mvc().perform(post("/ops/fulfillment/overdue-rule")
                        .header("Authorization", "Bearer " + opsLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"graceHours\":" + graceHours
                                + ",\"maxPostpone\":" + maxPostpone + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode overdueRule() throws Exception {
        String body = mvc().perform(get("/ops/fulfillment/overdue-rule")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
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

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    /** 把这一单的「已到货」时刻拨回若干小时前 —— 逾期判据读的就是这条日志。 */
    private void backdateArrival(String subOrderNo, int hours) {
        List<OrdStatusLog> logs = statusLogMapper.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                .eq(OrdStatusLog::getSubOrderNo, subOrderNo)
                .eq(OrdStatusLog::getStatus, OrdSubOrder.FULFILLING));
        assertThat(logs).as("到货登记必须留下一条 FULFILLING 状态日志").isNotEmpty();
        for (OrdStatusLog l : logs) {
            l.setAt(System.currentTimeMillis() - hours * 3600_000L);
            statusLogMapper.updateById(l);
        }
    }

    private void markArrived(String station, List<String> subOrderNos) throws Exception {
        String nos = String.join(",", subOrderNos.stream().map(s -> "\"" + s + "\"").toList());
        mvc().perform(post("/biz/pickup/arrived").header("Authorization", "Bearer " + station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupNo\":\"" + PICKUP + "\",\"orderNos\":[" + nos + "]}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private boolean verify(String station, String verifyCode) throws Exception {
        String body = mvc().perform(post("/biz/pickup/verify")
                        .header("Authorization", "Bearer " + station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verifyCode\":\"" + verifyCode + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("success").asBoolean();
    }

    private void reportShortage(String station, String subOrderNo, String skuNo, String kind,
                                String note) throws Exception {
        mvc().perform(post("/biz/pickup/" + subOrderNo + "/report")
                        .header("Authorization", "Bearer " + station)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuNo\":\"" + skuNo + "\",\"kind\":\"" + kind
                                + "\",\"note\":\"" + note + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * 下单 + 付款。<b>必须打支付回调</b> —— 只调 {@code /pay} 的话单还停在 WAIT_PAY，
     * 既没有核销码也进不了履约看板，而那是测试写错了不是代码错了。
     */
    private Ordered placeAndPay(String phone, String goodsNo, String skuNo, int qty)
            throws Exception {
        String buyer = login(phone);
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                        + "\",\"qty\":" + qty + "}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "ful-" + phone)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"" + PICKUP + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();

        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-ful-" + phone
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));

        // 子订单号从订单列表取（订单视角的 orderNo 就是子单号），核销码支付成功后才有
        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + buyer))
                .andReturn().getResponse().getContentAsString();
        JsonNode row = json.readTree(list).get("data").get("records").get(0);
        // 端上词表：付款后 C 端看到 PAID（库里是 WAIT_FULFILL）。没打回调的话这里是 WAIT_PAY
        assertThat(row.get("status").asString())
                .as("没打支付回调的话单会停在 WAIT_PAY").isEqualTo("PAID");
        return new Ordered(buyer, row.get("orderNo").asString(), row.get("verifyCode").asString());
    }

    private record Ordered(String userToken, String subOrderNo, String verifyCode) {
    }

    private String listedGoods(String token, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"履约调度测试品\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":" + stock + "}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));
        return goodsNo;
    }

    private String firstSku(String goodsNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + goodsNo)).andReturn().getResponse().getContentAsString();
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
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    /**
     * 把某个手机号登录出来的用户设为该商家的店主，从而拿到自提点作用域。
     *
     * <p>核销与到货登记只能发生在**货真的在的那一端**（TDD §五 T6）——
     * 平台侧没有这个动作，所以这里必须借一个真的站长身份。
     */
    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = login(phone);
        String userNo = userNoOf(token);
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(userNo);
        grantOwner(m.getEntityNo(), userNo);
        merchantMapper.updateById(m);
        // 作用域在登录时解析，改完属主要重新登录一次才生效
        // A7：这个令牌是拿去打 /biz/** 的，必须是 btk_
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private void grantOwner(String merchantNo, String userNo) {
        var existing = merchantStaffMapper.selectOne(
                Wrappers.<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .last("limit 1"));
        if (existing != null) {
            existing.setUserNo(userNo);
            merchantStaffMapper.updateById(existing);
            return;
        }
        var st = new ai.neargo.shop.merchant.entity.MchAccount();
        st.setMchAccountNo("SF-FUL-" + merchantNo);
        st.setEntityNo(merchantNo);
        st.setUserNo(userNo);
        st.setIsOwner(true);
        st.setIsPrimary(true);
        st.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
        merchantStaffMapper.insert(st);
    }

    private String userNoOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

}
