// 覆盖范围：支付管理（P-4.2）。
//
// 规则全部落在 mock 层，页面写不出违规操作 —— 这是本项目一贯的做法：
// 前端拦一下只是提示，规则在这里才是真的。
import * as db from "@/lib/mock/db";
import { MAX_UNPAID_CLOSE_MINUTES, MIN_UNPAID_CLOSE_MINUTES } from "@/lib/constants";
import type { ReconDiff } from "@/lib/types";
import type { PaymentApi } from "../contracts/payment";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function find(diffNo: string): ReconDiff {
  const d = db.reconDiffs.find((x) => x.diffNo === diffNo);
  if (!d) notFound("对账差异", "Reconciliation difference", diffNo);
  return d;
}

/** 已经处置过的不许再动：重复处置会二次补单或二次退款，那是真金白银。 */
function assertOpen(d: ReconDiff) {
  if (d.status !== "PENDING") fail(`${d.diffNo} 已处置（${d.status}），不能重复处置`, `${d.diffNo} is already resolved (${d.status}) — handling it twice means refunding or re-creating twice`);
}

export const paymentMock: PaymentApi = {
  /*
   * mock 里**固定返回「渠道账单未接入」** —— 那是今天的真实情况。
   * 造成 true 的话，开发期永远看不到那条提示条长什么样，
   * 而它恰恰是这一页最要紧的一句：不说的话「今天没有差异」是句假话。
   */
  reconCoverage: async () =>
    wait({
      channelBillConnected: false,
      note: "当前只有平台侧自查：扫我方停在待支付的收款流水，逐笔向通道查单。"
        + "「渠道扣了钱而我方没有记录」这一类差异**现在看不见** —— "
        + "要等通道开放账单下载能力。所以这张表为空不等于今天账是平的。",
    }),

  /*
   * 渠道报文。**五种结论各造一条**，尤其是被拒的那三种 ——
   * 出事那天最想看的正是它们，而只造「成功」的话
   * 「拒绝原因该显示在哪」这件事就没人看过。
   *
   * 其中一条**没有单号**（验签失败时我方拿不到）：
   * 它是「按单号筛会滤掉最该看的行」那句提示的活例子。
   */
  channelMessages: async () =>
    wait({
      records: [
        { messageNo: "CM-5", payChannel: "WECHAT", msgType: "CALLBACK",
          api: "/callback/wechat/pay", bizNo: null, paymentNo: null, outcome: "REJECTED",
          reason: "验签失败：证书序列号不在信任列表", payload: '{"id":"…","resource":"[已脱敏]"}',
          headers: '{"Wechatpay-Serial":"[已脱敏]"}', createdAt: 1_756_100_000_000 },
        { messageNo: "CM-4", payChannel: "WECHAT", msgType: "CALLBACK",
          api: "/callback/wechat/pay", bizNo: "SO-1009", paymentNo: null, outcome: "REJECTED",
          reason: "回查说未支付，与回调不一致 —— 已回 FAIL", payload: '{"trade_state":"NOTPAY"}',
          headers: "{}", createdAt: 1_756_090_000_000 },
        { messageNo: "CM-3", payChannel: "ALIPAY", msgType: "SEND",
          api: "alipay.trade.query", bizNo: "SO-1008", paymentNo: "PAY-8", outcome: "FAILED",
          reason: "调用超时（3 次重试均失败）", payload: '{"out_trade_no":"SO-1008"}',
          headers: null, createdAt: 1_756_080_000_000 },
        { messageNo: "CM-2", payChannel: "WECHAT", msgType: "CALLBACK",
          api: "/callback/wechat/pay", bizNo: "SO-1007", paymentNo: null, outcome: "RECEIVED",
          reason: null, payload: '{"id":"…"}', headers: "{}", createdAt: 1_756_070_000_000 },
        { messageNo: "CM-1", payChannel: "WECHAT", msgType: "CALLBACK",
          api: "/callback/wechat/pay", bizNo: "SO-1006", paymentNo: "PAY-6", outcome: "ACCEPTED",
          reason: null, payload: '{"trade_state":"SUCCESS"}', headers: "{}",
          createdAt: 1_756_060_000_000 },
      ],
      total: 5, pageNo: 1, size: 20,
      note: "报文已脱敏（签名、证书序列号、Authorization 都不入库），"
        + "不能拿去重放验签 —— 要验签请到通道后台调原件。",
    }),

  /*
   * 四条轴。**各造一种形态**：正常有差异、零差异、跑失败。
   * 全造成「零差异」的话，「这条轴今天没跑成」那个提示永远看不见 ——
   * 而它与「今天没问题」在页面上长得一样、含义完全相反。
   */
  reconAxes: async () =>
    wait([
      {
        axis: "PAYMENT",
        outcome: { scanned: 12, resolved: 2, opened: 1, deferred: 1 },
        coverage: { complete: false, note: "只有平台侧自查，渠道账单比对未接入 —— 「渠道扣了钱而我方没记录」这一类看不见。" },
        error: null,
      },
      {
        axis: "SPLIT",
        outcome: { scanned: 8, resolved: 0, opened: 8, deferred: 0 },
        coverage: { complete: false, note: "分账网关目前是桩实现，每一笔已发出的单都不会有回执 —— 这条轴报出来的量反映的是这件事，不是通道出了问题。" },
        error: null,
      },
      {
        axis: "PAYOUT",
        outcome: { scanned: 3, resolved: 0, opened: 0, deferred: 0 },
        coverage: { complete: false, note: "只查「已付款没流水号」与「一个流水号多张单」。银行流水未接入 —— 钱到底有没有划出去看不见。" },
        error: null,
      },
      {
        axis: "POINTS_POOL",
        outcome: null,
        coverage: { complete: false, note: "只判恒等式，不逐笔定位。失衡目前只写日志，没有告警接收方。" },
        error: "java.lang.IllegalStateException: 积分池快照读取超时",
      },
    ]),

  listReconDiffs: (q = {}) =>
    wait(
      db.paginate(db.reconDiffs, q.page, q.size, (d) =>
        db.eqHit(q.billDate, d.billDate) &&
        db.eqHit(q.type, d.type) &&
        db.eqHit(q.status, d.status) &&
        db.kwHit(q.keyword, d.diffNo, d.channelTxnNo, d.orderNo),
      ),
    ),

  resolveReconDiff: async ({ diffNo, action, resolution }) => {
    const d = find(diffNo);
    assertOpen(d);
    if (!resolution.trim()) fail("处置结论不能为空 —— 没有结论的「已处理」等于没处理", "A resolution note is required — “handled” with no note is not handled");

    if (d.type === "CHANNEL_ONLY") {
      // 渠道收了钱、平台没记：只有补单与退款两条路，必须选一条
      if (!action) fail("渠道有、平台无的差异必须选择处置方式：补单或退款", "A channel-only difference needs a choice: create the order or refund it");
      if (d.channelAmount <= 0) fail("渠道金额为 0，无法补单或退款", "The channel amount is 0 — there is nothing to create or refund");
      if (action === "CREATE_ORDER") {
        // 补单金额恒等于渠道实收 —— 不给调整入口：能改就一定会有人改错
        d.recoveredOrderNo = `SO${d.billDate.replace(/-/g, "")}R${db.reconDiffs.filter((x) => x.recoveredOrderNo).length + 1}`;
      }
    } else if (action) {
      // 另外两类没有"补单"的语义，传了就是调用方理解错了，直接拒绝而不是忽略
      fail(`${d.type} 类型的差异不支持补单/退款处置，请填写结论后标记已处理`, `A ${d.type} difference cannot be created or refunded — write the resolution and mark it handled`);
    }

    d.status = "RESOLVED";
    d.resolution = resolution.trim();
    d.resolvedAt = new Date().toISOString();
    d.resolvedBy = "admin";
    return wait(d, 350);
  },

  ignoreReconDiff: async ({ diffNo, resolution }) => {
    const d = find(diffNo);
    assertOpen(d);
    if (!resolution.trim()) fail("忽略也要写明理由，否则下个月同样的差异没人知道为什么放过", "Ignoring needs a reason too, or next month nobody knows why this one was let through");
    d.status = "IGNORED";
    d.resolution = resolution.trim();
    d.resolvedAt = new Date().toISOString();
    d.resolvedBy = "admin";
    return wait(d, 350);
  },

  getCloseRule: async () => wait(db.closeRule),

  saveCloseRule: async (v) => {
    if (!Number.isInteger(v.unpaidMinutes) || v.unpaidMinutes < MIN_UNPAID_CLOSE_MINUTES) {
      fail(`关单时限不得少于 ${MIN_UNPAID_CLOSE_MINUTES} 分钟：关得太快会把正在付款的用户关掉，那正是掉单的来源`, `The close window cannot be under ${MIN_UNPAID_CLOSE_MINUTES} minutes: closing that fast cuts off customers mid-payment, which is exactly where dropped orders come from`);
    }
    if (v.unpaidMinutes > MAX_UNPAID_CLOSE_MINUTES) {
      fail(`关单时限不得超过 ${MAX_UNPAID_CLOSE_MINUTES} 分钟（一天），否则库存会被长期占住`, `The close window cannot exceed ${MAX_UNPAID_CLOSE_MINUTES} minutes (a day), or stock stays tied up`);
    }
    if (v.remindBeforeMinutes < 0) fail("提醒提前量不能为负", "The reminder lead time cannot be negative");
    if (v.remindBeforeMinutes >= v.unpaidMinutes) {
      fail("提醒提前量必须小于关单时限，否则提醒发出时订单已经关了", "The reminder must come before the close window, otherwise it goes out after the order is already closed");
    }
    Object.assign(db.closeRule, v, { updatedAt: new Date().toISOString(), updatedBy: "admin" });
    return wait(db.closeRule, 350);
  },
};
