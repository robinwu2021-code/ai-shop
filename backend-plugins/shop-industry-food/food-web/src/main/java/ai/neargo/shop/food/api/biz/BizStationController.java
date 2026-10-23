package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StationContracts.CreateStationReq;
import ai.neargo.shop.food.dto.StationContracts.GetStationVO;
import ai.neargo.shop.food.dto.StationContracts.ReplaceStationRoutesReq;
import ai.neargo.shop.food.dto.StationContracts.StationMutationVO;
import ai.neargo.shop.food.dto.StationContracts.StationRoutesVO;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.dto.StationContracts.UpdateStationReq;
import ai.neargo.shop.food.service.StationService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

/** Original goods permissions protect store-specific kitchen definitions through trusted platform scope. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizStationController {
    private final StationService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizStationController(StationService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        this.service = service; this.stores = stores; this.scope = scope;
    }

    @PostMapping("/biz/x/food/stores/{storeNo}/stations")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public StationMutationVO createStation(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @RequestBody CreateStationReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        queryFields(request,Set.of()); var c=requireStore(storeNo,request);
        return service.createStation(c.merchantNo(),storeNo,SecurityUtils.currentUserNo(),req,key);
    }
    @PutMapping("/biz/x/food/stores/{storeNo}/stations/{configNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public StationMutationVO updateStation(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("configNo") @NotBlank @Size(max=32) String number,@Valid @RequestBody UpdateStationReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        queryFields(request,Set.of()); var c=requireStore(storeNo,request);
        return service.updateStation(c.merchantNo(),storeNo,number,SecurityUtils.currentUserNo(),req,key);
    }
    @GetMapping("/biz/x/food/stores/{storeNo}/stations/{configNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public GetStationVO getStation(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("configNo") @NotBlank @Size(max=32) String number,HttpServletRequest request) {
        queryFields(request,Set.of()); var c=requireStore(storeNo,request);
        return service.getStation(c.merchantNo(),storeNo,number);
    }
    @GetMapping("/biz/x/food/stores/{storeNo}/stations")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PageData<StationVO> listStations(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @RequestParam(value="page",required=false) Integer page,
            @RequestParam(value="size",required=false) Integer size,
            HttpServletRequest request) {
        queryFields(request,Set.of("page","size")); var c=requireStore(storeNo,request);
        return service.listStations(c.merchantNo(),storeNo,page,size);
    }
    @PutMapping("/biz/x/food/stores/{storeNo}/sku-routes/{skuNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public OperationVO replaceRoutes(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("skuNo") @NotBlank @Size(max=32) String sku,@Valid @RequestBody ReplaceStationRoutesReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        queryFields(request,Set.of()); var c=requireStore(storeNo,request);
        return service.replaceRoutes(c.merchantNo(),storeNo,sku,SecurityUtils.currentUserNo(),req,key);
    }
    @GetMapping("/biz/x/food/stores/{storeNo}/sku-routes/{skuNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public StationRoutesVO getRoutes(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("skuNo") @NotBlank @Size(max=32) String sku,HttpServletRequest request) {
        queryFields(request,Set.of()); var c=requireStore(storeNo,request);
        return service.getRoutes(c.merchantNo(),storeNo,sku);
    }

    private static void queryFields(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private BizContext requireStore(String storeNo, HttpServletRequest request) {
        var context = BizContext.current();
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), context.merchantNo(), storeNo,
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        var card = stores.cards(List.of(storeNo)).get(storeNo);
        if (card == null) throw BizException.of(ErrorCode.NOT_FOUND);
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), card.entityNo(), card.storeNo(),
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        return context;
    }
}
