<script setup lang="ts">
// 分类：三品类切换 + 列表。M0 只做品类维度，三级分类树在 M1 补。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCartStore } from "@/stores/cart";
import { useCommunityStore } from "@/stores/community";
import { GOODS_COVER_FALLBACK, CATEGORY_TYPE, ROUTES } from "@shared/utils/constants";
import { firstBuyableSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import type { CategoryType, Goods } from "@shared/types";

const cart = useCartStore();
const community = useCommunityStore();
const active = ref<CategoryType>(CATEGORY_TYPE.FRESH);
const list = ref<Goods[]>([]);

const tabs = [
  { type: CATEGORY_TYPE.FRESH, key: "fresh" },
  { type: CATEGORY_TYPE.NORMAL, key: "goods" },
  { type: CATEGORY_TYPE.SERVICE, key: "service" },
];
/*
 * 一期分类只留生鲜 / 日用 / 服务这三个「能在楼下买到」的。
 * 虚拟商品和卡券**不做入口，但模型保留**：
 *   · 虚拟商品（话费、会员）没有任何社区属性 —— 邻居的平台不该跟支付宝抢这单生意；
 *   · 卡券不是品类而是**销售形式** —— 理发次卡本质是「理发服务」的预售，
 *     它属于那家店的店铺页和服务品类，不属于一个独立频道。
 *     人的决策链是「我要理发」→「办卡划算」，没有人会去逛「卡券」。
 * 两者的 CATEGORY_TYPE / 履约策略 / 商品数据全部保留，商家照常可以上架，
 * 只是走店铺页与搜索触达。二期若真有量，再决定给不给独立入口。
 */

async function load() {
  // 与首页同一条约束：送不到我这个社区的商品不该出现在「逛」的场景里 ——
  // 让人点进去才发现没法自提，比一开始就不展示更糟。
  // （搜索页不加这个限制：那是**主动找特定商家**，用户自己清楚在找什么。）
  const res = await api.goodsList({
    type: active.value,
    size: 50,
    communityNo: community.community?.communityNo,
  });
  list.value = res.records;
}

function switchTab(type: CategoryType) {
  active.value = type;
  load();
}

async function add(g: Goods, e: unknown) {
  try {
    await cart.add(g.goodsNo, firstBuyableSku(g).skuNo, 1);
    const p = tapPoint(e as Parameters<typeof tapPoint>[0]);
    flyToCart(p.x, p.y, g.cover || GOODS_COVER_FALLBACK);
  } catch (err) {
    uni.showToast({ title: (err as Error).message, icon: "none" });
  }
}

function gotoSearch() {
  uni.navigateTo({ url: ROUTES.search });
}

function openGoods(g: Goods) {
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${g.goodsNo}` });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="tab.category" tab="category">
    <view class="searchentry" @tap="gotoSearch">
      <text class="txt-sub">{{ $t("search.placeholder") }}</text>
    </view>

    <view class="sh-block">
      <view class="sh-block__head sh-block__head--tabs">
        <sh-tabs
          :items="
            tabs.map((t) => ({
              key: t.type,
              label: String($t(`category.${t.key}`)),
            }))
          "
          :active="active"
          @change="switchTab"
        ></sh-tabs>
      </view>

      <biz-goods-card
        v-for="g in list"
        :key="g.goodsNo"
        :goods="g"
        @add="add(g, $event)"
        @tap="openGoods(g)"
      ></biz-goods-card>

      <!-- 加了社区过滤之后，「空」的含义变了：不是没上架，是**这个社区没人做这门生意**。
           照旧显示「还没有内容」会让人以为 App 坏了 -->
      <sh-empty
        bare
        v-if="!list.length"
        :text="$t('category.emptyInCommunity')"
      ></sh-empty>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.searchentry {
  background: var(--sh-surface);
  border-radius: 9999px;
  padding: 24rpx 32rpx;
  margin-bottom: 24rpx;
}
</style>
