package ai.neargo.shop.trade.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Reads original immutable allocation documents, without treating their evidence as allocations.
 */
public final class IndustryOrderAllocationPolicy {
    private IndustryOrderAllocationPolicy() {}

    public static List<Allocation> read(ObjectMapper json, String snapshot) {
        var document = json.readTree(snapshot);
        // Current order creation stores evidence alongside allocations; retain historical arrays.
        var values = document.isArray() ? document : document.get("allocations");
        if (values == null || !values.isArray()) throw BizException.of(ErrorCode.CONFLICT);
        return json.convertValue(values, new TypeReference<>() {});
    }
}
