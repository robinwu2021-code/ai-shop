package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchResourceBucket;
import ai.neargo.shop.merchant.entity.MchResourceCommand;
import ai.neargo.shop.merchant.entity.MchResourceOccupation;
import ai.neargo.shop.merchant.entity.MchResourceOccupationBucket;
import ai.neargo.shop.merchant.entity.MchResourceOccupationSegment;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingOwnerMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceCommandMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationSegmentMapper;
import ai.neargo.shop.merchant.mapper.ResourceOccupationLifecycleMapper;
import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
import ai.neargo.shop.merchant.policy.ResourcePhysicalProtectionPlan;
import ai.neargo.shop.merchant.policy.ResourceStartAccess;
import ai.neargo.shop.merchant.policy.ResourceWorkflowSegment;
import ai.neargo.shop.merchant.service.ResourceAdmissionService;
import ai.neargo.shop.merchant.service.ResourceOccupationLifecycleService;
import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceFactsPort.ResourceFact;
import ai.neargo.shop.spi.resource.ResourceFactsPort.Type;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.Outcome;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.ActualOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Cancellation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CancellationOutcome;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentWorkflowProof;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthority;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AccessWitness;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Action;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Request;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentOccupation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Segment;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.CleaningRule;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.WorkflowAuthority;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** One local transaction journals the original action, closes/opens real segments and changes all bucket counters. */
public final class ResourceOccupationLifecycleServiceImpl implements ResourceOccupationLifecycleService {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ServiceTaskWorkflowAuthorityPort sources;
    private final ServiceTaskStartAuthorityPort starts;
    private final ResourceExecutionEvidencePort evidence;
    private final ResourceOccupationGuard guard;
    private final ResourceStartAccess access;
    private final ResourceAdmissionService admission;
    private final BookingOwnerMapper owners;
    private final ResourceCommandMapper commands;
    private final ResourceBucketMapper buckets;
    private final ResourceOccupationSegmentMapper segmentRows;
    private final ResourceOccupationBucketMapper projections;
    private final ResourceOccupationLifecycleMapper lifecycle;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final Clock clock;

