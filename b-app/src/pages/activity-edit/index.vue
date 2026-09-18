<script setup lang="ts">
/*
 * 活动：新建 / 编辑 / 详情（原型 s03–s08 · s19 · s32 · TDD-营销域-详细设计 §1.2）。
 *
 * **新建两步：填写 → 确认**。玩法是表单第二行（选择面板），不是进表单前的一道门 ——
 * 店主给过语序：「活动名、活动类型、开始以及结束日期以及其他选项」。
 * 每种玩法都是**同一张表**，分四组：基本 / 范围 / 规则 / 上限；换玩法只换「规则」一组，
 * 行由 `packages/shared` 的玩法模板决定（`PLAY_TEMPLATES`）。没选玩法时不出现「规则」组 ——
 * 否则先填了满减的门槛再改成拼团，前面白填。
 *
 * **带活动号进来是详情**（s07 / s32）：三个数 + 与填写页同序的只读行 +
 * 实例入口（拼团「已开的团」、集单「今日一期」）+ 底部「暂停 / 结束」。
 * 结束的确认框说清「已开的团、已下单的集单不受影响」—— 规则与实例分层在界面上唯一必须露出的地方。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { isoDay } from "@/shared/quick-dates";
import { ROUTES } from "@/shared/nav";
import { confirm } from "@ai-shop/ui/prompt";
import { useMerchantStore } from "@/stores/merchant";
import { money, toMinor } from "@shared/utils/money";
import { PLAY_TEMPLATES, playOf, playOfActivity, type PlayTemplate } from "@shared/utils/play-templates";
import type { ActivityConflict, ActivityRuleItem, Goods, StoreActivity, StoreActivityDraft } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

/** 详情（带单号进来）还是表单 */
const mode = ref<"form" | "detail">("form");
const step = ref<1 | 2>(1);
const current = ref<StoreActivity | null>(null);
const currentNo = ref("");
const failed = ref(false);
const saving = ref(false);
const busy = ref(false);
const conflicts = ref<ActivityConflict[]>([]);

const form = ref({
  name: "",
  playKey: "",
  scheduleType: "ONE_OFF",
  startDay: isoDay(),
  endDay: isoDay(-7),
  weekdays: [] as number[],
  from: "08:00",
  to: "20:00",
  goodsNos: [] as string[],
  /** "" 所有人 / NON_MEMBER / SLEEPING / LOYAL */
  audience: "",
  threshold: "",
  qtyN: "",
  amount: "",
  price: "",
  buyN: "",
  giftM: "",
  groupN: "3",
  groupHours: "24",
  cutoffTime: "20:00",
  pickupOffset: 1,
  pickupFrom: "09:00",
  minQty: "",
  decideHours: "",
  quota: "",
  budget: "",
  periodQuota: "",
  /** 自己组合（s11）：条件（全部满足）与优惠（按顺序叠加）。「指定商品」的货放在 goodsNos 里 */
  conds: [] as ComboRow[],
  bens: [] as ComboRow[],
});

/**
 * 自己组合的一行。value：满金额 / 减 = 元；满件数 = 件；打折 = 几折（8 = 8 折）；送积分 = 分。cap：打折的封顶（元）
 */
interface ComboRow {
  type: string;
  value: string;
  cap: string;
}
const COND_TYPES = ["GOODS", "QTY", "AMOUNT"] as const;
const BEN_TYPES = ["CUT", "PERCENT", "POINTS"] as const;
const showAddCond = ref(false);
const showAddBen = ref(false);

function addCond(type: string) {
  if (!form.value.conds.some((c) => c.type === type)) form.value.conds.push({ type, value: "", cap: "" });
  showAddCond.value = false;
}

function addBen(type: string) {
  if (!form.value.bens.some((b) => b.type === type)) form.value.bens.push({ type, value: "", cap: "" });
  showAddBen.value = false;
}

/** 表单 → 组合行（与后端 RuleItem 同形） */
function comboRules(): ActivityRuleItem[] {
  const conds: ActivityRuleItem[] = form.value.conds.map((c) => c.type === "GOODS"
    ? { kind: "CONDITION", type: "GOODS", goodsNos: [...form.value.goodsNos] }
    : c.type === "QTY"
      ? { kind: "CONDITION", type: "QTY", n: Number(c.value || 0) }
      : { kind: "CONDITION", type: "AMOUNT", amountMinor: toMinor(c.value) });
  const bens: ActivityRuleItem[] = form.value.bens.map((b) => b.type === "CUT"
    ? { kind: "BENEFIT", type: "CUT", amountMinor: toMinor(b.value) }
    // 「8 折」按万分比提交：8 → 8000。让商家直接填 8000 的话他迟早会填 80
    : b.type === "PERCENT"
      ? { kind: "BENEFIT", type: "PERCENT", bp: Math.round(Number(b.value || 0) * 1000), capMinor: toMinor(b.cap) }
      : { kind: "BENEFIT", type: "POINTS", n: Number(b.value || 0) });
  return [...conds, ...bens];
}

/** 组合读成一句话（s11 底部）：读不通顺，多半是配错了 */
const comboSentence = computed(() => {
  const yuan = (v: string) => money(toMinor(v));
  const conds = form.value.conds.map((c) => c.type === "GOODS"
    ? String(t("activityEdit.comboS.GOODS", { n: form.value.goodsNos.length }))
    : String(t(`activityEdit.comboS.${c.type}`, { n: c.type === "AMOUNT" ? yuan(c.value) : c.value || "?" }))).join("");
  const bens = form.value.bens.map((b) => b.type === "PERCENT"
    ? String(t("activityEdit.comboS.PERCENT", { z: b.value || "?", c: yuan(b.cap) }))
    : String(t(`activityEdit.comboS.${b.type}`, { n: b.type === "CUT" ? yuan(b.value) : b.value || "?" }))).join("，");
  return conds && bens ? `${conds}：${bens}` : "";
});

