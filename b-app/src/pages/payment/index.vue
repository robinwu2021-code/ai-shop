<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 收款进件（ADR-002）。
//
// 这一页回答商家的一个具体问题：**「我能收钱了吗？卡在哪？」**
//
// 它与入驻审核是两条独立的链路：入驻过了店就能开、货能上架，
// 但通道没批就收不了钱。此前 B 端完全看不到这个状态 ——
// 商家开完店、上完架，第一笔订单才发现收不了款，而那时只能打电话给运营。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { pickImages } from "@shared/ports/media";
import type { PaymentApplyment } from "@shared/types";

const { t } = useI18n();

const list = ref<PaymentApplyment[]>([]);
const loading = ref(false);
const submitting = ref(false);

/**
 * 当前在看哪个通道。
 *
 * <p><b>此前这一页写死取 `list[0]`</b>，于是后端明明按通道各一条返回，
 * 页面永远只显示第一条 —— 商家开了支付宝也看不见，更没有地方去开第二个。
 */
const picked = ref("");
const current = computed(() =>
  list.value.find((p) => p.payChannel === picked.value) ?? list.value[0] ?? null);
const done = computed(() => current.value?.canReceiveMoney === true);

/**
 * 状态徽章分三种，判据是**球在谁那边**：
 *   能收钱        → 「可以收款」
 *   没发给通道过  → 「待补资料」（球在商家）
 *   发过了        → 照后端状态（审核中 / 已驳回 / 已冻结）
 */
function badgeText(p: PaymentApplyment): string {
  if (p.canReceiveMoney) return String(t("payment.ok"));
  // NONE = 这个通道商家一次都没申请过。归到「待补资料」会让人以为已经在办了
  if (p.applyStatus === "NONE") return String(t("payment.notOpened"));
  if (!p.submitted && p.applyStatus !== "REJECTED") return String(t("payment.needInfo"));
  return String(t(`payment.status.${p.applyStatus}`));
}

function badgeTone(p: PaymentApplyment): string {
  if (p.canReceiveMoney) return "is-ok";
  if (p.applyStatus === "NONE") return "is-none";
  // 待补资料要与「审核中」区分开：前者要他动手，后者只需要等
  return !p.submitted && p.applyStatus !== "REJECTED" ? "is-todo" : "is-wait";
}

const form = ref({ settleAccount: "", contactName: "", contactPhone: "" });
const licenses = ref<string[]>([]);
const uploading = ref(false);

/** 缺什么就说什么。服务端算好了直接用 —— 端上再算一遍必然与服务端不一致 */
const missing = computed(() => current.value?.missing ?? []);
const needLicense = computed(() => missing.value.includes("licenses"));

/** 给哪张证照进件。从证照详情页（多证照）进来时带上，空 = 当前证照 */
const entityNo = ref("");
onLoad((q) => {
  entityNo.value = q?.entityNo ?? "";
});
onShow(load);

async function load() {
  loading.value = true;
  try {
    /*
     * 取「能开的全部通道」而不是「已开的」：没开过的那几条会带着
     * applyStatus=NONE 回来，页面按同一套状态机渲染出「去开通」。
     * 只取已开的话，这一页永远长不出第二个通道的入口。
     */
    list.value = await api.mPayChannels(entityNo.value || undefined);
  } catch {
    list.value = [];
  } finally {
    loading.value = false;
  }
}

async function addLicense() {
  if (uploading.value) return;
  let picked;
  try {
    picked = await pickImages(1, ["camera", "album"]);
  } catch {
    return; // 取消不是错误
  }
  const img = picked[0];
  if (!img) return;
  uploading.value = true;
  try {
    const { url } = await api.mUploadImage(img.tempPath);
    licenses.value.push(url);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    uploading.value = false;
  }
}

