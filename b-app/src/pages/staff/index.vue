<script setup lang="ts">
// 员工与授权（B-11.10）。
//
// 两件事在一页：**谁是我的人**（账号）与**他能管哪家店**（逐店角色）。
// 合在一起是因为它们总是一起做 —— 加一个店员的下一步必然是给他指一家店，
// 分成两页会让「加完了但他什么都看不到」变成常态。
//
// 逐店授权不是过度设计：老店的店长去新店帮忙、但新店不归他管，是小连锁的常态。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { MerchantStaff, StaffRole, Store } from "@shared/types";

const { t } = useI18n();

const staff = ref<MerchantStaff[]>([]);
const stores = ref<Store[]>([]);
const busy = ref(false);

const adding = ref(false);
const phone = ref("");
/** 展开授权面板的员工 —— 一次只展开一个，免得整页变成一张巨大的矩阵 */
const editing = ref<string | null>(null);

/**
 * 可授予的角色。**默认只显示前三个** —— 三个角色的选择题谁都会做，
 * 六个并排摆出来就成了需要读说明的题，而店主不会读。
 * 理货员与配送员、客服收在「更多角色」里，夫妻店永远看不到它们。
 */
const COMMON_ROLES = ["MANAGER", "CLERK"] as const;
const MORE_ROLES = ["PICKER", "COURIER", "CS"] as const;
/** 展开了「更多角色」的员工 */
const expanded = ref<string | null>(null);

onShow(load);

async function load() {
  staff.value = await api.mStaffList().catch(() => []);
  stores.value = await api.mStoreList().catch(() => []);
}

