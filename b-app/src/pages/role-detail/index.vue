<script setup lang="ts">
// 角色详情（V71 自定义角色）。三种进入方式共用一页：
//
//   ?code=R…        改一个自定义角色
//   ?code=MANAGER   看一个预置角色（只读）
//   ?copyFrom=CLERK 以预置角色为起点建新的 —— **比从空白勾 13 个码容易得多**
//   （都不带）        从空白建
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import type { MerchantRole, MerchantStaff, PermOption } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t, te } = useI18n();

/**
 * 权限码 → 人话。**界面上不出现 `biz:xxx`** —— 老板看的是「能不能改价」。
 *
 * 先查本地 i18n（有中/英/阿三份），查不到才退到后端下发的中文标签，
 * 再查不到才是码本身。后端加了新码而这里还没跟上时，
 * 宁可显示一个中文标签，也不要在界面上甩一个常量给用户。
 */
const label = (code: string, fallback?: string) =>
  te(`perm.${code}`) ? String(t(`perm.${code}`)) : (fallback || code);
const merchant = useMerchantStore();

const roleCode = ref("");
const copyFrom = ref("");
const roles = ref<MerchantRole[]>([]);
const staff = ref<MerchantStaff[]>([]);
const perms = ref<PermOption[]>([]);
const busy = ref(false);

const name = ref("");
const picked = ref<string[]>([]);

const current = computed(() => roles.value.find((r) => r.roleCode === roleCode.value) ?? null);
const readonly = computed(() => !!current.value?.builtin);
const isNew = computed(() => !current.value);

/**
 * 可勾的权限点 —— **由后端给**（`/biz/role-perms`），不在这里拼。
 *
 * 拼过一版：把 6 个预置角色的权限并起来当选项，**少一条** ——
 * `biz:finance` 只有老板有，而老板那行是 `*`。后端收这个码，界面却勾不到。
 *
 * `biz:store:admin` 后端就不下发。界面上不出现是体验，
 * 后端建/改时还会再拒一次（70006）—— 端点是公开的。
 */
const options = computed(() =>
  perms.value.map((o) => ({ code: o.code, label: label(o.code, o.label) })),
);

const openStaff = (no: string) =>
  uni.navigateTo({ url: `${ROUTES.staffDetail}?no=${no}` });

/** 谁在用：可点进员工详情。删不掉时这张列表就是「去把谁撤下来」的清单 */
const holders = computed(() =>
  staff.value.filter((s) => s.roles.some((r) => r.role === roleCode.value)),
);

async function load() {
  [roles.value, staff.value, perms.value] = await Promise.all([
    api.mRoles().catch(() => []),
    api.mStaffList().catch(() => []),
    api.mRolePerms().catch(() => []),
  ]);
  const base = current.value
    ?? roles.value.find((r) => r.roleCode === copyFrom.value);
  if (base) {
    name.value = current.value ? base.name : t("staff.copyOf", { name: base.name });
    picked.value = base.perms.filter((p) => p !== "*" && p !== "biz:store:admin");
  }
}

const toggle = (code: string) => {
  if (readonly.value) return;
  picked.value = picked.value.includes(code)
    ? picked.value.filter((c) => c !== code)
    : [...picked.value, code];
};

async function save() {
  if (!name.value.trim()) {
    uni.showToast({ title: t("staff.roleNeedName"), icon: "none" });
    return;
  }
  if (!picked.value.length) {
    // 一个权限都没有的角色是个陷阱：授出去等于没授，而老板以为授了
    uni.showToast({ title: t("staff.roleNeedPerm"), icon: "none" });
    return;
  }
  if (busy.value) return;
  busy.value = true;
  const payload = { name: name.value.trim(), perms: [...picked.value] };
  try {
    if (isNew.value) {
      const r = await api.mCreateRole(payload);
      roleCode.value = r.roleCode;
      copyFrom.value = "";
    } else {
      await api.mUpdateRole(roleCode.value, payload);
    }
    uni.showToast({ title: t("common.saved"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function remove() {
  const r = current.value;
  if (!r || r.builtin) return;
  if (r.usedBy > 0) {
    // 按钮本身禁用了，这里是兜底：说清「为什么删不了」比让按钮无反应强
    uni.showToast({ title: t("staff.roleInUse", { n: r.usedBy }), icon: "none" });
    return;
  }
  const ok = await confirm({ title: String(t("staff.roleDelete")), hint: String(t("staff.roleDeleteHint")), danger: true });
  if (!ok) return;
  try {
    await api.mDeleteRole(r.roleCode);
    uni.navigateBack();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

onLoad((q) => {
  roleCode.value = (q?.code as string) ?? "";
  copyFrom.value = (q?.copyFrom as string) ?? "";
});
onShow(load);
</script>

<template>
  <sh-scaffold title-key="staff.roleDetailTitle" :denied="!merchant.can('biz:store:admin')">
    <view class="sh-card">
      <view class="field">
        <text class="field__label">{{ $t("staff.roleName") }}</text>
        <input
          maxlength="64"
          v-model="name"
          class="field__input"
          :disabled="readonly"
          :placeholder="$t('staff.roleNamePh')"
        />
      </view>
      <text v-if="readonly" class="sh-muted sh-hint">{{ $t("staff.builtinHint") }}</text>
    </view>

    <view class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("staff.rolePermTitle") }}</text>
      <!-- 管员工的权限为什么不在这里，页面上直说 —— 不解释的话下一个人会以为是漏了 -->
      <text class="sh-muted sh-hint">{{ $t("staff.rolePermHint") }}</text>
      <view class="chips">
        <text
          v-for="o in options"
          :key="o.code"
          class="sh-chip"
          :class="{ 'sh-chip--primary': picked.includes(o.code), 'is-readonly': readonly }"
          @tap="toggle(o.code)"
        >{{ o.label }}</text>
      </view>
    </view>

    <view v-if="!isNew && holders.length" class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("staff.roleHolders", { n: holders.length }) }}</text>
      <view
        v-for="h in holders"
        :key="h.mchAccountNo"
        class="holder"
        @tap="openStaff(h.mchAccountNo)"
      >
        <text>{{ h.displayName || h.loginPhone }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <view v-if="!readonly" class="sh-btn save" @tap="save">{{ $t("common.save") }}</view>

    <view
      v-if="!isNew && !readonly"
      class="sh-btn sh-btn--danger del"
      :class="{ 'is-disabled': (current?.usedBy ?? 0) > 0 }"
      @tap="remove"
    >
      {{ (current?.usedBy ?? 0) > 0
        ? $t("staff.roleInUse", { n: current?.usedBy ?? 0 })
        : $t("staff.roleDelete") }}
    </view>
  </sh-scaffold>
</template>

<style scoped>

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 20rpx;
}
.is-readonly {
  opacity: 0.85;
}
.holder {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 0;
  border-top: var(--sh-hairline);
  font-size: 24rpx;
  color: var(--sh-ink);
}
.save {
  margin-top: 24rpx;
}
.del {
  margin-top: 16rpx;
  color: var(--sh-danger);
}
.is-disabled {
  opacity: 0.5;
}
</style>
