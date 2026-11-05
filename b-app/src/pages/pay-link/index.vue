<template>
  <sh-scaffold title-key="bizPay.link.title" :denied="!merchant.can('mch:pay:collect')">
    <view v-if="!link" class="sh-card">
      <sh-section :title="L('amount')">
        <sh-go :text="amountText || L('amountPlaceholder')" @tap="askAmount"></sh-go>
      </sh-section>
      <sh-section :title="L('subject')">
        <sh-go :text="subject || L('subjectPlaceholder')" @tap="askSubject"></sh-go>
      </sh-section>
      <sh-section :title="L('validity')">
        <view class="sh-row validity">
          <view v-for="m in PAY_LINK_VALIDITY" :key="m" class="validity__chip" :class="{ 'is-on': minutes === m }"
                :data-minutes="m" @tap="minutes = m">{{ L('v' + m) }}</view>
        </view>
      </sh-section>
      <text class="sh-hint txt-quiet">{{ L("hint") }}</text>
      <view class="sh-btn sh-mt-md" :class="{ 'is-disabled': !amountMinor || busy }" data-action="generate" @tap="generate">
        {{ L("generate") }}
      </view>
      <text v-if="failed" class="sh-hint is-danger">{{ failure || t("bizPay.common.failed") }}</text>
    </view>

    <view v-else class="sh-card center">
      <text class="sh-muted">{{ L("amount") }}</text>
      <text class="txt-mega sh-num amt">{{ money(link.amountMinor) }}</text>
      <text v-if="link.subject" class="txt-caption txt-quiet">{{ link.subject }}</text>
      <biz-pay-qr v-if="link.url" :link="link.url"></biz-pay-qr>
      <text class="txt-caption link-url" selectable data-testid="link-url">{{ link.url }}</text>
      <text class="txt-caption txt-quiet sh-num" data-testid="link-expiry">{{ L("expiresAt", { time: expireText }) }}</text>
      <text class="txt-caption" :class="statusClass" data-testid="link-status">{{ statusText }}</text>
      <view class="sh-row sh-mt-md actions">
        <view class="sh-btn sh-btn--soft" data-action="copy" @tap="copy">{{ L("copy") }}</view>
        <view class="sh-btn sh-btn--soft" data-action="share" @tap="share">{{ L("share") }}</view>
      </view>
      <view class="sh-row sh-mt-md actions">
        <view class="sh-btn sh-btn--soft" data-action="refresh" @tap="refresh">{{ L("refresh") }}</view>
        <view class="sh-btn sh-btn--soft" data-action="again" @tap="reset">{{ L("again") }}</view>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
/*
 * 收款链接（动态码）：商家定金额与有效期（15 分钟 / 1 小时 / 24 小时 / 7 天），生成一条固定金额的链接发给顾客；
 * 顾客打开即为支付 H5 的动态码页（显示金额与失效时间）。一条链接一张单，付过或过期即失效。
 * 繁体中文仅本页提供：应用语言为中文且系统语言为繁体（TW / HK / MO / Hant）时用本页繁体词条。
 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money, toMinor, toMajor } from "@shared/utils/money";
import { prompt } from "@ai-shop/ui/prompt";
import { PAY_LINK_VALIDITY, type PayLink } from "@/api/pay";
import { linkText } from "./texts";

const { t, locale } = useI18n();
const merchant = useMerchantStore();
const amountMinor = ref(0);
const subject = ref("");
const minutes = ref<number>(1440);
const busy = ref(false);
const failed = ref(false);
const failure = ref("");
const link = ref<(PayLink & { amountMinor: number; subject: string }) | null>(null);

const systemLanguage = (() => {
  try { return uni.getSystemInfoSync().language || ""; } catch { return ""; }
})();
/** 本页文案：繁体环境走本页词条，其余走应用 i18n（bizPay.link.*） */
function L(key: string, named?: Record<string, unknown>): string {
  const hant = linkText(String(locale.value), systemLanguage, key, named);
  return hant ?? String(t("bizPay.link." + key, named ?? {}));
}

