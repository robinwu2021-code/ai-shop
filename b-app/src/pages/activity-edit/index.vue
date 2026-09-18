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
import { useMerchantStore } from "@/stores/merchant";
import { money, toMinor } from "@shared/utils/money";
import type { ActivityConflict, Goods, StoreActivityDraft } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const step = ref(1);
const activityNo = ref("");
const saving = ref(false);
const conflicts = ref<ActivityConflict[]>([]);

const form = ref({
  goal: "BASKET",
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
  days: "7",
  weekdays: [] as number[],
  from: "08:00",
  to: "20:00",
  quota: "100",
  budget: "",
  audienceType: "",
  /** 成团人数。**下限 2** —— 1 个人不叫团，后端也拒 */
  groupN: "2",
});

/** 目标 → 后面几步的默认值。商家想的是「拉新」，不是「触发条件 = 满额」 */
const GOALS = [
  { key: "ACQUIRE", benefit: "CUT", audience: "NON_MEMBER" },
  { key: "WAKEUP", benefit: "CUT", audience: "LEVEL:SLEEPING" },
  { key: "CLEAR", benefit: "PRICE", audience: "" },
  { key: "BASKET", benefit: "CUT", audience: "" },
  /*
   * ★ **团购**（2026-09-18）：价格与人数此前长在商品上，一件货一辈子只有一个
   * 团购价。挪进活动之后它才可能在不同时间参加不同的团。
   *
   * 与「清库存」都是 PRICE，**目标才是分水岭** —— 所以下面凡是判团购的地方
   * 一律看 `goal === "GROUP"`，不看 benefitType。
   */
  { key: "GROUP", benefit: "PRICE", audience: "" },
];

/** 是不是团购活动。判的是目标，不是优惠类型 —— 清库存也是 PRICE */
const isGroup = computed(() => form.value.goal === "GROUP");

function pickGoal(key: string) {
  const g = GOALS.find((x) => x.key === key)!;
  form.value.goal = key;
  form.value.benefitType = g.benefit;
  form.value.audienceType = g.audience;
  if (!form.value.name) form.value.name = String(t(`activityEdit.goalName.${key}`));
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
    failed.value = false;
  } catch {
    failed.value = true;
    return;
  }
  activityNo.value = a.activityNo;
  form.value.goal = a.goal ?? "BASKET";
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
    goal: form.value.goal,
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
    startAt: form.value.scheduleType === "ONE_OFF" ? now : null,
    endAt: form.value.scheduleType === "ONE_OFF"
      ? now + Number(form.value.days || 7) * 86400_000 : null,
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
  <sh-scaffold title-key="activityEdit.title" :denied="!merchant.can('biz:campaign')"
    :failed="failed"
    @retry="() => loadExisting(currentNo)"
  >
    <!-- 四步的进度：让他知道还剩几步，而不是面对一屏输入框 -->
    <view class="steps sh-wrap">
      <text
        v-for="s in [1, 2, 3, 4]"
        :key="s"
        class="sh-chip steps__i"
        :class="{ 'sh-chip--solid': step === s, 'is-done txt-primary': step > s }"
        @tap="step = s"
      >{{ s }}. {{ $t(`activityEdit.step${s}`) }}</text>
    </view>

    <!-- ① 想干什么 -->
    <view v-if="step === 1" class="sh-card">
      <text class="field__label">{{ $t("activityEdit.goalQ") }}</text>
      <view class="opts">
        <sh-option
          v-for="g in GOALS"
          :key="g.key"
          :selected="form.goal === g.key"
          @tap="pickGoal(g.key)"
        >
          <text class="txt-strong opt__t">{{ $t(`activityEdit.goal.${g.key}`) }}</text>
          <text class="txt-caption sh-muted opt__d">{{ $t(`activityEdit.goalHint.${g.key}`) }}</text>
        </sh-option>
      </view>
      <view class="sh-row sh-mt-sm sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("activityEdit.name") }}</text>
        <input maxlength="64" v-model="form.name" class="field__input row__input"
               :placeholder="$t('activityEdit.namePh')" />
      </view>
    </view>

    <!-- ② 优惠什么样 -->
    <view v-if="step === 2" class="sh-card">
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

    <!-- ③ 什么时候有效 -->
    <view v-if="step === 3" class="sh-card">
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

      <view v-if="form.scheduleType === 'ONE_OFF'" class="sh-row sh-mt-sm sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("activityEdit.days") }}</text>
        <input maxlength="4" v-model="form.days" class="field__input row__input" type="number" />
      </view>

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
    <view v-if="step === 4" class="sh-card">
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

    <view class="nav">
      <text v-if="step > 1" class="sh-btn sh-btn--soft nav__b" @tap="step -= 1">
        {{ $t("activityEdit.prev") }}
      </text>
      <view v-if="step < 4" class="sh-btn nav__b" @tap="step += 1">
        {{ $t("activityEdit.next") }}
      </view>
      <view v-else class="sh-btn nav__b" :class="{ 'is-disabled': saving }" @tap="save">
        {{ $t("activityEdit.save") }}
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.steps {
  gap: 8rpx;
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