async function submit() {
  if (!current.value || submitting.value) return;
  if (!form.value.settleAccount.trim()) {
    uni.showToast({ title: t("payment.needAccount"), icon: "none" });
    return;
  }
  submitting.value = true;
  try {
    const r = await api.mSubmitPayment({
      payChannel: current.value.payChannel,
      settleAccount: form.value.settleAccount.trim(),
      licenses: licenses.value,
      contactName: form.value.contactName,
      contactPhone: form.value.contactPhone,
      // 与读同一张证照 —— 少了它会「看的是第二张、进件进到第一张上」
      ...(entityNo.value ? { entityNo: entityNo.value } : {}),
    });
    list.value = [r, ...list.value.slice(1)];
    /*
     * 提交成功就**立刻清空明文账号**。留在内存里没有任何用处 ——
     * 回显用的是服务端给的掩码，而留着只会多一处泄露面。
     */
    form.value.settleAccount = "";
    uni.showToast({
      title: r.canReceiveMoney ? t("payment.activated") : t("payment.submitted"),
      icon: "none",
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}

/** 切到另一个通道：下面那张表单跟着换，已填的内容不带过去 */
function pick(p: PaymentApplyment) {
  if (picked.value === p.payChannel) return;
  picked.value = p.payChannel;
  form.value = { settleAccount: "", contactName: "", contactPhone: "" };
  licenses.value = [];
}

/** 回调会丢，丢了商家就永远停在「审核中」—— 给他一个自己能按的按钮 */
async function refresh() {
  if (!current.value) return;
  try {
    const r = await api.mRefreshPayment(current.value.payChannel);
    list.value = [r, ...list.value.slice(1)];
    uni.showToast({ title: t("payment.refreshed"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}
</script>

<template>
  <sh-scaffold title-key="payment.title" :denied="!merchant.can('biz:finance')">
    <view class="head">
      <text class="txt-display">{{ $t("payment.title") }}</text>
      <text class="sh-muted sh-mt-xs blk">{{ $t("payment.hint") }}</text>
    </view>

    <view
      v-for="p in list"
      :key="p.payChannel"
      class="sh-card ch"
      :class="{ 'is-picked': current && p.payChannel === current.payChannel }"
      @tap="pick(p)"
    >
      <view class="ch__top sh-row sh-row--between">
        <text class="txt-title">{{ p.channelName }}</text>
        <!--
          「能不能收钱」照 canReceiveMoney，不在端上比 applyStatus ——
          比错的表现是「显示能收钱但收不了」，要到第一笔订单才暴露。

          **收不了钱时还要再分一次：球在谁那边。**
          入驻通过时后端建的进件占位也是 APPLYING，而那时商家一个字都没填过。
          一律显示「审核中」的话，新商家看到的是
          「审核中」+ 下面「还差结算账户」—— 他读成平台在审，
          于是坐等；而这一步正是「不能收钱」最常卡死的地方。
          判据是 submitted（发给通道过才有单号），不是 missing 是否为空 ——
          资料填齐但还没点提交时，两者的答案不一样。
        -->
        <text class="txt-caption badge" :class="badgeTone(p)">{{ badgeText(p) }}</text>
      </view>

      <!-- 驳回原因要显眼：不给原因，商家只会反复重提同一份资料 -->
      <text v-if="p.rejectReason" class="txt-caption reason">{{ p.rejectReason }}</text>

      <sh-kv v-if="p.canReceiveMoney" between :label="String($t('payment.settleAccount'))">
        <text>{{ p.settleAccountMasked }}</text>
      </sh-kv>

      <!-- 没开过的通道不列「还缺什么」：他还没决定要不要开，先给他一句能点的 -->
      <text v-else-if="p.applyStatus === 'NONE'" class="txt-caption sh-muted">
        {{ p.payChannel === current?.payChannel ? $t("payment.fillBelow") : $t("payment.tapToOpen") }}
      </text>

      <!-- 还缺什么，逐条列出来。「还差结算账户」比「审核中」有用得多 -->
      <view v-else-if="p.missing.length" class="miss">
        <text class="txt-body miss__t">{{ $t("payment.missingTitle") }}</text>
        <text v-for="m in p.missing" :key="m" class="txt-caption miss__i">· {{ $t(`payment.missing.${m}`) }}</text>
      </view>
    </view>

    <!-- 已经能收钱就不再显示表单：重复进件会拿到新的商户号，历史分账仍指向旧号 -->
    <view v-if="current && !done" class="sh-card sh-mt-sm">
      <!-- 表单标题带上通道名：多通道下光写「开通收款」看不出在填哪一个 -->
      <text class="txt-title">{{ $t("payment.formTitle") }}（{{ current.channelName }}）</text>
      <text class="sh-hint">{{ $t("payment.formHint") }}</text>

      <view class="field">
        <text class="field__label">{{ $t("payment.settleAccount") }}</text>
        <input
          maxlength="32"
          v-model="form.settleAccount"
          class="field__input sh-num"
          type="number"
          :placeholder="$t('payment.accountPh')"
        />
        <text class="sh-hint">{{ $t("payment.accountHint") }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("payment.contactName") }}</text>
        <input maxlength="64" v-model="form.contactName" class="field__input" :placeholder="$t('payment.contactNamePh')" />
      </view>

      <view class="field">
        <text class="field__label">{{ $t("payment.contactPhone") }}</text>
        <input
          v-model="form.contactPhone"
          class="field__input sh-num"
          type="number"
          maxlength="11"
          placeholder="13800138000"
        />
      </view>

      <view v-if="needLicense" class="field">
        <text class="field__label">{{ $t("payment.licenses") }}</text>
        <text class="sh-hint">{{ $t("payment.licensesHint") }}</text>
        <sh-uploader
          :list="licenses"
          :w="160"
          :uploading="uploading"
          @add="addLicense"
        ></sh-uploader>
      </view>

      <view class="sh-btn submit" @tap="submit">
        {{ submitting ? $t("payment.submitting") : $t("payment.submit") }}
      </view>
      <view class="sh-btn sh-btn--soft refresh" @tap="refresh">{{ $t("payment.refresh") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
/* 同 stores：横向内边距由 sh-scaffold 统一给，这里再加一道会与卡片左边界错开 */
.head {
  padding: 8rpx 0 16rpx;
}
/* 同 stores：`<text>` 默认 inline，不给 block 标题与说明会挤在同一行 */
.blk {
  display: block;
}

.badge {
  padding: 6rpx 18rpx;
  border-radius: 9999px;
}
.badge.is-ok {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
.badge.is-wait {
  background: var(--sh-faint);
  color: var(--sh-sub);
}
/*
  待补资料：**要他动手，所以不能和「等着就行」长一个样。**
  「审核中」用安静的灰（他什么也做不了），这一个用提醒色 ——
  两者此前共用一个灰底，于是「该我了」和「等平台」在视觉上没有区别。
*/
.badge.is-todo {
  background: var(--sh-warning-tint);
  color: var(--sh-warning);
}
/*
  未开通：**比「审核中」还要安静。**它既不需要他动手（还没决定要开），
  也不是平台在处理 —— 用提醒色会把它抬到与「待补资料」同一优先级，
  而商家真正该先看的是已经在办的那一个。
*/
.badge.is-none {
  background: transparent;
  border: 1rpx solid var(--sh-line);
  color: var(--sh-sub);
}
/* 选中的通道要看得出来：多张卡片长得一样时，下面的表单在填哪一个全靠这一条 */
.ch.is-picked {
  border: 2rpx solid var(--sh-primary);
}
.reason {
  display: block;
  margin-top: 16rpx;
  color: var(--sh-danger);
}
.miss {
  margin-top: 20rpx;
}
.miss__t {
  display: block;
}
.miss__i {
  display: block;
  margin-top: 8rpx;
}
.field {
  margin-top: 20rpx;
}

.submit {
  margin-top: 28rpx;
}
.refresh {
  margin-top: 16rpx;
}
</style>
