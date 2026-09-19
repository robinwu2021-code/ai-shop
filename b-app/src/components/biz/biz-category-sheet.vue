<script setup lang="ts">
/**
 * 调整经营类目 —— 经营类目页与建商品页共用的底部面板（TDD-门店经营类目 §4.2）。
 *
 * <p>平台类目按一级分组铺开，<b>点灰的加入、点亮的移出</b>，一下一保存。
 * 保存与报错处理原样取自改版前的「我的类目」页：拒绝要当场发生 ——
 * 撤一个有货的类目、摆一个没证的类目，都要在他点的那一下说清楚；
 * 攒到最后一起提交，一次拒绝会把整屏改动一起打回，而他不知道是哪一条惹的。
 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { SHOW_CATEGORY_GATE } from "@/shared/flags";
import type { Category, StoreCategory } from "@shared/types";

/** 撤一个底下还有商品的类目（后端 `STORE_CATEGORY_IN_USE`）：出路是先把商品移走，不是再点一次 */
const IN_USE = 80008;
/** 没那张证（后端 `CATEGORY_NOT_AUTHORIZED`）：出路是去补资质 */
const NOT_AUTHORIZED = 70002;

const props = defineProps<{ visible: boolean; storeNo: string }>();
/**
 * change：每保存成功一次就发，带上最新的经营类目；
 * added 是这一下新加进来的那一类（移出时为 null）—— 建商品页据此把它回填为选中。
 */
const emit = defineEmits<{ close: []; change: [picked: StoreCategory[], added: string | null] }>();

const { t } = useI18n();
const merchant = useMerchantStore();
const tree = ref<Category[]>([]);
const picked = ref<StoreCategory[]>([]);
const busy = ref(false);

const pickedNos = computed(() => new Set(picked.value.map((c) => c.categoryNo)));
const countOf = (no: string) => picked.value.find((c) => c.categoryNo === no)?.goodsCount ?? 0;

/**
 * 「需资质」只对<b>第三方门店</b>标。自营门店后端不判资质（按门店的经营模式），
 * 标了就是在制造一个不存在的障碍。老后端不发经营模式时按「不是自营」—— 宁可多提示。
 * 其余判据与改版前相同：闸门开着、或要求善意提醒时才标。
 */
const selfOperated = computed(() => merchant.currentStore?.businessMode === "SELF_OPERATED");
const ungranted = (c: Category) =>
  !selfOperated.value
  && (merchant.categoryGateEnforced || SHOW_CATEGORY_GATE)
  && !!c.requiredCode && !merchant.categoryCodes.includes(c.requiredCode);

async function load() {
  if (!props.storeNo) return;
  try {
    const [tr, pk] = await Promise.all([api.mCategoryTree(), api.mStoreCategories(props.storeNo)]);
    tree.value = tr;
    picked.value = pk;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

watch(() => props.visible, (v) => { if (v) void load(); }, { immediate: true });

async function toggle(c: Category) {
  if (busy.value || !props.storeNo) return;
  const has = pickedNos.value.has(c.categoryNo);
  const next = has
    ? picked.value.filter((x) => x.categoryNo !== c.categoryNo)
    : [...picked.value, { categoryNo: c.categoryNo, displayName: undefined } as StoreCategory];
  busy.value = true;
  try {
    picked.value = await api.mSaveStoreCategories(props.storeNo, next.map((x, i) => ({
      categoryNo: x.categoryNo,
      displayName: x.displayName,
      sort: i,
    })));
    emit("change", picked.value, has ? null : c.categoryNo);
  } catch (e) {
    const code = (e as { code?: number }).code;
    const msg =
      code === IN_USE ? t("storeCategories.inUse")
        : code === NOT_AUTHORIZED ? t("storeCategories.notAuthorized")
          : (e as Error).message;
    uni.showToast({ title: String(msg), icon: "none" });
    // 拒绝之后回读：不回读的话界面会停在一个库里没有的状态
    picked.value = await api.mStoreCategories(props.storeNo).catch(() => picked.value);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-sheet :visible="visible" :title="t('storeCategories.adjust')" @close="emit('close')">
    <view v-for="top in tree" :key="top.categoryNo" class="grp">
      <text class="txt-title">{{ top.name }}</text>
      <view class="opts sh-wrap">
        <sh-option
          v-for="c in top.children ?? []"
          :key="c.categoryNo"
          class="opt sh-row"
          :selected="pickedNos.has(c.categoryNo)"
          @tap="toggle(c)"
        >
          <text class="txt-sub txt-ink">{{ c.name }}</text>
          <text v-if="countOf(c.categoryNo)" class="txt-caption">
            {{ t("storeCategories.goodsCount", { n: countOf(c.categoryNo) }) }}
          </text>
          <text v-if="ungranted(c)" class="txt-caption is-warning">{{ t("storeCategories.needCert") }}</text>
        </sh-option>
      </view>
    </view>
  </sh-sheet>
</template>

<style scoped>
.grp {
  margin-top: 24rpx;
}
.grp + .grp {
  margin-top: 32rpx;
}
.opts {
  gap: 16rpx;
  margin-top: 16rpx;
}
.opt {
  /* 只留版面：描边、圆角、选中态都归 sh-option */
  gap: 8rpx;
}
</style>
