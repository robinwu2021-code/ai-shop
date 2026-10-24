package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.QueueContracts.ChangeReq;
import ai.neargo.shop.food.dto.QueueContracts.IssueReq;
import ai.neargo.shop.food.dto.QueueContracts.Query;
import ai.neargo.shop.food.dto.QueueContracts.QueueVO;
import ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq;
import ai.neargo.shop.food.service.QueueService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

/** Reception staff scope and actual check-owner permissions are preserved. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizQueueController {
    private final QueueService service;
    private final FoodBizStoreScope scope;
    public BizQueueController(QueueService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator) {
        this.service=service; scope=new FoodBizStoreScope(stores,validator);
    }
    @PostMapping("/biz/x/food/queue-tickets")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public QueueVO issue(@Valid @RequestBody IssueReq body,@RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var c=scope.current(request);
        return service.issue(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),body,key);
    }
    @GetMapping("/biz/x/food/queue-tickets")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public PageData<QueueVO> list(@RequestParam LocalDate businessDate,@RequestParam(required=false) String status,
            @RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size,HttpServletRequest request) {
        var c=scope.current(request);
        return service.list(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),new Query(businessDate,status,page,size));
    }
    @GetMapping("/biz/x/food/queue-tickets/{ticketNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public QueueVO get(@PathVariable @NotBlank @Size(max=32) String ticketNo,HttpServletRequest request) {
        var c=scope.current(request);
        return service.get(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),ticketNo);
    }
    @PostMapping("/biz/x/food/queue-tickets:call-next")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public QueueVO callNext(@RequestParam LocalDate businessDate,@RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var c=scope.current(request);
        return service.callNext(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),businessDate,key);
    }
    @PostMapping("/biz/x/food/queue-tickets/{ticketNo}:call")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public QueueVO call(@PathVariable @NotBlank @Size(max=32) String ticketNo,
            @Valid @RequestBody ChangeReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var c=scope.current(request);
        return service.change(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),ticketNo,"CALL",body,key);
    }
    @PostMapping("/biz/x/food/queue-tickets/{ticketNo}:cancel")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public QueueVO cancel(@PathVariable @NotBlank @Size(max=32) String ticketNo,
            @Valid @RequestBody ChangeReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var c=scope.current(request);
        return service.change(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),ticketNo,"CANCEL",body,key);
    }
    @PostMapping("/biz/x/food/queue-tickets/{ticketNo}:no-show")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public QueueVO noShow(@PathVariable @NotBlank @Size(max=32) String ticketNo,
            @Valid @RequestBody ChangeReq body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var c=scope.current(request);
        return service.change(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),ticketNo,"NO_SHOW",body,key);
    }

    @PostMapping("/biz/x/food/queue-tickets/{ticketNo}:seat")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public QueueVO seat(@PathVariable @NotBlank @Size(max=32) String ticketNo,@Valid @RequestBody SeatQueueReq body,
            HttpServletRequest request) {
        var c=scope.current(request);
        return service.seat(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),ticketNo,body);
    }
    @PostMapping("/biz/x/food/queue-tickets/{ticketNo}:recover-seating")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public QueueVO recover(@PathVariable @NotBlank @Size(max=32) String ticketNo,HttpServletRequest request) {
        var c=scope.current(request);
        return service.recoverSeating(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),ticketNo);
    }
}
