package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.CourseReq;
import ai.neargo.shop.food.dto.KitchenContracts.CourseVO;
import ai.neargo.shop.food.dto.KitchenContracts.EventVO;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoute;
import ai.neargo.shop.food.dto.KitchenContracts.FrozenRoutes;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverReq;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverVO;
import ai.neargo.shop.food.dto.KitchenContracts.RemakeReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskActionReq;
import ai.neargo.shop.food.dto.KitchenContracts.TaskVO;
import ai.neargo.shop.food.dto.KitchenContracts.TicketVO;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbCourse;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbServiceEvent;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CourseMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.KitchenQuantityPolicy;
import ai.neargo.shop.food.policy.StationPublicationDocuments;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Batch locks serialize task deltas and independent station operations without remote work under locks. */
public class KitchenTaskServiceImpl implements KitchenTaskService {
    private final OrderBatchMapper batches;
    private final OrderLineMapper lines;
    private final OrderComponentMapper components;
    private final ConfigRevisionMapper revisions;
    private final KitchenTicketMapper tickets;
    private final KitchenTaskMapper tasks;
    private final ServiceEventMapper events;
    private final CourseMapper courses;
    private final CheckMapper checks;
    private final IdempotencyService idem;
    private final OutboxPublisher outbox;
    private final ObjectMapper json;
    private final TransactionTemplate write;

