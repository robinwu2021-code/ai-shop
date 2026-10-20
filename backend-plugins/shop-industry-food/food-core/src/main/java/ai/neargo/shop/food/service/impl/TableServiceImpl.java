package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbTableArea;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.TableAreaChanged;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.function.Supplier;
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

    public TableServiceImpl(TableAreaMapper areas, ActionLogMapper actions, IdempotencyService idempotency,
            @Qualifier("foodOutboxPublisher") OutboxPublisher publisher,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.areas = areas;
        this.actions = actions;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
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
}
