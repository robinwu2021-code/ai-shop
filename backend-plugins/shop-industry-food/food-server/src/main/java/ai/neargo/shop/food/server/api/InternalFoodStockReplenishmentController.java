package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.spi.food.FoodStockReplenishmentInternalApi.*;
import ai.neargo.shop.spi.food.FoodStockReplenishmentInternalApi;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort;
import ai.neargo.shop.spi.food.FoodStockReplenishmentPort.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import jakarta.validation.Valid;
import ai.neargo.shop.spi.food.FoodLocalStockPort.Scope;
import org.springframework.validation.annotation.Validated;

@RestController @ConditionalOnFoodEnabled @Conditional(PersistenceEnabled.class)
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
@RequestMapping(FoodStockReplenishmentInternalApi.ROOT)
@Validated
public class InternalFoodStockReplenishmentController extends FoodStockInternalErrors {
    private final FoodStockReplenishmentPort service;
    public InternalFoodStockReplenishmentController(FoodStockReplenishmentPort service){this.service=service;}
    @PostMapping("/source") public Lookup findSource(@RequestBody SourceQuery r){
        return new Lookup(service.findSource(r.scope(),r.system(),r.owner(),r.document(),r.line()));
    }
    @PostMapping("/register/claimed") public View registerClaimed(@RequestBody @Valid RegisterClaimed r){
        return service.registerClaimed(r.scope(),r.actorNo(),r.key(),r.source(),r.mapping(),r.claim());
    }
    @PostMapping("/manual/register/claimed") public View registerManualClaimed(@RequestBody @Valid RegisterClaimed r){
        return service.registerManualClaimed(r.scope(),r.actorNo(),r.key(),r.source(),r.mapping(),r.movementNo(),r.claim());
    }
    @PostMapping("/command/state") public CommandState commandState(@RequestBody CommandQuery r){return service.commandState(r.scope(),r.action(),r.key());}
    @PostMapping("/command/cancel") public CommandState cancelCommand(@RequestBody Cancel r){return service.cancelCommand(r.scope(),r.actorNo(),r.action(),r.key(),r.reason());}
    @PostMapping("/connection/get") public Connection connection(@RequestBody ConfigQuery r){return service.connection(r.scope());}
    @PostMapping("/connection/configure") public Connection configure(@RequestBody ConfigCommand r){return service.configure(r.scope(),r.actorNo(),r.key(),r.change(),r.source());}
    @PostMapping("/connection/recover") public ConfigResult recoverConfiguration(@RequestBody ConfigLookup r){return new ConfigResult(service.recoverConfiguration(r.scope(),r.actorNo(),r.key(),r.change()));}
    @PostMapping("/connection/command") public ConfigResult findConfigurationCommand(@RequestBody ConfigKey r){return new ConfigResult(service.findConfigurationCommand(r.scope(),r.key()));}
    @PostMapping("/register/recover") public Lookup recoverRegistration(@RequestBody RegistrationQuery r){
        return new Lookup(r.movementNo()==null?service.recoverRegistration(r.scope(),r.actorNo(),r.key(),r.documentNo(),r.lineNo(),r.mapping()):
                service.recoverManualRegistration(r.scope(),r.actorNo(),r.key(),r.documentNo(),r.lineNo(),r.mapping(),r.movementNo()));
    }
    @PostMapping("/manual/register") public View registerManual(@RequestBody ManualRegister r){
        requireExisting(r.scope(),r.source().sourceSystem(),r.source().sourceOwner(),r.source().documentNo(),r.source().lineNo());
        return service.registerManual(r.scope(),r.actorNo(),r.key(),r.source(),r.mapping(),r.movementNo());
    }
    @PostMapping("/manual/adopt") public View adoptManual(@RequestBody Manual r){return service.adoptManual(r.scope(),r.actorNo(),r.key(),r.no(),r.movementNo());}
    @PostMapping("/list") public Page list(@RequestBody ListQuery r){return service.list(r.scope(),r.after(),r.through(),r.size(),r.attentionOnly());}
    @PostMapping("/reconcile") public Reconciliation reconcile(@RequestBody Reconcile r){return service.reconcile(r.scope(),r.no(),r.source());}
    @PostMapping("/register") public View register(@RequestBody Register r){
        requireExisting(r.scope(),r.source().sourceSystem(),r.source().sourceOwner(),r.source().documentNo(),r.source().lineNo());
        return service.register(r.scope(),r.actorNo(),r.key(),r.source(),r.mapping());
    }
    private void requireExisting(Scope scope,String system,String owner,String doc,int line){
        if(service.findSource(scope,system,owner,doc,line)==null)throw BizException.of(ErrorCode.CONFLICT);
    }
    @PostMapping("/receive") public View receive(@RequestBody Quantity r){return service.receive(r.scope(),r.actorNo(),r.key(),r.no(),r.target());}
    @PostMapping("/close") public View close(@RequestBody Close r){return service.close(r.scope(),r.actorNo(),r.key(),r.no(),r.reason());}
    @PostMapping("/return-dispatch") public View dispatchReturn(@RequestBody Quantity r){return service.dispatchReturn(r.scope(),r.actorNo(),r.key(),r.no(),r.target(),r.reason());}
    @PostMapping("/return-ack") public View ackReturn(@RequestBody Ack r){return service.ackReturn(r.scope(),r.actorNo(),r.key(),r.no(),r.source());}
    @PostMapping("/get") public View get(@RequestBody Query r){return service.get(r.scope(),r.no());}
    @PostMapping("/command") public Lookup findCommand(@RequestBody CommandQuery r){return new Lookup(service.findCommand(r.scope(),r.action(),r.key()));}
}
