<script setup lang="ts">
/**
 * 跨店库存总览（INV-S7）—— **一件货一行，把它在各店的分布收在一起**。
 *
 * <h2>它替商家做的那一步合并</h2>
 *
 * 库存页那一屏是「一个库位」的余额；多门店商家在那儿看到的是同一件货重复
 * N 行，「哪家店断了」得自己在脑子里合并。这一页替他合并 —— 一行一件货，
 * 右边直接写着断了几家店。
 *
 * <h2>默认只给缺货的</h2>
 *
 * 这一屏的用途是补货。全给的话，两百多件里有两百件是「都还有」，
 * 那几件真断了的反而看不见了。「全部」在页签上，想看随时切。
 *
 * <h2>为什么按「断了几家店」排而不是按缺口大小</h2>
 *
 * 五家店断了三家的那件货，比某一家店少两袋更值得先看见 —— 前者是
 * 「这件货要补一批」，后者是「顺手补一下」。同样断几家时才比可用量。
 *
 * <h2>入口只给多门店商家</h2>
 *
 * 单店商家的「跨店」就是库存页本身，多一个入口只是多一处要维护的重复。
 * 判据用 `multiStore`（有没有多个门店）而**不是** `crossStoreStats` 那个
 * 付费能力位 —— 分层整体还没做（§八：32 个商家端点今天对所有商家免费开着），
 * 单独给这一个功能加门槛，会出现「只有跨店总览要钱」的怪状态。
 * 等分层做的时候一起接，接线成本不高。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import type { StockCrossStoreRow } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const TABS = [
  { key: "shortage", label: () => t("stockCross.tabShortage") },
  { key: "all", label: () => t("stockCross.tabAll") },
] as const;

const tab = ref<(typeof TABS)[number]["key"]>("shortage");
const rows = ref<StockCrossStoreRow[]>([]);
const loading = ref(false);
/** 展开的那一行。**一次只展开一个** —— 全展开之后这一屏就退回成了原来那张长列表 */
const openedId = ref("");

async function load() {
  loading.value = true;
  try {
    rows.value = await api.mStockCrossStore({ filter: tab.value, size: 50 });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

function switchTab(key: (typeof TABS)[number]["key"]) {
  if (tab.value === key) return;
  tab.value = key;
  openedId.value = "";
  void load();
}

function toggle(itemId: string) {
  openedId.value = openedId.value === itemId ? "" : itemId;
}

/** 回边：从一行走到那件货的明细（流水、改数都在那儿） */
function openDetail(r: StockCrossStoreRow) {
  uni.navigateTo({ url: `${ROUTES.stockDetail}?itemId=${encodeURIComponent(r.itemId)}` });
}

/** 断了几家店那句话。0 家时不出现 —— 「断了 0 家店」是噪声 */
function shortageText(r: StockCrossStoreRow): string {
  return String(t("stockCross.shortageN", { n: r.shortageLocations }));
}

const empty = computed(() => !loading.value && rows.value.length === 0);

onShow(load);
</script>

<template>
  <sh-scaffold title-key="stockCross.title" :denied="!merchant.can('biz:stock')">
    <!--
      页签只有两个，用 sh-chip 而不是 tab 条：两个选项的 tab 条在视觉上
      像个半成品，而这两个是「默认视图」与「看全部」的关系，不是并列的两屏。
    -->
    <view class="sh-row tabs">
      <text
        v-for="x in TABS"
        :key="x.key"
        class="sh-chip"
        :class="{ 'sh-chip--primary': tab === x.key }"
        @tap="switchTab(x.key)"
      >{{ x.label() }}</text>
    </view>

    <text class="sh-hint hint">{{ $t("stockCross.hint") }}</text>

    <sh-empty v-if="empty" :text="String($t(tab === 'shortage' ? 'stockCross.emptyOk' : 'stockCross.empty'))"></sh-empty>

    <view v-for="r in rows" :key="r.itemId" class="sh-card sh-mb-sm">
      <view class="row sh-row sh-row--between sh-row--baseline" @tap="toggle(r.itemId)">
        <!--
          名字与徽章都是 inline 的 text，中间不会自己长出间距 —— 第一版就是
          「小米 · 2斤装3 家店缺货」贴成一句话。用 flex + gap，不靠 margin：
          徽章不出现时（不缺货）也不会留下一段空白。
        -->
        <view class="sh-fill sh-row name">
          <text class="txt-strong">{{ r.name }}{{ r.specText ? ` · ${r.specText}` : "" }}</text>
          <!-- 断了几家店：这一屏唯一要一眼看到的数 -->
          <text v-if="r.shortageLocations > 0" class="sh-chip sh-chip--danger">
            {{ shortageText(r) }}
          </text>
        </view>
        <text class="txt-price sh-num">{{ r.available }}</text>
      </view>

      <!-- 展开：各店分布。数据随列表一起来的，展开不发请求 -->
      <template v-if="openedId === r.itemId">
        <view v-for="l in r.byLocation" :key="l.locationId" class="loc sh-row sh-row--between">
          <text class="sh-muted">{{ l.locationName }}</text>
          <text class="sh-num" :class="{ 'is-danger': l.onHand <= 0 }">{{ l.onHand }}</text>
        </view>
        <text class="sh-link detail" @tap="openDetail(r)">{{ $t("stockCross.openDetail") }}</text>
      </template>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.tabs {
  gap: 12rpx;
  padding-bottom: 12rpx;
}
.hint {
  display: block;
  padding-bottom: 16rpx;
}
.row {
  padding: 4rpx 0;
}
.name {
  gap: 12rpx;
  flex-wrap: wrap;
}
.loc {
  padding: 12rpx 0;
  border-top: var(--sh-hairline-soft);
}
.detail {
  display: block;
  text-align: end;
  padding-top: 12rpx;
}
</style>
