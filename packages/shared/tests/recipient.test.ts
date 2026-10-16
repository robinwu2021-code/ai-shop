import { describe, expect, it } from "vitest";
import { recipientName } from "../src/utils/recipient";
describe("recipient salutation",()=>{
 it("uses explicit choices and defaults without changing the saved name",()=>{
  expect(recipientName("张","MR")).toBe("张先生");
  expect(recipientName("张","MS")).toBe("张女士");
  expect(recipientName("张")).toBe("张先生/女士");
  expect(recipientName(null)).toBe("");
 });
 it("does not duplicate existing suffix and honors explicit overrides",()=>{
  expect(recipientName("张女士")).toBe("张女士");
  expect(recipientName("张先生/女士","MS")).toBe("张女士");
  expect(recipientName("张女士","MR")).toBe("张先生");
 });
 it("places localized English and Arabic titles before the name",()=>{
  expect(recipientName("张女士","MR",{mr:"Mr.",ms:"Ms.",fallback:"Mr./Ms.",prefix:true})).toBe("Mr. 张");
  expect(recipientName("Zhang","MR",{mr:"Mr.",ms:"Ms.",fallback:"Mr./Ms.",prefix:true})).toBe("Mr. Zhang");
 });
});
