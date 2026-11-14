package ai.neargo.shop.product.api.biz;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.product.policy.LocalStockAccessPolicy;
import ai.neargo.shop.product.service.StockReplenishmentService;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.Mapping;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** FOOD physical replenishment; the request never controls the source owner, instance or actor. */
@RestController @Profile("api") @Validated
@RequestMapping("/biz/stock-control/replenishments")
@PreAuthorize("@perm.canBiz('"+BizPerms.STOCK+"')")
public class BizStockReplenishmentController {
    public record Register(@NotBlank @Size(max=32) String documentNo,@Min(1) int lineNo,@NotNull @Valid Mapping mapping){
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);}
    }
    public record Quantity(@NotNull @DecimalMin("0.000001") @Digits(integer=18,fraction=6) BigDecimal cumulativeQuantity){
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);}
    }
    public record Reason(@NotBlank @Size(max=256) String reason){
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);}
    }
    public record ReturnQuantity(@NotNull @DecimalMin("0.000001") @Digits(integer=18,fraction=6) BigDecimal cumulativeQuantity,
                                 @NotBlank @Size(max=256) String reason){
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);}
    }
    public record Ack(@NotBlank @Size(max=32) String documentNo,@Min(1) int lineNo){
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);}
    }
    public record ManualRegister(@NotBlank @Size(max=32) String documentNo,@Min(1) int lineNo,@NotNull @Valid Mapping mapping,
                                 @NotBlank @Size(max=32) String movementNo){
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);}
    }
    public record ManualReceipt(@NotBlank @Size(max=32) String movementNo){
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);}
    }
    public record CancelRequest(@NotBlank @Size(max=128) String key,@NotBlank @Size(max=256) String reason) {
        @JsonAnySetter public void unknown(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);}
    }
    private final StockReplenishmentService service;
    private final LocalStockAccessPolicy access;
    public BizStockReplenishmentController(StockReplenishmentService service,LocalStockAccessPolicy access){this.service=service;this.access=access;}
    private Scope scope(String store,String expectedEntity){
        var actual=access.context(store);
        if(expectedEntity!=null&&!actual.entityNo().equals(expectedEntity))throw BizException.of(ErrorCode.FORBIDDEN);
        return actual;
    }
    @GetMapping public Page list(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @RequestParam(defaultValue="0") @Min(0) long after,@RequestParam(required=false) @Min(0) Long through,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(defaultValue="true") boolean attentionOnly){
        return service.list(scope(store,expectedEntity),after,through,size,attentionOnly);
    }
    @PostMapping("/manual") public View registerManual(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@RequestBody @Valid ManualRegister r){
        return service.registerManual(scope(store,expectedEntity),SecurityUtils.currentUserNo(),key,r.documentNo(),r.lineNo(),r.mapping(),r.movementNo());
    }
    @PostMapping("/{no}/manual-receipt") public View adoptManual(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@PathVariable @Size(max=32) String no,@RequestBody @Valid ManualReceipt r){
        return service.adoptManual(scope(store,expectedEntity),SecurityUtils.currentUserNo(),key,no,r.movementNo());
    }
    @GetMapping("/{no}/reconciliation") public Reconciliation reconcile(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @PathVariable @Size(max=32) String no){return service.reconcile(scope(store,expectedEntity),SecurityUtils.currentUserNo(),no);}
    @PostMapping public View register(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@RequestBody @Valid Register r){
        return service.register(scope(store,expectedEntity),SecurityUtils.currentUserNo(),key,r.documentNo(),r.lineNo(),r.mapping());
    }
    @GetMapping("/{no}") public View get(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @PathVariable @Size(max=32) String no){return service.get(scope(store,expectedEntity),no);}
    @PostMapping("/{no}/receive") public View receive(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@PathVariable @Size(max=32) String no,@RequestBody @Valid Quantity r){
        return service.receive(scope(store,expectedEntity),SecurityUtils.currentUserNo(),key,no,r.cumulativeQuantity());
    }
    @PostMapping("/{no}/close") public View close(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@PathVariable @Size(max=32) String no,@RequestBody @Valid Reason r){
        return service.close(scope(store,expectedEntity),SecurityUtils.currentUserNo(),key,no,r.reason());
    }
    @PostMapping("/{no}/return-dispatch") public View dispatchReturn(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@PathVariable @Size(max=32) String no,@RequestBody @Valid ReturnQuantity r){
        return service.dispatchReturn(scope(store,expectedEntity),SecurityUtils.currentUserNo(),key,no,r.cumulativeQuantity(),r.reason());
    }
    @PostMapping("/{no}/return-ack") public View ackReturn(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@PathVariable @Size(max=32) String no,@RequestBody @Valid Ack r){
        return service.ackReturn(scope(store,expectedEntity),SecurityUtils.currentUserNo(),key,no,r.documentNo(),r.lineNo());
    }
    @GetMapping("/commands/{action}/state") public CommandState commandState(
            @RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @PathVariable @Pattern(regexp="REPLENISH_(CONFIG|REGISTER|RECEIVE|CLOSE|RETURN|RETURN_ACK|MANUAL|MANUAL_REGISTER)") String action,
            @RequestParam @NotBlank @Size(max=128) String key){return service.commandState(scope(store,expectedEntity),action,key);}
    @PostMapping("/commands/{action}/cancel") public CommandState cancelCommand(
            @RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @PathVariable @Pattern(regexp="REPLENISH_(CONFIG|REGISTER|RECEIVE|CLOSE|RETURN|RETURN_ACK|MANUAL|MANUAL_REGISTER)") String action,
            @RequestBody @Valid CancelRequest r){return service.cancelCommand(scope(store,expectedEntity),SecurityUtils.currentUserNo(),action,r.key(),r.reason());}
    @GetMapping("/commands/{action}") public View command(@RequestHeader(value=BizContextFilter.STORE_HEADER,required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No",required=false) @Size(max=32) String expectedEntity,
            @PathVariable @Pattern(regexp="REPLENISH_(REGISTER|RECEIVE|CLOSE|RETURN|RETURN_ACK|MANUAL|MANUAL_REGISTER)") String action,
            @RequestParam @NotBlank @Size(max=128) String key){return service.findCommand(scope(store,expectedEntity),action,key);}
}
