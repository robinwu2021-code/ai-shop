<script setup lang="ts">
// 我的类目 —— 商家给自己的店摆货架（TDD-品类约束全链路 §三）。
//
// 与「商品的类目」的分工：商品选的是**平台类目**（它决定形态：生鲜要截单、
// 服务不发货），这一页管的是**本店把哪几类摆出来、叫什么名、什么顺序**。
// 两者同一个 categoryNo —— 所以商家改了显示名，跨店比价照样成立。
//
// 一个都没摆是合法状态（新店还没建过货）：建品时会自动把那一类加进来，
// 所以这一页更像「事后整理」，不是「开工前必填」。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { handOffGoodsCategory } from "@/shared/handoff";
import type { Category, StoreCategory } from "@shared/types";

/**
 * 撤架被拒（后端 `STORE_CATEGORY_IN_USE`）。
 *
 * **它与别的保存失败不是一类**：改什么都一样被拒，商家要做的是先把商品移走。
 * 按普通 toast 处理的话，他会反复点保存。
 */
const IN_USE = 80008;
/** 没那张证（后端 `CATEGORY_NOT_AUTHORIZED`）。出路是去申请资质，不是改这一页 */
const NOT_AUTHORIZED = 70002;

const { t } = useI18n();
const merchant = useMerchantStore();

const tree = ref<Category[]>([]);
const picked = ref<StoreCategory[]>([]);
const busy = ref(false);

/** 当前门店。多门店时货架各店各有一份 —— 分店卖的不一定是同一批货 */
const storeNo = computed(() => merchant.storeNo);

/** 已摆的编号集合，勾选态与「撤架代价」都读它 */
const pickedNos = computed(() => new Set(picked.value.map((c) => c.categoryNo)));
/** 这个类目要证、而主体还没有 —— 与后端 requireSelectable 同一条判据 */
const ungranted = (c: Category) =>
  !!c.requiredCode && !merchant.categoryCodes.includes(c.requiredCode);

const countOf = (no: string) =>
  picked.value.find((c) => c.categoryNo === no)?.goodsCount ?? 0;

/** 已摆的那一类的经营情况，用来在勾选行右边显示「在售 N · 待审 M」 */
const statOf = (no: string) => picked.value.find((c) => c.categoryNo === no);

/**
 * 点一类直接去商品列表，**并且落在这一类上**。
 *
 * <p>这一页从前只是个勾选框：商家看不出这一类卖得怎么样，
 * 也无从从这里走到那批货 —— 他得回首页、进商品、再在筛选条里选同一个类目。
 */
function openGoods(no: string) {
  // 商品列表是 tabBar 页，switchTab 不能带参数 —— 参数走交接位（见 shared/handoff）
  handOffGoodsCategory(no);
  uni.switchTab({ url: ROUTES.goods });
}

onShow(load);

async function load() {
  tree.value = await api.mCategoryTree().catch(() => []);
  if (!storeNo.value) {
    picked.value = [];
    return;
  }
  picked.value = await api.mStoreCategories(storeNo.value).catch(() => []);
}

/**
 * 勾选即保存 —— 不做「改完再统一提交」。
 *
 * 理由是拒绝要**当场**发生：撤一个有货的类目、摆一个没证的类目，
 * 都要在他点的那一下说清楚。攒到最后一起提交的话，一次拒绝会把
 * 整屏改动一起打回，而他并不知道是哪一条惹的。
 */
async function toggle(c: Category) {
  if (busy.value || !storeNo.value) return;
  const has = pickedNos.value.has(c.categoryNo);
  const next = has
    ? picked.value.filter((x) => x.categoryNo !== c.categoryNo)
    : [...picked.value, { categoryNo: c.categoryNo, displayName: undefined }];
  await save(next.map((x, i) => ({
    categoryNo: x.categoryNo,
    displayName: "displayName" in x ? x.displayName : undefined,
    sort: i,
  })));
}

/** 改显示名。它只是**皮** —— categoryNo 不变，所以跨店聚合与比价都不受影响 */
function rename(c: StoreCategory) {
  uni.showModal({
    title: String(t("storeCategories.rename")),
    editable: true,
    placeholderText: c.platformName,
    content: c.displayName ?? "",
    success: (r) => {
      if (!r.confirm) return;
      const name = (r.content ?? "").trim();
      save(picked.value.map((x, i) => ({
        categoryNo: x.categoryNo,
        // 清空 = 回到平台名，是合法操作，不是「叫空字符串」
        displayName: x.categoryNo === c.categoryNo ? name : x.displayName,
        sort: i,
      })));
    },
  });
}

