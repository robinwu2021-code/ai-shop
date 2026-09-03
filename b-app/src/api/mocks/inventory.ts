// 进销存：库存、单据、盘点、调拨、供应商 —— B 端替身的一域。
//
// 从 `api/mock.ts`（5240 行 / 228 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { delay } from "@shared/mock/db";
import type { Carrier, StockBalance, StockCount, StockTransfer, Supplier } from "@shared/types";
import {
  currentStoreNo,
  invBalances,
  invDocuments,
  invLedger,
  mockBarcodes,
  mockOutbounds,
  mockSafety,
  mockSuppliers,
  scopedBalances,
  shipped,
  shortage,
  voidedDocs,
} from "./_shared";
import type { MerchantApi } from "../contract";

export const inventoryMock: Pick<MerchantApi,
  "mStockSummary"
  | "mStockBalances"
  | "mStockPickable"
  | "mSuppliers"
  | "mSupplierCreate"
  | "mSupplierUpdate"
  | "mSupplierActive"
  | "mStockItem"
  | "mStockCrossStore"
  | "mItemBySku"
  | "mItemByBarcode"
  | "mBindBarcode"
  | "mSafetyStock"
  | "mStockLedger"
  | "mStockAdjust"
  | "mInboundCreate"
  | "mInboundUpdate"
  | "mInboundPost"
  | "mInboundVoid"
  | "mOutboundCreate"
  | "mOutboundPost"
  | "mOutboundVoid"
  | "mCountOpen"
  | "mCountDetail"
  | "mCountFill"
  | "mCountPost"
  | "mTransferCreate"
  | "mTransferDetail"
  | "mCarriers"
  | "mTransferShip"
  | "mTransferReceive"
  | "mTransferVoid"
  | "mStockDocuments"
  | "mStockMonthly"
  | "mStockRanking"
  | "mStockLocations"
  | "mWarehouseCreate"
  | "mLocationSetSource"