const play = computed<PlayTemplate | undefined>(() => playOf(form.value.playKey));
const has = (f: string) => !!play.value?.rules.includes(f as never);

// ---------------------------------------------------------------- 选择面板
const showPlay = ref(false);
const showGoods = ref(false);
const showAudience = ref(false);
const AUDIENCES = ["", "NON_MEMBER", "SLEEPING", "LOYAL"] as const;

function pickPlay(key: string) {
  const prev = form.value.playKey;
  form.value.playKey = key;
  const p = playOf(key)!;
  if (p.fixedSchedule) form.value.scheduleType = p.fixedSchedule;
  else if (prev && playOf(prev)?.fixedSchedule) form.value.scheduleType = "ONE_OFF";
  if (p.audience) form.value.audience = p.audience;
  if (!form.value.name.trim()) form.value.name = String(t(`plays.name.${key}`));
  showPlay.value = false;
}

const goods = ref<Goods[]>([]);
const goodsLoaded = ref(false);
async function loadGoods() {
  try {
    goods.value = (await api.mGoodsList({ size: 100 })).records;
  } catch {
    // 拉不到就让它空着：选不了货保存会被拦，比在这儿弹一个错更清楚
  } finally {
    goodsLoaded.value = true;
  }
}

function toggleGoods(no: string) {
  const cur = form.value.goodsNos;
  form.value.goodsNos = cur.includes(no) ? cur.filter((x) => x !== no) : [...cur, no];
}

function toggleWeekday(d: number) {
  const cur = form.value.weekdays;
  form.value.weekdays = cur.includes(d) ? cur.filter((x) => x !== d) : [...cur, d];
}

const goodsText = computed(() => {
  const n = form.value.goodsNos.length;
  if (!n) return String(t("activityEdit.goodsNone"));
  if (n === 1) return goods.value.find((g) => g.goodsNo === form.value.goodsNos[0])?.title
    ?? String(t("activityEdit.goodsN", { n }));
  return String(t("activityEdit.goodsN", { n }));
});

const audienceText = computed(() => String(t(`activityEdit.audienceOpt.${form.value.audience || "ALL"}`)));

