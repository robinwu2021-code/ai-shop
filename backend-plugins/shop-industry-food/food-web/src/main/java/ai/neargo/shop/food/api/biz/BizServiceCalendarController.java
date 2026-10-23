package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCalendarReq;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Store-scoped calendar editing reuses the platform identity, permission and directory facts. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizServiceCalendarController {
    private final StoreOperationService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizServiceCalendarController(StoreOperationService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        this.service = service;
        this.stores = stores;
        this.scope = scope;
    }

    /** Saves a business-day restriction; never treats an OPEN exception as permission to extend store hours. */
    @PutMapping("/biz/x/food/stores/{storeNo}/calendar/{localDate}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public OperationVO saveCalendar(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("localDate") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody SaveCalendarReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        BizContext context = requireStore(storeNo, request);
        return service.saveCalendar(context.merchantNo(), storeNo, date, SecurityUtils.currentUserNo(), req, key);
    }

    private BizContext requireStore(String storeNo, HttpServletRequest request) {
        BizContext context = BizContext.current();
        // Check path/header before directory lookup as well as against the directory's actual owner.
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), context.merchantNo(), storeNo,
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        var card = stores.cards(List.of(storeNo)).get(storeNo);
        if (card == null) throw BizException.of(ErrorCode.NOT_FOUND);
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), card.entityNo(), card.storeNo(),
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        return context;
    }
}
