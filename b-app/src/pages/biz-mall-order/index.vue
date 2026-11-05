<template>
  <sh-scaffold title-key="bizMall.order.title" :denied="!merchant.can('biz:store:admin')">
    <biz-store-tag readonly></biz-store-tag>
    <sh-empty v-if="!detail" :pending="!loaded" :failed="failed" @retry="load"></sh-empty>

    <template v-else>
      <view class="sh-card">
        <view class="sh-row sh-row--between">
          <text class="txt-caption sh-num">{{ orderNo }}</text>
          <text class="sh-chip" :class="paid ? 'sh-chip--primary' : 'sh-chip--warning'">{{ t(`bizMall.payment.${payment}`) }}</text>
        </view>
        <view v-for="(c, i) in detail.quote?.components ?? detail.summary?.order?.components ?? []" :key="c.componentNo ?? i" class="sh-mt-xs">
          <text class="txt-sub">
            {{ t(`bizMall.kind.${c.kind}`) }}<text v-if="c.cycleDays"> · {{ t("bizMall.checkout.days", { n: c.cycleDays }) }}</text>
            <text v-if="c.quantity && c.quantity > 1"> × {{ c.quantity }}</text>
          </text>
        </view>
        <view class="sh-mt-md">
          <sh-kv between :label="String(t('bizMall.checkout.payable'))"><text class="txt-strong sh-num">{{ mallMoney(detail.summary?.order?.payable) }}</text></sh-kv>
          <sh-kv between :label="String(t('bizMall.order.fulfillment'))"><text class="txt-sub">{{ t(`bizMall.fulfillment.${detail.summary?.fulfillmentStatus ?? "PENDING"}`) }}</text></sh-kv>
          <sh-kv between :label="String(t('bizMall.order.createdAt'))"><text class="txt-sub sh-num">{{ mallTime(detail.summary?.order?.createdAt) }}</text></sh-kv>
        </view>
        <view v-if="detail.shippingAddress" class="sh-mt-md">
          <text class="txt-caption txt-quiet">{{ t("bizMall.address.title") }}</text>
          <text class="txt-sub">{{ detail.shippingAddress.recipient }} · {{ detail.shippingAddress.phone }}</text>
          <text class="txt-sub">{{ [detail.shippingAddress.city, detail.shippingAddress.addressLine].filter(Boolean).join(" ") }}</text>
        </view>
        <view v-for="s in detail.shipments ?? []" :key="s.operationNo" class="sh-mt-xs">
          <text class="txt-caption">{{ t("bizMall.order.shipped", { carrier: s.carrierCode, no: s.trackingNo }) }}</text>
        </view>
      </view>

      <!-- 付款：只在资金维度还没结清时出现。申报≠到账，运营核到款后这一块整块消失 -->
      <view v-if="payment === 'UNPAID'" class="sh-card">
        <text class="txt-strong">{{ t("bizMall.pay.title") }}</text>
        <sh-empty v-if="!instructions" bare :pending="instructionsPending" :failed="!!instructionsError" @retry="loadInstructions"
          :text="instructionsError"></sh-empty>
        <view v-else class="sh-mt-xs">
          <sh-kv between :label="String(t('bizMall.pay.bankCode'))"><text class="sh-num">{{ instructions.instructions.bankCode }}</text></sh-kv>
          <sh-kv between :label="String(t('bizMall.pay.bankAccount'))">
            <text class="sh-num sh-link" @tap="copy(instructions.instructions.bankAccount)">{{ instructions.instructions.bankAccount }}</text>
          </sh-kv>
          <sh-kv between :label="String(t('bizMall.pay.accountName'))"><text>{{ instructions.instructions.accountName }}</text></sh-kv>
          <sh-kv between :label="String(t('bizMall.pay.amount'))"><text class="txt-strong sh-num">{{ mallMoney(instructions.payable) }}</text></sh-kv>
          <sh-kv between :label="String(t('bizMall.pay.dueAt'))"><text class="sh-num">{{ mallTime(instructions.paymentDueAt) }}</text></sh-kv>
          <text class="sh-hint txt-quiet">{{ t("bizMall.pay.hint") }}</text>
        </view>
      </view>

      <view v-if="claims.length" class="sh-card">
        <text class="txt-strong">{{ t("bizMall.claim.history") }}</text>
        <view v-for="c in claims" :key="c.claimNo" class="sh-row--divided">
          <view class="sh-row sh-row--between">
            <text class="txt-sub sh-num">{{ mallMoney(c.declaredAmount) }} · {{ mallTime(c.declaredPaidAt) }}</text>
            <text class="txt-sub" :class="c.status === 'DECLINED' || c.status === 'MISMATCHED' ? 'is-danger' : 'txt-quiet'">
              {{ t(`bizMall.claim.status.${c.status}`) }}
            </text>
          </view>
        </view>
      </view>

      <!-- 申报：没有在审的申报时才能报。被拒（DECLINED）后用新的申报号重报 -->
      <view v-if="payment === 'UNPAID' && instructions && !pendingClaim" class="sh-card">
        <text class="txt-strong">{{ t("bizMall.claim.title") }}</text>
        <text class="sh-hint txt-quiet">{{ t("bizMall.claim.hint") }}</text>
        <sh-section :title="String(t('bizMall.claim.receipts'))">
          <sh-uploader :list="receipts" :max="3" :uploading="submitting" removable @add="pickReceipt"
            @remove="(i: number) => receipts.splice(i, 1)"></sh-uploader>
        </sh-section>
        <sh-section :title="String(t('bizMall.claim.bankReference'))">
          <sh-go :text="bankReference || String(t('bizMall.claim.bankReferenceHint'))" @tap="askReference"></sh-go>
        </sh-section>
        <sh-section :title="String(t('bizMall.claim.paidAt'))">
          <view class="sh-row paid-at">
            <picker mode="date" :value="paidDate" :end="today" @change="(e: any) => (paidDate = e.detail.value)">
              <text class="sh-link sh-num">{{ paidDate }}</text>
            </picker>
            <picker mode="time" :value="paidClock" @change="(e: any) => (paidClock = e.detail.value)">
              <text class="sh-link sh-num">{{ paidClock }}</text>
            </picker>
          </view>
        </sh-section>
        <view class="sh-btn sh-mt-md" :class="{ 'is-disabled': !canSubmit || submitting }" @tap="submit">
          {{ t("bizMall.claim.submit") }}
        </view>
        <text v-if="submitError" class="sh-hint is-danger">{{ submitError }}</text>
      </view>

      <view v-if="pendingClaim && payment === 'UNPAID'" class="sh-card">
        <text class="sh-hint">{{ t("bizMall.claim.waiting") }}</text>
      </view>
    </template>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 商城订单详情 + 线下转账付款。
