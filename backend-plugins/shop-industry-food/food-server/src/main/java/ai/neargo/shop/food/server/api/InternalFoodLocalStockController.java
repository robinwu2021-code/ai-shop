package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.spi.food.FoodLocalStockInternalApi;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodLocalStockPort.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.*;

/** Authenticated by the existing token + signed entity/store/actor scope filter. */
@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
@ConditionalOnProperty(name="shop.food.local-stock.enabled",havingValue="true")
public class InternalFoodLocalStockController extends FoodStockInternalErrors {
    private final FoodLocalStockPort service;
    public InternalFoodLocalStockController(FoodLocalStockPort service) {this.service=service;}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/reservation/get")
    public ReservationView getReservation(@RequestBody FoodLocalStockInternalApi.ReservationQuery r) {return service.getReservation(r.scope(),r.reservationNo());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/reservation/find")
    public FoodLocalStockInternalApi.ReservationLookup findReservation(@RequestBody FoodLocalStockInternalApi.SourceQuery r) {return new FoodLocalStockInternalApi.ReservationLookup(service.findReservation(r.scope(),r.sourceSystem(),r.sourceNo(),r.sourceVersion()));}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/availability")
    public List<Availability> findAvailability(@RequestBody FoodLocalStockInternalApi.AvailabilityQuery r) {return service.findAvailability(r.scope(),r.items());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/reserve")
    public Receipt reserve(@RequestBody FoodLocalStockInternalApi.PlanCommand r) {return service.reserve(r.scope(),r.command(),r.plan());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/confirm")
    public Receipt confirm(@RequestBody FoodLocalStockInternalApi.ReservationCommand r) {return service.confirm(r.scope(),r.command(),r.reservationNo());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/supplement")
    public Receipt consumeSupplement(@RequestBody FoodLocalStockInternalApi.PlanCommand r) {return service.consumeSupplement(r.scope(),r.command(),r.plan());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/withdraw")
    public Receipt withdraw(@RequestBody FoodLocalStockInternalApi.PlanCommand r) {return service.withdraw(r.scope(),r.command(),r.plan());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/expire")
    public Receipt expire(@RequestBody FoodLocalStockInternalApi.PlanCommand r) {return service.expire(r.scope(),r.command(),r.plan());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/issue-delta")
    public Receipt issueDelta(@RequestBody FoodLocalStockInternalApi.TargetsCommand r) {return service.issueDelta(r.scope(),r.command(),r.reservationNo(),r.targets());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/issue")
    public Receipt issue(@RequestBody FoodLocalStockInternalApi.TargetsCommand r) {return service.issue(r.scope(),r.command(),r.reservationNo(),r.targets());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/release")
    public Receipt release(@RequestBody FoodLocalStockInternalApi.TargetsCommand r) {return service.release(r.scope(),r.command(),r.reservationNo(),r.targets());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/return")
    public Receipt returnIssued(@RequestBody FoodLocalStockInternalApi.ReturnCommand r) {return service.returnIssued(r.scope(),r.command(),r.reservationNo(),r.targets());}
    @PostMapping(FoodLocalStockInternalApi.ROOT+"/command/find")
    public Lookup findCommand(@RequestBody FoodLocalStockInternalApi.CommandQuery r) {return service.findCommand(r.scope(),r.action(),r.operationKey(),r.requestHash());}
}