const amountText = computed(() => (amountMinor.value ? money(amountMinor.value) : ""));
const expireText = computed(() => {
  if (!link.value) return "";
  const d = new Date(link.value.expireAt), now = new Date();
  const hm = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  return d.toDateString() === now.toDateString() ? hm : `${d.getMonth() + 1}/${d.getDate()} ${hm}`;
});
const closed = computed(() => !!link.value && (link.value.status === "CLOSED" || link.value.status === "FAILED"
  || link.value.expireAt <= Date.now()));
const statusText = computed(() => {
  if (!link.value) return "";
  if (link.value.status === "PAID" || link.value.status === "SUCCEEDED") return L("st_PAID");
  return closed.value ? L("st_closed") : L("st_open");
});
const statusClass = computed(() => (link.value?.status === "PAID" ? "is-success" : closed.value ? "is-danger" : "txt-quiet"));

async function askAmount() {
  const v = await prompt({ title: L("amount"), value: amountMinor.value ? toMajor(amountMinor.value) : "", type: "digit" });
  if (v == null) return;
  const minor = toMinor(v);
  if (!Number.isFinite(minor) || minor <= 0) {
    uni.showToast({ title: L("invalidAmount"), icon: "none" });
    return;
  }
  amountMinor.value = minor;
}

async function askSubject() {
  const v = await prompt({ title: L("subject"), value: subject.value, maxlength: 64 });
  if (v != null) subject.value = v.trim();
}

/** 同一次“生成”用同一个 requestNo：网络重试不会建出两张单 */
let pendingRequestNo = "";
function requestNo(): string {
  if (!pendingRequestNo) pendingRequestNo = "PL" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  return pendingRequestNo;
}

async function generate() {
  if (!amountMinor.value || busy.value) return;
  busy.value = true;
  failed.value = false;
  try {
    const sent = requestNo();
    const r = await api.mPayLinkCreate({ requestNo: sent, amountMinor: amountMinor.value,
      subject: subject.value || undefined, expireMinutes: minutes.value });
    // 查状态始终用本端发出的 requestNo（幂等键），不依赖响应回显
    link.value = { ...r, requestNo: sent, amountMinor: amountMinor.value, subject: subject.value };
    pendingRequestNo = "";
  } catch (e) {
    failed.value = true;
    failure.value = e instanceof Error ? e.message : "";
  } finally {
    busy.value = false;
  }
}

async function refresh() {
  if (!link.value) return;
  try {
    const s = await api.mPayLinkStatus(link.value.requestNo);
    link.value = { ...link.value, status: s.status, expireAt: s.expireAt };
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : String(t("bizPay.common.failed")), icon: "none" });
  }
}

function copy() {
  if (!link.value?.url) return;
  uni.setClipboardData({ data: link.value.url, success: () => uni.showToast({ title: L("copied"), icon: "none" }) });
}

function share() {
  const url = link.value?.url;
  if (!url) return;
  const nav = (globalThis as { navigator?: { share?: (d: { url: string; title?: string }) => Promise<void> } }).navigator;
  if (nav?.share) {
    nav.share({ url, title: L("title") }).catch(() => undefined);
    return;
  }
  const sys = (uni as unknown as { shareWithSystem?: (o: { type: string; href: string; summary?: string }) => void }).shareWithSystem;
  if (sys) {
    sys({ type: "text", href: url, summary: L("title") + " " + money(link.value!.amountMinor) });
    return;
  }
  copy();
}

function reset() {
  link.value = null;
  amountMinor.value = 0;
  subject.value = "";
  minutes.value = 1440;
  failed.value = false;
}

defineExpose({ L, generate, reset, minutes, amountMinor, link });
</script>

<style scoped>
.amt { margin: 8rpx 0 4rpx; }
.validity { flex-wrap: wrap; gap: 16rpx; padding: 8rpx 0; }
.validity__chip { padding: 12rpx 28rpx; border-radius: 999rpx; border: 2rpx solid var(--sh-line); color: var(--sh-sub); }
.validity__chip.is-on { border-color: var(--sh-primary); color: var(--sh-primary); font-weight: 600; }
.link-url { margin-top: 16rpx; word-break: break-all; color: var(--sh-sub); }
.actions { gap: 20rpx; justify-content: center; }
</style>
