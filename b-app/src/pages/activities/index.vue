<script setup lang="ts">
// 活动列表（P5 新模型）。**四分组，不是一张平铺的列表**：
//
//   在跑 / 没在跑（周期活动不在时段里）/ 已暂停 / 已结束
//
// 关键是把「在跑」和「现在真的在减」分开。周期活动在非时段里 status 仍是 RUNNING，
// 而商家问的是「顾客现在下单减不减」—— 平铺列表回答不了这个问题，
// 他只能自己去看今天周几、现在几点。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { StoreActivity } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const list = ref<StoreActivity[]>([]);
const includeEnded = ref(true);
const busy = ref(false);

const live = computed(() => list.value.filter((a) => a.status === "RUNNING" && a.liveNow));
const idle = computed(() => list.value.filter((a) => a.status === "RUNNING" && !a.liveNow));
const paused = computed(() => list.value.filter((a) => a.status === "PAUSED"));
const ended = computed(() => list.value.filter((a) => a.status === "ENDED"));

async function load() {
  list.value = await api.mActivities(includeEnded.value).catch(() => []);
}

async function run(fn: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true;
  try {
    await fn();
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/** 一句话说清这个活动做什么：满 X 减 Y / 特价 X / 买 N 送 M / 送券 */
function ruleText(a: StoreActivity) {
  if (a.benefitType === "CUT") {
    return t("activities.ruleCut", {
      n: money(a.triggerAmountMinor ?? 0), m: money(a.benefitAmountMinor ?? 0),
    });
  }
  if (a.benefitType === "PRICE") {
    return t("activities.rulePrice", { n: money(a.benefitAmountMinor ?? 0) });
  }
  if (a.benefitType === "GIFT") {
    return t("activities.ruleGift", { n: a.triggerQty ?? 0, m: a.benefitQty ?? 0 });
  }
  return t("activities.ruleCoupon");
}

/** 排期一句话。周期活动要把规则翻成人话，JSON 摆在商家面前等于没写 */
function scheduleText(a: StoreActivity) {
  if (a.scheduleType === "ALWAYS_ON") return t("activities.always");
  if (a.scheduleType === "RECURRING") {
    try {
      const r = JSON.parse(a.scheduleRule || "{}") as {
        weekdays?: number[]; from?: string; to?: string;
      };
      const days = (r.weekdays ?? []).map((d) => t(`activities.weekday.${d}`)).join("、");
      return t("activities.recurring", {
        d: days || String(t("activities.everyday")), f: r.from ?? "00:00", e: r.to ?? "24:00",
      });
    } catch {
      return t("activities.recurringBad");
    }
  }
  return t("activities.oneOff");
}

function go(url: string) {
  uni.navigateTo({ url });
}

function toggle(a: StoreActivity) {
  run(() => api.mSetActivityStatus(a.activityNo, a.status === "RUNNING" ? "PAUSED" : "RUNNING"));
}

/** 结束不可逆：确认框里要说清「不能再打开」，而不是只问「确定吗」 */
function end(a: StoreActivity) {
  uni.showModal({
    title: t("activities.endTitle", { name: a.name }),
    content: t("activities.endBody"),
    success: (r) => {
      if (r.confirm) run(() => api.mSetActivityStatus(a.activityNo, "ENDED"));
    },
  });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="activities.title" :denied="!merchant.can('biz:campaign')">
    <view class="bar">
      <text class="sh-chip sh-chip--primary" @tap="go('/pages/activity-edit/index')">
        ＋ {{ $t("activities.new") }}
      </text>
    </view>

    <sh-empty v-if="!list.length" :text="String($t('activities.empty'))"></sh-empty>

    <template v-for="g in [
      { key: 'live', rows: live },
      { key: 'idle', rows: idle },
      { key: 'paused', rows: paused },
      { key: 'ended', rows: ended },
    ]" :key="g.key">
      <view v-if="g.rows.length" class="group">
        <text class="group__t">{{ $t(`activities.group.${g.key}`, { n: g.rows.length }) }}</text>
        <text v-if="g.key === 'idle'" class="sh-muted group__d">
          {{ $t("activities.idleHint") }}
        </text>

        <view v-for="a in g.rows" :key="a.activityNo" class="sh-card item">
          <view class="item__head">
            <text class="item__name">{{ a.name }}</text>
            <text v-if="a.endedReason" class="sh-chip">
              {{ $t(`activities.endedReason.${a.endedReason}`) }}
            </text>
          </view>
          <text class="rule">{{ ruleText(a) }}</text>
          <text class="sh-muted line">{{ scheduleText(a) }}</text>

          <!--
            效果卡：**用掉多少、花了多少、还剩多少**。
            没有转化率、没有 UV —— 商家在这一页要决定的只有「要不要接着跑」。
          -->
          <sh-stat
            class="effect"
            :items="[
              { value: a.quotaUsed, label: String($t('activities.used')) },
              { value: money(a.budgetUsedMinor), label: String($t('activities.spent')) },
              { value: a.quotaLeft == null ? String($t('activities.unlimited')) : a.quotaLeft,
                label: String($t('activities.left')),
                tone: (a.quotaLeft ?? 99) <= 10 ? 'warn' : undefined },
            ]"
          ></sh-stat>

          <view v-if="a.status !== 'ENDED'" class="acts">
            <text class="sh-link" @tap="go(`/pages/activity-edit/index?activityNo=${a.activityNo}`)">
              {{ $t("activities.edit") }}
            </text>
            <text class="sh-link" @tap="toggle(a)">
              {{ a.status === "RUNNING" ? $t("activities.pause") : $t("activities.resume") }}
            </text>
            <text class="sh-link" @tap="end(a)">{{ $t("activities.end") }}</text>
          </view>
        </view>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.bar {
  display: flex;
  gap: 12rpx;
  margin-bottom: 12rpx;
}
.group {
  margin-top: 20rpx;
}
.group__t {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-sub);
}
.group__d {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  line-height: 1.5;
}
.item {
  margin-top: 12rpx;
}
.item__head {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.item__name {
  font-size: 30rpx;
  font-weight: 600;
}
.rule {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: var(--sh-primary-text);
}
.line {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
}
.acts {
  display: flex;
  gap: 24rpx;
  margin-top: 16rpx;
}
/* 效果数据与上面的活动信息之间的分隔线。**只留版面，不留样式** ——
   数字与标签的档位归 sh-stat，这里只说明「它和上面是两段」 */
.effect {
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 2rpx solid var(--sh-faint);
}
</style>
