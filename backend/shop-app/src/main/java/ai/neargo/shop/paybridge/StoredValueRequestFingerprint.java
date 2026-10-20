package ai.neargo.shop.paybridge;

import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Stable original-command fingerprint; never serialize raw customer references to logs. */
@Component
public class StoredValueRequestFingerprint {
    private final ObjectMapper json;

    public StoredValueRequestFingerprint(ObjectMapper json) {
        this.json = json;
    }

    public String reference(String customerRef, String storeNo, String planNo, String versionNo) {
        return ActivityCommandDeduplicator.hash(
                json.writeValueAsString(List.of(customerRef, storeNo, planNo, versionNo)));
    }
}
