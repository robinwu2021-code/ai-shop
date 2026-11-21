import { describe, expect, it } from "vitest";
import { bindableAccounts, keyReady, KEY_CHANNELS } from "./channel-accounts";
import type { ChannelAccountRow, StoreBinding } from "./_api/types";

const acc = (no: string, payChannel: string, patch: Partial<ChannelAccountRow> = {}) => ({ payMerchantNo: no, payChannel, verificationStatus: "VERIFIED", status: "ACTIVE", ...patch }) as ChannelAccountRow;
const bind = (payChannel: string, status: "ACTIVE" | "INACTIVE" = "ACTIVE") => ({ bindingNo: `B-${payChannel}`, payChannel, status }) as StoreBinding;

describe("渠道账户的两条前端规则（near-pay ChannelKeyForm / StoreBindingsTab 口径）", () => {
  it("密钥形态：蓝新 32+16 位、PayBy 私钥+公钥 PEM、其余 secret ≥16 位；未上线适配器的渠道没有表单", () => {
    const empty = { secret: "", hashKey: "", hashIv: "", publicMaterial: "" };
    expect(keyReady(KEY_CHANNELS.NEWEBPAY, { ...empty, hashKey: "k".repeat(32), hashIv: "v".repeat(16) })).toBe(true);
    expect(keyReady(KEY_CHANNELS.NEWEBPAY, { ...empty, hashKey: "k".repeat(31), hashIv: "v".repeat(16) })).toBe(false);
    expect(keyReady(KEY_CHANNELS.LINEPAY, { ...empty, secret: "s".repeat(15) })).toBe(false);
    expect(keyReady(KEY_CHANNELS.LINEPAY, { ...empty, secret: "s".repeat(16) })).toBe(true);
    expect(keyReady(KEY_CHANNELS.PAYBY, { ...empty, secret: "-----BEGIN PRIVATE KEY-----", publicMaterial: "-----BEGIN PUBLIC KEY-----" })).toBe(true);
    expect(KEY_CHANNELS.YITEPAY).toBeUndefined();
  });

  it("可直接绑定 = 已核验 + 可收款 + 该渠道在门店上没有生效绑定（已结束的绑定不占位）", () => {
    const accounts = [acc("A1", "NEWEBPAY"), acc("A2", "LINEPAY"), acc("A3", "JKOPAY", { verificationStatus: "PENDING" }), acc("A4", "MOCKPAY", { status: "REFUND_ONLY" })];
    expect(bindableAccounts(accounts, [bind("NEWEBPAY"), bind("LINEPAY", "INACTIVE")]).map((a) => a.payMerchantNo)).toEqual(["A2"]);
  });
});
