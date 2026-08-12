<script setup lang="ts">
// 员工详情（B-11.10）。**一个人的全部**，按「先认人、再看权、最后危险动作」排。
//
// 为什么单独一页：门店 × 角色矩阵塞在列表卡片里时被压成两行 chip，
// 六个角色有三个被折进「更多角色」—— 而那三个（理货/配送/客服）恰恰是
// 小店最需要区分的。一屏放得下，就不用折。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { datetime } from "@shared/utils/datetime";
import type { MerchantRole, MerchantStaff, StaffLog, StaffRole, Store } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const accountNo = ref("");
const staff = ref<MerchantStaff | null>(null);
const roles = ref<MerchantRole[]>([]);
const stores = ref<Store[]>([]);
const logs = ref<StaffLog[]>([]);
const busy = ref(false);

const nameOf = (s: MerchantStaff) => s.displayName || s.loginPhone;

/** 可授予的角色：**OWNER 不在其中** —— 授出去等于凭空造第二个老板 */
const grantable = computed(() => roles.value.filter((r) => r.roleCode !== "OWNER"));

const hasRole = (storeNo: string, role: string) =>
  !!staff.value?.roles.some((r) => r.storeNo === storeNo && r.role === role);

async function load() {
  const [list, roleList, storeList, logList] = await Promise.all([
    api.mStaffList().catch(() => []),
    api.mRoles().catch(() => []),
    api.mStoreList().catch(() => []),
    api.mStaffLogs(accountNo.value).catch(() => []),
  ]);
  staff.value = list.find((s) => s.mchAccountNo === accountNo.value) ?? null;
  roles.value = roleList;
  stores.value = storeList;
  logs.value = logList;
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

/** 点一下加一个角色，再点一下去掉 —— **一人一店可多角色，权限取并集** */
function grant(storeNo: string, role: string) {
  const had = hasRole(storeNo, role);
  run(() => api.mGrantStore(accountNo.value, storeNo, role as StaffRole, !had));
}

/** 停用**不删授权** —— 他回来时授权还在。文案要说清，否则没人敢点 */
function toggleStatus() {
  const s = staff.value;
  if (!s) return;
  run(() => api.mSetStaffStatus(s.mchAccountNo, s.status !== "ACTIVE"));
}

onLoad((q) => {
  accountNo.value = (q?.no as string) ?? "";
});
onShow(load);
</script>

<template>
  <!-- 与列表同一档权限：能看谁有什么权限 = 能改谁有什么权限 -->
  <sh-scaffold title-key="staff.detailTitle" :denied="!merchant.can('biz:store:admin')">
    <template v-if="staff">
      <!-- ① 认人 -->
      <view class="sh-card">
        <view class="head">
          <text class="sh-h1">{{ nameOf(staff) }}</text>
          <text v-if="staff.isOwner" class="tag tag--primary">{{ $t("staff.owner") }}</text>
          <text v-else-if="staff.status !== 'ACTIVE'" class="tag">{{ $t("staff.disabled") }}</text>
        </view>
        <text class="sh-muted phone sh-num">{{ staff.loginPhone }}</text>
        <text class="sh-muted note">{{ $t("staff.loginHint") }}</text>
      </view>

      <!-- ② 门店 × 角色：矩阵搬到这里，一屏放得下就不折叠。
           角色本身能做什么点进角色详情看 —— 在这里再铺一遍并集，
           两处会各自漂移，而漂移的那份没人会发现。 -->
      <view v-if="!staff.isOwner" class="sh-card mt">
        <text class="sh-h2">{{ $t("staff.grants") }}</text>
        <text class="sh-muted note">{{ $t("staff.grantHint") }}</text>
        <view v-for="st in stores" :key="st.storeNo" class="store">
          <text class="store__name">{{ st.name }}</text>
          <view class="chips">
            <text
              v-for="r in grantable"
              :key="r.roleCode"
              class="sh-chip"
              :class="{ 'sh-chip--primary': hasRole(st.storeNo, r.roleCode) }"
              @tap="grant(st.storeNo, r.roleCode)"
            >{{ r.name }}</text>
          </view>
        </view>
      </view>
      <text v-else class="sh-muted note owner-note">{{ $t("staff.ownerNote") }}</text>

      <!-- ③ 只看这个人的变更记录 -->
      <view class="sh-card mt">
        <text class="sh-h2">{{ $t("staff.logs") }}</text>
        <text v-if="!logs.length" class="sh-muted note">{{ $t("staff.logsEmpty") }}</text>
        <view v-for="(l, i) in logs" :key="i" class="log">
          <text class="log__t sh-num">{{ datetime(l.at) }}</text>
          <text class="log__d">{{ l.detail || l.action }}</text>
          <text v-if="l.actor" class="sh-muted log__a">{{ l.actor }}</text>
        </view>
      </view>

      <!-- ④ 危险动作放最后，且把「停用」与「收回授权」分开说 -->
      <view v-if="!staff.isOwner" class="sh-card mt danger">
        <text class="sh-h2">{{ $t("staff.dangerTitle") }}</text>
        <text class="sh-muted note">
          {{ staff.status === "ACTIVE" ? $t("staff.disableHint") : $t("staff.enableHint") }}
        </text>
        <view class="sh-btn sh-btn--soft mt-s" @tap="toggleStatus">
          {{ staff.status === "ACTIVE" ? $t("staff.disable") : $t("staff.enable") }}
        </view>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.phone {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
}
.note {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  line-height: 1.6;
}
.owner-note {
  margin: 24rpx 8rpx;
}
.mt {
  margin-top: 24rpx;
}
.mt-s {
  margin-top: 20rpx;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 16rpx;
}
.store {
  margin-top: 24rpx;
}
.store__name {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.tag {
  padding: 4rpx 14rpx;
  border-radius: 9999px;
  background: var(--sh-fill);
  font-size: 24rpx;
  color: var(--sh-sub);
}
.tag--primary {
  background: var(--sh-primary-tint);
  color: var(--sh-primary);
}
.log {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  padding: 12rpx 0;
  border-top: 2rpx solid var(--sh-line);
  font-size: 24rpx;
}
.log__t {
  color: var(--sh-sub);
}
.log__d {
  flex: 1;
  min-width: 0;
  color: var(--sh-ink);
}
.log__a {
  font-size: 24rpx;
}
.danger .sh-h2 {
  color: var(--sh-danger);
}
</style>
