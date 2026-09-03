// 交易：订单与配送、预约排期、自提点履约、售后 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { assertTransition, db, delay, paginate, persist, pushMessage } from "@shared/mock/db";
import type { AppointmentSlot, PickingRow } from "@shared/types";
import { SETTLE } from "@shared/utils/constants";
import {
  PICKUP_LIKE,
  belongsToMerchant,
  currentStoreNo,
  deliveryOverrides,
  findOrder,
  findOrderByAfterSale,
  pickupView,
  pushTimeline,
  scopedToStore,
  settleRefund,
  storeOfOrder,
  takePendingAfterSale,
} from "./_shared";
import type { MerchantApi } from "../contract";

export const orderMock: Pick<MerchantApi,
  "mOrderList"
  | "mOrderDetail"
  | "mShip"
  | "mDelivered"
  | "mConfirmOfflinePay"
  | "mAppointmentSlots"
  | "mOpenAppointmentSlot"
  | "mCloseAppointmentSlot"
  | "mDeliveryRule"
  | "mSaveDeliveryRule"
  | "mPickupOverview"
  | "mPickupOrders"
  | "mPickingList"
  | "mMarkArrived"
  | "mWithdrawPage"
  | "mDeposit"
  | "mDepositTxns"
  | "mPendingInvoice"
  | "mStatement"
  | "mInvoiceTitle"
  | "mMyInvoices"
  | "mSubmitInvoice"
  | "mApplyWithdraw"
  | "mAfterSaleList"
  | "mApproveAfterSale"
  | "mRejectAfterSale"
  | "mConfirmReturn"
