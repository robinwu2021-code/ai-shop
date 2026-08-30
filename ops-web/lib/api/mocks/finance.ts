// 覆盖范围：分账结算（P-12.1）。本域的价值在**跨域收口**：
// 读商家的报备状态、消费售后的回退标记 —— 这两处不接上，前面几个域的字段就是死的。
import * as db from "@/lib/mock/db";
import { MAX_TAX_RATE, MIN_WITHDRAW_AMOUNT, WITHDRAW_REVIEW_THRESHOLD } from "@/lib/constants";
import { WITHDRAW_TRANSITIONS } from "@/lib/types";
import { MAX_SPLIT_RETRY, SETTLE_FREEZE_MIN_DAYS } from "@/lib/constants";
import { SETTLE_TRANSITIONS, type Settlement } from "@/lib/types";
import type { FinanceApi } from "../contracts/finance";
import type { ClientPointsPolicy } from "@/lib/types";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function find(settleNo: string): Settlement {
  const s = db.settlements.find((x) => x.settleNo === settleNo);
  if (!s) notFound("结算单", "Settlement", settleNo);
  return s;
}

/** 对账恒等式：gross = 佣金 + 服务费 + 实付。三个数来自三处，不校验必对不上。 */
function assertBalanced(s: Settlement) {
  const sum = s.commissionMinor + s.serviceFeeMinor + s.netMinor;
  if (sum !== s.grossMinor) {
    fail(`对账不平：基数 ${s.grossMinor} ≠ 佣金 ${s.commissionMinor} + 服务费 ${s.serviceFeeMinor} + 实付 ${s.netMinor}`, `Books do not balance: gross ${s.grossMinor} ≠ commission ${s.commissionMinor} + service fee ${s.serviceFeeMinor} + net ${s.netMinor}`);
  }
}

/** mock 不落库，但改完要看得见变化，否则以为按钮没生效 */
let mockClientPolicy: ClientPointsPolicy = { earnDeny: [], redeemDeny: [], offlineRedeem: true };

/** 取一张应付单，找不到就报错 —— mock 里也要报，否则开发期点到不存在的单是静默的 */
function mustBill(settleNo: string) {
  const b = db.settlements.find((x) => x.settleNo === settleNo);
  if (!b) fail("结算单不存在", "Settlement not found");
  return b;
}

