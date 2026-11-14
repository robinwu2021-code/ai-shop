import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { transpileModule, ModuleKind, ScriptTarget } from "typescript";
import { expect, it, vi } from "vitest";

// Evaluate each real uni conditional branch; Vitest otherwise runs both comment blocks.
function port(file: string, platform: string, dependencies: Record<string, unknown>, uni: unknown, window = { location: { href: "" } }) {
  const stack = [true];
  const source = readFileSync(resolve(__dirname, `../src/ports/${file}.ts`), "utf8")
    .split("\n").filter(line => {
      const directive = line.match(/\/\/ #(ifdef|ifndef) (\S+)/);
      if (directive) { stack.push(stack.at(-1)! && (directive[1] === "ifdef" ? platform === directive[2] : platform !== directive[2])); return false; }
      if (/\/\/ #endif/.test(line)) { stack.pop(); return false; }
      return stack.at(-1)!;
    }).join("\n");
  const js = transpileModule(source, { compilerOptions: { module: ModuleKind.CommonJS, target: ScriptTarget.ES2022 } }).outputText;
  const exported: Record<string, (...args: any[]) => any> = {};
  new Function("require", "exports", "uni", "window", js)((id: string) => {
    if (!(id in dependencies)) throw Error(`Unexpected dependency ${id}`);
    return dependencies[id];
  }, exported, uni, window);
  return exported;
}

function tracking(platform: string, phone = true) {
  const api = { track: vi.fn(async () => ({ route: "anonymous" })), trackMine: vi.fn(async () => ({ route: "verified" })) };
  const uni = { redirectTo: vi.fn() };
  const window = { location: { href: "" } };
  const methods = port("recipient-track", platform, { "@/api": { api }, "@/stores/user": { useUserStore: () => ({ isPhoneLogin: phone }) }, "@shared/utils/constants": { ROUTES: { login: "/login", track: "/track" } } }, uni, window);
  return { methods, api, uni, window };
}

it("mini-program verifies phone identity before fetching the recipient view", async () => {
  const { methods, api } = tracking("MP-WEIXIN");
  expect(await methods.loadRecipientTracking!("ticket")).toEqual({ redirected: false, view: { route: "verified" } });
  expect(api.trackMine).toHaveBeenCalledWith("ticket");
  expect(api.track).not.toHaveBeenCalled();
});
it("unverified mini-program phone redirects and never falls back to anonymous tracking", async () => {
  const { methods, api, uni } = tracking("MP-WEIXIN", false);
  expect(await methods.loadRecipientTracking!("ticket")).toEqual({ redirected: true, view: null });
  expect(uni.redirectTo).toHaveBeenCalledWith({ url: "/login?redirect=%2Ftrack%3Ft%3Dticket" });
  expect(api.track).not.toHaveBeenCalled(); expect(api.trackMine).not.toHaveBeenCalled();
});
it("H5 keeps anonymous tracking and opens URL Link without phone verification", async () => {
  const { methods, api, uni, window } = tracking("H5", false);
  expect(await methods.loadRecipientTracking!("ticket")).toEqual({ redirected: false, view: { route: "anonymous" } });
  expect(api.trackMine).not.toHaveBeenCalled(); expect(uni.redirectTo).not.toHaveBeenCalled();
  expect(methods.supportsRecipientMiniLink!()).toBe(true);
  methods.openRecipientMiniLink!("https://wxaurl.cn/ticket"); expect(window.location.href).toBe("https://wxaurl.cn/ticket");
});
it.each(["MP-WEIXIN", "APP-PLUS"])("%s never opens a browser URL Link", platform => {
  const { methods, window } = tracking(platform);
  expect(methods.supportsRecipientMiniLink!()).toBe(false);
  methods.openRecipientMiniLink!("https://wxaurl.cn/ticket"); expect(window.location.href).toBe("");
});
it("native subscription opens synchronously within the tap call stack", () => {
  const uni = { requestSubscribeMessage: vi.fn() };
  const methods = port("stored-value-subscribe", "MP-WEIXIN", {}, uni);
  const request = { tmplIds: ["real-template"] };
  expect(methods.requestStoredValueSubscription!(request)).toBe(true);
  expect(uni.requestSubscribeMessage).toHaveBeenCalledWith(request);
});
it("H5 subscription port cannot invoke the native authorization API", () => {
  const uni = { requestSubscribeMessage: vi.fn() };
  const methods = port("stored-value-subscribe", "H5", {}, uni);
  expect(methods.requestStoredValueSubscription!({ tmplIds: ["real-template"] })).toBe(false);
  expect(uni.requestSubscribeMessage).not.toHaveBeenCalled();
});