> = {
  // ---------------------------------------------------------------- 订单与配送
  async mOrderList(q) {
    const merchantNo = db.merchant.merchantNo;
    // 订单也按当前门店（后端 BizOrderController 五处判当前门店）
    let list = merchantNo
      ? scopedToStore(db.orders.filter((o) => belongsToMerchant(o, merchantNo))) : [];
    if (q.status) list = list.filter((o) => o.status === q.status);
    // 与 status 正交：商家的「待核销」= FULFILLING + 自提/到店核销类
    if (q.fulfillments?.length) {
      const want = new Set(q.fulfillments);
      list = list.filter((o) => want.has(o.fulfillment));
    }
    return delay(paginate(list, q.page, q.size));
  },

  async mOrderDetail(orderNo) {
    return delay({ ...findOrder(orderNo) });
  },

  async mShip(orderNo, expressNo) {
    const o = findOrder(orderNo);
    assertTransition(o.status, "FULFILLING");
    o.status = "FULFILLING";
    o.expressNo = expressNo;
    pushTimeline(o, "已发货");
    pushMessage(
      "TRADE",
      "你的订单已发货",
      `运单号 ${expressNo}，可在订单详情查看物流`,
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(o);
  },

  async mDelivered(orderNo) {
    const o = findOrder(orderNo);
    /*
     * <b>只能操作当前门店的单</b> —— 与后端 `MerchantOrderServiceImpl.require()`
     * 同一条：「只按主体判的话，A 店店员能翻出 B 店的单」。
     * 替身不判的话，这条规则要等真机上第一次跨店点「已送达」才露面。
     */
    const cur = currentStoreNo();
    if (cur && storeOfOrder(o, db.stores) !== cur) {
      throw new Error("这一单不属于当前门店");
    }
    // 商家自送没有骑手轨迹，老板点一下就是送到了 —— 直接进完成态（ADR-005 §5）
    assertTransition(o.status, "COMPLETED");
    o.status = "COMPLETED";
    pushTimeline(o, "已送达");
    pushMessage(
      "TRADE",
      "订单已送达",
      "商家已标记送达，有问题可在订单里申请售后",
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(o);
  },

  async mConfirmOfflinePay(subOrderNo) {
    const o = findOrder(subOrderNo);
    // 商家当面收到钱 → 与支付回调走到同一个状态。**平台不碰这笔钱**，
    // 所以这里没有任何「入账」动作，只有状态与留痕
    assertTransition(o.status, "PAID");
    o.status = "PAID";
    pushTimeline(o, "商家已确认收款");
    persist();
    return delay(o);
  },

  // ---------------------------------------------------------------- 预约排期

  async mAppointmentSlots(storeNo, from, to) {
    return delay(
      db.appointmentSlots
        .filter((s) => s.storeNo === storeNo && s.startAt >= from && s.startAt < to)
        .sort((a, b) => a.startAt - b.startAt)
        .map((s) => ({ ...s })),
    );
  },

  async mOpenAppointmentSlot(storeNo, slot) {
    const row: AppointmentSlot = {
      slotNo: `APS${Date.now()}`,
      storeNo,
      startAt: slot.startAt,
      endAt: slot.endAt,
      capacity: slot.capacity,
      booked: 0,
      remaining: slot.capacity,
      status: "OPEN",
    };
    db.appointmentSlots.push(row);
    persist();
    return delay({ ...row });
  },

  async mCloseAppointmentSlot(slotNo) {
    const row = db.appointmentSlots.find((s) => s.slotNo === slotNo);
    if (!row) throw new Error("时段不存在");
    // 停约**不删行也不赶人**：已经约进来的单还指着它，删掉的话取消时
    // 不知道该把名额还给谁，商家也查不到那天到底接了几单
    row.status = "CLOSED";
    persist();
    return delay({ ...row });
  },

  async mDeliveryRule() {
    // 配送规则是**这家店的**（后端 BizDashboardController#deliveryRule 读 currentStoreNo）
    const cur = currentStoreNo();
    return delay({ ...(cur ? deliveryOverrides.get(cur) ?? db.deliveryRule : db.deliveryRule) });
  },

  async mSaveDeliveryRule(rule) {
    const cur = currentStoreNo();
    if (cur) {
      // 改的是这家店的规则，别把另一家的也改了
      deliveryOverrides.set(cur, { ...rule });
      return delay({ ...rule });
    }
    db.deliveryRule = { ...rule };
    persist();
    return delay({ ...db.deliveryRule });
  },

  // ---------------------------------------------------------------- 自提点履约
  /**
   * 履约总览。三个数都从**同一份订单数据**算出来，不另存计数器 ——
   * 计数器与明细分开维护，迟早会出现「总览说 3 单、点进去只有 2 单」。
   */
  async mPickupOverview() {
    const pickupNo = db.merchant.pickupNo;
    const mine = db.orders.filter(
      (o) => o.fulfillment === "STORE_PICKUP" && (!pickupNo || o.pickupNo === pickupNo),
    );
    const startOfDay = new Date().setHours(0, 0, 0, 0);
    const itemCount = mine
      .filter((o) => o.status === "COMPLETED")
      .reduce((n, o) => n + o.items.reduce((k, it) => k + it.qty, 0), 0);
    return delay({
      pickupNo: pickupNo || "",
      pickupName: db.merchant.name || "",
      pendingVerify: mine.filter((o) => o.status === "FULFILLING"
        && PICKUP_LIKE.has(o.fulfillment)).length,
      // 「批次」= 今天标记过到货的单，按到货动作聚合
      arrivedBatches: mine.filter((o) => o.status !== "PAID" && o.createdAt >= startOfDay)
        .length,
      // 服务费按**已完成**的件数算：货还没交到人手上，这笔钱不该先算进来
      serviceFeeMinor: itemCount * SETTLE.fulfillFeePerItemMinor,
    });
  },

  /** 履约台的单：**按 PickupOrder 的形状发**（子单号 + 裁剪过的字段），与后端一致 */
  async mPickupOrders() {
    const pickupNo = db.merchant.pickupNo;
    return delay(
      db.orders
        .filter((o) => o.fulfillment === "STORE_PICKUP" && (!pickupNo || o.pickupNo === pickupNo))
        .map(pickupView),
    );
  },

  async mPickingList() {
    const pickupNo = db.merchant.pickupNo;
    const map = new Map<string, PickingRow>();
    for (const o of db.orders) {
      if (o.fulfillment !== "STORE_PICKUP") continue;
      if (pickupNo && o.pickupNo !== pickupNo) continue;
      if (!["PAID", "FULFILLING"].includes(o.status)) continue;
      const buyer = o.buyerNickname ?? db.user.nickname;
      for (const it of o.items) {
        const cur = map.get(it.skuNo) ?? {
          goodsNo: it.goodsNo,
          skuNo: it.skuNo,
          title: it.title,
          cover: it.cover,
          spec: it.spec,
          totalQty: 0,
          buyers: [],
        };
        cur.totalQty += it.qty;
        cur.buyers.push({ nickname: buyer, qty: it.qty, orderNo: o.orderNo });
        map.set(it.skuNo, cur);
      }
    }
    return delay([...map.values()].sort((a, b) => b.totalQty - a.totalQty));
  },

  async mMarkArrived(subOrderNos, _pickupNo) {
    const changed: ReturnType<typeof pickupView>[] = [];
    for (const no of subOrderNos) {
      // mock 的主单号当子单号用（一单一商家），与 pickupView 同一口径
      const o = db.orders.find((x) => x.orderNo === no);
      if (!o || o.status !== "PAID") continue;
      assertTransition(o.status, "FULFILLING");
      o.status = "FULFILLING";
      pushTimeline(o, "已到自提点，请及时取货");
      pushMessage(
        "TRADE",
        "到货了，记得来取",
        `取货码 ${o.verifyCode ?? ""}，到 ${o.pickupName ?? "自提点"} 报码即可`,
        `/pages/order/index?orderNo=${o.orderNo}`,
      );
      changed.push(pickupView(o));
    }
    persist();
    return delay(changed);
  },

  // ---------------------------------------------------------------- 售后
  async mWithdrawPage() {
    // mock 里给一个「有钱可提、有一笔在审」的状态 —— 两种情况都要能看到
    return {
      withdrawableMinor: 128_600,
      minAmountMinor: 1000,
      records: [
        { withdrawNo: "WD-MOCK-2", amount: 50_000, availableBalance: 178_600,
          status: "PENDING", appliedAt: "2026-09-01 10:20", decidedAt: null, remark: null },
        { withdrawNo: "WD-MOCK-1", amount: 20_000, availableBalance: 198_600,
          status: "PAID", appliedAt: "2026-08-20 09:00", decidedAt: "2026-08-21 14:30", remark: null },
      ],
    };
  },

  /*
   * mock 给一个**不够**的状态：够的那一半没什么可看的，
   * 而「还差多少 + 限额因此被压着」正是这一页存在的理由。
   */
  async mDeposit() {
    return {
      paidMinor: 150_000,
      frozenMinor: 30_000,
      availableMinor: 120_000,
      requiredMinor: 200_000,
      sufficient: false,
      singleOrderLimitMinor: 50_000,
      dailyAmountLimitMinor: 500_000,
    };
  },

  async mDepositTxns() {
    // 五种流水类型各来一条 —— 少一种，那一种的文案就没人看过
    return [
      { txnNo: "DT-MOCK-5", txnType: "FREEZE", amountMinor: 30_000, balanceAfterMinor: 150_000,
        reason: "买家投诉理赔冻结", operator: "运营小李", createdAt: "2026-09-01 14:20" },
      { txnNo: "DT-MOCK-4", txnType: "DEDUCT", amountMinor: -20_000, balanceAfterMinor: 150_000,
        reason: "理赔扣划：订单 SO-8821", operator: "运营小李", createdAt: "2026-08-28 10:05" },
      // ⚠️ UNFREEZE 是**正数** —— 运营端对 FREEZE/UNFREEZE 都发正值，
      // 后端原样落库（Math.abs 只用在内部算冻结额那一步）。
      // 这里第一版写成负数，于是页面在 mock 下看着对、接真后端就反 ——
      // 替身与真实语义不一致，比没有替身更危险。
      { txnNo: "DT-MOCK-3", txnType: "UNFREEZE", amountMinor: 10_000, balanceAfterMinor: 170_000,
        reason: "争议撤销，解冻", operator: "运营小李", createdAt: "2026-08-20 16:40" },
      { txnNo: "DT-MOCK-2", txnType: "REFUND", amountMinor: -50_000, balanceAfterMinor: 170_000,
        reason: "降档退还", operator: "运营小王", createdAt: "2026-08-10 09:30" },
      { txnNo: "DT-MOCK-1", txnType: "PAY", amountMinor: 220_000, balanceAfterMinor: 220_000,
        reason: "入驻缴纳", operator: "运营小王", createdAt: "2026-07-01 11:00" },
    ];
  },

  /*
   * 待开票摘要。**跨两个月**是刻意的 —— 一张票覆盖全部待开票的单，
   * 不按月筛，而这件事只有跨月时才看得出来。
   */
  async mPendingInvoice() {
    return {
      payableMinor: 386_400,
      billCount: 3,
      periods: ["2026-07", "2026-08"],
      settleNos: ["ST-MOCK-1", "ST-MOCK-2", "ST-MOCK-3"],
    };
  },

  /*
   * 对账单。**三行是三种不同的状态**：已结算带凭证号、已分账未付款、待对账。
   * 只给「都结完了」那一种的话，「凭证号为空该显示什么」这件事就没人看过。
   */
  async mStatement(period?: string) {
    const lines = [
      { settleNo: "ST-MOCK-1", orderNo: "SO-1001", subOrderNo: "SUB-1001",
        grossMinor: 200_000, commissionMinor: 10_000, serviceFeeMinor: 2_000,
        netMinor: 188_000, commissionRate: 500, status: "PAID", invoiceStatus: "VERIFIED",
        settledAt: 1_756_000_000_000, voucherNo: "BANK-20260820-001" },
      { settleNo: "ST-MOCK-2", orderNo: "SO-1002", subOrderNo: "SUB-1002",
        grossMinor: 150_000, commissionMinor: 7_500, serviceFeeMinor: 1_500,
        netMinor: 141_000, commissionRate: 500, status: "SPLIT", invoiceStatus: "PENDING",
        settledAt: null, voucherNo: null },
      { settleNo: "ST-MOCK-3", orderNo: "SO-1003", subOrderNo: "SUB-1003",
        grossMinor: 60_000, commissionMinor: 3_000, serviceFeeMinor: 600,
        netMinor: 56_400, commissionRate: 500, status: "PENDING_RECON", invoiceStatus: "PENDING",
        settledAt: null, voucherNo: null },
    ];
    return {
      period: period ?? "",
      businessMode: "SELF_OPERATED",
      grossMinor: 410_000, commissionMinor: 20_500, serviceFeeMinor: 4_100,
      netMinor: 385_400, billCount: 3,
      voucherNos: ["BANK-20260820-001"],
      lines,
    };
  },

  async mInvoiceTitle() {
    return {
      companyName: "邻高科技有限公司",
      taxNo: "91310000MA1K00000X",
      address: "上海市徐汇区某路 100 号",
      phone: "021-12345678",
      bankAccount: "招商银行上海分行 1234 5678 9012",
    };
  },

  async mMyInvoices() {
    // 三种状态各一条：驳回那条要能看到原因，否则他只会原样再传一次
    return [
      { invoiceNo: "PI-MOCK-3", period: "2026-08", invoiceNumber: "00123456",
        invoiceType: "GENERAL", titleName: "邻高科技有限公司", amountMinor: 386_400,
        status: "PENDING", rejectReason: null, settleNos: ["ST-MOCK-1"] },
      { invoiceNo: "PI-MOCK-2", period: "2026-07", invoiceNumber: "00123455",
        invoiceType: "SPECIAL", titleName: "邻高科技有限公司", amountMinor: 120_000,
        status: "REJECTED", rejectReason: "票面金额与应付合计不符，请核对后重开",
        settleNos: ["ST-MOCK-0"] },
      { invoiceNo: "PI-MOCK-1", period: "2026-06", invoiceNumber: "00123454",
        invoiceType: "GENERAL", titleName: "邻高科技有限公司", amountMinor: 98_000,
        status: "VERIFIED", rejectReason: null, settleNos: [] },
    ];
  },

  async mSubmitInvoice(v: { period: string; invoiceNumber: string; invoiceType: string;
    titleName: string; amountMinor: number }) {
    return {
      invoiceNo: "PI-MOCK-NEW", period: v.period, invoiceNumber: v.invoiceNumber,
      invoiceType: v.invoiceType, titleName: v.titleName, amountMinor: v.amountMinor,
      status: "PENDING", rejectReason: null, settleNos: ["ST-MOCK-1"],
    };
  },

  async mApplyWithdraw(amountMinor: number) {
    return { withdrawNo: "WD-MOCK-NEW", amount: amountMinor, availableBalance: 128_600,
      status: "PENDING", appliedAt: "刚刚", decidedAt: null, remark: null };
  },

  async mAfterSaleList() {
    const merchantNo = db.merchant.merchantNo;
    /*
     * 返回**售后单**，不是订单。后端 /biz/after-sale 给的就是 List<AfterSaleVO>，
     * 而这里此前返回的是订单、且按 `o.status === "REFUNDING"` 筛 ——
     * 两个错误叠在一起：订单永远不会是这个状态（那是售后单的状态），
     * 于是商家端「售后」页签恒为空；就算筛出来了，形状也和后端对不上。
     */
    return delay(
      db.orders
        .filter((o) => o.afterSale && belongsToMerchant(o, merchantNo))
        .map((o) => o.afterSale!),
    );
  },

  async mApproveAfterSale(afterSaleNo, reply) {
    const o = takePendingAfterSale(afterSaleNo);
    const as = o.afterSale!;
    as.merchantReply = reply;
    as.updatedAt = Date.now();

    /**
     * 同意后**按类型分流**，这是售后闭环此前缺的那半段：
     *   · 仅退款   → 直接退款
     *   · 退货退款 → 等用户寄回、商家确认收货**之后**才退款
     * 两者合成一条路的后果是「退款了货没回来」。
     */
    if (as.type === "RETURN_REFUND") {
      as.status = "REFUNDING";
      pushTimeline(o, "商家已同意退货，等待寄回");
      pushMessage(
        "TRADE",
        "退货申请已通过",
        "请寄回商品并在订单里填写退货运单号",
        `/pages/order/index?orderNo=${o.orderNo}`,
      );
      persist();
      return delay(o.afterSale!);
    }

    settleRefund(o, "商家已同意退款");
    persist();
    return delay(o.afterSale!);
  },

  async mRejectAfterSale(afterSaleNo, reply) {
    const o = takePendingAfterSale(afterSaleNo);
    const as = o.afterSale!;
    if (!reply.trim()) throw new Error("驳回必须填写理由");
    as.merchantReply = reply;
    as.updatedAt = Date.now();
    // 驳回**不改订单状态** —— 用户还得能上升平台，直接置回已完成就把路堵死了
    as.status = "REJECTED";
    pushTimeline(o, `商家驳回：${reply}`);
    pushMessage(
      "TRADE",
      "售后被驳回",
      reply,
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(o.afterSale!);
  },

  async mConfirmReturn(afterSaleNo) {
    const o = findOrderByAfterSale(afterSaleNo);
    const as = o.afterSale!;
    if (as.type !== "RETURN_REFUND") throw new Error("该售后单不是退货退款");
    // 用户还没寄（没填运单号）就点确认收货，多半是误操作
    // 后端没有独立的「等寄回 / 已收货」两态：同意即 REFUNDING，
    // 是否已寄回看 returnExpressNo 有没有值
    if (as.status !== "REFUNDING") throw new Error("该售后已处理或状态不对");
    if (!as.returnExpressNo) throw new Error("用户还未填写退货运单号");
    as.updatedAt = Date.now();
    pushTimeline(o, "商家已确认收到退货");
    // 确认收货与退款是同一个动作的两半，中间不留悬空态
    settleRefund(o, "退款已发起");
    persist();
    return delay(o.afterSale!);
  },
};
