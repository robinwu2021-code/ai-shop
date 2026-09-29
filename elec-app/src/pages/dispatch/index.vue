<script setup lang="ts">
// 求购详情 · 报价 / 没货。
// - 同一条再报一次就是改价（覆盖，不是新增一条）
// - **没货就直说**：拒绝也算响应，不回才伤响应率
// - 他填的是自己的价（币种、含不含税照实填）；买家看到的是平台换算、加价、匿名之后的
// - 提交报价时要一次订阅授权 —— 覆盖「你的报价被选中了」那一条
import { computed, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api, errMsg, toast } from "@/api";
import { ensureLogin } from "@/shared/auth";
import { askSubscribe } from "@/shared/subscribe";
import {
  COND, DECLINE_REASON, DISPATCH_STATUS, PACKING, dateOf, e6Of, leadOf, priceOf, qtyOf, whenOf, yuanOf,
} from "@/shared/format";
import { demandLine } from "@/shared/dispatch";
import type { ElecCond, ElecCurrency, ElecDeclineReason, ElecDispatch, ElecPacking } from "@shared/types";

/** 报价有效期的几档（后端默认 3 天） */
const VALID_DAYS = [3, 7, 15] as const;
const CURRENCIES: ElecCurrency[] = ["CNY", "USD", "HKD"];

const dispatchNo = ref("");
const d = ref<ElecDispatch | null>(null);
const failed = ref("");
const busy = ref(false);
const editing = ref(false);
const declining = ref(false);
const form = reactive({
  price: "", currency: "CNY" as ElecCurrency, taxIncluded: true, qty: "", dateCode: "", leadDays: "",
  cond: "" as ElecCond | "", packing: "" as ElecPacking | "", moq: "", validDays: 3 as number, remark: "",
});

onLoad(async (q) => {
  dispatchNo.value = q?.dispatchNo ? decodeURIComponent(String(q.dispatchNo)) : "";
  if (!(await ensureLogin())) return;
  await load();
});

async function load() {
  failed.value = "";
  try {
    d.value = await api.dispatchDetail(dispatchNo.value);
    fill();
  } catch (e) {
    failed.value = errMsg(e);
  }
}

/** 报过的价回填；没报过就按他库里有的与买家要的数量给个起点 */
function fill() {
  const x = d.value;
  if (!x) return;
  const q = x.myQuote;
  if (q) {
    Object.assign(form, {
      price: yuanOf(q.priceE6).replace(/,/g, ""), currency: q.currency, taxIncluded: q.taxIncluded,
      qty: String(q.qtyAvailable), dateCode: q.dateCode ?? "", leadDays: q.leadDays == null ? "" : String(q.leadDays),
      cond: q.cond ?? "", packing: q.packing ?? "", moq: q.moq ? String(q.moq) : "", remark: q.remark ?? "",
    });
  } else {
    form.qty = String(Math.min(x.qty, x.inStock ?? x.qty));
  }
  editing.value = !q && x.status !== "DECLINED";
}

const open = computed(() => !!d.value && d.value.status !== "DECLINED");
const condOpts = Object.entries(COND) as [ElecCond, string][];
const packOpts = Object.entries(PACKING) as [ElecPacking, string][];
const reasons = Object.entries(DECLINE_REASON) as [ElecDeclineReason, string][];

function problem(): string {
  if (e6Of(form.price) === null || e6Of(form.price) === 0) return "单价写成数字，如 6.2";
  if (!/^\d+$/.test(form.qty.trim()) || Number(form.qty) <= 0) return "能供多少没填";
  if (form.leadDays.trim() && !/^\d{1,3}$/.test(form.leadDays.trim())) return "交期写天数，现货写 0";
  if (form.moq.trim() && !/^\d+$/.test(form.moq.trim())) return "起订量写数字";
  return "";
}

