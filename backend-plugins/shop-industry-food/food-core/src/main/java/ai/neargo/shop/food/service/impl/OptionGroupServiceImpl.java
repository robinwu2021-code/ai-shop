package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.CreateOptionGroupResultVO;
import ai.neargo.shop.food.dto.ListOptionGroupsQuery;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionGroupResultVO;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbOptionGroup;
import ai.neargo.shop.food.entity.FnbOptionGroupItem;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.OptionGroupChanged;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Reuses V1 shared group tables and the platform's atomic command infrastructure. */
public class OptionGroupServiceImpl implements OptionGroupService {
    private final OptionGroupMapper groups;
    private final OptionGroupItemMapper items;
    private final ActionLogMapper actions;
    private final IdempotencyService idempotency;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public OptionGroupServiceImpl(OptionGroupMapper groups, OptionGroupItemMapper items, ActionLogMapper actions,
            IdempotencyService idempotency, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager) {
        this.groups = groups;
        this.items = items;
        this.actions = actions;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.json = json;
        this.writeTransaction = new TransactionTemplate(manager);
        this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTransaction = new TransactionTemplate(manager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public CreateOptionGroupResultVO createOptionGroup(String entity, String store, String actor,
            CreateOptionGroupReq req, String key) {
        identifier(entity); identifier(store);
        if (actor == null || actor.isBlank() || actor.length() > 32 || !actor.equals(actor.trim())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (req == null || req.name() == null || req.name().isBlank() || req.name().trim().length() > 64) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var normalized = new CreateOptionGroupReq(req.name().trim(), quantity(req.minQty()), quantity(req.maxQty()));
        if (normalized.minQty().compareTo(normalized.maxQty()) > 0) throw BizException.of(ErrorCode.BAD_REQUEST);
        noOuterTransaction();
        String scope = FoodCommandFingerprint.scope("CREATE_OPTION_GROUP", entity, store);
        String hash = FoodCommandFingerprint.hash("create-option-group:v1", json.writeValueAsString(normalized));
        try {
            return writeTransaction.execute(status -> idempotency.execute(key, scope, actor, hash,
                    CreateOptionGroupResultVO.class, () -> create(entity, store, actor, key, normalized)));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private CreateOptionGroupResultVO create(String entity, String store, String actor, String key,
            CreateOptionGroupReq req) {
        var row = new FnbOptionGroup();
        row.setEntityNo(entity);
        row.setGroupNo(BizKey.next("FOG"));
        row.setName(req.name());
        row.setMinQty(req.minQty());
        row.setMaxQty(req.maxQty());
        row.setSelectionRules("{}");
        row.setStatus("DRAFT");
        row.setVersion(0L);
        groups.insert(row);
        var after = views(entity, List.of(row)).getFirst();
        var audit = new FnbActionLog();
        audit.setEntityNo(entity);
        audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT"));
        audit.setObjectType("OPTION_GROUP");
        audit.setObjectNo(row.getGroupNo());
        audit.setAction("CREATE_OPTION_GROUP");
        audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(
                FoodCommandFingerprint.scope("CREATE_OPTION_GROUP", entity, store), key));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after)));
        actions.insert(audit);
        publisher.publish(new OptionGroupChanged(entity, store, row.getGroupNo(), audit.getAction(),
                row.getVersion(), audit.getActionNo(), after),
                new PublishOptions("FOOD_OPTION_GROUP:" + row.getGroupNo(), 1));
        return new CreateOptionGroupResultVO(row.getGroupNo(), row.getVersion(), row.getStatus());
    }

    @Override
    public OptionGroupResultVO getOptionGroup(String entity, String store, String config) {
        identifier(entity); identifier(store); identifier(config); noOuterTransaction();
        return readTransaction.execute(status -> {
            var row = groups.selectOne(Wrappers.<FnbOptionGroup>lambdaQuery()
                    .eq(FnbOptionGroup::getEntityNo, entity).eq(FnbOptionGroup::getGroupNo, config));
            if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
            return new OptionGroupResultVO(views(entity, List.of(row)).getFirst());
        });
    }

    @Override
    public PageData<OptionGroupConfigVO> listOptionGroups(String entity, String store, ListOptionGroupsQuery query) {
        identifier(entity); identifier(store); noOuterTransaction();
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        if (page < 1 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        return readTransaction.execute(status -> {
            var result = groups.selectPage(new Page<FnbOptionGroup>(page, size), Wrappers.<FnbOptionGroup>lambdaQuery()
                    .eq(FnbOptionGroup::getEntityNo, entity).orderByAsc(FnbOptionGroup::getGroupNo));
            return PageData.of(views(entity, result.getRecords()), result.getTotal(), page, size);
        });
    }

    private List<OptionGroupConfigVO> views(String entity, List<FnbOptionGroup> rows) {
        if (rows.isEmpty()) return List.of();
        var numbers = rows.stream().map(FnbOptionGroup::getGroupNo).toList();
        var children = items.selectList(Wrappers.<FnbOptionGroupItem>lambdaQuery()
                .eq(FnbOptionGroupItem::getEntityNo, entity).in(FnbOptionGroupItem::getGroupNo, numbers)
                .orderByAsc(FnbOptionGroupItem::getSortNo).orderByAsc(FnbOptionGroupItem::getOptionItemNo))
                .stream().collect(Collectors.groupingBy(FnbOptionGroupItem::getGroupNo));
        return rows.stream().map(row -> {
            var options = children.getOrDefault(row.getGroupNo(), List.of()).stream().map(item ->
                    new OptionGroupConfigVO.Item(item.getOptionItemNo(), item.getSourceKind(), item.getSpecValueNo(),
                            item.getModifierNo(), item.getMinQty(), item.getMaxQty(),
                            item.getDefaultQty(), item.getSortNo()))
                    .toList();
            Map<String, Object> rules = json.readValue(row.getSelectionRules(),
                    json.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            return new OptionGroupConfigVO(row.getGroupNo(), "OPTION_GROUP", row.getVersion(), row.getStatus(),
                    new OptionGroupConfigVO.Data(row.getName(), row.getMinQty(), row.getMaxQty(),
                            row.getPublishedRevisionNo(), rules, options));
        }).toList();
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() < 0) throw BizException.of(ErrorCode.BAD_REQUEST);
        var normalized = value.stripTrailingZeros();
        if (normalized.scale() > 6 || normalized.compareTo(new BigDecimal("999999999999.999999")) > 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return normalized.signum() == 0 ? BigDecimal.ZERO : normalized;
    }

    private static void identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 32 || !value.equals(value.trim())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Option-group operations cannot join an existing transaction");
        }
    }
}
