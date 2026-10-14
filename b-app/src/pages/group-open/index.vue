<script setup lang="ts">
/*
 * 商家开团（原型 s34）。**只选三样**：哪个活动、哪件货、哪个自提点；
 * 人数、成团价、时限从活动带出来，只读 —— 开团这一步不能临时定价，
 * 否则同一件货会有两个价，而买家已经看到过另一个。
 *
 * 活动只列「进行中的拼团活动」：草稿与已结束的开不出团（后端会拒），列出来只会让人点一下再被拒。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { GroupPickupOption, StoreActivity } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const acts = ref<StoreActivity[]>([]);
const goodsTitle = ref<Record<string, string>>({});
const pickups = ref<GroupPickupOption[]>([]);
const loaded = ref(false);
const failed = ref(false);
const busy = ref(false);

const activityNo = ref("");
const goodsNo = ref("");
const pickupNo = ref("");
const sheet = ref<"" | "activity" | "goods" | "pickup">("");

async function load() {
  try {
    const [all, goods, pts] = await Promise.all([
      api.mActivities(), api.mGoodsList({ size: 100 }), api.mGroupPickups(),
    ]);
    acts.value = all.filter((a) => a.triggerType === "GROUP" && a.status === "RUNNING");
    goodsTitle.value = Object.fromEntries(goods.records.map((g) => [g.goodsNo, g.title]));
    pickups.value = pts;
    // 只有一个可选时直接替他选上 —— 让人去点一个只有一项的列表是多余的一步
    if (!activityNo.value && acts.value.length === 1) activityNo.value = acts.value[0]!.activityNo;
    if (!pickupNo.value && pts.length === 1) pickupNo.value = pts[0]!.pickupNo;
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

const act = computed(() => acts.value.find((a) => a.activityNo === activityNo.value) ?? null);
const goodsOfAct = computed(() => act.value?.goodsNos ?? []);
const pickup = computed(() => pickups.value.find((p) => p.pickupNo === pickupNo.value) ?? null);
const ready = computed(() => !!act.value && !!goodsNo.value);

function pickActivity(no: string) {
  activityNo.value = no;
  // 换了活动，原来选的货可能不在新活动里
  if (!goodsOfAct.value.includes(goodsNo.value)) goodsNo.value = goodsOfAct.value.length === 1 ? goodsOfAct.value[0]! : "";
  sheet.value = "";
}

async function submit() {
  if (!ready.value || busy.value) return;
  busy.value = true;
  try {
    const g = await api.mCreateGroup({
      goodsNo: goodsNo.value,
      activityNo: activityNo.value,
      pickupNo: pickupNo.value || undefined,
    });
    uni.redirectTo({ url: `${ROUTES.group}?groupNo=${g.groupNo}` });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function back() {
  uni.navigateBack();
}

onLoad((q) => {
  activityNo.value = String(q?.activityNo ?? "");
  void load();
});
</script>

<template>
  <sh-scaffold title-key="groupOpen.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <view class="sh-cells">
      <view class="sh-cell sh-row sh-row--between" @tap="sheet = 'activity'">
        <text class="txt-body sh-muted">{{ $t("groupOpen.activity") }}</text>
        <view class="sh-row">
          <text class="txt-body" :class="{ 'sh-muted': !act }">{{ act?.name || $t("groupOpen.pick") }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="act && (sheet = 'goods')">
        <text class="txt-body sh-muted">{{ $t("groupOpen.goods") }}</text>
        <view class="sh-row">
          <text class="txt-body" :class="{ 'sh-muted': !goodsNo }">
            {{ goodsNo ? goodsTitle[goodsNo] || goodsNo : $t("groupOpen.pick") }}
          </text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="sheet = 'pickup'">
        <text class="txt-body sh-muted">{{ $t("groupOpen.pickup") }}</text>
        <view class="sh-row">
          <text class="txt-body" :class="{ 'sh-muted': !pickup }">{{ pickup?.name || $t("groupOpen.anyPickup") }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
    </view>

    <template v-if="act">
      <text class="txt-caption sh-muted grp">{{ $t("groupOpen.byRule") }}</text>
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("groupOpen.rule") }}</text>
          <text class="txt-body sh-num">
            {{ $t("groupOpen.ruleValue", { n: act.triggerQty ?? 2, p: money(act.benefitAmountMinor ?? 0) }) }}
          </text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("groupOpen.deadline") }}</text>
          <text class="txt-body sh-num">{{ $t("groupOpen.deadlineValue", { h: act.groupHours || 24 }) }}</text>
        </view>
      </view>
    </template>

    <sh-empty
      v-if="loaded && !acts.length"
      :text="String($t('groupOpen.noActivity'))"
      :tip="String($t('groupOpen.noActivityTip'))"
    ></sh-empty>

    <sh-actionbar>
      <view class="sh-row bar">
        <view class="sh-btn sh-btn--muted sh-fill" @tap="back">{{ $t("groupOpen.cancel") }}</view>
        <view class="sh-btn bar__main" :class="{ 'is-disabled': !ready || busy }" @tap="submit">
          {{ $t("groupOpen.submit") }}
        </view>
      </view>
    </sh-actionbar>

    <sh-sheet :visible="sheet === 'activity'" :title="String($t('groupOpen.activity'))" @close="sheet = ''">
      <view class="sh-cells">
        <view v-for="a in acts" :key="a.activityNo" class="sh-cell sh-row sh-row--between" @tap="pickActivity(a.activityNo)">
          <text class="txt-body" :class="{ 'txt-primary': a.activityNo === activityNo }">{{ a.name }}</text>
          <sh-icon v-if="a.activityNo === activityNo" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
      </view>
    </sh-sheet>

    <sh-sheet :visible="sheet === 'goods'" :title="String($t('groupOpen.goods'))" @close="sheet = ''">
      <view class="sh-cells">
        <view v-for="no in goodsOfAct" :key="no" class="sh-cell sh-row sh-row--between"
              @tap="goodsNo = no; sheet = ''">
          <text class="txt-body" :class="{ 'txt-primary': no === goodsNo }">{{ goodsTitle[no] || no }}</text>
          <sh-icon v-if="no === goodsNo" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
      </view>
    </sh-sheet>

    <sh-sheet :visible="sheet === 'pickup'" :title="String($t('groupOpen.pickup'))" @close="sheet = ''">
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between" @tap="pickupNo = ''; sheet = ''">
          <text class="txt-body" :class="{ 'txt-primary': !pickupNo }">{{ $t("groupOpen.anyPickup") }}</text>
          <sh-icon v-if="!pickupNo" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
        <view v-for="p in pickups" :key="p.pickupNo" class="sh-cell sh-row sh-row--between"
              @tap="pickupNo = p.pickupNo; sheet = ''">
          <text class="txt-body" :class="{ 'txt-primary': p.pickupNo === pickupNo }">{{ p.name }}</text>
          <sh-icon v-if="p.pickupNo === pickupNo" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
      </view>
    </sh-sheet>
  </sh-scaffold>
</template>

<style scoped>
.grp {
  display: block;
  padding: 0 8rpx;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
