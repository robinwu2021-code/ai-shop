// 覆盖范围：门店主页治理（P-10.1）。
import * as db from "@/lib/mock/db";
import { MIN_ENABLED_SECTIONS } from "@/lib/constants";
import type { StoreApi } from "../contracts/store";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

/**
 * 这家主体覆盖该社区吗（P-11.2.1b）。
 *
 * 不传社区就全放行；传了而主体查不到，**算不覆盖** ——
 * 不能默认放行：那会让「筛了一个没人覆盖的社区」列出全平台的店，
 * 看起来筛了、其实没筛，是筛选最坏的一种坏法。
 */
function coversCommunity(merchantNo: string, communityNo?: string) {
  if (!communityNo) return true;
  const m = db.merchants.find((x) => x.merchantNo === merchantNo);
  return !!m?.communityNos?.includes(communityNo);
}

/** 1x1 透明 PNG。mock 不去要真的微信码，只证明「有图/没图」这条分叉走得通。 */
const MOCK_PNG =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

/**
 * 定位一家门店的店铺码行。
 *
 * <b>不传 storeNo 时取该主体的第一行</b>（对应后端的「默认店」）——
 * 单店商家不该被迫先去查门店号。
 */
function pickStore(merchantNo: string, storeNo?: string) {
  const row = db.storeQrcodes.find(
    (r) => r.merchantNo === merchantNo && (!storeNo || r.storeNo === storeNo),
  );
  if (!row) notFound("店铺码", "Store QR code", storeNo ?? merchantNo);
  return row!;
}

export const storeMock: StoreApi = {
  // ── 门店档案（P-11.2.1）────────────────────────────────────────

  listStores: (q = {}) =>
    wait(
      db.paginate(db.stores, q.page, q.size, (s) =>
        db.eqHit(q.merchantNo, s.merchantNo) &&
        db.eqHit(q.status, s.status) &&
        db.eqHit(q.businessMode, s.businessMode) &&
        // 社区维与真后端同口径：覆盖关系挂在**主体**上，门店跟着它的主体走
        coversCommunity(s.merchantNo, q.communityNo) &&
        db.kwHit(q.keyword, s.storeNo, s.name, s.address, s.merchantName),
      ),
    ),

  getStore: async (storeNo) => {
    const s = db.stores.find((x) => x.storeNo === storeNo);
    if (!s) notFound("门店", "Store", storeNo);
    const m = db.merchants.find((x) => x.merchantNo === s!.merchantNo);
    /*
     * 三样只有详情才有的东西（P-11.2.1c）。**两种态都要能演出来**：
     * ST001 挂了取货点，其余没挂 —— 空数组是「没挂」，不是「没查到」。
     */
    return wait({
      store: s!,
      /*
       * 覆盖明细。**mock 也要能演出「框了小区 + 排掉一栋楼」那一格** ——
       * 只演纳入项的话，「排除项要单列」这条规则在 mock 下永远看不出效果，
       * 而它正是这一屏改造的理由：混在一起列，运营会读成「他做这儿」。
       *
       * 投影数刻意与「框了几条」不同（框 1 条 → 覆盖 2 个聚落）：两个数字相等的话，
       * 界面把哪个显示成哪个都看不出来。
       */
      coverage: (() => {
        const nos = m?.communityNos ?? [];
        const nameOf = (no: string) => db.communities.find((x) => x.communityNo === no)?.name ?? no;
        const includes = nos.map((no) => ({ level: "COMMUNITY", refCode: no, name: nameOf(no), status: "ACTIVE" }));
        const excludes = nos.length
          ? [{ level: "COMMUNITY", refCode: `${nos[0]}B3`, name: `${nameOf(nos[0])} 3 幢`, status: "ACTIVE" }]
          : [];
        const sample = nos.flatMap((no) => [nameOf(no), `${nameOf(no)} 5 幢`]);
        return { includes, excludes, reachableCount: sample.length, reachableSample: sample };
      })(),
      pickupNames: storeNo === "ST001" ? ["文三路菜鸟驿站"] : [],
      /*
       * 扫码数取自店铺码那份数据。**mock 里两处的门店号不是同一套**
       * （门店档案用 ST001..，店铺码用 ST901..），所以先按门店号找，
       * 找不到再退回同主体的第一行 —— 这是 mock 数据的历史遗留，
       * 真后端两边读的都是 mkt_store_visit，不存在这个问题。
       */
      scanCount30d: (db.storeQrcodes.find((q) => q.storeNo === storeNo)
        ?? db.storeQrcodes.find((q) => q.merchantNo === s!.merchantNo))?.scanCount ?? 0,
    });
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
      // 非零：待售后恒为 0 的话，这一列有没有接上在界面上看不出来
      toAfterSale: 2,
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
    wait(db.paginate(db.storeQrcodes, q.page, q.size, (r) =>
      // codeless：只看还没发码的门店 —— 运营要动手的那一批
      (!q.codeless || r.code == null)
      && db.kwHit(q.keyword, r.merchantNo, r.merchantName, r.storeNo, r.code ?? ""))),

  // 发码幂等：已经有码就原样给回来，重复点不换码
  issueStoreQrcode: async ({ merchantNo, storeNo }) => {
    const row = pickStore(merchantNo, storeNo);
    if (row.code == null) row.code = `shop_${row.storeNo}_${Math.random().toString(36).slice(2, 6)}`;
    await wait(undefined);
    return { storeCode: row.code };
  },

  reissueStoreQrcode: async ({ merchantNo, storeNo, reason }) => {
    // 换码让已印物料全部失效 —— 没有理由就不许换（后端同一道闸）
    if (!reason?.trim()) fail("换码必须写明原因", "A reason is required to re-issue");
    const row = pickStore(merchantNo, storeNo);
    row.code = `shop_${row.storeNo}_${Math.random().toString(36).slice(2, 6)}`;
    await wait(undefined);
    return { storeCode: row.code };
  },

  /*
   * 导出带码图。mock 不真的去要微信码，给一张 1x1 的透明 PNG 占位 ——
   * **只给已经有码的行**：没发码的行 imageBase64 是 null，
   * 界面据此显示「待发码」而不是塞一张会被直接送去印刷的空图。
   */
  exportStoreQrcodes: async (q = {}) => {
    const rows = db.storeQrcodes.filter((r) =>
      (!q.codeless || r.code == null)
      && db.kwHit(q.keyword, r.merchantNo, r.merchantName, r.storeNo, r.code ?? ""));
    await wait(undefined);
    return rows.map((row) => ({ row, imageBase64: row.code == null ? null : MOCK_PNG }));
  },

  // 登记后重取列表即可看到累计变化；mock 直接改内存里的那一行
  recordQrcodePrint: async ({ merchantNo, storeNo, qty, size }) => {
    if (qty === 0) fail("印量不能为 0", "Quantity cannot be zero");
    const row = pickStore(merchantNo, storeNo);
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
