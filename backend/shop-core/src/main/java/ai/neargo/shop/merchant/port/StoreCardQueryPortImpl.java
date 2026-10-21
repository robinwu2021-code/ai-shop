package ai.neargo.shop.merchant.port;

import ai.neargo.shop.spi.user.StoreCardFacts;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Exposes the original directory's bounded cards without copying merchant filters or inventing a second store truth.
 */
@Component
public class StoreCardQueryPortImpl implements StoreCardQueryPort {
    private final StoreDirectoryPort directory;
    public StoreCardQueryPortImpl(StoreDirectoryPort directory) { this.directory = directory; }
    @Override public Map<String, StoreDirectoryPort.StoreCard> cards(Collection<String> numbers) {
        StoreCardQueryPort.validateLookup(numbers);
        var result = directory.cards(numbers);
        StoreCardFacts.response(result, numbers);
        return Map.copyOf(result);
    }
}
