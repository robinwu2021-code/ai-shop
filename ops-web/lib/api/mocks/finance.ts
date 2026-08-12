// 覆盖范围：分账结算（P-12.1）。本域的价值在**跨域收口**：
// 读商家的报备状态、消费售后的回退标记 —— 这两处不接上，前面几个域的字段就是死的。
import * as db from "@/lib/mock/db";
import { MAX_TAX_RATE, MIN_WITHDRAW_AMOUNT, WITHDRAW_REVIEW_THRESHOLD } from "@/lib/constants";
import { WITHDRAW_TRANSITIONS } from "@/lib/types";
import { MAX_SPLIT_RETRY, SETTLE_FREEZE_MIN_DAYS } from "@/lib/constants";
import { SETTLE_TRANSITIONS, type Settlement } from "@/lib/types";
import type { FinanceApi } from "../contracts/finance";
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

export const financeMock: FinanceApi = {
  // 两条轨道都给：运营要回答的是「这家店的钱到哪一步了」，
  // 分开查等于让一家同时有自营店和第三方店的商家在两个页面之间对照
  listSettlements: async (q = {}) =>
    wait(db.settlements.filter((s) =>
      db.eqHit(q.merchantNo, s.merchantNo)
      && db.eqHit(q.status, s.status)
      && db.eqHit(q.businessMode, s.businessMode ?? undefined))),

  // 失败的指令**也给**：出问题时要看的恰恰是它们
  listSplitRecords: async (q = {}) =>
    wait(db.splitRecords.filter((r) =>
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

  getTaxRule: async () => wait(db.taxRule),

  saveTaxRule: async (v) => {
    if (v.threshold < 0) fail("起征点不能为负", "The threshold cannot be negative");
    if (v.rate < 0) fail("税率不能为负", "The rate cannot be negative");
    if (v.rate > MAX_TAX_RATE) fail(`税率不得超过 ${MAX_TAX_RATE / 100}%，超过一定是配置错误`, `The rate cannot exceed ${MAX_TAX_RATE / 100}% — anything higher is a configuration error`);
    Object.assign(db.taxRule, v, { updatedAt: new Date().toISOString(), updatedBy: "admin" });
    return wait(db.taxRule, 400);
  },
};
