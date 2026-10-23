package ai.neargo.shop.spi.media;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.HashSet;
import java.util.List;

/** Original uploaded image ownership, not a second media store or an upload endpoint. */
public interface MediaAssetQueryPort {
    /** Unknown, foreign, private and deleted assets are omitted; authority failures must propagate. */
    List<AssetFact> getAssets(String entityNo, String storeNo, List<String> assetKeys);

    /** Canonical original asset keys keep GC references independent of CDN/provider URL changes. */
    record AssetFact(String assetKey, String entityNo, String storeNo, String bizType, String status) { }

    static void validateLookup(String entity, String store, List<String> keys) {
        if (!identifier(entity) || !identifier(store) || keys == null || keys.isEmpty() || keys.size() > 20
                || keys.stream().anyMatch(key -> !validKey(key)) || new HashSet<>(keys).size() != keys.size()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    static boolean validKey(String key) {
        return key != null && key.length() <= 512
                && key.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)+\\.(?i:jpg|jpeg|png|webp|gif)");
    }

    static boolean validFact(AssetFact fact, String entity, String store, List<String> keys) {
        return fact != null && keys.contains(fact.assetKey()) && entity.equals(fact.entityNo())
                && store.equals(fact.storeNo()) && "GOODS".equals(fact.bizType())
                && "ACTIVE".equals(fact.status());
    }

    private static boolean identifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 32 && value.equals(value.trim());
    }
}
