// 组件示例页（/dev/pay-kit）用的内存数据。只给示例与测试用，不是 P1 的支付订单 mock。
export type DemoOrderStatus = "CREATED" | "PAYING" | "PAID" | "CLOSED" | "FAILED" | "REFUNDED";
export interface DemoOrder {
  payOrderNo: string;
  merchantName: string;
  amount: number; // 最小货币单位
  currency: string;
  channel: string;
  status: DemoOrderStatus;
  createdAt: number;
  remark: string;
  settleBank: string;
  settleAccount: string;
  version: number;
}

const STATUSES: DemoOrderStatus[] = ["PAID", "PAYING", "CREATED", "FAILED", "CLOSED", "REFUNDED"];
const CHANNELS = ["NEWEBPAY", "LINEPAY", "JKOPAY", "MOCKPAY"];
const MERCHANTS = ["台北早餐店", "信义咖啡", "大安面包坊", "松山便当"];

let rows: DemoOrder[] = Array.from({ length: 37 }, (_, i) => ({
  payOrderNo: `PO2026112${String(i + 1).padStart(4, "0")}`,
  merchantName: MERCHANTS[i % MERCHANTS.length],
  amount: 3500 + i * 1250,
  currency: "TWD",
  channel: CHANNELS[i % CHANNELS.length],
  status: STATUSES[i % STATUSES.length],
  createdAt: Date.UTC(2026, 10, 20, 2, 0) - i * 3_600_000,
  remark: "",
  settleBank: "",
  settleAccount: "",
  version: 1,
}));

const delay = <T,>(v: T) => new Promise<T>((r) => setTimeout(() => r(v), 120));

export function listDemoOrders(q: { page: number; size: number; status?: string; keyword?: string }) {
  const kw = q.keyword?.trim().toLowerCase();
  const filtered = rows.filter((r) => (!q.status || r.status === q.status)
    && (!kw || r.payOrderNo.toLowerCase().includes(kw) || r.merchantName.toLowerCase().includes(kw)));
  return delay({ records: filtered.slice((q.page - 1) * q.size, q.page * q.size), total: filtered.length });
}

export function getDemoOrder(no: string) {
  return delay(rows.find((r) => r.payOrderNo === no) ?? null);
}

export function updateDemoOrder(no: string, patch: Partial<DemoOrder>, expectedVersion?: number) {
  const cur = rows.find((r) => r.payOrderNo === no);
  if (!cur) return Promise.reject(new Error("订单不存在"));
  if (expectedVersion != null && cur.version !== expectedVersion) return Promise.reject(new Error("记录已被他人修改，请刷新后重试"));
  rows = rows.map((r) => (r.payOrderNo === no ? { ...r, ...patch, version: r.version + 1 } : r));
  return delay(undefined);
}

export function createDemoOrder(input: { merchantName: string; amount: number; channel: string; remark?: string }) {
  const no = `PO2026112${String(rows.length + 1).padStart(4, "0")}`;
  rows = [{ payOrderNo: no, currency: "TWD", status: "CREATED", createdAt: Date.now(), remark: input.remark ?? "", settleBank: "", settleAccount: "", version: 1, ...input }, ...rows];
  return delay(no);
}