    public ResourceOccupationLifecycleServiceImpl(ServiceTaskWorkflowAuthorityPort sources, ServiceTaskStartAuthorityPort starts, ResourceExecutionEvidencePort evidence,
            ResourceOccupationGuard guard, ResourceStartAccess access, ResourceAdmissionService admission,
            BookingOwnerMapper owners, ResourceCommandMapper commands, ResourceBucketMapper buckets,
            ResourceOccupationSegmentMapper segmentRows, ResourceOccupationBucketMapper projections,
            ResourceOccupationLifecycleMapper lifecycle, PlatformTransactionManager manager, Clock clock) {
        this.sources=sources; this.starts=starts; this.evidence=evidence; this.guard=guard; this.access=access; this.admission=admission;
        this.owners=owners; this.commands=commands; this.buckets=buckets; this.segmentRows=segmentRows;
        this.projections=projections; this.lifecycle=lifecycle; this.clock=clock;
        writes=new TransactionTemplate(manager); writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads=new TransactionTemplate(manager); reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public record Original(Request request, WorkflowAuthority authority, ActualOriginal startActual) { }

    @Override public Result execute(Request request) {
        DelegatedIdentityContext.outside(); request.validate();
        var original=evidence.queryOriginal(request.startOriginal()); original.validate();
        require(original.outcome()==Outcome.APPLIED && original.original().equals(request.startOriginal())
                && request.occupationNo().equals(original.applied().occupationNo()),
                "actual START effect is required");
        var authority=sources.authorizeCurrent(request.authority()); authority.validate();
        requireAuthority(request,authority,now());
        try {
            return DataScopeContext.executeWithoutScope(() -> writes.execute(tx -> apply(request,authority,original.actual())));
        } catch (DuplicateKeyException collision) {
            return queryOriginal(request).orElseThrow(() -> collision);
        }
    }

    @Override public Optional<Result> queryOriginal(Request request) {
        DelegatedIdentityContext.outside(); request.validate();
        return DataScopeContext.executeWithoutScope(() -> reads.execute(tx -> readResult(request)));
    }

    @Override public CurrentOccupation queryCurrent(OriginalLookup original) {
        DelegatedIdentityContext.outside();original.validate();
        var initial=evidence.queryOriginal(original);initial.validate();
        require(initial.outcome()==Outcome.APPLIED&&initial.original().equals(original),"current occupation requires its exact actual START original");
        var ref=original.reference();var scope=new ResourceCommandScopeRow(ref.scope().tenantNo(),ref.scope().entityNo(),ref.scope().storeNo());
        return DataScopeContext.executeWithoutScope(() -> reads.execute(tx -> {
            var header=lifecycle.read(scope,initial.applied().occupationNo());
            require(header!=null&&header.getOwnerNo().equals(initial.actual().ownerNo())
                    && header.getStartCommandNo().equals(initial.actual().commandNo())&&header.getReferenceHash().equals(ref.referenceHash()),
                    "current occupation owner binding changed");
            guard.inspectNativeEvidence(scope);
            var segments=lifecycle.segments(scope,header.getOccupationNo()).stream().map(ResourceOccupationLifecycleServiceImpl::segment).toList();
            var current=new CurrentOccupation(original,initial.actual(),header.getOccupationNo(),header.getRevision(),header.getState(),
                    instant(header.getEndedAt()),segments,header.getStopObligation()!=0,header.getConflictObligation()!=0,
                    header.getObservationEpoch(),now(),null).signed();current.validate();return current;
        }));
    }

    @Override public Cancellation cancelBeforeEffect(Request request) {
        DelegatedIdentityContext.outside(); request.validate();
        var start=evidence.queryOriginal(request.startOriginal());start.validate();
        require(start.outcome()==Outcome.APPLIED && start.original().equals(request.startOriginal())
                && request.occupationNo().equals(start.applied().occupationNo()),"actual START original required for workflow cancellation");
        var authority=sources.authorizeCurrent(request.authority());authority.validate();
        requireCancelAuthority(request,authority,now());
        try {
            return DataScopeContext.executeWithoutScope(() -> writes.execute(tx -> {
                var scope=scope(request);
                require(owners.lockScope(scope.tenantNo(),scope.entityNo(),scope.storeNo())!=null,"current cancellation scope unavailable");
                var owner=owners.lockOwner(scope,start.actual().ownerNo());
                require(owner!=null && start.actual().commandNo().equals(owner.getInitialStartCommandNo())
                        && request.startOriginal().reference().referenceHash().equals(owner.getExecutionReferenceHash()),"workflow cancellation START changed");
                var header=lifecycle.lock(scope,request.occupationNo());
                require(header!=null && header.getOwnerNo().equals(owner.getOwnerNo()),"workflow cancellation occupation changed");
                var rows=lifecycle.segments(scope,request.occupationNo());
                admission.lockWorkflow(scope,rows.stream().map(MchResourceOccupationSegment::getResourceNo).collect(Collectors.toSet()),null,List.of());
                access.lockWorkflowCurrent(scope,authority);
                requireCancelAuthority(request,authority,now());
                var known=readCancellation(request); if(known.isPresent()) return known.get();
                guard.inspectNativeEvidence(scope);
                var at=now();var command=command(request,authority,header,start.actual(),at);
                command.setAction("VALIDATE_WORKFLOW");command.setPayloadSchema("RESOURCE_OCCUPATION_VALIDATE_WORKFLOW_V1");
                commands.insert(command);
                var seal=new Cancellation(request,CancellationOutcome.NO_EFFECT_SEALED,null,request.actionNo(),BizKey.next("RSL"),null).signed();
                one(commands.finishOccupation(scope,command.getCommandNo(),command.getVersion(),"REJECTED",JSON.writeValueAsString(seal),
                        "WORKFLOW_NO_EFFECT_SEALED",seal.sealNo(),1,seal.proofHash(),"REJECTED",null,null,null,authority.actor().actorNo(),local(at)));
                return seal;
            }));
        } catch(DuplicateKeyException collision) {
            return queryCancellationOriginal(request).orElseThrow(() -> collision);
        }
    }
    @Override public Optional<Cancellation> queryCancellationOriginal(Request request) {
        DelegatedIdentityContext.outside();request.validate();
        return DataScopeContext.executeWithoutScope(() -> reads.execute(tx -> readCancellation(request)));
    }
    private Optional<Cancellation> readCancellation(Request request) {
        var command=commands.selectCommand(scope(request),request.actionNo());
        if(command==null) return Optional.empty();
        if("SUCCEEDED".equals(command.getStatus())) {
            var applied=readResult(request).orElseThrow();
            return Optional.of(new Cancellation(request,CancellationOutcome.APPLIED,applied,request.actionNo(),null,null).signed());
        }
        require("REJECTED".equals(command.getStatus())&&"VALIDATE_WORKFLOW".equals(command.getAction())
                && request.payloadHash().equals(command.getPayloadHash())&&request.keyHash().equals(command.getRequestKeyHash()),
                "workflow cancellation conflicts with an unresolved original");
        var original=JSON.readValue(command.getFrozenPayload(),Original.class);original.authority().validate();
        var seal=JSON.readValue(command.getResultPayload(),Cancellation.class);seal.validate();
        require(original.request().equals(request)&&original.authority().action()==ServiceTaskWorkflowAuthorityPort.Action.CANCEL
                && seal.request().equals(request)&&seal.outcome()==CancellationOutcome.NO_EFFECT_SEALED
                && command.getOriginalResultNo().equals(seal.sealNo())&&command.getOriginalResultHash().equals(seal.proofHash()),
                "workflow no-effect cancellation original changed");
        var start=commands.selectCommand(scope(request),original.startActual().commandNo());
        require(start!=null&&original.startActual().ownerNo().equals(start.getOwnerNo())
                && original.startActual().payloadHash().equals(start.getPayloadHash())
                && original.startActual().payloadSchema().equals(start.getPayloadSchema())
                && original.startActual().commandNo().equals(command.getOriginalStartCommandNo())
                && request.startOriginal().reference().referenceHash().equals(start.getExecutionReferenceHash()),"cancellation actual START tuple changed");
        return Optional.of(seal);
    }
    private static void requireCancelAuthority(Request request,WorkflowAuthority authority,Instant at) {
        require(authority.action()==ServiceTaskWorkflowAuthorityPort.Action.CANCEL&&authority.lookup().equals(request.authority())
                && authority.actionNo().equals(request.actionNo())&&authority.actionHash().equals(request.payloadHash())
                && authority.expectedOccupationRevision()==request.expectedRevision()&&authority.actor().equals(currentIdentity())
                && !at.isBefore(authority.issuedAt())&&at.isBefore(authority.validUntil()),"current cancellation authority changed");
    }

    @Override public CurrentWorkflowProof validateCurrentStart(StartAuthorityLookup lookup,OriginalLookup original) {
        DelegatedIdentityContext.outside();original.validate();
        require(lookup.equals(original.source()),"current START source original changed");
        var nativeOriginal=evidence.queryOriginal(original);nativeOriginal.validate();
        require(nativeOriginal.outcome()==Outcome.APPLIED&&nativeOriginal.original().equals(original),"current START actual effect required");
        var authority=starts.authorizeCurrent(lookup);authority.validate();
        require(authority.original().equals(lookup)&&authority.actor().equals(currentIdentity()),"current START source authority changed");
        return DataScopeContext.executeWithoutScope(() -> writes.execute(tx ->
                validateCurrentLocked(original,nativeOriginal.actual(),nativeOriginal.applied().occupationNo(),null,null,authority)));
    }
    @Override public CurrentWorkflowProof validateCurrentWorkflow(Request request) {
        DelegatedIdentityContext.outside();request.validate();
        require(request.action()==Action.RESUME||request.action()==Action.HANDOVER,"current workflow participation proof is finite");
        var applied=queryOriginal(request).orElseThrow(() -> new IllegalStateException("actual workflow original is not applied"));
        var nativeOriginal=evidence.queryOriginal(request.startOriginal());nativeOriginal.validate();
        require(nativeOriginal.outcome()==Outcome.APPLIED&&nativeOriginal.original().equals(request.startOriginal()),"current workflow START original changed");
        var authority=sources.authorizeCurrent(request.authority());authority.validate();requireAuthority(request,authority,now());
        return DataScopeContext.executeWithoutScope(() -> writes.execute(tx ->
                validateCurrentLocked(request.startOriginal(),nativeOriginal.actual(),request.occupationNo(),request,applied,authority)));
    }
    private CurrentWorkflowProof validateCurrentLocked(OriginalLookup original,ActualOriginal actual,String occupationNo,
            Request workflow,Result applied,Object sourceAuthority) {
        var ref=original.reference();var scope=new ResourceCommandScopeRow(ref.scope().tenantNo(),ref.scope().entityNo(),ref.scope().storeNo());
        require(owners.lockScope(scope.tenantNo(),scope.entityNo(),scope.storeNo())!=null,"current proof scope unavailable");
        var owner=owners.lockOwner(scope,actual.ownerNo());
        require(owner!=null&&actual.commandNo().equals(owner.getInitialStartCommandNo())&&ref.referenceHash().equals(owner.getExecutionReferenceHash()),"current proof actual START changed");
        var header=lifecycle.lock(scope,occupationNo);
        require(header!=null&&actual.ownerNo().equals(header.getOwnerNo())&&header.getStopObligation()==0&&header.getConflictObligation()==0
                && (workflow==null ? "OPEN".equals(header.getState()) : header.getRevision()==applied.revision()
                    && header.getState().equals(applied.state())&&("OPEN".equals(header.getState())||"PAUSED".equals(header.getState()))),
                "actual resource lifecycle no longer permits current participation");
        var all=lifecycle.segments(scope,occupationNo);var active=all.stream().filter(s -> s.getActualEndAt()==null).toList();
        require(!active.isEmpty(),"current actual resource segments are absent");
        var at=now();var plan=currentPlan(active,at);
        var roster=sourceAuthority instanceof StartAuthority start ? start.acceptedRoster() : ((WorkflowAuthority)sourceAuthority).acceptedRoster();
        var resources=all.stream().map(MchResourceOccupationSegment::getResourceNo).collect(Collectors.toSet());
        var qualification=admission.lockWorkflow(scope,resources,plan,roster);
        List<AccessWitness> accessProof;
        Instant until;
        if(sourceAuthority instanceof StartAuthority start) {
            require(start.actor().equals(currentIdentity())&&at.isBefore(start.validUntil()),"current source START authority expired");
            require(ResourcePlanContract.decode(start.resourcePlanJson()).demands().stream().map(ResourcePlanContract.Demand::resourceNo).distinct().sorted().toList()
                    .equals(active.stream().map(MchResourceOccupationSegment::getResourceNo).distinct().sorted().toList()),"actual resources differ from source START groups");
            accessProof=access.lockCurrent(scope,start);until=start.validUntil();
        } else {
            var authority=(WorkflowAuthority)sourceAuthority;requireAuthority(workflow,authority,at);
            accessProof=access.lockWorkflowCurrent(scope,authority);until=authority.validUntil();
        }
        guard.inspectNativeEvidence(scope);
        requireCurrentCapacity(scope,active,qualification.resources().stream().collect(Collectors.toMap(ResourceFact::resourceNo,Function.identity())),at);
        at=now();
        var horizon=active.stream().map(MchResourceOccupationSegment::getExpectedEndAt).map(ResourceOccupationLifecycleServiceImpl::instant).min(Comparator.naturalOrder()).orElseThrow();
        if(horizon.isBefore(until)) until=horizon;
        if(at.plusSeconds(15).isBefore(until)) until=at.plusSeconds(15);
        require(at.isBefore(until),"current actual qualification proof expired while acquiring roots");
        String commandNo=BizKey.next("RVF"),resultNo=BizKey.next("RRR");
        var segmentFacts=all.stream().map(ResourceOccupationLifecycleServiceImpl::segment).toList();
        var proof=new CurrentWorkflowProof(original.source(),original,workflow==null?null:workflow.authority(),actual,occupationNo,
                header.getRevision(),ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_WORKFLOW_SEGMENTS_V1",segmentFacts),segmentFacts,
                qualification.qualifications(),accessProof,at,until,commandNo,resultNo,null).signed();proof.validate();
        var frozen=JSON.writeValueAsString(Map.of("schema","RESOURCE_CURRENT_WORKFLOW_REQUEST_V1","original",original,"authority",sourceAuthority));
        var command=new MchResourceCommand();command.setTenantNo(scope.tenantNo());command.setEntityNo(scope.entityNo());command.setStoreNo(scope.storeNo());
        command.setCommandNo(commandNo);command.setOwnerNo(actual.ownerNo());command.setAction("VALIDATE_WORKFLOW");command.setCommandKind("OCCUPATION_V1");
        command.setPayloadSchema("RESOURCE_OCCUPATION_VALIDATE_WORKFLOW_V1");command.setPayloadHash(ExecutionEvidenceContract.hash(command.getPayloadSchema(),frozen));
        command.setRequestKeyHash(ExecutionEvidenceContract.hash("RESOURCE_CURRENT_VALIDATION_KEY_V1",List.of(commandNo,original)));
        command.setFrozenPayload(frozen);command.setExpectedOwnerVersion(owner.getOwnerVersion());command.setSourceRevision(ref.sourceFenceRevision());
        command.setStatus("ACCEPTED");command.setInitiatedBy(currentIdentity().actorNo());command.setExecutionReferenceJson(JSON.writeValueAsString(ref));
        command.setExecutionReferenceHash(ref.referenceHash());command.setPlannedResourceCommandNo(ref.plannedResourceCommandNo());
        command.setPlannedResourceCommandHash(ref.plannedResourceCommandHash());command.setExecutionPlanHash(ref.planHash());command.setOriginalStartCommandNo(actual.commandNo());
        command.setActorNamespace(currentIdentity().subjectKind());command.setActorProvenanceHash(ExecutionEvidenceContract.hash("RESOURCE_CURRENT_SOURCE_V1",sourceAuthority));
        command.setObservationEpoch(0L);audit(command,currentIdentity().actorNo(),at);commands.insert(command);
        one(commands.finishOccupation(scope,commandNo,command.getVersion(),"SUCCEEDED",JSON.writeValueAsString(proof),null,resultNo,header.getRevision(),
                proof.proofHash(),"APPLIED",null,null,null,currentIdentity().actorNo(),local(at)));
        return proof;
    }

    private Optional<Result> readResult(Request request) {
        var command=commands.selectCommand(scope(request),request.actionNo());
        if(command==null) return Optional.empty();
        require("OCCUPATION_V1".equals(command.getCommandKind()) && request.payloadHash().equals(command.getPayloadHash())
                && request.keyHash().equals(command.getRequestKeyHash())
                && request.startOriginal().reference().referenceHash().equals(command.getExecutionReferenceHash())
                ,"workflow action identity conflict");
        if ("REJECTED".equals(command.getStatus()) && "VALIDATE_WORKFLOW".equals(command.getAction())) {
            var sealed=JSON.readValue(command.getResultPayload(),Cancellation.class);sealed.validate();
            require(sealed.request().equals(request)&&sealed.outcome()==CancellationOutcome.NO_EFFECT_SEALED,
                    "workflow permanent rejection is not its exact cancellation seal");
            return Optional.empty();
        }
        var original=JSON.readValue(command.getFrozenPayload(),Original.class);
        original.authority().validate();
        var start=commands.selectCommand(scope(request),original.startActual().commandNo());
        require(start!=null && original.startActual().ownerNo().equals(start.getOwnerNo())
                && original.startActual().commandNo().equals(command.getOriginalStartCommandNo())
                && original.startActual().ownerNo().equals(command.getOwnerNo())
                && original.startActual().payloadHash().equals(start.getPayloadHash())
                && original.startActual().payloadSchema().equals(start.getPayloadSchema())
                && request.startOriginal().reference().referenceHash().equals(start.getExecutionReferenceHash())
                && (request.startOriginal().actual()==null || request.startOriginal().actual().equals(original.startActual())),
                "workflow exact actual START tuple changed");
        require(request.equals(original.request()) && "SUCCEEDED".equals(command.getStatus()),"workflow original is not applied");
        var result=JSON.readValue(command.getResultPayload(),Result.class); result.validate();
        require(request.equals(result.request()) && command.getCommandNo().equals(result.commandNo())
                && command.getOriginalResultNo().equals(result.resultNo())
                && command.getOriginalResultRevision()==result.revision()
                && command.getOriginalResultHash().equals(result.resultHash()),"workflow result evidence changed");
        if (request.action()==Action.END) {
            var header=lifecycle.read(scope(request),request.occupationNo());
            var actual=lifecycle.segments(scope(request),request.occupationNo());
            require(header!=null && result.endedAt()!=null && result.endedAt().equals(instant(header.getEndedAt()))
                    && !actual.isEmpty() && actual.size()==result.segments().size()
                    && result.segments().stream().allMatch(s -> s.endedAt()!=null && actual.stream().anyMatch(row ->
                    row.getSegmentNo().equals(s.segmentNo()) && row.getResourceNo().equals(s.resourceNo())
                    && row.getLogicalDemandNo().equals(s.logicalDemandNo()) && row.getQty()==s.quantity()
                    && s.startedAt().equals(instant(row.getActualStartAt())) && s.endedAt().equals(instant(row.getActualEndAt())))),
                    "END journal lacks complete actual closed segments");
        }
        return Optional.of(result);
    }

    private Result apply(Request request, WorkflowAuthority authority,ActualOriginal startActual) {
        var scope=scope(request);
        require(owners.lockScope(scope.tenantNo(),scope.entityNo(),scope.storeNo())!=null,"current workflow scope unavailable");
        var owner=owners.lockOwner(scope,startActual.ownerNo());
        require(owner!=null && startActual.commandNo().equals(owner.getInitialStartCommandNo())
                && request.startOriginal().reference().referenceHash().equals(owner.getExecutionReferenceHash()),"workflow owner changed");
        var replay=readResult(request); if(replay.isPresent()) return replay.get();
        require(commands.selectCommand(scope,request.actionNo())==null,"WORKFLOW_NO_EFFECT_SEALED");
        var occupation=lifecycle.lock(scope,request.occupationNo());
        require(occupation!=null && owner.getOwnerNo().equals(occupation.getOwnerNo())
                && occupation.getRevision()==request.expectedRevision(),"occupation revision conflict; query original");
        var segments=lifecycle.segments(scope,request.occupationNo());
        var active=segments.stream().filter(s -> s.getActualEndAt()==null).toList();
        requireActionState(request.action(),occupation.getState());
        var at=now();
        String nextPlan=request.action()==Action.HANDOVER ? authority.replacementResourcePlanJson()
                : request.action()==Action.RESUME ? currentPlan(active,at) : null;
        Set<String> resourceNos=segments.stream().map(MchResourceOccupationSegment::getResourceNo).collect(Collectors.toCollection(HashSet::new));
        if(nextPlan!=null) ResourcePlanContract.decode(nextPlan).demands().forEach(d -> resourceNos.add(d.resourceNo()));
        var qualified=admission.lockWorkflow(scope,resourceNos,nextPlan,authority.acceptedRoster());
        var facts=qualified.resources().stream().collect(Collectors.toMap(ResourceFact::resourceNo,Function.identity()));
        access.lockWorkflowCurrent(scope,authority);
        at=now(); requireAuthority(request,authority,at);
        guard.inspectNativeEvidence(scope);
        var command=command(request,authority,occupation,startActual,at); commands.insert(command);
        Map<String,Long> debits=new TreeMap<>();
        List<MchResourceOccupationSegment> closing=switch(request.action()) {
            case END -> active;
            case HANDOVER -> selected(active,request);
            default -> List.of();
        };
        for(var segment:closing) {
            var rule=cleaningRule(segment,authority.cleaningRules(),facts.get(segment.getResourceNo()));
            var cleanFrom="MANUAL".equals(rule.mode()) ? local(at) : null;
            one(lifecycle.close(scope,occupation.getOccupationNo(),segment.getSegmentNo(),segment.getVersion(),
                    command.getCommandNo(),cleanFrom,cleanFrom==null?null:cleanFrom.plusMinutes(rule.expectedMinutes()),local(at),authority.actor().actorNo()));
        }
        if(!closing.isEmpty()) for(var projection:projections.selectOriginal(scope,occupation.getOccupationNo())) {
            if(projection.getRetiredAt()!=null || closing.stream().noneMatch(s -> s.getSegmentNo().equals(projection.getSegmentNo()))) continue;
            one(lifecycle.retire(scope,projection.getProjectionNo(),projection.getVersion(),command.getCommandNo(),local(at),authority.actor().actorNo()));
            require("OCCUPIED".equals(projection.getCategory()),"active service projection has wrong category");
            debits.merge(projection.getBucketNo(),-(long)projection.getQty(),Math::addExact);
        }
        // Close debits are applied while all old/new resource roots are held, before any successor capacity credit.
        for(var debit:debits.entrySet()) {
            var bucket=buckets.selectOccupationBucket(scope,debit.getKey());
            require(bucket!=null,"original projection bucket missing");
            one(lifecycle.delta(scope,bucket.getBucketNo(),bucket.getVersion(),debit.getValue(),0,local(at),authority.actor().actorNo()));
        }
        if(request.action()==Action.HANDOVER) handover(scope,request,authority,closing,nextPlan,qualified,facts,at);
        if(request.action()==Action.CLEAN) for(var no:request.cleaningSegmentNos()) {
            var segment=segments.stream().filter(s -> no.equals(s.getSegmentNo())).findFirst().orElseThrow();
            require(segment.getActualEndAt()!=null && segment.getCleaningFrom()!=null && segment.getCleaningCompletedAt()==null,
                    "original manual cleaning is not pending");
            one(lifecycle.clean(scope,occupation.getOccupationNo(),no,segment.getVersion(),command.getCommandNo(),local(at),authority.actor().actorNo()));
        }
        var after=lifecycle.segments(scope,occupation.getOccupationNo());
        if(request.action()==Action.RESUME||request.action()==Action.HANDOVER)
            requireCurrentCapacity(scope,after.stream().filter(s -> s.getActualEndAt()==null).toList(),facts,at);
        var ended=request.action()==Action.END ? local(at) : occupation.getEndedAt();
        String state=switch(request.action()) {
            case PAUSE -> "PAUSED";
            case RESUME -> "OPEN";
            case HANDOVER -> occupation.getState();
            case END,CLEAN -> ended==null ? occupation.getState() : after.stream().anyMatch(s ->
                    s.getCleaningFrom()!=null && s.getCleaningCompletedAt()==null) ? "CLEANING" : "ENDED";
        };
        var segmentFacts=after.stream().map(ResourceOccupationLifecycleServiceImpl::segment).toList();
        var result=new Result(request,command.getCommandNo(),BizKey.next("RRR"),Math.addExact(occupation.getRevision(),1),
                state,at,instant(ended),segmentFacts,"","").signed();
        one(lifecycle.advance(scope,occupation.getOccupationNo(),occupation.getRevision(),state,ended,result.segmentSetHash(),
                request.action()==Action.RESUME||request.action()==Action.HANDOVER,local(at),authority.actor().actorNo()));
        one(commands.finishOccupation(scope,command.getCommandNo(),command.getVersion(),"SUCCEEDED",JSON.writeValueAsString(result),null,
                result.resultNo(),result.revision(),result.resultHash(),"APPLIED",null,null,null,authority.actor().actorNo(),local(at)));
        return result;
    }

    private void handover(ResourceCommandScopeRow scope, Request request, WorkflowAuthority authority,
            List<MchResourceOccupationSegment> closing,String nextPlan,ResourceAdmissionService.WorkflowAdmission qualified,
            Map<String,ResourceFact> facts,Instant at) {
        var physical=ResourcePhysicalProtectionPlan.from(ResourcePlanContract.decode(nextPlan),at);
        var current=lifecycle.segments(scope,request.occupationNo());
        var targetNos=new HashSet<String>();
        for(var old:closing) {
            var replacement=request.replacements().stream().filter(r -> r.segmentNo().equals(old.getSegmentNo())).findFirst().orElseThrow();
            require(facts.get(old.getResourceNo()).resourceType()==facts.get(replacement.newResourceNo()).resourceType()
                    && !old.getResourceNo().equals(replacement.newResourceNo()) && targetNos.add(replacement.newResourceNo())
                    && current.stream().noneMatch(s -> s.getActualEndAt()==null && s.getResourceNo().equals(replacement.newResourceNo())),
                    "handover cannot merge permanent physical demand groups");
            var group=physical.groups().stream().filter(g -> g.resourceNo().equals(replacement.newResourceNo())).findFirst().orElseThrow();
            require(group.peakQty()==old.getQty(),"handover changes permanent physical quantity");
            var priorPlan=ResourcePlanContract.decode(plan(old));
            var priorLines=priorPlan.demands().stream().filter(d -> d.resourceNo().equals(old.getResourceNo()))
                    .map(d -> d.sourceLineRef()+"\n"+d.quantity()).sorted().toList();
            require(priorLines.equals(group.sourceGroups().stream().map(d -> d.sourceLineRef()+"\n"+d.quantity()).sorted().toList()),
                    "handover changes original role groups");
            var document=new ResourceWorkflowSegment(ResourceWorkflowSegment.SCHEMA,request,authority,old.getSegmentNo(),
                    replacement.newResourceNo(),old.getQty(),at,physical.protectionEnd()); document.validate();
            var next=new MchResourceOccupationSegment(); next.setTenantNo(scope.tenantNo()); next.setEntityNo(scope.entityNo());
            next.setSegmentNo(BizKey.next("RSG")); next.setOccupationNo(request.occupationNo());next.setLogicalDemandNo(old.getLogicalDemandNo());
            next.setPreviousSegmentNo(old.getSegmentNo());next.setCommandNo(request.actionNo());next.setResourceNo(replacement.newResourceNo());
            next.setPersonnelNo(facts.get(replacement.newResourceNo()).personnelNo()); next.setSourceGroupRef(old.getSourceGroupRef());
            next.setRoleSlot(old.getRoleSlot());next.setQty(old.getQty());next.setSourceUnitsJson(old.getSourceUnitsJson());
            next.setSourceUnitsHash(old.getSourceUnitsHash()); next.setPlanJson(JSON.writeValueAsString(document));
            next.setQualificationJson(JSON.writeValueAsString(qualified.qualifications())); next.setActualStartAt(local(at));
            next.setExpectedEndAt(local(physical.protectionEnd()));audit(next,authority.actor().actorNo(),at);segmentRows.insert(next);
            for(var desired:group.buckets()) {
                var bucket=buckets.lockBookingBucket(scope,next.getResourceNo(),local(desired.startAt()));
                if(bucket==null) {
                    bucket=new MchResourceBucket();bucket.setTenantNo(scope.tenantNo());bucket.setEntityNo(scope.entityNo());
                    bucket.setBucketNo(BizKey.next("RBK"));bucket.setResourceNo(next.getResourceNo());bucket.setStartAt(local(desired.startAt()));
                    bucket.setEndAt(local(desired.endAt()));bucket.setCapacity(facts.get(next.getResourceNo()).capacity());
                    bucket.setHeldQty(0);bucket.setConfirmedQty(0);bucket.setOccupiedQty(0);bucket.setCleaningQty(0);bucket.setCapacityRevision(0L);
                    audit(bucket,authority.actor().actorNo(),at);buckets.insert(bucket);
                }
                var counters=buckets.selectBookingCounters(scope,bucket.getBucketNo());
                require(counters.invalid()==0 && counters.held()==bucket.getHeldQty() && counters.confirmed()==bucket.getConfirmedQty(),
                        "handover bucket ownership drift");
                // The new unprojected segment is already visible in missing(); reserve its overlay exactly once.
                long used=guard.used(scope,next.getResourceNo(),bucket.getStartAt(),bucket,counters.held(),counters.confirmed(),true);
                require(used<=facts.get(next.getResourceNo()).capacity(),"handover target capacity unavailable");
                one(lifecycle.delta(scope,bucket.getBucketNo(),bucket.getVersion(),next.getQty(),0,local(at),authority.actor().actorNo()));
                var projection=new MchResourceOccupationBucket();projection.setTenantNo(scope.tenantNo());projection.setEntityNo(scope.entityNo());
                projection.setProjectionNo(BizKey.next("ROP"));projection.setOccupationNo(request.occupationNo());projection.setSegmentNo(next.getSegmentNo());
                projection.setLogicalDemandNo(next.getLogicalDemandNo());projection.setResourceNo(next.getResourceNo());projection.setBucketNo(bucket.getBucketNo());
                projection.setStartAt(bucket.getStartAt());projection.setEndAt(bucket.getEndAt());projection.setCategory("OCCUPIED");projection.setQty(next.getQty());
                audit(projection,authority.actor().actorNo(),at);projections.insert(projection);
            }
        }
        var active=lifecycle.segments(scope,request.occupationNo()).stream().filter(s -> s.getActualEndAt()==null).map(MchResourceOccupationSegment::getResourceNo).sorted().toList();
        require(active.equals(physical.groups().stream().map(ResourcePhysicalProtectionPlan.PhysicalGroup::resourceNo).sorted().toList()),
                "replacement plan must cover complete current resource set");
        for (var remaining:current.stream().filter(s -> s.getActualEndAt()==null).toList()) {
            var group=physical.groups().stream().filter(g -> g.resourceNo().equals(remaining.getResourceNo())).findFirst().orElseThrow();
            var before=ResourcePlanContract.decode(plan(remaining)).demands().stream()
                    .filter(d -> d.resourceNo().equals(remaining.getResourceNo()))
                    .map(d -> d.sourceLineRef()+"\n"+d.quantity()).sorted().toList();
            require(group.peakQty()==remaining.getQty() && before.equals(group.sourceGroups().stream()
                    .map(d -> d.sourceLineRef()+"\n"+d.quantity()).sorted().toList()),"handover changes an unchanged resource demand");
        }
    }

    private void requireCurrentCapacity(ResourceCommandScopeRow scope,List<MchResourceOccupationSegment> active,
            Map<String,ResourceFact> resources,Instant at) {
        Set<String> checked=new HashSet<>();
        for(var segment:active) {
            require(at.isBefore(instant(segment.getExpectedEndAt())),"current actual resource protection window expired");
            var start=local(at).withSecond(0).withNano(0);start=start.minusMinutes(start.getMinute()%5);
            for(;start.isBefore(segment.getExpectedEndAt());start=start.plusMinutes(5)) {
                if(!checked.add(segment.getResourceNo()+"\n"+start)) continue;
                var bucket=buckets.lockBookingBucket(scope,segment.getResourceNo(),start);
                long held=0,confirmed=0;
                if(bucket==null) {
                    bucket=new MchResourceBucket();bucket.setOccupiedQty(0);bucket.setCleaningQty(0);
                } else {
                    var counters=buckets.selectBookingCounters(scope,bucket.getBucketNo());
                    require(counters.invalid()==0&&counters.held()==bucket.getHeldQty()&&counters.confirmed()==bucket.getConfirmedQty(),
                            "current protected bucket ownership drift");
                    held=counters.held();confirmed=counters.confirmed();
                }
                long used=guard.used(scope,segment.getResourceNo(),start,bucket,held,confirmed,true);
                require(used<=resources.get(segment.getResourceNo()).capacity(),"current actual resource capacity conflict remains");
            }
        }
    }

    private static List<MchResourceOccupationSegment> selected(List<MchResourceOccupationSegment> active,Request request) {
        var selected=active.stream().filter(s -> request.replacements().stream().anyMatch(r -> r.segmentNo().equals(s.getSegmentNo()))).toList();
        require(selected.size()==request.replacements().size(),"handover original segment is no longer active"); return selected;
    }
    public static void requireActionState(Action action,String state) {
        boolean allowed=switch(action) {
            case PAUSE -> "OPEN".equals(state);
            case RESUME -> "PAUSED".equals(state);
            case HANDOVER,END -> "OPEN".equals(state)||"PAUSED".equals(state);
            case CLEAN -> "OPEN".equals(state)||"PAUSED".equals(state)||"CLEANING".equals(state);
        };
        require(allowed,"occupation state does not allow requested workflow action");
    }
    public static CleaningRule cleaningRule(MchResourceOccupationSegment segment,List<CleaningRule> rules,ResourceFact resource) {
        var selected=rules.stream().filter(r -> r.logicalDemandNo().equals(segment.getLogicalDemandNo())).toList();
        require(selected.size()==1 && selected.getFirst().expectedMinutes()>=0,"complete frozen cleaning policy required");
        var rule=selected.getFirst();
        require(resource!=null && (resource.resourceType()==Type.STAFF
                ? "NONE".equals(rule.mode()) : (resource.resourceType()==Type.ROOM
                ||resource.resourceType()==Type.DEVICE
                ||resource.resourceType()==Type.SEAT) && "MANUAL".equals(rule.mode())),
                "resource kind does not match explicit cleaning policy");
        return rule;
    }
    private static String currentPlan(List<MchResourceOccupationSegment> active,Instant at) {
        var demands=new ArrayList<ResourcePlanContract.Demand>(); var qualifications=new ArrayList<ResourcePlanContract.Qualification>();
        for(var segment:active) {
            var plan=ResourcePlanContract.decode(plan(segment));
            require(at.isBefore(instant(segment.getExpectedEndAt())),"current resource protection window expired");
            for(var demand:plan.demands()) if(demand.resourceNo().equals(segment.getResourceNo()))
                demands.add(demand);
            qualifications.addAll(plan.qualifications().stream().filter(q -> q.resourceNo().equals(segment.getResourceNo())).toList());
        }
        return ResourcePlanContract.encode(demands,qualifications);
    }
    private static String plan(MchResourceOccupationSegment segment) {
        var tree=JSON.readTree(segment.getPlanJson());
        return ResourceWorkflowSegment.SCHEMA.equals(tree.path("schema").asString())
                ? JSON.readValue(segment.getPlanJson(),ResourceWorkflowSegment.class).authority().replacementResourcePlanJson()
                : JSON.readValue(segment.getPlanJson(),ResourcePhysicalProtectionPlan.Document.class).resourcePlanJson();
    }
    private static void requireAuthority(Request request,WorkflowAuthority authority,Instant at) {
        require(authority.lookup().equals(request.authority()) && authority.actionNo().equals(request.actionNo())
                && authority.actionHash().equals(request.payloadHash()) && authority.expectedOccupationRevision()==request.expectedRevision()
                && authority.action().name().equals(request.action().name()) && authority.actor().equals(currentIdentity())
                && !at.isBefore(authority.issuedAt()) && at.isBefore(authority.validUntil()),"current workflow authority changed or expired");
    }
    private static MchResourceCommand command(Request request,WorkflowAuthority authority,MchResourceOccupation occupation,ActualOriginal startActual,Instant at) {
        var ref=request.startOriginal().reference();var row=new MchResourceCommand();
        row.setTenantNo(ref.scope().tenantNo());row.setEntityNo(ref.scope().entityNo());row.setStoreNo(ref.scope().storeNo());
        row.setCommandNo(request.actionNo());row.setOwnerNo(occupation.getOwnerNo());row.setAction(switch(request.action()) {
            case END -> "END"; case CLEAN -> "COMPLETE_CLEANING"; default -> "CHANGE";
        });
        row.setCommandKind("OCCUPATION_V1");row.setPayloadSchema("RESOURCE_OCCUPATION_"+row.getAction()+"_V1");
        row.setRequestKeyHash(request.keyHash());row.setPayloadHash(request.payloadHash());row.setFrozenPayload(JSON.writeValueAsString(new Original(request,authority,startActual)));
        row.setExpectedOwnerVersion(0L);row.setSourceRevision(ref.sourceFenceRevision());row.setStatus("ACCEPTED");row.setInitiatedBy(authority.actor().actorNo());
        row.setExecutionReferenceJson(JSON.writeValueAsString(ref));row.setExecutionReferenceHash(ref.referenceHash());
        row.setPlannedResourceCommandNo(ref.plannedResourceCommandNo());row.setPlannedResourceCommandHash(ref.plannedResourceCommandHash());
        row.setExecutionPlanHash(ref.planHash());row.setOriginalStartCommandNo(startActual.commandNo());
        row.setActorNamespace(authority.actor().subjectKind());row.setActorProvenanceHash(authority.proofHash());row.setObservationEpoch(0L);
        audit(row,authority.actor().actorNo(),at);return row;
    }
    private static ResourceCommandScopeRow scope(Request request) {
        var s=request.startOriginal().reference().scope();return new ResourceCommandScopeRow(s.tenantNo(),s.entityNo(),s.storeNo());
    }
    private static Segment segment(MchResourceOccupationSegment row) {
        return new Segment(row.getSegmentNo(),row.getLogicalDemandNo(),row.getResourceNo(),row.getPersonnelNo(),row.getQty(),
                instant(row.getActualStartAt()),instant(row.getActualEndAt()),row.getCleaningFrom()!=null,instant(row.getCleaningCompletedAt()));
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static Instant instant(LocalDateTime at) { return at==null?null:at.toInstant(ZoneOffset.UTC); }
    private static LocalDateTime local(Instant at) { return LocalDateTime.ofInstant(at,ZoneOffset.UTC); }
    private static void audit(BaseEntity row,String actor,Instant at) {
        row.setCreatedAt(local(at));row.setUpdatedAt(local(at));row.setCreatedBy(actor);row.setUpdatedBy(actor);row.setVersion(0L);row.setDeleted(0);
    }
    private static void require(boolean valid,String message) { if(!valid) throw new IllegalStateException(message); }
    private static void one(int count) { require(count==1,"occupation workflow compare-and-set conflict"); }
    private static DelegatedIdentity currentIdentity() {
        var user=SecurityUtils.requireUser();
        return new DelegatedIdentity(user.tenantNo(),user.realm().name(),user.subjectKind().name(),user.userNo());
    }
}
