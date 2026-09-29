<script setup lang="ts">
// 供应商资料（原型 e18）。公司名、类型、城市、联系人、联系电话。空着的字段不改，随时可以来补。
// 顶上那句先回答他心里的问题：填了这些谁看得到。联系手机可以与登录号不同 ——
// 老板用自己的号登录，真正接电话的是业务员。这一屏**没有执照上传**：第一步不做认证。
import { reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api, errMsg, toast } from "@/api";
import { ensureLogin } from "@/shared/auth";
import { SUPPLIER_KIND } from "@/shared/format";
import type { ElecSupplier, ElecSupplierKind } from "@shared/types";

const s = ref<ElecSupplier | null>(null);
const form = reactive({ companyName: "", kind: "TRADER" as ElecSupplierKind, city: "", contactName: "", contactPhone: "" });
const failed = ref("");
const busy = ref(false);
const kinds = Object.entries(SUPPLIER_KIND) as [ElecSupplierKind, string][];

onLoad(async () => {
  if (!(await ensureLogin())) return;
  await load();
});

async function load() {
  failed.value = "";
  try {
    s.value = await api.mySupplier();
    if (s.value) {
      form.companyName = s.value.companyName ?? "";
      form.kind = s.value.kind;
      form.city = s.value.city ?? "";
      form.contactName = s.value.contactName ?? "";
      form.contactPhone = s.value.contactPhone ?? "";
    }
  } catch (e) {
    failed.value = errMsg(e);
  }
}

async function save() {
  if (busy.value) return;
  const phone = form.contactPhone.trim();
  if (phone && !/^1\d{10}$/.test(phone)) {
    toast("联系手机写 11 位手机号");
    return;
  }
  if (form.companyName.trim() && form.companyName.trim().length < 2) {
    toast("公司名称至少两个字");
    return;
  }
  busy.value = true;
  try {
    s.value = await api.updateSupplier({
      companyName: form.companyName.trim() || undefined,
      kind: form.kind,
      city: form.city.trim() || undefined,
      contactName: form.contactName.trim() || undefined,
      contactPhone: phone || undefined,
    });
    toast("已保存");
    setTimeout(() => uni.navigateBack(), 500);
  } catch (e) {
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="title.supplierProfile" :pending="!s && !failed" :failed="!!failed" :failed-text="failed" @retry="load">
    <template v-if="s">
      <view class="sh-notice">
        <text class="txt-sub">买家看不到这些 —— 平台联系你、以后开放身份时才用得上</text>
      </view>
      <view class="sh-card block">
        <text class="txt-sub">公司名称</text>
        <input v-model="form.companyName" class="field__input f" maxlength="60" placeholder="公司名称" />
        <text class="txt-sub gap">类型</text>
        <view class="sh-wrap opts">
          <text v-for="[k, v] in kinds" :key="k" class="sh-seg" :class="{ 'sh-seg--on': form.kind === k }"
            @tap="form.kind = k">{{ v }}</text>
        </view>
        <text class="txt-sub gap">所在城市</text>
        <input v-model="form.city" class="field__input f" maxlength="20" placeholder="所在城市" />
        <text class="txt-sub gap">联系人</text>
        <input v-model="form.contactName" class="field__input f" maxlength="20" placeholder="联系人" />
        <text class="txt-sub gap">联系手机</text>
        <input v-model="form.contactPhone" class="field__input sh-num f" type="number" maxlength="11" placeholder="联系手机" />
        <sh-kv label="我的编号" between divided><text class="sh-num">{{ s.maskCode }}</text></sh-kv>
      </view>
      <text class="txt-caption sh-muted foot">身份公开程度由平台统一控制，现在是完全不公开。将来开放会先问你</text>

      <sh-actionbar>
        <view class="sh-btn" :class="{ 'is-disabled': busy }" @tap="save">{{ busy ? "保存中…" : "保存" }}</view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
/* 库里的输入框不带外边距，纵向间距是版面的事 */
.f {
  margin-top: 16rpx;
}
.block {
  margin-top: 24rpx;
}
.gap {
  display: block;
  margin-top: 28rpx;
}
.opts {
  margin-top: 12rpx;
}
.foot {
  display: block;
  text-align: center;
  padding: 28rpx 12rpx 0;
}
</style>
