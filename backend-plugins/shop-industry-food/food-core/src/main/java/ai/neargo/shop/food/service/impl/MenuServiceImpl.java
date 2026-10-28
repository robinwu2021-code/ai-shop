package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.food.service.CatalogCurrencyService;
import ai.neargo.shop.food.policy.CatalogCurrencySession;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.MenuContextsResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.ReplaceMenuContextsReq;
import ai.neargo.shop.food.dto.DisableMenuReq;
import ai.neargo.shop.food.entity.FnbMenuContext;
import ai.neargo.shop.food.mapper.FoodMappers.MenuContextMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuChannelBindingMapper;
import ai.neargo.shop.food.entity.FnbMenuChannelBinding;
import ai.neargo.shop.food.policy.MenuContextDefinitions;
import ai.neargo.shop.food.spi.MenuChannelAuthorityPort;
import java.util.function.Supplier;
import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.CreateMenuResultVO;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuResultVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuCompositionResultVO;
import ai.neargo.shop.food.dto.ReplaceMenuCompositionReq;
import ai.neargo.shop.food.policy.MenuCompositionSource;
import ai.neargo.shop.food.policy.MenuCompositionDefinitions;
import ai.neargo.shop.food.dto.MenuScheduleResultVO;
import ai.neargo.shop.food.dto.ReplaceMenuScheduleReq;
import ai.neargo.shop.food.dto.WeekScheduleInput;
import ai.neargo.shop.food.dto.ExceptionInput;
import ai.neargo.shop.food.entity.FnbMenuSchedule;
import ai.neargo.shop.food.entity.FnbMenuException;
import ai.neargo.shop.food.mapper.FoodMappers.MenuScheduleMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuExceptionMapper;
import ai.neargo.shop.food.policy.MenuScheduleDefinitions;
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
import ai.neargo.shop.food.policy.MenuWorkingDefinitionAccess;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.MenuChanged;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
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
public class MenuServiceImpl implements MenuService, MenuWorkingDefinitionAccess {
    private final CatalogCurrencyService currencies;
    private final MenuContextMapper contexts;
    private final MenuChannelBindingMapper channels;
    private final Supplier<MenuChannelAuthorityPort> channelAuthority;
    private final MenuCompositionSource composition;
    private final MenuScheduleMapper weeks;
    private final MenuExceptionMapper exceptions;
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
            PlatformTransactionManager manager, Clock clock, MenuScheduleMapper weeks,
            MenuExceptionMapper exceptions, MenuCompositionSource composition, MenuContextMapper contexts,
            MenuChannelBindingMapper channels, Supplier<MenuChannelAuthorityPort> channelAuthority) {
        this(heads, bindings, policies, actions, idem, publisher, json, manager, clock, weeks, exceptions,
                composition, contexts, channels, channelAuthority, (entity, store) ->
                composition.legacySession(entity, store));
    }

    /** Currency-aware wiring keeps legacy construction and original command semantics available. */
    public MenuServiceImpl(MenuMapper heads, MenuStoreMapper bindings, StorePolicyMapper policies,
            ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, Clock clock, MenuScheduleMapper weeks,
            MenuExceptionMapper exceptions, MenuCompositionSource composition, MenuContextMapper contexts,
            MenuChannelBindingMapper channels, Supplier<MenuChannelAuthorityPort> channelAuthority,
            CatalogCurrencyService currencies) {
        this.currencies = currencies;
        this.contexts = contexts; this.channels = channels; this.channelAuthority = channelAuthority;
        this.composition = composition;
        this.weeks = weeks; this.exceptions = exceptions;
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
                editable(entity, store, row, req.version());
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
    public MenuScheduleResultVO replaceSchedule(String entity, String store, String number, String actor,
            ReplaceMenuScheduleReq req, String key) {
        context(entity, store, actor, key); MenuDefinitionPolicy.key(number);
        var definition = MenuScheduleDefinitions.normalize(req);
        String scope = FoodCommandFingerprint.scope("REPLACE_MENU_SCHEDULE", entity, store);
        String hash = FoodCommandFingerprint.hash("menu-schedule:v1", number, req.version().toString(),
                json.writeValueAsString(definition));
        var replay = read.execute(tx -> idem.findCompleted(key, scope, actor, hash, MenuScheduleResultVO.class));
        if (replay.isPresent()) return replay.get();
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, MenuScheduleResultVO.class, () -> {
                var row = head(entity, store, number, true);
                editable(entity, store, row, req.version());
                var before = view(row, store);
                timezone(entity, store, row.getTimezone());
                var beforeRules = schedule(entity, number);
                weeks.replaceDraftRows(entity, number); exceptions.replaceDraftRows(entity, number);
                for (var rule : definition.weekSchedules()) {
                    var child = new FnbMenuSchedule(); child.setEntityNo(entity); child.setMenuNo(number);
                    child.setScheduleNo(BizKey.next("FSCH")); child.setWeekday(rule.weekday());
                    child.setStartTime(LocalTime.parse(rule.startTime()));
                    child.setEndTime(LocalTime.parse(rule.endTime()));
                    child.setEndDayOffset(rule.endDayOffset()); weeks.insert(child);
                }
                for (var rule : definition.exceptions()) {
                    var child = new FnbMenuException(); child.setEntityNo(entity); child.setMenuNo(number);
                    child.setExceptionNo(BizKey.next("FMEX")); child.setLocalDate(LocalDate.parse(rule.localDate()));
                    child.setMode(rule.mode());
                    child.setStartTime(rule.startTime() == null ? null : LocalTime.parse(rule.startTime()));
                    child.setEndTime(rule.endTime() == null ? null : LocalTime.parse(rule.endTime()));
                    child.setEndDayOffset(rule.endDayOffset()); exceptions.insert(child);
                }
                if (heads.update(null, Wrappers.<FnbMenu>lambdaUpdate().eq(FnbMenu::getEntityNo, entity)
                        .eq(FnbMenu::getMenuNo, number).eq(FnbMenu::getVersion, req.version())
                        .set(FnbMenu::getValidFrom, definition.validFrom() == null ? null
                                : LocalDate.parse(definition.validFrom()))
                        .set(FnbMenu::getValidTo, definition.validTo() == null ? null
                                : LocalDate.parse(definition.validTo()))
                        .set(FnbMenu::getStatus, "DRAFT").set(FnbMenu::getVersion, req.version() + 1)
                        .set(FnbMenu::getConfigVersion, row.getConfigVersion() + 1)
                        .set(FnbMenu::getUpdatedAt, LocalDateTime.now()).set(FnbMenu::getUpdatedBy, actor)) != 1) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                var after = view(head(entity, store, number, false), store);
                record(entity, store, actor, key, "REPLACE_MENU_SCHEDULE", before, after, beforeRules, definition);
                return new MenuScheduleResultVO(after, definition);
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public MenuCompositionResultVO replaceComposition(String entity, String store, String number, String actor,
            ReplaceMenuCompositionReq req, String key) {
        context(entity, store, actor, key); MenuDefinitionPolicy.key(number);
        var canonical = MenuCompositionDefinitions.normalizeStructure(req);
        String scope = FoodCommandFingerprint.scope("REPLACE_MENU_COMPOSITION", entity, store);
        String hash = FoodCommandFingerprint.hash("menu-composition:v1", number, req.version().toString(),
                json.writeValueAsString(canonical));
        var replay = read.execute(tx -> idem.findCompleted(key, scope, actor, hash, MenuCompositionResultVO.class));
        if (replay.isPresent()) return replay.get();
        var currency = currencies.open(entity, store);
        var definition = MenuCompositionDefinitions.normalizeForCurrency(canonical, currency.currency());
        var previous = read.execute(tx -> {
            head(entity, store, number, false);
            return currency.managed() ? composition.readStructure(entity, number) : composition.read(entity, number);
        });
        var facts = composition.facts(entity, store, definition, currency);
        currency.refresh();
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, MenuCompositionResultVO.class, () -> {
                currency.guardTransaction();
                var row = head(entity, store, number, true);
                editable(entity, store, row, req.version()); timezone(entity, store, row.getTimezone());
                var before = view(row, store);
                var locked = currency.managed() ? composition.readStructure(entity, number)
                        : composition.read(entity, number);
                if (!previous.equals(locked)) throw BizException.of(ErrorCode.CONFLICT);
                var result = composition.replace(entity, store, number, definition, locked, facts);
                if (heads.update(null, Wrappers.<FnbMenu>lambdaUpdate().eq(FnbMenu::getEntityNo, entity)
                        .eq(FnbMenu::getMenuNo, number).eq(FnbMenu::getVersion, req.version())
                        .set(FnbMenu::getStatus, "DRAFT").set(FnbMenu::getVersion, req.version() + 1)
                        .set(FnbMenu::getConfigVersion, row.getConfigVersion() + 1)
                        .set(FnbMenu::getUpdatedAt, LocalDateTime.now()).set(FnbMenu::getUpdatedBy, actor)) != 1) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                var after = view(head(entity, store, number, false), store);
                recordComposition(entity, store, actor, key, before, after, locked, result);
                return new MenuCompositionResultVO(after, result);
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public MenuContextsResultVO replaceContexts(String entity, String store, String number, String actor,
            ReplaceMenuContextsReq req, String key) {
        context(entity, store, actor, key); MenuDefinitionPolicy.key(number);
        var definition = MenuContextDefinitions.normalize(req);
        String scope = FoodCommandFingerprint.scope("REPLACE_MENU_CONTEXTS", entity, store);
        String hash = FoodCommandFingerprint.hash("menu-context:v1", number, req.version().toString(),
                json.writeValueAsString(definition));
        var replay = read.execute(tx -> idem.findCompleted(key, scope, actor, hash, MenuContextsResultVO.class));
        if (replay.isPresent()) return replay.get();
        var previous = read.execute(tx -> {
            head(entity, store, number, false);
            return contextDefinition(entity, number);
        });
        // Bindings remain explicitly unverified drafts until their real authority is connected.
        for (var input : definition) if (input.channelBindingNo() != null) {
            var binding = channels.selectOne(Wrappers.<FnbMenuChannelBinding>lambdaQuery()
                    .eq(FnbMenuChannelBinding::getEntityNo, entity).eq(FnbMenuChannelBinding::getStoreNo, store)
                    .eq(FnbMenuChannelBinding::getMenuNo, number)
                    .eq(FnbMenuChannelBinding::getBindingNo, input.channelBindingNo()));
            if (binding == null) throw BizException.of(ErrorCode.BAD_REQUEST);
            var authority = channelAuthority.get();
            if (authority == null || !authority.authorized(entity, store, binding.getProvider(),
                    binding.getChannelAccountRef(), binding.getExternalStoreRef())
                    || !authority.pricePolicyAuthorized(entity, store, binding.getBindingNo(),
                            input.pricePolicyRef())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, MenuContextsResultVO.class, () -> {
                var row = head(entity, store, number, true);
                editable(entity, store, row, req.version());
                var before = contextDefinition(entity, number);
                if (!previous.equals(before)) throw BizException.of(ErrorCode.CONFLICT);
                var beforeHead = view(row, store);
                for (var input : definition) if (input.channelBindingNo() != null) {
                    var binding = channels.selectOne(Wrappers.<FnbMenuChannelBinding>lambdaQuery()
                            .eq(FnbMenuChannelBinding::getEntityNo, entity)
                            .eq(FnbMenuChannelBinding::getStoreNo, store)
                            .eq(FnbMenuChannelBinding::getMenuNo, number)
                            .eq(FnbMenuChannelBinding::getBindingNo, input.channelBindingNo()).last("FOR UPDATE"));
                    if (binding == null || !"ACTIVE".equals(binding.getStatus())) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                }
                contexts.replaceDraftRows(entity, number);
                for (var input : definition) {
                    var child = new FnbMenuContext(); child.setEntityNo(entity); child.setMenuNo(number);
                    child.setContextNo(BizKey.next("FMCT")); child.setOrderSource(input.orderSource());
                    child.setFulfillment(input.fulfillment()); child.setChannelBindingNo(input.channelBindingNo());
                    child.setChannelScopeKey(input.channelBindingNo() == null ? "LOCAL" : input.channelBindingNo());
                    child.setPricePolicyRef(input.pricePolicyRef()); contexts.insert(child);
                }
                advance(row, entity, actor, "DRAFT");
                var after = view(head(entity, store, number, false), store);
                recordContext(entity, store, actor, key, beforeHead, after, before, definition);
                return new MenuContextsResultVO(after, definition);
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public MenuResultVO disableMenu(String entity, String store, String number, String actor,
            DisableMenuReq req, String key) {
        context(entity, store, actor, key); MenuDefinitionPolicy.key(number);
        if (req == null || req.version() == null || req.version() < 0 || req.version() == Long.MAX_VALUE
                || req.reason() == null || req.reason().isBlank() || req.reason().length() > 255
                || req.reason().codePoints().anyMatch(Character::isISOControl)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String scope = FoodCommandFingerprint.scope("DISABLE_MENU", entity, store);
        String hash = FoodCommandFingerprint.hash("disable-menu:v1", number, json.writeValueAsString(req));
        var replay = read.execute(tx -> idem.findCompleted(key, scope, actor, hash, MenuResultVO.class));
        if (replay.isPresent()) return replay.get();
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, MenuResultVO.class, () -> {
                var row = head(entity, store, number, true);
                editable(entity, store, row, req.version());
                var before = view(row, store); advance(row, entity, actor, "DISABLED");
                var after = view(head(entity, store, number, false), store);
                record(entity, store, actor, key, "DISABLE_MENU", before, after, null, null, req.reason());
                return new MenuResultVO(after);
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void advance(FnbMenu row, String entity, String actor, String status) {
        if (heads.update(null, Wrappers.<FnbMenu>lambdaUpdate().eq(FnbMenu::getEntityNo, entity)
                .eq(FnbMenu::getMenuNo, row.getMenuNo()).eq(FnbMenu::getVersion, row.getVersion())
                .set(FnbMenu::getStatus, status).set(FnbMenu::getVersion, row.getVersion() + 1)
                .set(FnbMenu::getConfigVersion, row.getConfigVersion() + 1)
                .set(FnbMenu::getUpdatedAt, LocalDateTime.now()).set(FnbMenu::getUpdatedBy, actor)) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private List<MenuContextInput> contextDefinition(String entity, String number) {
        try {
            return MenuContextDefinitions.normalize(contexts.selectList(Wrappers.<FnbMenuContext>lambdaQuery()
                    .eq(FnbMenuContext::getEntityNo, entity).eq(FnbMenuContext::getMenuNo, number)).stream()
                    .map(row -> new MenuContextInput(row.getOrderSource(), row.getFulfillment(),
                            row.getChannelBindingNo(), row.getPricePolicyRef())).toList());
        } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid stored menu contexts", invalid); }
    }

    private void recordContext(String entity, String store, String actor, String key, MenuVO before,
            MenuVO after, List<MenuContextInput> previous, List<MenuContextInput> definition) {
        var audit = new FnbActionLog(); audit.setEntityNo(entity); audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT")); audit.setObjectType("MENU"); audit.setObjectNo(after.menuNo());
        audit.setAction("REPLACE_MENU_CONTEXTS"); audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(
                FoodCommandFingerprint.scope("REPLACE_MENU_CONTEXTS", entity, store), key));
        audit.setBeforeRef(FoodCommandFingerprint.hash(
                json.writeValueAsString(new MenuContextsResultVO(before, previous))));
        audit.setAfterRef(FoodCommandFingerprint.hash(
                json.writeValueAsString(new MenuContextsResultVO(after, definition))));
        actions.insert(audit);
        publisher.publish(new MenuChanged(entity, store, after.menuNo(), "REPLACE_MENU_CONTEXTS", after.version(),
                audit.getActionNo(), after, null, null, definition),
                new PublishOptions("FOOD_MENU:" + after.menuNo(), 1));
    }

    private void recordComposition(String entity, String store, String actor, String key, MenuVO before,
            MenuVO after, MenuCompositionVO previous, MenuCompositionVO definition) {
        var audit = new FnbActionLog(); audit.setEntityNo(entity); audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT")); audit.setObjectType("MENU"); audit.setObjectNo(after.menuNo());
        audit.setAction("REPLACE_MENU_COMPOSITION"); audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(
                FoodCommandFingerprint.scope("REPLACE_MENU_COMPOSITION", entity, store), key));
        audit.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(
                new MenuCompositionResultVO(before, previous))));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(
                new MenuCompositionResultVO(after, definition)))); actions.insert(audit);
        publisher.publish(new MenuChanged(entity, store, after.menuNo(), "REPLACE_MENU_COMPOSITION", after.version(),
                audit.getActionNo(), after, null, definition), new PublishOptions("FOOD_MENU:" + after.menuNo(), 1));
    }

    private void editable(String entity, String store, FnbMenu row, long version) {
        var all = bindings.selectList(Wrappers.<FnbMenuStore>lambdaQuery()
                .eq(FnbMenuStore::getEntityNo, entity).eq(FnbMenuStore::getMenuNo, row.getMenuNo()).last("FOR UPDATE"));
        if (all.size() != 1 || !store.equals(all.getFirst().getStoreNo())
                || all.getFirst().getOverrideRevisionNo() != null || row.getVersion() == null
                || row.getVersion() != version || row.getConfigVersion() == null || row.getConfigVersion() < 0
                || row.getConfigVersion() == Long.MAX_VALUE
                || !List.of("DRAFT", "PUBLISHED").contains(row.getStatus() == null ? "" : row.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private MenuScheduleVO schedule(String entity, String number) {
        var row = heads.selectOne(Wrappers.<FnbMenu>lambdaQuery().eq(FnbMenu::getEntityNo, entity)
                .eq(FnbMenu::getMenuNo, number));
        var scheduled = weeks.selectList(Wrappers.<FnbMenuSchedule>lambdaQuery()
                .eq(FnbMenuSchedule::getEntityNo, entity).eq(FnbMenuSchedule::getMenuNo, number));
        var excepted = exceptions.selectList(Wrappers.<FnbMenuException>lambdaQuery()
                .eq(FnbMenuException::getEntityNo, entity).eq(FnbMenuException::getMenuNo, number));
        try {
            return MenuScheduleDefinitions.normalize(new MenuScheduleVO(
                    row.getValidFrom() == null ? null : row.getValidFrom().toString(),
                    row.getValidTo() == null ? null : row.getValidTo().toString(), scheduled.stream().map(r ->
                            new WeekScheduleInput(r.getWeekday(), r.getStartTime().toString(),
                                    r.getEndTime().toString(),
                                    r.getEndDayOffset())).toList(), excepted.stream().map(r -> new ExceptionInput(
                                            r.getLocalDate().toString(), r.getMode(),
                                            r.getStartTime() == null ? null : r.getStartTime().toString(),
                                            r.getEndTime() == null ? null : r.getEndTime().toString(),
                                            r.getEndDayOffset())).toList()));
        } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid stored menu schedule", invalid); }
    }

    @Override
    public MenuConfigResultVO capture(String entity, String store, String number, boolean lock) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Menu publication capture requires an owning local transaction");
        }
        var row = head(entity, store, number, lock);
        if (lock) editable(entity, store, row, row.getVersion());
        return new MenuConfigResultVO(view(row, store), schedule(entity, number),
                composition.readStructure(entity, number),
                contextDefinition(entity, number));
    }

    @Override
    public long currentVersion(String entity, String store, String number, boolean lock) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Menu publication version requires an owning local transaction");
        }
        var row = head(entity, store, number, lock);
        if ("DISABLED".equals(row.getStatus()) || row.getVersion() == null) throw BizException.of(ErrorCode.CONFLICT);
        if (lock) editable(entity, store, row, row.getVersion());
        return row.getVersion();
    }

    @Override
    public void advance(String entity, String store, String number, long version, FnbConfigRevision revision) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Menu publication advance requires an owning local transaction");
        }
        var row = head(entity, store, number, true); editable(entity, store, row, version);
        boolean effective = "EFFECTIVE".equals(revision.getStatus());
        long sourceVersion = json.readTree(revision.getDefinitionSnapshot()).get("sourceVersion").asLong();
        boolean matchesWorkingVersion = sourceVersion == version;
        if (heads.update(null, Wrappers.<FnbMenu>lambdaUpdate().eq(FnbMenu::getEntityNo, entity)
                .eq(FnbMenu::getMenuNo, number).eq(FnbMenu::getVersion, version)
                .set(FnbMenu::getVersion, version + 1)
                .set(FnbMenu::getStatus, effective && matchesWorkingVersion ? "PUBLISHED" : "DRAFT")
                .set(effective, FnbMenu::getPublishedRevisionNo, revision.getRevisionNo())
                .set(FnbMenu::getUpdatedAt, LocalDateTime.now())
                .set(FnbMenu::getUpdatedBy, revision.getCreatedBy())) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public MenuConfigResultVO getMenu(String entity, String store, String number) {
        queryContext(entity, store); MenuDefinitionPolicy.key(number);
        var currency = currencies.open(entity, store);
        return read.execute(tx -> {
            currency.requireFresh();
            return new MenuConfigResultVO(view(head(entity, store, number, false), store), schedule(entity, number),
                    composition.read(entity, number, currency.currency()), contextDefinition(entity, number));
        });
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
        record(entity, store, actor, key, action, before, after, null, null);
    }

    private void record(String entity, String store, String actor, String key, String action, MenuVO before,
            MenuVO after, MenuScheduleVO beforeRules, MenuScheduleVO afterRules) {
        record(entity, store, actor, key, action, before, after, beforeRules, afterRules, null);
    }

    private void record(String entity, String store, String actor, String key, String action, MenuVO before,
            MenuVO after, MenuScheduleVO beforeRules, MenuScheduleVO afterRules, String reason) {
        var audit = new FnbActionLog(); audit.setEntityNo(entity); audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT")); audit.setObjectType("MENU"); audit.setObjectNo(after.menuNo());
        audit.setAction(action); audit.setOperatorNo(actor); audit.setReason(reason);
        audit.setRequestNo(FoodCommandFingerprint.hash(FoodCommandFingerprint.scope(action, entity, store), key));
        if (before != null) audit.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(
                beforeRules == null ? before : new MenuScheduleResultVO(before, beforeRules))));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(
                afterRules == null ? after : new MenuScheduleResultVO(after, afterRules)))); actions.insert(audit);
        publisher.publish(new MenuChanged(entity, store, after.menuNo(), action, after.version(), audit.getActionNo(),
                after, afterRules), new PublishOptions("FOOD_MENU:" + after.menuNo(), 1));
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
