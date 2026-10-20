package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.ArchiveTableReq;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.CreateTableReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.ListTablesQuery;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SaveTableReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import ai.neargo.shop.food.dto.TableResultVO;
import ai.neargo.shop.food.dto.TableVO;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbTable;
import ai.neargo.shop.food.entity.FnbTableArea;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.TableAreaChanged;
import ai.neargo.shop.spi.food.TableChanged;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Set;
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

/** Store-scoped configuration; the area row arbitrates edits and future table membership changes. */
@Service
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class TableServiceImpl implements TableService {
    private final TableAreaMapper areas;
    private final ActionLogMapper actions;
    private final IdempotencyService idempotency;
    private final OutboxPublisher publisher;
    private final TransactionTemplate transaction;
    private final TransactionTemplate tableTransaction;
    private final TableMapper tables;

    public TableServiceImpl(TableAreaMapper areas, TableMapper tables, ActionLogMapper actions,
            IdempotencyService idempotency,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.areas = areas;
        this.tables = tables;
        this.actions = actions;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.tableTransaction = new TransactionTemplate(manager);
        this.tableTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    @Transactional(transactionManager="transactionManager", readOnly=true)
    public PageData<TableAreaVO> listTableAreas(String entityNo, String storeNo, ListTableAreasQuery query) {
        scope(entityNo, storeNo);
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        String status = query == null ? null : query.status();
        if (page < 1 || size < 1 || size > 100 || status != null && !validStatus(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var filter = Wrappers.<FnbTableArea>lambdaQuery().eq(FnbTableArea::getEntityNo, entityNo)
                .eq(FnbTableArea::getStoreNo, storeNo).eq(status != null, FnbTableArea::getStatus, status)
                .orderByAsc(FnbTableArea::getSortNo).orderByAsc(FnbTableArea::getAreaNo);
        var result = areas.selectPage(new Page<FnbTableArea>(page, size), filter);
        return PageData.of(result.getRecords().stream().map(TableServiceImpl::view).toList(),
                result.getTotal(), page, size);
    }

    @Override
    public TableAreaResultVO createTableArea(String entityNo, String storeNo, String actorNo,
            CreateTableAreaReq req, String key) {
        if (req == null || req.sortNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        String code = text(req.code(), 32);
        String name = text(req.name(), 64);
        String hash = FoodCommandFingerprint.hash("create-area:v1", code, name, req.sortNo().toString());
        return write("CREATE_AREA", entityNo, storeNo, actorNo, key, hash, () -> {
            var row = new FnbTableArea();
            row.setEntityNo(entityNo);
            row.setStoreNo(storeNo);
            row.setAreaNo(BizKey.next("FARE"));
            row.setCode(code);
            row.setName(name);
            row.setSortNo(req.sortNo());
            row.setStatus("ACTIVE");
            row.setVersion(0L);
            areas.insert(row);
            changed(row, actorNo, "CREATE_AREA", null, null, key);
            return new TableAreaResultVO(view(row));
        });
    }

    @Override
    public TableAreaResultVO saveTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo,
            SaveTableAreaReq req, String key) {
        identifier(areaNo);
        if (req == null || req.sortNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        version(req.version());
        String name = text(req.name(), 64);
        String hash = FoodCommandFingerprint.hash("save-area:v1", areaNo, name, req.sortNo().toString(),
                req.version().toString());
        return write("SAVE_AREA", entityNo, currentStoreNo, actorNo, key, hash, () -> {
            var row = locked(entityNo, currentStoreNo, areaNo, req.version());
            var before = view(row);
            row.setName(name);
            row.setSortNo(req.sortNo());
            update(row);
            changed(row, actorNo, "SAVE_AREA", null, before, key);
            return new TableAreaResultVO(view(row));
        });
    }

    @Override
    public TableAreaResultVO setStatusTableArea(String entityNo, String currentStoreNo, String areaNo, String actorNo,
            SetStatusTableAreaReq req, String key) {
        identifier(areaNo);
        if (req == null || !validStatus(req.status())) throw BizException.of(ErrorCode.BAD_REQUEST);
        version(req.version());
        String reason = text(req.reason(), 255);
        String hash = FoodCommandFingerprint.hash("status-area:v1", areaNo, req.status(), req.version().toString(),
                reason);
        return write("SET_STATUS_AREA", entityNo, currentStoreNo, actorNo, key, hash, () -> {
            var row = locked(entityNo, currentStoreNo, areaNo, req.version());
            if (req.status().equals(row.getStatus())) return new TableAreaResultVO(view(row));
            if ("DISABLED".equals(req.status())
                    && (areas.activeTables(entityNo, currentStoreNo, areaNo) > 0
                    || areas.liveOccupancies(entityNo, currentStoreNo, areaNo) > 0)) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            var before = view(row);
            row.setStatus(req.status());
            update(row);
            changed(row, actorNo, "SET_STATUS_AREA", reason, before, key);
            return new TableAreaResultVO(view(row));
        });
    }

    private TableAreaResultVO write(String operation, String entity, String store, String actor, String key,
            String hash, Supplier<TableAreaResultVO> command) {
        scope(entity, store);
        if (actor == null || actor.isBlank() || actor.length() > 32) throw BizException.of(ErrorCode.FORBIDDEN);
        text(key, 128);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Area command cannot join an existing transaction");
        }
        String scope = FoodCommandFingerprint.scope(operation, entity, store);
        try {
            return transaction.execute(status -> idempotency.execute(key, scope, actor, hash,
                    TableAreaResultVO.class, command));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private FnbTableArea locked(String entity, String store, String area, long expected) {
        var rows = areas.selectList(Wrappers.<FnbTableArea>lambdaQuery().eq(FnbTableArea::getEntityNo, entity)
                .eq(FnbTableArea::getStoreNo, store).eq(FnbTableArea::getAreaNo, area).last("LIMIT 1 FOR UPDATE"));
        if (rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        var row = rows.getFirst();
        if (row.getVersion() != expected) throw BizException.of(ErrorCode.CONFLICT);
        return row;
    }

    private void update(FnbTableArea row) {
        if (row.getVersion() == Long.MAX_VALUE || areas.updateById(row) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void changed(FnbTableArea row, String actor, String action, String reason, TableAreaVO before, String key) {
        var audit = new FnbActionLog();
        audit.setEntityNo(row.getEntityNo());
        audit.setStoreNo(row.getStoreNo());
        audit.setActionNo(BizKey.next("FACT"));
        audit.setObjectType("TABLE_AREA");
        audit.setObjectNo(row.getAreaNo());
        audit.setAction(action);
        audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(
                FoodCommandFingerprint.scope(action, row.getEntityNo(), row.getStoreNo()), key));
        audit.setReason(reason);
        audit.setBeforeRef(before == null ? null : snapshot(before));
        audit.setAfterRef(snapshot(view(row)));
        actions.insert(audit);
        publisher.publish(new TableAreaChanged(row.getEntityNo(), row.getStoreNo(), row.getAreaNo(), action,
                row.getVersion(), audit.getActionNo(), before, view(row), reason),
                new PublishOptions("FOOD_AREA:" + row.getAreaNo(), 1));
    }

    private static String snapshot(TableAreaVO row) {
        return FoodCommandFingerprint.hash(row.code(), row.name(), Integer.toString(row.sortNo()),
                row.status(), Long.toString(row.version()));
    }

    private static TableAreaVO view(FnbTableArea row) {
        return new TableAreaVO(row.getAreaNo(), row.getStoreNo(), row.getCode(), row.getName(), row.getSortNo(),
                row.getStatus(), row.getVersion());
    }

    private static void scope(String entity, String store) {
        if (entity == null || entity.isBlank() || entity.length() > 32
                || store == null || store.isBlank() || store.length() > 32) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    private static void identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 32) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static String text(String value, int length) {
        if (value == null || value.isBlank() || value.length() > length) throw BizException.of(ErrorCode.BAD_REQUEST);
        return value.trim();
    }

    private static void version(Long value) {
        if (value == null || value < 0) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static boolean validStatus(String status) { return "ACTIVE".equals(status) || "DISABLED".equals(status); }
    @Override
    @Transactional(transactionManager="transactionManager", readOnly=true)
    public PageData<TableVO> listTables(String entityNo, String storeNo, ListTablesQuery query) {
        scope(entityNo, storeNo);
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        String area = query == null ? null : query.areaNo();
        String status = query == null ? null : query.status();
        String turnover = query == null ? null : query.turnoverState();
        String keyword = query == null ? null : query.keyword();
        if (page < 1 || size < 1 || size > 100 || status != null && !validStatus(status)
                || turnover != null && !Set.of("IDLE", "IN_USE", "NEEDS_CLEANING").contains(turnover)
                || keyword != null && keyword.length() > 64) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (area != null) identifier(area);
        var filter = tableFilter(entityNo, storeNo).eq(area != null, FnbTable::getAreaNo, area)
                .eq(status != null, FnbTable::getStatus, status)
                .eq(turnover != null, FnbTable::getTurnoverState, turnover)
                .orderByAsc(FnbTable::getSortNo).orderByAsc(FnbTable::getTableNo);
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            filter.and(w -> w.apply("(code LIKE {0} ESCAPE '!' OR name LIKE {0} ESCAPE '!')", pattern));
        }
        var result = tables.selectPage(new Page<FnbTable>(page, size), filter);
        return PageData.of(result.getRecords().stream().map(TableServiceImpl::tableView).toList(),
                result.getTotal(), page, size);
    }

    @Override
    @Transactional(transactionManager="transactionManager", readOnly=true)
    public TableResultVO getTable(String entityNo, String currentStoreNo, String tableNo) {
        scope(entityNo, currentStoreNo);
        identifier(tableNo);
        return new TableResultVO(tableView(findTable(entityNo, currentStoreNo, tableNo, false)));
    }

    @Override
    public TableResultVO createTable(String entityNo, String storeNo, String actorNo, CreateTableReq req,
            String key) {
        if (req == null || req.capacity() == null || req.capacity() < 1 || req.sortNo() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        identifier(req.areaNo());
        String code = text(req.code(), 32);
        String name = text(req.name(), 64);
        if (req.resourceNo() != null && req.resourceNo().length() > 32) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (req.resourceNo() != null && !req.resourceNo().isBlank()) throw BizException.of(ErrorCode.FORBIDDEN);
        String hash = FoodCommandFingerprint.hash("create-table:v1", req.areaNo(), code, name,
                req.capacity().toString(), req.sortNo().toString());
        return tableWrite("CREATE_TABLE", entityNo, storeNo, actorNo, key, hash, TableResultVO.class, () -> {
            lockAreas(entityNo, storeNo, req.areaNo(), req.areaNo(), true);
            var row = new FnbTable();
            row.setEntityNo(entityNo);
            row.setStoreNo(storeNo);
            row.setTableNo(BizKey.next("FTBL"));
            row.setAreaNo(req.areaNo());
            row.setCode(code);
            row.setName(name);
            row.setCapacity(req.capacity());
            row.setSortNo(req.sortNo());
            row.setStatus("ACTIVE");
            row.setTurnoverState("IDLE");
            row.setVersion(0L);
            tables.insert(row);
            tableChanged(row, actorNo, "CREATE_TABLE", null, null, key);
            return new TableResultVO(tableView(row));
        });
    }

    @Override
    public TableResultVO saveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo,
            SaveTableReq req, String key) {
        identifier(tableNo);
        if (req == null || req.capacity() == null || req.capacity() < 1 || req.sortNo() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        identifier(req.areaNo());
        version(req.version());
        String name = text(req.name(), 64);
        String hash = FoodCommandFingerprint.hash("save-table:v1", tableNo, req.areaNo(), name,
                req.capacity().toString(), req.sortNo().toString(), req.version().toString());
        return tableWrite("SAVE_TABLE", entityNo, currentStoreNo, actorNo, key, hash, TableResultVO.class,
                () -> {
            var row = lockedTable(entityNo, currentStoreNo, tableNo, req.areaNo(), false, req.version());
            if ((!row.getAreaNo().equals(req.areaNo()) || req.capacity() < row.getCapacity())
                    && tables.liveOccupancies(entityNo, tableNo) > 0) throw BizException.of(ErrorCode.CONFLICT);
            var before = tableView(row);
            row.setAreaNo(req.areaNo());
            row.setName(name);
            row.setCapacity(req.capacity());
            row.setSortNo(req.sortNo());
            updateTable(row);
            tableChanged(row, actorNo, "SAVE_TABLE", null, before, key);
            return new TableResultVO(tableView(row));
        });
    }

    @Override
    public TableResultVO setStatusTable(String entityNo, String currentStoreNo, String tableNo, String actorNo,
            SetStatusTableReq req, String key) {
        identifier(tableNo);
        if (req == null || !validStatus(req.status())) throw BizException.of(ErrorCode.BAD_REQUEST);
        version(req.version());
        String reason = text(req.reason(), 255);
        String hash = FoodCommandFingerprint.hash("status-table:v1", tableNo, req.status(),
                req.version().toString(), reason);
        return tableWrite("SET_STATUS_TABLE", entityNo, currentStoreNo, actorNo, key, hash, TableResultVO.class,
                () -> {
            var row = lockedTable(entityNo, currentStoreNo, tableNo, null, "ACTIVE".equals(req.status()), req.version());
            if (row.getStatus().equals(req.status())) return new TableResultVO(tableView(row));
            if ("DISABLED".equals(req.status()) && tables.liveOccupancies(entityNo, tableNo) > 0) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            var before = tableView(row);
            row.setStatus(req.status());
            updateTable(row);
            tableChanged(row, actorNo, "SET_STATUS_TABLE", reason, before, key);
            return new TableResultVO(tableView(row));
        });
    }

    @Override
    public OperationResultVO archiveTable(String entityNo, String currentStoreNo, String tableNo, String actorNo,
            ArchiveTableReq req, String key) {
        identifier(tableNo);
        if (req == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        version(req.version());
        String reason = text(req.reason(), 255);
        String hash = FoodCommandFingerprint.hash("archive-table:v1", tableNo, req.version().toString(), reason);
        return tableWrite("ARCHIVE_TABLE", entityNo, currentStoreNo, actorNo, key, hash,
                OperationResultVO.class, () -> {
                    var row = lockedTable(entityNo, currentStoreNo, tableNo, null, false, req.version());
                    if (!"DISABLED".equals(row.getStatus()) || tables.liveOccupancies(entityNo, tableNo) > 0
                            || row.getVersion() == Long.MAX_VALUE) throw BizException.of(ErrorCode.CONFLICT);
                    var before = tableView(row);
                    if (tables.archive(entityNo, currentStoreNo, tableNo, row.getVersion(), actorNo) != 1) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    row.setVersion(row.getVersion() + 1);
                    row.setDeleted(1);
                    String operation = tableChanged(row, actorNo, "ARCHIVE_TABLE", reason, before, key);
                    return new OperationResultVO(new OperationVO(operation, "SUCCEEDED", row.getVersion(), tableNo));
                });
    }

    private <T> T tableWrite(String operation, String entity, String store, String actor, String key,
            String hash, Class<T> resultType, Supplier<T> command) {
        scope(entity, store);
        if (actor == null || actor.isBlank() || actor.length() > 32) throw BizException.of(ErrorCode.FORBIDDEN);
        text(key, 128);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Table command cannot join an existing transaction");
        }
        try {
            return tableTransaction.execute(status -> idempotency.execute(key,
                    FoodCommandFingerprint.scope(operation, entity, store), actor, hash, resultType, command));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private LambdaQueryWrapper<FnbTable> tableFilter(String entity, String store) {
        return Wrappers.<FnbTable>lambdaQuery().eq(FnbTable::getEntityNo, entity).eq(FnbTable::getStoreNo, store);
    }

    private FnbTable findTable(String entity, String store, String tableNo, boolean lock) {
        var rows = tables.selectList(tableFilter(entity, store).eq(FnbTable::getTableNo, tableNo)
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
        if (rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private FnbTable lockedTable(String entity, String store, String table, String target, boolean enable,
            long expected) {
        var snapshot = findTable(entity, store, table, false);
        if (snapshot.getVersion() != expected) throw BizException.of(ErrorCode.CONFLICT);
        String destination = target == null ? snapshot.getAreaNo() : target;
        lockAreas(entity, store, snapshot.getAreaNo(), destination,
                (enable && !"ACTIVE".equals(snapshot.getStatus())) || !snapshot.getAreaNo().equals(destination));
        var row = findTable(entity, store, table, true);
        if (!row.getAreaNo().equals(snapshot.getAreaNo()) || row.getVersion() != expected) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return row;
    }

    private void lockAreas(String entity, String store, String oldArea, String target, boolean requireActive) {
        for (String area : Stream.of(oldArea, target).distinct().sorted().toList()) {
            var rows = areas.selectList(Wrappers.<FnbTableArea>lambdaQuery().eq(FnbTableArea::getEntityNo, entity)
                    .eq(FnbTableArea::getStoreNo, store).eq(FnbTableArea::getAreaNo, area).last("LIMIT 1 FOR UPDATE"));
            if (rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            if (area.equals(target) && requireActive && !"ACTIVE".equals(rows.getFirst().getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
    }

    private void updateTable(FnbTable row) {
        if (row.getVersion() == Long.MAX_VALUE || tables.updateById(row) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private String tableChanged(FnbTable row, String actor, String action, String reason, TableVO before, String key) {
        var after = tableView(row);
        var audit = new FnbActionLog();
        audit.setEntityNo(row.getEntityNo());
        audit.setStoreNo(row.getStoreNo());
        audit.setActionNo(BizKey.next("FACT"));
        audit.setObjectType("TABLE");
        audit.setObjectNo(row.getTableNo());
        audit.setAction(action);
        audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(
                FoodCommandFingerprint.scope(action, row.getEntityNo(), row.getStoreNo()), key));
        audit.setReason(reason);
        audit.setBeforeRef(before == null ? null : tableSnapshot(before));
        audit.setAfterRef(tableSnapshot(after));
        actions.insert(audit);
        publisher.publish(new TableChanged(row.getEntityNo(), row.getStoreNo(), row.getTableNo(), action,
                row.getVersion(), audit.getActionNo(), before, after, reason),
                new PublishOptions("FOOD_TABLE:" + row.getTableNo(), 1));
        return audit.getActionNo();
    }

    private static String tableSnapshot(TableVO row) {
        return FoodCommandFingerprint.hash(row.areaNo(), row.code(), row.name(), Integer.toString(row.capacity()),
                Integer.toString(row.sortNo()), row.status(), row.turnoverState(), Long.toString(row.version()));
    }

    private static TableVO tableView(FnbTable row) {
        return new TableVO(row.getTableNo(), row.getStoreNo(), row.getAreaNo(), row.getCode(), row.getName(),
                row.getCapacity(), row.getSortNo(), row.getStatus(), row.getTurnoverState(), row.getResourceNo(),
                row.getVersion());
    }
}
