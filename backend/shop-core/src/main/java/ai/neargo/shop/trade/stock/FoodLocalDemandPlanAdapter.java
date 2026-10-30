package ai.neargo.shop.trade.stock;

import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

/** Per-original-plan adapter, never registered as a second global InventoryDemandPlanPort bean. */
public final class FoodLocalDemandPlanAdapter implements InventoryDemandPlanPort {
    private final Plan original;private final int protocol;
    private final FoodLocalStockPort local;private final FoodStockBridgeJournal journal;private final ObjectMapper json;
    private final FoodLocalStockPort.Scope scope;
    public FoodLocalDemandPlanAdapter(Plan plan,int protocol,FoodLocalStockPort local,
            FoodStockBridgeJournal journal,ObjectMapper json) {
        this.original=InventoryDemandPlanFacts.validate(plan);if(protocol!=2&&protocol!=3)throw conflict();
        var stores=plan.branches().stream().map(Branch::storeNo).distinct().toList();
        if(stores.size()!=1||!"MAIN".equals(plan.scope().tenantNo()))throw conflict();
        this.protocol=protocol;this.local=Objects.requireNonNull(local);
        this.journal=journal;this.json=json;this.scope=new FoodLocalStockPort.Scope(plan.scope().entityNo(),stores.getFirst());
    }
    @Override public Receipt reserve(Plan plan,long ttl,int version) {
        same(plan);if(version!=protocol||ttl<1||ttl>604800)throw conflict();
        String intent=hash("RESERVE",ttl,version);
        var wire=wire("RESERVE","RESERVE",intent,()->{
            var prior=view();if(prior!=null)throw conflict();
            // Match DATETIME(3) before hashing; round-trip expiry must preserve the frozen plan hash.
            var expiry=LocalDateTime.ofInstant(Instant.now().plusSeconds(ttl),ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            var translated=translate(expiry);return command("RESERVE","RESERVE",0,List.of(translated),translated);
        });
        var translated=json.readValue(wire.payloadJson(),FoodLocalStockPort.Plan.class);
        return project(local.reserve(scope,wire.command(),translated),translated.planHash(),translated.expiresAt());
    }
    @Override public Receipt query(Scope requested){scope(requested);var v=view();if(v==null)return new Receipt(original.scope(),null,null,"ABSENT",0,null,List.of());if("HELD".equals(v.status())&&v.expiresAt()!=null&&!v.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))){
            var known=v;var wire=wire("EXPIRE","EXPIRE",hash("EXPIRE",v.planHash()),()->{
                var translated=translate(known.expiresAt());return command("EXPIRE","EXPIRE",known.lifecycleVersion(),List.of(translated),translated);
            });
            var translated=json.readValue(wire.payloadJson(),FoodLocalStockPort.Plan.class);
            return project(local.expire(scope,wire.command(),translated),translated.planHash(),translated.expiresAt());
        }
        return project(v);}
    @Override public Receipt acceptUnpaid(Scope requested){scope(requested);if(protocol!=3)throw unsupported();return confirm("ACCEPT_UNPAID");}
    @Override public Receipt allocatePaid(Scope requested){scope(requested);var receipt=confirm("ALLOCATE_PAID");
        return protocol==3?new Receipt(receipt.scope(),receipt.planNo(),receipt.planHash(),"ALLOCATED",receipt.lifecycleVersion(),receipt.expiresAt(),receipt.lines()):receipt;}
    private Receipt confirm(String action) {
        var wire=wire("CONFIRM",action,hash(action),()->{var v=required();return command("CONFIRM",action,v.lifecycleVersion(),List.of(v.reservationNo()),v.reservationNo());});
        String number=json.readValue(wire.payloadJson(),String.class);var v=required();
        return project(local.confirm(scope,wire.command(),number),v.planHash(),v.expiresAt());
    }
    @Override public Receipt issueTo(Scope requested,String operation,String planHash,String phase,List<Target> targets){scope(requested);text(planHash,64);if(!original.planHash().equals(planHash)||!Set.of("PRODUCTION","HANDOVER").contains(phase))throw conflict();
        var canonical=canonical(targets);for(var target:canonical){var parent=branch(target.leafRef());var leaf=parent.leaves().stream().filter(l->l.leafRef().equals(target.leafRef())).findFirst().orElseThrow(FoodLocalDemandPlanAdapter::conflict);String kind=leaf.sourceKind()==null?parent.kind():leaf.sourceKind();if(!("PRODUCTION".equals(phase)?"RECIPE_MATERIAL":"FINISHED_SKU").equals(kind))throw conflict();}
        return change("ISSUE",operation,hash("ISSUE",phase,canonical),canonical);
    }
    @Override public Receipt releaseTo(Scope requested,String operation,String planHash,List<Target> targets){scope(requested);if(!original.planHash().equals(planHash))throw conflict();var canonical=canonical(targets);return change("RELEASE",operation,hash("RELEASE",canonical),canonical);}
    private record Change(String reservationNo,List<FoodLocalStockPort.Target> targets){}
    private Receipt change(String action,String operation,String intent,List<Target> targets){text(operation,128);
        var wire=wire(action,operation,intent,()->{var v=required();var translated=targets.stream().map(t->new FoodLocalStockPort.Target(t.leafRef(),t.cumulativeQty())).toList();var payload=new Change(v.reservationNo(),translated);return command(action,operation,v.lifecycleVersion(),List.of(v.reservationNo(),translated),payload);});
        var payload=json.readValue(wire.payloadJson(),Change.class);var v=required();
        var result="ISSUE".equals(action)?local.issue(scope,wire.command(),payload.reservationNo(),payload.targets()):local.release(scope,wire.command(),payload.reservationNo(),payload.targets());
        return project(result,v.planHash(),v.expiresAt());
    }
    @Override public WithdrawalReceipt withdraw(Plan plan,String operation,int version){same(plan);text(operation,128);if(version!=protocol)throw conflict();
        var wire=wire("WITHDRAW",operation,hash("WITHDRAW",operation,version),()->{var v=view();var translated=translate(v==null?null:v.expiresAt());return command("WITHDRAW",operation,v==null?0:v.lifecycleVersion(),List.of(translated),translated);});
        var payload=json.readValue(wire.payloadJson(),FoodLocalStockPort.Plan.class);var receipt=local.withdraw(scope,wire.command(),payload);
        if(!Set.of("WITHDRAWN","RELEASED","EXPIRED").contains(receipt.status())||receipt.commandNo()==null)throw conflict();
        return new WithdrawalReceipt(original.scope(),original.planNo(),original.planHash(),operation,"EXPIRED".equals(receipt.status())?WithdrawalStatus.ALREADY_EXPIRED:receipt.items().isEmpty()?WithdrawalStatus.WITHDRAWN_ABSENT:WithdrawalStatus.WITHDRAWN_RESERVED,receipt.commandNo());
    }
    public FoodLocalStockPort.Receipt issueProduction(String operation,String sourceRequestHash,List<Target> increments) {
        var normalized=production(increments);String intent=hash("PRODUCTION_DELTA",sourceRequestHash,normalized);
        var wire=wire("ISSUE_DELTA",operation,intent,()->{
            var v=required();var targets=normalized.stream().map(t->new FoodLocalStockPort.Target(t.leafRef(),t.cumulativeQty())).toList();
            return command("ISSUE_DELTA",operation,v.lifecycleVersion(),List.of(v.reservationNo(),targets),new Change(v.reservationNo(),targets));
        });
        var call=json.readValue(wire.payloadJson(),Change.class);
        return local.issueDelta(scope,wire.command(),call.reservationNo(),call.targets());
    }
    public FoodLocalStockPort.Lookup queryProduction(String operation,String sourceRequestHash,List<Target> increments) {
        var normalized=production(increments);String intent=hash("PRODUCTION_DELTA",sourceRequestHash,normalized);
        var wire=journal.lookup(scope,original.planNo(),"ISSUE_DELTA",operation,intent);
        return wire==null?new FoodLocalStockPort.Lookup("NOT_FOUND",null)
                :local.findCommand(scope,"ISSUE_DELTA",wire.command().operationKey(),wire.command().requestHash());
    }
    private List<Target> production(List<Target> increments){var canonical=canonical(increments);if(canonical.isEmpty())throw conflict();
        for(var t:canonical){InventoryDemandPlanFacts.quantity(t.cumulativeQty(),false);var b=branch(t.leafRef());
            var leaf=b.leaves().stream().filter(l->l.leafRef().equals(t.leafRef())).findFirst().orElseThrow(FoodLocalDemandPlanAdapter::conflict);
            if(!"RECIPE_MATERIAL".equals(InventoryDemandPlanFacts.sourceKind(b,leaf)))throw conflict();}
        return canonical;
    }
    // Incremental/remake/reversal semantics require their original operation journals, never a guessed target.
    @Override public Receipt issue(Scope s,String op,String hash,String phase,List<Target> quantities){throw unsupported();}
    @Override public Receipt supplement(Scope s,String op,String hash,List<Target> quantities){throw unsupported();}
    @Override public Receipt reverse(Scope s,String op,String original,String hash){throw unsupported();}
    private FoodStockBridgeJournal.Wire wire(String action,String key,String intent,Supplier<FoodStockBridgeJournal.Wire> create){var prior=journal.lookup(scope,original.planNo(),action,key,intent);if(prior!=null)return prior;var prepared=create.get();return journal.acquire(scope,original.planNo(),action,key,intent,()->prepared);}
    private FoodStockBridgeJournal.Wire command(String action,String key,long revision,Object fingerprintPayload,Object payload){String operation=FoodStockFingerprint.sha256(original.planNo()+":"+action+":"+key);var c=new FoodLocalStockPort.Command(operation,"","CORE_ORDER",original.planNo(),0,revision);String hash=FoodStockFingerprint.request(json,action,c,fingerprintPayload);return new FoodStockBridgeJournal.Wire(new FoodLocalStockPort.Command(operation,hash,"CORE_ORDER",original.planNo(),0,revision),json.writeValueAsString(payload));}
    private FoodLocalStockPort.Plan translate(LocalDateTime expiry){var result=new ArrayList<FoodLocalStockPort.Leaf>();for(var b:original.branches())for(var l:b.leaves()){String definition=json.writeValueAsString(new Object[]{original.planHash(),b,l});String kind=l.sourceKind()==null?b.kind():l.sourceKind();result.add(new FoodLocalStockPort.Leaf(l.leafRef(),b.sourceComponentRef(),l.itemRef(),l.unit(),l.quantity(),"RECIPE_MATERIAL".equals(kind)?"PRODUCTION":"HANDOVER",definition,FoodStockFingerprint.sha256(definition)));}result.sort(Comparator.comparing(FoodLocalStockPort.Leaf::leafRef));var unsealed=new FoodLocalStockPort.Plan("",expiry,result,original.planHash(),protocol);return new FoodLocalStockPort.Plan(FoodStockFingerprint.plan(json,unsealed),expiry,result,original.planHash(),protocol);}
    private FoodLocalStockPort.ReservationView view(){var v=local.findReservation(scope,"CORE_ORDER",original.planNo(),0);if(v!=null&&(!original.planHash().equals(v.sourcePlanHash())||!Integer.valueOf(protocol).equals(v.protocolVersion())||!translate(v.expiresAt()).planHash().equals(v.planHash())))throw conflict();return v;}
    private FoodLocalStockPort.ReservationView required(){var v=view();if(v==null)throw conflict();return v;}
    private Receipt project(FoodLocalStockPort.ReservationView v){return project(v.reservationNo(),v.planHash(),v.status(),v.expiresAt(),v.items());}
    private Receipt project(FoodLocalStockPort.Receipt receipt,String planHash,LocalDateTime expiry){if(receipt==null||!"FOOD_LOCAL".equals(receipt.owner())||!planHash.equals(receipt.planHash()))throw conflict();return project(receipt.reservationNo(),receipt.planHash(),receipt.status(),expiry,receipt.items());}
    private Receipt project(String reservation,String planHash,String status,LocalDateTime expiry,List<FoodLocalStockPort.Progress> progress){var lines=new ArrayList<Progress>();for(var p:progress){var b=branch(p.leafRef());var l=b.leaves().stream().filter(x->x.leafRef().equals(p.leafRef())).findFirst().orElseThrow(FoodLocalDemandPlanAdapter::conflict);if(!p.itemNo().equals(l.itemRef())||!p.baseUnit().equals(l.unit())||p.original().compareTo(l.quantity())!=0)throw conflict();String kind=l.sourceKind()==null?b.kind():l.sourceKind();lines.add(new Progress(p.leafRef(),b.sourceComponentRef(),b.fulfillmentRef(),kind,p.itemNo(),p.original(),p.issued(),p.released(),p.baseUnit(),l.definitionHash(),p.documentRef(),p.ledgerRef(),null,null,"UNTRACKED".equals(p.trackingMode())?"NOT_TRACKED":"UNKNOWN"));}
        String projected=switch(status){case "HELD"->"HELD";case "CONFIRMED","PARTIAL","COMPLETED"->protocol==3?"ACCEPTED_UNPAID":"ALLOCATED";case "RELEASED","WITHDRAWN"->"RELEASED";case "EXPIRED"->"EXPIRED";default->throw conflict();};return new Receipt(original.scope(),original.planNo(),original.planHash(),projected,protocol,expiry==null?null:expiry.toInstant(ZoneOffset.UTC),lines);}
    private Branch branch(String leaf){return original.branches().stream().filter(b->b.leaves().stream().anyMatch(l->l.leafRef().equals(leaf))).findFirst().orElseThrow(FoodLocalDemandPlanAdapter::conflict);}
    private List<Target> canonical(List<Target> targets){if(targets==null||targets.size()>200)throw conflict();var result=targets.stream().sorted(Comparator.comparing(Target::leafRef)).toList();var seen=new HashSet<String>();for(var t:result){text(t.leafRef(),64);InventoryDemandPlanFacts.quantity(t.cumulativeQty(),true);branch(t.leafRef());if(!seen.add(t.leafRef()))throw conflict();}return result;}
    private String hash(Object... values){return FoodStockFingerprint.sha256(json.writeValueAsString(new Object[]{original.planHash(),values}));}
    private void same(Plan plan){InventoryDemandPlanFacts.validate(plan);if(!original.equals(plan))throw conflict();}
    private void scope(Scope s){if(!original.scope().equals(s))throw conflict();}
    private static void text(String s,int max){if(s==null||s.isBlank()||s.length()>max)throw conflict();}
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
    private static BizException unsupported(){return BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
}
