package ai.neargo.shop.product.api.biz;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.policy.LocalStockAccessPolicy;
import ai.neargo.shop.product.service.RetailStockReplenishmentService;
import ai.neargo.shop.product.service.RetailStockReplenishmentService.AdjustmentResult;
import ai.neargo.shop.product.service.RetailStockReplenishmentService.Page;
import ai.neargo.shop.product.service.RetailStockReplenishmentService.Reconciliation;
import ai.neargo.shop.product.service.RetailStockReplenishmentService.View;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService.Change;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService.Connection;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService.Mapping;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** RETAIL physical replenishment into the original PLATFORM pool; the body never selects owner, pool or actor. */
@RestController @Profile("api") @Validated
@RequestMapping("/biz/stock-control/retail-replenishments")
@PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
public class BizRetailStockReplenishmentController {
    public record Register(@NotBlank @Size(max=32) String documentNo, @Min(1) int lineNo, @NotNull @Valid Mapping mapping) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field: " + key); }
    }
    /** Integer-only on the wire: a JSON 1.5 must fail validation, never be truncated into the PLATFORM ledger. */
    public record Quantity(@NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer=10, fraction=0)
                           BigDecimal cumulativeQuantity) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field: " + key); }
    }
    public record Reason(@NotBlank @Size(max=256) String reason) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field: " + key); }
    }
    public record ReturnQuantity(@NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer=10, fraction=0)
                                 BigDecimal cumulativeQuantity, @NotBlank @Size(max=256) String reason) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field: " + key); }
    }
    public record Ack(@NotBlank @Size(max=32) String documentNo, @Min(1) int lineNo) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field: " + key); }
    }
    public record Adjust(@NotBlank @Size(max=32) String itemNo, @NotBlank @Size(max=16) String baseUnit,
                         @NotNull @DecimalMin("-2147483647") @DecimalMax("2147483647") @Digits(integer=10, fraction=0)
                         BigDecimal delta, @NotBlank @Size(max=256) String reason) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field: " + key); }
    }
    public record ManualReceipt(@NotBlank @Size(max=32) String movementNo) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field: " + key); }
    }
    public record Context(String entityNo, String storeNo, String actorNo, Connection connection) { }

    private final RetailStockReplenishmentService service;
    private final LocalStockAccessPolicy access;

    public BizRetailStockReplenishmentController(RetailStockReplenishmentService service, LocalStockAccessPolicy access) {
        this.service = service; this.access = access;
    }

    private Scope scope(String store, String expectedEntity) {
        var actual = access.context(store);
        if (expectedEntity != null && !actual.entityNo().equals(expectedEntity)) throw BizException.of(ErrorCode.FORBIDDEN);
        return actual;
    }

    @GetMapping("/context")
    public Context context(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity) {
        var scope = scope(store, expectedEntity);
        return new Context(scope.entityNo(), scope.storeNo(), SecurityUtils.currentUserNo(), service.connection(scope));
    }

    @PostMapping("/connection")
    public Connection configure(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, @RequestBody @Valid Change change) {
        return service.configure(scope(store, expectedEntity), SecurityUtils.currentUserNo(), key, change);
    }

    @GetMapping
    public Page list(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @RequestParam(required=false) @Size(max=32) String after,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="true") boolean attentionOnly) {
        return service.list(scope(store, expectedEntity), after, size, attentionOnly);
    }

    @PostMapping
    public View register(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, @RequestBody @Valid Register r) {
        return service.register(scope(store, expectedEntity), SecurityUtils.currentUserNo(), key, r.documentNo(), r.lineNo(), r.mapping());
    }

    @PostMapping("/adjustments")
    public AdjustmentResult adjust(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, @RequestBody @Valid Adjust r) {
        return service.adjust(scope(store, expectedEntity), SecurityUtils.currentUserNo(), key,
                r.itemNo(), r.baseUnit(), r.delta().intValueExact(), r.reason());
    }

    @GetMapping("/{no}")
    public View get(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @PathVariable @Size(max=32) String no) {
        return service.get(scope(store, expectedEntity), no);
    }

    @GetMapping("/{no}/reconciliation")
    public Reconciliation reconcile(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @PathVariable @Size(max=32) String no) {
        return service.reconcile(scope(store, expectedEntity), SecurityUtils.currentUserNo(), no);
    }

    @PostMapping("/{no}/receive")
    public View receive(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,
            @PathVariable @Size(max=32) String no, @RequestBody @Valid Quantity r) {
        return service.receive(scope(store, expectedEntity), SecurityUtils.currentUserNo(), key, no, r.cumulativeQuantity().intValueExact());
    }

    @PostMapping("/{no}/close")
    public View close(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,
            @PathVariable @Size(max=32) String no, @RequestBody @Valid Reason r) {
        return service.close(scope(store, expectedEntity), SecurityUtils.currentUserNo(), key, no, r.reason());
    }

    @PostMapping("/{no}/return-dispatch")
    public View dispatchReturn(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,
            @PathVariable @Size(max=32) String no, @RequestBody @Valid ReturnQuantity r) {
        return service.dispatchReturn(scope(store, expectedEntity), SecurityUtils.currentUserNo(), key, no,
                r.cumulativeQuantity().intValueExact(), r.reason());
    }

    @PostMapping("/{no}/return-ack")
    public View ackReturn(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,
            @PathVariable @Size(max=32) String no, @RequestBody @Valid Ack r) {
        return service.ackReturn(scope(store, expectedEntity), SecurityUtils.currentUserNo(), key, no, r.documentNo(), r.lineNo());
    }

    @PostMapping("/{no}/manual-receipt")
    public View adoptManual(@RequestHeader(value=BizContextFilter.STORE_HEADER, required=false) String store,
            @RequestHeader(value="X-Stock-Entity-No", required=false) @Size(max=32) String expectedEntity,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,
            @PathVariable @Size(max=32) String no, @RequestBody @Valid ManualReceipt r) {
        return service.adoptManual(scope(store, expectedEntity), SecurityUtils.currentUserNo(), key, no, r.movementNo());
    }
}