async function run(fn: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true;
  try {
    await fn();
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function add() {
  if (!/^\d{11}$/.test(phone.value.trim())) {
    uni.showToast({ title: t("staff.needPhone"), icon: "none" });
    return;
  }
  run(async () => {
    await api.mAddStaff(phone.value.trim());
    phone.value = "";
    adding.value = false;
  });
}

function toggleStatus(s: MerchantStaff) {
  run(() => api.mSetStaffStatus(s.mchAccountNo, s.status !== "ACTIVE"));
}

/**
 * 点一下加一个角色，再点一下去掉 —— **一人一店可多角色**，权限取并集。
 *
 * 小店的常态是一人多岗：站收银台的顺手把货送了（店员 + 配送员）。
 * 此前是单选（点别的角色 = 覆盖），老板想「再加一个」会把原来的冲掉。
 */
function grant(s: MerchantStaff, storeNo: string, role: StaffRole) {
  const had = hasRole(s, storeNo, role);
  run(() => api.mGrantStore(s.mchAccountNo, storeNo, role, !had));
}

const hasRole = (s: MerchantStaff, storeNo: string, role: string) =>
  s.roles.some((r) => r.storeNo === storeNo && r.role === role);

/** 这家店上他持有的全部角色，用于摘要展示 */
const rolesAt = (s: MerchantStaff, storeNo: string) =>
  s.roles.filter((r) => r.storeNo === storeNo).map((r) => r.role);
</script>

<template>
  <sh-scaffold title-key="staff.title">
    <view class="head">
      <text class="sh-h1">{{ $t("staff.title") }}</text>
      <text class="sh-muted mt">{{ $t("staff.hint") }}</text>
    </view>

    <view v-for="s in staff" :key="s.mchAccountNo" class="sh-card p">
      <view class="p__top">
        <view class="p__id">
          <text class="sh-h2">{{ s.loginPhone }}</text>
          <view class="tags">
            <text v-if="s.isOwner" class="tag tag--primary">{{ $t("staff.owner") }}</text>
            <text v-if="s.status !== 'ACTIVE'" class="tag">{{ $t("staff.disabled") }}</text>
          </view>
        </view>
        <!-- 老板没有停用入口：那是个能把自己锁在门外的按钮 -->
        <text v-if="!s.isOwner" class="act" @tap="toggleStatus(s)">
          {{ s.status === "ACTIVE" ? $t("staff.disable") : $t("staff.enable") }}
        </text>
      </view>

      <!-- 老板不需要授权：他的店都归他管，列一遍只会让人以为漏配了 -->
      <text v-if="s.isOwner" class="meta">{{ $t("staff.ownerNote") }}</text>

      <template v-else>
        <text v-if="!s.roles.length" class="meta warn">{{ $t("staff.noStore") }}</text>
        <text v-else class="meta">
          {{ s.roles.map((r) => `${r.storeName}·${$t(`staff.role.${r.role}`)}`).join("，") }}
        </text>

        <text class="act mt" @tap="editing = editing === s.mchAccountNo ? null : s.mchAccountNo">
          {{ editing === s.mchAccountNo ? $t("common.done") : $t("staff.editRoles") }}
        </text>

        <view v-if="editing === s.mchAccountNo" class="grid">
          <view v-for="st in stores" :key="st.storeNo" class="row">
            <text class="row__name">{{ st.name }}</text>
            <view class="row__roles">
              <text
                v-for="r in COMMON_ROLES"
                :key="r"
                class="sh-chip"
                :class="{ 'sh-chip--primary': hasRole(s, st.storeNo, r) }"
                @tap="grant(s, st.storeNo, r)"
              >
                {{ $t(`staff.role.${r}`) }}
              </text>
              <!-- 更多角色：理货员/配送员/客服。夫妻店不用看见它们 -->
              <template v-if="expanded === s.mchAccountNo">
                <text
                  v-for="r in MORE_ROLES"
                  :key="r"
                  class="sh-chip"
                  :class="{ 'sh-chip--primary': hasRole(s, st.storeNo, r) }"
                  @tap="grant(s, st.storeNo, r)"
                >
                  {{ $t(`staff.role.${r}`) }}
                </text>
              </template>
              <text
                v-else
                class="sh-chip more"
                @tap="expanded = s.mchAccountNo"
              >{{ $t("staff.moreRoles") }}</text>
            </view>
          </view>
          <text class="hint">{{ $t("staff.grantHint") }}</text>
        </view>
      </template>
    </view>

    <view v-if="!adding" class="sh-btn sh-btn--soft add" @tap="adding = true">
      {{ $t("staff.add") }}
    </view>

    <view v-else class="sh-card mt-card">
      <text class="sh-h2">{{ $t("staff.add") }}</text>
      <!-- 说清楚不用设密码：店长最常问的就是「密码给他什么」 -->
      <text class="hint">{{ $t("staff.addHint") }}</text>
      <view class="field">
        <text class="field__label">{{ $t("staff.phone") }}</text>
        <input
          v-model="phone"
          class="field__input sh-num"
          type="number"
          maxlength="11"
          placeholder="13800138000"
        />
      </view>
      <view class="sh-btn submit" @tap="add">{{ $t("common.save") }}</view>
      <view class="sh-btn sh-btn--soft cancel" @tap="adding = false">{{ $t("common.cancel") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.head {
  padding: 32rpx 32rpx 8rpx;
}
.mt {
  margin-top: 12rpx;
}
.mt-card {
  margin-top: 24rpx;
}
.p {
  margin-top: 24rpx;
}
.p__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16rpx;
}
.p__id {
  flex: 1;
  min-width: 0;
}
.tags {
  display: flex;
  gap: 10rpx;
  margin-top: 8rpx;
}
.tag {
  padding: 4rpx 14rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  font-size: 24rpx;
  color: var(--sh-sub);
}
.tag--primary {
  background: var(--sh-primary-tint);
  color: var(--sh-primary);
}
.meta {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
.warn {
  color: var(--sh-danger);
}
.act {
  font-size: 26rpx;
  color: var(--sh-primary);
}
.grid {
  margin-top: 20rpx;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 16rpx 0;
}
.row__name {
  font-size: 26rpx;
  color: var(--sh-ink);
}
.row__roles {
  display: flex;
  gap: 12rpx;
}
.field {
  margin-top: 28rpx;
}
.field__label {
  display: block;
  font-size: 26rpx;
  color: var(--sh-sub);
}
.field__input {
  margin-top: 12rpx;
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 28rpx;
  color: var(--sh-ink);
}
.hint {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
.add {
  margin-top: 24rpx;
}
.submit {
  margin-top: 32rpx;
}
.cancel {
  margin-top: 16rpx;
}
</style>
