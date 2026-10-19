import { expect, it } from "vitest";
import { pointsCommand } from "./points-command";
it("retains the original intent on response loss but replaces it when the command changes", () => {
  const first = pointsCommand({ typeCode: "T", enabled: false, reason: "pause" });
  const retry = pointsCommand({ typeCode: "T", enabled: false, reason: "pause" }, first.intent);
  expect(retry.command.requestId).toBe(first.command.requestId);
  expect(pointsCommand({ typeCode: "T", enabled: true, reason: "restore" }, retry.intent).command.requestId).not.toBe(first.command.requestId);
});
