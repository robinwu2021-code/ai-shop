<script setup lang="ts">
// 批量查（原型 e06）。粘一列料号（Excel 里整列复制过来就是这个形状），逐行告诉你库里有没有，
// 勾上的一起询价。数量能从同一行里读出来。
// 同一料号多家厂牌时**不猜**，标出来让他点 —— 猜错了报价整单作废。
// 写的是参数不是料号（「0805 10K 1%」）也不拦：照原文询价，让他改成标准料号他就不询了。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api, errMsg, toast } from "@/api";
import { ROUTES, go } from "@/shared/routes";
import { setRfqDraft } from "@/shared/draft";
import { QTY_BAND, leadOf, priceOf, qtyOf } from "@/shared/format";
import type { ElecLookupLine } from "@shared/types";

/** 后端上限 50 行 */
const MAX_LINES = 50;

const text = ref("");
const lines = ref<ElecLookupLine[]>([]);
const picked = ref<boolean[]>([]);
const busy = ref(false);

onLoad((q) => {
  if (q?.text) text.value = decodeURIComponent(String(q.text));
});

const inputCount = computed(() => text.value.split(/\r?\n/).filter((l) => l.trim()).length);

async function check() {
  if (!inputCount.value || busy.value) return;
  if (inputCount.value > MAX_LINES) {
    toast(`一次最多 ${MAX_LINES} 行，分两次查`);
    return;
  }
  busy.value = true;
  try {
    lines.value = await api.lookup(text.value);
    picked.value = lines.value.map(() => true);
  } catch (e) {
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}

const count = computed(() => ({
  exact: lines.value.filter((l) => l.match === "EXACT").length,
  // 开头一致的也算「要确认」：库里那颗不一定是他要的
  ambiguous: lines.value.filter((l) => l.match === "AMBIGUOUS" || l.match === "PREFIX").length,
  none: lines.value.filter((l) => l.match === "NONE").length,
}));
const pickedCount = computed(() => picked.value.filter(Boolean).length);

function toggle(i: number) {
  picked.value[i] = !picked.value[i];
}

/** 多家厂牌：去搜索页让他自己点那一家 */
function resolve(l: ElecLookupLine) {
  go(ROUTES.search, { keyword: l.mpn || l.input });
}

/**
 * 这一行按什么询。**只有库里正好有（EXACT）才换成库里那个料号**；
 * 开头一致的（他写 CH340、库里是 CH340N）不替他改 —— 那可能是另一颗料；
 * 库里没有的按原文询，由平台去认（「0805 10K 1%」会被切出一个「1%」来，那不是料号）。
 */
function askText(l: ElecLookupLine): string {
  if (l.match === "EXACT" && l.hit) return l.hit.mpn;
  // 同一行里读出了数量 = 这一行被拆清楚了（「LM2596S-5.0 300」），用拆出来的料号；
  // 读不出数量的（「0805 10K 1%」）拆出来的那一段靠不住，按原文
  if (l.match === "NONE") return l.qty != null && l.mpn ? l.mpn : l.input.trim();
  return l.mpn || l.input.trim();
}

/** 标题永远是他写的那一段，不是库里的：否则看起来像是他输入了库里那一个 */
function titleOf(l: ElecLookupLine): string {
  return askText(l);
}

function askAll() {
  const chosen = lines.value.filter((_, i) => picked.value[i]);
  if (!chosen.length) return;
  setRfqDraft(chosen.map((l) => ({
    mpn: askText(l),
    partNo: l.match === "EXACT" ? l.hit?.partNo : undefined,
    mfr: l.match === "EXACT" && l.hit?.mfrKnown ? (l.hit.mfrName ?? undefined) : undefined,
    qty: l.qty ?? null,
  })));
  go(ROUTES.rfqCreate, { from: "lookup" });
}
</script>

<template>
  <sh-scaffold title-key="title.lookup">
    <view class="sh-card">
      <text class="txt-caption sh-muted">从 Excel 里整列复制，一行一个料号（可带厂牌与数量）</text>
      <textarea
        v-model="text"
        class="field__area ta sh-num"
        :maxlength="-1"
        placeholder="STM32F103C8T6 2000&#10;TI TPS54331DR 500"
      />
      <view class="sh-row sh-row--between">
        <text class="txt-caption sh-muted">{{ inputCount }} 行</text>
        <view class="sh-btn sh-btn--md sh-btn--soft" :class="{ 'is-disabled': !inputCount || busy }" @tap="check">
          {{ busy ? "正在查…" : "查一下" }}
        </view>
      </view>
    </view>

    <template v-if="lines.length">
      <view class="sh-row summary">
        <text class="txt-sub"><text class="txt-bold">{{ count.exact }}</text> 库里有</text>
        <text class="txt-sub"><text class="txt-bold warn">{{ count.ambiguous }}</text> 要确认</text>
        <text class="txt-sub"><text class="txt-bold">{{ count.none }}</text> 库里没有</text>
      </view>

      <view class="sh-cells">
        <view v-for="(l, i) in lines" :key="i" class="sh-cell sh-row sh-row--top line">
          <sh-check :model-value="picked[i]" @update:model-value="toggle(i)"></sh-check>
          <view class="grow" @tap="l.hit && l.match !== 'AMBIGUOUS' ? go(ROUTES.part, { partNo: l.hit.partNo }) : undefined">
            <view class="sh-row sh-row--between">
              <text class="txt-strong sh-num mpn">{{ titleOf(l) }}</text>
              <text class="txt-sub sh-num">× {{ l.qty ? qtyOf(l.qty) : "数量待填" }}</text>
            </view>

            <text v-if="!l.mpn || (l.match === 'NONE' && !l.hit)" class="txt-caption sh-muted block">
              库里没有 · 照原文询价，平台帮你找
            </text>
            <text v-else-if="l.match === 'AMBIGUOUS'" class="txt-caption warn block" @tap.stop="resolve(l)">
              同一料号有 {{ l.others + 1 }} 家厂牌 —— 点一下选哪家 ›
            </text>
            <text v-else-if="!l.hit?.market" class="txt-caption sh-muted block">
              库里没有 · 照样可以询价，平台帮你找
            </text>
            <text v-else-if="l.match === 'PREFIX'" class="txt-caption block">
              <text class="warn">库里只有开头一致的 {{ l.hit.mpn }}</text> · 询价按你写的发，平台会核对
            </text>
            <text v-else class="txt-caption block">
              {{ l.hit.mfrName || "厂牌未确认" }} ·
              库存 {{ QTY_BAND[l.hit.market.qtyBand] }}<text v-if="l.hit.market.priceFromE6 != null"> ·
              {{ priceOf(l.hit.market.priceFromE6) }} 起</text> · {{ l.hit.market.spot ? "现货" : leadOf(l.hit.market.leadDaysMin) }}
            </text>
          </view>
        </view>
      </view>

      <sh-actionbar pill="lead">
        <text class="txt-sub">已选 {{ pickedCount }} 行</text>
        <view class="sh-btn sh-btn--md" :class="{ 'is-disabled': !pickedCount }" @tap="askAll">一起询价</view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.ta {
  min-height: 280rpx;
  margin: 16rpx 0;
}
.summary {
  gap: 32rpx;
  padding: 28rpx 12rpx 16rpx;
}
.warn {
  color: var(--sh-warning);
}
.line {
  gap: 20rpx;
}
.grow {
  flex: 1;
  min-width: 0;
}
.mpn {
  word-break: break-all;
}
.block {
  display: block;
  margin-top: 8rpx;
}
</style>
