import { expect, it, vi } from "vitest";
import { http } from "@shared/net/http-client";
import { payHttp } from "../src/api/pay-http";

it("requests the actual owner-scoped pay-service status without an invented entity parameter", async () => {
  const value = { status: "NEED_MORE", merchantRef: "PM1", linkStatus: "LINKED", profileStatus: "NEED_MORE", reviewReason: "Registration required" };
  const get = vi.spyOn(http, "get").mockResolvedValueOnce(value);
  expect(await payHttp.mPayServiceStatus()).toEqual(value);
  expect(get).toHaveBeenCalledWith("/biz/merchant/pay-service");
  get.mockRestore();
});
