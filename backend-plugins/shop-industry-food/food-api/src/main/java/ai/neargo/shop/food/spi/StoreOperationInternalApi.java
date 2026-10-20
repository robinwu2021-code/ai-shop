package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Platform-to-food read contract; identity coordinates come from the authenticated platform, never public JSON. */
public interface StoreOperationInternalApi {
    String POLICY_PATH = "/internal/food/store-policy/get";

    /** Reads an authorized store snapshot; no writes, defaults or transaction grants are created. */
    @PostExchange(POLICY_PATH)
    StoreOperationPolicyResultVO getPolicy(@RequestBody Lookup request);

    record Lookup(@NotBlank @Size(max = 32) String entityNo,
                  @NotBlank @Size(max = 32) String storeNo, Instant at) { }
}
