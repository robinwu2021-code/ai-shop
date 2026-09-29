<script setup lang="ts">
// 成为供应商（原型 e12）。**一点就成**：不审核、不填表，唯一要确认的是手机号 ——
// 平台要打得通他的电话。第一版这里是七项表单，改掉的理由是这一步什么都还没发生：
// 他连自己的货能不能被搜到都不知道，就要先填执照。
// 顶上那句「身份不公开」是贸易商最在意的事，放在按钮正上方。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api, errMsg, toast } from "@/api";
import { ensurePhone } from "@/shared/auth";
import { useUserStore } from "@/stores/user";
import { ROUTES } from "@/shared/routes";

const user = useUserStore();
const busy = ref(false);

onShow(async () => {
  if (!user.isLogin) return;
  // 已经是供应商就直接去工作台，别让他再点一次「成为」
  const mine = await api.mySupplier().catch(() => null);
  if (mine) {
    uni.redirectTo({ url: ROUTES.supplier });
    return;
  }
  await user.loadProfile().catch(() => null);
});

const masked = computed(() => {
  const p = user.user?.phone ?? "";
  return /^\d{11}$/.test(p) ? `${p.slice(0, 3)}****${p.slice(7)}` : p;
});

async function join() {
  if (busy.value) return;
  if (!(await ensurePhone())) return;
  busy.value = true;
  try {
    await api.joinSupplier();
    uni.redirectTo({ url: ROUTES.supplier });
  } catch (e) {
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}

function back() {
  uni.navigateBack();
}
</script>

<template>
  <sh-scaffold title-key="title.supplierJoin">
    <view class="sh-card">
      <text class="txt-title">把库存传上来，买家搜得到就有询价</text>
      <view class="sh-notice sh-notice--success gap">
        <text class="txt-sub">身份不公开 —— 同行看不到你的名字和库存明细</text>
      </view>
      <sh-kv v-if="user.isLogin" label="手机号" between divided><text class="sh-num">{{ masked || "还没绑手机号" }}</text></sh-kv>
      <text class="txt-caption sh-muted block">平台用这个号联系你。公司名等之后在资料里补</text>
    </view>

    <sh-actionbar>
      <view class="sh-row acts">
        <view class="sh-btn sh-btn--muted grow" @tap="back">再想想</view>
        <view class="sh-btn grow" :class="{ 'is-disabled': busy }" @tap="join">
          {{ user.isLogin ? "就用这个号" : "登录后成为供应商" }}
        </view>
      </view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.gap {
  margin: 24rpx 0;
}
.block {
  display: block;
  margin-top: 12rpx;
}
.acts {
  gap: 20rpx;
  width: 100%;
}
.grow {
  flex: 1;
}
</style>
