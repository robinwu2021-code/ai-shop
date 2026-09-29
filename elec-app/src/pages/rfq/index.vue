<script setup lang="ts">
// 询价详情（原型 e08 待报价 / e10 已报价 / e11 接受报价），外加按行选供应商报价。
//
// - 待报价：三步状态轴，第三步写明「微信通知你」—— 他就不必守着这一页刷新
// - 已报价：逐行报价；**没报的行留在原位并写清楚**，不能悄悄消失 —— 他要知道这一项是
//   「平台没找到」而不是「我漏填了」。合计只算报了的行，顶上同时给有效期与剩余天数
// - 按行选：一张 BOM 上不同的行很可能出自不同供应商，整单选等于逼他为一行放弃另一行更好的价。
//   代号 A/B/C 只在这一行内有效
// - 平台报价走整单「接受报价」：按行选的接口只认供应商的报价（平台那条会 404）
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api, errMsg, toast } from "@/api";
import { ensureLogin } from "@/shared/auth";
import { ROUTES, go } from "@/shared/routes";
import {
  COND, COND_REQ, DC_REQ, INVOICE, PACKING, PACKING_REQ, dateOf, daysLeft, leadOf, priceOf, qtyOf, subtotalOf, whenOf,
} from "@/shared/format";
import { lineSubtotalE6, rfqStatusText, rfqTotal } from "@/shared/rfq";
import type { ElecOffer, ElecRfq, ElecRfqLine } from "@shared/types";

const rfqNo = ref("");
const fresh = ref(false);
const rfq = ref<ElecRfq | null>(null);
const failed = ref("");
const confirming = ref(false);
const busy = ref(false);
/** 这次会话里选过的报价。**后端的 Offer 还没有「已选中」标记**（TDD 偏差说明），刷新后只能靠它 */
const picked = ref<Record<number, string>>({});

onLoad((q) => {
  rfqNo.value = q?.rfqNo ? decodeURIComponent(String(q.rfqNo)) : "";
  fresh.value = q?.fresh === "1";
});
onShow(async () => {
  if (!(await ensureLogin())) return;
  await load();
});

async function load() {
  failed.value = "";
  try {
    rfq.value = await api.rfqDetail(rfqNo.value);
  } catch (e) {
    failed.value = errMsg(e);
  }
}

const r = computed(() => rfq.value);
const reqLine = computed(() => {
  const x = r.value;
  if (!x) return "";
  return [
    `${x.lineCnt} 项`,
    INVOICE[x.needInvoice],
    DC_REQ[x.dcReq],
    x.condReq && x.condReq !== "ANY" ? COND_REQ[x.condReq] : "",
    x.packingReq && x.packingReq !== "ANY" ? PACKING_REQ[x.packingReq] : "",
    x.needByDays ? `${x.needByDays} 天内到货` : "",
    x.allowAlt ? "可用替代" : "",
    x.deliverCity ?? "",
  ].filter(Boolean).join(" · ");
});
const left = computed(() => daysLeft(r.value?.quoteValidUntil));
const total = computed(() => (r.value ? rfqTotal(r.value) : null));
const quotedLines = computed(() => r.value?.lines.filter((l) => l.quote) ?? []);
const missingLines = computed(() => r.value?.lines.filter((l) => !l.quote) ?? []);
const canAcceptAll = computed(() => r.value?.status === "QUOTED" && quotedLines.value.length > 0);
const canPick = computed(() => !!r.value && r.value.status !== "CLOSED" && r.value.status !== "ACCEPTED");

/** 供应商报的那几条（平台那条单独画在上面） */
function supplierOffers(l: ElecRfqLine): ElecOffer[] {
  return l.offers.filter((o) => o.from === "SUPPLIER");
}

function offerMeta(o: ElecOffer, want: number): string {
  return [
    o.qty != null && o.qty < want ? `只能供 ${qtyOf(o.qty)}` : "",
    o.dcYear ? `${o.dcYear} 年批次` : "",
    leadOf(o.leadDays),
    o.cond ? COND[o.cond] : "",
    o.packing ? PACKING[o.packing] : "",
    o.validUntil ? `有效至 ${dateOf(o.validUntil)}` : "",
  ].filter(Boolean).join(" · ");
}

