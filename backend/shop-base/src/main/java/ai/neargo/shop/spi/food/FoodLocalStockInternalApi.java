package ai.neargo.shop.spi.food;

import ai.neargo.shop.spi.food.FoodLocalStockPort.*;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** FOOD owns these transactions; CORE retains original wire commands before crossing this boundary. */
public interface FoodLocalStockInternalApi {
    String ROOT = "/internal/food/local-stock";
    record ReservationLookup(ReservationView reservation) {}
    record ReservationQuery(Scope scope, String reservationNo) {}
    record SourceQuery(Scope scope, String sourceSystem, String sourceNo, long sourceVersion) {}
    record AvailabilityQuery(Scope scope, List<ItemRef> items) {}
    record PlanCommand(Scope scope, Command command, Plan plan) {}
    record ReservationCommand(Scope scope, Command command, String reservationNo) {}
    record TargetsCommand(Scope scope, Command command, String reservationNo, List<Target> targets) {}
    record ReturnCommand(Scope scope, Command command, String reservationNo, List<ReturnTarget> targets) {}
    record CommandQuery(Scope scope, String action, String operationKey, String requestHash) {}
    @PostExchange(ROOT+"/reservation/get") ReservationView getReservation(@RequestBody ReservationQuery request);
    @PostExchange(ROOT+"/reservation/find") ReservationLookup findReservation(@RequestBody SourceQuery request);
    @PostExchange(ROOT+"/availability") List<Availability> findAvailability(@RequestBody AvailabilityQuery request);
    @PostExchange(ROOT+"/reserve") Receipt reserve(@RequestBody PlanCommand request);
    @PostExchange(ROOT+"/confirm") Receipt confirm(@RequestBody ReservationCommand request);
    @PostExchange(ROOT+"/supplement") Receipt consumeSupplement(@RequestBody PlanCommand request);
    @PostExchange(ROOT+"/withdraw") Receipt withdraw(@RequestBody PlanCommand request);
    @PostExchange(ROOT+"/expire") Receipt expire(@RequestBody PlanCommand request);
    @PostExchange(ROOT+"/issue-delta") Receipt issueDelta(@RequestBody TargetsCommand request);
    @PostExchange(ROOT+"/issue") Receipt issue(@RequestBody TargetsCommand request);
    @PostExchange(ROOT+"/release") Receipt release(@RequestBody TargetsCommand request);
    @PostExchange(ROOT+"/return") Receipt returnIssued(@RequestBody ReturnCommand request);
    @PostExchange(ROOT+"/command/find") Lookup findCommand(@RequestBody CommandQuery request);
}
