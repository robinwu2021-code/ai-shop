package ai.neargo.shop.community.port;

import ai.neargo.shop.community.service.ConsumerProfileResolver;
import ai.neargo.shop.spi.reach.ConsumerProfile;
import ai.neargo.shop.spi.reach.ConsumerProfilePort;
import org.springframework.stereotype.Component;

/**
 * {@link ConsumerProfilePort} 的薄转发（ADR-034）。判据在 {@link ConsumerProfileResolver}，这里一行不加。
 */
@Component
public class ConsumerProfilePortImpl implements ConsumerProfilePort {

    private final ConsumerProfileResolver resolver;

    public ConsumerProfilePortImpl(ConsumerProfileResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public ConsumerProfile resolve(String communityNo, String regionCode, Integer latE6, Integer lngE6) {
        return resolver.resolve(communityNo, regionCode, latE6, lngE6);
    }
}
