package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.spi.food.FoodLocalStockInternalApi;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import java.util.List;

/** Retains exact source keys, quantities and immutable requests across standalone calls. */
public final class RemoteFoodLocalStockPort implements FoodLocalStockPort {
    private final FoodLocalStockInternalApi api;
    public RemoteFoodLocalStockPort(FoodLocalStockInternalApi api) {this.api=api;}
    @Override public ReservationView getReservation(Scope scope, String reservationNo) {return FoodStockRemoteCalls.invoke(()->api.getReservation(new FoodLocalStockInternalApi.ReservationQuery(scope,reservationNo)));}
    @Override public ReservationView findReservation(Scope scope, String sourceSystem, String sourceNo, long sourceVersion) {return FoodStockRemoteCalls.invoke(()->api.findReservation(new FoodLocalStockInternalApi.SourceQuery(scope,sourceSystem,sourceNo,sourceVersion))).reservation();}
    @Override public List<Availability> findAvailability(Scope scope, List<ItemRef> items) {return FoodStockRemoteCalls.invoke(()->api.findAvailability(new FoodLocalStockInternalApi.AvailabilityQuery(scope,items)));}
    @Override public Receipt reserve(Scope scope, Command command, Plan plan) {return FoodStockRemoteCalls.invoke(()->api.reserve(new FoodLocalStockInternalApi.PlanCommand(scope,command,plan)));}
    @Override public Receipt confirm(Scope scope, Command command, String reservationNo) {return FoodStockRemoteCalls.invoke(()->api.confirm(new FoodLocalStockInternalApi.ReservationCommand(scope,command,reservationNo)));}
    @Override public Receipt consumeSupplement(Scope scope, Command command, Plan plan) {return FoodStockRemoteCalls.invoke(()->api.consumeSupplement(new FoodLocalStockInternalApi.PlanCommand(scope,command,plan)));}
    @Override public Receipt withdraw(Scope scope, Command command, Plan plan) {return FoodStockRemoteCalls.invoke(()->api.withdraw(new FoodLocalStockInternalApi.PlanCommand(scope,command,plan)));}
    @Override public Receipt expire(Scope scope, Command command, Plan plan) {return FoodStockRemoteCalls.invoke(()->api.expire(new FoodLocalStockInternalApi.PlanCommand(scope,command,plan)));}
    @Override public Receipt issueDelta(Scope scope,Command command,String reservationNo,List<Target> targets) {return FoodStockRemoteCalls.invoke(()->api.issueDelta(new FoodLocalStockInternalApi.TargetsCommand(scope,command,reservationNo,targets)));}
    @Override public Receipt issue(Scope scope, Command command, String reservationNo, List<Target> targets) {return FoodStockRemoteCalls.invoke(()->api.issue(new FoodLocalStockInternalApi.TargetsCommand(scope,command,reservationNo,targets)));}
    @Override public Receipt release(Scope scope, Command command, String reservationNo, List<Target> targets) {return FoodStockRemoteCalls.invoke(()->api.release(new FoodLocalStockInternalApi.TargetsCommand(scope,command,reservationNo,targets)));}
    @Override public Receipt returnIssued(Scope scope, Command command, String reservationNo, List<ReturnTarget> targets) {return FoodStockRemoteCalls.invoke(()->api.returnIssued(new FoodLocalStockInternalApi.ReturnCommand(scope,command,reservationNo,targets)));}
    @Override public Lookup findCommand(Scope scope, String action, String operationKey, String requestHash) {return FoodStockRemoteCalls.invoke(()->api.findCommand(new FoodLocalStockInternalApi.CommandQuery(scope,action,operationKey,requestHash)));}
}
