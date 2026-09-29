<script setup lang="ts">
// 询价（原型 e05）。数量必填，其余带默认值（发票与收货城市记住上次）。
//
// 发票放在询价时就问：专票与不开票差 13%，比任何加价率都大，口径不一致就没法比。
// **手机号与订阅授权都在按下「提交」这一刻**，不在进页面时 —— 那时他还没产生任何关系，
// 要号是最典型的劝退；而现在他正要平台替他找货，两件事都讲得通。
import { computed, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api, errMsg, toast } from "@/api";
import { ensurePhone } from "@/shared/auth";
import { askSubscribe } from "@/shared/subscribe";
import { takeRfqDraft, type DraftLine } from "@/shared/draft";
import { ROUTES } from "@/shared/routes";
import { COND_REQ, DC_REQ, INVOICE, PACKING_REQ, e6Of } from "@/shared/format";
import type { ElecCondReq, ElecDcReq, ElecInvoice, ElecPackingReq, ElecRfqReq } from "@shared/types";

/** 上次选的发票与收货城市：采购的这两样几乎不变，每次都让他重选是在浪费他的时间 */
const MEMO_KEY = "she_elec_rfq_memo";
/** 后端上限：50 行、十亿片 */
const MAX_LINES = 50;
const MAX_QTY = 1_000_000_000;

interface Row { partNo?: string; mpn: string; mfr: string; qty: string; target: string }

const rows = ref<Row[]>([]);
const form = reactive({
  needInvoice: "VAT_SPECIAL" as ElecInvoice,
  dcReq: "Y2" as ElecDcReq,
  condReq: "ANY" as ElecCondReq,
  packingReq: "ANY" as ElecPackingReq,
  allowAlt: false,
  needByDays: "",
  deliverCity: "",
  company: "",
  contactName: "",
  remark: "",
});
const more = ref(false);
const busy = ref(false);

onLoad((q) => {
  try {
    const memo = uni.getStorageSync(MEMO_KEY) as { needInvoice?: ElecInvoice; deliverCity?: string } | "";
    if (memo && memo.needInvoice) form.needInvoice = memo.needInvoice;
    if (memo && memo.deliverCity) form.deliverCity = memo.deliverCity;
  } catch {
    // 记不住就用默认值
  }
  const draft: DraftLine[] = q?.from === "lookup" ? takeRfqDraft() : [];
  if (draft.length) {
    rows.value = draft.map((l) => ({ partNo: l.partNo, mpn: l.mpn, mfr: l.mfr ?? "", qty: l.qty ? String(l.qty) : "", target: "" }));
  } else {
    const dec = (v: unknown) => (v ? decodeURIComponent(String(v)) : "");
    rows.value = [{ partNo: dec(q?.partNo) || undefined, mpn: dec(q?.mpn), mfr: dec(q?.mfr), qty: "", target: "" }];
  }
});

function addRow() {
  if (rows.value.length >= MAX_LINES) return;
  rows.value.push({ mpn: "", mfr: "", qty: "", target: "" });
}
function removeRow(i: number) {
  if (rows.value.length > 1) rows.value.splice(i, 1);
}

const qtyNum = (s: string) => (/^\d+$/.test(s.trim()) ? Number(s.trim()) : NaN);
const rowError = (r: Row): string => {
  if (!r.mpn.trim()) return "料号没填";
  const n = qtyNum(r.qty);
  if (!(n > 0)) return "数量没填";
  if (n > MAX_QTY) return "数量太大，是不是多敲了几个 0";
  if (r.target.trim() && e6Of(r.target) === null) return "目标单价写成数字，如 6.5";
  return "";
};
const firstError = computed(() => {
  for (let i = 0; i < rows.value.length; i++) {
    const err = rowError(rows.value[i]!);
    if (err) return rows.value.length > 1 ? `第 ${i + 1} 行：${err}` : err;
  }
  if (form.needByDays.trim() && !/^\d{1,3}$/.test(form.needByDays.trim())) return "几天内要写成天数";
  return "";
});

async function submit() {
  if (busy.value) return;
  if (firstError.value) {
    toast(firstError.value);
    return;
  }
  // 订阅授权必须在点击回调里同步调起（微信的限制），所以排在任何 await 之前
  const sub = askSubscribe("quoted");
  if (!(await ensurePhone())) return;
  await sub;
  busy.value = true;
  const req: ElecRfqReq = {
    lines: rows.value.map((r) => ({
      partNo: r.partNo || undefined,
      mpn: r.mpn.trim(),
      mfr: r.mfr.trim() || undefined,
      qty: qtyNum(r.qty),
      targetE6: r.target.trim() ? (e6Of(r.target) ?? undefined) : undefined,
    })),
    needInvoice: form.needInvoice,
    dcReq: form.dcReq,
    condReq: form.condReq,
    packingReq: form.packingReq,
    allowAlt: form.allowAlt,
    needByDays: form.needByDays.trim() ? Number(form.needByDays.trim()) : undefined,
    deliverCity: form.deliverCity.trim() || undefined,
    company: form.company.trim() || undefined,
    contactName: form.contactName.trim() || undefined,
    remark: form.remark.trim() || undefined,
  };
  try {
    const rfq = await api.submitRfq(req);
    try {
      uni.setStorageSync(MEMO_KEY, { needInvoice: form.needInvoice, deliverCity: form.deliverCity.trim() });
    } catch {
      // 同上
    }
    uni.redirectTo({ url: `${ROUTES.rfq}?rfqNo=${encodeURIComponent(rfq.rfqNo)}&fresh=1` });
  } catch (e) {
    toast(errMsg(e));
  } finally {
    busy.value = false;
  }
}

