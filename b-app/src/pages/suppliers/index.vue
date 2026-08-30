<script setup lang="ts">
// 供应商档案（进销存 S5）。
//
// **这一页与进货页的选择器是同一份数据的两种用法**：那边只要在用的（挑一家来记账），
// 这一页要全部（停用的也得看得见，否则没法再启用回来）。
// 所以两处发的是同一个接口，差别只在 `activeOnly`。
//
// **没有删除，只有停用。** 历史进货单指着这条档案，删了之后那些单据就指向空 ——
// 而它们是账，不是可以清理的缓存。
//
// 门禁与进货同一个码（`biz:stock`）：建供应商与记一笔进货是同一件事的两半，
// 分成两个码的结果是「能记账但选不到供应商」。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { Supplier } from "@shared/types";
import { confirm, prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const rows = ref<Supplier[]>([]);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    // activeOnly=false：这一页要看得见停用的
    rows.value = await api.mSuppliers({ activeOnly: false });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

async function add() {
  const name = await prompt({
    title: String(t("suppliers.addTitle")),
    placeholder: String(t("suppliers.addPh")),
  });
  if (name == null || !name.trim()) return;
  try {
    await api.mSupplierCreate({ name: name.trim() });
    await load();
  } catch (e) {
    // 重名后端拒（10409）。**这里如实转述它的话**，不换成「保存失败」——
    // 商家需要知道的是「这家已经建过了」，那句话让他去列表里找，而不是重试
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 改名。**引用平台档案的不给改** —— 名称跟平台走，
 * 在这儿改了下次同步就被盖掉，而他不会知道自己改的东西没了。
 */
async function rename(s: Supplier) {
  if (s.fromPlatform) {
    uni.showToast({ title: String(t("suppliers.platformReadonly")), icon: "none" });
    return;
  }
  const name = await prompt({ title: String(t("suppliers.renameTitle")), value: s.name });
  if (name == null || !name.trim() || name.trim() === s.name) return;
  try {
    await api.mSupplierUpdate(s.supplierNo, { name: name.trim() });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

async function toggle(s: Supplier) {
  const active = s.status !== "ACTIVE";
  // 停用要二次确认；启用不用 —— 停用会让它从进货页消失，而那是商家未必预期的后果
  if (!active) {
    const okd = await confirm({
      title: String(t("suppliers.archiveTitle", { name: s.name })),
      hint: String(t("suppliers.archiveHint")),
      confirmText: String(t("suppliers.archive")),
    });
    if (!okd) return;
  }
  try {
    await api.mSupplierActive(s.supplierNo, { active });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="suppliers.title" :denied="!merchant.can('biz:stock')">
    <text class="sh-hint">{{ $t("suppliers.intro") }}</text>

    <sh-empty v-if="!loading && !rows.length" :text="String($t('suppliers.empty'))"></sh-empty>

    <view v-for="s in rows" :key="s.supplierNo" class="sh-card sup">
      <view class="sh-row sh-row--between sh-row--baseline">
        <text class="txt-strong">{{ s.name }}</text>
        <!-- 停用的标出来：不标的话它与在用的长得一样，而它挑不到 -->
        <text v-if="s.status !== 'ACTIVE'" class="sh-muted">{{ $t("suppliers.archived") }}</text>
        <text v-else-if="s.fromPlatform" class="sh-muted">{{ $t("suppliers.platform") }}</text>
      </view>

      <text v-if="s.contactName || s.contactPhone" class="sh-muted sup__meta">
        {{ [s.contactName, s.contactPhone].filter(Boolean).join(" · ") }}
      </text>

      <view class="sup__acts sh-row">
        <text class="sh-link" @tap="rename(s)">{{ $t("suppliers.rename") }}</text>
        <text class="sh-link" @tap="toggle(s)">
          {{ s.status === "ACTIVE" ? $t("suppliers.archive") : $t("suppliers.restore") }}
        </text>
      </view>
    </view>

    <sh-add :text="String($t('suppliers.add'))" @tap="add"></sh-add>
  </sh-scaffold>
</template>

<style scoped>
.sup__meta {
  display: block;
  margin-top: 8rpx;
}

.sup__acts {
  gap: 32rpx;
  margin-top: 16rpx;
}
</style>
