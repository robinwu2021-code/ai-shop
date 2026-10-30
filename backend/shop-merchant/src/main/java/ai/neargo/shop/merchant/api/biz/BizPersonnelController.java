package ai.neargo.shop.merchant.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.dto.AssignPersonnelReq;
import ai.neargo.shop.merchant.dto.ChangePersonnelStatusReq;
import ai.neargo.shop.merchant.dto.CreatePersonnelReq;
import ai.neargo.shop.merchant.dto.LinkPersonnelAccountReq;
import ai.neargo.shop.merchant.dto.PersonnelAccountLinkVO;
import ai.neargo.shop.merchant.dto.PersonnelAssignmentVO;
import ai.neargo.shop.merchant.dto.PersonnelChangeVO;
import ai.neargo.shop.merchant.dto.PersonnelQuery;
import ai.neargo.shop.merchant.dto.PersonnelVO;
import ai.neargo.shop.merchant.service.PersonnelService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Current-store authorization protects personnel maintenance; a business position never grants login rights. */
@RestController
@Validated
@Profile("api")
@RequestMapping("/biz/personnel")
public class BizPersonnelController {
    private final PersonnelService service;
    private final StoreDirectoryPort stores;
    public BizPersonnelController(PersonnelService service, StoreDirectoryPort stores) {
        this.service = service;
        this.stores = stores;
    }
    /** Internal personnel data is not a publicly enumerable staff directory. */
    @GetMapping
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public PageData<PersonnelVO> listPersonnel(@Valid @ModelAttribute PersonnelQuery query,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore, false);
        return service.listPersonnel(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), query);
    }
    /** Initial employment and a canonical STAFF identity commit together without manufacturing a login. */
    @PostMapping
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public PersonnelVO createPersonnel(@Valid @RequestBody CreatePersonnelReq request,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore, false);
        return service.createPersonnel(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), request, key);
    }
    /** Employment qualifications remain business facts, separate from existing account roles. */
    @PostMapping("/{personnelNo}/assignments")
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public PersonnelAssignmentVO assignPersonnel(@PathVariable String personnelNo,
            @Valid @RequestBody AssignPersonnelReq request, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore, false);
        return service.assignPersonnel(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), personnelNo, request, key);
    }
    /** Entity-wide identity binding requires the owner, even when store management is delegated later. */
    @PostMapping("/{personnelNo}/account-links")
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public PersonnelAccountLinkVO linkPersonnelAccount(@PathVariable String personnelNo,
            @Valid @RequestBody LinkPersonnelAccountReq request, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore, true);
        return service.linkPersonnelAccount(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), personnelNo, request, key);
    }
    /** Suspension affects every store and the linked login; it cannot be delegated through one store grant. */
    @PostMapping("/{personnelNo}/status-changes")
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public PersonnelChangeVO changePersonnelStatus(@PathVariable String personnelNo,
            @Valid @RequestBody ChangePersonnelStatusReq request, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore, true);
        return service.changePersonnelStatus(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), personnelNo, request, key);
    }
    private void requireStore(String requestedStore, boolean ownerOnly) {
        var context = BizContext.current();
        if (ownerOnly && !context.owner()) { throw BizException.of(ErrorCode.FORBIDDEN); }
        String entity = BizContext.requireMerchantNo();
        String store = BizContext.requireStoreNo();
        var card = stores.cards(List.of(store)).get(store);
        if (card == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        StoreRequestScopeValidator.requireStoreTarget(entity, store, card.entityNo(), card.storeNo(),
                requestedStore, context.storeNos());
    }
}
