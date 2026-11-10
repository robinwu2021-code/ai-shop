package ai.neargo.shop.infra;

import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.AggregateReceiptV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.Applicability;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentKind;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentObservationV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentRouteV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.OwnerProof;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RowProof;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionState;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.PlatformExecutionStockRetentionComponent;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AcceptedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartRequest;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.OwnSlot;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.SourceGroup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthority;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.Continuity;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.ContinuityState;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.OriginalFacts;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.PrepareResult;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.UnitState;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import java.time.Instant;
import java.util.List;

/** Fully signed cross-owner test evidence. Native tests still execute all actual Native mapper/service writes. */
public record NativeResourceProofFixture(StartRequest request, StartAuthority source,
        ExecutionOriginalEvidence core, AggregateReceiptV2 retention) {
    public static final String H="a".repeat(64);
    public static final DelegatedIdentity ACTOR=new DelegatedIdentity("MAIN","MERCHANT","MCH","ACCOUNT");
    public static String resourcePlan(Instant at) {
        var span=List.of(new ResourcePlanContract.Interval(at.minusSeconds(60),at.plusSeconds(3600)));
        return ResourcePlanContract.encode(List.of(new ResourcePlanContract.Demand("STAFF1","STAFF_SLOT",1,span),
                new ResourcePlanContract.Demand("ROOM1","ROOM_SLOT",1,span)),
                List.of(new ResourcePlanContract.Qualification("STAFF1","STAFF_SLOT",null,null,false)));
    }
    public static NativeResourceProofFixture create(Instant at) {
        String binding="{}";
        var ref=new ExecutionReference("SERVICE_EXECUTION_REFERENCE_V1",new ExecutionScope("MAIN","E","S"),
                ExecutionReference.SourceKind.STORE_ORDER,"MANUAL_EXECUTION_V1","EX","GRANT","UNIT","TASK","WORK",1,1,
                H,H,ExecutionEvidenceContract.bindingBytesHash(binding),H,H,"FENCE",1,H,"BTRC",H,"PREPARE",H,H,null).signed();
        return create(ref,binding,"{}",resourcePlan(at),at);
    }
    public static NativeResourceProofFixture create(ExecutionReference ref,String bindingJson,String frozenTaskPlan,
            String resourcePlanJson,Instant at) {
        return create(ref,bindingJson,frozenTaskPlan,resourcePlanJson,at,ACTOR);
    }
    public static NativeResourceProofFixture create(ExecutionReference ref,String bindingJson,String frozenTaskPlan,
            String resourcePlanJson,Instant at,DelegatedIdentity actor) {
        return create(ref,bindingJson,frozenTaskPlan,resourcePlanJson,at,actor,null);
    }
    public static NativeResourceProofFixture create(ExecutionOriginalEvidence core,String resourcePlanJson,
            Instant at,DelegatedIdentity actor) {
        return create(core.reference(),core.facts().originalBindingJson(),core.facts().frozenPlan(),resourcePlanJson,at,actor,core);
    }
    private static NativeResourceProofFixture create(ExecutionReference ref,String bindingJson,String frozenTaskPlan,
            String resourcePlanJson,Instant at,DelegatedIdentity actor,ExecutionOriginalEvidence persistedCore) {
        var needs=List.of(new StockDispositionPort.Need("SKU","S","FULFILL",1));
        var stock=persistedCore==null ? new StockDispositionPort.Original("MAIN","E","ORDER","PLATFORM",2,
                StockDispositionContract.manifestHash(needs),needs) : persistedCore.facts().stockOriginal();
        var lookup=ExecutionStockRetentionContract.request(ref,stock,StockDispositionContract.operationNo(stock)).lookup();
        var descriptor=PlatformExecutionStockRetentionComponent.descriptor(lookup,"CONFIRMATION");
        var row=new RowProof(1,"LOCK","SKU","S","S","FULFILL",2,false,1,stock.manifestHash(),
                lookup.confirmationOperationNo(),0,0,"HELD",0,List.of());
        var owner=new OwnerProof("PLATFORM",ref.scope(),stock.orderNo(),lookup.retentionNo(),lookup.requestHash(),
                ExecutionStockRetentionContract.originalHash(stock),lookup.confirmationOperationNo(),H,1,List.of(row),
                RetentionState.RETAINED,1,null,null,null);
        var legacy=new ExecutionStockRetentionPort.Receipt("EXECUTION_STOCK_RETENTION_RECEIPT_V1",ref.referenceHash(),
                lookup.retentionNo(),lookup.requestHash(),"PLATFORM",H,List.of(owner),Outcome.RETAINED,null,at,H);
        var component=PlatformExecutionStockRetentionComponent.receipt(descriptor,legacy); component.validate();
        var roster=List.of(new ComponentRouteV2(ComponentKind.PLATFORM,descriptor.componentNo(),descriptor.componentHash(),
                Applicability.REQUIRED,descriptor,null,null));
        var observations=List.of(new ComponentObservationV2(ComponentKind.PLATFORM,descriptor.componentNo(),descriptor.componentHash(),
                Outcome.RETAINED,component,null,null));
        var unsigned=new AggregateReceiptV2("EXECUTION_STOCK_AGGREGATE_RECEIPT_V2",ref.referenceHash(),lookup.retentionNo(),
                lookup.requestHash(),ExecutionStockRetentionAggregateContract.routeNo(lookup),H,"PLATFORM",
                ExecutionStockRetentionAggregateContract.rosterHash(roster),observations,Outcome.RETAINED,null,at,null);
        var retained=new AggregateReceiptV2(unsigned.schema(),unsigned.referenceHash(),unsigned.retentionNo(),unsigned.requestHash(),
                unsigned.routeNo(),unsigned.routeHash(),unsigned.frozenAuthority(),unsigned.ownerRosterHash(),unsigned.components(),
                unsigned.outcome(),null,at,ExecutionEvidenceContract.digest(unsigned.schema(),unsigned,"receiptHash"));
        var person=persistedCore==null ? new OperatorPersonnelFact("PERSON","EMPLOYMENT",0,0,0,at.minusSeconds(86400),at.plusSeconds(86400))
                : persistedCore.facts().frozenPersonnel();
        var accepted=List.of(new AcceptedStaffRef("ASSIGNMENT",1,"STAFF_REQ",1,"THERAPIST","STAFF1","PERSON", "ACCEPT",H,H,actor,"ACCOUNT",person));
        var continuity=new Continuity(ContinuityState.READY_UNCHANGED,1,1,1L,H,null);
        var sourceLookup=new StartAuthorityLookup(ref,"START_GATE",H);
        var source=new StartAuthority("SERVICE_TASK_START_AUTHORITY_V1",sourceLookup,1,"START_ACTION",H,
                "TEST_TASK_PLAN_V1",frozenTaskPlan,ref.planHash(),resourcePlanJson,ResourcePlanContract.hash(resourcePlanJson),
                List.of(new SourceGroup("STAFF_SLOT","STAFF_REQ",1,"THERAPIST","STAFF1","PERSON"),
                        new SourceGroup("ROOM_SLOT","ROOM_REQ",1,"ROOM","ROOM1",null)),null,accepted,
                List.of(new OwnSlot("STAFF_REQ",1,1)),roster,continuity,actor,"ACCOUNT",person,at,at.plusSeconds(300),null).signed();
        var facts=new OriginalFacts(bindingJson,ExecutionEvidenceContract.bindingBytesHash(bindingJson),ref.preparedGrantRevision(),
                new PrepareResult(ref.prepareActionNo(),ref.prepareActionHash(),ref.prepareKeyHash(),"PREPARED",ref.preparedGrantRevision()),
                ref.preparedGrantRevision(),0,0,GrantState.PREPARED,UnitState.RESERVED,ref.executionNo(),ref.attemptNo(),1,"COUNT",
                "DECLARATION","CONFIRMATION",1,stock,lookup.confirmationOperationNo(),H,ref.sourceIntentHash(),ref.planHash(),
                ref.plannedResourceCommandNo(),ref.plannedResourceCommandHash(),frozenTaskPlan,continuity,null,H,person);
        var core=persistedCore==null ? new ExecutionOriginalEvidence("SERVICE_EXECUTION_ORIGINAL_EVIDENCE_V1",ref,
                ExecutionOriginalEvidence.EvidenceOutcome.PROVEN,null,facts,at,null).signed() : persistedCore;
        var request=new StartRequest(ref,sourceLookup,"START_KEY",ResourceOccupationContract.stableRequestHash(ref,sourceLookup,"START_KEY"));
        var result=new NativeResourceProofFixture(request,source,core,retained);
        result.accepted(at).validate(); return result;
    }
    public AcceptedStart accepted(Instant at) {
        var f=core.facts();
        var lookup=ExecutionStockRetentionContract.request(request.reference(),f.stockOriginal(),f.stockConfirmationOperationNo()).lookup();
        return new AcceptedStart("RESOURCE_OCCUPATION_ACCEPTED_V1",request,ResourceOccupationContract.PlannedOriginal.of(request.reference()),
                source,core,lookup,retention,at,null).signed();
    }
}