async function pick(l: ElecRfqLine, o: ElecOffer) {
  if (busy.value || !r.value) return;
  const ok = await new Promise<boolean>((resolve) => uni.showModal({
    title: `选${o.label}？`,
    content: `${l.mpn}：${priceOf(o.priceE6)} × ${qtyOf(Math.min(o.qty ?? l.qty, l.qty))}（含税）。`
      + "价格按这一刻锁定，平台专员会联系你确认合同与交货 —— 小程序里不收款",
    success: (res) => resolve(res.confirm),
    fail: () => resolve(false),
  }));
  if (!ok) return;
  busy.value = true;
  try {
    rfq.value = await api.acceptOffer(r.value.rfqNo, l.lineNo, o.offerNo);
    picked.value = { ...picked.value, [l.lineNo]: o.offerNo };
    toast(`已选${o.label}，平台会联系你`);
  } catch (e) {
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}

async function acceptAll() {
  if (busy.value || !r.value) return;
  busy.value = true;
  try {
    rfq.value = await api.acceptRfq(r.value.rfqNo);
    confirming.value = false;
    toast("已接受，平台专员会联系你");
  } catch (e) {
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}

function again() {
  const x = r.value;
  if (!x) return;
  if (x.lines.length === 1) go(ROUTES.rfqCreate, { mpn: x.lines[0]!.mpn, partNo: x.lines[0]!.partNo });
  else go(ROUTES.lookup, { text: x.lines.map((l) => `${l.mpn} ${l.qty}`).join("\n") });
}
</script>

<template>
  <sh-scaffold title-key="title.rfq" :pending="!r && !failed" :failed="!!failed" :failed-text="failed" @retry="load">
    <template v-if="r">
      <!-- 顶上：状态 -->
      <view v-if="r.status === 'SUBMITTED'" class="sh-card">
        <view v-if="fresh" class="sh-notice sh-notice--success gap-b">
          <text class="txt-sub">询价已提交</text>
        </view>
        <view class="steps">
          <view class="step is-done"><text class="dot"></text><text class="txt-sub">已提交 · {{ whenOf(r.createdAt) }}</text></view>
          <view class="step is-on"><text class="dot"></text><text class="txt-sub">平台正在找货、核价</text></view>
          <view class="step"><text class="dot"></text><text class="txt-sub">报价后微信通知你</text></view>
        </view>
        <text v-if="r.dispatchCnt" class="txt-caption sh-muted block">
          已派给 {{ r.dispatchCnt }} 家供应商<text v-if="r.quoteCnt">，{{ r.quoteCnt }} 家已报价 —— 可以先选</text>
        </text>
      </view>
      <view v-else-if="r.status === 'QUOTED'" class="sh-notice">
        <text class="txt-sub">平台报价 · 有效至 {{ dateOf(r.quoteValidUntil) }}<text v-if="left !== null && left >= 0">，还剩 {{ left }} 天</text></text>
      </view>
      <view v-else-if="r.status === 'EXPIRED'" class="sh-notice sh-notice--warning">
        <text class="txt-sub">报价已过期（{{ dateOf(r.quoteValidUntil) }}）—— 行情变了，平台不再兑现这个价</text>
      </view>
      <view v-else-if="r.status === 'ACCEPTED'" class="sh-notice sh-notice--success">
        <text class="txt-sub">已接受 · 平台专员会联系你确认合同与交货</text>
      </view>
      <view v-else class="sh-notice sh-notice--muted">
        <text class="txt-sub">{{ rfqStatusText(r) }}<text v-if="r.closeReason === 'NO_SOURCE'"> —— 平台没找到货，可以换个料号再询</text></text>
      </view>

      <!-- 逐行 -->
      <view class="sh-card block">
        <text class="txt-caption sh-muted">{{ reqLine }}</text>
        <view v-for="l in r.lines" :key="l.lineNo" class="line">
          <view class="sh-row sh-row--between">
            <text class="txt-strong sh-num mpn">{{ l.mpn }}</text>
            <text class="txt-sub sh-num">× {{ qtyOf(l.qty) }}</text>
          </view>
          <text v-if="l.mfr || l.targetE6" class="txt-caption sh-muted block">
            {{ [l.mfr, l.targetE6 ? `目标价 ${priceOf(l.targetE6)}` : ""].filter(Boolean).join(" · ") }}
          </text>

          <!-- 平台那条 -->
          <view v-if="l.quote" class="offer is-platform">
            <view class="sh-row sh-row--between">
              <text class="txt-sub">平台报价</text>
              <text class="txt-strong">{{ priceOf(l.quote.priceE6) }}</text>
            </view>
            <text class="txt-caption sh-muted block">
              {{ [l.quote.qty != null && l.quote.qty < l.qty ? `只能供 ${qtyOf(l.quote.qty)}` : `× ${qtyOf(l.quote.qty ?? l.qty)}`,
                  l.quote.dcYear ? `${l.quote.dcYear} 年批次` : "", leadOf(l.quote.leadDays),
                  l.quote.cond ? COND[l.quote.cond] : "", l.quote.packing ? PACKING[l.quote.packing] : "", "含税"]
                .filter(Boolean).join(" · ") }}
            </text>
            <text v-if="l.quote.note" class="txt-caption block">{{ l.quote.note }}</text>
            <text class="txt-caption sh-muted block">小计 ¥{{ subtotalOf(lineSubtotalE6(l)!, 1) }}</text>
          </view>
          <view v-else-if="r.status === 'QUOTED' || r.status === 'EXPIRED'" class="offer is-missing">
            <text class="txt-sub warn">平台没找到货</text>
            <text class="txt-caption sh-muted block">这一项平台暂时供不了，可以换个料号再询</text>
          </view>

          <!-- 供应商的那几条：代号只在这一行内有效 -->
          <view v-for="o in supplierOffers(l)" :key="o.offerNo" class="offer">
            <view class="sh-row sh-row--between">
              <text class="txt-sub">{{ o.label }}</text>
              <view class="sh-row">
                <text class="txt-strong">{{ priceOf(o.priceE6) }}</text>
                <text v-if="picked[l.lineNo] === o.offerNo" class="sh-chip sh-chip--success pick">已选</text>
                <text v-else-if="canPick && !picked[l.lineNo]" class="sh-btn sh-btn--sm pick" @tap="pick(l, o)">选这条</text>
              </view>
            </view>
            <text class="txt-caption sh-muted block">{{ offerMeta(o, l.qty) }} · 含税</text>
          </view>
          <text v-if="!l.quote && !supplierOffers(l).length && r.status === 'SUBMITTED'" class="txt-caption sh-muted block">
            等报价
          </text>
        </view>
      </view>

      <view v-if="r.quoteNote" class="sh-card block">
        <text class="txt-caption sh-muted">平台说明</text>
        <text class="txt-sub block">{{ r.quoteNote }}</text>
      </view>

      <view class="sh-card block">
        <sh-kv v-if="r.remark" label="备注">{{ r.remark }}</sh-kv>
        <sh-kv label="联系你" :divided="!!r.remark">{{ r.contactPhone }}</sh-kv>
        <sh-kv label="单号" divided><text class="sh-num">{{ r.rfqNo }}</text></sh-kv>
      </view>
      <text v-if="r.status === 'SUBMITTED' && !r.quoteCnt" class="txt-caption sh-muted foot">
        还没收到报价？平台会先打电话核实数量与批次要求
      </text>

      <sh-actionbar v-if="canAcceptAll" pill="lead">
        <view>
          <text class="txt-caption sh-muted">合计（含税）</text>
          <text class="txt-price block-inline">¥{{ total }}</text>
        </view>
        <view class="sh-btn sh-btn--md" @tap="confirming = true">接受报价</view>
      </sh-actionbar>
      <sh-actionbar v-else-if="r.status === 'EXPIRED' || r.status === 'CLOSED'">
        <view class="sh-btn sh-btn--soft" @tap="again">重新询一次</view>
      </sh-actionbar>

      <!-- e11：确认面板 -->
      <sh-sheet :visible="confirming" title="接受这份报价" @close="confirming = false">
        <view class="sheet-body">
          <sh-kv label="几项" between>
            {{ quotedLines.length }} 项<text v-if="missingLines.length" class="warn">（{{ missingLines.map((l) => l.mpn).join("、") }} 不在内）</text>
          </sh-kv>
          <sh-kv label="合计（含税）" between divided><text class="txt-price">¥{{ total }}</text></sh-kv>
          <text class="txt-caption sh-muted block">
            价格按这一刻锁定。平台专员会在 2 小时内联系你确认合同与交货 —— 小程序里不收款
          </text>
          <view class="sh-row acts">
            <view class="sh-btn sh-btn--muted grow" @tap="confirming = false">再想想</view>
            <view class="sh-btn grow" :class="{ 'is-disabled': busy }" @tap="acceptAll">确定</view>
          </view>
        </view>
      </sh-sheet>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.block {
  display: block;
  margin-top: 24rpx;
}
.sh-card .block,
.offer .block,
.line .block {
  margin-top: 6rpx;
}
.gap-b {
  margin-bottom: 20rpx;
}
.steps {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.step {
  display: flex;
  align-items: center;
  gap: 16rpx;
  color: var(--sh-sub);
}
.step.is-done,
.step.is-on {
  color: var(--sh-ink);
}
.dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 9999px;
  background: var(--sh-line);
}
.step.is-done .dot {
  background: var(--sh-primary);
}
.step.is-on .dot {
  background: var(--sh-warning);
}
.line {
  padding-top: 24rpx;
  margin-top: 24rpx;
  border-top: var(--sh-hairline);
}
.mpn {
  flex: 1;
  min-width: 0;
  word-break: break-all;
}
.offer {
  margin-top: 16rpx;
  padding: 16rpx 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.offer.is-platform {
  background: var(--sh-primary-tint);
}
.warn {
  color: var(--sh-warning);
}
.pick {
  margin-inline-start: 16rpx;
}
.foot {
  display: block;
  text-align: center;
  margin-top: 28rpx;
}
.block-inline {
  display: block;
}
.sheet-body {
  padding: 8rpx 0 24rpx;
}
.acts {
  gap: 20rpx;
  margin-top: 32rpx;
}
.grow {
  flex: 1;
}
</style>
