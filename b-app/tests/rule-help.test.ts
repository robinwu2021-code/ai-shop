// @vitest-environment happy-dom
import { expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import { createI18n } from "vue-i18n";
import "./component-setup";
import Help from "../src/components/biz/biz-rule-help.vue";
it("keeps low-frequency explanations off the page until opened and allows closing without losing page state", async () => {
  const i18n = createI18n({ legacy: false, locale: "en", messages: { en: { common: { rules: "Rules" } } } });
  const page = mount(Help, { props: { title: "Audience rules", texts: ["Recalculated before sending", "Excludes opted-out customers"] }, global: { plugins: [i18n] } });
  expect(page.text()).toBe("Rules");
  await page.find(".sh-link").trigger("tap");
  expect(page.text()).toContain("Recalculated before sending");
  expect(page.text()).toContain("Excludes opted-out customers");
  await page.find(".sh-mask").trigger("tap");
  expect(page.text()).toBe("Rules");
  page.unmount();
});
