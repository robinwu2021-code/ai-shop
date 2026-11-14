package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;

import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Only v3 gains canonical collection order; immutable legacy hashes keep their original algorithms.
 */
public final class CatalogV3Policy {
    private CatalogV3Policy() {}

    /**
     * Time and tax facts are business content; refreshed evidence receipts and deadlines are not.
     */
    public static String hash(Document document, ObjectMapper json) {
        var sources =
                document.sources().stream()
                        .map(
                                s -> {
                                    var source = new TreeMap<String, Object>();
                                    source.put("owner", s.owner());
                                    source.put("sourceRef", s.sourceRef());
                                    source.put("selectionRevision", s.selectionRevision());
                                    source.put("priority", s.priority());
                                    source.put("contentHash", s.proof().contentHash());
                                    source.put("salesOfferRef", s.proof().canonicalSalesOfferRef());
                                    return source;
                                })
                        .toList();
        var content =
                Map.of(
                        "schemaVersion",
                        3,
                        "owner",
                        document.owner(),
                        "currency",
                        document.currency(),
                        "sources",
                        sources,
                        "details",
                        CatalogDetailsPolicy.businessContent(document.details(), json),
                        "availability",
                        document.availability(),
                        "taxBases",
                        document.taxBases());
        if (document.replacement() != null) {
            var extended = new TreeMap<String, Object>(content);
            extended.put("replacement", CatalogReplacementPolicy.business(document.replacement()));
            return ChannelIdentity.hash(
                    json.writeValueAsString(
                            canonical(
                                    json.readValue(json.writeValueAsString(extended), Object.class),
                                    json)));
        }
        return ChannelIdentity.hash(
                json.writeValueAsString(
                        canonical(
                                json.readValue(json.writeValueAsString(content), Object.class),
                                json)));
    }

    private static Object canonical(Object value, ObjectMapper json) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach(
                    (key, child) -> {
                        if (!"receiptNo".equals(key))
                            result.put((String) key, canonical(child, json));
                    });
            return result;
        }
        if (value instanceof List<?> list)
            return list.stream()
                    .map(item -> canonical(item, json))
                    .sorted(Comparator.comparing(json::writeValueAsString))
                    .toList();
        return value;
    }
}
