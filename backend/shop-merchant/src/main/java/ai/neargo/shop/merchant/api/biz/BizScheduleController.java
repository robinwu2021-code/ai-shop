package ai.neargo.shop.merchant.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ScheduleVO;
import ai.neargo.shop.merchant.dto.ScheduleQuery;
import ai.neargo.shop.merchant.dto.ScheduleRuleVO;
import ai.neargo.shop.merchant.dto.SaveScheduleRuleReq;
import ai.neargo.shop.merchant.service.ScheduleService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Current-store authorization protects shared schedule maintenance without claiming availability. */
@RestController
@Validated
@Profile("api")
@RequestMapping("/biz")
public class BizScheduleController {
    private final ScheduleService service;
    private final StoreDirectoryPort stores;
    public BizScheduleController(ScheduleService service, StoreDirectoryPort stores) {
        this.service = service;
        this.stores = stores;
    }
    /** Schedule reads remain bounded and authorized by the current store. */
    @GetMapping("/schedules")
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public ScheduleVO listSchedules(@Valid @ModelAttribute ScheduleQuery query,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return service.listSchedules(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), query);
    }
    /** Weekly maintenance preserves old rules and refuses unresolved resource impacts. */
    @PostMapping("/schedule-rules")
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public ScheduleRuleVO saveScheduleRule(@Valid @RequestBody SaveScheduleRuleReq request,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return service.saveScheduleRule(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), request, key);
    }
    private void requireStore(String requestedStore) {
        var context = BizContext.current();
        String entity = BizContext.requireMerchantNo();
        String store = BizContext.requireStoreNo();
        var card = stores.cards(List.of(store)).get(store);
        if (card == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        StoreRequestScopeValidator.requireStoreTarget(entity, store, card.entityNo(), card.storeNo(),
                requestedStore, context.storeNos());
    }
}
