// @vitest-environment happy-dom
import { mount, flushPromises } from "@vue/test-utils";
import { expect, it, vi } from "vitest";
import "./component-setup";
import Page from "@/pages/member-reach/index.vue";
import { setPendingAudience } from "@/shared/audience";

const mock = vi.hoisted(() => ({
  show: () => {}, schedule: vi.fn(), send: vi.fn(),
}));
vi.mock("@dcloudio/uni-app", () => ({ onShow: (fn: () => void) => { mock.show = fn; } }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => ({ can: () => true, currentStore: { timeZone: "Asia/Shanghai" } }) }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock("@ai-shop/ui/prompt", () => ({ confirm: async () => true }));
vi.mock("@/api", () => ({ api: {
  mPlanReach: async () => ({ matched: 0, reachable: 0, pushable: 0, skips: [] }),
  mScheduleReach: mock.schedule, mSendReach: mock.send,
} }));

it("schedules the chosen store-local time with the audience rule even when the current estimate is zero", async () => {
  const audiences = [{ type: "SEGMENT" as const, value: "SG1" }];
  setPendingAudience(audiences, "Segment");
  const redirectTo = vi.fn();
  Object.assign(uni, { redirectTo });
  mock.schedule.mockRejectedValueOnce(new Error("offline")).mockResolvedValue({ taskNo: "R1" });
  const page = mount(Page, { global: {
    mocks: { $t: (key: string) => key },
    stubs: { "sh-scaffold": { template: "<main><slot/></main>" }, "biz-audience-picker": true, "sh-stat": true },
  } });
  mock.show();
  await flushPromises();
  await page.get("input").setValue("Hello");
  await page.get("switch").trigger("change");
  await page.get('picker[mode="date"]').trigger("change", { detail: { value: "2030-08-03" } });
  await page.get('picker[mode="time"]').trigger("change", { detail: { value: "18:45" } });
  await page.get(".send").trigger("tap");
  await flushPromises();
  expect(mock.send).not.toHaveBeenCalled();
  expect(mock.schedule).toHaveBeenCalledWith({ audiences, scene: "NOTICE", title: "Hello", body: "",
    audienceDesc: "Segment", localTime: "2030-08-03T18:45:00" }, expect.any(String));
  await page.get(".send").trigger("tap");
  await flushPromises();
  expect(mock.schedule.mock.calls[1]).toEqual(mock.schedule.mock.calls[0]);
  expect(redirectTo).toHaveBeenCalledWith({ url: "/pages/reach-task/index?taskNo=R1" });
  page.unmount();
});
