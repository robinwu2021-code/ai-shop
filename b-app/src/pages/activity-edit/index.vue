<script setup lang="ts">
// 建活动：四步向导（P5）。
//
// **为什么是向导而不是一张长表单**：这四步里每一步都可能让商家改主意
// （「原来长期活动必须设限量，那我改成一周」），而一张 12 个输入框的表单
// 要填到最后一个才知道前面填错了。老的营销页就是那样，它有 8 个字段
// 按活动类型显示/隐藏，商家切一次类型就有一半字段变空。
//
// 四步：① 想干什么（目标）② 优惠什么样 ③ 什么时候有效 ④ 给谁
// 目标那一步不是装饰：它决定后面三步的默认值 —— 拉新默认受众是「非会员」，
// 唤回默认是「沉睡」。商家从来不是先想「触发条件」的。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { isoDay } from "@/shared/quick-dates";
import { confirm } from "@ai-shop/ui/prompt";
import { useMerchantStore } from "@/stores/merchant";
import { money, toMinor } from "@shared/utils/money";
import type { ActivityConflict, Goods, StoreActivity, StoreActivityDraft } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const step = ref(1);
const activityNo = ref("");
const saving = ref(false);
const conflicts = ref<ActivityConflict[]>([]);

const form = ref({
  /** 类型：CUT / PRICE / GROUP / GIFT。**不是 goal** —— 见 TYPES 上面那段 */
  kind: "CUT",
  name: "",
  benefitType: "CUT",
  /** 满多少（元） */
  threshold: "50",
  /** 减多少 / 特价多少（元） */
  amount: "5",
  buyN: "2",
  giftM: "1",
  goodsNos: [] as string[],
  scheduleType: "ONE_OFF",
  /** 起止。店主说的是「这周六到下周日」，不是「限时 7 天」 */
  startDay: isoDay(),
  endDay: isoDay(-7),
  weekdays: [] as number[],
  from: "08:00",
  to: "20:00",
  quota: "100",
  budget: "",
  audienceType: "",
  /** 成团人数。**下限 2** —— 1 个人不叫团，后端也拒 */
  groupN: "2",
});

/*
 * ★ **类型，不是「目标」**（2026-09-18 店主：「整理逻辑应该是活动名、活动类型、
 * 开始以及结束日期以及其他选项」）。
 *
 * 改之前第一步问的是「你想达成什么」（拉新客 / 唤回老客 / 清库存 / 提高客单），
 * 而那五个**只是默认值生成器** —— 它们在第 1 步写一次 benefit 与 audience，
 * 之后第 2/4 步可以改回去，而 `goal` 是**存下来的**。于是「拉新客」的活动
 * 可以对所有人生效、名字还叫「新客立减」，没有任何一处校验。
 *
 * 现在第一步直接问**类型**：它就是 `triggerType × benefitType` 的那几个组合，
 * 与库里那一行一一对应，不会与别的字段互相矛盾。
 *
 * 「拉新客 / 唤回老客」降级成**受众那一步的预设** —— 它们本来就是受众的别名。
 *
 * **发券不放进来**（方案 §6.1）：模型支持 BENEFIT_COUPON，但券有自己的一页，
 * 两处都能发券会让人不知道该去哪儿。
 */
const TYPES = [
  { key: "CUT", trigger: "AMOUNT", benefit: "CUT" },
  { key: "PRICE", trigger: "GOODS", benefit: "PRICE" },
  { key: "GROUP", trigger: "GROUP", benefit: "PRICE" },
  { key: "GIFT", trigger: "QTY", benefit: "GIFT" },
];

function pickType(key: string) {
  const t2 = TYPES.find((x) => x.key === key)!;
  form.value.kind = key;
  form.value.benefitType = t2.benefit;
  if (!form.value.name) form.value.name = String(t(`activityEdit.typeName.${key}`));
}

/** 是不是团购活动。判的是目标，不是优惠类型 —— 清库存也是 PRICE */
/** 是不是团购活动。判的是类型 —— 它与「特价」都是 PRICE，靠 benefitType 分不开 */
const isGroup = computed(() => form.value.kind === "GROUP");

