<script setup lang="ts">
// 地址簿：列表 + 新增/编辑（同页内弹层，不再多开一页 —— 表单只有 5 个字段）。
// `picking=1` 时从结算页进入，选中即回填并返回。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { Address } from "@shared/types";

const { t } = useI18n();

const list = ref<Address[]>([]);
const picking = ref(false);
const editing = ref(false);
/** 编辑中的草稿。addressId 为空 = 新增 */
const draft = ref<Omit<Address, "addressId"> & { addressId?: string }>({
  name: "",
  phone: "",
  region: "",
  detail: "",
  isDefault: false,
  tag: "",
});

const valid = computed(
  () =>
    draft.value.name.trim() &&
    /^\d{11}$/.test(draft.value.phone.trim()) &&
    draft.value.region.trim() &&
    draft.value.detail.trim(),
);

async function load() {
  list.value = await api.addressList();
}

function openNew() {
  draft.value = { name: "", phone: "", region: "", detail: "", isDefault: !list.value.length, tag: "" };
  editing.value = true;
}

function openEdit(a: Address) {
  draft.value = { ...a };
  editing.value = true;
}

async function save() {
  if (!valid.value) {
    uni.showToast({ title: String(t("address.invalid")), icon: "none" });
    return;
  }
  list.value = await api.saveAddress({ ...draft.value });
  editing.value = false;
}

async function remove(a: Address) {
  const ok = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: String(t("address.removeTitle")),
      content: `${a.region} ${a.detail}`,
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false),
    });
  });
  if (!ok) return;
  list.value = await api.removeAddress(a.addressId);
}

async function setDefault(a: Address) {
  list.value = await api.setDefaultAddress(a.addressId);
}

/** 从结算页进来时，点一条即选中并返回 */
async function pick(a: Address) {
  if (!picking.value) return;
  await api.setDefaultAddress(a.addressId);
  uni.navigateBack();
}

onLoad((q) => {
  picking.value = q?.picking === "1";
  load();
});
</script>

<template>
  <sh-scaffold title-key="address.title">
    <view v-for="a in list" :key="a.addressId" class="sh-card card" @tap="pick(a)">
      <view class="card__head">
        <text class="card__name">{{ a.name }}</text>
        <text class="card__phone sh-num">{{ a.phone }}</text>
        <text v-if="a.tag" class="sh-chip tiny">{{ a.tag }}</text>
        <text v-if="a.isDefault" class="sh-chip sh-chip--primary tiny">
          {{ $t("address.default") }}
        </text>
      </view>
      <text class="card__addr">{{ a.region }} {{ a.detail }}</text>

      <view class="card__ops">
        <text v-if="!a.isDefault" class="op" @tap.stop="setDefault(a)">
          {{ $t("address.setDefault") }}
        </text>
        <text class="op" @tap.stop="openEdit(a)">{{ $t("address.edit") }}</text>
        <text class="op op--danger" @tap.stop="remove(a)">{{ $t("address.remove") }}</text>
      </view>
    </view>

    <sh-empty bare v-if="!list.length" :text='$t("address.empty")'></sh-empty>

    <view class="sh-btn fab" @tap="openNew">{{ $t("address.add") }}</view>
    <view class="fab__spacer" />

    <!-- 编辑弹层 -->
    <view v-if="editing" class="sheet">
      <view class="sheet__mask" @tap="editing = false" />
      <view class="sheet__panel">
        <view class="sheet__grip" />
        <text class="sh-h2">
          {{ draft.addressId ? $t("address.edit") : $t("address.add") }}
        </text>

        <input v-model="draft.name" class="field" :placeholder="$t('address.name')" />
        <input
          v-model="draft.phone"
          class="field"
          type="number"
          maxlength="11"
          :placeholder="$t('address.phone')"
        />
        <input v-model="draft.region" class="field" :placeholder="$t('address.region')" />
        <input v-model="draft.detail" class="field" :placeholder="$t('address.detail')" />
        <input v-model="draft.tag" class="field" :placeholder="$t('address.tagPh')" />

        <view class="switchrow" @tap="draft.isDefault = !draft.isDefault">
          <text class="switchrow__label">{{ $t("address.asDefault") }}</text>
          <view class="dot" :class="{ 'is-on': draft.isDefault }" />
        </view>

        <view class="sh-btn sheet__save" :class="{ 'is-disabled': !valid }" @tap="save">
          {{ $t("common.confirm") }}
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.card {
  margin-bottom: 20rpx;
}
.card__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12rpx;
}
.card__name {
  font-size: 30rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.card__phone {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.tiny {
  padding: 4rpx 14rpx;
  font-size: 24rpx;
}
.card__addr {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.55;
  margin-top: 14rpx;
}
.card__ops {
  display: flex;
  justify-content: flex-end;
  gap: 32rpx;
  margin-top: 20rpx;
}
.op {
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.op--danger {
  color: var(--sh-danger);
}
.fab {
  position: fixed;
  inset-inline: 28rpx;
  bottom: calc(28rpx + env(safe-area-inset-bottom));
}
.fab__spacer {
  height: 160rpx;
}
.sheet {
  position: fixed;
  inset: 0;
  z-index: 100;
}
.sheet__mask {
  position: absolute;
  inset: 0;
  background: var(--sh-scrim);
}
.sheet__panel {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--sh-surface);
  border-radius: 44rpx 44rpx 0 0;
  padding: 24rpx 36rpx calc(48rpx + env(safe-area-inset-bottom));
}
.sheet__grip {
  width: 72rpx;
  height: 8rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  margin: 0 auto 32rpx;
}
.field {
  background: var(--sh-faint);
  border-radius: 24rpx;
  padding: 26rpx 28rpx;
  font-size: 26rpx;
  color: var(--sh-ink);
  margin-top: 16rpx;
}
.switchrow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 28rpx;
}
.switchrow__label {
  font-size: 26rpx;
  color: var(--sh-ink);
}
.dot {
  width: 44rpx;
  height: 44rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
}
.dot.is-on {
  background: var(--sh-primary);
}
.sheet__save {
  margin-top: 36rpx;
}
.is-disabled {
  opacity: 0.45;
}
</style>
