<script setup lang="ts">
// 我的规格 —— **这家店能用哪些规格**，平台给的与自己建的都在这里。
//
// 第一版只列自建的，而线上自建规格是 0 条（这功能很少用）——
// 于是这一页对**所有**商家都是空的，回答不了他真正的问题：「我能用哪些」。
// 自建只是其中一小部分，把它当成整页的主题是把次要的东西放大了。
//
// 现在分两段：
//   · 本店类目的规格 —— **按货架类目分组**，而不是把平台那 13 个通用维度全倒出来：
//     一家只卖蔬菜和肉的店，看到「尺码」「口径」「时长」是纯噪音，
//     而噪音会让他觉得这一页与自己无关。按他真正摆出来的类目给，每一行他都认得。
//     这一段也保证页面不空
//   · 我建的 —— 可改名、可停用，带**用量**（动它会影响多少）与配额
//
// 自建这条路此前是单向的：建品页里输个名字就落进规格库，之后没有任何地方
// 看得到它。建错了只能一直留着，还占着配额，而配额用完那句「不能再建了」
// 也说不清是被什么占了。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { MerchantSpecDim, StoreCategorySpecs } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const dims = ref<MerchantSpecDim[]>([]);
/** 本店货架类目各自能用的规格。只读，但它让这一页永远不空 */
const byCategory = ref<StoreCategorySpecs[]>([]);
const loading = ref(false);

/** 配额用量取第一条就够 —— 三个配额字段对同一家店是同一份 */
const quota = computed(() => dims.value[0]);
const active = computed(() => dims.value.filter((d) => d.status === "ACTIVE"));
const archived = computed(() => dims.value.filter((d) => d.status !== "ACTIVE"));

async function load() {
  loading.value = true;
  try {
    // 两段一起拉。平台那段取不到不该让整页空着，所以各自兜底
    const [mine, byCat] = await Promise.all([
      api.mMySpecDims(),
      // 取不到不该让整页空着 —— 自建那段还有内容
      api.mStoreSpecDims(merchant.storeNo || undefined).catch(() => []),
    ]);
    dims.value = mine;
    byCategory.value = byCat;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

async function rename(d: MerchantSpecDim) {
  const name = await new Promise<string>((resolve) => {
    uni.showModal({
      title: t("mySpecs.renameTitle"),
      editable: true,
      placeholderText: d.name,
      success: (r) => resolve(r.confirm ? (r.content ?? "") : ""),
      fail: () => resolve(""),
    });
  });
  if (!name.trim() || name.trim() === d.name) return;
  try {
    await api.mRenameSpecDim(d.dimNo, name.trim());
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 停用 / 启用。**用在商品上的要先问一句** —— 停用不会改动那些商品
 * （它们存的是规格快照），但商家不知道这一点，看到「用在 8 件商品上」
 * 会以为自己正要弄坏什么。把后果说清楚，比拦着他更有用。
 */
async function toggle(d: MerchantSpecDim) {
  const off = d.status === "ACTIVE";
  if (off && d.usedCount > 0) {
    const ok = await new Promise<boolean>((resolve) => {
      uni.showModal({
        title: t("mySpecs.archiveTitle"),
        content: t("mySpecs.archiveConfirm", { n: d.usedCount }),
        success: (r) => resolve(!!r.confirm),
        fail: () => resolve(false),
      });
    });
    if (!ok) return;
  }
  try {
    await api.mArchiveSpecDim(d.dimNo, off);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

onShow(() => void load());
</script>

<template>
  <sh-scaffold title-key="mySpecs.title" :denied="!merchant.can('biz:goods')">
    <text class="sh-muted intro">{{ $t("mySpecs.intro") }}</text>

    <!--
      平台规格摆在前面：**它才是大多数人该用的那些**。
      放在自建后面的话，一个自建为空的商家先看到的仍是一片空白。
    -->
    <view v-for="g in byCategory" :key="g.categoryNo" class="sh-card mt">
      <text class="sh-h2">{{ g.categoryName }}</text>
      <view v-for="t in g.dims" :key="t.templateNo" class="pf">
        <text class="pf__name">{{ t.name }}</text>
        <text class="sh-muted pf__vals">{{ t.options.map((o) => o.label).join(" · ") }}</text>
      </view>
      <!--
        没配规格的类目**也留着**，并说清这是平台那边的缺口 ——
        不显示的话商家只会觉得「这一类怎么没有规格」，而问不出来问谁。
      -->
      <text v-if="!g.dims.length" class="sh-muted pf__vals">{{ $t("mySpecs.catNoDims") }}</text>
    </view>

    <sh-empty v-if="!loading && !byCategory.length" :text='$t("mySpecs.noShelf")'></sh-empty>

    <view class="sh-card mt">
      <text class="sh-h2">{{ $t("mySpecs.mineTitle") }}</text>
      <text v-if="quota" class="sh-muted pf__vals">
        {{ quota.dimUsed }} / {{ quota.dimQuota }}
      </text>
      <sh-empty v-if="!loading && !dims.length" :text='$t("mySpecs.empty")'></sh-empty>
    </view>

    <view v-for="d in active" :key="d.dimNo" class="sh-card mt dim">
      <view class="dim__head">
        <text class="dim__name">{{ d.name }}</text>
        <!-- 用量是这一页的重点：它回答「动它会影响多少」 -->
        <text class="sh-muted dim__used">{{ $t("mySpecs.used", { n: d.usedCount }) }}</text>
      </view>
      <view class="dim__vals">
        <text v-for="v in d.values" :key="v.code || v.label" class="sh-chip">{{ v.label }}</text>
        <text v-if="!d.values.length" class="sh-muted">{{ $t("mySpecs.noValues") }}</text>
      </view>
      <view class="dim__acts">
        <text class="link" @tap="rename(d)">{{ $t("mySpecs.rename") }}</text>
        <text class="link" @tap="toggle(d)">{{ $t("mySpecs.archive") }}</text>
      </view>
    </view>

    <!-- 停用的收在下面：它们不该和在用的抢注意力，但要看得到（能启用回来） -->
    <view v-if="archived.length" class="sh-card mt">
      <text class="sh-h2">{{ $t("mySpecs.archivedTitle") }}</text>
      <view v-for="d in archived" :key="d.dimNo" class="row">
        <text class="row__name">{{ d.name }}</text>
        <text class="link" @tap="toggle(d)">{{ $t("mySpecs.unarchive") }}</text>
      </view>
    </view>

    <text class="sh-muted foot">{{ $t("mySpecs.foot") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.intro {
  display: block;
  font-size: 24rpx;
}
.mt {
  margin-top: 20rpx;
}
.pf {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  padding: 10rpx 0;
}
.pf__name {
  min-width: 120rpx;
  font-size: 28rpx;
}
.pf__vals {
  font-size: 24rpx;
}
.dim__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.dim__name {
  font-size: 30rpx;
  font-weight: 600;
}
.dim__used {
  font-size: 24rpx;
}
.dim__vals {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.dim__acts {
  display: flex;
  gap: 28rpx;
  margin-top: 16rpx;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12rpx 0;
}
.row__name {
  font-size: 28rpx;
}
.foot {
  display: block;
  margin: 24rpx 0;
  font-size: 24rpx;
}
</style>
