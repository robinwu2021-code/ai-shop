import { pointsCurrency } from "@/lib/points-currency";
import type { FinanceApi } from "../contracts/finance";
import type { PointsTypeConfig, PointsFallback, PointsSwitchView, PointsTerms, PointsChangeResult } from "@/lib/types";
import { fail } from "@/lib/biz-error";
import { wait } from "./_wait";

// Synthetic configuration only. No store signature, source fact or bank receipt is fabricated.
const types: PointsTypeConfig[] = [{ typeCode: "POINTS_DEMO", name: "演示积分", market: "CN", currency: "CNY", scale: 2, rate: { pointsUnit: 1, minorUnit: 1 }, deductRatioBp: 3000, idleDays: 365, status: "ACTIVE" }];
const rules = new Map<string, PointsFallback>();
const terms = new Map<string, PointsTerms>();
const switches: PointsSwitchView[] = [];
const receipts = new Map<string, { body: string; result: PointsChangeResult }>();
function command(action: string, v: { requestId: string; typeCode: string; reason: string }, work: () => void) {
  const key = `${action}:${v.requestId}`;
  const body = JSON.stringify(v);
  const prior = receipts.get(key);
  if (prior) {
    if (prior.body !== body) fail("请求号已用于其他操作内容", "Request ID conflicts with the original command");
    return wait(structuredClone(prior.result));
  }
  if (!v.requestId || !v.reason.trim()) fail("请填写操作原因", "An operation reason is required");
  work();
  const result = { requestId: v.requestId, action, typeCode: v.typeCode, at: Date.now() };
  receipts.set(key, { body, result });
  return wait(structuredClone(result));
}
function type(code: string) {
  const t = types.find((t) => t.typeCode === code);
  if (!t) fail("积分类型不存在", "Points type not found");
  return t;
}
export const pointsConfigurationMock: Pick<FinanceApi,
  "pointsTypes" | "createPointsType" | "transitionPointsType" | "pointsFallback" | "savePointsFallback" |
  "pointsSwitches" | "changePointsSwitch" | "pointsTerms" | "publishPointsTerms" | "pointsAgreements"> = {
  pointsTypes: () => wait(structuredClone(types)),
  createPointsType: (v) => command("CREATE", { ...v, typeCode: v.command.typeCode }, () => {
    if (types.some((t) => t.typeCode.toLocaleLowerCase() === v.command.typeCode.trim().toLocaleLowerCase() || t.name.trim().toLocaleLowerCase() === v.command.name.trim().toLocaleLowerCase())) fail("积分代码或名称已存在，请修改后重试。", "This points code or name already exists. Change it and try again.");
    const currency = pointsCurrency(v.command.currency ?? "CNY");
    if (!currency) fail("货币类型无效", "Invalid currency");
    const rate = v.command.rate ?? { pointsUnit: 1, minorUnit: 1 };
    if (![rate.pointsUnit, rate.minorUnit].every((n) => Number.isSafeInteger(n) && n > 0)) fail("兑换比例无效", "Invalid conversion rate");
    let a = rate.pointsUnit, b = rate.minorUnit;
    while (b) { const rest = a % b; a = b; b = rest; }
    types.push({ ...v.command, typeCode: v.command.typeCode.trim(), name: v.command.name.trim(), market: "CN", currency: currency.code, scale: currency.scale, rate: { pointsUnit: rate.pointsUnit / a, minorUnit: rate.minorUnit / a }, deductRatioBp: 3000, idleDays: 365, status: "DRAFT" });
  }),
  transitionPointsType: (v) => command("STATE", v, () => {
    const t = type(v.typeCode);
    const target = { ACTIVATE: "ACTIVE", DRAIN: "DRAINING", RETIRE: "RETIRED" } as const;
    if ((v.action === "ACTIVATE" && t.status !== "DRAFT") || (v.action === "DRAIN" && t.status !== "ACTIVE") || (v.action === "RETIRE" && t.status !== "DRAINING")) fail("类型状态不允许此操作", "Type state does not allow this operation");
    t.status = target[v.action];
  }),
  pointsFallback: (code) => { type(code); return wait({ ...(rules.get(code) ?? { revision: 0, mode: "RATIO" as const, value: 0 }) }); },
  savePointsFallback: (v) => command("FALLBACK", v, () => {
    type(v.typeCode);
    const revision = rules.get(v.typeCode)?.revision ?? 0;
    if (revision !== v.expectedRevision) fail("规则已变化，请刷新", "Rule changed; reload before saving");
    rules.set(v.typeCode, { revision: revision + 1, mode: v.mode, value: v.value });
  }),
  pointsSwitches: (q) => {
    const rows = switches.filter((x) => (x.typeCode === q.typeCode || x.typeCode === "*") && x.id > (q.after ?? 0)).slice(0, (q.limit ?? 50) + 1);
    const hasMore = rows.length > (q.limit ?? 50);
    if (hasMore) rows.pop();
    return wait(structuredClone({ rows, next: rows.at(-1)?.id ?? q.after ?? 0, hasMore }));
  },
  changePointsSwitch: (v) => command("SWITCH", v, () => {
    if (v.typeCode !== "*") type(v.typeCode);
    if ((v.scope === "CLIENT" && !["MP_WECHAT", "MP_ALIPAY", "IOS", "ANDROID", "H5"].includes(v.subjectNo)) || (v.scope === "SCENE" && !["ONLINE", "OFFLINE"].includes(v.subjectNo))) fail("范围对象无效", "Invalid scope subject");
    const old = switches.findIndex((x) => x.scope === v.scope && x.typeCode === v.typeCode && x.subjectNo === v.subjectNo && x.flow === v.flow);
    if (old >= 0) switches.splice(old, 1);
    switches.push({ ...v, id: Date.now(), actor: "DEMO_OPERATOR", at: Date.now() });
  }),
  pointsTerms: (code) => { type(code); return wait(structuredClone(terms.get(code) ?? null)); },
  publishPointsTerms: (v) => command("TERMS", v, () => {
    type(v.typeCode);
    const prior = terms.get(v.typeCode);
    if (prior?.version === v.version && prior.text !== v.text) fail("同版本正文不可修改", "Terms text is immutable within a version");
    terms.set(v.typeCode, { version: v.version, text: v.text, hash: "DEMO_ONLY" });
  }),
  pointsAgreements: () => wait({ rows: [], next: 0, hasMore: false }),
};
