package ai.neargo.shop.merchant.plan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EntityPlanPurchasePolicyTest {
  @Test
  void activePaidCrossTierRequiresOperatorAgreementInsteadOfSilentLoss() {
    assertFalse(EntityPlanPurchasePolicy.canBuy("PRO", 10000L, "PLATFORM", "CHAIN", 1000));
    assertFalse(EntityPlanPurchasePolicy.canBuy("CHAIN", null, "PLATFORM", "PRO", 1000));
    assertFalse(EntityPlanPurchasePolicy.canBuy("CHAIN", null, "PLATFORM", "CHAIN", 1000));
    assertTrue(EntityPlanPurchasePolicy.canBuy("PRO", 10000L, "SELF_PAID", "PRO", 1000));
    assertTrue(EntityPlanPurchasePolicy.canBuy("FREE", null, "SELF_PAID", "CHAIN", 1000));
    assertTrue(EntityPlanPurchasePolicy.canBuy("CHAIN", 999L, "SELF_PAID", "PRO", 1000));
    assertFalse(EntityPlanPurchasePolicy.canBuy("PRO", 10000L, "SELF_PAID", "FREE", 1000));
  }

  @Test
  void renewalPreservesPaidRemainingDaysButDoesNotCarryTrialDays() {
    assertEquals(
        10000L + 30L * 86400000,
        EntityPlanPurchasePolicy.expiry("PRO", 10000L, "SELF_PAID", "PRO", 30, 1000));
    assertEquals(
        1000L + 30L * 86400000,
        EntityPlanPurchasePolicy.expiry("PRO", 10000L, "TRIAL", "PRO", 30, 1000));
    assertEquals(
        1000L + 30L * 86400000,
        EntityPlanPurchasePolicy.expiry("FREE", null, "SELF_PAID", "PRO", 30, 1000));
  }
}