async function submit() {
  const x = d.value;
  if (!x || busy.value) return;
  const err = problem();
  if (err) {
    toast(err);
    return;
  }
  const sub = askSubscribe("picked");
  busy.value = true;
  try {
    await sub;
    d.value = await api.quoteDispatch(x.dispatchNo, {
      priceE6: e6Of(form.price)!,
      currency: form.currency,
      taxIncluded: form.taxIncluded,
      qtyAvailable: Number(form.qty),
      dateCode: form.dateCode.trim() || undefined,
      leadDays: form.leadDays.trim() ? Number(form.leadDays) : undefined,
      cond: form.cond || undefined,
      packing: form.packing || undefined,
      moq: form.moq.trim() ? Number(form.moq) : undefined,
      validDays: form.validDays,
      remark: form.remark.trim() || undefined,
    });
    editing.value = false;
    toast("报价已提交，买家选中时通知你");
  } catch (e) {
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}

async function decline(reason: ElecDeclineReason) {
  const x = d.value;
  if (!x || busy.value) return;
  busy.value = true;
  try {
    d.value = await api.declineDispatch(x.dispatchNo, { reason });
    declining.value = false;
    toast("已回复平台");
  } catch (e) {
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="title.dispatch" :pending="!d && !failed" :failed="!!failed" :failed-text="failed" @retry="load">
    <template v-if="d">
      <view class="sh-card">
        <view class="sh-row sh-row--between">
          <text class="txt-title sh-num mpn">{{ d.mpn }}</text>
          <text class="sh-chip" :class="d.status === 'QUOTED' ? 'sh-chip--primary' : d.status === 'DECLINED' ? 'sh-chip--dashed-quiet' : 'sh-chip--warning'">
            {{ DISPATCH_STATUS[d.status] }}
          </text>
        </view>
        <sh-kv label="要几片" between divided><text class="sh-num">{{ qtyOf(d.qty) }}</text></sh-kv>
        <sh-kv v-if="d.mfr" label="厂牌" between divided>{{ d.mfr }}</sh-kv>
        <sh-kv v-if="d.targetE6" label="目标价" between divided>{{ priceOf(d.targetE6) }}（含税）</sh-kv>
        <sh-kv label="要求" between divided>{{ demandLine(d) || "不限" }}</sh-kv>
        <sh-kv label="你库里" between divided>{{ d.inStock ? qtyOf(d.inStock) : "没有这个料号" }}</sh-kv>
        <text class="txt-caption sh-muted block">{{ whenOf(d.createdAt) }} · 单号 {{ d.dispatchNo }} · 买家身份不公开</text>
      </view>

      <!-- 已报过：先看自己的报价，要改再点开 -->
      <view v-if="d.myQuote && !editing" class="sh-card block">
        <view class="sh-row sh-row--between">
          <text class="txt-strong">我的报价</text>
          <text v-if="d.myQuote.status === 'ACCEPTED'" class="sh-chip sh-chip--success">买家选了你</text>
          <text v-else-if="open" class="sh-link" @tap="editing = true">改价</text>
        </view>
        <sh-kv label="单价" between divided>
          {{ priceOf(d.myQuote.priceE6, d.myQuote.currency) }} {{ d.myQuote.taxIncluded ? "含税" : "未税" }}
        </sh-kv>
        <sh-kv label="能供" between divided>{{ qtyOf(d.myQuote.qtyAvailable) }}</sh-kv>
        <sh-kv label="货况" between divided>
          {{ [d.myQuote.dateCode ? `批号 ${d.myQuote.dateCode}` : "", leadOf(d.myQuote.leadDays),
              d.myQuote.cond ? COND[d.myQuote.cond] : "", d.myQuote.packing ? PACKING[d.myQuote.packing] : ""]
            .filter(Boolean).join(" · ") }}
        </sh-kv>
        <sh-kv label="有效至" between divided>{{ dateOf(d.myQuote.validUntil) }}</sh-kv>
      </view>

      <!-- 报价表单 -->
      <view v-if="editing && open" class="sh-card block">
        <text class="txt-strong">报价</text>
        <!-- 预填了值的输入框看不见占位符，所以字段名写在框外 -->
        <view class="sh-row pair f">
          <view class="half">
            <text class="txt-caption sh-muted">单价</text>
            <input v-model="form.price" class="field__input sh-num" type="digit" placeholder="如 6.2" />
          </view>
          <view class="half">
            <text class="txt-caption sh-muted">能供多少（片）</text>
            <input v-model="form.qty" class="field__input sh-num" type="number" placeholder="片数" />
          </view>
        </view>
        <view class="sh-row opts">
          <text v-for="c in CURRENCIES" :key="c" class="sh-seg" :class="{ 'sh-seg--on': form.currency === c }"
            @tap="form.currency = c">{{ c === "CNY" ? "人民币" : c === "USD" ? "美元" : "港币" }}</text>
        </view>
        <view class="sh-row sh-row--between f">
          <text class="txt-sub">价格含税</text>
          <sh-switch v-model="form.taxIncluded"></sh-switch>
        </view>
        <view class="sh-row pair f">
          <view class="half">
            <text class="txt-caption sh-muted">批号</text>
            <input v-model="form.dateCode" class="field__input" placeholder="如 2338 / 24+" />
          </view>
          <view class="half">
            <text class="txt-caption sh-muted">交期（天）</text>
            <input v-model="form.leadDays" class="field__input sh-num" type="number" placeholder="现货填 0" />
          </view>
        </view>
        <text class="txt-caption sh-muted gap">货况</text>
        <view class="sh-wrap opts">
          <text v-for="[k, v] in condOpts" :key="k" class="sh-seg" :class="{ 'sh-seg--on': form.cond === k }"
            @tap="form.cond = form.cond === k ? '' : k">{{ v }}</text>
        </view>
        <text class="txt-caption sh-muted gap">包装</text>
        <view class="sh-wrap opts">
          <text v-for="[k, v] in packOpts" :key="k" class="sh-seg" :class="{ 'sh-seg--on': form.packing === k }"
            @tap="form.packing = form.packing === k ? '' : k">{{ v }}</text>
        </view>
        <input v-model="form.moq" class="field__input sh-num f" type="number" placeholder="起订量（选填）" />
        <text class="txt-caption sh-muted gap">报价有效</text>
        <view class="sh-row opts">
          <text v-for="n in VALID_DAYS" :key="n" class="sh-seg" :class="{ 'sh-seg--on': form.validDays === n }"
            @tap="form.validDays = n">{{ n }} 天</text>
        </view>
        <textarea v-model="form.remark" class="field__area remark" maxlength="200" placeholder="给平台的备注（买家看不到）" />
        <text class="txt-caption sh-muted gap">买家看到的是平台换算成人民币含税后的价，看不到你是谁</text>
      </view>

      <sh-actionbar v-if="open && editing">
        <view class="sh-row acts">
          <view v-if="!d.myQuote" class="sh-btn sh-btn--muted grow" @tap="declining = true">没货</view>
          <view v-else class="sh-btn sh-btn--muted grow" @tap="editing = false; fill()">不改了</view>
          <view class="sh-btn grow" :class="{ 'is-disabled': busy }" @tap="submit">
            {{ busy ? "提交中…" : d.myQuote ? "提交新价" : "提交报价" }}
          </view>
        </view>
      </sh-actionbar>

      <sh-sheet :visible="declining" title="这条做不了？" hint="直说也算回复，平台会找别家" @close="declining = false">
        <view class="sh-cells">
          <view v-for="[k, v] in reasons" :key="k" class="sh-cell" @tap="decline(k)">
            <text class="txt-body">{{ v }}</text>
          </view>
        </view>
      </sh-sheet>
    </template>
  </sh-scaffold>
</template>

<style scoped>
/* 库里的输入框不带外边距，纵向间距是版面的事 */
.f {
  margin-top: 16rpx;
}
.mpn {
  flex: 1;
  min-width: 0;
  word-break: break-all;
}
.block {
  display: block;
  margin-top: 24rpx;
}
.sh-card .block {
  margin-top: 12rpx;
}
.pair {
  gap: 16rpx;
}
.half {
  flex: 1;
  min-width: 0;
}
.opts {
  gap: 12rpx;
  margin-top: 12rpx;
}
.gap {
  display: block;
  margin-top: 24rpx;
}
.remark {
  min-height: 120rpx;
  margin-top: 16rpx;
}
.acts {
  gap: 20rpx;
  width: 100%;
}
.grow {
  flex: 1;
}
</style>
