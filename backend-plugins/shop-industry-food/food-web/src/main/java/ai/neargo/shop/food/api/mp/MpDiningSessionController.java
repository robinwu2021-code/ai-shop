package ai.neargo.shop.food.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.DiningSessionVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.StartDiningSessionReq;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Public bootstrap resolves a hashed table credential before looking up any owner or store. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class MpDiningSessionController {
    private final DiningSessionService service;
    private final StoreDirectoryPort stores;
    public MpDiningSessionController(DiningSessionService service,StoreDirectoryPort stores) {
        this.service=service;this.stores=stores;
    }
    @PostMapping("/mp/x/food/sessions")
    public DiningSessionVO startSession(@Valid @RequestBody StartDiningSessionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key) {
        var target=service.resolveTableToken(req.tableToken());
        var card=stores.cards(List.of(target.storeNo())).get(target.storeNo());
        if(card==null || !card.active() || !target.entityNo().equals(card.entityNo())
                || !target.storeNo().equals(card.storeNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        return service.startSession(card.entityNo(),card.storeNo(),SecurityUtils.currentUserNoOrNull(),req,key);
    }
    @PostMapping("/mp/x/food/sessions/{sessionNo}:end")
    public OperationResultVO endSession(@PathVariable("sessionNo") @NotBlank @Size(max=32) String session,
            @RequestHeader("X-Food-Session") @NotBlank @Size(max=128) String access,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key) {
        return service.endSession(session,access,SecurityUtils.currentUserNoOrNull(),key);
    }
}
