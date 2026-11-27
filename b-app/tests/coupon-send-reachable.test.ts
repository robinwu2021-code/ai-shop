// @vitest-environment happy-dom
import { flushPromises, mount } from "@vue/test-utils";
import { expect, it, vi } from "vitest";
import "./component-setup";
import Page from "@/pages/coupon-send/index.vue";
import { setPendingAudience } from "@/shared/audience";

const mock = vi.hoisted(() => ({
  load: (_query: Record<string, string>) => {},
  preview: vi.fn(), post: vi.fn(),
}));
vi.mock("@dcloudio/uni-app", () => ({ onLoad: (handler: typeof mock.load) => { mock.load = handler; } }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => ({ can: () => true }) }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock("@ai-shop/ui/prompt", () => ({ confirm: async () => true }));
vi.mock("@shared/net/http-client", async importOriginal => ({
  ...await importOriginal<typeof import("@shared/net/http-client")>(), http: { post: mock.post },
}));
vi.mock("@/api", async () => ({
  api: {
    mCoupon: async () => ({ couponNo: "C1", benefitValue: 100, benefitMode: "CASH" }),
    mAudiencePreview: mock.preview,
    mIssueCoupon: (await import("@/api/http")).httpApi.mIssueCoupon,
  },
}));

it("preserves the segment and retry key until the coupon eligibility option changes", async () => {
  const audiences = [{ type: "SEGMENT" as const, value: "SG1" }];
  setPendingAudience(audiences, "Segment 1");
  mock.preview.mockResolvedValue({ matched: 3, reachable: 2, skips: [] });
  mock.post.mockRejectedValue(new Error("offline"));
  const page = mount(Page, { global: {
    mocks: { $t: (key: string) => key },
    stubs: { "sh-scaffold": { template: "<main><slot/></main>" }, "biz-audience-picker": true },
  } });
  mock.load({ couponNo: "C1" });
  await flushPromises();
  const submit = async () => { await page.get(".bar__main").trigger("tap"); await flushPromises(); };
  expect(mock.preview).toHaveBeenLastCalledWith({ audiences, scene: undefined });
  await submit();
  const initial = mock.post.mock.calls[0];
  expect(initial).toEqual(["/biz/coupons/C1/issue", { audiences, reachableOnly: false },
    { "Idempotency-Key": expect.any(String) }]);
  await submit();
  expect(mock.post.mock.calls[1]).toEqual(initial);

  await page.get(".sw").trigger("tap");
  await flushPromises();
  expect(mock.preview).toHaveBeenLastCalledWith({ audiences, scene: "COUPON" });
  await submit();
  const changed = mock.post.mock.calls[2];
  expect(changed[1]).toEqual({ audiences, reachableOnly: true });
  expect(changed[2]["Idempotency-Key"]).not.toBe(initial[2]["Idempotency-Key"]);
  page.unmount();
});
