package ai.neargo.shop.merchant.plan;

/** No implicit cross-tier repricing or conversion of remaining paid days. */
public final class EntityPlanPurchasePolicy {
  private EntityPlanPurchasePolicy() {}

  public static boolean canBuy(
      String current, Long expireAt, String grantedBy, String target, long now) {
    return ("FREE".equals(current) || expireAt != null || "TRIAL".equals(grantedBy))
        && !"FREE".equals(target)
        && (target.equals(current)
            || "FREE".equals(current)
            || "TRIAL".equals(grantedBy)
            || expireAt != null && expireAt <= now);
  }

  public static long expiry(
      String current, Long expireAt, String grantedBy, String target, int days, long now) {
    long base =
        target.equals(current) && !"TRIAL".equals(grantedBy) && expireAt != null
            ? Math.max(now, expireAt)
            : now;
    return Math.addExact(base, Math.multiplyExact(days, 86400000L));
  }
}
