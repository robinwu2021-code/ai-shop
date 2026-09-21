<script setup lang="ts">
// 库存设置 · 记库存的品类（原型 inv-managed-switch s02–s04，TDD-商品纳入进销存开关 §3）。
//
// 设置挂在主体上，各门店共用：同一件货在 A 店记、B 店不记，账就说不清。
// 一类一行开关，「{n} 件商品」告诉他拨这一下会动到多少东西；
// 「默认不记」让他知道那个关着的状态不是出错，是平台给服务类的默认值。
//
// 关掉一个品类走后端的三道判，界面只负责把结果说清楚：
//   BLOCKED       有在途单据 → 列出单据、什么都没改（s04）
//   NEEDS_CONFIRM 还有库存   → 列出实存、确认键写「不记库存」，确认后带 confirm 再来一次（s03）
//   DONE          直接改好
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { confirm } from "@ai-shop/ui/prompt";
import { describeBlockers, describeStocked } from "@/shared/inv-mode";
import type { InvCategorySetting } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const rows = ref<InvCategorySetting[]>([]);
const loaded = ref(false);
const failed = ref(false);
/** 正在拨的那一类。**一次只拨一类** —— 两个请求并发时，后回来的会把先回来的界面状态盖掉 */
const busy = ref<string | null>(null);

async function load() {
  try {
    rows.value = await api.mInvCategorySettings();
    failed.value = false;
  } catch {
    failed.value = true;
  } finally {
    loaded.value = true;
  }
}

async function toggle(row: InvCategorySetting) {
  if (busy.value) return;
  busy.value = row.categoryNo;
  const managed = !row.managed;
  try {
    let r = await api.mInvSetCategory(row.categoryNo, { managed });
    if (r.status === "BLOCKED") {
      await confirm({
        title: String(t("stockSettings.blockedTitle")),
        hint: String(t("stockSettings.blockedHint", { list: describeBlockers(t, r.goods) })),
        alert: true,
      });
      return;
    }
    if (r.status === "NEEDS_CONFIRM") {
      const ok = await confirm({
        title: String(t("stockSettings.confirmTitle", { name: row.name })),
        hint: String(t("stockSettings.confirmStocked", { n: r.goods.length, list: describeStocked(t, r.goods) })),
        confirmText: String(t("stockSettings.confirmOk")),
      });
      if (!ok) return;
      r = await api.mInvSetCategory(row.categoryNo, { managed, confirm: true });
      // 确认之后又冒出在途单据（这几秒里有人开了进货单）：照样说清楚
      if (r.status === "BLOCKED") {
        await confirm({
          title: String(t("stockSettings.blockedTitle")),
          hint: String(t("stockSettings.blockedHint", { list: describeBlockers(t, r.goods) })),
          alert: true,
        });
        return;
      }
    }
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = null;
  }
}

function meta(row: InvCategorySetting): string {
  return row.isDefault && !row.managed
    ? String(t("stockSettings.defaultOff", { n: row.goodsCount }))
    : String(t("stockSettings.goodsCount", { n: row.goodsCount }));
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="stockSettings.title" :denied="!merchant.can('biz:goods')"
    :failed="failed"
    @retry="load"
  >
    <view class="sh-card">
      <text class="txt-title">{{ $t("stockSettings.section") }}</text>
      <sh-empty v-if="!rows.length" :pending="!loaded" :text="String($t('stockSettings.empty'))"></sh-empty>
      <view
        v-for="row in rows"
        :key="row.categoryNo"
        class="sh-row sh-row--between line"
        @tap="toggle(row)"
      >
        <view class="name">
          <text class="txt-strong">{{ row.name }}</text>
          <text class="txt-caption sh-muted">{{ meta(row) }}</text>
        </view>
        <sh-switch :model-value="row.managed" :disabled="busy !== null"></sh-switch>
      </view>
    </view>
    <view class="notes">
      <text class="txt-caption sh-muted">{{ $t("stockSettings.sharedHint") }}</text>
      <text class="txt-caption sh-muted">{{ $t("stockSettings.goodsHint") }}</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.line {
  padding: 24rpx 0;
}
.name {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.notes {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  padding: 16rpx 8rpx 0;
}
</style>
