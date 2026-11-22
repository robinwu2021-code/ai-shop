<script setup lang="ts">
/**
 * 发票开法：给自己的一张订单选「发票存哪里 / 给谁」。载具优先 ——
 * 有默认载具就预选它，没有就预选会员载具；纸本证明联放在后面。
 *
 * <p>一张主单可能拆给几家商家，每家各回一条结果（记下 / 已开 / 早开过 / 没开通 / 没有会员载具），
 * 用一句人话逐条说出来；没成的可以改选再提交，已记下的以最后一次为准。
 */
import { computed, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { InvoiceCarrier, InvoiceChoiceKind, InvoiceChoiceReq, InvoiceChoiceResult } from "@/api/contract";
import {
  CARRIER_TYPE_KEY,
  CHOICE_HINT_KEY,
  CHOICE_KEY,
  defaultChoice,
  donateCodeValid,
  formatFiscalNumber,
  OUTCOME_KEY,
  taxIdValid,
} from "@/shared/sales-invoice";

const { t } = useI18n();
const orderNo = ref("");
const carriers = ref<InvoiceCarrier[]>([]);
const loaded = ref(false);
const failed = ref(false);
/** 用户自己点过选项之后，回到这页（比如刚去加了载具）不再用默认值覆盖他的选择 */
const touched = ref(false);

const KINDS: readonly InvoiceChoiceKind[] = ["MEMBER", "CARRIER", "CONSUMER", "TAX_ID", "DONATE"];
const kind = ref<InvoiceChoiceKind>("MEMBER");
const carrierNo = ref("");
const form = reactive({ taxId: "", name: "", donateCode: "" });
const submitting = ref(false);
const results = ref<InvoiceChoiceResult[]>([]);

async function load() {
  try {
    carriers.value = await api.invoiceCarriers();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
  const picked = carriers.value.find((c) => c.carrierNo === carrierNo.value);
  if (!touched.value || (kind.value === "CARRIER" && !picked)) {
    const d = defaultChoice(carriers.value);
    if (!touched.value) kind.value = d.kind;
    carrierNo.value = d.carrierNo || carriers.value[0]?.carrierNo || "";
  }
}

function pickKind(k: InvoiceChoiceKind) {
  touched.value = true;
  kind.value = k;
}

function pickCarrier(c: InvoiceCarrier) {
  touched.value = true;
  kind.value = "CARRIER";
  carrierNo.value = c.carrierNo;
}

function gotoCarriers() {
  uni.navigateTo({ url: "/pages/invoice-carriers/index" });
}

const taxIdWrong = computed(() => !!form.taxId.trim() && !taxIdValid(form.taxId.trim()));
const donateWrong = computed(() => !!form.donateCode.trim() && !donateCodeValid(form.donateCode.trim()));

/** 按所选开法拼请求；缺必填项返回 null（按钮置灰，不发请求） */
const request = computed<InvoiceChoiceReq | null>(() => {
  if (!orderNo.value) return null;
  const base = { orderNo: orderNo.value, kind: kind.value };
  switch (kind.value) {
    case "MEMBER":
    case "CONSUMER":
      return base;
    case "CARRIER": {
      const c = carriers.value.find((x) => x.carrierNo === carrierNo.value);
      return c ? { ...base, carrierType: c.carrierType, carrierId: c.carrierId } : null;
    }
    case "TAX_ID": {
      const taxId = form.taxId.trim();
      if (!taxIdValid(taxId)) return null;
      const name = form.name.trim();
      return { ...base, taxId, ...(name ? { name } : {}) };
    }
    case "DONATE": {
      const donateCode = form.donateCode.trim();
      return donateCodeValid(donateCode) ? { ...base, donateCode } : null;
    }
  }
  return null;
});

async function submit() {
  const body = request.value;
  if (!body || submitting.value) return;
  submitting.value = true;
  try {
    results.value = await api.chooseSalesInvoice(body);
  } catch (e) {
    uni.showToast({ title: (e as Error)?.message || String(t("salesInvoice.failed")), icon: "none" });
  } finally {
    submitting.value = false;
  }
}

function outcomeText(r: InvoiceChoiceResult): string {
  return String(t(OUTCOME_KEY[r.outcome], { no: formatFiscalNumber(r.fiscalNumber) }));
}

const done = computed(() => results.value.length > 0 && results.value.every((r) => r.outcome === "SAVED" || r.outcome === "ISSUED"));

function back() {
  uni.navigateBack();
}

onLoad((q) => {
  orderNo.value = typeof q?.orderNo === "string" ? q.orderNo : "";
});
onShow(load);
</script>

<template>
  <sh-scaffold title-key="salesInvoice.choiceTitle" :failed="!orderNo" :failed-text="String($t('salesInvoice.noOrder'))">
    <text class="sh-hint block-gap">{{ $t("salesInvoice.choiceHint") }}</text>

    <view class="sh-card block-gap">
      <view v-for="k in KINDS" :key="k" class="opt">
        <sh-option :selected="kind === k" @tap="pickKind(k)">
          <text class="txt-body block-0">{{ $t(CHOICE_KEY[k]) }}</text>
          <text class="txt-caption sh-muted">{{ $t(CHOICE_HINT_KEY[k]) }}</text>
        </sh-option>

        <!-- 我的载具：展开已存的，默认那个已预选；没有就给去添加的门 -->
        <view v-if="k === 'CARRIER' && kind === 'CARRIER'" class="sub">
          <sh-empty v-if="!carriers.length" bare compact :pending="!loaded" :failed="failed"
            :text="$t('salesInvoice.carriersEmpty')" @retry="load" />
          <sh-option v-for="c in carriers" :key="c.carrierNo" class="sub__opt" :selected="carrierNo === c.carrierNo" @tap="pickCarrier(c)">
            <view class="sh-row sh-row--between sub__row">
              <text class="txt-body sh-fill">{{ c.label || $t(CARRIER_TYPE_KEY[c.carrierType]) }}</text>
              <text class="txt-caption sh-num ltr">{{ c.carrierId }}</text>
            </view>
          </sh-option>
          <text class="sh-link txt-caption sub__manage" @tap="gotoCarriers">{{ $t("salesInvoice.manageCarriers") }} ›</text>
        </view>

        <view v-if="k === 'TAX_ID' && kind === 'TAX_ID'" class="sub">
          <input v-model="form.taxId" class="field__input" type="number" maxlength="8" :placeholder="String($t('salesInvoice.taxIdPh'))" />
          <text v-if="taxIdWrong" class="sh-hint block-0">{{ $t("salesInvoice.taxIdWrong") }}</text>
          <input v-model="form.name" class="field__input sub__field" maxlength="60" :placeholder="String($t('salesInvoice.buyerNamePh'))" />
        </view>

        <view v-if="k === 'DONATE' && kind === 'DONATE'" class="sub">
          <input v-model="form.donateCode" class="field__input" type="number" maxlength="7" :placeholder="String($t('salesInvoice.donatePh'))" />
          <text v-if="donateWrong" class="sh-hint block-0">{{ $t("salesInvoice.donateWrong") }}</text>
        </view>
      </view>
    </view>

    <sh-section v-if="results.length" :title="String($t('salesInvoice.resultTitle'))">
      <view v-for="r in results" :key="r.sourceNo" class="result">
        <text class="txt-caption sh-muted sh-num block-0">{{ $t("salesInvoice.subOrder", { no: r.sourceNo }) }}</text>
        <text class="txt-body" :class="{ 'txt-primary': r.outcome === 'SAVED' || r.outcome === 'ISSUED' }">{{ outcomeText(r) }}</text>
      </view>
    </sh-section>

    <view class="sh-btn submit" :class="{ 'is-disabled': !request || submitting }" @tap="submit">
      {{ results.length ? $t("salesInvoice.resubmit") : $t("salesInvoice.submit") }}
    </view>
    <view v-if="done" class="sh-btn sh-btn--muted submit" @tap="back">{{ $t("salesInvoice.done") }}</view>
  </sh-scaffold>
</template>

<style scoped>
.block-gap { display: block; margin-bottom: var(--sh-gap-block); }
.block-0 { display: block; }
.opt + .opt { margin-top: 16rpx; }
.sub { padding-top: 16rpx; padding-bottom: 8rpx; padding-inline-start: 24rpx; }
.sub__opt { margin-top: 12rpx; }
.sub__row { gap: 16rpx; }
.sub__manage { display: block; margin-top: 16rpx; }
.sub__field { margin-top: 16rpx; }
.ltr { direction: ltr; }
.result { padding: 12rpx 0; }
.submit { margin-top: var(--sh-gap-block); }
</style>
