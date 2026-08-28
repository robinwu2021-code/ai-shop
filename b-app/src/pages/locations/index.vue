<script setup lang="ts">
// 库位与仓（B-9）。
//
// **仓是一种库位，不是一种门店** —— 所以它没有店招、没有收款、
// 不出现在买家那一侧。
//
// **发货源不允许链式指向**（A→B→C）：第一个后果是环，第二个是没人说得清
// 货到底从哪出。保存时直接拦。
//
// 门禁是 `biz:store:admin`：**看得见库位**只要 `biz:stock`，但改这一页上的两件事
//（加仓、设发货源）都要管理员 —— 而这一页除了这两件事没有别的内容，
// 按只读权限放人进来，他会看到一整屏点不动的东西。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockLocation } from "@shared/types";
import { pick, prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const rows = ref<StockLocation[]>([]);
const loading = ref(false);

/** 能当发货源的：只有仓。**门店不能当别的门店的源** —— 那是接力的第一步 */
const sources = computed(() => rows.value.filter((l) => l.kind === "WAREHOUSE"));

async function load() {
  loading.value = true;
  try {
    rows.value = await api.mStockLocations();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

function nameOf(id?: string | null): string {
  if (!id) return "";
  return rows.value.find((l) => l.locationId === id)?.name ?? id;
}

async function addWarehouse() {
  const name = await prompt({
    title: String(t("locations.addTitle")),
    placeholder: String(t("locations.addPh")),
  });
  if (!name) return;
  try {
    await api.mWarehouseCreate(name);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 设发货源。选「发自己的」= 清空，所以传 null 而不是空串 */
async function setSource(l: StockLocation) {
  if (l.kind !== "STORE") return;
  const items = [String(t("locations.ownStock")), ...sources.value.map((s) => s.name)];
  const idx = await pick({ items: items });
  if (idx === null) return;
  try {
    await api.mLocationSetSource(l.locationId, idx === 0 ? null : sources.value[idx - 1]!.locationId);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 类型的 chip 该长什么样。**在途是系统的**，配灰底并注明不可删 */
function kindClass(kind: string): string {
  if (kind === "WAREHOUSE") return "sh-chip--primary";
  if (kind === "TRANSIT") return "sh-chip--warning";
  return "";
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="locations.title" :denied="!merchant.can('biz:store:admin')">
    <sh-empty v-if="!loading && !rows.length" :text="String($t('locations.empty'))"></sh-empty>

    <view v-for="l in rows" :key="l.locationId" class="sh-card sh-mb-sm" @tap="setSource(l)">
      <view class="row__top sh-row">
        <view class="sh-fill">
          <text class="txt-strong row__title">{{ l.name }}</text>
          <view class="row__meta sh-row">
            <text class="sh-chip" :class="kindClass(l.kind)">
              {{ $t(`locations.kind.${l.kind}`) }}
            </text>
            <text v-if="l.kind === 'STORE'" class="sh-muted">
              {{ $t("locations.sourceIs", {
                name: l.sourceLocationId ? nameOf(l.sourceLocationId) : String($t("locations.ownStock")),
              }) }}
            </text>
            <text v-else-if="l.kind === 'TRANSIT'" class="sh-muted">
              {{ $t("locations.transitHint") }}
            </text>
          </view>
        </view>
        <!--
          **只有「在途」是不可删的那一个** —— 它是系统库位。
          仓落进 else 分支会被一起标成「不可删」，而仓是商家自己建的、可以停用；
          标错的后果是他以为建错了也没法收拾，于是干脆不建。
        -->
        <sh-icon v-if="l.kind === 'STORE'" class="row__end" name="chevronRight"
          :size="22" color="var(--sh-sub)"></sh-icon>
        <text v-else-if="l.kind === 'TRANSIT'" class="row__end txt-caption">
          {{ $t("locations.undeletable") }}
        </text>
      </view>
    </view>

    <sh-add :text="String($t('locations.add'))" @tap="addWarehouse"></sh-add>

    <view class="sh-block">
      <sh-section pad :title="String($t('locations.sourceTitle'))"></sh-section>
      <view class="blk">
        <text class="txt-sub">{{ $t("locations.sourceBody") }}</text>
        <text class="txt-sub warn">{{ $t("locations.noRelay") }}</text>
      </view>
    </view>

    <view class="sh-card">
      <text class="txt-caption">{{ $t("locations.warehouseHint") }}</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.row__top {
  gap: 20rpx;
}

.row__title {
  display: block;
}
.row__meta {
  margin-top: 8rpx;
}
.row__end {
  flex: none;
}
.blk {
  padding: 0 26rpx 8rpx;
}
.blk > text {
  display: block;
}
.warn {
  color: var(--sh-danger);
  margin-top: 12rpx;
}
</style>
