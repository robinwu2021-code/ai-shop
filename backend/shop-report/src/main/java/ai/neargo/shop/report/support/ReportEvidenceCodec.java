package ai.neargo.shop.report.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import tools.jackson.databind.json.JsonMapper;

/** Reuses the platform canonical digest; JSON is evidence, never executable configuration. */
public final class ReportEvidenceCodec {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ReportEvidenceCodec() {}
    public static String json(Object value) { return JSON.writeValueAsString(value); }
    public static <T> T read(String value, Class<T> type) { return JSON.readValue(value, type); }
    public static String hash(Object value) { return PricingSnapshotHash.of(value); }
    public static Map<String,String> map(String value) {
        var result = new TreeMap<String,String>();
        JSON.readTree(value).properties().forEach(e -> {
            if (!e.getValue().isString()) throw new IllegalStateException("REPORT_INVALID_STORED_DIMENSION");
            result.put(e.getKey(),e.getValue().asString());
        });
        return Map.copyOf(result);
    }
    public static List<String> strings(String value) {
        var result = new ArrayList<String>();
        JSON.readTree(value).forEach(v -> {
            if (!v.isString()) throw new IllegalStateException("REPORT_INVALID_STORED_REFERENCE");
            result.add(v.asString());
        });
        return List.copyOf(result);
    }
}
