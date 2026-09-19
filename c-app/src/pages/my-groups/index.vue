<script setup lang="ts">
/*
 * 我的拼团（原型 p12 · TDD-C端拼团买家流程）。
 *
 * 参过的团此前没有集中看的地方：只能回商品页找、或翻订单。这里按状态三栏 ——
 * 拼团中 / 已成团 / 没凑齐。拼团中的点进团页就是「邀请」那一屏。
 * 「我发起的团」（邻里自提的签收、核销）收进页底一行，不再单独占「我的」一行。
 */
import { computed, onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { ROUTES } from "@shared/utils/constants";
import type { GroupBuy } from "@shared/types";

const { t } = useI18n();
const user = useUserStore();

type Tab = "open" | "formed" | "failed";
const tab = ref<Tab>("open");
const groups = ref<GroupBuy[]>([]);
const loaded = ref(false);
const failed = ref(false);
const now = ref(Date.now());
const tick = setInterval(() => (now.value = Date.now()), 1000);
onUnmounted(() => clearInterval(tick));

/** 过了截止还没结算的团按「没凑齐」算 —— 定时任务收尾前的那几分钟，不该还挂在「拼团中」 */
function tabOf(g: GroupBuy): Tab {
  if (g.status === "FORMED") return "formed";
  if (g.status === "OPEN" && g.expireAt > now.value) return "open";
  return "failed";
}

const byTab = computed(() => groups.value.filter((g) => tabOf(g) === tab.value));
const count = (k: Tab) => groups.value.filter((g) => tabOf(g) === k).length;
const tabs = computed(() => [
  { key: "open", label: String(t("myGroups.open", { n: count("open") })) },
  { key: "formed", label: String(t("myGroups.formed", { n: count("formed") })) },
  { key: "failed", label: String(t("myGroups.failed", { n: count("failed") })) },
]);

async function load() {
  if (!user.isLogin) await user.silentLogin().catch(() => {});
  if (!user.isLogin) {
    uni.navigateTo({ url: ROUTES.login });
    return;
  }
  try {
    groups.value = await api.myJoinedGroups();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

function openGroup(g: GroupBuy) {
  uni.navigateTo({ url: `${ROUTES.group}?groupNo=${g.groupNo}` });
}

function gotoHosted() {
  uni.navigateTo({ url: ROUTES.groupHost });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="myGroups.title">
    <sh-tabs :items="tabs" :active="tab" @change="(k: string) => (tab = k as Tab)"></sh-tabs>

    <view class="sh-block">
      <biz-group-card v-for="g in byTab" :key="g.groupNo" :group="g" :now="now" @tap="openGroup(g)"></biz-group-card>
      <sh-empty
        bare
        v-if="!byTab.length"
        :pending="!loaded"
        :failed="failed"
        @retry="load"
        :text="$t('myGroups.empty')"
      ></sh-empty>
    </view>

    <!-- 邻里自提的发起人侧（签收、核销）：低频，收在页底一行 -->
    <view class="sh-cells">
      <view class="sh-cell sh-row sh-row--between" @tap="gotoHosted">
        <text class="txt-body">{{ $t("groupHost.title") }}</text>
        <text class="txt-caption">{{ $t("groupHost.entryHint") }}</text>
      </view>
    </view>
  </sh-scaffold>
</template>
