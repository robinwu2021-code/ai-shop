package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.HashSet;
import java.util.List;

/** Original dictionary identity facts; neither category templates nor restaurant sale authorization. */
public interface SpecValueCatalogQueryPort {
    /** Omits only explicitly unavailable identities; infrastructure failures must propagate. */
    List<SpecValueFact> getValues(String entityNo, List<String> valueNos);

    /** Preserves both ownership axes and revisions without inventing product, price or unit authority. */
    record SpecValueFact(String valueNo, String dimNo, String label, String dimName, String valueType,
            String usageType, String valueScope, String valueEntityNo, String dimScope, String dimEntityNo,
            long valueVersion, long dimVersion) { }

    /** The same bounded identity contract is enforced locally, before remote calls and after token validation. */
    static void validateLookup(String entity, List<String> numbers) {
        if (!validKey(entity, 32) || numbers == null || numbers.isEmpty() || numbers.size() > 256
                || new HashSet<>(numbers).size() != numbers.size()
                || numbers.stream().anyMatch(n -> !validKey(n, 64))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static boolean validKey(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.trim());
    }
}
