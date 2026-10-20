package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CreatePolicyReq;
import ai.neargo.shop.food.dto.OperationVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Trusted platform command; actor and store facts are established before constructing this internal request. */
public interface StorePolicyPublicationInternalApi {
    String POLICY_CREATE_PATH = "/internal/food/store-policy/create";

    /** Publishes once under the original key; transport retries never create a new key. */
    @PostExchange(POLICY_CREATE_PATH)
    OperationVO createPolicy(@RequestBody Command request);

    record Command(@NotBlank @Size(max = 32) String entityNo,
                   @NotBlank @Size(max = 32) String storeNo,
                   @NotBlank @Size(max = 32) String actorNo,
                   @NotBlank @Size(max = 128) String idempotencyKey,
                   @NotNull @Valid CreatePolicyReq policy) { }
}
