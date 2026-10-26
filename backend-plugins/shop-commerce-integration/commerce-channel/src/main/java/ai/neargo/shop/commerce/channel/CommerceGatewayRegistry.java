package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.CommerceGateway;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 未装配的协议没有默认网关；重复profile启动即失败。 */
public final class CommerceGatewayRegistry {
    private final Map<String, CommerceGateway> gateways;

    public CommerceGatewayRegistry(Collection<CommerceGateway> candidates) {
        Map<String, CommerceGateway> index = new HashMap<>();
        for (CommerceGateway gateway : candidates) {
            if (gateway.profileNo() == null
                    || gateway.profileNo().isBlank()
                    || index.putIfAbsent(gateway.profileNo(), gateway) != null) {
                throw new IllegalStateException("Invalid or duplicate commerce gateway profile");
            }
        }
        gateways = Map.copyOf(index);
    }

    public Optional<CommerceGateway> find(String profileNo) {
        return Optional.ofNullable(gateways.get(profileNo));
    }
}
