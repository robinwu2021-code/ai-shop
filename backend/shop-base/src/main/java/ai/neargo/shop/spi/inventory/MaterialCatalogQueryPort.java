package ai.neargo.shop.spi.inventory;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Reads inventory-owned material definitions without creating identities or granting stock authority. */
public interface MaterialCatalogQueryPort {
    /** Missing/inactive identities are omitted; infrastructure failures must propagate. */
    List<MaterialFact> getMaterials(String entityNo, List<String> itemIds);

    /** Content hash identifies a definition, never a monotonic revision or compare-and-swap token. */
    record MaterialFact(
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String itemId,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String baseUom,
            @JsonDeserialize(using=MaterialCatalogFacts.WeighedDeserializer.class) Integer weighed,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String dataSource,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unitSupport,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String postingSupport,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String definitionHash) { }

    /** Identical bounds at the local provider and remote caller prevent unbounded identity queries. */
    static void validateLookup(String entityNo, List<String> itemIds) {
        if (!MaterialCatalogFacts.validKey(entityNo, 32) || itemIds == null || itemIds.isEmpty()
                || itemIds.size() > 256 || new HashSet<>(itemIds).size() != itemIds.size()
                || itemIds.stream().anyMatch(id -> !MaterialCatalogFacts.validKey(id, 32))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
