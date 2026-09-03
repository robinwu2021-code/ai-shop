// 结算与账期、到货异常、积分服务费 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { assertTransition, db, delay, persist, pushMessage } from "@shared/mock/db";
import type { MyDebt, MySettleBatch, SettleBill, VerifyBatchResult } from "@shared/types";
import { SETTLE } from "@shared/utils/constants";
import {
  AFTER_SALE_MS,
  batchNoOf,
  batchStateOf,
  belongsToMerchant,
  dueOf,
  findOrder,
  mockState,
  pickupView,
  pointsAccount,
  pointsFeeRecords,
  pushTimeline,
} from "./_shared";
import type { MerchantApi } from "../contract";

export const settleMock: Pick<MerchantApi,
  "mRateCard"
  | "mIncomeSummary"
  | "mSettleList"
  | "mSettleBatches"
  | "mMyDebt"
  | "mReportShortage"
  | "mVerify"
  | "mVerifyBatch"
  | "mVerifySearch"
  | "mPointsAccount"
  | "mPointsRecords"
  | "mPointsToggle"
> = {
  // ---------------------------------------------------------------- 结算
  /**
   * 费率卡。**费率是万分比整数**（与后端 RateCardVO 一致）：2% 存成 200。
   * 直接当百分数显示会把 2% 显示成 200%，这种错在界面上看着还挺"合理"。
   */
  async mRateCard() {
    const pct = (r: number) => Math.round(r * 10000);
    return delay({
      merchantOwnedRate: pct(SETTLE.commissionRate.MERCHANT_OWNED),
      platformRate: pct(SETTLE.commissionRate.PLATFORM),
      note: "自带客流（扫店铺码进店）零佣金；平台客流按公示费率收取。费率以下单时快照为准，调整不影响历史订单。",
    });
  },

  /**
   * 收入汇总。**从 mSettleList 的结果推**，不另写一套 ——
   * 两处口径不同的话，总览的数和明细加起来对不上，
   * 而那是这一屏最不该出的错（商家会以为钱少了）。
   */
  async mIncomeSummary(allStores) {
    const bills = await this.mSettleList(allStores);
    const sum = (f: (b: SettleBill) => boolean) =>
      bills.filter(f).reduce((n, b) => n + b.netMinor, 0);
    const inFlight = bills.filter((b) => b.status === "SPLIT");
    return delay({
      receivedMinor: sum((b) => b.status === "SPLIT_CONFIRMED" || b.status === "PAID"),
      inFlightMinor: sum((b) => b.status === "SPLIT"),
      pendingMinor: sum((b) => !["SPLIT", "SPLIT_CONFIRMED", "PAID", "OFFLINE_SETTLED", "REVERSED"].includes(b.status)),
      offlineMinor: sum((b) => b.status === "OFFLINE_SETTLED"),
      inFlightCount: inFlight.length,
      oldestInFlightAt: inFlight.length
        ? Math.min(...inFlight.map((b) => b.splitAt ?? b.createdAt))
        : null,
    });
  },

  async mSettleList(allStores) {
    const merchantNo = db.merchant.merchantNo;
    /*
     * **一个子订单一行**，与后端 stl_bill 同形 —— 这里此前造的是一套「按周聚合的账单」
     * （billNo / periodStart / orderCount），后端从来没有过那个模型。
     * 页面照着 mock 写，于是连真后端时字段整片 undefined，而 mock 下一直是绿的。
     */
    const settled = db.orders.filter(
      (o) => belongsToMerchant(o, merchantNo) && ["COMPLETED", "REFUNDED"].includes(o.status),
    );
    const home = db.stores.find((s) => s.isDefault) ?? db.stores[0];
    const scope = allStores ? null : home?.storeNo;

    return delay(
      settled
        .filter(() => !scope || Boolean(home))
        .map((o) => {
          const gross = o.amount.payableMinor;
          // 佣金按客流来源分档：自带客流零佣金（ADR-004 §6）
          const rate = SETTLE.commissionRate[o.trafficSource ?? "PLATFORM"];
          const commission = Math.round(gross * rate);
          // 自提点履约服务费按件。供货方付、承接方收，两个角色都是自己时账面抵消
          const serviceFee =
            o.fulfillment === "STORE_PICKUP"
              ? o.items.reduce((n, it) => n + it.qty, 0) * SETTLE.fulfillFeePerItemMinor
              : 0;
          return {
            settleNo: `SB${o.orderNo}`,
            subOrderNo: o.orderNo,
            orderNo: o.orderNo,
            merchantNo,
            grossMinor: gross,
            commissionMinor: commission,
            serviceFeeMinor: serviceFee,
            netMinor: gross - commission - serviceFee,
            trafficSource: o.trafficSource ?? "PLATFORM",
            commissionRate: Math.round(rate * 10000),
            // 退过款的走回退态：账面上不能出现「退过款还照结」的钱（ADR-002 §3）
            status: o.status === "REFUNDED" ? ("REVERSED" as const) : ("SPLIT" as const),
            createdAt: o.createdAt,
            splitAt: o.status === "REFUNDED" ? undefined : o.createdAt,
            /*
             * T2 可结算 = 完成 + 售后期。**退款单不给可结算时刻** ——
             * 给了的话页面会显示一个「预计到账日」，而那笔钱不会到。
             */
            settleableAt: o.status === "REFUNDED" ? undefined : o.createdAt + AFTER_SALE_MS,
            dueAt: o.status === "REFUNDED" ? undefined : dueOf(o.createdAt + AFTER_SALE_MS),
            batchNo: o.status === "REFUNDED" ? undefined : batchNoOf(dueOf(o.createdAt + AFTER_SALE_MS)),
            batchStatus: o.status === "REFUNDED"
              ? undefined
              : batchStateOf(dueOf(o.createdAt + AFTER_SALE_MS)).status,
            batchBlockedReason: o.status === "REFUNDED"
              ? undefined
              : batchStateOf(dueOf(o.createdAt + AFTER_SALE_MS)).blockedReason,
            storeNo: home?.storeNo,
            // 门店没单独配号就走主体默认号 —— 那就是合并结算
            payMerchantNo: home?.payMerchantNo ?? "PM-MOCK-ENTITY",
          };
        }),
    );
  },

  /**
   * 我的账期批次。**从 mSettleList 的结果推**，与收入汇总同一个理由 ——
   * 两处口径不同的话，「这一批 3 笔」点进去只有 2 笔，
   * 而商家会认为少给了一笔钱，不会认为是界面在骗人。
   *
   * 第一批固定造成 BLOCKED：**挂起是这一页唯一需要商家看懂的状态**，
   * 而它在真实数据里很稀少 —— mock 里不造，这条渲染路径就永远没人看过。
   */
  async mSettleBatches() {
    const bills = await this.mSettleList(true);
    const byDue = new Map<number, SettleBill[]>();
    for (const b of bills) {
      if (!b.dueAt) continue;
      const group = byDue.get(b.dueAt);
      if (group) group.push(b);
      else byDue.set(b.dueAt, [b]);
    }
    return delay(
      [...byDue.keys()].sort((a, c) => a - c).map((due): MySettleBatch => {
        const group = byDue.get(due)!;
        return {
          batchNo: batchNoOf(due),
          payChannel: "WECHAT",
          settleCycle: "T1",
          dueAt: due,
          billCount: group.length,
          netMinor: group.reduce((n, b) => n + b.netMinor, 0),
          ...batchStateOf(due),
        };
      }),
    );
  },

  /**
   * 我的欠款。**默认 0** —— 绝大多数商家从没欠过，
   * mock 里长期造一笔欠款会让每个人都以为这一块总是显示的，
   * 于是「余额为 0 时整块不出现」这条分支从来没被看过。
   *
   * 余额**从流水推**，不另写一个数：两处对不上的时候，
   * 商家信的是余额，而能解释的是流水。
   */
  async mMyDebt(): Promise<MyDebt> {
    /** 改成 true 看有欠款时的样子 */
    const OWE: boolean = false;
    const now = Date.now();
    const txns: MyDebt["txns"] = OWE
      ? [
          {
            txnNo: "DT0001", txnType: "INCUR", amountMinor: 3200, balanceAfterMinor: 3200,
            sourceType: "REFUND", sourceNo: "RF20260801001",
            reason: "退款时这笔货款已经放出，先记欠款", at: now - 3 * 86_400_000,
          },
          {
            txnNo: "DT0002", txnType: "OFFSET", amountMinor: -2000, balanceAfterMinor: 1200,
            batchNo: "STB20260810", reason: "从本批货款中抵扣", at: now - 86_400_000,
          },
        ]
      : [];
    return delay({
      balanceMinor: txns.length ? txns[txns.length - 1]!.balanceAfterMinor : 0,
      txns,
    });
  },

  // ---------------------------------------------------------------- 到货异常
  async mReportShortage(subOrderNo, payload) {
    const o = findOrder(subOrderNo);
    const label = payload.kind === "SHORTAGE" ? "短少" : "破损";
    pushTimeline(o, `自提点上报${label} ${payload.qty} 件：${payload.note}`);
    // 只留痕、通知用户，**不自动退款** —— 责任在供货方还是承接方尚未定（矩阵 M4），
    // 自动退等于默认平台兜底
    pushMessage(
      "TRADE",
      `商品${label}已上报`,
      `${payload.note}。我们会尽快处理，你也可以直接申请售后`,
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(pickupView(o));
  },

  /**
   * 核销。**失败不抛异常，返回 `success: false` + reason** —— 与真实后端同口径。
   *
   * 此前 mock 用抛异常表达失败，而后端把失败当业务结果回（code 0）。
   * 端上照着 mock 写「try/catch，能走到下一行就是成功」，
   * 于是**真机上任何一次失败都提示「核销成功」**。
   * mock 与后端在「失败长什么样」上分岔，比在字段名上分岔危险得多。
   */
  async mVerify(code) {
    const o = db.orders.find((x) => x.verifyCode === code);
    if (!o) return delay({ success: false, subOrderNo: null, reason: "CODE_NOT_FOUND" });
    if (o.status === "COMPLETED") {
      return delay({ success: false, subOrderNo: o.orderNo, reason: "ALREADY_VERIFIED" });
    }
    if (o.status === "CANCELLED" || o.status === "REFUNDED") {
      return delay({ success: false, subOrderNo: o.orderNo, reason: "REFUNDED" });
    }
    if (o.status === "WAIT_PAY") {
      return delay({ success: false, subOrderNo: o.orderNo, reason: "NOT_PAID" });
    }
    const pickupNo = db.merchant.pickupNo;
    if (pickupNo && o.pickupNo && o.pickupNo !== pickupNo) {
      return delay({ success: false, subOrderNo: o.orderNo, reason: "NOT_THIS_PICKUP" });
    }
    if (o.status === "PAID") {
      o.status = "FULFILLING";
      pushTimeline(o, "已到自提点");
    }
    assertTransition(o.status, "COMPLETED");
    o.status = "COMPLETED";
    pushTimeline(o, "已核销完成");
    persist();
    return delay({ success: true, subOrderNo: o.orderNo, reason: null });
  },

  /**
   * 批量核销。**逐条尝试、失败逐条回报**，不整批回滚 ——
   * 一张废码不该让另外四单白扫；而「3 成功 2 失败」这种汇总，店主还得自己一个个找出是哪两单。
   * 单条的三校验完全复用，避免两条路的规则各写一遍（那必然漂）。
   */
  async mVerifyBatch(codes) {
    const failed: VerifyBatchResult["failed"] = [];
    let successCount = 0;
    for (const code of codes) {
      try {
        await this.mVerify(code);
        successCount += 1;
      } catch (e) {
        failed.push({ code, reason: (e as Error).message });
      }
    }
    return delay({ successCount, failed });
  },

  /**
   * 按取货码**片段**搜单。输码核销走不通时的最后一条路：
   * 码磨花了、屏幕反光、邻居只记得后四位。
   *
   * 与真后端同口径：**子串匹配**（`contains`），且只在本自提点的单里找 ——
   * 跨点搜出来的单他也核销不了，列出来只会让人以为「明明有这单为什么核不了」。
   */
  async mVerifySearch(keyword) {
    const k = keyword.trim();
    const pickupNo = db.merchant.pickupNo;
    if (!k) return delay([]);
    return delay(
      db.orders
        .filter(
          (o) =>
            !!o.verifyCode
            && o.verifyCode.includes(k)
            && (!pickupNo || o.pickupNo === pickupNo),
        )
        .map(pickupView),
    );
  },

  // ---- 积分：商家只感知发分服务费与开关（V34）。
  // 抵扣、补差、资金池对他全部不可见 —— 他收到的是订单全额减各项费用。
  async mPointsAccount() {
    return delay(pointsAccount());
  },

  async mPointsRecords() {
    return delay(pointsFeeRecords());
  },

  async mPointsToggle(req) {
    mockState.pointsEnabled = req.enabled;
    return delay(pointsAccount());
  },
};
