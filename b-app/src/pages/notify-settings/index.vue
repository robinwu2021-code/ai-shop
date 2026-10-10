<script setup lang="ts">
// 通知设置（TDD-来单四渠道与商家通知设置 §2.4）。
//
// 来单、售后、评价三件事，每件四条通道各一个开关；外加本店的企业微信群地址。
//
// **都是门店级的**：改的是当前门店那一家（X-Store-No），
// 自营一个主体下已有 4 家店，各店的人不同、各店可以有自己的群。
//
// 为什么四条腿都要有、而不是只留最好的那一条：微信订阅一次授权只够一条，
// 店主不进小程序就没额度；App 的厂商通道没报备，退到后台就收不到；
// 企微群与短信配过一次就一直能发。来单是最怕漏的一条，任一条单独都不够可靠。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { NotifySetting } from "@/api/contract";
import { prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const setting = ref<NotifySetting | null>(null);
const failed = ref(false);
const busy = ref(false);

/** 改要 biz:store:admin（关掉来单提醒影响整店），看只要 biz:store */
const editable = computed(() => merchant.can("biz:store:admin"));

async function load() {
  failed.value = false;
  try {
    setting.value = await api.mNotifySetting();
  } catch {
    failed.value = true;
  }
}

onShow(load);

/**
 * 翻一个开关。
 *
 * <p>**先改后端再回显，不做乐观更新**：这一屏的每一格都对应「会不会收到通知」，
 * 乐观更新在失败时会留下一个看起来已经关掉、实际还在响的开关 ——
 * 而那正是店主最不能被骗的一格。
 */
async function toggle(scene: string, channel: string, enabled: boolean) {
  if (!editable.value || busy.value) return;
  busy.value = true;
  try {
    setting.value = await api.mSaveNotifySwitch(scene, channel as never, enabled);
  } catch {
    await load();   // 失败就回读真相，别留一个说假话的开关
  } finally {
    busy.value = false;
  }
}

/**
 * 录入企微群地址。
 *
 * <p>**不回显已存的那条**（它是凭据，后端永不下发），所以输入框永远是空的 ——
 * 右边只显示「已配置 / 未配置」。想换就重填一次。
 */
async function editWecom() {
  if (!editable.value) return;
  const url = await prompt({
    title: String(t("notifySetting.wecomTitle")),
    hint: String(t("notifySetting.wecomHint")),
    placeholder: String(t("notifySetting.wecomPlaceholder")),
    confirmText: String(t("common.save")),
    maxlength: 255,
  });
  if (!url) return;
  busy.value = true;
  try {
    setting.value = await api.mSaveNotifyWecom(url.trim());
    uni.showToast({ title: String(t("notifySetting.saved")), icon: "none" });
  } catch {
    uni.showToast({ title: String(t("notifySetting.wecomBad")), icon: "none" });
  } finally {
    busy.value = false;
  }
}

/** 往群里发一条测试。失败要原样说出来 —— 这是店主唯一能自己验的一条 */
async function testWecom() {
  if (busy.value) return;
  busy.value = true;
  try {
    const ok = await api.mTestNotifyWecom();
    uni.showToast({
      title: String(t(ok ? "notifySetting.testSent" : "notifySetting.testFailed")),
      icon: "none",
    });
  } catch {
    uni.showToast({ title: String(t("notifySetting.testFailed")), icon: "none" });
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="notifySetting.title" :denied="!merchant.can('biz:store')" :failed="failed" @retry="load">
    <biz-store-tag readonly></biz-store-tag>

    <!--
      一个场景一张卡，卡里四行开关。来单那张在最前面 —— 它是最要紧的一条。
      开关用 switch 而不是药丸：这里是「开/关」而不是「选一个」。
    -->
    <view v-for="sc in setting?.scenes ?? []" :key="sc.scene" class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t(`notifySetting.scene.${sc.scene}`) }}</text>
      </view>
      <view v-for="(on, ch) in sc.switches" :key="ch" class="sh-row sh-row--between sh-row--divided">
        <view class="chan">
          <text class="txt-body">{{ $t(`notifySetting.channel.${ch}`) }}</text>
          <!-- 四条都写一句：不写的话店主不知道短信到底发给谁、群是不是全店共用 -->
          <text class="txt-caption sh-muted chan__hint">{{ $t(`notifySetting.hint.${ch}`) }}</text>
        </view>
        <!--
          值取事件里那个，不是 `!on`：连点两下时 `on` 已经过期，
          取反会把第二下算成「回到原状」而后端收到的是同一个值。
        -->
        <switch :checked="on" :disabled="!editable || busy" color="var(--sh-primary)"
          @change="(e: any) => toggle(sc.scene, String(ch), !!e.detail.value)"></switch>
      </view>
    </view>

    <!-- 企微群：一行录入 + 一行测试。URL 永不回显，右边只说配过没有 -->
    <view class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("notifySetting.wecom") }}</text>
      </view>
      <view class="sh-row sh-row--between sh-row--divided" @tap="editWecom">
        <text class="txt-body">{{ $t("notifySetting.wecomAddr") }}</text>
        <view class="sh-row">
          <text class="txt-body" :class="setting?.wecomReady ? '' : 'sh-muted'">
            {{ $t(setting?.wecomReady ? "notifySetting.ready" : "notifySetting.notReady") }}
          </text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
      <view v-if="setting?.wecomReady" class="sh-row sh-row--between sh-row--divided" @tap="testWecom">
        <text class="txt-body">{{ $t("notifySetting.test") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <text v-if="!editable" class="txt-caption sh-hint">{{ $t("notifySetting.adminOnly") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.chan {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.chan__hint {
  margin-top: 4rpx;
}
</style>
