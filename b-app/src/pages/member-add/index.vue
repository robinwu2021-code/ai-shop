<script setup lang="ts">
// 手工录入会员（P2）。
//
// **合规提示在保存之前，不在保存之后**：录入手机号不等于拿到推送许可。
// 他按下保存前就该知道「这条是线索、不会收到消息」，否则等他发现发不出去，
// 他会以为是功能坏了 —— 而那时他已经录了三十个号。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { MemberTag } from "@shared/types";
import { isPhone } from "@shared/utils/validate";

const { t } = useI18n();
const merchant = useMerchantStore();

const phone = ref("");
const remark = ref("");
const tags = ref<MemberTag[]>([]);
const picked = ref<string[]>([]);
const saving = ref(false);

/** 商家自己的标签才能在这里打 —— 系统标签是算出来的，手动打上去只会让口径变脏 */
const mine = computed(() => tags.value.filter((x) => x.tagType === "MCH" && x.status === "ACTIVE"));
/*
 * **判号段，不只判长度**。`length === 11` 会放行 `00000000000` ——
 * 手工录入的号本来就靠人手打，录错了不会有人来纠正：这条记录会一直躺在会员库里，
 * 发券发不到、回访打不通，而列表上看着与真会员没有区别。
 * 后端 `/biz/members` 现在也拒（`Phones.CN_MOBILE`）—— 这里拦是为了当场给提示，
 * 不然人看到的是一句「参数错误」。
 */
const canSave = computed(() => isPhone(phone.value) && !saving.value);

function toggle(tagNo: string) {
  const i = picked.value.indexOf(tagNo);
  if (i >= 0) picked.value.splice(i, 1);
  else picked.value.push(tagNo);
}

async function load() {
  tags.value = await api.mMemberTags().catch(() => []);
}

async function save() {
  if (!canSave.value) return;
  saving.value = true;
  try {
    const m = await api.mEnrollMember({
      phone: phone.value,
      remark: remark.value.trim() || undefined,
      tagNos: picked.value.length ? [...picked.value] : undefined,
      storeNo: merchant.storeNo || undefined,
    });
    uni.showToast({
      title: m.status === "LEAD" ? t("memberAdd.savedLead") : t("memberAdd.saved"),
      icon: "none",
    });
    phone.value = "";
    remark.value = "";
    picked.value = [];
    setTimeout(() => uni.navigateBack(), 800);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="memberAdd.title" :denied="!merchant.can('biz:customer')">
    <view class="sh-card">
      <view class="field">
        <text class="field__label">{{ $t("memberAdd.phone") }}</text>
        <input
          v-model="phone"
          class="field__input"
          type="number"
          maxlength="11"
          :placeholder="$t('memberAdd.phonePh')"
        />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("memberAdd.remark") }}</text>
        <input maxlength="255" v-model="remark" class="field__input" :placeholder="$t('memberAdd.remarkPh')" />
      </view>
      <view v-if="mine.length" class="field">
        <text class="field__label">{{ $t("memberAdd.tags") }}</text>
        <view class="tags">
          <text
            v-for="tg in mine"
            :key="tg.tagNo"
            class="sh-chip"
            :class="{ 'sh-chip--primary': picked.includes(tg.tagNo) }"
            @tap="toggle(tg.tagNo)"
          >{{ tg.name }}</text>
        </view>
      </view>
    </view>

    <!-- 这段话必须在保存之前出现 —— 见文件头的说明 -->
    <view class="notice">{{ $t("memberAdd.leadHint") }}</view>

    <view class="sh-btn go" :class="{ 'is-off': !canSave }" @tap="save">
      {{ saving ? "…" : $t("common.save") }}
    </view>
  </sh-scaffold>
</template>

<style scoped>
.field {
  margin-top: 20rpx;
}
.field:first-child {
  margin-top: 0;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.notice {
  margin-top: 16rpx;
  padding: 16rpx 20rpx;
  border-radius: 24rpx;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  font-size: 24rpx;
  line-height: 1.6;
}
.go {
  margin-top: 28rpx;
}
.go.is-off {
  background: var(--sh-faint);
  color: var(--sh-sub);
}
</style>
