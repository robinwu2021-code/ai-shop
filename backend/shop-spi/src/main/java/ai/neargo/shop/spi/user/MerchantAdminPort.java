package ai.neargo.shop.spi.user;

/**
 * platform → user：审核通过后创建/激活商家主体。
 *
 * <p>**审核通过才创建**：驳回的申请不该在库里留下一个「僵尸商家」——
 * 那些记录会出现在商家列表、报表、分账接收方清单里，谁也说不清它算不算数。
 */
public interface MerchantAdminPort {

    /**
     * @param ownerUserNo 申请人的 C 端用户号 —— 他由此获得 B 端作用域
     * @return merchantNo
     */
    String activate(String ownerUserNo, String name, String type);
}
