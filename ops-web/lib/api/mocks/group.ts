// 覆盖范围：商家团与求团撮合（P-8）。约束遵循 ADR-003：不做事前审核，用改价公示 + 信用。
import * as db from "@/lib/mock/db";
import { MAX_QUOTE_PRICE_CHANGES, MAX_MERCHANT_BREACH } from "@/lib/constants";
import { GROUP_TRANSITIONS, type GroupCampaign, type Quote } from "@/lib/types";
import type { GroupApi } from "../contracts/group";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function findGroup(no: string): GroupCampaign {
  const g = db.groupCampaigns.find((x) => x.groupNo === no);
  if (!g) notFound("团", "Group buy", no);
  return g;
}
function findQuote(no: string): Quote {
  const q = db.quotes.find((x) => x.quoteNo === no);
  if (!q) notFound("报价", "Quote", no);
  return q;
}

export const groupMock: GroupApi = {
  listGroupCampaigns: (q = {}) =>
    wait(
      db.paginate(db.groupCampaigns, q.page, q.size, (g) =>
        db.eqHit(q.status, g.status) &&
        db.kwHit(q.keyword, g.groupNo, g.merchantName, g.skuTitle),
      ),
    ),

  auditGroupCampaign: async (groupNo, pass, reason) => {
    const g = findGroup(groupNo);
    if (g.status !== "PENDING_AUDIT") fail("该团已审核，请刷新列表", "This group buy has already been reviewed — refresh the list");
    if (pass) {
      // 1 个人不叫团；团购价不低于原价的话"团购"就是假的 —— 这两条不能只做 UI 提示
      if (g.minCount < 2) fail("起团人数至少为 2", "A group buy needs at least 2 people");
      if (g.groupPrice >= g.originPrice) fail("团购价必须低于原价", "The group price has to be below the regular price");
      g.status = "RUNNING";
    } else {
      if (!reason?.trim()) fail("驳回必须填写原因，商家会原样看到", "Rejection needs a reason — the merchant sees it verbatim");
      g.status = "FAILED";
    }
    return wait(g, 400);
  },

  setGroupStatus: async (groupNo, status) => {
    const g = findGroup(groupNo);
    db.assertTransition(GROUP_TRANSITIONS, g.status, status, "团", "Group buy");
    g.status = status;
    return wait(g, 400);
  },

  listDemands: (q = {}) =>
    wait(
      db.paginate(db.demandOrders, q.page, q.size, (d) =>
        db.scopeHit(q, d) &&
        db.eqHit(q.status, d.status) &&
        db.kwHit(q.keyword, d.demandNo, d.title, d.initiatorNickname, d.communityName),
      ),
    ),

  listQuotes: (q = {}) =>
    wait(
      db.paginate(db.quotes, q.page, q.size, (x) =>
        db.eqHit(q.demandNo, x.demandNo) &&
        db.kwHit(q.keyword, x.quoteNo, x.demandTitle, x.merchantName),
      ),
    ),

  assignQuote: async ({ demandNo, merchantNo, price, minQty, validTo }) => {
    const demand = db.demandOrders.find((d) => d.demandNo === demandNo);
    if (!demand) notFound("需求单", "Demand request", demandNo);
    if (demand.status === "CLOSED" || demand.status === "CHOSEN") fail("该需求已关闭或已选定报价", "This request is closed or a quote has already been chosen");
    if (price <= 0 || minQty < 1) fail("单价与起订量必须为正数", "Unit price and minimum quantity must both be positive");
    // 同一需求同一商家只能有一条：要调价走改价（留痕），而不是再发一条把旧的顶掉
    if (db.quotes.some((x) => x.demandNo === demandNo && x.merchantNo === merchantNo)) {
      fail("该商家已对本需求报过价，请改价而不是重复报价", "This merchant already quoted on this request — revise the price instead of quoting again");
    }
    // 毁约累计到阈值就禁止报价（ADR-003 用信用代替事前审核）。
    // 计数取**商家档案**的 breachCount：毁约可能发生在报价之外（成团后不发货），只数报价表会漏。
    const merchant = db.merchants.find((m) => m.merchantNo === merchantNo);
    const breaches = merchant?.breachCount ?? 0;
    if (breaches >= MAX_MERCHANT_BREACH) {
      fail(`该商家累计毁约 ${breaches} 次，已被限制报价`, `${breaches} breaches on record — this merchant is barred from quoting`);
    }
    const rec: Quote = {
      quoteNo: db.nextNo("QT", db.quotes, 9000, "quoteNo"),
      demandNo, demandTitle: demand.title,
      merchantNo, merchantName: merchant?.name ?? merchantNo,
      price, minQty, validTo, priceChanges: 0, breached: false,
      createdAt: "2026-08-06T00:00:00Z",
    };
    db.quotes.unshift(rec);
    demand.quoteCount += 1;
    demand.status = "QUOTING";
    return wait(rec, 400);
  },

  changeQuotePrice: async (quoteNo, price) => {
    const q = findQuote(quoteNo);
    if (price <= 0) fail("单价必须为正数", "The unit price must be positive");
    // ADR-003：不禁止改价，但每次留痕；改太多次本身就是信号，超阈即锁
    if (q.priceChanges >= MAX_QUOTE_PRICE_CHANGES) {
      fail(`已改价 ${q.priceChanges} 次，达到上限后不可再改（改价已公示给参团用户）`, `Priced changed ${q.priceChanges} times already — the cap is reached, and every change was shown to the people who joined`);
    }
    q.price = price;
    q.priceChanges += 1;
    return wait(q, 400);
  },

  markQuoteBreached: async (quoteNo) => {
    const q = findQuote(quoteNo);
    if (q.breached) fail("该报价已标记毁约", "This quote is already marked as breached");
    q.breached = true;
    // 同时累加商家信用档案：两处不同步的话，「限制报价」会按一个永远长不大的数判
    const merchant = db.merchants.find((m) => m.merchantNo === q.merchantNo);
    if (merchant) merchant.breachCount += 1;
    return wait(q, 400);
  },
};
