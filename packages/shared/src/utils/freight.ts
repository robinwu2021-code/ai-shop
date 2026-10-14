// 快递运费计价（TDD-快递100商家寄件 §8）—— 与后端 FreightPort#fee / FreightPortImpl#quote 同一公式。
//
// 端上只拿它做「预估」（商品编辑页、发货设置页）；**真正收多少由后端下单时算**。
// 两份实现靠 tests/freight.test.ts 与后端 FreightTemplateFlowTest 用同一组用例对拍 —— 改一边不改另一边，
// 商家看到的预估与买家实付就会不一样，而两边各自的测试都是绿的。

/** 本店适用的运费模板（`GET /biz/store/{storeNo}/freight-template`）。重量克、金额分 */
export interface FreightTemplateView {
  templateNo: string;
  name: string;
  firstWeightGram: number;
  firstFee: number;
  addWeightGram: number;
  addFee: number;
  /** 满多少分包邮；0 = 不包邮 */
  freeThreshold: number;
  /** 地区规则：按收货地址**开头**匹配省份 */
  rules: { region: string; action: "REJECT" | "SURCHARGE"; surcharge: number }[];
}

/** `重 ≤ 首重 → 首重费；否则 首重费 + ⌈(重 − 首重) / 续重单位⌉ × 续重费`（不含加收与满免） */
export function freightFee(weightGram: number, t: Pick<FreightTemplateView,
  "firstWeightGram" | "firstFee" | "addWeightGram" | "addFee">): number {
  if (weightGram <= t.firstWeightGram || t.addWeightGram <= 0) return t.firstFee;
  return t.firstFee + Math.ceil((weightGram - t.firstWeightGram) / t.addWeightGram) * t.addFee;
}

/**
 * 一件货寄到「基础价」地区的运费预估：没填重量按首重（与下单同口径）。
 * @returns weighed=false 表示按首重估的 —— 端上据此提示商家补重量
 */
export function estimateFreight(t: FreightTemplateView, nominalGram: number | null | undefined):
  { fee: number; weighed: boolean } {
  const weighed = nominalGram != null && nominalGram > 0;
  return { fee: freightFee(weighed ? nominalGram : t.firstWeightGram, t), weighed };
}

/** 合并的一份：同一个模板下的全部货（后端 `FreightPort.Part`） */
export interface FreightPart {
  template: FreightTemplateView;
  /** 计费重（没填重量的件已按首重折进来） */
  weightGram: number;
  goodsAmountMinor: number;
  /** 收货地址命中的地区规则；没命中为空 */
  hit?: FreightTemplateView["rules"][number] | null;
}

/**
 * 一家门店多模板合并（淘宝式，ADR-031 §2.5）—— 逐条照抄后端 `FreightPort#merge`，
 * 用例与 `FreightMergeTest` 同一批：
 * 任一份不配送 → 整店拒；各份按自己的门槛判包邮（包邮的不参与比较）；
 * 其余份首费最高者计首重 + 续重，其他份全部重量只按各自续重；加收取最高一笔、只加一次。
 */
export function mergeFreight(parts: FreightPart[]):
  { templateNo: string; fee: number; rejected: boolean; free: boolean } {
  const reject = parts.find((p) => p.hit?.action === "REJECT");
  if (reject) return { templateNo: reject.template.templateNo, fee: 0, rejected: true, free: false };
  const charged = parts.filter((p) => !(p.template.freeThreshold > 0
    && p.goodsAmountMinor >= p.template.freeThreshold));
  if (!charged.length) return { templateNo: parts[0]!.template.templateNo, fee: 0, rejected: false, free: true };
  let primary = charged[0]!;
  for (const p of charged) if (p.template.firstFee > primary.template.firstFee) primary = p;
  let fee = freightFee(primary.weightGram, primary.template);
  let surcharge = 0;
  for (const p of charged) {
    const t = p.template;
    if (p !== primary && t.addWeightGram > 0 && p.weightGram > 0) {
      fee += Math.ceil(p.weightGram / t.addWeightGram) * t.addFee;
    }
    if (p.hit && p.hit.surcharge > surcharge) surcharge = p.hit.surcharge;
  }
  return { templateNo: primary.template.templateNo, fee: fee + surcharge, rejected: false, free: false };
}
