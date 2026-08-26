<script setup lang="ts">
// 员工与授权（B-11.10）。**列表层只回答三个问题，细节都在详情页**：
//
//   员工 —— 这些人都是谁          → 点进员工详情
//   角色 —— 某个角色到底能干什么   → 点进角色详情
//   审计 —— 上周三谁把张三提成了店长
//
// 上一版把这三件事全塞进一张卡片（身份 + 状态 + 角色摘要 + 门店×角色矩阵 + 变更记录），
// 于是矩阵被压成两行 chip、「更多角色」被折起来、记录只能按人看。
// **该分页的分页** —— 这一版的主要改动就是把该出去的搬出去。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { datetime } from "@shared/utils/datetime";
import type { MerchantRole, MerchantStaff, StaffLog } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const TABS = ["staff", "roles", "logs"] as const;
type Tab = (typeof TABS)[number];
const tab = ref<Tab>("staff");

const staff = ref<MerchantStaff[]>([]);
const roles = ref<MerchantRole[]>([]);
const logs = ref<StaffLog[]>([]);
const busy = ref(false);

/** 搜索：备注名或尾号。人一多就只能翻，而小连锁十几个人是常态 */
const keyword = ref("");
/** 只看在职。停用的默认收起来 —— 但要能看得见，否则没人能把他重新启用 */
const activeOnly = ref(true);

const adding = ref(false);
const form = ref({ phone: "", name: "" });

/**
 * 认人优先用姓名 —— 一列号码谁也分不清；没填姓名就拿号码顶上。
 *
 * **老板两样都可能没有**：他的 `mch_account.login_phone` 是空的
 * （入驻时建的号，他走 C 端账号登录），于是这一行会渲染成一片空白 ——
 * 唯一那个不可能被停用、权限最大的人，在列表里反而没有标识。
 */
const nameOf = (s: MerchantStaff) => s.displayName || s.loginPhone || t("staff.owner");

/** 他在各店的角色，一句话摘要。列表要的是「他大概管什么」，不是完整矩阵 */
function summary(s: MerchantStaff) {
  if (s.isOwner) return t("staff.ownerNote");
  if (!s.roles.length) return t("staff.noStore");
  return s.roles.map((r) => `${r.storeName}·${roleName(r.role)}`).join("，");
}

/** 老板那行是 `*`（全部），数出来是「1 项权限」—— 数字对，意思反了 */
function permCount(r: MerchantRole) {
  return r.perms.includes("*")
    ? t("staff.roleAll")
    : t("staff.rolePerms", { n: r.perms.length });
}

/** 角色码 → 显示名。**从后端下发的角色表里查**，不在前端写第二份映射 */
function roleName(code: string) {
  return roles.value.find((r) => r.roleCode === code)?.name ?? code;
}

const visibleStaff = computed(() => {
  const k = keyword.value.trim();
  return staff.value
    .filter((s) => !activeOnly.value || s.status === "ACTIVE" || s.isOwner)
    .filter((s) => !k || nameOf(s).includes(k) || s.loginPhone.includes(k));
});

async function load() {
  // 三段各自 catch：角色表拉不到不该让员工列表也空掉
  [staff.value, roles.value, logs.value] = await Promise.all([
    api.mStaffList().catch(() => []),
    api.mRoles().catch(() => []),
    api.mStaffLogs().catch(() => []),
  ]);
}

function add() {
  if (!/^\d{11}$/.test(form.value.phone.trim())) {
    uni.showToast({ title: t("staff.needPhone"), icon: "none" });
    return;
  }
  if (busy.value) return;
  busy.value = true;
  api
    .mAddStaff(form.value.phone.trim(), form.value.name.trim() || undefined)
    .then(async () => {
      form.value = { phone: "", name: "" };
      adding.value = false;
      await load();
    })
    .catch((e: Error) => uni.showToast({ title: e.message, icon: "none" }))
    .finally(() => {
      busy.value = false;
    });
}

const openStaff = (s: MerchantStaff) =>
  uni.navigateTo({ url: `${ROUTES.staffDetail}?no=${s.mchAccountNo}` });
const openRole = (r: MerchantRole) =>
  uni.navigateTo({ url: `${ROUTES.roleDetail}?code=${r.roleCode}` });

/** 从预置角色起步建自定义角色 —— 比从空白勾 13 个码容易得多 */
const copyRole = (r: MerchantRole) =>
  uni.navigateTo({ url: `${ROUTES.roleDetail}?copyFrom=${r.roleCode}` });

onShow(load);
</script>

