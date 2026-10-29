package ai.neargo.shop.trade.stock;

import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.food.*;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryProductionPort;
import ai.neargo.shop.spi.inventory.InventoryProductionFacts;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Authoritative original-order plan lookup precedes local kitchen writes; no native inventory fallback. */
@Service
public final class FoodProductionStockService implements FoodProductionStockPort {
    private final IndustryOrderSourceMapper sources;private final IndustryStockOwnerRouter owners;
    private final ObjectProvider<FoodLocalStockPort> stock;private final FoodStockBridgeJournal journal;private final ObjectMapper json;
    public FoodProductionStockService(IndustryOrderSourceMapper sources,IndustryStockOwnerRouter owners,
            ObjectProvider<FoodLocalStockPort> stock,FoodStockBridgeJournal journal,ObjectMapper json){this.sources=sources;this.owners=owners;this.stock=stock;this.journal=journal;this.json=json;}
    private record Original(OrdIndustryOrderSource source,InventoryDemandPlanPort.Plan plan,int protocol){}
    @Override public String owner(InventoryProductionPort.Command input){outside();var c=normalized(input);return selected(original(c).source);}
    @Override public InventoryProductionPort.Receipt consume(InventoryProductionPort.Command input){return execute(normalized(input),false);}
    @Override public InventoryProductionPort.Receipt query(InventoryProductionPort.Command input){return execute(normalized(input),true);}
    private record ReturnCall(String reservationNo,List<FoodLocalStockPort.ReturnTarget> targets){}
    @Override public InventoryProductionPort.Receipt reverse(InventoryProductionPort.Reverse reversal) {
        outside();if(reversal==null||reversal.reverseEventNo()==null||reversal.reverseEventNo().isBlank()
                ||reversal.reverseEventNo().length()>32||reversal.reason()==null||reversal.reason().isBlank()
                ||reversal.reason().length()>255||reversal.reasonCode()==null||reversal.reasonCode().isBlank()
                ||reversal.reasonCode().length()>32||reversal.actorNo()==null||reversal.actorNo().isBlank()
                ||reversal.actorNo().length()>32||!Set.of("RESALE","LOSS").contains(reversal.disposition()==null?"":reversal.disposition()))throw conflict();
        var c=normalized(reversal.original());var origin=original(c);
        if(!IndustryStockOwnerPolicy.FOOD_LOCAL.equals(selected(origin.source)))throw unsupported();
        var targets=validate(c,origin.plan);var scope=new FoodLocalStockPort.Scope(c.entityNo(),c.storeNo());
        var local=stock.getIfUnique();if(local==null)throw unsupported();String originalHash=InventoryProductionFacts.hash(c);
        String productionOp="FP"+FoodStockFingerprint.sha256(c.storeNo()+":"+c.eventNo()).substring(0,62);
        FoodLocalStockPort.Lookup lookup;
        if("PRODUCTION".equals(c.purpose())){
            var provider=owners.exact(origin.source.getInventoryOwner(),origin.plan,origin.protocol);
            if(!(provider instanceof FoodLocalDemandPlanAdapter adapter))throw unsupported();
            lookup=adapter.queryProduction(productionOp,originalHash,targets);
        }else{
            String intent=FoodStockFingerprint.sha256(json.writeValueAsString(List.of("SUPPLEMENT",origin.plan.planHash(),originalHash)));
            var forward=journal.lookup(scope,origin.plan.planNo(),"SUPPLEMENT",productionOp,intent);
            if(forward==null)throw conflict();lookup=local.findCommand(scope,"SUPPLEMENT",forward.command().operationKey(),forward.command().requestHash());
        }
        if(!"COMPLETED".equals(lookup.status())||lookup.receipt()==null)throw conflict();var forward=lookup.receipt();
        var proven=project(c,originalHash,forward,origin.plan);
        String intent=FoodStockFingerprint.sha256(json.writeValueAsString(new Object[]{"PRODUCTION_RETURN",origin.plan.planHash(),reversal}));
        var wire=journal.lookup(scope,origin.plan.planNo(),"RETURN",reversal.reverseEventNo(),intent);
        if(wire==null){var current=local.getReservation(scope,forward.reservationNo());
            if(!origin.plan.planHash().equals(current.sourcePlanHash()))throw conflict();
            var returns=new ArrayList<FoodLocalStockPort.ReturnTarget>();for(var line:proven.issuedLines())returns.add(new FoodLocalStockPort.ReturnTarget(
                    line.leafRef(),line.ledgerRef(),line.quantity(),reversal.reverseEventNo(),reversal.disposition(),
                    reversal.reasonCode(),reversal.reason(),reversal.actorNo(),true));
            var raw=new FoodLocalStockPort.Command(FoodStockFingerprint.sha256("PRODUCTION_RETURN:"+reversal.reverseEventNo()),"",current.sourceSystem(),current.sourceNo(),current.sourceVersion(),current.lifecycleVersion());
            String hash=FoodStockFingerprint.request(json,"RETURN",raw,List.of(current.reservationNo(),returns));
            var payload=new ReturnCall(current.reservationNo(),returns);var prepared=new FoodStockBridgeJournal.Wire(new FoodLocalStockPort.Command(raw.operationKey(),hash,raw.sourceSystem(),raw.sourceNo(),raw.sourceVersion(),raw.lifecycleVersion()),json.writeValueAsString(payload));
            wire=journal.acquire(scope,origin.plan.planNo(),"RETURN",reversal.reverseEventNo(),intent,()->prepared);
        }
        var payload=json.readValue(wire.payloadJson(),ReturnCall.class);var result=local.returnIssued(scope,wire.command(),payload.reservationNo(),payload.targets());
        if(result==null||!"FOOD_LOCAL".equals(result.owner())||result.commandNo()==null)throw conflict();
        return new InventoryProductionPort.Receipt(c.eventNo(),originalHash,"RESALE".equals(reversal.disposition())?"REVERSED":"DISPOSED",
                result.commandNo(),c.lines(),proven.costMinor(),proven.currency(),proven.issuedLines());
    }
    private InventoryProductionPort.Receipt execute(InventoryProductionPort.Command c,boolean query){outside();var origin=original(c);
        if(!IndustryStockOwnerPolicy.FOOD_LOCAL.equals(selected(origin.source)))throw unsupported();
        var quantities=validate(c,origin.plan);String requestHash=InventoryProductionFacts.hash(c);
        String operation="FP"+FoodStockFingerprint.sha256(c.storeNo()+":"+c.eventNo()).substring(0,62);
        FoodLocalStockPort.Receipt receipt;
        if("PRODUCTION".equals(c.purpose())){
            var provider=owners.exact(origin.source.getInventoryOwner(),origin.plan,origin.protocol);
            if(!(provider instanceof FoodLocalDemandPlanAdapter local))throw unsupported();
            if(query){var known=local.queryProduction(operation,requestHash,quantities);if("NOT_FOUND".equals(known.status()))return absent(c,requestHash);
                if("CONFLICT".equals(known.status()))throw conflict();if(!"COMPLETED".equals(known.status()))return unknown(c,requestHash);receipt=known.receipt();}
            else receipt=local.issueProduction(operation,requestHash,quantities);
        }else{
            var scope=new FoodLocalStockPort.Scope(c.entityNo(),c.storeNo());String intent=FoodStockFingerprint.sha256(json.writeValueAsString(List.of("SUPPLEMENT",origin.plan.planHash(),requestHash)));
            var wire=journal.lookup(scope,origin.plan.planNo(),"SUPPLEMENT",operation,intent);
            if(wire==null&&query)return absent(c,requestHash);
            if(wire==null){var plan=supplement(c,origin,quantities);String number="FSX"+FoodStockFingerprint.sha256(origin.plan.planNo()+":"+operation).substring(0,29);
                var raw=new FoodLocalStockPort.Command(operation,"","CORE_SUPPLEMENT",number,0,0);
                String hash=FoodStockFingerprint.request(json,"SUPPLEMENT",raw,List.of(plan));
                var proposed=new FoodStockBridgeJournal.Wire(new FoodLocalStockPort.Command(operation,hash,"CORE_SUPPLEMENT",number,0,0),json.writeValueAsString(plan));
                wire=journal.acquire(scope,origin.plan.planNo(),"SUPPLEMENT",operation,intent,()->proposed);}
            var local=stock.getIfUnique();if(local==null)throw unsupported();
            if(query){var known=local.findCommand(scope,"SUPPLEMENT",wire.command().operationKey(),wire.command().requestHash());
                if("NOT_FOUND".equals(known.status()))return absent(c,requestHash);if("CONFLICT".equals(known.status()))throw conflict();if(!"COMPLETED".equals(known.status()))return unknown(c,requestHash);receipt=known.receipt();}
            else receipt=local.consumeSupplement(scope,wire.command(),json.readValue(wire.payloadJson(),FoodLocalStockPort.Plan.class));
        }
        return project(c,requestHash,receipt,origin.plan);
    }
    private Original original(InventoryProductionPort.Command c){var p=c.planProof();if(p==null||p.scope()==null||!"MAIN".equals(p.scope().tenantNo())||!c.entityNo().equals(p.scope().entityNo()))throw conflict();
        var rows=DataScopeContext.executeWithoutScope(()->sources.selectList(Wrappers.<OrdIndustryOrderSource>lambdaQuery()
                .eq(OrdIndustryOrderSource::getTenantNo,"MAIN").eq(OrdIndustryOrderSource::getEntityNo,c.entityNo())
                .eq(OrdIndustryOrderSource::getStoreNo,c.storeNo()).eq(OrdIndustryOrderSource::getDemandPlanHash,p.planHash())));
        if(rows.size()!=1)throw conflict();var source=rows.getFirst();if(source.getDemandPlanSnapshot()==null)throw conflict();
        var plan=InventoryDemandPlanFacts.validate(json.readValue(source.getDemandPlanSnapshot(),InventoryDemandPlanPort.Plan.class));
        if(!p.scope().equals(plan.scope())||!p.planHash().equals(plan.planHash())||!plan.planNo().equals(source.getDemandPlanNo()))throw conflict();
        return new Original(source,plan,Set.of("POSTPAY","CHANNEL").contains(source.getOrderMode())?3:2);
    }
    private List<InventoryDemandPlanPort.Target> validate(InventoryProductionPort.Command c,InventoryDemandPlanPort.Plan plan){var proof=c.planProof();
        var branches=plan.branches().stream().filter(b->b.sourceComponentRef().equals(proof.sourceComponentRef())&&b.storeNo().equals(c.storeNo())).toList();
        if(branches.size()!=1||proof.leaves()==null||proof.leaves().isEmpty()||proof.leaves().size()>200)throw conflict();var branch=branches.getFirst();
        var aggregate=new TreeMap<String,BigDecimal>();var seen=new HashSet<String>();var targets=new ArrayList<InventoryDemandPlanPort.Target>();
        for(var selected:proof.leaves()){if(selected==null||!seen.add(selected.leafRef()))throw conflict();InventoryDemandPlanFacts.quantity(selected.quantity(),false);
            var leaf=branch.leaves().stream().filter(l->l.leafRef().equals(selected.leafRef())).findFirst().orElseThrow(FoodProductionStockService::conflict);
            if(!"RECIPE_MATERIAL".equals(InventoryDemandPlanFacts.sourceKind(branch,leaf)))throw conflict();
            var line=c.lines().stream().filter(l->l.itemRef().equals(leaf.itemRef())).findFirst().orElseThrow(FoodProductionStockService::conflict);
            if(!line.unit().equals(leaf.unit())||!line.definitionHash().equals(leaf.definitionHash()))throw conflict();
            aggregate.merge(leaf.itemRef(),selected.quantity(),BigDecimal::add);targets.add(new InventoryDemandPlanPort.Target(selected.leafRef(),selected.quantity()));}
        if(aggregate.size()!=c.lines().size()||c.lines().stream().anyMatch(l->aggregate.get(l.itemRef())==null||aggregate.get(l.itemRef()).compareTo(l.quantity())!=0))throw conflict();
        return targets.stream().sorted(Comparator.comparing(InventoryDemandPlanPort.Target::leafRef)).toList();
    }
    private FoodLocalStockPort.Plan supplement(InventoryProductionPort.Command c,Original o,List<InventoryDemandPlanPort.Target> targets){var branch=o.plan.branches().stream().filter(b->b.sourceComponentRef().equals(c.planProof().sourceComponentRef())).findFirst().orElseThrow(FoodProductionStockService::conflict);
        var leaves=new ArrayList<FoodLocalStockPort.Leaf>();for(var target:targets){var original=branch.leaves().stream().filter(l->l.leafRef().equals(target.leafRef())).findFirst().orElseThrow(FoodProductionStockService::conflict);
            String snapshot=json.writeValueAsString(new Object[]{o.plan.planHash(),c,branch,original});leaves.add(new FoodLocalStockPort.Leaf(original.leafRef(),branch.sourceComponentRef(),original.itemRef(),original.unit(),target.cumulativeQty(),"PRODUCTION",snapshot,FoodStockFingerprint.sha256(snapshot)));}
        var plan=new FoodLocalStockPort.Plan("",null,leaves,o.plan.planHash(),o.protocol);return new FoodLocalStockPort.Plan(FoodStockFingerprint.plan(json,plan),null,leaves,o.plan.planHash(),o.protocol);}
    private InventoryProductionPort.Receipt project(InventoryProductionPort.Command c,String hash,FoodLocalStockPort.Receipt receipt,InventoryDemandPlanPort.Plan plan){if(receipt==null||!"FOOD_LOCAL".equals(receipt.owner())||receipt.commandNo()==null)throw conflict();
        var actual=new ArrayList<InventoryProductionPort.IssuedLine>();for(var selected:c.planProof().leaves()){var progress=receipt.items().stream().filter(l->l.leafRef().equals(selected.leafRef())).findFirst().orElseThrow(FoodProductionStockService::conflict);
            var expected=plan.branches().stream().flatMap(b->b.leaves().stream()).filter(l->l.leafRef().equals(selected.leafRef())).findFirst().orElseThrow(FoodProductionStockService::conflict);
            if(!expected.itemRef().equals(progress.itemNo())||!expected.unit().equals(progress.baseUnit())||!receipt.commandNo().equals(progress.documentRef())||progress.ledgerRef()==null||progress.documentRef()==null||!receipt.movementRefs().contains(progress.ledgerRef())||progress.issued().compareTo(selected.quantity())<0)throw conflict();
            actual.add(new InventoryProductionPort.IssuedLine(progress.leafRef(),progress.itemNo(),selected.quantity(),progress.baseUnit(),progress.documentRef(),progress.ledgerRef(),null,null,null,"UNTRACKED".equals(progress.trackingMode())?"NOT_TRACKED":"UNKNOWN"));}
        return new InventoryProductionPort.Receipt(c.eventNo(),hash,"CONFIRMED",receipt.commandNo(),c.lines(),null,null,actual);}
    private String selected(OrdIndustryOrderSource source){return IndustryStockOwnerPolicy.frozenOwner(source.getInventoryOwner(),"INDUSTRY_INVENTORY_V2");}
    private static InventoryProductionPort.Command normalized(InventoryProductionPort.Command c){try{return InventoryProductionFacts.normalize(c);}catch(IllegalArgumentException invalid){throw BizException.of(ErrorCode.BAD_REQUEST);}}
    private static InventoryProductionPort.Receipt absent(InventoryProductionPort.Command c,String hash){return new InventoryProductionPort.Receipt(c.eventNo(),hash,"ABSENT",null,List.of(),null,null);}
    private static InventoryProductionPort.Receipt unknown(InventoryProductionPort.Command c,String hash){return new InventoryProductionPort.Receipt(c.eventNo(),hash,"UNKNOWN",null,List.of(),null,null);}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Kitchen stock calls must not hold CORE transactions");}
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
    private static BizException unsupported(){return BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
}
