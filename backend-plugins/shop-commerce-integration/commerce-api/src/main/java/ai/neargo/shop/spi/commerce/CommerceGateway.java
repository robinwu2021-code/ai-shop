package ai.neargo.shop.spi.commerce;

import java.util.Set;

/** 平台身份和已实现能力；声明能力不授予账号授权或资金履约资格。 */
public interface CommerceGateway {
    String profileNo();

    Set<Capability> capabilities();

    enum Capability {
        AUTHORIZATION,
        CATALOG,
        AVAILABILITY,
        INVENTORY,
        ORDER,
        FULFILLMENT,
        AFTER_SALE,
        REDEMPTION,
        STATEMENT
    }
}
