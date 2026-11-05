package ai.neargo.shop.merchant.paysync;

import ai.neargo.shop.auth.Perms;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 运营：门店全量（或指定门店号）同步到支付系统（门店信息同步设计 §5.3）。未开启同步时返回 0。 */
@Profile("ops")
@RestController
public class OpsStoreSyncController {

    public record SyncRequest(List<String> storeNos) {
    }

    private final ObjectProvider<StoreSyncPublisher> publisher;

    public OpsStoreSyncController(ObjectProvider<StoreSyncPublisher> publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/ops/stores/nearpay-sync")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_BAN + "')")
    public Map<String, Object> sync(@RequestBody(required = false) SyncRequest req) {
        var p = publisher.getIfAvailable();
        if (p == null) {
            return Map.of("enabled", false, "queued", 0);
        }
        List<String> stores = req == null || req.storeNos() == null ? List.of() : req.storeNos();
        if (stores.size() > 1000 || stores.stream().anyMatch(s -> s == null || !s.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("storeNos invalid");
        }
        return Map.of("enabled", true, "queued", p.publishAll(stores));
    }
}
