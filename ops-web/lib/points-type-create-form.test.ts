// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { PointsTypeCreateForm } from "@/app/points/type-create-form";

let root: Root;
let host: HTMLDivElement;
const save = vi.fn();
beforeEach(async () => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  save.mockReset(); host = document.createElement("div"); document.body.append(host); root = createRoot(host);
  await act(async () => root.render(h(PointsTypeCreateForm, { existing: [], onSubmit: save })));
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
function field(name: string) { return host.querySelector(`[name="${name}"]`) as HTMLInputElement | HTMLSelectElement; }
async function change(name: string, value: string) {
  const el = field(name);
  const proto = el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, "value")!.set!.call(el, value);
  await act(async () => el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? "change" : "input", { bubbles: true })));
}
async function submit() { await act(async () => host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }))); }
it("defaults to the currency's smallest unit and submits the chosen rate as exact minor units", async () => {
  expect(field("currency").value).toBe("CNY"); expect(field("unit").value).toBe("fen"); expect(field("points").value).toBe("1");
  await change("currency", "USD"); expect(field("unit").value).toBe("cent");
  await change("unit", "dollar"); expect(field("points").value).toBe("100");
  field("name").value = "海外积分"; field("code").value = "OVERSEAS";
  await submit(); expect(save).toHaveBeenCalledWith(expect.objectContaining({ name: "海外积分", typeCode: "OVERSEAS", currency: "USD", rate: { pointsUnit: 100, minorUnit: 100 } }));
});
it("resets dependent units when currency changes and uses three-decimal dinar units", async () => {
  await change("currency", "KWD"); expect(field("unit").value).toBe("fils");
  await change("unit", "dinar"); expect(field("points").value).toBe("1000");
  await change("currency", "CNY"); expect(field("unit").value).toBe("fen"); expect(field("points").value).toBe("1");
});
it("blocks fractional, zero or unsafe points and whitespace-only names without sending a command", async () => {
  field("name").value = "   "; field("code").value = "CODE"; await submit(); expect(save).not.toHaveBeenCalled();
  expect(host.textContent).toContain("请填写积分名称"); field("name").value = "积分";
  for (const value of ["0", "1.5", "9007199254740992"]) { await change("points", value); await submit(); }
  expect(save).not.toHaveBeenCalled(); expect(host.querySelector('[aria-invalid="true"]')).not.toBeNull();
});
it("rejects unit conversion that would silently round fractional points", async () => {
  await change("unit", "yuan"); await change("points", "150"); await change("unit", "fen");
  expect(field("unit").value).toBe("yuan"); expect(field("points").value).toBe("150"); expect(host.textContent).toContain("整数积分");
});
it("supports UAE dirham with fils as the default unit and no market note", async () => {
  await change("currency", "AED"); expect(field("unit").value).toBe("fils");
  expect(host.textContent).not.toContain("系统市场");
  await change("unit", "dirham"); expect(field("points").value).toBe("100");
  field("name").value = "阿联酋积分"; field("code").value = "UAE_POINTS";
  await submit(); expect(save).toHaveBeenCalledWith(expect.objectContaining({ currency: "AED", rate: { pointsUnit: 100, minorUnit: 100 } }));
});
