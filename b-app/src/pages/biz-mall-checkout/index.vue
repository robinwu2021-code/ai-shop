<template>
  <sh-scaffold title-key="bizMall.checkout.title" :denied="!merchant.can('biz:store:admin')">
    <biz-store-tag readonly></biz-store-tag>
    <sh-empty v-if="!quote" :pending="quoting" :failed="!!quoteError" @retry="requote"
      :text="quoteError || String(t('bizMall.checkout.quoting'))"></sh-empty>

    <template v-else>
      <view class="sh-card">
        <text class="txt-strong">{{ name }}</text>
        <view v-for="(c, i) in quote.components ?? []" :key="c.componentNo ?? i" class="sh-row sh-row--between sh-mt-xs">
          <text class="txt-sub">
            {{ t(`bizMall.kind.${c.kind}`) }}<text v-if="c.cycleDays"> · {{ t("bizMall.checkout.days", { n: c.cycleDays }) }}</text>
            <text v-if="c.quantity && c.quantity > 1"> × {{ c.quantity }}</text>
          </text>
        </view>
        <view class="sh-mt-md">
          <sh-kv between :label="String(t('bizMall.checkout.total'))"><text class="sh-num">{{ mallMoney(quote.total) }}</text></sh-kv>
          <sh-kv between :label="String(t('bizMall.checkout.payable'))"><text class="txt-strong sh-num">{{ mallMoney(quote.payable) }}</text></sh-kv>
        </view>
        <text class="sh-hint txt-quiet">{{ t("bizMall.checkout.expiresAt", { time: mallTime(quote.expiresAt) }) }}</text>
      </view>

      <view v-if="needsAddress" class="sh-card">
        <text class="txt-strong">{{ t("bizMall.address.title") }}</text>
        <sh-section :title="String(t('bizMall.address.recipient'))">
          <sh-go :text="address.recipient || String(t('bizMall.address.required'))" @tap="ask('recipient', 64)"></sh-go>
        </sh-section>
        <sh-section :title="String(t('bizMall.address.phone'))">
          <sh-go :text="address.phone || String(t('bizMall.address.required'))" @tap="ask('phone', 32, 'number')"></sh-go>
        </sh-section>
        <sh-section :title="String(t('bizMall.address.city'))">
          <sh-go :text="address.city || String(t('bizMall.address.optional'))" @tap="ask('city', 64)"></sh-go>
        </sh-section>
        <sh-section :title="String(t('bizMall.address.line'))">
          <sh-go :text="address.addressLine || String(t('bizMall.address.required'))" @tap="ask('addressLine', 200)"></sh-go>
        </sh-section>
        <sh-section :title="String(t('bizMall.address.postal'))">
          <sh-go :text="address.postalCode || String(t('bizMall.address.optional'))" @tap="ask('postalCode', 16)"></sh-go>
        </sh-section>
      </view>

      <view class="sh-card">
        <text class="txt-strong">{{ t("bizMall.checkout.payBy") }}</text>
        <text class="sh-hint txt-quiet">{{ t("bizMall.checkout.offlineHint") }}</text>
        <view class="sh-btn sh-mt-md" :class="{ 'is-disabled': !ready || placing }" @tap="place">
          {{ t("bizMall.checkout.submit") }}
        </view>
        <text v-if="placeError" class="sh-hint is-danger">{{ placeError }}</text>
      </view>
    </template>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 确认商城订单：服务端报价（线下转账口径）→ 有硬件时填收货地址 → 下单 → 发出付款指引 → 去订单详情付款。
//
// 下单与发指引是两步：下单成功、发指引失败时订单已经在了，**不能让商家再下一单**，
// 直接带去订单详情，那里会重试发指引。幂等键在进页时生成一次，重复点不会下两单。
import { computed, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { countryOf, hasHardware, mallErrorText, mallMoney, mallTime } from "@/shared/mall";
import { prompt } from "@ai-shop/ui/prompt";
import { idempotencyKey } from "@shared/net/http-client";
import type { MallQuote, MallShippingAddress } from "@/api/mall";

const { t, te } = useI18n();
const merchant = useMerchantStore();
const err = (e: unknown) => mallErrorText(e, (k, v) => String(v ? t(k, v) : t(k)), te);

const sku = ref("");
const currency = ref("");
const name = ref("");
const quote = ref<MallQuote | null>(null);
const quoting = ref(false);
const quoteError = ref("");
const placing = ref(false);
const placeError = ref("");
const key = idempotencyKey();
const address = reactive<MallShippingAddress>({ recipient: "", phone: "", city: "", addressLine: "", postalCode: "" });

const needsAddress = computed(() => hasHardware(quote.value?.components));
const ready = computed(() =>
  !!quote.value?.quoteNo && (!needsAddress.value || !!(address.recipient && address.phone && address.addressLine)));

async function requote() {
  quoting.value = true;
  quoteError.value = "";
  try {
    quote.value = await api.mMallOfflineQuote(merchant.storeNo, {
      lines: [{ skuVersionNo: sku.value, quantity: 1 }],
      currency: currency.value,
    });
  } catch (e) {
    quoteError.value = err(e);
  } finally {
    quoting.value = false;
  }
}

async function ask(field: "recipient" | "phone" | "city" | "addressLine" | "postalCode", maxlength: number, type?: "number") {
  const v = await prompt({ title: String(t(`bizMall.address.${field === "addressLine" ? "line" : field === "postalCode" ? "postal" : field}`)),
    value: address[field] ?? "", maxlength, ...(type ? { type } : {}) });
  if (v != null) address[field] = v.trim();
}

async function place() {
  if (!ready.value || placing.value) return;
  placing.value = true;
  placeError.value = "";
  const store = merchant.storeNo;
  try {
    const order = await api.mMallPlaceOrder(store, {
      quoteNo: quote.value!.quoteNo!,
      idempotencyKey: key,
      shippingAddress: needsAddress.value
        ? { ...address, countryCode: countryOf(currency.value), city: address.city || null, postalCode: address.postalCode || null, region: null }
        : null,
    });
    // 指引发不出去不挡路：订单已建，详情页会再发
    await api.mMallIssueInstructions(store, order.orderNo!).catch(() => undefined);
    uni.redirectTo({ url: `${ROUTES.bizMallOrder}?orderNo=${encodeURIComponent(order.orderNo!)}` });
  } catch (e) {
    placeError.value = err(e);
    // 报价过期 / 已用：重新报价，金额以新报价为准
    if (/QUOTE_(EXPIRED|USED)|VERSION_CONFLICT/.test(e instanceof Error ? e.message : "")) await requote();
  } finally {
    placing.value = false;
  }
}

onLoad((q) => {
  sku.value = decodeURIComponent(String(q?.sku ?? ""));
  currency.value = decodeURIComponent(String(q?.currency ?? ""));
  name.value = decodeURIComponent(String(q?.name ?? ""));
  void requote();
});
</script>
