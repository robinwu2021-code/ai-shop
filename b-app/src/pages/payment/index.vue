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

/** 表单只在「还没开好户」时显示；开好了就没什么可填的 */
const current = computed(() => list.value[0] ?? null);
const done = computed(() => current.value?.canReceiveMoney === true);

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
    list.value = await api.mPayments(entityNo.value || undefined);
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

    <view v-for="p in list" :key="p.payChannel" class="sh-card ch">
      <view class="ch__top">
        <text class="txt-title">{{ p.channelName }}</text>
        <!--
          状态一律照 canReceiveMoney 显示，不在端上比 applyStatus ——
          比错的表现是「显示能收钱但收不了」，要到第一笔订单才暴露
        -->
        <text class="badge" :class="p.canReceiveMoney ? 'is-ok' : 'is-wait'">
          {{ p.canReceiveMoney ? $t("payment.ok") : $t(`payment.status.${p.applyStatus}`) }}
        </text>
      </view>

      <!-- 驳回原因要显眼：不给原因，商家只会反复重提同一份资料 -->
      <text v-if="p.rejectReason" class="reason">{{ p.rejectReason }}</text>

      <sh-kv v-if="p.canReceiveMoney" between :label="String($t('payment.settleAccount'))">
        <text>{{ p.settleAccountMasked }}</text>
      </sh-kv>

      <!-- 还缺什么，逐条列出来。「还差结算账户」比「审核中」有用得多 -->
      <view v-else-if="p.missing.length" class="miss">
        <text class="miss__t">{{ $t("payment.missingTitle") }}</text>
        <text v-for="m in p.missing" :key="m" class="miss__i">· {{ $t(`payment.missing.${m}`) }}</text>
      </view>
    </view>

    <!-- 已经能收钱就不再显示表单：重复进件会拿到新的商户号，历史分账仍指向旧号 -->
    <view v-if="current && !done" class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("payment.formTitle") }}</text>
      <text class="hint">{{ $t("payment.formHint") }}</text>

      <view class="field">
        <text class="field__label">{{ $t("payment.settleAccount") }}</text>
        <input
          v-model="form.settleAccount"
          class="field__input sh-num"
          type="number"
          :placeholder="$t('payment.accountPh')"
        />
        <text class="hint">{{ $t("payment.accountHint") }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("payment.contactName") }}</text>
        <input v-model="form.contactName" class="field__input" placeholder="张老板" />
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
        <text class="hint">{{ $t("payment.licensesHint") }}</text>
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
.ch {
  margin-top: 14rpx;
}
.ch__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.badge {
  padding: 6rpx 18rpx;
  border-radius: 9999px;
  font-size: 24rpx;
}
.badge.is-ok {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
.badge.is-wait {
  background: var(--sh-faint);
  color: var(--sh-sub);
}
.reason {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-danger);
}
.miss {
  margin-top: 20rpx;
}
.miss__t {
  display: block;
  font-size: 28rpx;
  color: var(--sh-ink);
}
.miss__i {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.field {
  margin-top: 20rpx;
}
.hint {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
.submit {
  margin-top: 28rpx;
}
.refresh {
  margin-top: 16rpx;
}
</style>
