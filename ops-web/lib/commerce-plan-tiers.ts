import type { MallPlanTier } from "@/lib/types";

const industryPlanTiers: readonly MallPlanTier[] = ["FREE", "BASE", "PRO", "GROWTH", "CHAIN"];

export const mallPlanTiers = (typeCode: string): readonly MallPlanTier[] =>
  typeCode === "PAYMENT" ? ["FREE", "BASE"] : industryPlanTiers;