/*
 * ★ **暂停 / 恢复 / 结束搬到这一页**（2026-09-18）。
 *
 * 它们原来在活动列表上，是三个 24×15px 的纯文字 —— 看不出能点、也点不中。
 * 列表改成整条可点之后那三个字撤掉了，**但能力不能跟着没**：
 * 光撤不搬就是把「怎么停一个活动」这件事从产品里删掉了。
 */
async function setStatus(next: string) {
  if (!current.value || busy.value) return;
  if (next === "ENDED") {
    // 结束不可逆：确认框里要说清「不能再打开」，而不是只问「确定吗」
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
    uni.showToast({ title: String(t("activityEdit.saved")), icon: "none" });
    setTimeout(() => uni.navigateBack(), 600);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

const busy = ref(false);

/**
 * 从库里那一行反推类型。<b>先判触发再判优惠</b> —— 团购与特价都是 PRICE，
 * 只有 triggerType 分得开；反过来判的话，所有团购活动打开都会显示成「特价」。
 */
/** 毫秒 → `YYYY-MM-DD`（本地日历日）。回填时用，与 dayStart 互为逆 */
function dayOf(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** `YYYY-MM-DD` → 那一天本地 00:00 的毫秒。**不用 Date.parse** —— 它按 UTC 解 */
function dayStart(day: string): number {
  const [y, m, d] = day.split("-").map(Number);
  return new Date(y!, (m ?? 1) - 1, d ?? 1, 0, 0, 0, 0).getTime();
}

/** 那一天本地 23:59:59.999 —— 结束日是**含当天**的，店主说「到下周日」指整个周日 */
function dayEnd(day: string): number {
  const [y, m, d] = day.split("-").map(Number);
  return new Date(y!, (m ?? 1) - 1, d ?? 1, 23, 59, 59, 999).getTime();
}

function kindOf(a: StoreActivity): string {
  if (a.triggerType === "GROUP") return "GROUP";
  const hit = TYPES.find((x) => x.benefit === a.benefitType);
  return hit ? hit.key : "CUT";
}

const isItemCost = computed(
  () => form.value.benefitType === "PRICE" || form.value.benefitType === "GIFT",
);

/** 最大敞口。**边填边显示** —— 他填的是份数，要为之负责的是钱 */
const exposure = computed(() => {
  if (form.value.benefitType !== "CUT") return 0;
  const per = toMinor(form.value.amount);
  const n = Number(form.value.quota || 0);
  return per && n ? per * n : 0;
});

/** 长期活动没有限量也没有预算 = 永久敞口。这条与后端一字不差 */
const alwaysOnUncapped = computed(
  () => form.value.scheduleType === "ALWAYS_ON"
    && !Number(form.value.quota || 0) && !toMinor(form.value.budget),
);

/** 这次没取到。**与「这个东西不存在」是两件事** —— 预填失败留下的是一张空表单，
 *  照着它填完保存，存出来的是一条新的，原来那条还在 */
/** 重试要把单号带回去 —— `@retry` 不带参数，而 `activityNo`/`couponNo`
 *  是**加载成功之后**才设的，失败时它们是空的 */
const currentNo = ref("");
/**
 * 载入的那份活动。**效果三数与状态从它读**（2026-09-18）——
 * 它们原来长在活动列表的卡上，占掉每条 55px 而商家扫列表时并不看它们；
 * 看「这个花了多少」是专门来看的，那就该在这一页。
 */
const current = ref<StoreActivity | null>(null);
const failed = ref(false);

/*
 * ★ **选货控件此前根本不存在**（2026-09-18 查实）。
 *
 * `goodsNos` 只在「载入一个已有活动」时被填过，新建时永远是空数组，
 * 而「改单价 / 送商品必须指定商品」那条校验拦在保存那一步 ——
 * 于是四个目标里的**「清库存」与「买赠」从这一页建不出来**，
 * 报的是「特价和买赠必须选商品」，而界面上没有任何地方能选。
 * 线上 pmt_activity 0 条，与这条正好对得上。
 *
 * 写法照营销页那一段（chip 多选），不新造件。
 */
const goods = ref<Goods[]>([]);
/** 首屏到过没有。**不是 loading** —— 没有它的话，数据回来之前会先闪一下「本店还没有商品」 */
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
  void checkConflicts();
}

async function checkConflicts() {
  if (!form.value.goodsNos.length) {
    conflicts.value = [];
    return;
  }
  conflicts.value = await api.mActivityConflicts(form.value.goodsNos).catch(() => []);
}

function toggleWeekday(d: number) {
  form.value.weekdays = form.value.weekdays.includes(d)
    ? form.value.weekdays.filter((x) => x !== d)
    : [...form.value.weekdays, d];
}

async function loadExisting(no: string) {
  currentNo.value = no;
  /*
   * **`.catch(() => null)` 脱掉**。兜成 null 之后 `if (!a) return` 悄悄退出，
   * 留下一张空表单，而 `activityNo` 停在空 —— 商家照着空白填完点保存，
   * 存出来的是**一个新活动**，原来那个还在。他要过一阵才会发现多了一条。
   */
  let a;
  try {
    a = await api.mActivity(no);
    current.value = a;
    failed.value = false;
  } catch {
    failed.value = true;
    return;
  }
  activityNo.value = a.activityNo;
  form.value.kind = kindOf(a);
  if (a.startAt) form.value.startDay = dayOf(a.startAt);
  if (a.endAt) form.value.endDay = dayOf(a.endAt);
  form.value.name = a.name;
  form.value.benefitType = a.benefitType;
  form.value.threshold = String(((a.triggerAmountMinor ?? 0) / 100).toFixed(2));
  form.value.amount = String(((a.benefitAmountMinor ?? 0) / 100).toFixed(2));
  form.value.buyN = String(a.triggerQty ?? 2);
  form.value.groupN = String(a.triggerQty ?? 2);
  form.value.giftM = String(a.benefitQty ?? 1);
  form.value.goodsNos = [...a.goodsNos];
  form.value.scheduleType = a.scheduleType;
  form.value.quota = a.quota == null ? "" : String(a.quota);
  form.value.budget = a.budgetMinor ? String((a.budgetMinor / 100).toFixed(2)) : "";
  form.value.audienceType = a.audiences.length
    ? (a.audiences[0]!.type === "LEVEL"
      ? `LEVEL:${a.audiences[0]!.value}` : a.audiences[0]!.type)
    : "";
  if (a.scheduleRule) {
    try {
      const r = JSON.parse(a.scheduleRule) as { weekdays?: number[]; from?: string; to?: string };
      form.value.weekdays = r.weekdays ?? [];
      form.value.from = r.from ?? "08:00";
      form.value.to = r.to ?? "20:00";
    } catch { /* 坏规则读不出来就用默认值，保存时后端会拦 */ }
  }
  await checkConflicts();
}

async function save() {
  if (saving.value) return;
  if (!form.value.name.trim()) {
    uni.showToast({ title: t("activityEdit.needName"), icon: "none" });
    return;
  }
  if (alwaysOnUncapped.value) {
    uni.showToast({ title: t("activityEdit.alwaysOnNeedsCap"), icon: "none" });
    return;
  }
  if (isItemCost.value && !form.value.goodsNos.length) {
    uni.showToast({ title: t("activityEdit.needGoods"), icon: "none" });
    return;
  }
  if (isItemCost.value && !Number(form.value.quota || 0)) {
    uni.showToast({ title: t("activityEdit.needQuota"), icon: "none" });
    return;
  }

  const now = Date.now();
  const audiences = form.value.audienceType
    ? [form.value.audienceType.startsWith("LEVEL:")
      ? { type: "LEVEL", value: form.value.audienceType.slice(6) }
      : { type: form.value.audienceType, value: "*" }]
    : [];

  const draft: StoreActivityDraft = {
    activityNo: activityNo.value || undefined,
    name: form.value.name.trim(),
    // ★ goal 停写：它与 benefitType/audience 可以互相矛盾且无人校验（方案 §4.1）。
    // 列先留着不删 —— 与 prd_goods 那两列同一处置，回滚窗口留长
    goal: null,
    benefitType: form.value.benefitType,
    // 团购看目标不看优惠：它与「清库存」都是 PRICE，分不开就会把团存成特价
    triggerType: isGroup.value ? "GROUP"
      : form.value.benefitType === "GIFT" ? "QTY"
        : form.value.benefitType === "PRICE" ? "GOODS" : "AMOUNT",
    triggerAmountMinor: form.value.benefitType === "CUT" ? toMinor(form.value.threshold) : null,
    triggerQty: isGroup.value ? Number(form.value.groupN || 0)
      : form.value.benefitType === "GIFT" ? Number(form.value.buyN || 0) : null,
    benefitAmountMinor: form.value.benefitType === "GIFT" ? null : toMinor(form.value.amount),
    benefitQty: form.value.benefitType === "GIFT" ? Number(form.value.giftM || 0) : null,
    scheduleType: form.value.scheduleType,
    /*
     * 起止取**本地日历日的边界**：开始那天的 00:00、结束那天的 23:59:59。
     * 直接用 `Date.parse(day)` 的话拿到的是 UTC 零点 —— 在东八区会把活动
     * 提前八小时开始、提前八小时结束，而界面上写着的日期一个字都没变。
     */
    startAt: form.value.scheduleType === "ONE_OFF" ? dayStart(form.value.startDay) : null,
    endAt: form.value.scheduleType === "ONE_OFF" ? dayEnd(form.value.endDay) : null,
    scheduleRule: form.value.scheduleType === "RECURRING"
      ? JSON.stringify({ weekdays: form.value.weekdays, from: form.value.from, to: form.value.to })
      : null,
    quota: form.value.quota ? Number(form.value.quota) : null,
    budgetMinor: toMinor(form.value.budget) || null,
    audiences,
    goodsNos: form.value.goodsNos,
  };

  saving.value = true;
  try {
    await api.mSaveActivity(draft);
    uni.showToast({ title: t("activityEdit.saved"), icon: "none" });
    setTimeout(() => uni.navigateBack(), 600);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

onLoad((q) => {
  void loadGoods();
  if (q?.activityNo) void loadExisting(q.activityNo as string);
});
</script>

<template>
  <sh-scaffold :title-key="current ? 'activityEdit.titleEdit' : 'activityEdit.title'" :denied="!merchant.can('biz:campaign')"
    :failed="failed"
    @retry="() => loadExisting(currentNo)"
  >
    <!--
      进度只做指示，不做导航：走动靠底部的「上一步 / 下一步」。
      做成三颗可点的 chip 等于同一件事两套控件，且每一步都固定吃掉一行。
    -->
    <text class="txt-caption steps">{{ $t("activityEdit.stepOf", { i: step }) }} · {{ $t(`activityEdit.step${step}`) }}</text>

    <!-- ① 想干什么 -->
    <view v-if="step === 1" class="sh-card">
      <text class="field__label">{{ $t("activityEdit.typeQ") }}</text>
      <view class="opts">
        <sh-option
          v-for="ty in TYPES"
          :key="ty.key"
          :selected="form.kind === ty.key"
          @tap="pickType(ty.key)"
        >
          <text class="txt-strong opt__t">{{ $t(`activityEdit.type.${ty.key}`) }}</text>
          <text class="txt-caption sh-muted opt__d">{{ $t(`activityEdit.typeHint.${ty.key}`) }}</text>
        </sh-option>
      </view>
      <view class="sh-row sh-mt-sm sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("activityEdit.name") }}</text>
        <input maxlength="64" v-model="form.name" class="field__input row__input"
               :placeholder="$t('activityEdit.namePh')" />
      </view>
    </view>

    <!-- ③ 优惠什么样 -->
    <view v-if="step === 3" class="sh-card">
      <!-- 团购没有可选项，那这个提问也不该出现：一个问句下面空着比没有问句更怪 -->
      <text v-if="!isGroup" class="field__label">{{ $t("activityEdit.benefitQ") }}</text>
      <!--
        ★ **团购不给选优惠类型**（2026-09-18）：它只可能是「成团价」。
        给了三个选项而其中两个存不进去（后端拒），那不是自由，是让他试错。
      -->
      <view v-if="!isGroup" class="chips sh-wrap">
        <text
          v-for="b in ['CUT', 'PRICE', 'GIFT']"
          :key="b"
          class="sh-chip"
          :class="{ 'sh-chip--primary': form.benefitType === b }"
          @tap="form.benefitType = b"
        >{{ $t(`activityEdit.benefit.${b}`) }}</text>
      </view>

      <template v-if="form.benefitType === 'CUT'">
        <view class="sh-row sh-mt-sm sh-mt-xs">
          <text class="txt-sub row__label">{{ $t("activityEdit.threshold") }}</text>
          <input maxlength="10" v-model="form.threshold" class="field__input row__input" type="digit" />
        </view>
        <view class="sh-row sh-mt-xs">
          <text class="txt-sub row__label">{{ $t("activityEdit.cut") }}</text>
          <input maxlength="10" v-model="form.amount" class="field__input row__input" type="digit" />
        </view>
      </template>

      <!--
        团购多问一个人数，排在价格**前面**：他脑子里先有「几个人一起买」，
        才有「那便宜多少」。反过来问的话，填价时还不知道是几人的价。
      -->
      <view v-if="isGroup" class="sh-row sh-mt-sm sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("activityEdit.groupN") }}</text>
        <input maxlength="3" v-model="form.groupN" class="field__input row__input" type="number" />
      </view>

      <template v-if="form.benefitType === 'PRICE'">
        <view class="sh-row sh-mt-sm sh-mt-xs">
          <text class="txt-sub row__label">{{ isGroup ? $t("activityEdit.groupPrice") : $t("activityEdit.price") }}</text>
          <input maxlength="10" v-model="form.amount" class="field__input row__input" type="digit" />
        </view>
        <text class="sh-muted sh-hint">{{ isGroup ? $t("activityEdit.groupHint") : $t("activityEdit.priceHint") }}</text>
      </template>

      <template v-if="form.benefitType === 'GIFT'">
        <view class="sh-row sh-mt-sm sh-mt-xs">
          <text class="txt-sub row__label">{{ $t("activityEdit.buyN") }}</text>
          <input maxlength="6" v-model="form.buyN" class="field__input row__input" type="number" />
        </view>
        <view class="sh-row sh-mt-xs">
          <text class="txt-sub row__label">{{ $t("activityEdit.giftM") }}</text>
          <input maxlength="6" v-model="form.giftM" class="field__input row__input" type="number" />
        </view>
      </template>

      <!--
        选货。**只在「改单价 / 送商品」时出现** —— 满减是整单的，问他挑哪几件
        没有意义，而多一个控件就多一次「这个要不要填」。
      -->
      <template v-if="isItemCost">
        <text class="field__label sh-mt-sm">{{ $t("activityEdit.goodsQ") }}</text>
        <view class="chips sh-wrap">
          <text
            v-for="g in goods"
            :key="g.goodsNo"
            class="sh-chip"
            :class="{ 'sh-chip--primary': form.goodsNos.includes(g.goodsNo) }"
            @tap="toggleGoods(g.goodsNo)"
          >{{ g.title }}</text>
        </view>
        <!--
          三态交给件（:pending）：数据回来之前不许显示「本店还没有商品」——
          那句话在加载中是假的，而它与「真的一件都没有」长得一模一样。
        -->
        <sh-empty v-if="!goods.length" :pending="!goodsLoaded"
            :text="String($t('activityEdit.noGoods'))"></sh-empty>
      </template>

      <!-- 冲突提示：不阻止，但要在保存前说出来 -->
      <view v-if="conflicts.length" class="sh-notice sh-notice--warning conflict">
        <text v-for="c in conflicts" :key="c.activityNo + c.goodsNo" class="txt-caption conflict__l">
          {{ $t("activityEdit.conflict", { g: c.goodsNo, name: c.activityName }) }}
        </text>
        <text class="txt-caption sh-muted conflict__h">{{ $t("activityEdit.conflictHint") }}</text>
      </view>
    </view>

    <!--
      受众折在优惠之后，**不再单独占一步**：多数活动不挑人，
      单独一步会让每建一个活动都多点一次「所有人」。
    -->
    <view v-if="step === 3" class="sh-card sh-mt-sm">
      <text class="field__label">{{ $t("activityEdit.audienceQ") }}</text>
      <view class="opts">
        <sh-option
          v-for="a in ['', 'NON_MEMBER', 'LEVEL:SLEEPING', 'LEVEL:LOYAL']"
          :key="a || 'all'"
          :selected="form.audienceType === a"
          @tap="form.audienceType = a"
        >
          <text class="txt-strong opt__t">{{ $t(`activityEdit.audience.${a || "ALL"}`) }}</text>
          <text class="txt-caption sh-muted opt__d">{{ $t(`activityEdit.audienceHint.${a || "ALL"}`) }}</text>
        </sh-option>
      </view>
    </view>


    <!-- ③ 什么时候有效 -->
    <!-- ② 什么时候 —— 提到第 2 位：他先说出口的是「这周六到下周日」 -->
    <view v-if="step === 2" class="sh-card">
      <text class="field__label">{{ $t("activityEdit.scheduleQ") }}</text>
      <view class="chips sh-wrap">
        <text
          v-for="s in ['ONE_OFF', 'ALWAYS_ON', 'RECURRING']"
          :key="s"
          class="sh-chip"
          :class="{ 'sh-chip--primary': form.scheduleType === s }"
          @tap="form.scheduleType = s"
        >{{ $t(`activityEdit.schedule.${s}`) }}</text>
      </view>

      <!--
        ★ **给两个日期，不让他算天数**（2026-09-18）。
        店主说的是「这周六到下周日」；改之前这里问的是「限时几天」，
        他得自己把日期换算成天数，而那个数存下来之后谁也看不出原本是哪两天。
      -->
      <template v-if="form.scheduleType === 'ONE_OFF'">
        <sh-kv between :label="String($t('activityEdit.startDay'))">
          <picker mode="date" :value="form.startDay" @change="form.startDay = $event.detail.value">
            <view class="day sh-row">
              <text class="txt-body sh-num">{{ form.startDay }}</text>
              <text class="sh-muted">›</text>
            </view>
          </picker>
        </sh-kv>
        <sh-kv between :label="String($t('activityEdit.endDay'))">
          <picker mode="date" :value="form.endDay" :start="form.startDay"
                  @change="form.endDay = $event.detail.value">
            <view class="day sh-row">
              <text class="txt-body sh-num">{{ form.endDay }}</text>
              <text class="sh-muted">›</text>
            </view>
          </picker>
        </sh-kv>
      </template>

      <template v-if="form.scheduleType === 'RECURRING'">
        <view class="week sh-mt-sm sh-wrap">
          <text
            v-for="d in [1, 2, 3, 4, 5, 6, 7]"
            :key="d"
            class="sh-chip"
            :class="{ 'sh-chip--primary': form.weekdays.includes(d) }"
            @tap="toggleWeekday(d)"
          >{{ $t(`activities.weekday.${d}`) }}</text>
        </view>
        <view class="sh-row sh-mt-xs">
          <text class="txt-sub row__label">{{ $t("activityEdit.timeRange") }}</text>
          <input maxlength="5" v-model="form.from" class="field__input row__input" placeholder="08:00" />
          <input maxlength="5" v-model="form.to" class="field__input row__input" placeholder="20:00" />
        </view>
        <text class="sh-muted sh-hint">{{ $t("activityEdit.recurringHint") }}</text>
      </template>

      <view class="sh-row sh-mt-sm sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("activityEdit.quota") }}</text>
        <input maxlength="6" v-model="form.quota" class="field__input row__input" type="number" />
      </view>
      <view class="sh-row sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("activityEdit.budget") }}</text>
        <input maxlength="10" v-model="form.budget" class="field__input row__input" type="digit"
               :placeholder="$t('activityEdit.budgetPh')" />
      </view>

      <view v-if="exposure > 0" class="txt-strong sh-notice exposure">
        {{ $t("activityEdit.exposure", { n: money(exposure) }) }}
      </view>
      <text v-if="alwaysOnUncapped" class="txt-caption bad">{{ $t("activityEdit.alwaysOnNeedsCap") }}</text>
    </view>

    <!-- ④ 给谁 -->
    <!--
      效果与停用：**只有改既有活动时才有**。新建时这三个数都是 0、也没得停，
      画出来只是让新建流程多一屏要跳过的东西。
    -->
    <template v-if="current">
      <view class="sh-card sh-mt-sm">
        <view class="effect"><sh-stat
          :items="[
            { value: current.quotaUsed, label: String($t('activities.used')) },
            { value: money(current.budgetUsedMinor), label: String($t('activities.spent')) },
            { value: current.quotaLeft == null ? String($t('activities.unlimited')) : current.quotaLeft,
              label: String($t('activities.left')),
              tone: (current.quotaLeft ?? 99) <= 10 ? 'warn' : undefined },
          ]"
        ></sh-stat></view>
      </view>

      <!--
        两枚按钮形态，不是两行字（店主提过「按钮不要纯文字」）。
        结束用危险态：它不可逆，与暂停不是一类动作。
      -->
      <view v-if="current.status !== 'ENDED'" class="acts sh-row sh-mt-sm">
        <view class="sh-btn sh-btn--soft sh-fill" :class="{ 'is-disabled': busy }"
              @tap="setStatus(current.status === 'RUNNING' ? 'PAUSED' : 'RUNNING')">
          {{ current.status === "RUNNING" ? $t("activities.pause") : $t("activities.resume") }}
        </view>
        <view class="sh-btn sh-btn--danger sh-fill" :class="{ 'is-disabled': busy }"
              @tap="setStatus('ENDED')">{{ $t("activities.end") }}</view>
      </view>
    </template>

    <view class="nav">
      <text v-if="step > 1" class="sh-btn sh-btn--soft nav__b" @tap="step -= 1">
        {{ $t("activityEdit.prev") }}
      </text>
      <view v-if="step < 3" class="sh-btn nav__b" @tap="step += 1">
        {{ $t("activityEdit.next") }}
      </view>
      <view v-else class="sh-btn nav__b" :class="{ 'is-disabled': saving }" @tap="save">
        {{ $t("activityEdit.save") }}
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
/* 两枚并排。**不自己写纵向 margin** —— 顶层块的块间距归外壳管，
   自己写就压过那条，这一页的间距从此和别处不一样 */
.acts {
  gap: 16rpx;
}
.steps {
  display: block;
}
.opts {
  margin-top: 12rpx;
}
.opt__t {
  display: block;
}
.opt__d {
  display: block;
  margin-top: 8rpx;
}
.chips {
  margin-top: 12rpx;
}
.week {
  gap: 8rpx;
}

.row__label {
  width: 200rpx;
}
.row__input {
  flex: 1;
}

.conflict {
  margin-top: 16rpx;
}
.conflict__l {
  display: block;
}
.conflict__h {
  display: block;
  margin-top: 8rpx;
}
.exposure {
  margin-top: 16rpx;
}
.bad {
  display: block;
  margin-top: 8rpx;
  color: var(--sh-danger);
}
.nav {
  display: flex;
  gap: 16rpx;
}
.nav__b {
  flex: 1;
}
</style>
