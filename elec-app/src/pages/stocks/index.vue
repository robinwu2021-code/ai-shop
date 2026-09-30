<script setup lang="ts">
// 我的库存（原型 e17）。供应商自己看得到精确数量与批号；底栏一键续期。
//
// 库存会过期，是这门生意和普通商城最大的不同：一个月前的现货今天可能已经卖完，
// 买家询到不存在的货，平台的信誉当场就没了。但续期要一下就能做完，否则他会觉得是在给平台打工。
// 按钮写「这些还有货」而不是「续期」—— 他要确认的是事实，不是执行一个动作。
import { ref } from "vue";
import { onReachBottom, onShow } from "@dcloudio/uni-app";
import { api, errMsg, toast } from "@/api";
import { handleElecError } from "@/shared/errors";
import { ensureLogin } from "@/shared/auth";
import { COND, PACKING, daysLeft, dateOf, leadOf, priceOf, qtyOf } from "@/shared/format";
import type { ElecStock, ElecStockFilter } from "@shared/types";

const SIZE = 20;
const TABS: { key: ElecStockFilter; label: string }[] = [
  { key: "ALL", label: "在售" },
  { key: "EXPIRING", label: "将到期" },
  { key: "EXPIRED", label: "已下架" },
];

const filter = ref<ElecStockFilter>("ALL");
const keyword = ref("");
const list = ref<ElecStock[]>([]);
const page = ref(1);
const done = ref(false);
const loaded = ref(false);
const failed = ref("");
const busy = ref(false);

onShow(async () => {
  if (!(await ensureLogin())) return;
  await reload();
});
onReachBottom(() => void more());

async function reload() {
  page.value = 1;
  failed.value = "";
  try {
    list.value = await api.myStocks({ keyword: keyword.value.trim(), filter: filter.value, page: 1, size: SIZE });
    done.value = list.value.length < SIZE;
  } catch (e) {
    failed.value = errMsg(e);
  } finally {
    loaded.value = true;
  }
}

async function more() {
  if (done.value || !loaded.value) return;
  const next = await api.myStocks({ keyword: keyword.value.trim(), filter: filter.value, page: page.value + 1, size: SIZE })
    .catch(() => [] as ElecStock[]);
  page.value += 1;
  list.value = list.value.concat(next);
  done.value = next.length < SIZE;
}

function setFilter(k: string) {
  filter.value = k as ElecStockFilter;
  void reload();
}

function expiryText(s: ElecStock): string {
  const d = daysLeft(s.validUntil);
  if (s.status === "EXPIRED" || (d !== null && d < 0)) return `${dateOf(s.validUntil)} 已到期`;
  if (d === 0) return "今天到期";
  return d !== null && d <= 7 ? `${d} 天后到期` : `到 ${dateOf(s.validUntil)}`;
}

function expiryClass(s: ElecStock): string {
  const d = daysLeft(s.validUntil);
  if (s.status === "EXPIRED" || (d !== null && d < 0)) return "danger";
  return d !== null && d <= 7 ? "warn" : "sh-muted";
}

function meta(s: ElecStock): string {
  return [
    s.mfr, qtyOf(s.qty), s.dateCode ? `批号 ${s.dateCode}` : "", s.packageName,
    s.cond ? COND[s.cond] : "", s.packing ? PACKING[s.packing] : "",
    // 他自己的库存：没写交期就不提（「交期未说」是给买家看的提醒，这里只是噪音）
    s.leadDays == null ? "" : leadOf(s.leadDays),
  ].filter(Boolean).join(" · ");
}

async function renew() {
  if (busy.value) return;
  busy.value = true;
  try {
    const r = await api.renewStocks();
    toast(`已续 ${qtyOf(r.renewed)} 行，到 ${dateOf(r.validUntil)}`);
    await reload();
  } catch (e) {
    handleElecError(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="title.stocks" :failed="!!failed" :failed-text="failed" @retry="reload">
    <view class="sh-searchbox">
      <sh-icon name="search" :size="36" color="var(--sh-sub)"></sh-icon>
      <input v-model="keyword" class="grow txt-body" confirm-type="search" placeholder="搜料号" @confirm="reload" />
    </view>
    <!-- 类名别叫 tabs：scoped 样式会同时落到子组件根上，而 sh-tabs 的根就叫 .tabs -->
    <view class="tab-wrap">
      <sh-tabs :items="TABS" :active="filter" line @change="setFilter"></sh-tabs>
    </view>

    <sh-empty v-if="loaded && !list.length" text="这里没有库存"></sh-empty>
    <view class="sh-cells">
      <view v-for="s in list" :key="s.stockNo" class="sh-cell">
        <view class="sh-row sh-row--between">
          <text class="txt-strong sh-num mpn">{{ s.mpn }}</text>
          <text class="txt-caption" :class="expiryClass(s)">{{ expiryText(s) }}</text>
        </view>
        <text class="txt-caption sh-muted block">{{ meta(s) }}</text>
        <text class="txt-caption block">
          <text v-if="s.tiers.length > 1">{{ s.tiers.map((t) => `${qtyOf(t.minQty)}+ ${priceOf(t.priceE6, s.currency ?? "CNY")}`).join(" / ") }}</text>
          <text v-else-if="s.priceE6 != null">{{ priceOf(s.priceE6, s.currency ?? "CNY") }}</text>
          <text v-else class="sh-muted">没报价</text>
          {{ s.taxIncluded ? "含税" : "未税" }}<text v-if="s.moq"> · 起订 {{ qtyOf(s.moq) }}</text>
        </text>
      </view>
    </view>

    <sh-actionbar v-if="filter !== 'EXPIRED' && list.length">
      <view class="sh-btn" :class="{ 'is-disabled': busy }" @tap="renew">{{ busy ? "续期中…" : "这些还有货，续期" }}</view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.grow {
  flex: 1;
}
.tab-wrap {
  margin: 16rpx 0;
}
.mpn {
  flex: 1;
  min-width: 0;
  word-break: break-all;
}
.block {
  display: block;
  margin-top: 6rpx;
}
.warn {
  color: var(--sh-warning);
}
.danger {
  color: var(--sh-danger);
}
</style>
