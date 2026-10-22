package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.ActionLogVO;
import ai.neargo.shop.food.dto.CheckDetailVO;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.ClearCheckReq;
import ai.neargo.shop.food.dto.CombineTablesReq;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.ListCheckActionsQuery;
import ai.neargo.shop.food.dto.ListChecksQuery;
import ai.neargo.shop.food.dto.OccupancyVO;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.OpenSharedCheckReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCheckSeatsReq;
import ai.neargo.shop.food.dto.SeatReq;
import ai.neargo.shop.food.dto.TableVersionReq;
import ai.neargo.shop.food.dto.TransferCheckReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbGuestSession;
import ai.neargo.shop.food.entity.FnbSeat;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableOccupancy;
import ai.neargo.shop.food.entity.FnbTableOperation;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SeatMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOperationMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.ServiceCalendarPolicy;
import ai.neargo.shop.food.policy.TableOccupancyPolicy;
import ai.neargo.shop.food.policy.TableResourceLocks;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.CheckChanged;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Atomic local reception; funded/unknown checks remain closed to edits until their authority contracts exist. */
@Service
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class CheckServiceImpl implements CheckService {
    private final CheckMapper checks;
    private final TableMapper tables;
    private final TableOccupancyMapper occupancies;
    private final SeatMapper seats;
    private final TableOperationMapper operations;
    private final GuestSessionMapper sessions;
    private final ActionLogMapper actions;
    private final StoreOperationService policies;
    private final IdempotencyService idem;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final TableResourceLocks locks;

    public CheckServiceImpl(CheckMapper checks,TableAreaMapper areas,TableMapper tables,TableOccupancyMapper occupancies,
            SeatMapper seats,TableOperationMapper operations,GuestSessionMapper sessions,ActionLogMapper actions,
            StoreOperationService policies,IdempotencyService idem,@Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            ObjectMapper json,@Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.checks=checks;this.tables=tables;this.occupancies=occupancies;this.seats=seats;this.operations=operations;
        this.sessions=sessions;this.actions=actions;this.policies=policies;this.idem=idem;this.publisher=publisher;this.json=json;
        this.locks=new TableResourceLocks(areas,tables);this.tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public CheckResultVO openCheck(String entity,String store,String actor,OpenCheckReq req,String key) {
        return open(entity,store,actor,req,key,"OPEN_CHECK");
    }
    private CheckResultVO open(String entity,String store,String actor,OpenCheckReq req,String key,String action) {
        if(req==null || req.partySize()==null || req.partySize()<1) bad();
        prepay(req.payMode());unverified(req.reservationNo());unverified(req.serviceEmployeeNo());
        if(!Set.of("EXCLUSIVE","SHARED").contains(req.sharingMode()==null ? "":req.sharingMode())) bad();
        var targets=numbers(req.tableNos(),100);var versions=versions(req.tableVersions(),100);
        if(!versions.keySet().equals(new HashSet<>(targets))) bad();
        var desired=seatInputs(req.seats(),req.partySize());
        if(desired.stream().anyMatch(s -> s.seatNo()!=null)) bad();
        String hash=FoodCommandFingerprint.hash(json.writeValueAsString(targets),req.partySize().toString(),
                req.payMode(),req.sharingMode(),json.writeValueAsString(versions),json.writeValueAsString(desired));
        return write(action,entity,store,actor,key,hash,CheckResultVO.class,() -> {
            var locked=locks.lock(entity,store,targets,versions,true);
            var allocation=allocation(entity,locked,req.partySize(),req.sharingMode(),null);
            var at=Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var policy=policies.getPolicy(entity,store,new GetPolicyQuery(at));
            var check=new FnbCheck();check.setEntityNo(entity);check.setStoreNo(store);check.setCheckNo(BizKey.next("FCHK"));
            check.setBusinessDate(ServiceCalendarPolicy.businessDate(at,policy));check.setPolicyNo(policy.policyNo());
            check.setPartySize(req.partySize());check.setPayMode(req.payMode());check.setStatus("OPEN");
            check.setOpenedAt(local(at));check.setServiceState("OPEN");initialize(check,actor);checks.insert(check);
            occupy(check,allocation,req.sharingMode(),actor);saveSeatRows(check,desired,actor);touch(entity,locked,actor);
            String operation=BizKey.next("FOPR");
            if(action.equals("SHARE_TABLE")) operation(check,operation,action,key,hash,Map.of("tableVersions",versions),actor,"独立拼桌");
            changed(check,actor,action,null,null,key,operation);
            return new CheckResultVO(view(check));
        });
    }
    @Override public CheckResultVO openSharedCheck(String entity,String store,String table,String actor,OpenSharedCheckReq req,String key) {
        id(table);if(req==null) bad();version(req.version());
        return open(entity,store,actor,new OpenCheckReq(List.of(table),req.partySize(),req.payMode(),null,
                List.of(new TableVersionReq(table,req.version())),"SHARED",List.of(),null),key,"SHARE_TABLE");
    }
    @Override @Transactional(transactionManager="transactionManager",readOnly=true)
    public PageData<CheckVO> listChecks(String entity,String store,ListChecksQuery query) {
        scope(entity,store);int page=page(query==null?null:query.page());int size=size(query==null?null:query.size());
        String state=query==null?null:query.status();
        if(state!=null && !Set.of("OPEN","SETTLING","CLOSED","MERGED","VOIDED").contains(state)) bad();
        var filter=Wrappers.<FnbCheck>lambdaQuery().eq(FnbCheck::getEntityNo,entity).eq(FnbCheck::getStoreNo,store)
                .eq(state!=null,FnbCheck::getStatus,state).eq(query!=null && query.businessDate()!=null,FnbCheck::getBusinessDate,
                        query==null?null:query.businessDate()).orderByDesc(FnbCheck::getOpenedAt).orderByDesc(FnbCheck::getCheckNo);
        var result=checks.selectPage(new Page<FnbCheck>(page,size),filter);
        return PageData.of(result.getRecords().stream().map(this::view).toList(),result.getTotal(),page,size);
    }
    @Override @Transactional(transactionManager="transactionManager",readOnly=true)
    public CheckDetailVO getCheck(String entity,String store,String number) {
        scope(entity,store);id(number);var check=find(entity,store,number,false);
        var slots=seatViews(check);
        var active=active(check);
        return new CheckDetailVO(view(check,active),slots,active.stream().map(row -> new OccupancyVO(row.getOccupancyNo(),
                row.getTableNo(),row.getCheckNo(),row.getSeats(),row.getStartedAt().toInstant(ZoneOffset.UTC))).toList());
    }
    @Override public CheckResultVO saveSeats(String entity,String store,String number,String actor,SaveCheckSeatsReq req,String key) {
        id(number);if(req==null || req.partySize()==null || req.partySize()<1) bad();version(req.version());
        String reason=text(req.reason(),255);var desired=seatInputs(req.seats(),req.partySize());
        String hash=FoodCommandFingerprint.hash(number,req.version().toString(),req.partySize().toString(),reason,json.writeValueAsString(desired));
        return write("SAVE_SEATS",entity,store,actor,key,hash,CheckResultVO.class,() -> {
            var check=locked(entity,store,number,req.version());noTrade(check);var before=view(check);var active=active(check);
            var beforeSeats=seatViews(check);
            var locked=locks.lock(entity,store,tableNos(active),null,true);localResources(active,locked);String mode=mode(active);
            var allocation=allocation(entity,locked,req.partySize(),mode,number);
            for(var row:active) {
                row.setSeats(allocation.get(row.getTableNo()));
                if(occupancies.updateById(row)!=1) throw BizException.of(ErrorCode.CONFLICT);
            }
            saveSeatRows(check,desired,actor);check.setPartySize(req.partySize());update(check,actor);touch(entity,locked,actor);
            changed(check,actor,"SAVE_SEATS",reason,before,key,BizKey.next("FOPR"),beforeSeats);return new CheckResultVO(view(check));
        });
    }
    @Override public OperationVO clearCheck(String entity,String store,String number,String actor,ClearCheckReq req,String key) {
        id(number);if(req==null) bad();version(req.version());unverified(req.approvalRef());
        String reason=text(req.reason(),255);var requested=numbers(req.occupancyNos(),100);
        String hash=FoodCommandFingerprint.hash(number,req.version().toString(),reason,json.writeValueAsString(requested));
        return write("CLEAR",entity,store,actor,key,hash,OperationVO.class,() -> {
            var check=locked(entity,store,number,req.version());noTrade(check);var before=view(check);var active=active(check);
            var chosen=active.stream().filter(o -> requested.contains(o.getOccupancyNo())).toList();
            if(chosen.size()!=requested.size()) throw BizException.of(ErrorCode.NOT_FOUND);
            if(chosen.isEmpty() && !active.isEmpty()) bad();
            var locked=locks.lock(entity,store,tableNos(active),null,false);localResources(active,locked);
            var remaining=active.stream().filter(row -> !requested.contains(row.getOccupancyNo())).toList();
            if(!remaining.isEmpty()) {
                var remainingTables=locked.stream().filter(table -> tableNos(remaining).contains(table.getTableNo())).toList();
                var allocation=allocation(entity,remainingTables,check.getPartySize(),mode(active),number);
                for(var row:remaining) {
                    row.setSeats(allocation.get(row.getTableNo()));
                    if(row.getVersion()==Long.MAX_VALUE || occupancies.updateById(row)!=1) throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            endOccupancies(chosen,actor);
            if(chosen.size()==active.size()) {
                check.setServiceState("RELEASED");check.setStatus("VOIDED");check.setClosedAt(now());
                revokeCheckSessions(check,actor);
            }
            update(check,actor);touch(entity,locked,actor);
            String operation=BizKey.next("FOPR");operation(check,operation,"CLEAR",key,hash,
                    Map.of("checkVersion",req.version(),"occupancyNos",requested),actor,reason);
            changed(check,actor,"CLEAR",reason,before,key,operation);
            return new OperationVO(operation,"SUCCEEDED",check.getVersion(),number);
        });
    }
    @Override public OperationResultVO transferCheck(String entity,String store,String number,String actor,TransferCheckReq req,String key) {
        id(number);if(req==null) bad();version(req.version());unverified(req.approvalRef());
        var targets=numbers(req.targetTableNos(),100);if(targets.isEmpty()) bad();
        var versions=versions(req.tableVersions(),200);String reason=text(req.reason(),255);
        return move(entity,store,number,actor,req.version(),targets,versions,reason,key,"TRANSFER");
    }
    @Override public OperationResultVO combineTables(String entity,String store,String number,String actor,CombineTablesReq req,String key) {
        id(number);if(req==null) bad();version(req.version());String reason=text(req.reason(),255);
        var versions=versions(req.tableVersions(),100);if(versions.isEmpty()) bad();
        return move(entity,store,number,actor,req.version(),List.copyOf(versions.keySet()),versions,reason,key,"COMBINE_TABLES");
    }
    private OperationResultVO move(String entity,String store,String number,String actor,long version,List<String> targets,
            Map<String,Long> versions,String reason,String key,String action) {
        String hash=FoodCommandFingerprint.hash(number,Long.toString(version),reason,json.writeValueAsString(targets),
                json.writeValueAsString(versions));
        return write(action,entity,store,actor,key,hash,OperationResultVO.class,() -> {
            var check=locked(entity,store,number,version);noTrade(check);var before=view(check);var active=active(check);
            var old=tableNos(active);var all=Stream.concat(old.stream(),targets.stream()).distinct().sorted().toList();
            boolean combine=action.equals("COMBINE_TABLES");
            if(combine && targets.stream().anyMatch(old::contains)) bad();
            if(!combine && !versions.keySet().equals(new HashSet<>(all))) bad();
            var locked=locks.lock(entity,store,all,combine?null:versions,true);localResources(active,locked);
            if(combine) for(var row:locked) if(versions.containsKey(row.getTableNo())
                    && !row.getVersion().equals(versions.get(row.getTableNo()))) throw BizException.of(ErrorCode.CONFLICT);
            var destination=combine ? all:targets;
            var targetRows=locked.stream().filter(t -> destination.contains(t.getTableNo())).toList();
            String mode=mode(active);var allocation=allocation(entity,targetRows,check.getPartySize(),mode,number);
            // Claim every target first; releasing the old records is in this same primary transaction.
            occupy(check,allocation,mode,actor);endOccupancies(active,actor);update(check,actor);touch(entity,locked,actor);
            String operation=BizKey.next("FOPR");operation(check,operation,action,key,hash,
                    Map.of("checkVersion",version,"tableVersions",versions),actor,reason);
            changed(check,actor,action,reason,before,key,operation);
            return new OperationResultVO(new OperationVO(operation,"SUCCEEDED",check.getVersion(),number));
        });
    }
    @Override @Transactional(transactionManager="transactionManager",readOnly=true)
    public OperationResultVO getTableOperation(String entity,String store,String operation) {
        scope(entity,store);id(operation);
        var rows=operations.selectList(Wrappers.<FnbTableOperation>lambdaQuery().eq(FnbTableOperation::getEntityNo,entity)
                .eq(FnbTableOperation::getStoreNo,store).eq(FnbTableOperation::getOperationNo,operation).last("LIMIT 1"));
        if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        var row=rows.getFirst();String state=row.getStatus();
        if(state==null || !Set.of("PREPARED","CLAIMING","APPLIED","RECOVERING","FAILED").contains(state))
            throw BizException.of(ErrorCode.CONFLICT);
        long resultVersion=row.getVersion();
        if("APPLIED".equals(state)) {
            var snapshot=json.readTree(row.getTargetScope());var value=snapshot==null?null:snapshot.get("resultVersion");
            if(value==null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<0)
                throw BizException.of(ErrorCode.CONFLICT);
            resultVersion=value.asLong();
        }
        return new OperationResultVO(new OperationVO(operation,"APPLIED".equals(state)?"SUCCEEDED":state,
                resultVersion,row.getCheckNo()));
    }
    @Override @Transactional(transactionManager="transactionManager",readOnly=true)
    public PageData<ActionLogVO> listActions(String entity,String store,String number,ListCheckActionsQuery query) {
        scope(entity,store);id(number);find(entity,store,number,false);
        int page=page(query==null?null:query.page());int size=size(query==null?null:query.size());
        var result=actions.selectPage(new Page<FnbActionLog>(page,size),Wrappers.<FnbActionLog>lambdaQuery()
                .eq(FnbActionLog::getEntityNo,entity).eq(FnbActionLog::getStoreNo,store).eq(FnbActionLog::getObjectType,"CHECK")
                .eq(FnbActionLog::getObjectNo,number).orderByDesc(FnbActionLog::getCreatedAt).orderByDesc(FnbActionLog::getActionNo));
        return PageData.of(result.getRecords().stream().map(a -> new ActionLogVO(a.getActionNo(),a.getAction(),a.getOperatorNo(),
                a.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),a.getReason())).toList(),result.getTotal(),page,size);
    }
    private <T> T write(String action,String entity,String store,String actor,String key,String hash,Class<T> type,Supplier<T> work) {
        scope(entity,store);if(actor==null || actor.isBlank() || actor.length()>32) throw BizException.of(ErrorCode.FORBIDDEN);
        text(key,128);if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Check command cannot join an existing transaction");
        try { return tx.execute(status -> idem.execute(key,FoodCommandFingerprint.scope(action,entity,store),actor,hash,type,work)); }
        catch(ConcurrencyFailureException | DuplicateKeyException conflict) { throw BizException.of(ErrorCode.CONFLICT); }
    }
    private FnbCheck find(String entity,String store,String number,boolean lock) {
        var rows=checks.selectList(Wrappers.<FnbCheck>lambdaQuery().eq(FnbCheck::getEntityNo,entity)
                .eq(FnbCheck::getStoreNo,store).eq(FnbCheck::getCheckNo,number).last(lock?"LIMIT 1 FOR UPDATE":"LIMIT 1"));
        if(rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);return rows.getFirst();
    }
    private FnbCheck locked(String entity,String store,String number,long version) {
        var row=find(entity,store,number,true);
        if(row.getVersion()!=version || !"OPEN".equals(row.getStatus()) || !"OPEN".equals(row.getServiceState()))
            throw BizException.of(ErrorCode.CONFLICT);
        return row;
    }
    private static void localResources(List<FnbTableOccupancy> active,List<FnbTable> tables) {
        if(active.stream().anyMatch(row -> row.getResourceClaimNo()!=null)
                || tables.stream().anyMatch(row -> row.getResourceNo()!=null)) throw BizException.of(ErrorCode.FORBIDDEN);
    }
    private void noTrade(FnbCheck row) {
        if(!"PREPAY".equals(row.getPayMode()) || checks.tradeFacts(row.getEntityNo(),row.getCheckNo())>0
                || operations.externalOrPendingForCheck(row.getEntityNo(),row.getCheckNo())>0)
            throw BizException.of(ErrorCode.CONFLICT);
    }
    private List<FnbTableOccupancy> active(FnbCheck row) {
        var active=occupancies.activeForCheck(row.getEntityNo(),row.getCheckNo());
        if(active.stream().map(FnbTableOccupancy::getTableNo).distinct().count()!=active.size()) throw BizException.of(ErrorCode.CONFLICT);
        return active;
    }
    private Map<String,Integer> allocation(String entity,List<FnbTable> tables,int party,String mode,String own) {
        var active=new LinkedHashMap<String,List<FnbTableOccupancy>>();
        for(var table:tables) active.put(table.getTableNo(),occupancies.activeForTable(entity,table.getTableNo()));
        return TableOccupancyPolicy.allocate(tables,active,party,mode,own);
    }
    private void occupy(FnbCheck check,Map<String,Integer> allocation,String mode,String actor) {
        for(var target:allocation.entrySet()) {
            var row=new FnbTableOccupancy();row.setEntityNo(check.getEntityNo());row.setCheckNo(check.getCheckNo());
            row.setOccupancyNo(BizKey.next("FOCC"));row.setTableNo(target.getKey());row.setSeats(target.getValue());
            row.setStartedAt(now());row.setSharingMode(mode);initialize(row,actor);occupancies.insert(row);
        }
    }
    private void endOccupancies(List<FnbTableOccupancy> active,String actor) {
        for(var row:active) {
            row.setEndedAt(now());
            if(row.getVersion()==Long.MAX_VALUE || occupancies.updateById(row)!=1) throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private void touch(String entity,List<FnbTable> locked,String actor) {
        for(var table:locked) {
            boolean empty=occupancies.activeForTable(entity,table.getTableNo()).isEmpty();
            table.setTurnoverState(empty?"IDLE":"IN_USE");
            if(table.getVersion()==Long.MAX_VALUE || tables.updateById(table)!=1) throw BizException.of(ErrorCode.CONFLICT);
            if(empty) sessions.update(null,Wrappers.<FnbGuestSession>lambdaUpdate().eq(FnbGuestSession::getEntityNo,entity)
                    .eq(FnbGuestSession::getStoreNo,table.getStoreNo()).eq(FnbGuestSession::getTableNo,table.getTableNo())
                    .isNull(FnbGuestSession::getCheckNo).isNull(FnbGuestSession::getRevokedAt)
                    .set(FnbGuestSession::getRevokedAt,now()).set(FnbGuestSession::getUpdatedBy,actor).set(FnbGuestSession::getUpdatedAt,LocalDateTime.now()));
        }
    }
    private void revokeCheckSessions(FnbCheck check,String actor) {
        sessions.update(null,Wrappers.<FnbGuestSession>lambdaUpdate().eq(FnbGuestSession::getEntityNo,check.getEntityNo())
                .eq(FnbGuestSession::getStoreNo,check.getStoreNo()).eq(FnbGuestSession::getCheckNo,check.getCheckNo())
                .isNull(FnbGuestSession::getRevokedAt).set(FnbGuestSession::getRevokedAt,now()).set(FnbGuestSession::getUpdatedBy,actor).set(FnbGuestSession::getUpdatedAt,LocalDateTime.now()));
    }
    private void saveSeatRows(FnbCheck check,List<SeatReq> desired,String actor) {
        var history=seats.history(check.getEntityNo(),check.getCheckNo());var retained=new HashSet<String>();
        for(var input:desired) {
            FnbSeat row=null;
            if(input.seatNo()!=null) row=history.stream().filter(s -> s.getSeatNo().equals(input.seatNo())).findFirst()
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
            else row=history.stream().filter(s -> s.getLabel().equals(input.label())).findFirst().orElse(null);
            if(row==null) {
                row=new FnbSeat();row.setEntityNo(check.getEntityNo());row.setCheckNo(check.getCheckNo());row.setSeatNo(BizKey.next("FSET"));
                row.setLabel(input.label());initialize(row,actor);seats.insert(row);
            } else {
                // Untraded free-label changes keep seat identity; uniqueness conflicts roll back the whole request.
                if(!row.getLabel().equals(input.label()) || row.getDeleted()!=0) {
                    if(row.getVersion()==Long.MAX_VALUE || seats.save(check.getEntityNo(),check.getCheckNo(),row.getSeatNo(),row.getVersion(),
                            input.label(),0,actor,LocalDateTime.now())!=1) throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            if(!retained.add(row.getSeatNo())) bad();
        }
        for(var row:history) if(row.getDeleted()==0 && !retained.contains(row.getSeatNo())) {
            if(row.getVersion()==Long.MAX_VALUE || seats.save(check.getEntityNo(),check.getCheckNo(),row.getSeatNo(),row.getVersion(),
                    row.getLabel(),1,actor,LocalDateTime.now())!=1) throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private void operation(FnbCheck check,String number,String kind,String key,String hash,Object expected,String actor,String reason) {
        var row=new FnbTableOperation();row.setEntityNo(check.getEntityNo());row.setStoreNo(check.getStoreNo());row.setOperationNo(number);
        row.setKind(kind);row.setCheckNo(check.getCheckNo());row.setRequestKey(FoodCommandFingerprint.hash(check.getEntityNo(),kind,actor,key));
        row.setRequestHash(hash);row.setExpectedVersions(json.writeValueAsString(expected));
        row.setTargetScope(json.writeValueAsString(Map.of("tableNos",view(check).tableNos(),"partySize",check.getPartySize(),"resultVersion",check.getVersion())));
        row.setStatus("APPLIED");row.setReason(reason);initialize(row,actor);operations.insert(row);
    }
    private void changed(FnbCheck check,String actor,String action,String reason,CheckVO before,String key,String operation) {
        changed(check,actor,action,reason,before,key,operation,before==null?List.of():seatViews(check));
    }
    private void changed(FnbCheck check,String actor,String action,String reason,CheckVO before,String key,String operation,
            List<SeatReq> beforeSeats) {
        var after=view(check);var afterSeats=seatViews(check);var row=new FnbActionLog();row.setEntityNo(check.getEntityNo());row.setStoreNo(check.getStoreNo());
        row.setObjectType("CHECK");row.setObjectNo(check.getCheckNo());row.setActionNo(operation);row.setAction(action);
        row.setOperatorNo(actor);row.setReason(reason);row.setRequestNo(FoodCommandFingerprint.hash(
                FoodCommandFingerprint.scope(action,check.getEntityNo(),check.getStoreNo()),key));
        row.setBeforeRef(before==null?null:FoodCommandFingerprint.hash(json.writeValueAsString(before),json.writeValueAsString(beforeSeats)));
        row.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after),json.writeValueAsString(afterSeats)));initialize(row,actor);actions.insert(row);
        publisher.publish(new CheckChanged(check.getEntityNo(),check.getStoreNo(),check.getCheckNo(),action,check.getVersion(),
                operation,before,after,reason,beforeSeats,afterSeats),new PublishOptions("FOOD_CHECK:"+check.getCheckNo(),1));
    }
    private List<SeatReq> seatViews(FnbCheck check) {
        return seats.selectList(Wrappers.<FnbSeat>lambdaQuery().eq(FnbSeat::getEntityNo,check.getEntityNo())
                .eq(FnbSeat::getCheckNo,check.getCheckNo()).orderByAsc(FnbSeat::getSeatNo)).stream()
                .map(row -> new SeatReq(row.getSeatNo(),row.getLabel(),null)).toList();
    }
    private void update(FnbCheck row,String actor) {
        if(row.getVersion()==Long.MAX_VALUE || checks.updateById(row)!=1) throw BizException.of(ErrorCode.CONFLICT);
    }
    private CheckVO view(FnbCheck check) { return view(check,active(check)); }
    private CheckVO view(FnbCheck check,List<FnbTableOccupancy> active) {
        return new CheckVO(check.getCheckNo(),check.getStoreNo(),check.getBusinessDate(),check.getStatus(),check.getPayMode(),
                check.getPartySize(),tableNos(active),check.getVersion());
    }
    private static String mode(List<FnbTableOccupancy> active) {
        if(active.isEmpty()) return "EXCLUSIVE";
        String mode=active.getFirst().getSharingMode();
        if(mode==null || active.stream().anyMatch(row -> !mode.equals(row.getSharingMode()))) throw BizException.of(ErrorCode.CONFLICT);
        return mode;
    }
    private static List<String> tableNos(List<FnbTableOccupancy> active) {
        return active.stream().map(FnbTableOccupancy::getTableNo).distinct().sorted().toList();
    }
    private static List<SeatReq> seatInputs(List<SeatReq> inputs,int party) {
        if(inputs==null || inputs.size()>1000 || inputs.size()>party) bad();
        var normalized=new ArrayList<SeatReq>();var labels=new HashSet<String>();var ids=new HashSet<String>();
        for(var input:inputs) {
            if(input==null) bad();unverified(input.guestRef());String label=text(input.label(),32);
            String number=input.seatNo();if(number!=null) id(number);
            if(!labels.add(label) || number!=null && !ids.add(number)) bad();normalized.add(new SeatReq(number,label,null));
        }
        normalized.sort(Comparator.comparing(SeatReq::label));return List.copyOf(normalized);
    }
    private static Map<String,Long> versions(List<TableVersionReq> inputs,int max) {
        if(inputs==null || inputs.size()>max) bad();var result=new TreeMap<String,Long>();
        for(var input:inputs) {
            if(input==null) bad();id(input.tableNo());version(input.version());
            if(result.put(input.tableNo(),input.version())!=null) bad();
        }
        return result;
    }
    private static List<String> numbers(Collection<String> inputs,int max) {
        if(inputs==null || inputs.size()>max) bad();var copy=new ArrayList<String>();
        for(String input:inputs) { id(input);copy.add(input); }
        if(new HashSet<>(copy).size()!=copy.size()) bad();return copy.stream().sorted().toList();
    }
    private static void initialize(BaseEntity row,String actor) {
        row.setVersion(0L);row.setDeleted(0);
    }
    private static void prepay(String mode) {
        if("POSTPAY".equals(mode)) throw BizException.of(ErrorCode.FORBIDDEN);if(!"PREPAY".equals(mode)) bad();
    }
    private static void unverified(String ref) { if(ref!=null && !ref.isBlank()) throw BizException.of(ErrorCode.FORBIDDEN); }
    private static void scope(String entity,String store) { id(entity);id(store); }
    private static void id(String value) { text(value,32); }
    private static void version(Long value) { if(value==null || value<0) bad(); }
    private static int page(Integer value) { if(value!=null && value<1) bad();return value==null?1:value; }
    private static int size(Integer value) { if(value!=null && (value<1 || value>100)) bad();return value==null?20:value; }
    private static String text(String value,int max) { if(value==null || value.isBlank() || value.length()>max) bad();return value.trim(); }
    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    private static LocalDateTime now() { return local(Instant.now().truncatedTo(ChronoUnit.MILLIS)); }
    private static LocalDateTime local(Instant at) { return LocalDateTime.ofInstant(at,ZoneOffset.UTC); }
}
