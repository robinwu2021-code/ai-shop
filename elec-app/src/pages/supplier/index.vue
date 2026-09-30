<script setup lang="ts">
// 供应商工作台（原型 e13 刚成为供应商 / e14 有库存之后）—— 同一个页面的两态。
//
// 刚成为供应商：第一屏就是「上传库存」，下面三步说清**怎么把文件弄到手机里** ——
// 小程序只能从微信聊天记录选文件，这件事不说，他会在工作台上找「从电脑上传」。
// 有库存之后：最要紧的事变成「别让它过期」，到期提醒顶到最上面、续期提到底栏。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api, errMsg, toast } from "@/api";
import { handleElecError } from "@/shared/errors";
import { ensureLogin } from "@/shared/auth";
import { ROUTES, go } from "@/shared/routes";
import { SUPPLIER_KIND, agoOf, dateOf, qtyOf } from "@/shared/format";
import { rememberRole } from "@/shared/role";
import type { ElecDispatch, ElecMe, ElecSupplier } from "@shared/types";

const s = ref<ElecSupplier | null>(null);
const me = ref<ElecMe | null>(null);
const pending = ref<ElecDispatch[]>([]);
const loaded = ref(false);
const failed = ref("");
const busy = ref(false);

onShow(async () => {
  if (!(await ensureLogin())) return;
  await load();
});

async function load() {
  failed.value = "";
  try {
    s.value = await api.mySupplier();
    if (!s.value) {
      rememberRole("buyer");
      uni.redirectTo({ url: ROUTES.supplierJoin });
      return;
    }
    rememberRole("supplier");
    const [p, m] = await Promise.all([
      api.myDispatches("SENT", 1, 50).catch(() => [] as ElecDispatch[]),
      api.me().catch(() => null),
    ]);
    pending.value = p;
    me.value = m;
  } catch (e) {
    failed.value = errMsg(e);
  } finally {
    loaded.value = true;
  }
}

/** 待回的求购：待报价 + 看过没回。以 /elec/me 为准（与切换条红点同一个口径），拿不到退回只数「待报价」 */
const pendingCnt = computed(() => me.value?.badges.dispatchPending ?? pending.value.length);
/** 被暂停：工作台只读（上传、续期都会被后端 90004 拒掉，按钮就别给了） */
const suspended = computed(() => s.value?.status === "SUSPENDED");
const fresh = computed(() => !!s.value && !s.value.lastUploadAt && s.value.onCount === 0);
const stats = computed(() => {
  const x = s.value;
  if (!x) return [];
  return [
    { key: "on", value: qtyOf(x.onCount), label: "在售料号" },
    { key: "exp", value: qtyOf(x.expiringCount), label: "7 天内到期" },
    { key: "last", value: x.lastUploadAt ? agoOf(x.lastUploadAt) : "—", label: "上次上传" },
  ];
});

async function renew() {
  if (busy.value) return;
  busy.value = true;
  try {
    const r = await api.renewStocks();
    toast(`已续 ${qtyOf(r.renewed)} 行，到 ${dateOf(r.validUntil)}`);
    await load();
  } catch (e) {
    handleElecError(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="title.supplier" :pending="!loaded" :failed="!!failed" :failed-text="failed" @retry="load">
    <el-role-switch active="supplier" :me="me"></el-role-switch>
    <template v-if="s">
      <view v-if="s.status === 'SUSPENDED'" class="sh-notice sh-notice--danger gap-b">
        <text class="txt-sub">你的供应商身份已被暂停，库存暂时不给买家看。有疑问联系平台</text>
      </view>

      <!-- e13：刚成为供应商 -->
      <template v-if="fresh">
        <view class="sh-notice gap-b">
          <text class="txt-sub">还没传过库存 —— 传一张表，买家就搜得到你的货</text>
        </view>
        <view class="sh-card">
          <view class="sh-btn" :class="{ 'is-disabled': suspended }" @tap="suspended || go(ROUTES.stockUpload)">上传库存</view>
          <text class="txt-caption sh-muted center">Excel / CSV，列自动认</text>
          <text class="txt-strong how">怎么传</text>
          <view class="step"><text class="num">1</text><text class="txt-sub">从 ERP 导出库存表（xlsx / csv）</text></view>
          <view class="step"><text class="num">2</text><text class="txt-sub">在微信里发给自己，或存到聊天</text></view>
          <view class="step"><text class="num">3</text><text class="txt-sub">回这里点「上传库存」，选那个文件</text></view>
        </view>
      </template>

      <!-- e14：有库存之后 -->
      <template v-else>
        <view v-if="s.expiringCount" class="sh-notice sh-notice--warning gap-b">
          <text class="txt-sub">{{ qtyOf(s.expiringCount) }} 行 7 天内到期 —— 到期就不再给买家看了</text>
        </view>
        <view class="sh-card">
          <sh-stat :items="stats"></sh-stat>
        </view>
        <view class="sh-cells block">
          <view v-if="!suspended" class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.stockUpload)">
            <text class="txt-body">上传库存</text>
            <text class="txt-caption sh-muted">增量或全量替换 ›</text>
          </view>
          <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.stocks)">
            <text class="txt-body">我的库存</text>
            <text class="txt-caption sh-muted">{{ qtyOf(s.onCount) }} 行 ›</text>
          </view>
        </view>
      </template>

      <view class="sh-cells block">
        <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.dispatches)">
          <text class="txt-body">求购</text>
          <text class="txt-caption" :class="pendingCnt ? 'txt-primary' : 'sh-muted'">
            {{ pendingCnt ? `${pendingCnt} 条待回` : "买家要的货派给你" }} ›
          </text>
        </view>
      </view>

      <view class="sh-card block" @tap="go(ROUTES.supplierProfile)">
        <view class="sh-row sh-row--between">
          <text class="txt-strong">资料</text>
          <text class="sh-link">{{ s.companyName ? "去修改" : "补全" }} ›</text>
        </view>
        <sh-kv label="公司名称" divided>{{ s.companyName || "还没填" }}</sh-kv>
        <sh-kv v-if="s.companyName" label="类型 · 城市" divided>{{ [SUPPLIER_KIND[s.kind], s.city].filter(Boolean).join(" · ") }}</sh-kv>
        <sh-kv label="我的编号" divided><text class="sh-num">{{ s.maskCode }}</text></sh-kv>
      </view>
      <text class="txt-caption sh-muted center foot">库存 {{ s.stockTtlDays }} 天不更新就不再给买家看 —— 货会卖掉，旧库存会让买家询到不存在的货</text>

      <sh-actionbar v-if="!fresh && s.expiringCount && !suspended">
        <view class="sh-btn" :class="{ 'is-disabled': busy }" @tap="renew">
          {{ busy ? "续期中…" : `${qtyOf(s.expiringCount)} 行还有货，续 ${s.stockTtlDays} 天` }}
        </view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.gap-b {
  margin-bottom: 24rpx;
}
.block {
  margin-top: 24rpx;
}
.center {
  display: block;
  text-align: center;
  margin-top: 12rpx;
}
.how {
  display: block;
  margin-top: 36rpx;
}
.step {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 16rpx;
}
.num {
  width: 40rpx;
  height: 40rpx;
  line-height: 40rpx;
  text-align: center;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  font-size: 22rpx;
}
.foot {
  padding: 28rpx 12rpx 0;
}
</style>
