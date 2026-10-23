package ai.neargo.shop.spi.user;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

/** Bounded original store identity and hours, without introducing a partial implementation of the full directory. */
public interface StoreCardQueryPort {
    /**
     * Missing stores are omitted; READONLY is retained and inactive entities stay excluded by the original authority.
     */
    Map<String, StoreDirectoryPort.StoreCard> cards(Collection<String> storeNos);

    static void validateLookup(Collection<String> numbers) {
        if (numbers == null || numbers.isEmpty() || numbers.size() > 100
                || numbers.stream().anyMatch(n -> !StoreCardFacts.key(n))
                || new HashSet<>(numbers).size() != numbers.size()) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
