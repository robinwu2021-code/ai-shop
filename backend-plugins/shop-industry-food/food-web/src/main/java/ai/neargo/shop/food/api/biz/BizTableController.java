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
import ai.neargo.shop.food.dto.ArchiveTableReq;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.ListTablesQuery;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.SaveTableReq;
import ai.neargo.shop.food.dto.SetStatusTableReq;
import ai.neargo.shop.food.dto.TableResultVO;
import ai.neargo.shop.food.dto.TableVO;
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

/** Table configuration shares the platform store scope, trusted actor and original permission. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizTableController {
    private final TableService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;
    public BizTableController(TableService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        this.service=service;
        this.stores=stores;
        this.scope=scope;
    }

    /** Pagination and filters cannot select another owner's table data. */
    @GetMapping("/biz/x/food/stores/{storeNo}/tables")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public PageData<TableVO> listTables(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @ModelAttribute ListTablesQuery query, HttpServletRequest request) {
        var context=requireStore(storeNo,request);
        return service.listTables(context.merchantNo(),storeNo,query);
    }

    /** Object lookup remains bound to the user's current store. */
    @GetMapping("/biz/x/food/tables/{tableNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public TableResultVO getTable(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo,
            HttpServletRequest request) {
        var context=requireStore(BizContext.current().currentStoreNo(),request);
        return service.getTable(context.merchantNo(),context.currentStoreNo(),tableNo);
    }

    /** Creation cannot accept client-owned identity or occupancy state. */
    @PostMapping("/biz/x/food/stores/{storeNo}/tables")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public TableResultVO createTable(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @RequestBody CreateTableReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context=requireStore(storeNo,request);
        return service.createTable(context.merchantNo(),storeNo,SecurityUtils.currentUserNo(),req,key);
    }

    /** The authoritative core checks version and occupancy before changing configuration. */
    @PutMapping("/biz/x/food/tables/{tableNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public TableResultVO saveTable(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo,
            @Valid @RequestBody SaveTableReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context=requireStore(BizContext.current().currentStoreNo(),request);
        return service.saveTable(context.merchantNo(),context.currentStoreNo(),tableNo,
                SecurityUtils.currentUserNo(),req,key);
    }

    /** The authoritative core checks version and occupancy before changing configuration. */
    @PostMapping("/biz/x/food/tables/{tableNo}:set-status")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public TableResultVO setStatusTable(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo,
            @Valid @RequestBody SetStatusTableReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context=requireStore(BizContext.current().currentStoreNo(),request);
        return service.setStatusTable(context.merchantNo(),context.currentStoreNo(),tableNo,
                SecurityUtils.currentUserNo(),req,key);
    }

    /** The authoritative core checks version and occupancy before changing configuration. */
    @PostMapping("/biz/x/food/tables/{tableNo}:archive")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public OperationResultVO archiveTable(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo,
            @Valid @RequestBody ArchiveTableReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context=requireStore(BizContext.current().currentStoreNo(),request);
        return service.archiveTable(context.merchantNo(),context.currentStoreNo(),tableNo,
                SecurityUtils.currentUserNo(),req,key);
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