// ---------------------------------------------------------------- 日期
function dayOf(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
/** `YYYY-MM-DD` → 本地 00:00。**不用 Date.parse** —— 它按 UTC 解，东八区会早八小时 */
function dayStart(day: string): number {
  const [y, m, d] = day.split("-").map(Number);
  return new Date(y!, (m ?? 1) - 1, d ?? 1, 0, 0, 0, 0).getTime();
}
/** 结束日含当天：店主说「到下周日」指整个周日 */
function dayEnd(day: string): number {
  const [y, m, d] = day.split("-").map(Number);
  return new Date(y!, (m ?? 1) - 1, d ?? 1, 23, 59, 59, 999).getTime();
}

// ---------------------------------------------------------------- 组草稿与校验
const isItemCost = computed(() => play.value?.benefitType === "PRICE" || play.value?.benefitType === "GIFT");

/** 最多让利。减钱类 = 份数 × 每次；改价与送货的单次成本由商品决定，按份数封顶 */
const exposureText = computed(() => {
  const n = Number(form.value.quota || 0);
  if (play.value?.benefitType === "CUT" && n) return money(n * toMinor(form.value.amount));
  if (toMinor(form.value.budget)) return money(toMinor(form.value.budget));
  return String(t("activityEdit.exposureUnknown"));
});

function draft(): StoreActivityDraft {
  const p = play.value!;
  const aud = form.value.audience;
  const audiences = !aud ? [] : aud === "NON_MEMBER" ? [{ type: "NON_MEMBER", value: "*" }]
    : [{ type: "LEVEL", value: aud }];
  const schedule = p.fixedSchedule ?? form.value.scheduleType;
  const triggerQty = p.key === "GROUP" ? Number(form.value.groupN || 0)
    : p.key === "CUT_QTY" ? Number(form.value.qtyN || 0)
      : p.key === "GIFT" ? Number(form.value.buyN || 0) : null;
  const benefitAmount = p.benefitType === "CUT" ? toMinor(form.value.amount)
    : p.benefitType === "PRICE" ? toMinor(form.value.price) : null;
  const n = (v: string) => (v === "" ? null : Number(v));
  return {
    activityNo: current.value?.activityNo,
    name: form.value.name.trim(),
    goal: null,
    triggerType: p.triggerType,
    triggerAmountMinor: p.key === "CUT" ? toMinor(form.value.threshold) : null,
    triggerQty,
    benefitType: p.benefitType,
    benefitAmountMinor: benefitAmount,
    benefitQty: p.benefitType === "GIFT" ? Number(form.value.giftM || 0) : null,
    scheduleType: schedule,
    startAt: schedule === "ONE_OFF" ? dayStart(form.value.startDay) : Date.now(),
    endAt: schedule === "ONE_OFF" ? dayEnd(form.value.endDay) : null,
    scheduleRule: schedule === "RECURRING"
      ? JSON.stringify({ weekdays: form.value.weekdays, from: form.value.from, to: form.value.to })
      : null,
    quota: n(form.value.quota),
    budgetMinor: toMinor(form.value.budget) || null,
    audiences,
    goodsNos: p.needsGoods ? form.value.goodsNos : [],
    cutoffTime: p.triggerType === "CUTOFF" ? form.value.cutoffTime : null,
    pickupOffset: p.triggerType === "CUTOFF" ? form.value.pickupOffset : null,
    pickupFrom: p.triggerType === "CUTOFF" ? form.value.pickupFrom : null,
    minQty: p.triggerType === "CUTOFF" ? n(form.value.minQty) : null,
    periodQuota: p.triggerType === "CUTOFF" ? n(form.value.periodQuota) : null,
    decideHours: p.triggerType === "CUTOFF" ? n(form.value.decideHours) : null,
    groupHours: p.triggerType === "GROUP" ? n(form.value.groupHours) : null,
    rules: p.key === "COMBO" ? comboRules() : null,
  };
}

/** 与后端同一套硬校验，拦在「下一步」—— 到确认页才知道填错就晚了 */
function problem(): string | null {
  const p = play.value;
  if (!form.value.name.trim()) return "activityEdit.need.name";
  if (!p) return "activityEdit.need.play";
  if (p.needsGoods && !form.value.goodsNos.length) return "activityEdit.need.goods";
  if (p.key === "COMBO") {
    // 与后端 assertCombo 同一口径：至少一个条件、一个优惠，每一行都填得成立；打折必须封顶
    if (!form.value.conds.length || !form.value.bens.length) return "activityEdit.need.combo";
    for (const c of form.value.conds) {
      if (c.type === "GOODS" ? !form.value.goodsNos.length : !(Number(c.value) > 0)) return "activityEdit.need.combo";
    }
    for (const b of form.value.bens) {
      if (!(Number(b.value) > 0)) return "activityEdit.need.combo";
      if (b.type === "PERCENT" && (!(Number(b.value) >= 1 && Number(b.value) < 10) || !(toMinor(b.cap) > 0))) {
        return "activityEdit.need.comboPercent";
      }
    }
  }
  const rules: Record<string, string> = {
    threshold: form.value.threshold, qtyN: form.value.qtyN, amount: form.value.amount,
    price: form.value.price, buyN: form.value.buyN, giftM: form.value.giftM, groupN: form.value.groupN,
  };
  for (const r of p.rules) {
    if (r in rules && !(Number(rules[r]) > 0)) return "activityEdit.need.rule";
  }
  if (p.key === "GROUP" && Number(form.value.groupN) < 2) return "activityEdit.need.rule";
  if (p.triggerType === "CUTOFF" && !/^([01]\d|2[0-3]):[0-5]\d$/.test(form.value.cutoffTime)) {
    return "activityEdit.need.cutoff";
  }
  const schedule = p.fixedSchedule ?? form.value.scheduleType;
  const capped = !!Number(form.value.quota || 0) || !!toMinor(form.value.budget);
  if (schedule === "ALWAYS_ON" && !capped) return "activityEdit.need.cap";
  if (isItemCost.value && !Number(form.value.quota || 0)) return "activityEdit.need.quota";
  return null;
}

async function next() {
  const key = problem();
  if (key) {
    uni.showToast({ title: String(t(key)), icon: "none" });
    return;
  }
  conflicts.value = play.value?.needsGoods && form.value.goodsNos.length
    ? await api.mActivityConflicts(form.value.goodsNos).catch(() => [])
    : [];
  step.value = 2;
}

/**
 * A6（原型 s35）：**开始了的活动只能改结束时间与上限**。已有订单按旧规则算过价，
 * 这时改门槛 / 优惠 / 商品，同一个活动就有两种价。判据与后端 `ActivityServiceImpl#started` 一致：
 * 进行中或暂停着，且开始时刻已过（长期活动建好即开始）。
 */
const locked = computed(() => {
  const a = current.value;
  if (!a) return false;
  const live = a.status === "RUNNING" || a.status === "PAUSED";
  return live && (!a.startAt || a.startAt <= Date.now());
});

/**
 * 锁定时的入参：规则**从已存的那一条原样回传**，不从表单重算 ——
 * 表单会把长期活动的开始时刻算成「此刻」，回传出去后端就当成改了规则。只换四项：结束、份数、预算、每期份数。
 */
function lockedDraft(): StoreActivityDraft {
  const a = current.value!;
  const n = (v: string) => (v === "" ? null : Number(v));
  return {
    activityNo: a.activityNo, name: a.name, goal: a.goal ?? null, storeNo: a.storeNo ?? null,
    triggerType: a.triggerType ?? undefined, triggerAmountMinor: a.triggerAmountMinor ?? null,
    triggerQty: a.triggerQty ?? null, benefitType: a.benefitType,
    benefitAmountMinor: a.benefitAmountMinor ?? null, benefitQty: a.benefitQty ?? null,
    benefitRef: a.benefitRef ?? null, scheduleType: a.scheduleType, startAt: a.startAt ?? null,
    endAt: a.scheduleType === "ONE_OFF" ? dayEnd(form.value.endDay) : a.endAt ?? null,
    scheduleRule: a.scheduleRule ?? null,
    quota: n(form.value.quota), budgetMinor: toMinor(form.value.budget) || null,
    audiences: a.audiences, goodsNos: a.goodsNos,
    cutoffTime: a.cutoffTime ?? null, pickupOffset: a.pickupOffset ?? null, pickupFrom: a.pickupFrom ?? null,
    minQty: a.minQty ?? null,
    periodQuota: a.triggerType === "CUTOFF" ? n(form.value.periodQuota) : null,
    decideHours: a.decideHours ?? null, groupHours: a.groupHours ?? null,
    rules: a.rules ?? null,
  };
}

async function saveLocked() {
  if (saving.value || !current.value) return;
  saving.value = true;
  try {
    await api.mSaveActivity(lockedDraft());
    uni.showToast({ title: String(t("activityEdit.saved")), icon: "none" });
    await loadExisting(current.value.activityNo);
    mode.value = "detail";
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

async function publish() {
  if (saving.value) return;
  saving.value = true;
  try {
    await api.mSaveActivity(draft());
    uni.showToast({ title: String(t("activityEdit.saved")), icon: "none" });
    setTimeout(() => uni.navigateBack(), 600);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

// ---------------------------------------------------------------- 确认页的只读行
const ruleSummary = computed(() => {
  const p = play.value;
  if (!p) return "";
  const yuan = (v: string) => money(toMinor(v));
  switch (p.key) {
    case "CUT": return String(t("activities.ruleCut", { n: yuan(form.value.threshold), m: yuan(form.value.amount) }));
    case "CUT_QTY": return String(t("activities.ruleCutQty", { n: form.value.qtyN, m: yuan(form.value.amount) }));
    case "CUT_ANY":
    case "NEW_CUSTOMER": return String(t("activities.ruleCutAny", { m: yuan(form.value.amount) }));
    case "PRICE": return String(t("activities.rulePrice", { n: yuan(form.value.price) }));
    case "GIFT": return String(t("activities.ruleGift", { n: form.value.buyN, m: form.value.giftM }));
    case "GROUP": return String(t("activities.ruleGroup", { n: form.value.groupN, m: yuan(form.value.price) }));
    case "BATCH": return String(t("activities.ruleBatch", { m: yuan(form.value.price) }));
    case "COMBO": return comboSentence.value;
    default: return "";
  }
});

const timeSummary = computed(() => {
  const p = play.value;
  if (p?.triggerType === "CUTOFF") return String(t("activities.batchDaily", { t: form.value.cutoffTime }));
  const s = p?.fixedSchedule ?? form.value.scheduleType;
  if (s === "ALWAYS_ON") return String(t("activities.always"));
  if (s === "RECURRING") {
    const d = form.value.weekdays.map((w) => String(t(`activities.weekday.${w}`))).join("、");
    return String(t("activities.recurring", { d, f: form.value.from, e: form.value.to }));
  }
  return String(t("activities.range", { s: form.value.startDay.slice(5), e: form.value.endDay.slice(5) }));
});

const capSummary = computed(() => {
  const parts: string[] = [];
  if (form.value.quota) parts.push(`${form.value.quota} ${String(t("period.qty"))}`);
  if (form.value.periodQuota) parts.push(`${String(t("activityEdit.periodQuota"))} ${form.value.periodQuota}`);
  if (toMinor(form.value.budget)) parts.push(money(toMinor(form.value.budget)));
  return parts.join(" · ") || String(t("activityEdit.unlimited"));
});

// ---------------------------------------------------------------- 详情
async function loadExisting(no: string) {
  currentNo.value = no;
  let a: StoreActivity;
  try {
    a = await api.mActivity(no);
    failed.value = false;
  } catch {
    failed.value = true;
    return;
  }
  current.value = a;
  mode.value = "detail";
  const p = playOfActivity(a);
  const yuan = (m?: number | null) => (m == null ? "" : (m / 100).toFixed(2));
  form.value = {
    ...form.value,
    name: a.name,
    playKey: p?.key ?? "",
    scheduleType: a.scheduleType,
    startDay: a.startAt ? dayOf(a.startAt) : form.value.startDay,
    endDay: a.endAt ? dayOf(a.endAt) : form.value.endDay,
    goodsNos: [...a.goodsNos],
    audience: a.audiences[0]?.type === "NON_MEMBER" ? "NON_MEMBER"
      : a.audiences[0]?.type === "LEVEL" ? String(a.audiences[0]?.value) : "",
    threshold: yuan(a.triggerAmountMinor),
    qtyN: a.triggerType === "QTY" && a.benefitType === "CUT" ? String(a.triggerQty ?? "") : "",
    amount: a.benefitType === "CUT" ? yuan(a.benefitAmountMinor) : "",
    price: a.benefitType === "PRICE" ? yuan(a.benefitAmountMinor) : "",
    buyN: a.benefitType === "GIFT" ? String(a.triggerQty ?? "") : "",
    giftM: a.benefitType === "GIFT" ? String(a.benefitQty ?? "") : "",
    groupN: a.triggerType === "GROUP" ? String(a.triggerQty ?? 3) : "3",
    groupHours: a.groupHours == null ? "24" : String(a.groupHours),
    cutoffTime: a.cutoffTime ?? "20:00",
    pickupOffset: a.pickupOffset ?? 1,
    pickupFrom: a.pickupFrom ?? "09:00",
    minQty: a.minQty == null ? "" : String(a.minQty),
    decideHours: a.decideHours == null ? "" : String(a.decideHours),
    quota: a.quota == null ? "" : String(a.quota),
    budget: a.budgetMinor ? (a.budgetMinor / 100).toFixed(2) : "",
    periodQuota: a.periodQuota == null ? "" : String(a.periodQuota),
  };
  if (a.triggerType === "COMBO") {
    const rules = a.rules ?? [];
    form.value.conds = rules.filter((r) => r.kind === "CONDITION").map((r) => ({
      type: r.type,
      value: r.type === "AMOUNT" ? yuan(r.amountMinor) : r.type === "QTY" ? String(r.n ?? "") : "",
      cap: "",
    }));
    form.value.bens = rules.filter((r) => r.kind === "BENEFIT").map((r) => ({
      type: r.type,
      value: r.type === "CUT" ? yuan(r.amountMinor) : r.type === "PERCENT" ? String((r.bp ?? 0) / 1000) : String(r.n ?? ""),
      cap: r.type === "PERCENT" ? yuan(r.capMinor) : "",
    }));
    form.value.goodsNos = [...(rules.find((r) => r.type === "GOODS")?.goodsNos ?? [])];
  }
  if (a.scheduleRule) {
    try {
      const r = JSON.parse(a.scheduleRule) as { weekdays?: number[]; from?: string; to?: string };
      form.value.weekdays = r.weekdays ?? [];
      form.value.from = r.from ?? "08:00";
      form.value.to = r.to ?? "20:00";
    } catch { /* 坏规则读不出来就用默认值，保存时后端会拦 */ }
  }
}

function edit() {
  mode.value = "form";
  step.value = 1;
}

async function setStatus(next: string) {
  if (!current.value || busy.value) return;
  if (next === "ENDED") {
    const ok = await confirm({
      title: String(t("activities.endTitle", { name: current.value.name })),
      hint: String(t("activities.endBody")),
      danger: true,
    });
    if (!ok) return;
  }
  busy.value = true;
  try {
    await api.mSetActivityStatus(current.value.activityNo, next);
    await loadExisting(current.value.activityNo);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function openInstances() {
  const a = current.value;
  if (!a) return;
  uni.navigateTo({ url: a.triggerType === "CUTOFF" ? ROUTES.periods : ROUTES.groups });
}

function back() {
  if (step.value === 2) step.value = 1;
  else if (current.value) mode.value = "detail";
  else uni.navigateBack();
}

onLoad((q) => {
  void loadGoods();
  if (q?.activityNo) void loadExisting(q.activityNo as string);
});
</script>

<template>
  <sh-scaffold
    :title-key="current ? 'activityEdit.titleEdit' : 'activityEdit.title'"
    :denied="!merchant.can('biz:campaign')"
    :failed="failed"
    @retry="() => loadExisting(currentNo)"
  >
    <!-- ============================== 详情（s07 / s32） -->
    <template v-if="mode === 'detail' && current">
      <view class="sh-card">
        <sh-stat :items="[
          { value: current.quotaUsed, label: String($t('activityEdit.statUsed')) },
          { value: money(current.budgetUsedMinor), label: String($t('activityEdit.statSpent')) },
          { value: current.quotaLeft == null ? String($t('activityEdit.unlimited')) : current.quotaLeft,
            label: String($t('activityEdit.statLeft')) },
        ]"></sh-stat>
      </view>

      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.name") }}</text>
          <view class="sh-row">
            <text class="txt-body">{{ current.name }}</text>
            <text class="sh-chip" :class="{ 'sh-chip--success': current.status === 'RUNNING' }">
              {{ $t(`activityEdit.status.${current.status}`) }}
            </text>
          </view>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.play") }}</text>
          <text class="txt-body">{{ play ? $t(`plays.name.${play.key}`) : "" }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.schedule") }}</text>
          <text class="txt-body sh-num">{{ timeSummary }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.groupRule") }}</text>
          <text class="txt-body sh-num">{{ ruleSummary }}</text>
        </view>
        <view v-if="play?.needsGoods" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.goods") }}</text>
          <text class="txt-body">{{ goodsText }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.groupCap") }}</text>
          <text class="txt-body sh-num">{{ capSummary }}</text>
        </view>
      </view>

      <view v-if="play?.instance" class="sh-cells">
        <view class="sh-cell sh-row sh-row--between" @tap="openInstances">
          <text class="txt-body">{{ play.instance === "PERIOD" ? $t("activityEdit.periodsAll") : $t("activityEdit.openGroups") }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>

      <sh-actionbar v-if="current.status !== 'ENDED'">
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--soft sh-fill" :class="{ 'is-disabled': busy }" @tap="edit">
            {{ $t("activityEdit.edit") }}
          </view>
          <view class="sh-btn sh-btn--muted sh-fill" :class="{ 'is-disabled': busy }"
                @tap="setStatus(current.status === 'RUNNING' ? 'PAUSED' : 'RUNNING')">
            {{ current.status === "RUNNING" ? $t("activities.pause") : $t("activities.resume") }}
          </view>
          <view class="sh-btn sh-btn--danger sh-fill" :class="{ 'is-disabled': busy }" @tap="setStatus('ENDED')">
            {{ $t("activities.end") }}
          </view>
        </view>
      </sh-actionbar>
    </template>

    <!-- ============================== 填写（s03–s05 · s19） -->
    <!-- ============================== 编辑进行中的活动（s35）：锁住的行去掉 ›，只有结束与上限可改 -->
    <template v-else-if="locked && current">
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.name") }}</text>
          <text class="txt-body">{{ current.name }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.play") }}</text>
          <text class="txt-body">{{ play ? $t(`plays.name.${play.key}`) : "—" }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.groupRule") }}</text>
          <text class="txt-body sh-num">{{ ruleSummary }}</text>
        </view>
        <view v-if="current.goodsNos.length" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.goods") }}</text>
          <text class="txt-body">{{ $t("activityEdit.goodsN", { n: current.goodsNos.length }) }}</text>
        </view>
      </view>

      <text class="txt-caption sh-muted grp">{{ $t("activityEdit.editable") }}</text>
      <view class="sh-cells">
        <picker v-if="current.scheduleType === 'ONE_OFF'" mode="date" :value="form.endDay"
                @change="form.endDay = $event.detail.value">
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.end") }}</text>
            <text class="txt-body sh-num">{{ form.endDay }}</text>
          </view>
        </picker>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.quota") }}</text>
          <input v-model="form.quota" type="number" maxlength="6" class="txt-body cell__input sh-num" :placeholder="$t('activityEdit.quotaPh')" />
        </view>
        <view v-if="current.triggerType === 'CUTOFF'" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.periodQuota") }}</text>
          <input v-model="form.periodQuota" type="number" maxlength="6" class="txt-body cell__input sh-num" />
        </view>
      </view>

      <view class="sh-notice sh-notice--warning">
        <text class="txt-caption">{{ $t("activityEdit.lockedNote") }}</text>
      </view>

      <sh-actionbar>
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--muted sh-fill" @tap="back">{{ $t("activityEdit.cancel") }}</view>
          <view class="sh-btn bar__main" :class="{ 'is-disabled': saving }" @tap="saveLocked">{{ $t("activityEdit.save") }}</view>
        </view>
      </sh-actionbar>
    </template>

    <template v-else-if="step === 1">
      <view class="sh-row sh-row--between prog">
        <text class="txt-body txt-bold">{{ $t("activityEdit.stepFill") }}</text>
        <text class="txt-caption sh-muted sh-num">{{ $t("activityEdit.progress", { i: 1 }) }}</text>
      </view>

      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.name") }}</text>
          <input v-model="form.name" maxlength="64" class="txt-body cell__input" :placeholder="$t('activityEdit.namePh')" />
        </view>
        <view class="sh-cell sh-row sh-row--between" @tap="showPlay = true">
          <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.play") }}</text>
          <view class="sh-row">
            <text class="txt-body" :class="{ 'sh-muted': !play }">
              {{ play ? $t(`plays.name.${play.key}`) : $t("activityEdit.pickPh") }}
            </text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
        <view v-if="!play?.fixedSchedule" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.schedule") }}</text>
          <view class="sh-row segs">
            <text v-for="s in ['ONE_OFF', 'ALWAYS_ON', 'RECURRING']" :key="s"
                  class="sh-seg seg" :class="{ 'sh-seg--on': form.scheduleType === s }"
                  @tap="form.scheduleType = s">{{ $t(`activityEdit.scheduleType.${s}`) }}</text>
          </view>
        </view>
        <template v-if="!play?.fixedSchedule && form.scheduleType === 'ONE_OFF'">
          <picker mode="date" :value="form.startDay" @change="form.startDay = $event.detail.value">
            <view class="sh-cell sh-row sh-row--between">
              <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.start") }}</text>
              <text class="txt-body sh-num">{{ form.startDay }}</text>
            </view>
          </picker>
          <picker mode="date" :value="form.endDay" :start="form.startDay" @change="form.endDay = $event.detail.value">
            <view class="sh-cell sh-row sh-row--between">
              <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.end") }}</text>
              <text class="txt-body sh-num">{{ form.endDay }}</text>
            </view>
          </picker>
        </template>
        <template v-if="!play?.fixedSchedule && form.scheduleType === 'RECURRING'">
          <view class="sh-cell">
            <text class="txt-body sh-muted">{{ $t("activityEdit.weekdays") }}</text>
            <view class="sh-wrap weeks">
              <text v-for="d in [1, 2, 3, 4, 5, 6, 7]" :key="d" class="sh-seg seg"
                    :class="{ 'sh-seg--on': form.weekdays.includes(d) }"
                    @tap="toggleWeekday(d)">{{ $t(`activities.weekday.${d}`) }}</text>
            </view>
          </view>
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.timeRange") }}</text>
            <view class="sh-row">
              <picker mode="time" :value="form.from" @change="form.from = $event.detail.value">
                <text class="txt-body sh-num">{{ form.from }}</text>
              </picker>
              <text class="sh-muted">–</text>
              <picker mode="time" :value="form.to" @change="form.to = $event.detail.value">
                <text class="txt-body sh-num">{{ form.to }}</text>
              </picker>
            </view>
          </view>
        </template>
      </view>

      <template v-if="play">
        <text class="txt-caption sh-muted grp">{{ $t("activityEdit.groupScope") }}</text>
        <view class="sh-cells">
          <view v-if="play.needsGoods" class="sh-cell sh-row sh-row--between" @tap="showGoods = true">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.goods") }}</text>
            <view class="sh-row">
              <text class="txt-body" :class="{ 'sh-muted': !form.goodsNos.length }">{{ goodsText }}</text>
              <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
            </view>
          </view>
          <view class="sh-cell sh-row sh-row--between" @tap="showAudience = true">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.audience") }}</text>
            <view class="sh-row">
              <text class="txt-body">{{ audienceText }}</text>
              <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
            </view>
          </view>
        </view>

        <!-- 自己组合（s11）：条件与优惠两张清单，各自「＋ 添加」；底部读成一句话 -->
        <template v-if="play.key === 'COMBO'">
          <text class="txt-caption sh-muted grp">{{ $t("activityEdit.comboCond") }}</text>
          <view class="sh-cells">
            <view v-for="(c, i) in form.conds" :key="'c' + c.type" class="sh-cell sh-row sh-row--between">
              <text class="txt-body sh-muted cell__k">{{ $t(`activityEdit.comboType.${c.type}`) }}</text>
              <view class="sh-row combo__v">
                <text v-if="c.type === 'GOODS'" class="txt-body" @tap="showGoods = true">{{ goodsText }}</text>
                <input v-else v-model="c.value" :type="c.type === 'QTY' ? 'number' : 'digit'" maxlength="10"
                       class="txt-body sh-num cell__input" :placeholder="$t(`activityEdit.comboPh.${c.type}`)" />
                <view class="combo__x" @tap="form.conds.splice(i, 1)">
                  <sh-icon name="close" :size="22" color="var(--sh-sub)"></sh-icon>
                </view>
              </view>
            </view>
            <view v-if="form.conds.length < COND_TYPES.length" class="sh-cell sh-row" @tap="showAddCond = true">
              <sh-icon name="plus" :size="22" color="var(--sh-primary-text)"></sh-icon>
              <text class="txt-body txt-primary">{{ $t("activityEdit.comboAddCond") }}</text>
            </view>
          </view>

          <text class="txt-caption sh-muted grp">{{ $t("activityEdit.comboBen") }}</text>
          <view class="sh-cells">
            <view v-for="(b, i) in form.bens" :key="'b' + b.type" class="sh-cell sh-row sh-row--between">
              <text class="txt-body sh-muted cell__k">{{ $t(`activityEdit.comboType.${b.type}`) }}</text>
              <view class="sh-row combo__v">
                <input v-model="b.value" :type="b.type === 'POINTS' ? 'number' : 'digit'" maxlength="10"
                       class="txt-body sh-num cell__input" :placeholder="$t(`activityEdit.comboPh.${b.type}`)" />
                <input v-if="b.type === 'PERCENT'" v-model="b.cap" type="digit" maxlength="10"
                       class="txt-body sh-num cell__input" :placeholder="$t('activityEdit.comboPh.CAP')" />
                <view class="combo__x" @tap="form.bens.splice(i, 1)">
                  <sh-icon name="close" :size="22" color="var(--sh-sub)"></sh-icon>
                </view>
              </view>
            </view>
            <view v-if="form.bens.length < BEN_TYPES.length" class="sh-cell sh-row" @tap="showAddBen = true">
              <sh-icon name="plus" :size="22" color="var(--sh-primary-text)"></sh-icon>
              <text class="txt-body txt-primary">{{ $t("activityEdit.comboAddBen") }}</text>
            </view>
          </view>

          <view v-if="comboSentence" class="sh-notice">
            <text class="txt-caption">{{ comboSentence }}</text>
          </view>
        </template>

        <text v-if="play.key !== 'COMBO'" class="txt-caption sh-muted grp">{{ $t("activityEdit.groupRule") }}</text>
        <view v-if="play.key !== 'COMBO'" class="sh-cells">
          <view v-if="has('cutoffTime')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.cutoffTime") }}</text>
            <picker mode="time" :value="form.cutoffTime" @change="form.cutoffTime = $event.detail.value">
              <text class="txt-body sh-num">{{ form.cutoffTime }}</text>
            </picker>
          </view>
          <view v-if="has('pickup')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.pickupOffset") }}</text>
            <view class="sh-row segs">
              <text v-for="d in [0, 1, 2]" :key="d" class="sh-seg seg"
                    :class="{ 'sh-seg--on': form.pickupOffset === d }"
                    @tap="form.pickupOffset = d">{{ $t(`activityEdit.pickupDay.${d}`) }}</text>
            </view>
          </view>
          <view v-if="has('pickup')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.pickupFrom") }}</text>
            <picker mode="time" :value="form.pickupFrom" @change="form.pickupFrom = $event.detail.value">
              <text class="txt-body sh-num">{{ form.pickupFrom }}</text>
            </picker>
          </view>
          <view v-if="has('threshold')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.threshold") }}</text>
            <input v-model="form.threshold" type="digit" maxlength="10" class="txt-body sh-num cell__input" />
          </view>
          <view v-if="has('qtyN')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.qtyN") }}</text>
            <input v-model="form.qtyN" type="number" maxlength="4" class="txt-body sh-num cell__input" />
          </view>
          <view v-if="has('buyN')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.buyN") }}</text>
            <input v-model="form.buyN" type="number" maxlength="4" class="txt-body sh-num cell__input" />
          </view>
          <view v-if="has('giftM')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.giftM") }}</text>
            <input v-model="form.giftM" type="number" maxlength="4" class="txt-body sh-num cell__input" />
          </view>
          <view v-if="has('groupN')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.groupN") }}</text>
            <input v-model="form.groupN" type="number" maxlength="3" class="txt-body sh-num cell__input" />
          </view>
          <view v-if="has('amount')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.amount") }}</text>
            <input v-model="form.amount" type="digit" maxlength="10" class="txt-body sh-num cell__input" />
          </view>
          <view v-if="has('price')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.price") }}</text>
            <input v-model="form.price" type="digit" maxlength="10" class="txt-price sh-num cell__input" />
          </view>
          <view v-if="has('groupHours')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.groupHours") }}</text>
            <input v-model="form.groupHours" type="number" maxlength="3" class="txt-body sh-num cell__input" />
          </view>
          <view v-if="has('minQty')" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.minQty") }}</text>
            <input v-model="form.minQty" type="number" maxlength="6" class="txt-body sh-num cell__input"
                   :placeholder="$t('activityEdit.optional')" />
          </view>
          <view v-if="has('decideHours') && form.minQty" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.rule.decideHours") }}</text>
            <input v-model="form.decideHours" type="number" maxlength="3" class="txt-body sh-num cell__input"
                   placeholder="14" />
          </view>
        </view>

        <text class="txt-caption sh-muted grp">{{ $t("activityEdit.groupCap") }}</text>
        <view class="sh-cells">
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.quota") }}</text>
            <input v-model="form.quota" type="number" maxlength="6" class="txt-body sh-num cell__input"
                   :placeholder="isItemCost ? '' : $t('activityEdit.optional')" />
          </view>
          <view v-if="play.triggerType === 'CUTOFF'" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.periodQuota") }}</text>
            <input v-model="form.periodQuota" type="number" maxlength="6" class="txt-body sh-num cell__input"
                   :placeholder="$t('activityEdit.optional')" />
          </view>
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("activityEdit.budget") }}</text>
            <input v-model="form.budget" type="digit" maxlength="10" class="txt-body sh-num cell__input"
                   :placeholder="$t('activityEdit.optional')" />
          </view>
        </view>
      </template>

      <sh-actionbar>
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--muted sh-fill" @tap="back">{{ $t("activityEdit.prev") }}</view>
          <view class="sh-btn bar__main" @tap="next">{{ $t("activityEdit.next") }}</view>
        </view>
      </sh-actionbar>
    </template>

    <!-- ============================== 确认（s06） -->
    <template v-else>
      <view class="sh-row sh-row--between prog">
        <text class="txt-body txt-bold">{{ $t("activityEdit.stepConfirm") }}</text>
        <text class="txt-caption sh-muted sh-num">{{ $t("activityEdit.progress", { i: 2 }) }}</text>
      </view>
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.name") }}</text>
          <text class="txt-body">{{ form.name }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.play") }}</text>
          <text class="txt-body">{{ play ? $t(`plays.name.${play.key}`) : "" }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.schedule") }}</text>
          <text class="txt-body sh-num">{{ timeSummary }}</text>
        </view>
        <view v-if="play?.needsGoods" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.goods") }}</text>
          <text class="txt-body">{{ goodsText }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.audience") }}</text>
          <text class="txt-body">{{ audienceText }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.groupRule") }}</text>
          <text class="txt-body sh-num">{{ ruleSummary }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.groupCap") }}</text>
          <text class="txt-body sh-num">{{ capSummary }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("activityEdit.exposure") }}</text>
          <text class="txt-body sh-num">{{ exposureText }}</text>
        </view>
      </view>
      <view v-for="c in conflicts" :key="c.activityNo + c.goodsNo" class="sh-notice sh-notice--warning">
        <text class="txt-caption">{{ $t("activityEdit.conflict", { name: c.activityName }) }}</text>
      </view>

      <sh-actionbar>
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--muted sh-fill" @tap="back">{{ $t("activityEdit.prev") }}</view>
          <view class="sh-btn bar__main" :class="{ 'is-disabled': saving }" @tap="publish">
            {{ $t("activityEdit.publish") }}
          </view>
        </view>
      </sh-actionbar>
    </template>

    <!-- 选择面板：单选列表，选中项右侧打勾 -->
    <sh-sheet :visible="showAddCond" :title="String($t('activityEdit.comboAddCond'))" @close="showAddCond = false">
      <view class="sh-cells">
        <view v-for="c in COND_TYPES.filter((x) => !form.conds.some((r) => r.type === x))" :key="c"
              class="sh-cell sh-row sh-row--between" @tap="addCond(c)">
          <text class="txt-body">{{ $t(`activityEdit.comboType.${c}`) }}</text>
        </view>
      </view>
    </sh-sheet>

    <sh-sheet :visible="showAddBen" :title="String($t('activityEdit.comboAddBen'))" @close="showAddBen = false">
      <view class="sh-cells">
        <view v-for="b in BEN_TYPES.filter((x) => !form.bens.some((r) => r.type === x))" :key="b"
              class="sh-cell sh-row sh-row--between" @tap="addBen(b)">
          <text class="txt-body">{{ $t(`activityEdit.comboType.${b}`) }}</text>
        </view>
      </view>
    </sh-sheet>

    <sh-sheet :visible="showPlay" :title="String($t('activityEdit.playPick'))" @close="showPlay = false">
      <view class="sh-cells">
        <view v-for="p in PLAY_TEMPLATES" :key="p.key" class="sh-cell sh-row sh-row--between" @tap="pickPlay(p.key)">
          <view>
            <text class="txt-body" :class="{ 'txt-primary': form.playKey === p.key }">{{ $t(`plays.name.${p.key}`) }}</text>
            <text class="txt-caption sh-muted pick__d">{{ $t(`plays.desc.${p.key}`) }}</text>
          </view>
          <sh-icon v-if="form.playKey === p.key" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
      </view>
    </sh-sheet>

    <sh-sheet :visible="showAudience" :title="String($t('activityEdit.audience'))" @close="showAudience = false">
      <view class="sh-cells">
        <view v-for="a in AUDIENCES" :key="a || 'ALL'" class="sh-cell sh-row sh-row--between"
              @tap="form.audience = a; showAudience = false">
          <text class="txt-body" :class="{ 'txt-primary': form.audience === a }">{{ $t(`activityEdit.audienceOpt.${a || "ALL"}`) }}</text>
          <sh-icon v-if="form.audience === a" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
      </view>
    </sh-sheet>

    <sh-sheet :visible="showGoods" :title="String($t('activityEdit.goodsPick'))" @close="showGoods = false">
      <view class="sh-cells">
        <view v-for="g in goods" :key="g.goodsNo" class="sh-cell sh-row sh-row--between" @tap="toggleGoods(g.goodsNo)">
          <text class="txt-body" :class="{ 'txt-primary': form.goodsNos.includes(g.goodsNo) }">{{ g.title }}</text>
          <sh-icon v-if="form.goodsNos.includes(g.goodsNo)" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
      </view>
      <sh-empty v-if="!goods.length" :pending="!goodsLoaded" line :text="String($t('activityEdit.goodsEmpty'))"></sh-empty>
    </sh-sheet>
  </sh-scaffold>
</template>

<style scoped>
.prog {
  padding: 0 8rpx;
}
.grp {
  display: block;
  padding: 0 8rpx;
}
.cell__k {
  flex-shrink: 0;
}
.cell__input {
  flex: 1;
  text-align: end;
}
.segs {
  gap: 8rpx;
}
.seg {
  padding: 12rpx 20rpx;
}
.weeks {
  margin-top: 12rpx;
  gap: 8rpx;
}
.pick__d {
  display: block;
}
.combo__v {
  gap: 12rpx;
  flex: 1;
  justify-content: flex-end;
}
.combo__x {
  padding: 4rpx;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
