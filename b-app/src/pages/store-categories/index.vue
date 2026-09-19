<script setup lang="ts">
// 经营类目 —— 这家门店卖哪几类（TDD-门店经营类目；上游 TDD-品类约束全链路 §三）。
//
// 与「商品的类目」的分工：商品选的是**平台类目**（它决定形态：生鲜要截单、
// 服务不发货），这一页管的是**本店卖哪几类、叫什么名、什么顺序**。
// 两者同一个 categoryNo —— 所以商家改了显示名，跨店比价照样成立。
//
// 2026-09-19 改版：此前这一页把平台整棵类目树铺在上面、本店的压在底部，
// 店主以为那些灰框也是自己的类目。现在只列本店的；加与移出都在「调整经营类目」面板里
// （biz-category-sheet，建商品页也用它）。经营类目也不再被建品自动撑大 —— 不在就拒。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { handOffGoodsCategory } from "@/shared/handoff";
import type { StoreCategory } from "@shared/types";
import { prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();
const picked = ref<StoreCategory[]>([]);
const busy = ref(false);
/** 「调整经营类目」面板开没开 */
const adjusting = ref(false);

/** 当前门店。多门店时经营类目各店各有一份 —— 分店卖的不一定是同一批货 */
const storeNo = computed(() => merchant.storeNo);

/** 商品规格：本店在用哪些规格、各有哪些档位。按类目分组，所以挂在这一页 */
function goSpecs() {
  uni.navigateTo({ url: ROUTES.mySpecs });
}

/**
 * 点一类直接去商品列表，**并且落在这一类上**。
 * <p>商家看得出这一类卖得怎么样，也能从这里直接走到那批货。
 */
function openGoods(no: string) {
  // 商品列表是 tabBar 页，switchTab 不能带参数 —— 参数走交接位（见 shared/handoff）
  handOffGoodsCategory(no);
  uni.switchTab({ url: ROUTES.goods });
}

onShow(load);

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有经营类目」 */
const failed = ref(false);

async function load() {
  try {
    // 没选门店时本来就没有「本店经营类目」可言，这是确定的空，不是没取到
    picked.value = storeNo.value ? await api.mStoreCategories(storeNo.value) : [];
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

/** 面板里每保存成功一次就回来一份最新的 */
function onChange(next: StoreCategory[]) {
  picked.value = next;
}

/** 改显示名。它只是**皮** —— categoryNo 不变，所以跨店聚合与比价都不受影响 */
async function rename(c: StoreCategory) {
  const input = await prompt({
    title: String(t("storeCategories.rename")),
    placeholder: c.platformName,
    value: c.displayName ?? "",
  });
  // 清空 = 回到平台名，是合法操作，不是「叫空字符串」——
  // 所以只有**取消**（null）才提前返回，空串要走下去
  if (input === null) return;
  const name = input.trim();
  if (busy.value || !storeNo.value) return;
  busy.value = true;
  try {
    picked.value = await api.mSaveStoreCategories(storeNo.value, picked.value.map((x, i) => ({
      categoryNo: x.categoryNo,
      displayName: x.categoryNo === c.categoryNo ? name : x.displayName,
      sort: i,
    })));
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
    await load();
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="storeCategories.title" :denied="!merchant.can('biz:store:admin')">
    <!--
      **只列本店的。**平台其余类目收进底部「调整经营类目」面板 ——
      改版前它们铺在这一页上面，标题又叫「我的类目」，店主以为那些灰框也是自己的。
    -->
    <view v-if="picked.length" class="sh-card">
      <view v-for="c in picked" :key="c.categoryNo" class="sh-row sh-row--divided row">
        <view class="sh-fill" @tap="openGoods(c.categoryNo)">
          <text class="txt-body row__name">{{ c.name }}</text>
          <text v-if="c.displayName" class="txt-caption">{{ c.platformName }}</text>
          <text class="txt-caption sh-muted row__stat">
            {{ $t("storeCategories.onSale", { n: c.onSaleCount }) }}
            <template v-if="c.pendingCount">
              · {{ $t("storeCategories.pending", { n: c.pendingCount }) }}
            </template>
            <template v-if="c.goodsCount > c.onSaleCount + c.pendingCount">
              · {{ $t("storeCategories.total", { n: c.goodsCount }) }}
            </template>
          </text>
        </view>
        <text class="sh-link row__act" @tap.stop="rename(c)">{{ $t("storeCategories.rename") }}</text>
      </view>
    </view>
    <text v-if="picked.length" class="sh-hint">{{ $t("storeCategories.scopeHint") }}</text>
    <sh-empty v-else :pending="!loaded" :failed="failed" @retry="load"
      :text='$t("storeCategories.empty")' :tip='$t("storeCategories.emptyTip")'></sh-empty>

    <view v-if="merchant.can('biz:goods')" class="sh-card specs sh-row sh-row--between" @tap="goSpecs">
      <text class="txt-title">{{ $t("storeCategories.specsEntry") }}</text>
      <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
    </view>

    <!-- 这一页的主动作：二级页的主动作放贴底通栏，与建活动、建券同位 -->
    <sh-actionbar v-if="storeNo">
      <view class="sh-btn" @tap="adjusting = true">{{ $t("storeCategories.adjust") }}</view>
    </sh-actionbar>
    <biz-category-sheet
      :visible="adjusting"
      :store-no="storeNo"
      @close="adjusting = false"
      @change="onChange"
    ></biz-category-sheet>
  </sh-scaffold>
</template>

<style scoped>
.row__name {
  flex: 1;
}
/* 改过名时跟在后面的平台原名：与新名字隔开，别读成一个词 */
.row__name + .txt-caption {
  margin-inline-start: 8rpx;
}
.row__stat {
  display: block;
  margin-top: 4rpx;
}
</style>