const invoiceOpts = Object.entries(INVOICE) as [ElecInvoice, string][];
const dcOpts = Object.entries(DC_REQ) as [ElecDcReq, string][];
const condOpts = Object.entries(COND_REQ) as [ElecCondReq, string][];
const packOpts = Object.entries(PACKING_REQ) as [ElecPackingReq, string][];
</script>

<template>
  <sh-scaffold title-key="title.rfqCreate">
    <view v-for="(r, i) in rows" :key="i" class="sh-card row-card">
      <view class="sh-row sh-row--between">
        <text class="txt-caption sh-muted">{{ rows.length > 1 ? `第 ${i + 1} 行` : "料号" }}</text>
        <text v-if="rows.length > 1" class="sh-link sh-link--quiet" @tap="removeRow(i)">删掉这行</text>
      </view>
      <input v-model="r.mpn" class="field__input sh-num f" placeholder="料号" />
      <!-- 从批量查带过来的行已经填了数，占位符看不见了 —— 字段名写在框外 -->
      <view class="sh-row pair f">
        <view class="half">
          <text class="txt-caption sh-muted">数量（片）</text>
          <input v-model="r.qty" class="field__input sh-num" type="number" placeholder="必填" />
        </view>
        <view class="half">
          <text class="txt-caption sh-muted">目标单价（元）</text>
          <input v-model="r.target" class="field__input sh-num" type="digit" placeholder="选填" />
        </view>
      </view>
      <input v-model="r.mfr" class="field__input f" placeholder="厂牌（选填，写了只按这家找）" />
    </view>
    <view v-if="rows.length < MAX_LINES" class="add-row" @tap="addRow">
      <text class="sh-link">+ 再加一个料号</text>
    </view>

    <view class="sh-card block">
      <text class="txt-sub">发票</text>
      <view class="sh-wrap opts">
        <text v-for="[k, v] in invoiceOpts" :key="k" class="sh-seg" :class="{ 'sh-seg--on': form.needInvoice === k }"
          @tap="form.needInvoice = k">{{ v }}</text>
      </view>
      <text class="txt-sub gap">批次</text>
      <view class="sh-wrap opts">
        <text v-for="[k, v] in dcOpts" :key="k" class="sh-seg" :class="{ 'sh-seg--on': form.dcReq === k }"
          @tap="form.dcReq = k">{{ v }}</text>
      </view>
      <text class="txt-sub gap">收货城市</text>
      <input v-model="form.deliverCity" class="field__input f" placeholder="收货城市" />

      <view class="sh-row sh-row--between gap" @tap="more = !more">
        <text class="txt-sub">货况、包装、交期、替代</text>
        <text class="sh-link">{{ more ? "收起" : "展开" }}</text>
      </view>
      <template v-if="more">
        <view class="sh-wrap opts">
          <text v-for="[k, v] in condOpts" :key="k" class="sh-seg" :class="{ 'sh-seg--on': form.condReq === k }"
            @tap="form.condReq = k">{{ v }}</text>
        </view>
        <view class="sh-wrap opts">
          <text v-for="[k, v] in packOpts" :key="k" class="sh-seg" :class="{ 'sh-seg--on': form.packingReq === k }"
            @tap="form.packingReq = k">{{ v }}</text>
        </view>
        <input v-model="form.needByDays" class="field__input f" type="number" placeholder="几天内要到货（不急就空着）" />
        <view class="sh-row sh-row--between gap">
          <text class="txt-sub">可以用替代 / 国产兼容型号</text>
          <sh-switch v-model="form.allowAlt"></sh-switch>
        </view>
      </template>
    </view>

    <view class="sh-card block">
      <input v-model="form.company" class="field__input first" placeholder="公司名（选填，供应商看不到）" />
      <input v-model="form.contactName" class="field__input f" placeholder="联系人（选填）" />
      <textarea v-model="form.remark" class="field__area remark" maxlength="200" placeholder="备注（选填）" />
    </view>

    <text class="txt-caption sh-muted foot">平台一般 24 小时内给报价，微信通知你</text>

    <sh-actionbar>
      <view class="sh-btn" :class="{ 'is-disabled': busy }" @tap="submit">{{ busy ? "提交中…" : "提交询价" }}</view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
/* 库里的输入框不带外边距，纵向间距是版面的事 */
.f {
  margin-top: 16rpx;
}
.row-card + .row-card {
  margin-top: 16rpx;
}
.pair {
  gap: 16rpx;
}
.half {
  flex: 1;
  min-width: 0;
}
.add-row {
  padding: 20rpx 12rpx 0;
}
.block {
  margin-top: 24rpx;
}
.opts {
  margin-top: 12rpx;
}
.gap {
  display: flex;
  margin-top: 28rpx;
}
.first {
  margin-top: 0;
}
.remark {
  min-height: 140rpx;
  margin-top: 16rpx;
}
.foot {
  display: block;
  text-align: center;
  margin-top: 28rpx;
}
</style>
