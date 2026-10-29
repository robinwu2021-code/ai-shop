package ai.neargo.shop.food.api.mp;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Challenge;
import ai.neargo.shop.food.dto.OfflineDraftContracts.ChallengeRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Enroll;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Lease;
import ai.neargo.shop.food.dto.OfflineDraftContracts.LeaseRequest;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Receipt;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Replay;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Signer;
import ai.neargo.shop.food.dto.OfflineDraftContracts.Upload;
import ai.neargo.shop.food.service.OfflineDraftService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Scope derives from the actual store owner; credential/possession/session proofs authorize each
 * operation.
 */
@RestController
@Profile("api")
@Validated
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@ConditionalOnProperty(name = "shop.food.offline-drafts.enabled", havingValue = "true")
@RequestMapping("/mp/x/food/stores/{storeNo}/offline-drafts")
public class MpOfflineDraftController {
    private final OfflineDraftService service;
    private final StoreDirectoryPort stores;

    public MpOfflineDraftController(OfflineDraftService service, StoreDirectoryPort stores) {
        this.service = service;
        this.stores = stores;
    }

    private String entity(String store, HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var card = stores.cards(List.of(store)).get(store);
        if (card == null || !card.active() || !store.equals(card.storeNo()))
            throw BizException.of(ErrorCode.NOT_FOUND);
        return card.entityNo();
    }

    @PostMapping("/challenge")
    public Challenge challenge(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestHeader("X-Food-Device") @NotBlank @Size(max = 32) String device,
            @Valid @RequestBody ChallengeRequest request,
            HttpServletRequest http) {
        return service.challenge(entity(storeNo, http), storeNo, device, request);
    }

    @PostMapping("/enroll")
    public Signer enroll(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestHeader("X-Food-Device") @NotBlank @Size(max = 32) String device,
            @Valid @RequestBody Enroll request,
            HttpServletRequest http) {
        return service.enroll(entity(storeNo, http), storeNo, device, request);
    }

    @PostMapping("/lease")
    public Lease acquire(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestHeader("X-Food-Device") @NotBlank @Size(max = 32) String device,
            @Valid @RequestBody LeaseRequest request,
            HttpServletRequest http) {
        return service.acquire(entity(storeNo, http), storeNo, device, request);
    }

    @PostMapping("/capture")
    public Receipt capture(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestHeader("X-Food-Device") @NotBlank @Size(max = 32) String device,
            @Valid @RequestBody Upload request,
            HttpServletRequest http) {
        return service.capture(entity(storeNo, http), storeNo, device, request);
    }

    @PostMapping("/replay")
    public Receipt replay(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestHeader("X-Food-Device") @NotBlank @Size(max = 32) String device,
            @Valid @RequestBody Replay request,
            HttpServletRequest http) {
        return service.replay(entity(storeNo, http), storeNo, device, request);
    }

    @PostMapping("/state")
    public Lease state(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestHeader("X-Food-Device") @NotBlank @Size(max = 32) String device,
            @Valid @RequestBody ai.neargo.shop.food.dto.CartAccess access,
            HttpServletRequest http) {
        return service.state(entity(storeNo, http), storeNo, device, access);
    }
}
