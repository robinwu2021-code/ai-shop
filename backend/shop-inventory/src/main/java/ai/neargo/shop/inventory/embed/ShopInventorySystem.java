package ai.neargo.shop.inventory.embed;

/**
 * ai-shop 在进销存里的系统码。
 *
 * <p>业主的外部引用（主体号）只在同一系统内唯一（V16：唯一键是「系统 + 外部引用」）。
 * 测试环境共用一个库，ShareHub 的运营方号可能与商城的主体号相同 ——
 * 商城侧所有按主体号找业主的查询都必须带上这个系统码。
 */
public final class ShopInventorySystem {

    public static final String CODE = "SHOP";

    private ShopInventorySystem() {
    }
}
