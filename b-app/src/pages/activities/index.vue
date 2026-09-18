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

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」 */
const failed = ref(false);

async function load() {
  try {
    list.value = await api.mActivities(includeEnded.value);
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}


/** 一句话说清这个活动做什么：满 X 减 Y / 特价 X / 买 N 送 M / 送券 */
function ruleText(a: StoreActivity) {
  if (a.benefitType === "CUT") {
    return t("activities.ruleCut", {
      n: money(a.triggerAmountMinor ?? 0), m: money(a.benefitAmountMinor ?? 0),
    });
  }
  /*
   * 团购与清库存**都是 PRICE**，靠 benefitType 分不开 —— 分水岭是触发。
   * 不分的话列表上一条团购写着「特价 ¥8.80」，而商家找的是「几人成团」。
   */
  if (a.triggerType === "GROUP") {
    return t("activities.ruleGroup", {
      n: a.triggerQty ?? 0, m: money(a.benefitAmountMinor ?? 0),
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



onShow(load);
</script>

<template>
  <sh-scaffold title-key="activities.title" :denied="!merchant.can('biz:campaign')">

    <sh-empty v-if="!list.length" :pending="!loaded" :failed="failed" @retry="load" :text="String($t('activities.empty'))" :tip="String($t('activities.emptyTip'))"></sh-empty>

    <template v-for="g in [
      { key: 'live', rows: live },
      { key: 'idle', rows: idle },
      { key: 'paused', rows: paused },
      { key: 'ended', rows: ended },
    ]" :key="g.key">
      <view v-if="g.rows.length" class="group">
        <text class="txt-strong group__t txt-quiet">{{ $t(`activities.group.${g.key}`, { n: g.rows.length }) }}</text>
        <text v-if="g.key === 'idle'" class="txt-caption sh-muted group__d">
          {{ $t("activities.idleHint") }}
        </text>

        <!--
          ★ **两行一条，整条可点**（2026-09-18）。

          改之前每条 177px：名称 / 规则 / 排期 / 三个 20px 大数 / 三个纯文字动作，
          四个活动占掉 947px ≈ 2.3 屏。而量到的硬毛病是那三个动作：
          **各 24×15px 的纯文字**，不到可点下限 44 的三分之一，
          既看不出能点、也点不中（店主提过「按钮不要纯文字」）。

          改法不是把按钮做大，是**取消按钮**：整条点进编辑页，
          暂停 / 结束在那儿做（那一页原来没有这两个动作，一并补上了 ——
          光从列表撤掉就是把能力弄丢）。

          列表只回答三件事：**有哪些、在不在跑、还剩多少**。
          「已用 / 已花」是复盘用的，收进编辑页 —— 商家看「这个花了多少」时是
          专门去看的，不是扫列表时顺带看的，留在这儿只让每条多占 55px。

          右上角一枚徽章同时说完**排期与状态**：两者从来不会同时需要
          （已结束的不必再说「每周三」）。
        -->
        <view
          v-for="a in g.rows"
          :key="a.activityNo"
          class="sh-card sh-mt-xs item"
          @tap="go(`/pages/activity-edit/index?activityNo=${a.activityNo}`)"
        >
          <view class="sh-row sh-row--between">
            <text class="txt-strong">{{ a.name }}</text>
            <!--
              **只用库里真有的修饰**：sh-chip--sm / --muted 都不存在，
              挂了等于没挂 —— 样式静默落空、页面照跑（ui-package 那道闸盯的就是它）。
              已结束的用 dashed：虚线本身就说「这条不再生效」，不必另造一个灰态。
            -->
            <text class="sh-chip" :class="{ 'sh-chip--dashed': a.status === 'ENDED' }">
              {{ a.endedReason
                ? $t(`activities.endedReason.${a.endedReason}`)
                : scheduleText(a) }}
            </text>
          </view>
          <view class="sh-row sh-row--between item__b">
            <!-- 与右边同一档字阶：层级交给颜色，不靠差一档字号（两端差一档在两个端上会差 1px） -->
            <text class="txt-sub sh-fill">{{ ruleText(a) }}</text>
            <!--
              「还剩」留在列表：它是唯一影响「要不要现在管它」的数。
              快见底时变色 —— 那一刻才是他需要动手的时候。
            -->
            <text
              v-if="a.quotaLeft != null && a.status !== 'ENDED'"
              class="txt-sub sh-num"
              :class="(a.quotaLeft ?? 99) <= 10 ? 'is-warning' : 'sh-muted'"
            >{{ $t("activities.leftN", { n: a.quotaLeft }) }}</text>
          </view>
        </view>
      </view>
    </template>

    <!--
      ★ **新建改成右下悬浮**（2026-09-18 店主：「新建活动的位置不对」）。

      原来是左上角一枚小 chip：它与下面的分组标题（「正在生效（2）」）挤在一起，
      读起来像是那一组的一部分；而它是全页唯一的主动作。

      用 sh-fab，与商品页的「＋ 新建商品」同一个件、同一个位置 ——
      两页的主动作长在同一处，不用每页重新找。
      不放导航栏右上：那在原生包里是系统导航栏，三端位置不一致。
    -->
    <sh-fab :text="`＋ ${$t('activities.new')}`" @tap="go('/pages/activity-edit/index')"></sh-fab>
  </sh-scaffold>
</template>

<style scoped>
/* 整条是可点目标（不是三个 24×15 的字），两行内容 + 卡内边距已过 88rpx */
.item {
  min-height: 88rpx;
}
/* 8rpx 而不是 4rpx：4rpx 在两端会差 1px（页面规范那道闸盯的就是它） */
.item__b {
  margin-top: 8rpx;
  gap: 16rpx;
}

.group__d {
  display: block;
  margin-top: 4rpx;
}

.item__head {
  gap: 12rpx;
}

.rule {
  display: block;
  margin-top: 8rpx;
  color: var(--sh-primary-text);
}
.line {
  display: block;
  margin-top: 4rpx;
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
  border-top: var(--sh-hairline-soft);
}
</style>
