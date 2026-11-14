import { afterEach, expect, it } from "vitest";
import { pointsEarningMock } from "./api/mocks/points-earning";
import { ApiError } from "./api/error";
import { useLocaleStore } from "./stores/locale";

afterEach(() => useLocaleStore.setState({ locale: "zh" }));

it("rejects unavailable points in the operator's selected language", async () => {
  for (const [locale, message] of [["zh", "请选择已生效积分"], ["en", "Select an active points type."]] as const) {
    useLocaleStore.setState({ locale });
    const failure = await pointsEarningMock.previewPointsEarning({ typeCode: "MISSING_TYPE", cashMinor: 100 })
      .catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toHaveProperty("message", message);
  }
});