//
// 流程：看收款账户 → 去银行转账 → 回来上传转账凭证、填转账备注与时间 → 申报。
// **申报不是到账**：申报后订单仍是「待付款」，运营在运营端核到款（录入实际到账）才变「已付款」，
// 权益与发货才开始；运营拒了这份申报，商家看到「未通过」后可重报。
// 凭证按后端要求逐张「预留 → 传原始字节」，全部传完才申报；中途失败整份不报，免得报出没有凭证的申报。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { prompt } from "@ai-shop/ui/prompt";
import {
  EVIDENCE_MAX_BYTES, mallErrorText, mallMoney, mallTime, readImageBytes, sniffImageType,
} from "@/shared/mall";
import type { MallOfflineClaim, MallOfflineInstructions, MallOrderDetail } from "@/api/mall";

const { t, te } = useI18n();
const merchant = useMerchantStore();
const err = (e: unknown) => mallErrorText(e, (k, v) => String(v ? t(k, v) : t(k)), te);

const orderNo = ref("");
const detail = ref<MallOrderDetail | null>(null);
const loaded = ref(false);
const failed = ref(false);
const instructions = ref<MallOfflineInstructions | null>(null);
const instructionsPending = ref(false);
const instructionsError = ref("");
const claims = ref<MallOfflineClaim[]>([]);