async function save(items: { categoryNo: string; displayName?: string; sort: number }[]) {
  if (busy.value || !storeNo.value) return;
  busy.value = true;
  try {
    picked.value = await api.mSaveStoreCategories(storeNo.value, items);
  } catch (e) {
    const code = (e as { code?: number }).code;
    const msg =
      code === IN_USE ? t("storeCategories.inUse")
        : code === NOT_AUTHORIZED ? t("storeCategories.notAuthorized")
          : (e as Error).message;
    uni.showToast({ title: String(msg), icon: "none" });
    // 拒绝之后回读：本地那份已经被乐观地改过了，不回读的话界面会停在一个库里没有的状态
    await load();
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="storeCategories.title" :denied="!merchant.can('biz:store:admin')">
    <view class="head">
      <text class="sh-h1">{{ $t("storeCategories.title") }}</text>
      <text class="sh-muted mt">{{ $t("storeCategories.hint") }}</text>
    </view>

    <view v-for="top in tree" :key="top.categoryNo" class="sh-card grp">
      <text class="sh-h2">{{ top.name }}</text>
      <view class="opts">
        <!--
          只列二级（平台类目就是两级）。一级是**分组标题，不是可选项** ——
          让它可选的话，「食品生鲜」这种大筐会成为最省事的选择，
          而店铺页里的货架分类就此失去意义。
        -->
        <view
          v-for="c in top.children ?? []"
          :key="c.categoryNo"
          class="opt"
          :class="{ 'opt--on': pickedNos.has(c.categoryNo) }"
          @tap="toggle(c)"
        >
          <text class="opt__name">{{ c.name }}</text>
          <!-- 有货的数量要显眼：撤架之前商家要看得见代价（有货就撤不掉） -->
          <text v-if="countOf(c.categoryNo)" class="opt__n">
            {{ $t("storeCategories.goodsCount", { n: countOf(c.categoryNo) }) }}
          </text>
          <!--
            没那张证的类目**先标出来**，别等他勾完一屏再报 70002 ——
            那句「缺少经营这一类的资质」既说不出缺哪张，也说不出去哪申请。
          -->
          <text v-if="ungranted(c)" class="opt__gate">{{ $t("storeCategories.needCert") }}</text>
        </view>
      </view>
    </view>

    <view v-if="picked.length" class="sh-card">
      <text class="sh-h2">{{ $t("storeCategories.mine") }}</text>
      <text class="sh-muted mt">{{ $t("storeCategories.renameHint") }}</text>
      <view v-for="c in picked" :key="c.categoryNo" class="row">
        <!--
          点名字进商品（落在这一类上），点右边「改名」才是改名。
          从前整行都是改名 —— 而商家在这一页最常想做的其实是
          「看看这一类的货」，那条路从这里根本走不通。
        -->
        <view class="row__main" @tap="openGoods(c.categoryNo)">
          <text class="row__name">{{ c.name }}</text>
          <!-- 改过名的要标出来：只显示新名字的话，商家找不回平台原来叫什么 -->
          <text v-if="c.displayName" class="row__from">{{ c.platformName }}</text>
          <!--
            三个数分开给，因为它们回答的是不同问题：在售 = 卖得怎么样，
            待审 = 「为什么这一类看起来没货」的常见答案，
            而括号里的总数才是「能不能撤架」看的那个。
          -->
          <text class="sh-muted row__stat">
            {{ $t("storeCategories.onSale", { n: c.onSaleCount }) }}
            <template v-if="c.pendingCount">
              · {{ $t("storeCategories.pending", { n: c.pendingCount }) }}
            </template>
            <template v-if="c.goodsCount > c.onSaleCount + c.pendingCount">
              · {{ $t("storeCategories.total", { n: c.goodsCount }) }}
            </template>
          </text>
        </view>
        <text class="row__act" @tap.stop="rename(c)">{{ $t("storeCategories.rename") }}</text>
      </view>
    </view>

    <sh-empty v-else :text='$t("storeCategories.empty")'></sh-empty>
  </sh-scaffold>
</template>

<style scoped>
.head {
  padding: 24rpx 32rpx 8rpx;
}

.mt {
  display: block;
  margin-top: 8rpx;
}

.grp {
  margin-top: 24rpx;
}

.opts {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 16rpx;
}

.opt {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 12rpx 24rpx;
  border: 2rpx solid var(--sh-line);
  border-radius: 16rpx;
}

.opt--on {
  border-color: var(--sh-primary);
  background: var(--sh-primary-tint);
}

.opt__name {
  font-size: 26rpx;
  color: var(--sh-ink);
}

.opt__gate {
  font-size: 24rpx;
  color: var(--sh-warning);
}

.opt__n {
  font-size: 24rpx;
  color: var(--sh-sub);
}

.row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx 0;
  border-top: 2rpx solid var(--sh-line);
}

.row__main {
  flex: 1;
}

.row__stat {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
}

.row__name {
  flex: 1;
  font-size: 28rpx;
  color: var(--sh-ink);
}

.row__from {
  font-size: 24rpx;
  color: var(--sh-sub);
}

.row__act {
  font-size: 24rpx;
  color: var(--sh-primary);
}
</style>
