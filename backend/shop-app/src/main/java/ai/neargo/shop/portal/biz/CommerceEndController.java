package ai.neargo.shop.portal.biz;

import ai.neargo.commerce.api.access.*;
import ai.neargo.shop.commercebridge.ShopCommerceEndContextAdapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Profile("api")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class CommerceEndController {
    private final ShopCommerceEndContextAdapter bridge;
    private final EndAccessFacade access;

    CommerceEndController(ShopCommerceEndContextAdapter bridge, EndAccessFacade access) {
        this.bridge = bridge;
        this.access = access;
    }

    record Enter(String endCode, String clientId) {}

    @PostMapping("/biz/stores/{storeNo}/end-sessions")
    EndContextToken issue(@PathVariable String storeNo, @RequestBody Enter body) {
        return access.issueEndContext(bridge.command(storeNo, body.endCode(), body.clientId()));
    }

    @GetMapping("/biz/stores/{storeNo}/end-access")
    AccessDecision status(
            @PathVariable String storeNo,
            @RequestParam String endCode,
            @RequestParam String clientId) {
        var decision = access.inspect(bridge.command(storeNo, endCode, clientId));
        if (decision.status() == AccessStatus.ACCOUNT_INVALID
                || decision.status() == AccessStatus.CHECK_UNAVAILABLE)
            throw new AccessDenied(decision.status());
        return decision;
    }

    @GetMapping("/biz/stores/{storeNo}/pos/access")
    Map<String, String> pos(@PathVariable String storeNo) {
        return Map.of("storeNo", storeNo, "endCode", "APP_POS");
    }
}
