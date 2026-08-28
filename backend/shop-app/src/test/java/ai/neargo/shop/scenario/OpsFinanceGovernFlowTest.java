package ai.neargo.shop.scenario;

import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.entity.StlPayment;
import ai.neargo.shop.settle.entity.StlSettleInvoice;
import ai.neargo.shop.settle.entity.StlSplitLog;
import ai.neargo.shop.settle.entity.StlWithdraw;
import ai.neargo.shop.settle.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.settle.mapper.SettleMappers.SettleInvoiceMapper;
import ai.neargo.shop.settle.mapper.SettleMappers.WithdrawMapper;
import ai.neargo.shop.support.TestLogin;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 平台端财务补齐（TDD-运营端财务补齐，P-12.2 + P-12.1.5）。
 *
 * <p>三块都动真钱，所以这个类的每一条断言都要落到<b>钱真的动了/真的没动</b>，
 * 而不是「接口返回 200」：
 * <ol>
 *   <li><b>退款回退分账走真链路</b>（P-12.1.5 / E4，标高风险）：
 *       下单 → 付款 → 分账 → 裁决支持退款 → 执行。断言的是
 *       <b>结算单真的从 SPLIT 变成 REVERSED、回退金额与分出去的那笔一分不差、
 *       售后单从 REFUNDING 走到 REFUNDED</b>。
 *       只断言 200 的话，一个「什么都不做直接返回」的实现也能过</li>
 *   <li><b>队列口径</b>：商家刚同意的退货退款（货还没寄回）<b>不能</b>进队列 ——
 *       进了就意味着财务会在货没收到时把钱退出去，这是这条链路上最贵的一种错</li>
 *   <li><b>提现审批</b>：状态机 · 驳回必须带原因 · 五道校验。
 *       ⚠️ 并且断言<b>「通过」只落 APPROVED、不打款</b>
 *       （B-12.5 一期只记账、线下结算）—— 用 {@code stl_payment} 里
 *       PAYOUT 方向的流水数量来证明「没有任何打款动作被触发」</li>
 *   <li><b>结算发票</b>：开票/驳回状态机，三道防虚开的校验</li>
 * </ol>
 *
 * <p>登录用 {@code finance/finance123} 而不是超管：这批端点的权限码是本轮新登记的，
 * 用超管跑等于跳过「FINANCE 角色到底拿没拿到这个码」这半个问题。
 *
 * <p>手机号段 {@code 126007xxxxx}（商户）/ {@code 130007xxxxx}（买家）。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsFinanceGovernFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SettleService settleService;

    @Autowired
    private WithdrawMapper withdrawMapper;

    @Autowired
    private SettleInvoiceMapper invoiceMapper;

    @Autowired
    private PaymentMapper paymentMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /**
     * 个税规则是<b>全平台单例</b>（sys_setting 一行 JSON）。本类会改它，跑完必须还原成缺省，
     * 否则后面读这条规则的用例（以及运营端页面）看到的是本类留下的税率。
     */
    @AfterEach
    void restoreTaxRule() throws Exception {
        saveTaxRuleRaw(10000L, 2000L);
    }

    // ---------------------------------------------------------------- 退款回退分账（P-12.1.5 / E4）

    @Test
    @DisplayName("★ 退款回退分账走真链路：分账真的被回退、金额分毫不差、售后单走到 REFUNDED")
    void refundSplitBackReallyReversesTheSplit() throws Exception {
        String biz = merchant("12600700001", "财务·回退分账店");
        String merchantNo = merchantNoOf(biz);
        String goodsNo = listedGoods(biz, 20);
        String skuNo = firstSku(goodsNo);

        Ordered o = placeAndPay("13000700011", goodsNo, skuNo, "fin-back-1");

        // ① 分账：钱到了商家账户上。没有这一步，后面的「回退」就无从谈起
        JsonNode bill = billOf(biz, o.subOrderNo());
        String settleNo = bill.get("settleNo").asString();
        settleService.executeSplit(settleNo);
        assertThat(billStatus(biz, settleNo)).isEqualTo("SPLIT");
        long splitAmount = splitLogAmount(settleNo, StlSplitLog.SPLIT);
        assertThat(splitAmount).as("分账指令必须带金额，否则回退无从核对").isPositive();

        // ② 走到「平台裁决支持退款」——arbitrate 只改状态、不退款，队列的来源就是这里
        String asNo = escalated(biz, o);
        String support = TestLogin.operator(mvc(), json, "support", "support123");
        mvc().perform(post("/ops/after-sales/" + asNo + "/decide")
                        .header("Authorization", "Bearer " + support)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refund\":true,\"liability\":\"MERCHANT\","
                                + "\"verdict\":\"照片可见破损，商家承担\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("REFUNDING"));

        /*
         * 裁决之后**钱一分没退、分账一分没收回**。这两条是队列存在的全部理由，
         * 不先钉住它们，后面「执行完变了」就证明不了是执行干的。
         */
        assertThat(afterSaleStatus(o.userToken(), asNo)).isEqualTo("REFUNDING");
        assertThat(billStatus(biz, settleNo)).isEqualTo("SPLIT");

        JsonNode item = queueItem(asNo);
        assertThat(item).as("裁决支持退款且已分账的单必须进队列").isNotNull();
        assertThat(item.get("liability").asString()).isEqualTo("MERCHANT");
        assertThat(item.get("merchantNo").asString()).isEqualTo(merchantNo);
        assertThat(item.get("refundMinor").asLong()).isEqualTo(o.paidMinor());
        assertThat(item.get("refundSplitPending").asBoolean()).isTrue();
        // share 恒为 null：赔付出资比例口径未定，接口不假装它已经判过
        assertThat(item.get("share").isNull()).isTrue();
        // images 是必填数组，缺了运营端会在渲染时抛异常而不是显示空白
        assertThat(item.get("images").isArray()).isTrue();
        assertThat(item.get("verdict").asString()).contains("商家承担");

        // ③ 执行：先回退分账，再退款
        assertThat(codeOf(executeRaw(asNo))).isZero();

        /*
         * **这四条才是「真的回退了」**：
         * 结算单转 REVERSED、回退指令留痕且金额与分出去的一分不差、
         * 售后单走到 REFUNDED、子单跟着转态。
         * 只断言接口 200 的话，一个空实现也能过。
         */
        assertThat(billStatus(biz, settleNo)).isEqualTo("REVERSED");
        assertThat(settleService.splitLogCount(settleNo, StlSplitLog.REVERSE))
                .as("回退必须留痕：钱从商家账上收回来这件事必须可查").isEqualTo(1);
        assertThat(splitLogAmount(settleNo, StlSplitLog.REVERSE))
                .as("回退金额必须与分出去的那笔相等 —— 差一分就是有人吃亏")
                .isEqualTo(splitAmount);
        assertThat(afterSaleStatus(o.userToken(), asNo)).isEqualTo("REFUNDED");
        assertThat(subOrderStatus(o.userToken(), o.subOrderNo()))
                .as("退款收尾要连子单一起转态，只改售后单等于订单还挂在那儿")
                .isEqualTo("REFUNDED");

        // 办完了就不在待办里 —— 队列不清空的话运营会一直点
        assertThat(queueItem(asNo)).isNull();
        // 列表没刷新又点了一次：不能退第二次（队列里已经没有它了）
        assertThat(codeOf(executeRaw(asNo)))
                .as("重复执行必须被挡住 —— 这条路径动的是真钱").isNotEqualTo(0);
    }

    @Test
    @DisplayName("★ 货还没寄回的退货退款不进队列 —— 只按状态取，财务会在收货前把钱退出去")
    void queueExcludesReturnRefundWaitingForGoods() throws Exception {
        String biz = merchant("12600700010", "财务·待寄回不进队列店");
        String goodsNo = listedGoods(biz, 20);
        String skuNo = firstSku(goodsNo);
        Ordered o = placeAndPay("13000700021", goodsNo, skuNo, "fin-back-2");

        String settleNo = billOf(biz, o.subOrderNo()).get("settleNo").asString();
        settleService.executeSplit(settleNo);

        // 退货退款 + 商家同意：状态同样是 REFUNDING，但**没有责任方** —— 货还没回来
        String asNo = applyAfterSale(o, "RETURN_REFUND", "尺码不对");
        mvc().perform(post("/biz/after-sale/" + asNo + "/approve")
                        .header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(afterSaleStatus(o.userToken(), asNo)).isEqualTo("REFUNDING");

        /*
         * liability 是关键判别器：REFUNDING 有两个来源，
         * 平台裁决支持退款（写责任方）与商家同意退货退款（不写）。
         * 后者的钱本来就不该现在退 —— 只按状态取队列，这一单会被财务点掉。
         */
        assertThat(queueItem(asNo))
                .as("商家刚同意、买家还没寄回的单不能进回退队列").isNull();
        // 队列没有它，执行入口也必须够不着
        assertThat(codeOf(executeRaw(asNo))).isEqualTo(10404);
        // 而且钱确实没动
        assertThat(billStatus(biz, settleNo)).isEqualTo("SPLIT");
    }

    // ---------------------------------------------------------------- 提现审批（P-12.2.1 / 12.2.2）

    @Test
    @DisplayName("★ 提现审批状态机：驳回必须带原因 · 已审的不能再审 · 三道金额闸")
    void withdrawApprovalStateMachine() throws Exception {
        String biz = merchant("12600700020", "财务·提现审批店");
        String merchantNo = merchantNoOf(biz);

        // 驳回不写原因 = 让商家猜。原因是原样回 B 端的那半边
        String w1 = seedWithdraw(merchantNo, "提现审批店", 200_000L, 500_000L);
        assertThat(codeOf(decideRaw(w1, false, "   "))).isEqualTo(10430);
        JsonNode rejected = data(decideRaw(w1, false, "收款账户与主体不符"));
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("remark").asString()).isEqualTo("收款账户与主体不符");
        assertThat(rejected.get("decidedBy").asString())
                .as("这是运营端唯一会把钱批出去的动作，必须留痕").isNotBlank();
        assertThat(rejected.get("decidedAt").isNull()).isFalse();

        // 已驳回的单被二次审批 = 同一笔钱批两次
        assertThat(codeOf(decideRaw(w1, true, "再批一次"))).isEqualTo(50003);

        // 低于单笔下限：渠道手续费比本金还贵
        String w2 = seedWithdraw(merchantNo, "提现审批店", 500L, 500_000L);
        assertThat(codeOf(decideRaw(w2, true, null))).isEqualTo(50005);

        // 超过申请那一刻的可提余额快照（不是实时值 —— 实时值会因期间的新订单而漂移）
        String w3 = seedWithdraw(merchantNo, "提现审批店", 600_000L, 300_000L);
        assertThat(codeOf(decideRaw(w3, true, "大额说明"))).isEqualTo(50004);

        // 大额没有复核说明：事后只能看到「某人批了五万」，看不到为什么这五万是对的
        String w4 = seedWithdraw(merchantNo, "提现审批店", 500_000L, 1_000_000L);
        assertThat(codeOf(decideRaw(w4, true, null))).isEqualTo(50006);

        // 被拒的审批一律不能留下副作用
        assertThat(statusOfWithdraw(w2)).isEqualTo("PENDING");
        assertThat(statusOfWithdraw(w3)).isEqualTo("PENDING");
        assertThat(statusOfWithdraw(w4)).isEqualTo("PENDING");

        // 列表按状态筛得动，且是分页壳
        JsonNode page = withdrawPage("PENDING");
        assertThat(rowByNo(page, "withdrawNo", w2)).isNotNull();
        assertThat(rowByNo(withdrawPage("REJECTED"), "withdrawNo", w1)).isNotNull();
        assertThat(rowByNo(withdrawPage("REJECTED"), "withdrawNo", w2)).isNull();
    }

    @Test
    @DisplayName("★「通过」只落 APPROVED，不打款 —— 一期线下结算，系统不碰支付通道")
    void approvingAWithdrawDoesNotPayAnything() throws Exception {
        String biz = merchant("12600700030", "财务·通过不打款店");
        String merchantNo = merchantNoOf(biz);
        String no = seedWithdraw(merchantNo, "通过不打款店", 300_000L, 800_000L);

        long payoutsBefore = payoutCount();

        JsonNode approved = data(decideRaw(no, true, null));
        assertThat(approved.get("status").asString())
                .as("通过之后是 APPROVED，不是 PAID —— 打款结果只能来自渠道回执")
                .isEqualTo("APPROVED");
        assertThat(approved.get("decidedBy").asString()).isNotBlank();

        /*
         * **「没有触发任何打款动作」的证据在这里。**
         * stl_payment 里 direction=PAYOUT 的流水是系统里唯一记「往外打钱」的地方 ——
         * 审批前后它一条都没多，才说明这个按钮真的只是记账。
         * 只断言 status=APPROVED 是不够的：一个「顺手把钱打出去还写了 APPROVED」的实现同样能过。
         */
        assertThat(payoutCount())
                .as("审批不该产生任何 PAYOUT 流水（B-12.5：一期只记账、线下结算）")
                .isEqualTo(payoutsBefore);

        // 也没有任何人工入口能把它推到 PAID —— APPROVED 之后再审就被状态机挡住
        assertThat(codeOf(decideRaw(no, true, "想再点一次"))).isEqualTo(50003);
        assertThat(statusOfWithdraw(no)).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- 结算发票（P-12.2.4）

    @Test
    @DisplayName("★ 结算发票开票/驳回状态机：企业票必须有税号 · 不得超已结算额 · 开过的不能再开")
    void settleInvoiceStateMachine() throws Exception {
        String biz = merchant("12600700040", "财务·结算发票店");
        String merchantNo = merchantNoOf(biz);

        // 企业抬头没税号：对方入不了账，等于白开
        String noTax = seedInvoice(merchantNo, "结算发票店", "2026-01", 10_000L, 50_000L,
                StlSettleInvoice.COMPANY, null);
        assertThat(codeOf(issueRaw(noTax, "FP-0001"))).isEqualTo(50008);

        // 超出已结算金额的部分没有真实交易对应，就是虚开
        String over = seedInvoice(merchantNo, "结算发票店", "2026-02", 80_000L, 50_000L,
                StlSettleInvoice.COMPANY, "91330100MA2XXXXX1A");
        assertThat(codeOf(issueRaw(over, "FP-0002"))).isEqualTo(50007);

        // 没有流水号的「已开票」等于没开：商家拿不到凭证，事后也查不到开没开
        String ok = seedInvoice(merchantNo, "结算发票店", "2026-03", 30_000L, 50_000L,
                StlSettleInvoice.COMPANY, "91330100MA2XXXXX1A");
        assertThat(codeOf(issueRaw(ok, "  "))).isEqualTo(10400);

        JsonNode issued = data(issueRaw(ok, "FP-0003"));
        assertThat(issued.get("status").asString()).isEqualTo("ISSUED");
        assertThat(issued.get("serialNo").asString()).isEqualTo("FP-0003");
        assertThat(issued.get("decidedAt").isNull()).isFalse();

        // 重复开票就是重复虚开 —— 不做幂等早退，要让点第二次的人看见「已处理」
        assertThat(codeOf(issueRaw(ok, "FP-0004"))).isEqualTo(10409);
        assertThat(codeOf(rejectRaw(ok, "改主意了"))).isEqualTo(10409);
        // 被拒之后流水号不能被改掉
        assertThat(invoiceOf(ok).get("serialNo").asString()).isEqualTo("FP-0003");

        // 驳回必须写原因，原样回商家 B 端
        String rej = seedInvoice(merchantNo, "结算发票店", "2026-04", 10_000L, 50_000L,
                StlSettleInvoice.PERSONAL, null);
        assertThat(codeOf(rejectRaw(rej, " "))).isEqualTo(10430);
        JsonNode rejected = data(rejectRaw(rej, "抬头与主体不一致"));
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("remark").asString()).isEqualTo("抬头与主体不一致");
        // 个人抬头不要求税号 —— 校验只对企业抬头生效
        assertThat(rejected.get("titleType").asString()).isEqualTo("PERSONAL");

        assertThat(rowByNo(invoicePage("ISSUED"), "invoiceNo", ok)).isNotNull();
        assertThat(rowByNo(invoicePage("PENDING"), "invoiceNo", ok)).isNull();
    }

    // ---------------------------------------------------------------- 个税规则（P-12.2.3）

    @Test
    @DisplayName("个税规则：税率上限 45% · 负值拒绝 · 改动必须留下是谁改的")
    void taxRuleGates() throws Exception {
        // 一个手滑多打的零会让每一笔提现都扣光
        assertThat(codeOf(saveTaxRuleRaw(10000L, 5000L))).isEqualTo(50009);
        assertThat(codeOf(saveTaxRuleRaw(-1L, 2000L))).isEqualTo(10400);

        JsonNode saved = data(saveTaxRuleRaw(20000L, 1500L));
        assertThat(saved.get("threshold").asLong()).isEqualTo(20000L);
        assertThat(saved.get("rate").asLong()).isEqualTo(1500L);
        assertThat(saved.get("updatedBy").asString())
                .as("改税率会改变所有后续提现被扣多少，必须追得到是谁改的").isNotBlank();

        JsonNode reread = data(mvc().perform(get("/ops/finance/tax-rule")
                        .header("Authorization", "Bearer " + finance()))
                .andReturn().getResponse().getContentAsString());
        assertThat(reread.get("rate").asLong()).isEqualTo(1500L);
        assertThat(reread.get("updatedAt").asString()).isNotBlank();
    }

    // ---------------------------------------------------------------- 装配 · 退款回退

    private JsonNode queueItem(String afterSaleNo) throws Exception {
        String body = mvc().perform(get("/ops/refund-split-backs")
                        .header("Authorization", "Bearer " + finance()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data")) {
            if (afterSaleNo.equals(r.get("afterSaleNo").asString())) {
                return r;
            }
        }
        return null;
    }

    private String executeRaw(String afterSaleNo) throws Exception {
        return mvc().perform(post("/ops/refund-split-backs/" + afterSaleNo + "/execute")
                        .header("Authorization", "Bearer " + finance()))
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * 售后单走到 ARBITRATING（平台仲裁台），走的是真链路：申请 → 商家驳回 → 买家申诉。
     *
     * <p><b>要分两种主体</b>：资金归集（自营）主体的售后<b>申请即进仲裁</b> ——
     * 平台是法律上的销售主体，没有「先派给商家」这一步（ADR-017 §3.4）。
     * 写死走驳回-申诉两步的话，在归集主体上第一步就会被状态机拒，
     * 而那不是缺陷，是用例假设了另一种主体。
     */
    private String escalated(String bizToken, Ordered o) throws Exception {
        String asNo = applyAfterSale(o, "RETURN_REFUND", "收到时已破损");
        if ("ARBITRATING".equals(afterSaleStatus(o.userToken(), asNo))) {
            return asNo;
        }
        mvc().perform(post("/biz/after-sale/" + asNo + "/reject")
                        .header("Authorization", "Bearer " + bizToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"商品无质量问题\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/mp/after-sale/" + asNo + "/escalate")
                        .header("Authorization", "Bearer " + o.userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appeal\":\"有照片为证\"}"))
                .andExpect(jsonPath("$.data.status").value("ARBITRATING"));
        return asNo;
    }

    private String applyAfterSale(Ordered o, String type, String reason) throws Exception {
        String body = mvc().perform(post("/mp/order/" + o.subOrderNo() + "/after-sale")
                        .header("Authorization", "Bearer " + o.userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"reason\":\"" + reason
                                + "\",\"images\":[\"https://cdn/x.jpg\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("afterSaleNo").asString();
    }

    private String afterSaleStatus(String userToken, String asNo) throws Exception {
        String body = mvc().perform(get("/mp/after-sale/" + asNo)
                        .header("Authorization", "Bearer " + userToken))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("status").asString();
    }

    private String subOrderStatus(String userToken, String subOrderNo) throws Exception {
        String body = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + userToken))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (subOrderNo.equals(r.get("orderNo").asString())) {
                return r.get("status").asString();
            }
        }
        throw new AssertionError("订单列表里没有 " + subOrderNo);
    }

    // ---------------------------------------------------------------- 装配 · 结算单

    private JsonNode billOf(String bizToken, String subOrderNo) throws Exception {
        String body = mvc().perform(get("/biz/settle/bills")
                        .header("Authorization", "Bearer " + bizToken))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode list = json.readTree(body).get("data");
        assertThat(list).as("付款后必须生成结算单，否则分账无从谈起").isNotEmpty();
        for (JsonNode b : list) {
            if (subOrderNo.equals(b.get("subOrderNo").asString())) {
                return b;
            }
        }
        throw new AssertionError("商家结算单里没有子单 " + subOrderNo + "：" + list);
    }

    private String billStatus(String bizToken, String settleNo) throws Exception {
        String body = mvc().perform(get("/biz/settle/bills/" + settleNo)
                        .header("Authorization", "Bearer " + bizToken))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("status").asString();
    }

    /** 某条分账指令的金额。回退核对看的就是它 —— 没有金额的「回退成功」核对不了。 */
    private long splitLogAmount(String settleNo, String action) {
        var logs = settleService.opsSplitLogs(settleNo, action);
        assertThat(logs).as("没有 " + action + " 指令留痕：" + settleNo).isNotEmpty();
        return logs.get(0).amountMinor();
    }

    // ---------------------------------------------------------------- 装配 · 提现

    /**
     * 直接种一张提现单。
     *
     * <p><b>不是走捷径</b>：`stl_withdraw` 今天没有生产者 —— B 端申请入口不在本批
     * （TDD §五 T5：加 /biz 端点会连带改 BizEndpointPermTest 与 b-app 契约）。
     * 审批侧的每一道闸仍然走真实 HTTP。
     */
    private String seedWithdraw(String entityNo, String name, long amount, long available) {
        StlWithdraw w = new StlWithdraw();
        w.setWithdrawNo("WD-TEST-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        w.setEntityNo(entityNo);
        w.setMerchantName(name);
        w.setAmountMinor(amount);
        w.setAvailableBalanceMinor(available);
        w.setBankAccountMasked("****8821");
        w.setStatus(StlWithdraw.PENDING);
        w.setAppliedAt(System.currentTimeMillis());
        withdrawMapper.insert(w);
        return w.getWithdrawNo();
    }

    private String statusOfWithdraw(String withdrawNo) {
        return withdrawMapper.selectOne(Wrappers.<StlWithdraw>lambdaQuery()
                .eq(StlWithdraw::getWithdrawNo, withdrawNo).last("limit 1")).getStatus();
    }

    /** 系统里唯一记「往外打钱」的地方。审批前后它一条都不该多。 */
    private long payoutCount() {
        Long n = paymentMapper.selectCount(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getDirection, StlPayment.PAYOUT));
        return n == null ? 0L : n;
    }

    private String decideRaw(String withdrawNo, boolean pass, String remark) throws Exception {
        String body = "{\"pass\":" + pass
                + (remark == null ? "" : ",\"remark\":\"" + remark + "\"") + "}";
        return mvc().perform(post("/ops/finance/withdrawals/" + withdrawNo + "/decide")
                        .header("Authorization", "Bearer " + finance())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode withdrawPage(String status) throws Exception {
        return pageOf(get("/ops/finance/withdrawals").param("status", status).param("size", "200"));
    }

    // ---------------------------------------------------------------- 装配 · 发票

    private String seedInvoice(String entityNo, String name, String period, long amount,
                               long settled, String titleType, String taxNo) {
        StlSettleInvoice iv = new StlSettleInvoice();
        iv.setInvoiceNo("SI-TEST-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        iv.setEntityNo(entityNo);
        iv.setMerchantName(name);
        iv.setPeriod(period);
        iv.setAmountMinor(amount);
        iv.setSettledAmountMinor(settled);
        iv.setTitleType(titleType);
        iv.setTitle(name + "有限公司");
        iv.setTaxNo(taxNo);
        iv.setStatus(StlSettleInvoice.PENDING);
        iv.setAppliedAt(System.currentTimeMillis());
        invoiceMapper.insert(iv);
        return iv.getInvoiceNo();
    }

    private String issueRaw(String invoiceNo, String serialNo) throws Exception {
        return mvc().perform(post("/ops/finance/invoices/" + invoiceNo + "/issue")
                        .header("Authorization", "Bearer " + finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNo\":\"" + serialNo + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String rejectRaw(String invoiceNo, String reason) throws Exception {
        return mvc().perform(post("/ops/finance/invoices/" + invoiceNo + "/reject")
                        .header("Authorization", "Bearer " + finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode invoicePage(String status) throws Exception {
        return pageOf(get("/ops/finance/invoices").param("status", status).param("size", "200"));
    }

    private JsonNode invoiceOf(String invoiceNo) throws Exception {
        JsonNode row = rowByNo(invoicePage("ISSUED"), "invoiceNo", invoiceNo);
        assertThat(row).isNotNull();
        return row;
    }

    private String saveTaxRuleRaw(Long threshold, Long rate) throws Exception {
        return mvc().perform(put("/ops/finance/tax-rule")
                        .header("Authorization", "Bearer " + finance())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threshold\":" + threshold + ",\"rate\":" + rate + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    // ---------------------------------------------------------------- 装配 · 通用

    /** 分页壳 {@code {records,total}} —— 裸数组会被运营端当成空页。 */
    private JsonNode pageOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + finance()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("records")).as("运营端列表必须是分页壳：" + body).isNotNull();
        assertThat(data.get("total").asLong())
                .isGreaterThanOrEqualTo(data.get("records").size());
        return data;
    }

    private JsonNode rowByNo(JsonNode page, String field, String value) {
        for (JsonNode r : page.get("records")) {
            if (value.equals(r.get(field).asString())) {
                return r;
            }
        }
        return null;
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

    // ---------------------------------------------------------------- 装配 · 真实链路

    private record Ordered(String userToken, String subOrderNo, long paidMinor) {
    }

    private Ordered placeAndPay(String phone, String goodsNo, String skuNo, String idem)
            throws Exception {
        String buyer = login(phone);
        mvc().perform(post("/mp/user/community").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0001\"}"));
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));

        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();

        // 只调 /pay 的话单还在 WAIT_PAY，也不会生成结算单 —— 分账无从谈起
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idem
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));

        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + buyer))
                .andReturn().getResponse().getContentAsString();
        JsonNode row = json.readTree(list).get("data").get("records").get(0);
        assertThat(row.get("status").asString())
                .as("没打支付回调的话单会停在 WAIT_PAY").isEqualTo("PAID");
        return new Ordered(buyer, row.get("orderNo").asString(),
                row.get("amount").get("paidMinor").asLong());
    }

    private String listedGoods(String token, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"财务测试品\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":6980,\"stock\":" + stock + "}]}"))
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
        String body = mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("skus").get(0).get("skuNo").asString();
    }

    private String merchantNoOf(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile")
                        .header("Authorization", "Bearer " + bizToken))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
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

    /** 财务岗账号 —— 用它而不是超管，顺带验证这批新码真的授给了 FINANCE 角色。 */
    private String finance() throws Exception {
        return TestLogin.operator(mvc(), json, "finance", "finance123");
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