> = {
  // ---- 进销存（P-18）
  //
  // **形状逐字对着后端的 record**（`InventoryVOs` / `Biz*Controller`），
  // 不照界面拟。运营端那三页就是照自拟的形状接的、mock 也照那份写，
  // 于是两边自洽、mock 自查全过，而真接口一个都调不通 ——
  // **替身与真身对不上时，替身跑得越顺，越看不出问题。**
  //
  // 种子刻意「不干净」：有缺货、有滞销、有预留。全绿的库存页看不出这一页是干什么的。

  async mStockSummary() {
    /*
     * 四个数按**当前门店**算，而不是四个写死的常量。
     * 后端的库存三个控制器都判当前门店 —— mock 给常量的话，切了店这一屏一动不动，
     * 而那正是「门店切换好像没做好」的样子。
     */
    const mine = scopedBalances();
    if (currentStoreNo()) {
      return delay({
        itemCount: mine.length,
        shortageCount: mine.filter((b) => b.flags.includes("SHORTAGE")).length,
        staleCount: mine.filter((b) => b.flags.includes("STALE")).length,
        inTransitCount: 0,
        openCountNo: "CNT-24082601",
      });
    }
    return delay({ itemCount: 216, shortageCount: 6, staleCount: 12, inTransitCount: 1,
      // mock 里给一张开着的盘点单，否则「继续盘点」这条在 mock 上永远看不见
      openCountNo: "CNT-24082601" });
  },

  async mStockBalances(q) {
    const all = scopedBalances();
    const filter = q?.filter ?? "todo";
    // shortage / stale 是**精确的两档**：点「缺货 6」就该给这 6 条。
    // mock 不认的话，前端这条在 mock 上验不到（会静静地落回 todo）
    const picked = filter === "all" ? all
      : filter === "reserved" ? all.filter((b) => b.reserved > 0)
      : filter === "shortage" ? all.filter((b) => b.flags.includes("SHORTAGE"))
      : filter === "stale" ? all.filter((b) => b.flags.includes("STALE"))
      : all.filter((b) => b.flags.length > 0);
    return delay(picked.slice(0, q?.size ?? 100));
  },

  async mStockPickable(q) {
    // 从物料出发：mock 里也要有一件 0 库存的，否则「挑不到新货」这个缺陷在 mock 上看不见
    // 可挑的货**按当前门店**：盘点/出库改的是这家店的库存
    const k = (q?.q ?? "").trim();
    const all = [...scopedBalances(), {
      // **故意不给 skuNo**：没有平台映射的物料绑不了码，那条分支要在替身上看得见，
      // 否则它只会在真机上第一次露面（2026-09-02 的绑码缺陷就是这么漏过去的）
      itemId: "IT-NEW", name: "新到的货（还没进过）", specText: "500g",
      baseUom: "袋", onHand: 0, reserved: 0, available: 0,
      safetyStock: 0, flags: [],
    } as StockBalance];
    const picked = k ? all.filter((b) => b.name.includes(k) || (b.specText ?? "").includes(k)) : all;
    return delay(picked.slice(0, q?.size ?? 200));
  },

  /*
   * 供应商 mock。**三条刻意各是一种状态**（在用 / 有联系人 / 已停用）——
   * 三条都一样的话，「停用的不出现在挑供应商里」这条在 mock 上永远看不见。
   */
  async mSuppliers(q) {
    const all = mockSuppliers;
    const k = (q?.keyword ?? "").trim();
    let out = q?.activeOnly === false ? all : all.filter((s) => s.status === "ACTIVE");
    if (k) out = out.filter((s) => s.name.includes(k) || (s.contactName ?? "").includes(k));
    return delay(out);
  },

  async mSupplierCreate(body) {
    const name = (body.name ?? "").trim();
    // 与后端同一条规矩：重名拒。mock 上放过去的话，端上那条「搜到同名就不给新建」
    // 的分支永远走不到，而它正是最容易写错的一处
    if (mockSuppliers.some((s) => s.name === name)) throw new Error("这家已经建过了");
    const row = {
      supplierNo: `SUP-M${mockSuppliers.length + 1}`, name,
      shortName: null, contactName: body.contactName ?? null,
      contactPhone: body.contactPhone ?? null, remark: body.remark ?? null,
      status: "ACTIVE", fromPlatform: false,
    } as Supplier;
    mockSuppliers.push(row);
    return delay({ supplierNo: row.supplierNo });
  },

  async mSupplierUpdate(no, body) {
    const row = mockSuppliers.find((s) => s.supplierNo === no);
    if (row && !row.fromPlatform && body.name) row.name = body.name;
    if (row) row.remark = body.remark ?? row.remark;
    return delay(undefined as void);
  },

  async mSupplierActive(no, body) {
    const row = mockSuppliers.find((s) => s.supplierNo === no);
    if (row) row.status = body.active ? "ACTIVE" : "ARCHIVED";
    return delay(undefined as void);
  },

  async mStockItem(itemId) {
    const b = invBalances().find((x) => x.itemId === itemId) ?? invBalances()[0]!;
    /** 库位覆盖读回来 —— `undefined` 是「跟随默认」，与显式的 0（这个库位不预警）是两件事 */
    const override = (loc: string) => mockSafety.get(`${b.itemId}@${loc}`);
    return delay({
      itemId: b.itemId, name: b.name, specText: b.specText, baseUom: b.baseUom,
      barcode: "6901234567892", itemCode: "LM-05",
      onHand: b.onHand, reserved: b.reserved, available: b.available,
      safetyStock: mockSafety.get(b.itemId) ?? 0,
      byLocation: [
        { locationId: "L1", locationName: "文三路店", onHand: 5, safetyStock: override("L1") },
        { locationId: "L2", locationName: "古墩路店", onHand: 12, safetyStock: override("L2") },
        { locationId: "L3", locationName: "城西仓", onHand: 40, safetyStock: override("L3") },
      ],
    });
  },

  /**
   * 按平台 SKU 查进销存的账。
   *
   * **替身要能给出「没有账」这一态**：线上一件刚建的 SKU 在投影跑到之前
   * 本来就没有物料，而商品页正是要把这件事显示出来。全都给一份账的替身，
   * 会让「还没建账」那条分支在端上永远走不到。
   *
   * 约定：`skuNo` 以 `NOINV` 开头的当作还没投影过来。
   */
  /**
   * 跨店总览。**替身要造出「同一件货在几家店冷热不均」的样子** ——
   * 各店都一样多的话，这一屏存在的理由（哪家店断了）在开发期就看不出来。
   *
   * 与后端同一套语义：**每一行都列出全部门店**，包括那家从来没进过这件货的
   *（后端那边余额行是按需建的，没有行的库位按 0 补齐并算作缺货）。
   * 只列「有余额的店」的话，「二号店一件都没有」这件事在界面上永远不出现，
   * 而那正是商家最想知道的。
   */
  async mStockCrossStore(q) {
    const rows = invBalances().slice(0, 8).map((b, i) => {
      // 三家店，按序号错开：让第 0 件在两家店断、第 1 件断一家、其余不断
      const per = [
        { locationId: "L1", locationName: "文三路店", onHand: i === 0 ? 0 : b.onHand },
        { locationId: "L2", locationName: "古墩路店", onHand: i <= 1 ? 0 : Math.ceil(b.onHand / 2) },
        { locationId: "L3", locationName: "城西仓", onHand: b.onHand },
      ];
      const onHand = per.reduce((n, l) => n + l.onHand, 0);
      return {
        itemId: b.itemId, name: b.name, specText: b.specText, baseUom: b.baseUom,
        onHand, reserved: b.reserved, available: onHand - b.reserved,
        shortageLocations: per.filter((l) => l.onHand <= 0).length,
        byLocation: per,
      };
    });
    const picked = q?.filter === "all" ? rows : rows.filter((r) => r.shortageLocations > 0);
    return delay([...picked]
      .sort((a, b) => b.shortageLocations - a.shortageLocations || a.available - b.available)
      .slice(0, q?.size ?? 50));
  },

  async mItemBySku(skuNo) {
    if (!skuNo || skuNo.startsWith("NOINV")) return delay(null);
    const b = invBalances()[0]!;
    /*
     * **合计由库位算出来，不另写一个数。**真实的 `itemDetail` 里 onHand 就是
     * 各库位之和；替身把两者各写各的话，界面上「记着 5（文三路店 5、城西仓 40）」
     * 这种自相矛盾看起来像后端算错了，而实际是替身在骗人。
     */
    const byLocation = [
      { locationId: "L1", locationName: "文三路店", onHand: 5, safetyStock: undefined },
      { locationId: "L3", locationName: "城西仓", onHand: 40, safetyStock: undefined },
    ];
    const onHand = byLocation.reduce((n, l) => n + l.onHand, 0);
    return delay({
      itemId: b.itemId, name: b.name, specText: b.specText, baseUom: b.baseUom,
      barcode: "6901234567892", itemCode: "LM-05",
      onHand, reserved: b.reserved, available: onHand - b.reserved,
      safetyStock: mockSafety.get(b.itemId) ?? 0,
      byLocation,
    });
  },

  /**
   * 按条码找货。**替身要留痕**：绑过的码在本次会话里再扫要命中 ——
   * 「绑完第二次直接命中」正是这个功能的全部承诺，而吞掉绑定的替身验不到它。
   *
   * 种子只给一个已绑的码，其余全部未命中 —— 这与线上一致（`prd_sku.barcode` 0/396），
   * 替身比线上干净的话，「第一天扫什么都不中」这件事就看不见了。
   */
  async mItemByBarcode(code) {
    const itemId = mockBarcodes.get(code.trim());
    if (!itemId) return delay(null);
    return delay(invBalances().find((b) => b.itemId === itemId) ?? null);
  },

  async mBindBarcode(body) {
    const code = body.barcode.trim();
    const taken = mockBarcodes.get(code);
    // 幂等：同一个码绑同一件货是成功；绑到另一件上要拒
    if (taken && taken !== body.skuNo) throw new Error("这个码已经绑在另一件货上");
    mockBarcodes.set(code, body.skuNo);
    return delay(undefined as void);
  },

  async mSafetyStock(itemId, body) {
    const key = body.locationId ? `${itemId}@${body.locationId}` : itemId;
    // qty 为 null = 撤掉库位覆盖。**删掉而不是写 0** —— 写 0 是「这个库位不预警」
    if (body.qty == null) mockSafety.delete(key);
    else mockSafety.set(key, body.qty);
    return delay(undefined as void);
  },

  async mStockLedger(q) {
    const size = q?.size ?? 20;
    const rows = invLedger()
      .filter((r) => (!q?.docNo || r.docNo === q.docNo) && (!q?.itemId || r.itemId === q.itemId))
      .filter((r) => !q?.cursor || r.id < q.cursor)
      .slice(0, size);
    const last = rows[rows.length - 1];
    return delay({
      entries: rows,
      nextCursor: rows.length < size ? null : (last?.id ?? null),
    });
  },

  async mStockAdjust() {
    return delay(undefined);
  },

  async mInboundCreate() {
    return delay("IN-24082601");
  },
  async mInboundUpdate() {
    return delay(undefined);
  },
  async mInboundPost() {
    return delay(undefined);
  },
  async mInboundVoid(no) {
    voidedDocs.add(no);
    return delay(undefined);
  },

  async mOutboundCreate(req) {
    /*
     * **把请求记下来，别吞掉。**
     *
     * 原来这个替身连参数都不接，于是「去向有没有真的带上」在 mock 上根本验不到 ——
     * 页面选了「退给老周」，提交，回到单据列表看到的还是「报损」，而两者都不报错。
     * 同一条教训在调拨发货、安全库存上各吃过一次。
     */
    // 单号带上门店：出库扣的是**这家店**的库存（后端 BizStockDocController 按当前门店）
    const cur = currentStoreNo();
    const no = `OUT-${cur ? cur.slice(-4) + "-" : ""}24082600${32 + mockOutbounds.length}`;
    const target = req.targetNo
      ? (mockSuppliers.find((x) => x.supplierNo === req.targetNo)?.name ?? req.targetNo)
      : "";
    mockOutbounds.push({
      kind: "OUT",
      docNo: no,
      status: "POSTED",
      // 服务端的口径：有去向时 label=purpose、subtitle=去向名；没有时 label=原因码
      label: target ? req.purpose : (req.reasonCode ?? req.purpose),
      subtitle: target,
      // **负数**：后端下发的是 `-totalQty`（出库是减）。
      // 上一轮这里写成正数，于是 H5 上出库单显示绿色的 +1 ——
      // 我据此报了一条「后端符号反了」，那条是错的，错的是这个替身
      totalQty: -req.lines.reduce((n, l) => n + l.qty, 0),
      occurredAt: req.occurredAt ?? "2026-08-30T10:00:00",
      operator: "老板",
    });
    return delay(no);
  },
  async mOutboundPost() {
    return delay(undefined);
  },
  async mOutboundVoid(no) {
    voidedDocs.add(no);
    return delay(undefined);
  },

  async mCountOpen() {
    // 单号带上门店：盘的是这家店的库位（后端 BizStockDocController 按当前门店解库位）
    const cur = currentStoreNo();
    return delay(cur ? `CNT-${cur.slice(-4)}-24082601` : "CNT-24082601");
  },
  /** 账面数是开单那一刻的快照 —— mock 里也给成与当前余额**不同**的数，
   *  否则「用当前余额顶替」这个错在 mock 下永远看不出来 */
  async mCountDetail(no) {
    // 盘的是这家店的库位：可盘行取当前门店那一份（与 mStockPickable 同一口径）
    const mine = scopedBalances();
    const seeded = [
      { itemId: "I1", name: "东北大米", specText: "5斤装", baseUom: "袋", bookQty: 5, countedQty: null, diffQty: null },
      { itemId: "I4", name: "土鸡蛋", specText: "30枚装", baseUom: "箱", bookQty: 48, countedQty: null, diffQty: null },
      { itemId: "I3", name: "陈醋", specText: "500ml", baseUom: "瓶", bookQty: 24, countedQty: null, diffQty: null },
    ];
    /*
     * 多门店时只留**这家店有的那几行**：盘点单盘的是这家店的库位。
     * 一行都不剩时退回整份 —— 空盘点单看不出这一页是干什么的，
     * 而「这家店恰好没有这三样」不是这一屏要演的事。
     */
    const lines = mine.length && currentStoreNo()
      ? seeded.filter((l) => mine.some((b) => b.itemId === l.itemId))
      : seeded;
    return delay({
      countNo: no,
      status: "COUNTING",
      locationId: "L1",
      startedAt: "2026-08-26T09:02:00",
      operator: "张伟",
      lines: lines.length ? lines : seeded,
    } satisfies StockCount);
  },

  async mCountFill() {
    return delay(undefined);
  },
  async mCountPost() {
    return delay(undefined);
  },

  async mTransferCreate() {
    return delay("TRF-24082507");
  },
  async mTransferDetail(no) {
    return delay({
      transferNo: no,
      status: "SHIPPED",
      fromLocationId: "L3", fromLocationName: "城西仓",
      toLocationId: "L1", toLocationName: "文三路店",
      shippedAt: "2026-08-26T07:30:00",
      // 发过货的单回读那次填的；没发过的用一条既有的示例值
      ...(shipped.get(no) ?? { carrierName: "顺丰速运", trackingNo: "SF1234567890" }),
      totalQty: 20,
      lines: [{ itemId: "I1", name: "东北大米", specText: "5斤装", qty: 20, uom: "袋" }],
    } satisfies StockTransfer);
  },

  /*
   * **三条里一条 enabled=false**（与线上一致：SF/JD 启用、YTO 停用）。
   * 三条都启用的话，「只列启用的」这条判据在 mock 上永远看不见 ——
   * 而它正是最容易漏的：停用的承运方被选中，那张单指向一个已经不合作的公司。
   */
  async mCarriers() {
    return delay([
      { carrier: "SF", name: "顺丰速运" },
      { carrier: "JD", name: "京东物流" },
    ] as Carrier[]);
  },

  /*
   * **记下来，让 mTransferDetail 读得到。**
   *
   * 原先这一口直接吞掉参数返回 undefined，于是 mock 上「发了一次货 →
   * 单据上看得到承运方与运单号」这条闭环**永远验不了** —— 而它正是 S4 的判据。
   * 一个只收不吐的替身会把「只写不读」的缺陷盖住，那正是这次真实发生的事：
   * 后端把三列写进了库，VO 里却一个都没下发。
   */
  async mTransferShip(no, body) {
    shipped.set(no, {
      carrierName: body?.carrierName,
      trackingNo: body?.trackingNo,
    });
    return delay(undefined);
  },
  async mTransferReceive() {
    return delay(undefined);
  },
  async mTransferVoid(no) {
    // 与后端同口径：已发出的不给作废，端上的按钮该按这条判据显隐
    if (shipped.has(no)) {
      throw new Error("已发出的调拨单不能作废");
    }
    voidedDocs.add(no);
    return delay(undefined);
  },

  async mStockDocuments(q) {
    // 本次会话里新建的排在前面 —— 刚提交完就该在最上面看到它
    const all = [...mockOutbounds, ...invDocuments()];
    const picked = q?.no ? all.filter((d) => d.docNo === q.no)
      : q?.kind ? all.filter((d) => d.kind === q.kind) : all;
    return delay(picked.slice(0, q?.size ?? 50)
      .map((d) => (voidedDocs.has(d.docNo) ? { ...d, status: "VOIDED" } : d)));
  },

  async mStockMonthly(month) {
    // 期初 + 进 − 销 − 损 ± 调 = 期末。**这条式子在界面上要能算得通** ——
    // 对不上说明台账漏了一笔，那正是这张报表存在的理由
    return delay({
      month, opening: 320, purchased: 480, sold: 512, lost: 9, adjusted: 0,
      closing: 279, balanced: true,
      // 按笔累加的成本，不是「件数 × 当前成本价」
      soldCostMinor: 1_612_800, lostCostMinor: 37_800,
    });
  },

  async mStockRanking(q) {
    // slow 的 qty 是**库存量**（后端取 onHand），且 **costAmountMinor 是 null** ——
    // mock 里塞一个金额进去，界面就会显示一个真接口永远不会给的数
    return (q?.type === "slow"
      ? delay([{ itemId: "I3", name: "陈醋", specText: "500ml", qty: 24, costAmountMinor: null }])
      : delay([
          { itemId: "I1", name: "东北大米", specText: "5斤装", qty: 186, costAmountMinor: 781200 },
          { itemId: "I4", name: "土鸡蛋", specText: "30枚装", qty: 142, costAmountMinor: 397600 },
          { itemId: "I5", name: "面粉", specText: "10斤装", qty: 97, costAmountMinor: 291000 },
        ]));
  },

  async mStockLocations() {
    return delay([
      { locationId: "L1", name: "文三路店", kind: "STORE", externalRef: "S0001", sourceLocationId: "L3" },
      { locationId: "L2", name: "古墩路店", kind: "STORE", externalRef: "S0002" },
      { locationId: "L3", name: "城西仓", kind: "WAREHOUSE" },
      // 在途是**真实的库位**，不是「暂时没有」—— 调拨途中的货停在这里，合计才守恒
      { locationId: "L0", name: "在途", kind: "TRANSIT", status: "SYSTEM" },
    ]);
  },

  async mWarehouseCreate() {
    return delay("L4");
  },
  async mLocationSetSource() {
    return delay(undefined);
  },
};
