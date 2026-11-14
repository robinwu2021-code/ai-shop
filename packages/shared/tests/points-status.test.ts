import { expect, it } from "vitest";
import { pointsTypeStatusKey } from "../src/utils/points-status";
it.each([
 ["DRAFT", "points.typeStateDraft"], ["ACTIVE", "points.typeStateActive"],
 ["DRAINING", "points.typeStateDraining"], ["RETIRED", "points.typeStateRetired"],
 ["FUTURE_STATE", "points.typeStateUnknown"], [undefined, "points.typeStateUnknown"],
])("maps %s to a known business translation key", (status, key) => expect(pointsTypeStatusKey(status)).toBe(key));
it("distinguishes paused earning and does not override non-active lifecycles", () => {
 expect(pointsTypeStatusKey("ACTIVE", true)).toBe("points.typeStateEarningPaused");
 expect(pointsTypeStatusKey("DRAINING", true)).toBe("points.typeStateDraining");
 expect(pointsTypeStatusKey("RETIRED", true)).toBe("points.typeStateRetired");
});
