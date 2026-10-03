// 覆盖范围：履约调度（P-5.1）。
import type { OutOfRangeRule } from "@/lib/types";
import * as db from "@/lib/mock/db";
import { MIN_FIRST_WEIGHT_GRAM, MIN_OVERDUE_GRACE_HOURS } from "@/lib/constants";
import { BATCH_TRANSITIONS } from "@/lib/types";
import type { FulfillmentApi } from "../contracts/fulfillment";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

/** 与后端 ExpressCompanies 同名（微信 delivery_name） */
const CARRIER_NAMES: Record<string, string> = {
  ZTO: "中通快递", YTO: "圆通速递", YD: "韵达速递", STO: "申通快递",
  JTSD: "极兔速递", JD: "京东快递", DBL: "德邦快递", EMS: "EMS",
};

export const fulfillmentMock: FulfillmentApi = {
  listArrivalBatches: (q = {}) =>
    wait(
      db.paginate(db.batches, q.page, q.size, (b) =>
        db.scopeHit(q, b) &&
        db.eqHit(q.status, b.status) &&
        db.kwHit(q.keyword, b.batchNo, b.pickupName, b.communityName, b.vehicle),
      ),
    ),

  setBatchStatus: async (batchNo, status) => {
    const b = db.batches.find((x) => x.batchNo === batchNo);
    if (!b) notFound("批次", "Batch", batchNo);
    db.assertTransition(BATCH_TRANSITIONS, b.status, status, "到货批次", "Batch");
    b.status = status;
    return wait(b, 400);
  },

  // 分拣只看**已签收**批次：没签收就分拣，等于把责任判定的依据跳过去了
  // 与后端 PageData 同形 —— 裸数组正是「mock 绿、真后端 data.map 崩」那一类
  listSorting: async (q = {}) => {
    const signed = new Set(db.batches.filter((b) => b.status === "SIGNED").map((b) => b.pickupNo));
    return wait(db.paginate(db.sorting, q.page, q.size,
      (r) => signed.has(r.pickupNo) && db.eqHit(q.pickupNo, r.pickupNo)));
  },

  listRedeemStats: async (q = {}) =>
    wait(db.paginate(db.redeemStats, q.page, q.size, (r) => db.eqHit(q.pickupNo, r.pickupNo))),

  getOverdueRule: async () => wait(db.overdueRule),

  saveOverdueRule: async (rule) => {
    // 到点即作废必产生客诉：宽限期是**规则**不是建议，两侧都校验
    if (rule.graceHours < MIN_OVERDUE_GRACE_HOURS) {
      fail(`宽限期不能少于 ${MIN_OVERDUE_GRACE_HOURS} 小时`, `The grace period cannot be under ${MIN_OVERDUE_GRACE_HOURS} hours`);
    }
    if (rule.action === "POSTPONE" && rule.maxPostpone < 1) {
      fail("顺延次数上限至少为 1", "Allow at least 1 deferral");
    }
    Object.assign(db.overdueRule, rule, { updatedAt: "2026-08-06T00:00:00Z", updatedBy: "admin" });
    return wait(db.overdueRule, 400);
  },

  listShipments: (q = {}) =>
    wait(
      db.paginate(db.shipments, q.page, q.size, (s) =>
        db.eqHit(q.status, s.status) &&
        db.eqHit(q.carrier, s.carrier) &&
        db.kwHit(q.keyword, s.shipmentNo, s.orderNo, s.waybillNo, s.receiver),
      ),
    ),

  updateWaybill: async ({ shipmentNo, waybillNo, reason }) => {
    const sh = db.shipments.find((x) => x.shipmentNo === shipmentNo);
    if (!sh) notFound("快递单", "Shipment", shipmentNo);
    if (!waybillNo.trim()) fail("运单号不能为空", "The waybill number cannot be empty");
    if (!reason.trim()) fail("换单号必须写原因 —— 之后对不上时这是唯一线索", "Changing the waybill needs a reason — it is the only trail when things stop matching up");
    // 货都到了再改单号，等于把一条已完成的轨迹指向别处
    if (sh.status === "DELIVERED") fail("已签收的快递单不能改运单号", "A delivered shipment's waybill number cannot be changed");
    // 同一承运商下重号会把两单的轨迹搅在一起
    const dup = db.shipments.find(
      (x) => x.shipmentNo !== shipmentNo && x.carrier === sh.carrier && x.waybillNo === waybillNo.trim(),
    );
    if (dup) fail(`该承运商下运单号已被 ${dup.shipmentNo} 占用`, `${dup.shipmentNo} already uses that waybill number with this carrier`);

    sh.waybillNo = waybillNo.trim();
    sh.updatedAt = new Date().toISOString();
    sh.traces = [
      { at: sh.updatedAt, text: `运营换单号：${reason.trim()}`, location: "平台" },
      ...sh.traces,
    ];
    return wait(sh, 350);
  },

  listFreightTemplates: async (q = {}) =>
    wait(db.paginate(db.freightTemplates, undefined, 100, (t) => db.liveHit(t, q.showArchived))),

  saveFreightTemplate: async (v) => {
    if (!v.name.trim()) fail("模板名称不能为空", "The template name cannot be empty");
    if (v.firstWeightGram < MIN_FIRST_WEIGHT_GRAM) {
      fail(`首重不得少于 ${MIN_FIRST_WEIGHT_GRAM} 克 —— 首重为 0 意味着拿起来就收首重费`, `The first weight cannot be under ${MIN_FIRST_WEIGHT_GRAM} g — at 0 you charge the first-weight fee just for picking it up`);
    }
    if (v.firstFee < 0 || v.addFee < 0) fail("运费不能为负", "Shipping fees cannot be negative");
    if (v.addWeightGram <= 0) fail("续重单位必须大于 0，否则续重费无从计算", "The additional-weight unit must be above 0, or the extra fee cannot be worked out");
    if (v.freeThreshold < 0) fail("免邮门槛不能为负（0 表示不免邮）", "The free-shipping threshold cannot be negative (0 means no free shipping)");

    const seen = new Set<string>();
    for (const r of v.outOfRange) {
      if (!r.region.trim()) fail("超区区域不能为空", "The out-of-range region cannot be empty");
      // 同一区域配两条规则，命中哪条取决于顺序 —— 那是隐性行为，不许存在
      if (seen.has(r.region)) fail(`超区区域重复：${r.region}`, `Duplicate out-of-range region: ${r.region}`);
      seen.add(r.region);
      if (r.action === "REJECT" && r.surcharge !== 0) {
        // 传了就是调用方理解错了，拒绝而不是静默清零
        fail(`${r.region} 设为不配送，不能同时填加价额`, `${r.region} is set to “do not ship”, so it cannot carry a surcharge`);
      }
      if (r.action === "SURCHARGE" && r.surcharge <= 0) {
        fail(`${r.region} 设为加价配送，加价额必须大于 0`, `${r.region} ships with a surcharge, so the surcharge must be above 0`);
      }
    }

    const saved = db.upsert(
      db.freightTemplates,
      { ...v, updatedAt: new Date().toISOString(), updatedBy: "admin" },
      "templateNo",
      () => db.nextNo("FT", db.freightTemplates, 900, "templateNo"),
    );
    return wait(saved, 400);
  },

  // 模板名里的快递公司名（后端取 ExpressCompanies#nameOf）；替身只认快递100 那八家
  // 替身按「多数省份一个价、偏远几档加价」造报价，推导规则与后端 FreightDraftServiceImpl#build 同一套
  draftFreightTemplate: async (v) => {
    if (!v.origin.trim()) fail("请填发货地址", "Enter the ship-from address");
    if (v.firstWeightGram < MIN_FIRST_WEIGHT_GRAM) {
      fail(`首重不得少于 ${MIN_FIRST_WEIGHT_GRAM} 克`, `The first weight cannot be under ${MIN_FIRST_WEIGHT_GRAM} g`);
    }
    const far: Record<string, [number, number]> = { 新疆: [2000, 1500], 西藏: [2500, 1800], 青海: [1200, 600], 海南: [1000, 300] };
    const regions = ["北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏", "浙江", "安徽",
      "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州", "云南", "西藏",
      "陕西", "甘肃", "青海", "宁夏", "新疆"];
    const rows = regions.map((region) => {
      const [f, a] = far[region] ?? [600, 200];
      return { region, firstFee: region === "西藏" ? null : f, addFee: region === "西藏" ? null : a };
    });
    const baseFirst = 600;
    const baseAdd = 200;
    const outOfRange = rows.flatMap((r): OutOfRangeRule[] => {
      if (r.firstFee === null) return [{ region: r.region, action: "REJECT", surcharge: 0 }];
      const extra = Math.max(0, r.firstFee - baseFirst) + Math.max(0, (r.addFee ?? 0) - baseAdd);
      return extra > 0 ? [{ region: r.region, action: "SURCHARGE", surcharge: extra }] : [];
    });
    const city = v.origin.replace(/^(.*?(省|自治区))/, "").replace(/(市).*$/, "$1") || v.origin;
    return wait({
      name: `${city}发 · ${CARRIER_NAMES[v.carrier] ?? v.carrier}`, firstWeightGram: v.firstWeightGram, firstFee: baseFirst,
      addWeightGram: v.addWeightGram, addFee: baseAdd, outOfRange, rows, unquoted: 1,
    }, 800);
  },

  archiveFreightTemplate: async (templateNo) => {
    const t = db.freightTemplates.find((x) => x.templateNo === templateNo);
    if (!t) notFound("模板", "Template", templateNo);
    // 归档之后新商家没有模板可用
    if (t.isDefault) fail("默认模板不能归档 —— 归档之后新商家没有模板可用", "The default template cannot be archived — new merchants would have none to use");
    return wait(db.archiveRow(db.freightTemplates, "templateNo", templateNo), 300);
  },

  unarchiveFreightTemplate: async (templateNo) =>
    wait(db.unarchiveRow(db.freightTemplates, "templateNo", templateNo), 300),

  listCarriers: async () => wait([...db.carriers].sort((a, b) => a.priority - b.priority)),

  saveCarrier: async (v) => {
    const c = db.carriers.find((x) => x.carrier === v.carrier);
    if (!c) notFound("运力", "Carrier", v.carrier);
    if (!v.name.trim()) fail("运力名称不能为空", "The carrier name cannot be empty");
    if (!Number.isInteger(v.priority) || v.priority < 1) fail("优先级必须是正整数", "Priority must be a positive whole number");
    // 同优先级时选哪家取决于数组顺序，那是隐性行为
    if (db.carriers.some((x) => x.carrier !== v.carrier && x.priority === v.priority)) {
      fail(`优先级 ${v.priority} 已被别的运力占用，同优先级时选哪家将取决于顺序`, `Priority ${v.priority} is taken by another carrier — sharing one means the winner depends on order`);
    }
    if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(v.pickupCutoff)) fail("截单时间必须是 HH:mm，例如 17:00", "The cut-off must be HH:mm, for example 17:00");
    if (!Number.isInteger(v.slaHours) || v.slaHours <= 0) fail("承诺时效必须是正整数小时", "The promised SLA must be a positive whole number of hours");

    Object.assign(c, v, { updatedAt: new Date().toISOString(), updatedBy: "admin" });
    return wait(c, 400);
  },

  setCarrierEnabled: async (carrier, enabled) => {
    const c = db.carriers.find((x) => x.carrier === carrier);
    if (!c) notFound("运力", "Carrier", carrier);

    if (enabled) {
      // 启用了下单当场失败，那比不启用更糟：至少不启用时运营知道它不可用
      if (!c.apiKeyConfigured) fail(`${c.name} 尚未配置接入密钥，启用后下单会当场失败`, `${c.name} has no API key configured — bookings would fail the moment it is enabled`);
    } else {
      // 停了之后这些单的轨迹拉不回来
      const inFlight = db.shipments.filter(
        (s) => s.carrier === carrier && s.status !== "DELIVERED",
      );
      if (inFlight.length) {
        fail(`${c.name} 还有 ${inFlight.length} 个在途快递单，停用后这些单的轨迹会拉不回来`, `${c.name} still has ${inFlight.length} parcels in transit — disabling it strands their tracking`);
      }
      // 全停之后快递单无处可下
      const others = db.carriers.filter((x) => x.carrier !== carrier && x.enabled);
      if (!others.length) fail("至少要保留一家启用的运力 —— 全停之后快递单无处可下", "Keep at least one carrier enabled — with none there is nowhere to book a parcel");
    }

    c.enabled = enabled;
    c.updatedAt = new Date().toISOString();
    c.updatedBy = "admin";
    return wait(c, 400);
  },
};