export const financeMock: FinanceApi = {
  // 两条轨道都给：运营要回答的是「这家店的钱到哪一步了」，
  // 分开查等于让一家同时有自营店和第三方店的商家在两个页面之间对照
  /*
   * ⚠️ mock 里**故意让两个数对得上**（流通 12800 分 == 池子 12800 分）。
   * 这一页的用途就是发现失衡，而开发时看到的应该是「正常的样子」——
   * 造一份天然不平的假数据，会让人误以为页面坏了。
   * 真接口下这两个数由流水推出来，对不上才是真信号。
   */
  /*
   * 端策略。mock 里**默认什么都不禁** —— 与后端默认值一致。
   * 造一个「已经禁了两个端」的初值会让人以为线上就是那样，
   * 而这一页的第一职责恰恰是「现在到底禁了谁」。
   */
  pointsClientPolicy: async () => wait({ ...mockClientPolicy }),

  savePointsClientPolicy: async (v) => {
    mockClientPolicy = {
      earnDeny: [...v.earnDeny],
      redeemDeny: [...v.redeemDeny],
      offlineRedeem: v.offlineRedeem,
    };
    return wait({ ...mockClientPolicy });
  },

  pointsOverview: async (market = "CN") => {
    return wait({
      circulatingPoints: 12_800,
      poolBalanceMinor: 12_800,
      periodRedeemMinor: 3_400,
      byChannel: [
        { market, payChannel: "WECHAT", balanceMinor: 9_000 },
        { market, payChannel: "ALIPAY", balanceMinor: 3_800 },
      ],
    });
  },

  /*
   * **返回分页包，与真后端同形**。
   *
   * 此前 mock 返裸数组而后端返 `{records,…}` —— 页面按数组用，
   * 于是 mock 下一切正常、真接口下 `rows.filter is not a function` 整页崩。
   * mock 与真接口形状不一致时，mock 不再是「后端的替身」，而是一层遮罩。
   */
  // ── 自营应付账款 ──
  //
  // **mock 里各档都要有**：只造「待付款」的话，「票还没到所以付不了」
  // 那条分支永远看不见，而它恰恰是这一页最要紧的规则（票到付款）。
  listPayables: async (q = {}) =>
    wait(db.settlements.filter((s) =>
      s.businessMode === "SELF_OPERATED"
      && db.eqHit(q.status, s.status)
      && db.eqHit(q.entityNo, s.merchantNo))),

  confirmPayable: async (settleNo) => {
    const b = mustBill(settleNo);
    // 未对账不能付款 —— 付了一个双方还没认的数
    if (b.status !== "PENDING_RECON") fail("只有待对账的单能确认", "Only bills awaiting reconciliation can be confirmed");
    b.status = "CONFIRMED";
    return wait({ ...b });
  },

  payPayable: async (settleNo, paymentRef) => {
    const b = mustBill(settleNo);
    if (b.status !== "CONFIRMED") fail("只有已对账的单能登记付款", "Only reconciled bills can be marked paid");
    // 票到付款：要么票已核验，要么显式标过无票供应商
    if (b.invoiceStatus !== "VERIFIED" && b.invoiceStatus !== "NO_INVOICE") {
      fail("票还没到 —— 核验进项票，或先标记为无票供应商",
        "Invoice not received — verify it, or mark the supplier as invoice-exempt");
    }
    b.status = "PAID";
    b.paymentRef = paymentRef;
    return wait({ ...b });
  },

  markNoInvoice: async (settleNo) => {
    const b = mustBill(settleNo);
    b.invoiceStatus = "NO_INVOICE";
    return wait({ ...b });
  },

  // ── 进项票 ──
  listPurchaseInvoices: async (q = {}) =>
    wait(db.purchaseInvoices.filter((i) => db.eqHit(q.status, i.status))),

  verifyPurchaseInvoice: async (invoiceNo) => {
    const i = db.purchaseInvoices.find((x) => x.invoiceNo === invoiceNo);
    if (!i) fail("发票不存在", "Invoice not found");
    // 抬头对不上不给过 —— 而界面上要说清是这个原因
    if (!i.titleMatched) fail("抬头与主体名不一致，不能核验", "Title does not match the entity name");
    i.status = "VERIFIED";
    return wait({ ...i });
  },

  rejectPurchaseInvoice: async (invoiceNo, reason) => {
    const i = db.purchaseInvoices.find((x) => x.invoiceNo === invoiceNo);
    if (!i) fail("发票不存在", "Invoice not found");
    i.status = "REJECTED";
    i.rejectReason = reason;
    return wait({ ...i });
  },

  // ── 买家开票申请 ──
  listBuyerInvoiceRequests: async (q = {}) =>
    wait(db.buyerInvoiceRequests.filter((r) => db.eqHit(q.status, r.status))),

  markBuyerInvoiceIssued: async (requestNo, invoiceNo) => {
    const r = db.buyerInvoiceRequests.find((x) => x.requestNo === requestNo);
    if (!r) fail("申请不存在", "Request not found");
    r.status = "ISSUED";
    r.invoiceNo = invoiceNo;
    r.issuedAt = Date.now();
    return wait({ ...r });
  },

  rejectBuyerInvoiceRequest: async (requestNo, reason) => {
    const r = db.buyerInvoiceRequests.find((x) => x.requestNo === requestNo);
    if (!r) fail("申请不存在", "Request not found");
    r.status = "REJECTED";
    r.rejectReason = reason;
    return wait({ ...r });
  },

  listSettlements: async (q = {}) =>
    wait(db.paginate(db.settlements, 1, 100, (s) =>
      db.eqHit(q.merchantNo, s.merchantNo)
      && db.eqHit(q.status, s.status)
      && db.eqHit(q.businessMode, s.businessMode ?? undefined))),

  // 失败的指令**也给**：出问题时要看的恰恰是它们
  listSplitRecords: async (q = {}) =>
    wait(db.paginate(db.splitRecords, 1, 100, (r) =>
      db.eqHit(q.settleNo, r.settleNo) && db.eqHit(q.action, r.splitAction))),

  // 队列直接由售后单派生，不另建实体：另建就有两份真相，且一定会不同步
  listRefundSplitBacks: async () => wait(db.afterSales.filter((a) => a.refundSplitPending)),

  executeRefundSplitBack: async (asNo) => {
    const a = db.afterSales.find((x) => x.afterSaleNo === asNo);
    if (!a) notFound("售后单", "After-sales case", asNo);
    if (!a.refundSplitPending) fail("该售后单没有待回退的分账", "This case has no split waiting to be reversed");
    if (!a.share) fail("该售后单尚未判定赔付比例，无法按比例回退", "Liability shares have not been decided, so the reversal cannot be apportioned");
    // 回退完成 → 清标记。不清的话队列永远消不掉，运营会反复执行同一单
    a.refundSplitPending = false;
    a.status = "REFUNDED";
    return wait(a, 400);
  },

  listFeeRules: async () =>
    wait([...db.feeRules].sort((a, b) => b.effectiveFrom - a.effectiveFrom)),

  /**
   * 停用的版本要**参与覆盖再被移除**，不能直接跳过 —— 与后端同一套语义。
   * 「停用最新版本」的意图是回退到上一版；直接跳过会让最新版形同没存在过，
   * 于是命中的是更早的某一版：只调过一次时看不出区别，调过三次时结果完全不同。
   */
  effectiveFeeRates: async (at = Date.now()) => {
    const out: Record<string, number> = {};
    for (const r of [...db.feeRules].filter((r) => r.effectiveFrom <= at)
      .sort((a, b) => a.effectiveFrom - b.effectiveFrom)) {
      const key = `${r.businessMode}|${r.trafficSource}`;
      if (r.enabled !== 1) delete out[key];
      else out[key] = r.rateBp;
    }
    return wait(out);
  },

  addFeeRule: async (v) => {
    // 少一个零和多一个零是同一次手滑：5000（50%）打成 50000 就是 500%，
    // 净额会变成大额负数并一路走到分账
    if (v.rateBp < 0 || v.rateBp > 10_000) {
      fail("费率必须在 0–10000 万分比之间", "The rate must be between 0 and 10000 basis points");
    }
    const rule = {
      ruleNo: `FR-${db.feeRules.length + 1}`,
      businessMode: v.businessMode,
      trafficSource: v.trafficSource,
      rateBp: v.rateBp,
      effectiveFrom: v.effectiveFrom ?? Date.now(),
      enabled: 1,
      remark: v.remark ?? null,
    };
    db.feeRules.push(rule);
    return wait(rule, 400);
  },

  listWithdrawals: (q = {}) =>
    wait(
      db.paginate(db.withdrawals, q.page, q.size, (w) =>
        db.eqHit(q.status, w.status) && db.kwHit(q.keyword, w.withdrawNo, w.merchantNo, w.merchantName),
      ),
    ),

  decideWithdrawal: async ({ withdrawNo, pass, remark }) => {
    const w = db.withdrawals.find((x) => x.withdrawNo === withdrawNo);
    if (!w) notFound("提现单", "Withdrawal", withdrawNo);
    db.assertTransition(WITHDRAW_TRANSITIONS, w.status, pass ? "APPROVED" : "REJECTED", "提现单", "Withdrawal");

    if (!pass) {
      // 驳回原因原样回商家 B 端，不写等于让人猜
      if (!remark?.trim()) fail("驳回提现必须写原因 —— 商家在 B 端看到的就是这段话", "Rejecting a withdrawal needs a reason — the merchant sees this text in their app");
      w.status = "REJECTED";
    } else {
      const m = db.merchants.find((x) => x.merchantNo === w.merchantNo);
      if (!m) notFound("商家", "Merchant", w.merchantNo);
      // 没有收款账户，批了钱也打不出去（ADR-002）
      if (!m.settleAccountReady) fail(`${m.name} 尚未报备分账接收方，无法打款`, `${m.name} has no payout account registered, so there is nowhere to send the money`);
      // 解封是另一条链路上的决定（P-11.1.4），不在这里绕过去
      if (m.status === "SUSPENDED") fail(`${m.name} 处于封禁中，请先解封再处理提现`, `${m.name} is banned — lift the ban before handling the withdrawal`);
      // 用申请那一刻的余额快照，而不是实时值：实时值会因为期间的新订单而漂移
      if (w.amount > w.availableBalance) {
        fail(`申请金额超过可提余额（可提 ${w.availableBalance / 100} 元）`, `The request exceeds the available balance (¥${w.availableBalance / 100})`);
      }
      if (w.amount < MIN_WITHDRAW_AMOUNT) {
        fail(`单笔提现不得低于 ${MIN_WITHDRAW_AMOUNT / 100} 元 —— 渠道手续费比本金还贵`, `A withdrawal cannot be under ¥${MIN_WITHDRAW_AMOUNT / 100} — the channel fee costs more than the amount`);
      }
      // 大额是最容易被冒用的口子
      if (w.amount >= WITHDRAW_REVIEW_THRESHOLD && !remark?.trim()) {
        fail(`金额超过 ${WITHDRAW_REVIEW_THRESHOLD / 100} 元，必须填写复核说明`, `Above ¥${WITHDRAW_REVIEW_THRESHOLD / 100} a review note is required`);
      }
      // 落 APPROVED 而不是 PAID：打款结果来自渠道回执
      w.status = "APPROVED";
    }

    w.remark = remark?.trim() || null;
    w.decidedAt = new Date().toISOString();
    w.decidedBy = "admin";
    return wait(w, 400);
  },

  listInvoiceRequests: (q = {}) =>
    wait(
      db.paginate(db.invoiceRequests, q.page, q.size, (i) =>
        db.eqHit(q.status, i.status) && db.kwHit(q.keyword, i.invoiceNo, i.merchantName, i.title),
      ),
    ),

  issueInvoice: async ({ invoiceNo, serialNo }) => {
    const iv = db.invoiceRequests.find((x) => x.invoiceNo === invoiceNo);
    if (!iv) notFound("开票申请", "Invoice request", invoiceNo);
    // 重复开票就是重复虚开
    if (iv.status !== "PENDING") fail(`该申请已${iv.status === "ISSUED" ? "开票" : "驳回"}，不能重复处理`, `This request was already ${iv.status === "ISSUED" ? "issued" : "rejected"} and cannot be handled twice`);
    if (!serialNo.trim()) fail("发票流水号不能为空", "The invoice serial number cannot be empty");
    if (iv.titleType === "COMPANY" && !iv.taxNo?.trim()) {
      fail("企业抬头必须有纳税人识别号，请让商家补填后再开", "A company title needs a tax ID — ask the merchant to supply it before issuing");
    }
    // 超出已结算金额的部分没有真实交易对应，就是虚开
    if (iv.amount > iv.settledAmount) {
      fail(`开票金额超过该周期已结算金额（${iv.settledAmount / 100} 元）`, `The invoice exceeds what was settled this period (¥${iv.settledAmount / 100})`);
    }

    iv.status = "ISSUED";
    iv.serialNo = serialNo.trim();
    iv.decidedAt = new Date().toISOString();
    return wait(iv, 400);
  },

  rejectInvoice: async ({ invoiceNo, reason }) => {
    const iv = db.invoiceRequests.find((x) => x.invoiceNo === invoiceNo);
    if (!iv) notFound("开票申请", "Invoice request", invoiceNo);
    if (iv.status !== "PENDING") fail("该申请已处理，不能重复处理", "This request has already been handled");
    if (!reason.trim()) fail("驳回开票必须写原因", "Rejecting an invoice request needs a reason");
    iv.status = "REJECTED";
    iv.remark = reason.trim();
    iv.decidedAt = new Date().toISOString();
    return wait(iv, 400);
  },

  getInvoiceTitle: async () => wait(db.invoiceTitle),

  saveInvoiceTitle: async (v) => {
    // **与后端同一条规则**：缺这两项供应商根本开不出票，存下去只会让人以为已经配好了。
    // mock 放宽的话，开发时看着能存、接真后端才被 10400 拒 —— 那正是这一族缺陷的来源。
    if (!v.companyName?.trim()) fail("公司全称必填", "The company name is required");
    if (!v.taxNo?.trim()) fail("纳税人识别号必填", "The tax number is required");
    Object.assign(db.invoiceTitle, v);
    return wait(db.invoiceTitle, 400);
  },

  getTaxRule: async () => wait(db.taxRule),

  saveTaxRule: async (v) => {
    if (v.threshold < 0) fail("起征点不能为负", "The threshold cannot be negative");
    if (v.rate < 0) fail("税率不能为负", "The rate cannot be negative");
    if (v.rate > MAX_TAX_RATE) fail(`税率不得超过 ${MAX_TAX_RATE / 100}%，超过一定是配置错误`, `The rate cannot exceed ${MAX_TAX_RATE / 100}% — anything higher is a configuration error`);
    Object.assign(db.taxRule, v, { updatedAt: new Date().toISOString(), updatedBy: "admin" });
    return wait(db.taxRule, 400);
  },

  /*
   * 支付通道。**mock 里也要照后端的形状返回 `currentRate: null`** ——
   * 「没配过费率」是真实存在的初始状态，mock 里塞一个数会让页面
   * 永远走不到「未配置」那一支，而那正是运营第一次打开时看到的画面。
   */
  listPayChannels: async () => wait(db.payChannels.map(withCurrentRate)),

  // ── 账期批次
  listSettleBatches: async (q = {}) =>
    wait(db.settleBatches.filter((b) =>
      db.eqHit(q.status, b.status) && db.eqHit(q.entityNo, b.entityNo))),

  releaseSettleBatch: async (batchNo, remark) => {
    const b = db.settleBatches.find((x) => x.batchNo === batchNo);
    if (!b) notFound("批次", "Batch", batchNo);
    /*
     * mock 也走「必须写原因」这条规则：恒成功的 mock 会让端上
     * 「不写原因就点不动」那段界面永远走不到 —— 而那正是这个动作最要紧的约束。
     */
    if (!remark || !remark.trim()) fail("放行必须写原因", "A reason is required");
    // 只有挂起中的能人工处置：已放行的再挂起最危险 —— 钱已经在路上，界面却显示挂起
    if (b.status !== "BLOCKED" && b.status !== "RECONCILING") {
      fail(`只有挂起中的批次能人工处置，当前 ${b.status}`,
        `Only blocked batches can be decided, now ${b.status}`);
    }
    b.status = "RECONCILED";
    b.decidedBy = "admin";
    b.decideRemark = remark;
    return wait({ ...b });
  },

  holdSettleBatch: async (batchNo, remark) => {
    const b = db.settleBatches.find((x) => x.batchNo === batchNo);
    if (!b) notFound("批次", "Batch", batchNo);
    if (!remark || !remark.trim()) fail("继续挂起必须写原因", "A reason is required");
    b.status = "BLOCKED";
    b.blockedReason = remark;
    b.blockedAt = Date.now();
    b.decidedBy = "admin";
    b.decideRemark = remark;
    return wait({ ...b });
  },

  // ── 商家欠款
  merchantDebt: async (entityNo) =>
    wait(db.merchantDebts[entityNo]
      ?? { entityNo, balanceMinor: 0, txns: [] }),

  offsetDebtByDeposit: async (entityNo, amountMinor, reason) => {
    const d = db.merchantDebts[entityNo];
    if (!d || d.balanceMinor <= 0) fail("这家没有待抵扣的欠款", "No outstanding debt");
    // 两头封顶，与后端一致：不超过欠款，也不超过保证金可用额（mock 里假设可用 5000）
    const available = 5_000;
    const take = Math.min(d.balanceMinor, amountMinor, available);
    if (take <= 0) fail("保证金可用额不足", "Deposit balance is insufficient");
    d.balanceMinor -= take;
    d.txns.unshift({
      txnNo: `DBT-MOCK-${d.txns.length + 1}`, txnType: "DEPOSIT",
      amountMinor: -take, balanceAfterMinor: d.balanceMinor,
      sourceType: null, sourceNo: null, batchNo: null,
      reason: `${reason || "保证金抵扣"}（操作人 admin）`, at: Date.now(),
    });
    return wait({ ...d });
  },

  updatePayChannel: async (channel, v) => {
    const row = db.payChannels.find((c) => c.payChannel === channel);
    if (!row) fail("通道不存在", "Channel not found");
    Object.assign(row, v);
    return wait(withCurrentRate(row), 300);
  },

  addPayChannelRate: async (channel, v) => {
    const row = db.payChannels.find((c) => c.payChannel === channel);
    if (!row) fail("通道不存在", "Channel not found");
    if (v.rateBp < 0 || v.rateBp > 10000) {
      fail("万分比越界 —— 把 0.38% 写成 38 是最常见的那种错",
        "Basis points out of range — writing 0.38% as 38 is the usual slip");
    }
    const rate = {
      rateNo: `PCR${Date.now()}${Math.floor(Math.random() * 1000)}`,
      payChannel: channel,
      payMethod: v.payMethod || "*",
      legalForm: v.legalForm || "*",
      rateBp: v.rateBp,
      minFeeMinor: v.minFeeMinor ?? 0,
      effectiveFrom: v.effectiveFrom ?? Date.now(),
      enabled: true,
      remark: v.remark ?? null,
    };
    row.rates = [rate, ...row.rates];
    return wait(rate, 300);
  },
};

/** 此刻生效的那一版：精确优先于通配，与后端 `PayChannelRateServiceImpl` 同一套。 */
function withCurrentRate(c: (typeof db.payChannels)[number]) {
  const now = Date.now();
  const usable = c.rates.filter((r) => r.enabled !== false && r.effectiveFrom <= now);
  const pick = (pm: string, lf: string) =>
    usable.filter((r) => r.payMethod === pm && r.legalForm === lf)
      .sort((a, b) => b.effectiveFrom - a.effectiveFrom)[0] ?? null;
  return { ...c, currentRate: pick("*", "*") };
}