    public KitchenTaskServiceImpl(OrderBatchMapper batches, OrderLineMapper lines, OrderComponentMapper components,
            ConfigRevisionMapper revisions, KitchenTicketMapper tickets, KitchenTaskMapper tasks,
            ServiceEventMapper events, CourseMapper courses, CheckMapper checks, IdempotencyService idem,
            ObjectMapper json, PlatformTransactionManager manager, OutboxPublisher outbox) {
        this.batches=batches; this.lines=lines; this.components=components; this.revisions=revisions;
        this.tickets=tickets; this.tasks=tasks; this.events=events; this.courses=courses; this.checks=checks;
        this.idem=idem; this.json=json; this.outbox=outbox; write=new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public FrozenRoutes freezeRoutes(String entity, String store, List<String> skus) {
        context(entity,store); if (skus==null || skus.isEmpty() || skus.size()>1000) throw bad();
        skus.forEach(KitchenTaskServiceImpl::id);
        return DataScopeContext.executeWithoutScope(() -> {
            var found=new ArrayList<FrozenRoute>(); var seen=new HashSet<String>();
            for (var revision:tickets.stationPublications(entity)) {
                // Consider only the latest effective publication of each station, including store mismatch.
                if (!seen.add(revision.getConfigNo())) continue;
                var body=StationPublicationDocuments.validate(json,revision.getConfigNo(),
                        revision.getDefinitionSnapshot());
                if (!entity.equals(body.entityNo()) || !store.equals(body.storeNo())) continue;
                for (var route:body.routes()) if (skus.contains(route.skuNo())) {
                    found.add(new FrozenRoute(route.skuNo(),body.configNo(),route.taskRole(),route.routeVersion(),
                            revision.getRevisionNo(),revision.getContentHash()));
                }
            }
            for (String sku:skus) if (found.stream().noneMatch(r -> r.skuNo().equals(sku))) throw conflict();
            return new FrozenRoutes(found);
        });
    }

    @Override public TicketVO accepted(String entity,String store,String batchNo,String dispatchKey) {
        context(entity,store); id(batchNo); key(dispatchKey);
        return tx(() -> {
            var batch=batch(entity,store,batchNo,true);
            if (!"ACCEPTED".equals(batch.getStatus()) || batch.getAcceptedAt()==null) throw conflict();
            var existing=tickets.selectList(Wrappers.<FnbKitchenTicket>lambdaQuery()
                    .eq(FnbKitchenTicket::getEntityNo,entity).eq(FnbKitchenTicket::getBatchNo,batchNo)
                    .eq(FnbKitchenTicket::getKind,"MAKE"));
            if (!existing.isEmpty()) return ticket(entity,existing.getFirst());
            var frozen=json.readTree(batch.getFrozenRequest()).get("kitchenRoutes");
            if (frozen==null || frozen.isNull()) throw conflict();
            var routes=json.treeToValue(frozen,FrozenRoutes.class);
            validateRoutes(entity,store,routes);
            var orderLines=lines.selectList(Wrappers.<FnbOrderLine>lambdaQuery()
                    .eq(FnbOrderLine::getEntityNo,entity).eq(FnbOrderLine::getBatchNo,batchNo)
                    .orderByAsc(FnbOrderLine::getFoodLineNo));
            if (orderLines.isEmpty()) throw conflict();
            var head=new FnbKitchenTicket(); stamp(head,"SYSTEM"); head.setEntityNo(entity);
            head.setTicketNo(BizKey.next("FKIT")); head.setBatchNo(batchNo);
            head.setDispatchKey(FoodCommandFingerprint.hash("kitchen-dispatch:v1",entity,store,batchNo));
            head.setKind("MAKE"); head.setStatus("QUEUED"); tickets.insert(head);
            for (var line:orderLines) {
                var parts=components.selectList(Wrappers.<FnbOrderComponent>lambdaQuery()
                        .eq(FnbOrderComponent::getEntityNo,entity)
                        .eq(FnbOrderComponent::getFoodLineNo,line.getFoodLineNo()));
                // Persisted component quantities are the frozen executable units, never caller deltas.
                if (parts.isEmpty()) addTasks(entity,head,line,null,line.getSkuNo(),line.getQty(),routes);
                else {
                    for (var part:parts) addTasks(entity,head,line,part.getComponentNo(),part.getSkuNo(),
                            part.getQty(),routes);
                    var parentRoutes=routes.routes().stream().filter(r -> r.skuNo().equals(line.getSkuNo())
                            && !"MAKE".equals(r.taskRole())).toList();
                    if (!parentRoutes.isEmpty()) addTasks(entity,head,line,null,line.getSkuNo(),line.getQty(),
                            new FrozenRoutes(parentRoutes));
                }
            }
            refreshTicket(entity,head.getTicketNo(),"SYSTEM");
            return ticket(entity,head(entity,head.getTicketNo()));
        });
    }

    private void validateRoutes(String entity,String store,FrozenRoutes frozen) {
        if (frozen==null || frozen.routes().isEmpty() || frozen.routes().size()>5000) throw conflict();
        var seen=new HashSet<String>();
        for (var route:frozen.routes()) {
            id(route.skuNo()); id(route.stationNo()); id(route.revisionNo());
            if (!seen.add(route.skuNo()+":"+route.stationNo()+":"+route.taskRole())) throw conflict();
            var row=revisions.findRevision(entity,route.revisionNo());
            if (row==null || !"STATION".equals(row.getConfigKind())
                    || !route.stationNo().equals(row.getConfigNo())
                    || !row.getContentHash().equals(route.contentHash())) throw conflict();
            var body=StationPublicationDocuments.validate(json,row.getConfigNo(),row.getDefinitionSnapshot());
            if (!entity.equals(body.entityNo()) || !store.equals(body.storeNo())
                    || body.routes().stream().noneMatch(r -> r.skuNo().equals(route.skuNo())
                    && r.taskRole().equals(route.taskRole()) && r.routeVersion()==route.routeVersion())) throw conflict();
        }
    }

    private void addTasks(String entity,FnbKitchenTicket head,FnbOrderLine line,String component,String sku,
            BigDecimal qty,FrozenRoutes frozen) {
        KitchenQuantityPolicy.positive(qty);
        BigDecimal cancelled=tickets.cancelledMainQty(entity,line.getFoodLineNo());
        if (cancelled==null) cancelled=BigDecimal.ZERO;
        if (cancelled.signum()<0 || cancelled.compareTo(line.getQty())>0) throw conflict();
        if (cancelled.compareTo(line.getQty())==0) return;
        if (cancelled.signum()>0) qty=KitchenReturnServiceImpl.quantity(
                line.getQty().subtract(cancelled),qty,line.getQty());
        var matched=frozen.routes().stream().filter(r -> sku.equals(r.skuNo())).toList();
        if (matched.isEmpty()) throw conflict();
        String state="QUEUED";
        if (line.getCourseNo()!=null) {
            var course=course(entity,line.getCourseNo(),false);
            var batch=batch(entity,null,line.getBatchNo(),false);
            if (!course.getCheckNo().equals(batch.getCheckNo()) || "CLOSED".equals(course.getStatus())) throw conflict();
            if ("HELD".equals(course.getStatus())) state="HELD";
        }
        for (var route:matched) {
            var row=new FnbKitchenTask(); stamp(row,"SYSTEM"); row.setEntityNo(entity);
            row.setTaskNo(BizKey.next("FTSK")); row.setTicketNo(head.getTicketNo());
            row.setFoodLineNo(line.getFoodLineNo()); row.setComponentNo(component);
            row.setTaskPartKey(component==null?line.getFoodLineNo():component);
            row.setStationNo(route.stationNo()); row.setRouteVersion(route.routeVersion()); row.setTaskRole(route.taskRole());
            row.setQty(qty); row.setMakingQty(BigDecimal.ZERO); row.setReadyQty(BigDecimal.ZERO);
            row.setServedQty(BigDecimal.ZERO); row.setVoidQty(BigDecimal.ZERO); row.setStatus(state); tasks.insert(row);
        }
    }

    @Override public List<TaskVO> queue(String entity,String store,String station,String batchNo) {
        context(entity,store); if (station!=null) id(station); if (batchNo!=null) id(batchNo);
        return DataScopeContext.executeWithoutScope(() -> {
            boolean active=batchNo==null;
            var activeBatches=active?tasks.activeBatchNos(entity,store,station):List.<String>of();
            if (active && activeBatches.isEmpty()) return List.of();
            var heads=batches.selectList(Wrappers.<FnbOrderBatch>lambdaQuery().eq(FnbOrderBatch::getTenantNo,"MAIN")
                    .eq(FnbOrderBatch::getEntityNo,entity).eq(FnbOrderBatch::getStoreNo,store)
                    .eq(!active,FnbOrderBatch::getBatchNo,batchNo).in(active,FnbOrderBatch::getBatchNo,activeBatches)
                    .eq(FnbOrderBatch::getStatus,"ACCEPTED").orderByAsc(FnbOrderBatch::getAcceptedAt)
                    .orderByAsc(FnbOrderBatch::getBatchNo));
            var result=new ArrayList<TaskVO>();
            for (var batch:heads) for (var head:tickets.selectList(Wrappers.<FnbKitchenTicket>lambdaQuery()
                    .eq(FnbKitchenTicket::getTenantNo,"MAIN").eq(FnbKitchenTicket::getEntityNo,entity)
                    .eq(FnbKitchenTicket::getBatchNo,batch.getBatchNo()).ne(active,FnbKitchenTicket::getStatus,"CLOSED")
                    .orderByAsc(FnbKitchenTicket::getCreatedAt).orderByAsc(FnbKitchenTicket::getTicketNo))) {
                for (var task:tasks.selectList(Wrappers.<FnbKitchenTask>lambdaQuery()
                        .eq(FnbKitchenTask::getTenantNo,"MAIN").eq(FnbKitchenTask::getEntityNo,entity)
                        .eq(FnbKitchenTask::getTicketNo,head.getTicketNo())
                        .eq(station!=null,FnbKitchenTask::getStationNo,station)
                        .notIn(active,FnbKitchenTask::getStatus,List.of("SERVED","VOIDED"))
                        .orderByAsc(FnbKitchenTask::getCreatedAt).orderByAsc(FnbKitchenTask::getTaskNo)))
                    result.add(view(task,batch.getBatchNo()));
            }
            return List.copyOf(result);
        });
    }

    @Override public EventVO act(String entity,String store,String actor,String taskNo,TaskActionReq req,String key) {
        context(entity,store); id(actor); id(taskNo); key(key);
        if (req==null || req.action()==null || req.version()==null || req.version()<0
                || req.version()==Long.MAX_VALUE) throw bad();
        KitchenQuantityPolicy.positive(req.qty()); reason(req.action(),req.reason());
        return command(entity,store,actor,key,"TASK_ACTION",taskNo,req,EventVO.class,() -> {
            var task=lockedTask(entity,store,taskNo);
            if (req.version()==null || !req.version().equals(task.getVersion())) throw conflict();
            if (("HOLD".equals(req.action()) || "FIRE".equals(req.action()))
                    && req.qty().compareTo(task.getQty())!=0) throw bad();
            if (Set.of("START","READY").contains(req.action())) {
                stageReady(entity,task,"START".equals(req.action())?task.getMakingQty().add(req.qty()):
                        task.getReadyQty().add(req.qty()));
            }
            KitchenQuantityPolicy.apply(task,req.action(),req.qty(),loss(entity,taskNo));
            stampUpdate(task,actor); if (tasks.updateById(task)!=1) throw conflict();
            var event=append(entity,actor,taskNo,req.action(),req.qty(),req.reason(),operation(entity,store,key));
            refreshTicket(entity,task.getTicketNo(),actor);
            return event(event);
        });
    }

    @Override public TicketVO remake(String entity,String store,String actor,String taskNo,RemakeReq req,String key) {
        context(entity,store); id(actor); id(taskNo); key(key);
        if (req==null || req.version()==null || req.version()<0 || req.version()==Long.MAX_VALUE) throw bad();
        KitchenQuantityPolicy.positive(req.qty()); reason("REMAKE",req.reason());
        return command(entity,store,actor,key,"REMAKE",taskNo,req,TicketVO.class,() -> {
            var original=lockedTask(entity,store,taskNo);
            if (req.version()==null || !req.version().equals(original.getVersion())) throw conflict();
            var parent=head(entity,original.getTicketNo());
            var children=tickets.selectList(Wrappers.<FnbKitchenTicket>lambdaQuery()
                    .eq(FnbKitchenTicket::getEntityNo,entity).eq(FnbKitchenTicket::getOriginalTicketNo,parent.getTicketNo())
                    .eq(FnbKitchenTicket::getKind,"REMAKE"));
            BigDecimal prior=BigDecimal.ZERO;
            for (var child:children) for (var task:ticketTasks(entity,child.getTicketNo())) {
                if (taskNo.equals(task.getTaskPartKey())) prior=prior.add(task.getQty());
            }
            if (prior.add(req.qty()).compareTo(loss(entity,taskNo))>0) throw conflict();
            var head=new FnbKitchenTicket(); stamp(head,actor); head.setEntityNo(entity);
            head.setTicketNo(BizKey.next("FKIT")); head.setBatchNo(parent.getBatchNo()); head.setKind("REMAKE");
            head.setOriginalTicketNo(parent.getTicketNo()); head.setDispatchKey(operation(entity,store,key));
            head.setStatus("QUEUED"); tickets.insert(head);
            var task=new FnbKitchenTask(); stamp(task,actor); task.setEntityNo(entity); task.setTaskNo(BizKey.next("FTSK"));
            task.setTicketNo(head.getTicketNo()); task.setFoodLineNo(original.getFoodLineNo());
            task.setComponentNo(original.getComponentNo()); task.setStationNo(original.getStationNo());
            task.setRouteVersion(original.getRouteVersion()); task.setTaskRole(original.getTaskRole());
            task.setTaskPartKey(taskNo); task.setQty(req.qty()); task.setMakingQty(BigDecimal.ZERO);
            task.setReadyQty(BigDecimal.ZERO); task.setServedQty(BigDecimal.ZERO); task.setVoidQty(BigDecimal.ZERO);
            task.setStatus("QUEUED"); tasks.insert(task);
            append(entity,actor,taskNo,"REMAKE",req.qty(),req.reason(),operation(entity,store,key));
            return ticket(entity,head);
        });
    }

    @Override public List<EventVO> events(String entity,String store,String taskNo) {
        context(entity,store); id(taskNo);
        return DataScopeContext.executeWithoutScope(() -> {
            scopedTask(entity,store,taskNo);
            return events.selectList(Wrappers.<FnbServiceEvent>lambdaQuery().eq(FnbServiceEvent::getEntityNo,entity)
                    .eq(FnbServiceEvent::getTaskNo,taskNo).orderByAsc(FnbServiceEvent::getId))
                    .stream().map(KitchenTaskServiceImpl::event).toList();
        });
    }

    @Override public CourseVO createCourse(String entity,String store,String actor,String checkNo,CourseReq req,
            String key) {
        context(entity,store); id(actor); id(checkNo); key(key);
        if (req==null || req.name()==null || req.name().isBlank() || req.name().length()>64
                || req.sequenceNo()==null || req.sequenceNo()<1 || req.sequenceNo()>1000) throw bad();
        return command(entity,store,actor,key,"CREATE_COURSE",checkNo,req,CourseVO.class,() -> {
            check(entity,store,checkNo,true);
            var row=new FnbCourse(); stamp(row,actor); row.setEntityNo(entity);
            row.setCourseNo(BizKey.next("FCRS")); row.setCheckNo(checkNo); row.setName(req.name());
            row.setSequenceNo(req.sequenceNo()); row.setStatus("HELD"); courses.insert(row); return courseView(row);
        });
    }
    @Override public List<CourseVO> courses(String entity,String store,String checkNo) {
        context(entity,store); id(checkNo);
        return DataScopeContext.executeWithoutScope(() -> {
            check(entity,store,checkNo,false);
            return courses.selectList(Wrappers.<FnbCourse>lambdaQuery().eq(FnbCourse::getEntityNo,entity)
                    .eq(FnbCourse::getCheckNo,checkNo).orderByAsc(FnbCourse::getSequenceNo))
                    .stream().map(KitchenTaskServiceImpl::courseView).toList();
        });
    }
    @Override public CourseVO actCourse(String entity,String store,String actor,String courseNo,CourseActionReq req,
            String key) {
        context(entity,store); id(actor); id(courseNo); key(key);
        if (req==null || req.action()==null || req.version()==null || req.version()<0
                || req.version()==Long.MAX_VALUE || !Set.of("HOLD","FIRE","CLOSE").contains(req.action())) throw bad();
        reason(req.action(),req.reason());
        if (req.seatNos()!=null) {
            if (req.seatNos().size()>100 || new HashSet<>(req.seatNos()).size()!=req.seatNos().size()) throw bad();
            req.seatNos().forEach(KitchenTaskServiceImpl::id);
            if (!req.seatNos().isEmpty() && "CLOSE".equals(req.action())) throw bad();
        }
        return command(entity,store,actor,key,"COURSE_ACTION",courseNo,req,CourseVO.class,() -> {
            var current=course(entity,courseNo,false); check(entity,store,current.getCheckNo(),true);
            // Lock batches in stable order, the same lock hierarchy as task operations; lock course last.
            var orderBatches=batches.selectList(Wrappers.<FnbOrderBatch>lambdaQuery()
                    .eq(FnbOrderBatch::getEntityNo,entity).eq(FnbOrderBatch::getStoreNo,store)
                    .eq(FnbOrderBatch::getCheckNo,current.getCheckNo()).orderByAsc(FnbOrderBatch::getBatchNo)
                    .last("FOR UPDATE"));
            current=course(entity,courseNo,true);
            if (req.version()==null || !req.version().equals(current.getVersion())) throw conflict();
            if ("CLOSED".equals(current.getStatus())) throw conflict();
            for (var batch:orderBatches) for (var ticket:tickets.selectList(
                    Wrappers.<FnbKitchenTicket>lambdaQuery().eq(FnbKitchenTicket::getEntityNo,entity)
                    .eq(FnbKitchenTicket::getBatchNo,batch.getBatchNo()))) {
                for (var task:ticketTasks(entity,ticket.getTicketNo())) {
                    var line=line(entity,task.getFoodLineNo()); if (!courseNo.equals(line.getCourseNo())) continue;
                    if (req.seatNos()!=null && !req.seatNos().isEmpty()
                            && !req.seatNos().contains(line.getSeatNo())) continue;
                    if ("CLOSE".equals(req.action())) {
                        if (!Set.of("SERVED","VOIDED").contains(task.getStatus())) throw conflict();
                    } else {
                        String desired="FIRE".equals(req.action())?"QUEUED":"HELD";
                        if (desired.equals(task.getStatus())) continue;
                        KitchenQuantityPolicy.apply(task,req.action(),task.getQty(),loss(entity,task.getTaskNo()));
                        stampUpdate(task,actor); if (tasks.updateById(task)!=1) throw conflict();
                        append(entity,actor,task.getTaskNo(),req.action(),task.getQty(),req.reason(),
                                FoodCommandFingerprint.hash("course-task:v1",entity,store,key,task.getTaskNo()));
                    }
                }
                refreshTicket(entity,ticket.getTicketNo(),actor);
            }
            current.setStatus(switch(req.action()) { case "HOLD" -> "HELD"; case "FIRE" ->
                req.seatNos()==null || req.seatNos().isEmpty()?"FIRED":current.getStatus();
                default -> "CLOSED"; });
            stampUpdate(current,actor); if (courses.updateById(current)!=1) throw conflict(); return courseView(current);
        });
    }

    @Override public HandoverVO handover(String entity,String store,String actor,String batchNo,HandoverReq req,
            String key) {
        context(entity,store); id(actor); id(batchNo); key(key);
        if (req==null || req.kind()==null || !Set.of("DINE_IN","PICKUP","DELIVERY").contains(req.kind())
                || req.lines()==null || req.lines().isEmpty() || req.lines().size()>1000) throw bad();
        reason("HOLD",req.reason());
        return command(entity,store,actor,key,"HANDOVER",batchNo,req,HandoverVO.class,() -> {
            var batch=batch(entity,store,batchNo,true);
            if (!"ACCEPTED".equals(batch.getStatus()) || !req.kind().equals(batch.getFulfillment())) throw conflict();
            var original=tickets.selectOne(Wrappers.<FnbKitchenTicket>lambdaQuery()
                    .eq(FnbKitchenTicket::getEntityNo,entity).eq(FnbKitchenTicket::getBatchNo,batchNo)
                    .eq(FnbKitchenTicket::getKind,"MAKE"));
            if (original==null) throw conflict();
            var originalTasks=ticketTasks(entity,original.getTicketNo());
            var requested=new HashMap<String,BigDecimal>();
            for (var line:req.lines()) {
                if (line==null) throw bad();
                id(line.foodLineNo()); KitchenQuantityPolicy.positive(line.qty());
                if (requested.put(line.foodLineNo(),line.qty())!=null) throw bad();
            }
            var orderLines=lines.selectList(Wrappers.<FnbOrderLine>lambdaQuery()
                    .eq(FnbOrderLine::getEntityNo,entity).eq(FnbOrderLine::getBatchNo,batchNo));
            int deliverable=0;
            for (var line:orderLines) {
                BigDecimal cancelled=tickets.cancelledMainQty(entity,line.getFoodLineNo());
                if (cancelled==null) cancelled=BigDecimal.ZERO;
                BigDecimal demand=line.getQty().subtract(cancelled);
                if (demand.signum()==0) continue;
                if (demand.signum()<0) throw conflict();
                deliverable++;
                // Exact remaining kitchen demand is derived from handled cancellation facts, never financial progress.
                if (!requested.containsKey(line.getFoodLineNo())
                        || requested.get(line.getFoodLineNo()).compareTo(demand)!=0) throw conflict();
                var candidates=tasks.selectList(Wrappers.<FnbKitchenTask>lambdaQuery()
                        .eq(FnbKitchenTask::getEntityNo,entity).eq(FnbKitchenTask::getFoodLineNo,line.getFoodLineNo()));
                var lineTasks=originalTasks.stream().filter(t -> line.getFoodLineNo().equals(t.getFoodLineNo())).toList();
                if (lineTasks.isEmpty() || !"DINE_IN".equals(req.kind())
                        && lineTasks.stream().noneMatch(t -> "PACK".equals(t.getTaskRole()))) throw conflict();
                for (var task:lineTasks) {
                    if (effectiveReady(entity,task,candidates)
                            .compareTo(task.getQty().subtract(task.getVoidQty()))<0) throw conflict();
                    if (!events.selectList(Wrappers.<FnbServiceEvent>lambdaQuery()
                            .eq(FnbServiceEvent::getEntityNo,entity).eq(FnbServiceEvent::getTaskNo,task.getTaskNo())
                            .eq(FnbServiceEvent::getAction,"HANDOVER")).isEmpty()) throw conflict();
                }
            }
            if (deliverable!=requested.size() || deliverable==0) throw conflict();
            var handoverTickets=tickets.selectList(Wrappers.<FnbKitchenTicket>lambdaQuery()
                    .eq(FnbKitchenTicket::getEntityNo,entity).eq(FnbKitchenTicket::getBatchNo,batchNo));
            for (var localTicket:handoverTickets) {
                for (var task:ticketTasks(entity,localTicket.getTicketNo())) {
                    if (!requested.containsKey(task.getFoodLineNo())) continue;
                    BigDecimal recordedLoss=loss(entity,task.getTaskNo());
                    BigDecimal handing=task.getReadyQty().subtract(recordedLoss).subtract(task.getServedQty());
                    if (handing.signum()>0) {
                        KitchenQuantityPolicy.apply(task,"SERVE",handing,recordedLoss);
                        stampUpdate(task,actor); if (tasks.updateById(task)!=1) throw conflict();
                        append(entity,actor,task.getTaskNo(),"SERVE",handing,req.reason(),
                                FoodCommandFingerprint.hash("handover-serve:v1",entity,store,batchNo,task.getTaskNo()));
                    }
                }
                refreshTicket(entity,localTicket.getTicketNo(),actor);
            }
            String receipt=null;
            for (var task:originalTasks) {
                BigDecimal handing=task.getQty().subtract(task.getVoidQty()); if (handing.signum()==0) continue;
                var event=append(entity,actor,task.getTaskNo(),"HANDOVER",handing,req.reason(),
                        FoodCommandFingerprint.hash("handover-task:v1",entity,store,batchNo,task.getTaskNo()));
                if (receipt==null) receipt=event.getEventNo();
            }
            // This is local service evidence. No suitable original industry delivery receipt contract exists.
            return new HandoverVO(receipt,batchNo,"LOCAL_HANDOVER_RECORDED",true,null);
        });
    }

    private void stageReady(String entity,FnbKitchenTask current,BigDecimal target) {
        int rank=rank(current.getTaskRole()); if (rank==0) return;
        var candidates=tasks.selectList(Wrappers.<FnbKitchenTask>lambdaQuery()
                .eq(FnbKitchenTask::getEntityNo,entity).eq(FnbKitchenTask::getFoodLineNo,current.getFoodLineNo()));
        for (var upstream:candidates) {
            if (!"MAKE".equals(head(entity,upstream.getTicketNo()).getKind())
                    || rank(upstream.getTaskRole())>=rank) continue;
            BigDecimal ready=effectiveReady(entity,upstream,candidates);
            BigDecimal required=upstream.getQty();
            if (ready.multiply(current.getQty()).compareTo(target.multiply(required))<0) throw conflict();
        }
    }
    private BigDecimal effectiveReady(String entity,FnbKitchenTask original,List<FnbKitchenTask> candidates) {
        BigDecimal ready=original.getReadyQty().subtract(loss(entity,original.getTaskNo()));
        for (var remake:candidates) if (original.getTaskNo().equals(remake.getTaskPartKey())
                && "REMAKE".equals(head(entity,remake.getTicketNo()).getKind())) {
            ready=ready.add(effectiveReady(entity,remake,candidates));
        }
        return ready;
    }
    private static int rank(String role) {
        return switch(role) { case "MAKE" -> 0; case "ASSEMBLE" -> 1; case "PACK" -> 2;
            default -> throw conflict(); };
    }

    private <T> T command(String entity,String store,String actor,String key,String kind,String target,Object req,
            Class<T> type,Supplier<T> body) {
        String scope=FoodCommandFingerprint.scope(kind,entity,store);
        String hash=FoodCommandFingerprint.hash("kitchen-command:v1",target,json.writeValueAsString(req));
        return tx(() -> idem.execute(key,scope,actor,hash,type,body));
    }
    private <T> T tx(Supplier<T> body) {
        try { return DataScopeContext.executeWithoutScope(() -> write.execute(status -> body.get())); }
        catch (ConcurrencyFailureException | DuplicateKeyException failure) { throw conflict(); }
    }
    private FnbKitchenTask scopedTask(String entity,String store,String number) {
        var task=tasks.selectOne(Wrappers.<FnbKitchenTask>lambdaQuery().eq(FnbKitchenTask::getEntityNo,entity)
                .eq(FnbKitchenTask::getTaskNo,number));
        if (task==null) throw missing(); batch(entity,store,head(entity,task.getTicketNo()).getBatchNo(),false);
        return task;
    }
    private FnbKitchenTask lockedTask(String entity,String store,String number) {
        var original=scopedTask(entity,store,number);
        batch(entity,store,head(entity,original.getTicketNo()).getBatchNo(),true);
        var row=tasks.selectOne(Wrappers.<FnbKitchenTask>lambdaQuery().eq(FnbKitchenTask::getEntityNo,entity)
                .eq(FnbKitchenTask::getTaskNo,number).last("FOR UPDATE"));
        if (row==null) throw missing();
        var lineTasks=tasks.selectList(Wrappers.<FnbKitchenTask>lambdaQuery().eq(FnbKitchenTask::getEntityNo,entity)
                .eq(FnbKitchenTask::getFoodLineNo,row.getFoodLineNo()));
        for (var related:lineTasks) if (!events.selectList(Wrappers.<FnbServiceEvent>lambdaQuery()
                .eq(FnbServiceEvent::getEntityNo,entity).eq(FnbServiceEvent::getTaskNo,related.getTaskNo())
                .eq(FnbServiceEvent::getAction,"HANDOVER")).isEmpty()) throw conflict();
        return row;
    }
    private FnbOrderBatch batch(String entity,String store,String number,boolean lock) {
        var row=batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery().eq(FnbOrderBatch::getEntityNo,entity)
                .eq(store!=null,FnbOrderBatch::getStoreNo,store).eq(FnbOrderBatch::getBatchNo,number)
                .last(lock?"FOR UPDATE":""));
        if (row==null) throw missing(); return row;
    }
    private FnbKitchenTicket head(String entity,String number) {
        var row=tickets.selectOne(Wrappers.<FnbKitchenTicket>lambdaQuery().eq(FnbKitchenTicket::getEntityNo,entity)
                .eq(FnbKitchenTicket::getTicketNo,number)); if (row==null) throw missing(); return row;
    }
    private FnbOrderLine line(String entity,String number) {
        var row=lines.selectOne(Wrappers.<FnbOrderLine>lambdaQuery().eq(FnbOrderLine::getEntityNo,entity)
                .eq(FnbOrderLine::getFoodLineNo,number)); if (row==null) throw missing(); return row;
    }
    private FnbCheck check(String entity,String store,String number,boolean lock) {
        var row=checks.selectOne(Wrappers.<FnbCheck>lambdaQuery().eq(FnbCheck::getEntityNo,entity)
                .eq(FnbCheck::getStoreNo,store).eq(FnbCheck::getCheckNo,number).last(lock?"FOR UPDATE":""));
        if (row==null) throw missing(); if (!"OPEN".equals(row.getStatus())) throw conflict(); return row;
    }
    private FnbCourse course(String entity,String number,boolean lock) {
        var row=courses.selectOne(Wrappers.<FnbCourse>lambdaQuery().eq(FnbCourse::getEntityNo,entity)
                .eq(FnbCourse::getCourseNo,number).last(lock?"FOR UPDATE":"")); if (row==null) throw missing(); return row;
    }
    private List<FnbKitchenTask> ticketTasks(String entity,String ticket) {
        return tasks.selectList(Wrappers.<FnbKitchenTask>lambdaQuery().eq(FnbKitchenTask::getEntityNo,entity)
                .eq(FnbKitchenTask::getTicketNo,ticket).orderByAsc(FnbKitchenTask::getTaskNo));
    }
    private BigDecimal loss(String entity,String task) {
        return events.selectList(Wrappers.<FnbServiceEvent>lambdaQuery().eq(FnbServiceEvent::getEntityNo,entity)
                .eq(FnbServiceEvent::getTaskNo,task).eq(FnbServiceEvent::getAction,"LOSS"))
                .stream().map(FnbServiceEvent::getQty).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private FnbServiceEvent append(String entity,String actor,String task,String action,BigDecimal qty,String reason,
            String operation) {
        var event=new FnbServiceEvent(); stamp(event,actor); event.setEntityNo(entity);
        event.setEventNo(BizKey.next("FSEV")); event.setTaskNo(task); event.setAction(action); event.setQty(qty);
        event.setOperationKey(operation); event.setReason(reason); events.insert(event);
        var row=tasks.selectOne(Wrappers.<FnbKitchenTask>lambdaQuery().eq(FnbKitchenTask::getEntityNo,entity)
                .eq(FnbKitchenTask::getTaskNo,task));
        if (row==null) throw conflict();
        var head=head(entity,row.getTicketNo()); var batch=batch(entity,null,head.getBatchNo(),false);
        outbox.publish(new DiningServiceEvent(event.getEventNo(),entity,batch.getStoreNo(),batch.getBatchNo(),
                head.getTicketNo(),task,row.getFoodLineNo(),row.getComponentNo(),row.getStationNo(),row.getTaskRole(),
                row.getRouteVersion(),action,qty,reason,actor,Instant.now()),
                new PublishOptions("FOOD_KITCHEN_TASK:"+task,1));
        return event;
    }
    private void refreshTicket(String entity,String number,String actor) {
        var rows=ticketTasks(entity,number); var ticket=head(entity,number);
        boolean closed=rows.stream().allMatch(t -> Set.of("SERVED","VOIDED").contains(t.getStatus()));
        ticket.setStatus(closed?"CLOSED":rows.stream().anyMatch(t -> t.getMakingQty().signum()>0)?"ACTIVE":"QUEUED");
        stampUpdate(ticket,actor); if (tickets.updateById(ticket)!=1) throw conflict();
    }
    private TicketVO ticket(String entity,FnbKitchenTicket row) {
        return new TicketVO(row.getTicketNo(),row.getBatchNo(),row.getKind(),row.getOriginalTicketNo(),row.getStatus(),
                ticketTasks(entity,row.getTicketNo()).stream().map(t -> view(t,row.getBatchNo())).toList());
    }
    private TaskVO view(FnbKitchenTask t,String batch) {
        var line=line(t.getEntityNo(),t.getFoodLineNo()); var head=head(t.getEntityNo(),t.getTicketNo());
        return new TaskVO(t.getTaskNo(),t.getTicketNo(),batch,t.getFoodLineNo(),t.getComponentNo(),t.getStationNo(),
                t.getTaskRole(),t.getRouteVersion(),t.getQty(),t.getMakingQty(),t.getReadyQty(),t.getServedQty(),
                t.getVoidQty(),t.getStatus(),line.getSeatNo(),line.getCourseNo(),t.getVersion(),
                head.getKind(),head.getOriginalTicketNo());
    }
    private static EventVO event(FnbServiceEvent e) {
        return new EventVO(e.getEventNo(),e.getTaskNo(),e.getAction(),e.getQty(),e.getReason(),e.getInventoryDocNo());
    }
    private static CourseVO courseView(FnbCourse c) {
        return new CourseVO(c.getCourseNo(),c.getCheckNo(),c.getName(),c.getSequenceNo(),c.getStatus(),c.getVersion());
    }
    private static String operation(String entity,String store,String key) {
        return FoodCommandFingerprint.hash("kitchen-operation:v1",entity,store,key);
    }
    private static void stamp(BaseEntity row,String actor) {
        row.setTenantNo("MAIN"); row.setVersion(0L); row.setDeleted(0); row.setCreatedBy(actor);
        row.setCreatedAt(LocalDateTime.now()); stampUpdate(row,actor);
    }
    private static void stampUpdate(BaseEntity row,String actor) {
        row.setUpdatedBy(actor); row.setUpdatedAt(LocalDateTime.now());
    }
    private static void reason(String action,String value) {
        if (value!=null && value.length()>255) throw bad();
        if (Set.of("HOLD","VOID","LOSS","REMAKE").contains(action)
                && (value==null || value.isBlank())) throw bad();
    }
    private static void context(String entity,String store) { id(entity); id(store); }
    private static void id(String value) { if (value==null || !value.matches("[A-Za-z0-9_-]{1,32}")) throw bad(); }
    private static void key(String value) { if (value==null || value.isBlank() || value.length()>128) throw bad(); }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private static BizException missing() { return BizException.of(ErrorCode.NOT_FOUND); }
}
