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
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Store-owned table areas use the original platform identity, permissions and directory facts. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizTableAreaController {
    private final TableService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizTableAreaController(TableService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        this.service = service;
        this.stores = stores;
        this.scope = scope;
    }

    /** Only the authorized store contributes both rows and pagination total. */
    @GetMapping("/biz/x/food/stores/{storeNo}/table-areas")
    @PreAuthorize("@perm.canAnyBiz('" + BizPerms.STORE + "','" + BizPerms.ORDER_VIEW + "')")
    public PageData<TableAreaVO> listTableAreas(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @ModelAttribute ListTableAreasQuery query, HttpServletRequest request) {
        var context = requireStore(storeNo, request);
        return service.listTableAreas(context.merchantNo(), storeNo, query);
    }

    /** The actor and owner come from authentication; client keys only protect retry semantics. */
    @PostMapping("/biz/x/food/stores/{storeNo}/table-areas")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public TableAreaResultVO createTableArea(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @RequestBody CreateTableAreaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context = requireStore(storeNo, request);
        return service.createTableArea(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), req, key);
    }

    /** Knowing an area identifier never permits changing an area outside the current store. */
    @PutMapping("/biz/x/food/table-areas/{areaNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public TableAreaResultVO saveTableArea(@PathVariable("areaNo") @NotBlank @Size(max=32) String areaNo,
            @Valid @RequestBody SaveTableAreaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context = requireStore(BizContext.current().currentStoreNo(), request);
        return service.saveTableArea(context.merchantNo(), context.currentStoreNo(), areaNo,
                SecurityUtils.currentUserNo(), req, key);
    }

    /** The core checks actual tables and occupancies before accepting a disabling transition. */
    @PostMapping("/biz/x/food/table-areas/{areaNo}:set-status")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public TableAreaResultVO setStatusTableArea(@PathVariable("areaNo") @NotBlank @Size(max=32) String areaNo,
            @Valid @RequestBody SetStatusTableAreaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context = requireStore(BizContext.current().currentStoreNo(), request);
        return service.setStatusTableArea(context.merchantNo(), context.currentStoreNo(), areaNo,
                SecurityUtils.currentUserNo(), req, key);
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
