package ai.neargo.shop.trade.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable database evidence returned only by named fee-owner queries. */
public final class PricingFeeEvidenceRow {
    private final Map<String, Object> values;

    public PricingFeeEvidenceRow(Map<String, Object> source) {
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(key.toLowerCase(Locale.ROOT), value));
        values = Collections.unmodifiableMap(copy);
    }

    public Object get(String name) {
        return values.get(name);
    }
}
