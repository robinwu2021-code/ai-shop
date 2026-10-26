package ai.neargo.shop.merchant.service;

/**
 * 收款商户双写的挂钩（TDD-支付服务-I4进件与收款商户 S4）：旧表的每个写点在改完后报一声主体号。
 * 有事务时等提交后才同步（同一事务里同一主体只同步一次），没有事务立即同步；失败只记日志、不往外抛。
 */
public interface PayMerchantMirror {

    void changed(String entityNo);
}
