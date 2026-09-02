// 覆盖范围：门店主页治理（P-10.1）。
import * as db from "@/lib/mock/db";
import { MIN_ENABLED_SECTIONS } from "@/lib/constants";
import type { StoreApi } from "../contracts/store";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

export const storeMock: StoreApi = {
  // ── 门店档案（P-11.2.1）────────────────────────────────────────

  listStores: (q = {}) =>
    wait(
      db.paginate(db.stores, q.page, q.size, (s) =>
        db.eqHit(q.merchantNo, s.merchantNo) &&
        db.eqHit(q.status, s.status) &&
        db.eqHit(q.businessMode, s.businessMode) &&
        db.kwHit(q.keyword, s.storeNo, s.name, s.address, s.merchantName),
      ),
    ),

  getStore: async (storeNo) => {
    const s = db.stores.find((x) => x.storeNo === storeNo);
    if (!s) notFound("门店", "Store", storeNo);
    return wait(s);
  },

  /*
   * 固定数字，不随机：mock 数据每次刷新都变的话，截图对不上、测试也不稳
   * （与本文件顶部那份种子同一个理由）。ownedTrafficRate 给一个非 0 非 1 的值 ——
   * 0 或 1 会让「自带客流占比」那一格看起来像没接上数据。
   */
  getStoreStats: async (storeNo) => {
    const s = db.stores.find((x) => x.storeNo === storeNo);
    if (!s) notFound("门店", "Store", storeNo);
    return wait({
      storeNo, merchantNo: s.merchantNo,
      todayOrders: 12, todayGmvMinor: 38650,
      monthOrders: 305, monthGmvMinor: 1042300,
      ownedTrafficRate: 0.42,
      toShip: 3, toDeliver: 2, toStock: 1,
    });
  },

  restoreStore: async (storeNo) => {
    const s = db.stores.find((x) => x.storeNo === storeNo);
    if (!s) notFound("门店", "Store", storeNo);
    /*
     * 只有平台压下的店解得开。**READONLY 是商家自己关的** ——
     * 平台替他开等于替他做经营决定，而他下一分钟就会再关一次。
     * mock 也拦：不拦的话页面会给每家店都摆一个「解除下线」按钮。
     */
    if (s.status !== "SUSPENDED") {
      fail("只有被平台强制下线的门店可以解除，商家自助停用的店由商家自己开回来", "Only a store the platform forced offline can be restored — one the merchant paused reopens on their side");
    }
    s.status = "ACTIVE";
    return wait(s, 400);
  },

  listStoreAudits: (q = {}) =>
    wait(
      db.paginate(db.storeAudits, q.page, q.size, (a) =>
        db.eqHit(q.kind, a.kind) &&
        // 默认只看待审：审核页是个队列，历史记录是次要视图
        (q.status ? a.status === q.status : true) &&
        db.kwHit(q.keyword, a.auditNo, a.merchantName, a.content),
      ),
    ),

  decideStoreAudit: async (auditNo, pass, reason) => {
    const a = db.storeAudits.find((x) => x.auditNo === auditNo);
    if (!a) notFound("审核单", "Review request", auditNo);
    if (a.status !== "PENDING") fail("该审核单已处理，请刷新列表", "This request has already been handled — refresh the list");
    // 驳回原因原样进商家 B 端 —— 空原因等于让商家猜，猜不到就会反复提交同一份
    if (!pass && !reason?.trim()) fail("驳回必须填写原因，商家会原样看到这段话", "Rejection needs a reason — the merchant sees this text verbatim");
    a.status = pass ? "PASSED" : "REJECTED";
    a.reason = pass ? undefined : reason?.trim();
    return wait(a, 400);
  },

  listStoreQrcodes: (q = {}) =>
    wait(db.paginate(db.storeQrcodes, q.page, q.size, (r) => db.kwHit(q.keyword, r.merchantNo, r.merchantName, r.code))),

  // 登记后重取列表即可看到累计变化；mock 直接改内存里的那一行
  recordQrcodePrint: async ({ merchantNo, qty, size }) => {
    if (qty === 0) fail("印量不能为 0", "Quantity cannot be zero");
    const row = db.storeQrcodes.find((r) => r.merchantNo === merchantNo);
    if (!row) notFound("店铺码", "Store QR code", merchantNo);
    // null 表示还没登记过 —— 第一次登记要从 0 起累加，而不是把 null 当 0 用
    row.printed = (row.printed ?? 0) + qty;
    if (size) row.size = size;
    await wait(undefined);
  },

  listStoreAcquisition: (q = {}) =>
    wait(db.paginate(db.storeAcquisition, q.page, q.size, (r) => db.kwHit(q.keyword, r.merchantNo, r.merchantName))),

  listStoreTemplates: async () => wait(db.storeTemplates),

  saveStoreTemplate: async (v) => {
    if (!v.name.trim()) fail("模板名称不能为空", "The template name cannot be empty");

    const seen = new Set<string>();
    for (const s of v.sections) {
      // 重复 key 时哪条生效取决于顺序，那是隐性行为
      if (seen.has(s.key)) fail(`板块重复：${s.key}`, `Duplicate block: ${s.key}`);
      seen.add(s.key);
      // 店招关掉之后店铺页没有头部，等于一张裸列表
      if (s.required && !s.enabled) fail(`${s.key} 是必选板块，不能停用`, `${s.key} is a required block and cannot be turned off`);
    }
    if (v.sections.filter((s) => s.enabled).length < MIN_ENABLED_SECTIONS) {
      fail(`至少要启用 ${MIN_ENABLED_SECTIONS} 个板块 —— 只剩店招的店铺页等于一张裸列表`, `At least ${MIN_ENABLED_SECTIONS} blocks must stay on — a storefront with nothing but its header is a bare list`);
    }

    const saved = db.upsert(
      db.storeTemplates,
      { ...v, usedByCount: 0, updatedAt: new Date().toISOString(), updatedBy: "admin" },
      "templateNo",
      () => db.nextNo("TPL", db.storeTemplates, 900, "templateNo"),
    );
    return wait(saved, 400);
  },

  setStoreTemplateEnabled: async (templateNo, enabled) => {
    const t = db.storeTemplates.find((x) => x.templateNo === templateNo);
    if (!t) notFound("模板", "Template", templateNo);
    if (!enabled) {
      // 停用会让正在用它的店铺页瞬间失去模板
      if (t.isDefault) fail("默认模板不能停用 —— 新店开出来就用它", "The default template cannot be turned off — new stores open with it");
      if (t.usedByCount > 0) fail(`还有 ${t.usedByCount} 家店在用这个模板，请先把店迁到别的模板`, `${t.usedByCount} stores still use this template — move them to another one first`);
    }
    t.enabled = enabled;
    t.updatedAt = new Date().toISOString();
    t.updatedBy = "admin";
    return wait(t, 400);
  },
};
