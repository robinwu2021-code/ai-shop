package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.CreateMenuResultVO;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuResultVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbMenu;
import ai.neargo.shop.food.entity.FnbMenuStore;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.food.service.MenuService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.MenuChanged;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Single-store editing cannot silently mutate shared menus or reinterpret their published snapshots. */
public class MenuServiceImpl implements MenuService {
    private final MenuMapper heads;
    private final MenuStoreMapper bindings;
    private final StorePolicyMapper policies;
    private final ActionLogMapper actions;
    private final IdempotencyService idem;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public MenuServiceImpl(MenuMapper heads, MenuStoreMapper bindings, StorePolicyMapper policies,
            ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, Clock clock) {
        this.heads = heads; this.bindings = bindings; this.policies = policies; this.actions = actions;
        this.idem = idem; this.publisher = publisher; this.json = json; this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public CreateMenuResultVO createMenu(String entity, String store, String actor, CreateMenuReq req, String key) {
        context(entity, store, actor, key); MenuDefinitionPolicy.create(req);
        String scope = FoodCommandFingerprint.scope("CREATE_MENU", entity, store);
        String hash = FoodCommandFingerprint.hash("create-menu:v1", json.writeValueAsString(req));
        var replay = read.execute(tx -> idem.findCompleted(key, scope, actor, hash, CreateMenuResultVO.class));
        if (replay.isPresent()) return replay.get();
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, CreateMenuResultVO.class, () -> {
                timezone(entity, store, req.timezone());
                var row = new FnbMenu();
                row.setEntityNo(entity); row.setMenuNo(BizKey.next("FMNU"));
                row.setName(req.name()); row.setMenuType(req.menuType()); row.setTimezone(req.timezone());
                row.setPriority(req.priority()); row.setExclusiveGroup(req.exclusiveGroup());
                row.setStatus("DRAFT"); row.setVersion(0L); row.setConfigVersion(0L);
                heads.insert(row);
                var binding = new FnbMenuStore();
                binding.setEntityNo(entity); binding.setMenuNo(row.getMenuNo()); binding.setStoreNo(store);
                bindings.insert(binding);
                record(entity, store, actor, key, "CREATE_MENU", null, view(row, store));
                return new CreateMenuResultVO(row.getMenuNo(), 0L, "DRAFT");
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public MenuResultVO updateMenu(String entity, String store, String number, String actor,
            UpdateMenuReq req, String key) {
        context(entity, store, actor, key); MenuDefinitionPolicy.key(number); MenuDefinitionPolicy.update(req);
        String scope = FoodCommandFingerprint.scope("UPDATE_MENU", entity, store);
        String hash = FoodCommandFingerprint.hash("update-menu:v1", number, json.writeValueAsString(req));
        var replay = read.execute(tx -> idem.findCompleted(key, scope, actor, hash, MenuResultVO.class));
        if (replay.isPresent()) return replay.get();
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, MenuResultVO.class, () -> {
                var row = head(entity, store, number, true);
                var all = bindings.selectList(Wrappers.<FnbMenuStore>lambdaQuery()
                        .eq(FnbMenuStore::getEntityNo, entity).eq(FnbMenuStore::getMenuNo, number)
                        .last("FOR UPDATE"));
                if (all.size() != 1 || !store.equals(all.getFirst().getStoreNo())
                        || all.getFirst().getOverrideRevisionNo() != null
                        || !req.version().equals(row.getVersion()) || row.getConfigVersion() == null
                        || row.getConfigVersion() < 0 || row.getConfigVersion() == Long.MAX_VALUE
                        || !List.of("DRAFT", "PUBLISHED").contains(row.getStatus() == null ? "" : row.getStatus())) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                timezone(entity, store, req.timezone());
                var before = view(row, store);
                int affected = heads.update(null, Wrappers.<FnbMenu>lambdaUpdate()
                        .eq(FnbMenu::getEntityNo, entity).eq(FnbMenu::getMenuNo, number)
                        .eq(FnbMenu::getVersion, req.version())
                        .set(FnbMenu::getName, req.name()).set(FnbMenu::getMenuType, req.menuType())
                        .set(FnbMenu::getTimezone, req.timezone()).set(FnbMenu::getPriority, req.priority())
                        .set(FnbMenu::getExclusiveGroup, req.exclusiveGroup()).set(FnbMenu::getStatus, "DRAFT")
                        .set(FnbMenu::getUpdatedBy, actor)
                        .set(FnbMenu::getUpdatedAt, LocalDateTime.now())
                        .set(FnbMenu::getVersion, req.version() + 1)
                        .set(FnbMenu::getConfigVersion, row.getConfigVersion() + 1));
                if (affected != 1) throw BizException.of(ErrorCode.CONFLICT);
                var after = view(head(entity, store, number, false), store);
                record(entity, store, actor, key, "UPDATE_MENU", before, after);
                return new MenuResultVO(after);
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public MenuConfigResultVO getMenu(String entity, String store, String number) {
        queryContext(entity, store); MenuDefinitionPolicy.key(number);
        return read.execute(tx -> new MenuConfigResultVO(view(head(entity, store, number, false), store)));
    }

    @Override
    public PageData<MenuVO> listMenus(String entity, String store, ListMenusQuery query) {
        queryContext(entity, store); MenuDefinitionPolicy.query(query);
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        return read.execute(tx -> {
            var filter = Wrappers.<FnbMenu>lambdaQuery().eq(FnbMenu::getEntityNo, entity)
                    .apply("EXISTS (SELECT 1 FROM fnb_menu_store ms WHERE ms.menu_no=fnb_menu.menu_no "
                            + "AND ms.entity_no={0} AND ms.store_no={1} AND ms.deleted=0)", entity, store);
            if (query != null && query.status() != null) filter.eq(FnbMenu::getStatus, query.status());
            if (query != null && query.keyword() != null) filter.apply("LOCATE({0}, name)>0", query.keyword());
            var result = heads.selectPage(new Page<FnbMenu>(page, size), filter.orderByAsc(FnbMenu::getMenuNo));
            return PageData.of(result.getRecords().stream().map(row -> view(row, store)).toList(),
                    result.getTotal(), page, size);
        });
    }

    private FnbMenu head(String entity, String store, String number, boolean lock) {
        var filter = Wrappers.<FnbMenu>lambdaQuery().eq(FnbMenu::getEntityNo, entity).eq(FnbMenu::getMenuNo, number);
        if (lock) filter.last("FOR UPDATE");
        var row = heads.selectOne(filter);
        var binding = bindings.selectOne(Wrappers.<FnbMenuStore>lambdaQuery()
                .eq(FnbMenuStore::getEntityNo, entity).eq(FnbMenuStore::getMenuNo, number)
                .eq(FnbMenuStore::getStoreNo, store));
        if (row == null || binding == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private void timezone(String entity, String store, String timezone) {
        var rows = policies.selectList(Wrappers.<FnbStorePolicy>lambdaQuery()
                .eq(FnbStorePolicy::getEntityNo, entity).eq(FnbStorePolicy::getStoreNo, store)
                .le(FnbStorePolicy::getEffectiveFrom, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .orderByDesc(FnbStorePolicy::getEffectiveFrom).last("LIMIT 1 FOR UPDATE"));
        if (rows.isEmpty() || !timezone.equals(rows.getFirst().getTimezone())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static MenuVO view(FnbMenu row, String store) {
        var view = new MenuVO(row.getMenuNo(), store, row.getName(), row.getMenuType(), row.getTimezone(),
                row.getPriority(), row.getExclusiveGroup(), row.getStatus(), row.getVersion(), row.getConfigVersion());
        if (!MenuDefinitionPolicy.valid(view, store)) throw new IllegalStateException("Invalid menu working head");
        return view;
    }

    private void record(String entity, String store, String actor, String key, String action, MenuVO before,
            MenuVO after) {
        var audit = new FnbActionLog(); audit.setEntityNo(entity); audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT")); audit.setObjectType("MENU"); audit.setObjectNo(after.menuNo());
        audit.setAction(action); audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(FoodCommandFingerprint.scope(action, entity, store), key));
        if (before != null) audit.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(before)));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after))); actions.insert(audit);
        publisher.publish(new MenuChanged(entity, store, after.menuNo(), action, after.version(), audit.getActionNo(),
                after), new PublishOptions("FOOD_MENU:" + after.menuNo(), 1));
    }

    private static void context(String entity, String store, String actor, String key) {
        queryContext(entity, store); MenuDefinitionPolicy.key(actor);
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static void queryContext(String entity, String store) {
        MenuDefinitionPolicy.key(entity); MenuDefinitionPolicy.key(store);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Menu operations cannot join an existing transaction");
        }
    }
}
