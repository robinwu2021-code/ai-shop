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
import ai.neargo.shop.food.dto.KitchenContracts.EventVO;
import ai.neargo.shop.food.dto.KitchenContracts.RemakeReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskVO;
import ai.neargo.shop.food.dto.KitchenContracts.TicketVO;
import ai.neargo.shop.food.service.KitchenTaskService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** KDS actions expose fulfillment only through trusted current-store scope and existing receive permissions. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizKitchenTaskController {
    private final KitchenTaskService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;
    public BizKitchenTaskController(KitchenTaskService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { this.service=service; this.stores=stores; this.scope=scope; }

    @GetMapping("/biz/x/food/stores/{storeNo}/kitchen/tasks")
    @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")
    public PageData<TaskVO> queue(@PathVariable @NotBlank @Size(max=32) String storeNo,
            @RequestParam @NotBlank @Size(max=32) String stationNo,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,HttpServletRequest request) {
        var c=requireStore(storeNo,request,Set.of("stationNo","status","page","size"));
        if (page<1 || size<1 || size>100 || status!=null
                && !Set.of("HELD","QUEUED","MAKING","READY").contains(status)) throw bad();
        var rows=service.queue(c.merchantNo(),storeNo,stationNo,null).stream()
                .filter(t -> status==null?!Set.of("SERVED","VOIDED").contains(t.status()):status.equals(t.status()))
                .toList();
        long skip=(long)(page-1)*size;
        return PageData.of(rows.stream().skip(skip).limit(size).toList(),rows.size(),page,size);
    }
    @PostMapping("/biz/x/food/tasks/{taskNo}:start")
    @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")
    public EventVO start(@PathVariable @NotBlank @Size(max=32) String taskNo,
            @Valid @RequestBody TaskDeltaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var c=requireCurrentStore(request);
        return service.act(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),taskNo,
                new TaskActionReq(req.version(),"START",req.qty(),req.reason()),key);
    }
    @PostMapping("/biz/x/food/tasks/{taskNo}:ready")
    @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")
    public EventVO ready(@PathVariable @NotBlank @Size(max=32) String taskNo,
            @Valid @RequestBody TaskDeltaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var c=requireCurrentStore(request);
        return service.act(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),taskNo,
                new TaskActionReq(req.version(),"READY",req.qty(),req.reason()),key);
    }
    @PostMapping("/biz/x/food/tasks/{taskNo}:serve")
    @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")
    public EventVO serve(@PathVariable @NotBlank @Size(max=32) String taskNo,
            @Valid @RequestBody TaskDeltaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var c=requireCurrentStore(request);
        return service.act(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),taskNo,
                new TaskActionReq(req.version(),"SERVE",req.qty(),req.reason()),key);
    }
    @PostMapping("/biz/x/food/tasks/{taskNo}:hold")
    @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")
    public EventVO hold(@PathVariable @NotBlank @Size(max=32) String taskNo,
            @Valid @RequestBody TaskDeltaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var c=requireCurrentStore(request);
        return service.act(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),taskNo,
                new TaskActionReq(req.version(),"HOLD",req.qty(),req.reason()),key);
    }
    @PostMapping("/biz/x/food/tasks/{taskNo}:fire")
    @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")
    public EventVO fire(@PathVariable @NotBlank @Size(max=32) String taskNo,
            @Valid @RequestBody TaskDeltaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var c=requireCurrentStore(request);
        return service.act(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),taskNo,
                new TaskActionReq(req.version(),"FIRE",req.qty(),req.reason()),key);
    }
    @PostMapping("/biz/x/food/tasks/{taskNo}:urge")
    @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")
    public EventVO urge(@PathVariable @NotBlank @Size(max=32) String taskNo,
            @Valid @RequestBody TaskDeltaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var c=requireCurrentStore(request);
        return service.act(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),taskNo,
                new TaskActionReq(req.version(),"URGE",req.qty(),req.reason()),key);
    }
    @PostMapping("/biz/x/food/tasks/{taskNo}:void")
    @PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')")
    public EventVO voidTask(@PathVariable @NotBlank @Size(max=32) String taskNo,
            @Valid @RequestBody TaskDeltaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var c=requireCurrentStore(request);
        return service.act(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),taskNo,
                new TaskActionReq(req.version(),"VOID",req.qty(),req.reason()),key);
    }
    @PostMapping("/biz/x/food/tasks/{taskNo}:loss")
    @PreAuthorize("@perm.canBiz('"+BizPerms.STOCK+"')")
    public EventVO loss(@PathVariable @NotBlank @Size(max=32) String taskNo,
            @Valid @RequestBody TaskDeltaReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var c=requireCurrentStore(request);
        return service.act(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),taskNo,
                new TaskActionReq(req.version(),"LOSS",req.qty(),req.reason()),key);
    }
    @PostMapping("/biz/x/food/tasks/{taskNo}:remake")
    @PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')")
    public TicketVO remake(@PathVariable @NotBlank @Size(max=32) String taskNo,
            @Valid @RequestBody RemakeReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var c=requireCurrentStore(request);
        return service.remake(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),taskNo,req,key);
    }
    @GetMapping("/biz/x/food/tasks/{taskNo}/events")
    @PreAuthorize("@perm.canBiz('"+BizPerms.RECEIVE+"')")
    public List<EventVO> events(@PathVariable @NotBlank @Size(max=32) String taskNo,HttpServletRequest request) {
        var c=requireCurrentStore(request); return service.events(c.merchantNo(),c.currentStoreNo(),taskNo);
    }
    private BizContext requireCurrentStore(HttpServletRequest request) {
        var c=BizContext.current(); return requireStore(c.currentStoreNo(),request,Set.of());
    }
    private BizContext requireStore(String store,HttpServletRequest request,Set<String> fields) {
        if (!fields.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length!=1)) throw bad();
        var c=BizContext.current();
        scope.requireStoreTarget(c.merchantNo(),c.currentStoreNo(),c.merchantNo(),store,
                request.getHeader(BizContextFilter.STORE_HEADER),c.storeNos());
        var card=stores.cards(List.of(store)).get(store); if (card==null) throw BizException.of(ErrorCode.NOT_FOUND);
        scope.requireStoreTarget(c.merchantNo(),c.currentStoreNo(),card.entityNo(),card.storeNo(),
                request.getHeader(BizContextFilter.STORE_HEADER),c.storeNos()); return c;
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
}
