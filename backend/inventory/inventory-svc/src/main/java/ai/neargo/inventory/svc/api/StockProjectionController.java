package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.projection.StockProjectionContracts.Scope;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Snapshot;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Receipt;
import ai.neargo.inventory.api.projection.StockProjectionContracts.MappingResult;
import ai.neargo.inventory.api.projection.StockProjectionContracts.MappingQuery;
import ai.neargo.inventory.api.projection.StockProjectionContracts.SessionCommand;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Apply;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Query;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Items;
import ai.neargo.inventory.api.projection.StockProjectionContracts.ReceiptLookup;
import ai.neargo.inventory.api.projection.StockProjectionContracts.ProjectionView;
import ai.neargo.inventory.api.projection.StockProjectionContracts.ProjectionPage;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Request;
import ai.neargo.inventory.domain.service.StockProjectionService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;

/** Incoming source mirrors use the same credential and owner guard as the inventory service. */
@RestController
@RequestMapping("/api/inventory/v1/stock-projections")
public class StockProjectionController {
    private final StockProjectionService service;
    public StockProjectionController(StockProjectionService service){this.service=service;}
    @InitBinder void validation(WebDataBinder binder) {
        if(binder.getTarget() instanceof Request)binder.addValidators(new StockProjectionValidator());
    }
    @PostMapping("/validate-mappings") public List<MappingResult> mappings(@RequestBody @Validated MappingQuery r) {
        return service.validateMappings(InventoryRequestContext.caller(),r.scope(),r.mappings());
    }
    @PostMapping("/activate") public Receipt activate(@RequestBody @Validated SessionCommand r) {
        return service.activate(InventoryRequestContext.caller(),r.scope(),r.generation(),r.operationKey());
    }
    @PostMapping("/deactivate") public Receipt deactivate(@RequestBody @Validated SessionCommand r) {
        return service.deactivate(InventoryRequestContext.caller(),r.scope(),r.generation(),r.operationKey());
    }
    @PostMapping("/apply") public Receipt apply(@RequestBody @Validated Apply r) {
        return service.apply(InventoryRequestContext.caller(),r.scope(),r.generation(),r.eventNo(),r.payloadHash(),r.snapshot());
    }
    @PostMapping("/receipt") public ReceiptLookup receipt(@RequestBody @Validated Query r) {
        return new ReceiptLookup(service.queryReceipt(InventoryRequestContext.caller(),r.scope(),r.eventNo(),r.payloadHash()));
    }
    @PostMapping("/query") public List<Snapshot> query(@RequestBody @Validated Items r) {
        return service.queryProjection(InventoryRequestContext.caller(),r.scope(),r.itemNos());
    }
    @GetMapping public ProjectionPage list(@RequestParam @NotBlank @Size(max=32) String entityNo,@RequestParam @NotBlank @Size(max=32) String storeNo,
            @RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return service.list(InventoryRequestContext.caller(),new Scope(entityNo,storeNo),page,size);
    }
    @GetMapping("/{itemNo}") public ProjectionView get(@PathVariable @NotBlank @Size(max=32) String itemNo,
            @RequestParam @NotBlank @Size(max=32) String entityNo,@RequestParam @NotBlank @Size(max=32) String storeNo) {
        return service.get(InventoryRequestContext.caller(),new Scope(entityNo,storeNo),itemNo);
    }
}
