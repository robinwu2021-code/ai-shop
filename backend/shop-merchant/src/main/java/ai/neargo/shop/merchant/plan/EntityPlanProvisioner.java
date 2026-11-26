package ai.neargo.shop.merchant.plan;

/**
 * 主体一激活就该有的默认订阅。
 *
 * <p>商户核心在开通主体时调它，而不是自己写套餐表：套餐在新设计里属于平台商城库，和主体不在一个库。
 * 这一步可以重复执行（已有订阅就什么都不做），所以两边各自提交也安全。
 */
public interface EntityPlanProvisioner {

    /** 主体还没有订阅时建一行免费档；已有就不动。 */
    void ensureFreePlan(String entityNo);
}