const receipts = ref<string[]>([]);
const bankReference = ref("");
const pad = (n: number) => String(n).padStart(2, "0");
const nowDate = new Date();
const today = `${nowDate.getFullYear()}-${pad(nowDate.getMonth() + 1)}-${pad(nowDate.getDate())}`;
const paidDate = ref(today);
const paidClock = ref(`${pad(nowDate.getHours())}:${pad(nowDate.getMinutes())}`);
const submitting = ref(false);
const submitError = ref("");

const payment = computed(() => detail.value?.summary?.paymentStatus ?? "UNPAID");
const paid = computed(() => payment.value === "PAID");
const pendingClaim = computed(() => claims.value.some((c) => c.status === "SUBMITTED" || c.status === "REVIEWING"));
const canSubmit = computed(() => receipts.value.length > 0 && bankReference.value.length > 0);

async function load() {
  failed.value = false;
  const store = merchant.storeNo;
  try {
    const d = await api.mMallOrder(store, orderNo.value);
    if (store !== merchant.storeNo) return;
    detail.value = d;
    if ((d.summary?.paymentStatus ?? "UNPAID") === "UNPAID") await loadInstructions();
    claims.value = await api.mMallClaims(store, orderNo.value).catch(() => claims.value);
  } catch {
    failed.value = true;
  } finally {
    loaded.value = true;
  }
}

/** 读指引；还没发出（下单后发指引那一步失败过）就当场发一次 —— 发指引是幂等的 */
async function loadInstructions() {
  instructionsPending.value = true;
  instructionsError.value = "";
  const store = merchant.storeNo;
  try {
    instructions.value = await api.mMallInstructions(store, orderNo.value).catch((e: unknown) => {
      if (e instanceof Error && e.message === "OFFLINE_INSTRUCTIONS_NOT_ISSUED") {
        return api.mMallIssueInstructions(store, orderNo.value);
      }
      throw e;
    });
  } catch (e) {
    instructionsError.value = err(e);
  } finally {
    instructionsPending.value = false;
  }
}

function copy(text?: string) {
  if (text) uni.setClipboardData({ data: text });
}

function pickReceipt() {
  uni.chooseImage({
    count: 3 - receipts.value.length,
    sizeType: ["compressed"],
    success: (res) => {
      const paths = Array.isArray(res.tempFilePaths) ? res.tempFilePaths : [res.tempFilePaths];
      receipts.value = [...receipts.value, ...paths].slice(0, 3);
    },
  });
}

async function askReference() {
  const v = await prompt({ title: String(t("bizMall.claim.bankReference")), value: bankReference.value, maxlength: 64 });
  if (v != null) bankReference.value = v.trim();
}

async function submit() {
  if (!canSubmit.value || submitting.value || !instructions.value) return;
  submitting.value = true;
  submitError.value = "";
  const store = merchant.storeNo;
  const order = orderNo.value;
  try {
    const keys: string[] = [];
    for (const path of receipts.value) {
      const bytes = await readImageBytes(path);
      const type = sniffImageType(bytes);
      if (!type) throw new Error("EVIDENCE_NOT_IMAGE");
      if (bytes.byteLength > EVIDENCE_MAX_BYTES) throw new Error("EVIDENCE_TOO_LARGE");
      const ticket = await api.mMallReserveEvidence(store, order);
      await api.mMallUploadEvidence(store, order, ticket.assetKey, bytes, type);
      keys.push(ticket.assetKey);
    }
    await api.mMallSubmitClaim(store, order, {
      claimNo: `CL${Date.now()}${Math.random().toString(36).slice(2, 6).toUpperCase()}`,
      declaredAmount: instructions.value.payable,
      declaredPaidAt: new Date(`${paidDate.value}T${paidClock.value}:00`).toISOString(),
      bankReference: bankReference.value,
      evidenceKeys: keys,
    });
    receipts.value = [];
    bankReference.value = "";
    uni.showToast({ title: String(t("bizMall.claim.submitted")), icon: "none" });
    await load();
  } catch (e) {
    submitError.value = err(e);
  } finally {
    submitting.value = false;
  }
}

onLoad((q) => {
  orderNo.value = decodeURIComponent(String(q?.orderNo ?? ""));
});
onShow(load);
</script>

<style scoped>
.paid-at {
  gap: 32rpx;
}
</style>
