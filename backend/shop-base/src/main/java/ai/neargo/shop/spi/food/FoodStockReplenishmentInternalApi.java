package ai.neargo.shop.spi.food;

import ai.neargo.shop.spi.food.FoodLocalStockPort.Scope;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.*;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort.*;
import java.math.BigDecimal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ai.neargo.shop.spi.product.StockReplenishmentClaim;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface FoodStockReplenishmentInternalApi {
    String ROOT="/internal/food/local-stock/replenishments";
    record Register(Scope scope,String actorNo,String key,Outbound source,Mapping mapping) {}
    record SourceQuery(Scope scope,String system,String owner,String document,int line) {}
    record RegisterClaimed(@NotNull Scope scope,@NotBlank @Size(max=64) String actorNo,
                           @NotBlank @Size(max=128) String key,@NotNull Outbound source,@NotNull @Valid Mapping mapping,
                           @NotNull @Valid StockReplenishmentClaim claim,String movementNo) {}
    @PostExchange(ROOT+"/source") Lookup findSource(@RequestBody SourceQuery request);
    @PostExchange(ROOT+"/register/claimed") View registerClaimed(@RequestBody RegisterClaimed request);
    @PostExchange(ROOT+"/manual/register/claimed") View registerManualClaimed(@RequestBody RegisterClaimed request);
    record Quantity(Scope scope,String actorNo,String key,String no,BigDecimal target,String reason) {}
    record Close(Scope scope,String actorNo,String key,String no,String reason) {}
    record Ack(Scope scope,String actorNo,String key,String no,Inbound source) {}
    record Query(Scope scope,String no) {}
    record CommandQuery(Scope scope,String action,String key) {}
    record Lookup(View result) {}
    record Cancel(Scope scope,String actorNo,String action,String key,String reason) {}
    @PostExchange(ROOT+"/command/state") CommandState commandState(@RequestBody CommandQuery request);
    @PostExchange(ROOT+"/command/cancel") CommandState cancelCommand(@RequestBody Cancel request);
    record ConfigCommand(Scope scope,String actorNo,String key,ConnectionChange change,Checkpoint source) {}
    record ConfigQuery(Scope scope) {}
    record ConfigLookup(Scope scope,String actorNo,String key,ConnectionChange change) {}
    record ConfigKey(Scope scope,String key) {}
    record ConfigResult(Connection result) {}
    record RegistrationQuery(Scope scope,String actorNo,String key,String documentNo,int lineNo,Mapping mapping,String movementNo) {
        public RegistrationQuery(Scope scope,String actorNo,String key,String documentNo,int lineNo,Mapping mapping) {
            this(scope,actorNo,key,documentNo,lineNo,mapping,null);
        }
    }
    record ManualRegister(Scope scope,String actorNo,String key,Outbound source,Mapping mapping,String movementNo) {}
    record Manual(Scope scope,String actorNo,String key,String no,String movementNo) {}
    record ListQuery(Scope scope,long after,Long through,int size,boolean attentionOnly) {}
    record Reconcile(Scope scope,String no,Outbound source) {}
    @PostExchange(ROOT+"/connection/get") Connection connection(@RequestBody ConfigQuery request);
    @PostExchange(ROOT+"/connection/configure") Connection configure(@RequestBody ConfigCommand request);
    @PostExchange(ROOT+"/connection/recover") ConfigResult recoverConfiguration(@RequestBody ConfigLookup request);
    @PostExchange(ROOT+"/connection/command") ConfigResult findConfigurationCommand(@RequestBody ConfigKey request);
    @PostExchange(ROOT+"/register/recover") Lookup recoverRegistration(@RequestBody RegistrationQuery request);
    @PostExchange(ROOT+"/manual/register") View registerManual(@RequestBody ManualRegister request);
    @PostExchange(ROOT+"/manual/adopt") View adoptManual(@RequestBody Manual request);
    @PostExchange(ROOT+"/list") Page list(@RequestBody ListQuery request);
    @PostExchange(ROOT+"/reconcile") Reconciliation reconcile(@RequestBody Reconcile request);
    @PostExchange(ROOT+"/register") View register(@RequestBody Register request);
    @PostExchange(ROOT+"/receive") View receive(@RequestBody Quantity request);
    @PostExchange(ROOT+"/close") View close(@RequestBody Close request);
    @PostExchange(ROOT+"/return-dispatch") View dispatchReturn(@RequestBody Quantity request);
    @PostExchange(ROOT+"/return-ack") View ackReturn(@RequestBody Ack request);
    @PostExchange(ROOT+"/get") View get(@RequestBody Query request);
    @PostExchange(ROOT+"/command") Lookup findCommand(@RequestBody CommandQuery request);
}