<template>
  <sh-scaffold title-key="staff.title" :denied="!merchant.can('biz:store:admin')">
    <sh-tabs
      :items="TABS.map((k) => ({ key: k, label: String($t(`staff.tab.${k}`)) }))"
      :active="tab"
      @change="(k: string) => (tab = k as Tab)"
    ></sh-tabs>

    <!-- ══════════ 员工 ══════════ -->
    <template v-if="tab === 'staff'">
      <view class="bar">
        <input v-model="keyword" class="field__input" :placeholder="$t('staff.search')" />
        <text
          class="sh-chip"
          :class="{ 'sh-chip--primary': activeOnly }"
          @tap="activeOnly = !activeOnly"
        >{{ $t("staff.activeOnly") }}</text>
      </view>

      <sh-empty v-if="!visibleStaff.length" :text='$t("staff.empty")'></sh-empty>

      <!-- 一行四样：认人的、状态、他管什么、进详情。**其余全在详情页** -->
      <view v-for="s in visibleStaff" :key="s.mchAccountNo" class="sh-card row" @tap="openStaff(s)">
        <view class="row__main">
          <view class="row__top">
            <text class="row__name">{{ nameOf(s) }}</text>
            <!-- 号码就是他的登录用户名：搜到人之后老板下一眼看的就是这个 -->
            <text v-if="s.displayName && s.loginPhone" class="row__phone sh-num sh-muted">
              {{ s.loginPhone }}
            </text>
            <!-- 名字已经回落成「老板」时不再挂同名标签，两个「老板」并排是噪声 -->
            <text v-if="s.isOwner && nameOf(s) !== $t('staff.owner')" class="tag tag--primary">
              {{ $t("staff.owner") }}
            </text>
            <text v-else-if="s.status !== 'ACTIVE'" class="tag">{{ $t("staff.disabled") }}</text>
          </view>
          <text class="row__sub sh-muted">{{ summary(s) }}</text>
        </view>
        <text class="row__go">›</text>
      </view>

      <view v-if="!adding" class="sh-btn sh-btn--soft add" @tap="adding = true">
        {{ $t("staff.add") }}
      </view>
      <view v-else class="sh-card mt-card">
        <text class="sh-h2">{{ $t("staff.add") }}</text>
        <text class="hint">{{ $t("staff.addHint") }}</text>
        <view class="field">
          <text class="field__label">{{ $t("staff.phone") }}</text>
          <input v-model="form.phone" class="field__input" type="number" maxlength="11" />
        </view>
        <view class="field">
          <text class="field__label">{{ $t("staff.name") }}</text>
          <input v-model="form.name" class="field__input" :placeholder="$t('staff.namePh')" />
        </view>
        <view class="sh-btn save" @tap="add">{{ $t("common.save") }}</view>
        <view class="sh-btn sh-btn--soft mt-s" @tap="adding = false">{{ $t("common.cancel") }}</view>
      </view>
    </template>

    <!-- ══════════ 角色 ══════════ -->
    <template v-else-if="tab === 'roles'">
      <text class="sh-muted tip">{{ $t("staff.roleTip") }}</text>

      <view v-for="r in roles" :key="r.roleCode" class="sh-card row">
        <view class="row__main" @tap="openRole(r)">
          <view class="row__top">
            <text class="row__name">{{ r.name }}</text>
            <text v-if="r.builtin" class="tag">{{ $t("staff.builtin") }}</text>
          </view>
          <text class="row__sub sh-muted">
            {{ permCount(r) }}　{{ $t("staff.roleUsed", { n: r.usedBy }) }}
          </text>
        </view>
        <!-- 预置角色改不了，但可以「以它为起点」建一个自己的 -->
        <text v-if="r.builtin" class="sh-link" @tap="copyRole(r)">{{ $t("staff.copyRole") }}</text>
        <text v-else class="row__go" @tap="openRole(r)">›</text>
      </view>

      <view class="sh-btn sh-btn--soft add" @tap="openRole({ roleCode: '' } as MerchantRole)">
        {{ $t("staff.newRole") }}
      </view>
    </template>

    <!-- ══════════ 审计 ══════════ -->
    <template v-else>
      <text class="sh-muted tip">{{ $t("staff.logTip") }}</text>
      <sh-empty v-if="!logs.length" :text='$t("staff.logsEmpty")'></sh-empty>
      <view v-for="(l, i) in logs" :key="i" class="sh-card log">
        <view class="log__head">
          <text class="log__t sh-num">{{ datetime(l.at) }}</text>
          <text v-if="l.actor" class="sh-muted">{{ l.actor }}</text>
        </view>
        <text class="log__d">{{ l.detail || l.action }}</text>
        <text v-if="l.targetName" class="sh-muted log__who">→ {{ l.targetName }}</text>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin: 20rpx 0;
}
.bar .field__input {
  flex: 1;
}
.tip {
  display: block;
  margin: 20rpx 8rpx;
  font-size: 24rpx;
  line-height: 1.6;
}
.row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 16rpx;
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__top {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.row__name {
  font-size: 30rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__sub {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  line-height: 1.5;
}
.row__phone {
  font-size: 24rpx;
}
.row__go {
  font-size: 34rpx;
  color: var(--sh-sub);
}
.tag {
  padding: 4rpx 14rpx;
  border-radius: 9999px;
  /* --sh-fill 不存在，此前 tag 底色是透明的（与 .sh-chip 同款，用 --sh-faint） */
  background: var(--sh-faint);
  font-size: 24rpx;
  color: var(--sh-sub);
}
.tag--primary {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
.add {
  margin-top: 24rpx;
}
.mt-card {
  margin-top: 16rpx;
}
.mt-s {
  margin-top: 16rpx;
}
.hint {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
.field {
  margin-top: 20rpx;
}
.save {
  margin-top: 28rpx;
}
.log {
  margin-bottom: 16rpx;
}
.log__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.log__t {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.log__d {
  display: block;
  margin-top: 8rpx;
  font-size: 28rpx;
  color: var(--sh-ink);
  line-height: 1.5;
}
.log__who {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
}
</style>
