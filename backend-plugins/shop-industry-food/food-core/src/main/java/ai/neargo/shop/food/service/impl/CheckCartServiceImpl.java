package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.AuthorizeCartSessionReq;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartSessionGrantVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CartVersionReq;
import ai.neargo.shop.food.dto.CartVersionVO;
import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.CreateCartReq;
import ai.neargo.shop.food.dto.CreateCustomerCartReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.entity.FnbCheckCartItem;
import ai.neargo.shop.food.entity.FnbGuestSession;
import ai.neargo.shop.food.entity.FnbSeat;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SeatMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableMapper;
import ai.neargo.shop.food.mapper.FoodMappers.TableOccupancyMapper;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.policy.CartLinePolicy;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.TableResourceLocks;
import ai.neargo.shop.food.service.CatalogService;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.service.ServiceSlotLifecycle;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.CartChanged;
import ai.neargo.shop.spi.food.DiningSessionChanged;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Shared restaurant drafts. Owner locks arbitrate creation; no retail order, amount or inventory is
 * mutated.
 */
public class CheckCartServiceImpl implements CheckCartService {
    private DiningIdentityService diningIdentity;
    private ServiceSlotLifecycle serviceSlots;

    @Autowired(required = false)
    public void setDiningIdentity(DiningIdentityService identity) {
        this.diningIdentity = identity;
    }

    @Autowired(required = false)
    public void setServiceSlots(ServiceSlotLifecycle slots) {
        this.serviceSlots = slots;
    }

    private CheckBusinessGuardService businessGuard;

    @Autowired(required = false)
    public void setBusinessGuard(CheckBusinessGuardService guard) {
        this.businessGuard = guard;
    }

    private final CheckCartMapper carts;
    private final CheckCartItemMapper items;
    private final CheckMapper checks;
    private final GuestSessionMapper sessions;
    private final SeatMapper seats;
    private final TableOccupancyMapper occupancies;
    private final TableResourceLocks resources;
    private final ActionLogMapper actions;
    private final IdempotencyService idem;
    private final OutboxPublisher publisher;
    private final CatalogService catalog;
    private final StoreCardQueryPort stores;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public CheckCartServiceImpl(
            CheckCartMapper carts,
            CheckCartItemMapper items,
            CheckMapper checks,
            GuestSessionMapper sessions,
            SeatMapper seats,
            TableOccupancyMapper occupancies,
            TableAreaMapper areas,
            TableMapper tables,
            ActionLogMapper actions,
            IdempotencyService idem,
            OutboxPublisher publisher,
            CatalogService catalog,
            StoreCardQueryPort stores,
            ObjectMapper json,
            PlatformTransactionManager manager,
            Clock clock) {
        this.carts = carts;
        this.items = items;
        this.checks = checks;
        this.sessions = sessions;
        this.seats = seats;
        this.occupancies = occupancies;
        resources = new TableResourceLocks(areas, tables);
        this.actions = actions;
        this.idem = idem;
        this.publisher = publisher;
        this.catalog = catalog;
        this.stores = stores;
        this.json = json;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private record Envelope(int schema, OrderLineInput line) {}

    @Override
    public CartResultVO createCart(
            String entity, String store, String actor, CreateCartReq req, String key) {
        context(entity, store, actor, key);
        if (req == null
                || req.buyerNo() != null
                || !List.of("POS", "WAITER")
                        .contains(req.orderSource() == null ? "" : req.orderSource())) bad();
        createInput(req.checkNo(), req.sessionNo(), req.fulfillment());
        return scoped(
                () ->
                        command(
                                "CREATE_CART",
                                entity,
                                store,
                                actor,
                                key,
                                req,
                                CartResultVO.class,
                                () -> {
                                    owner(entity, store, req.checkNo(), req.sessionNo(), true);
                                    return new CartResultVO(
                                            view(
                                                    scheduled(
                                                            create(
                                                                    entity,
                                                                    store,
                                                                    actor,
                                                                    req.checkNo(),
                                                                    req.sessionNo(),
                                                                    null,
                                                                    req.orderSource(),
                                                                    req.fulfillment(),
                                                                    key),
                                                            req.scheduledAt(),
                                                            actor,
                                                            key),
                                                    false));
                                }));
    }

    @Override
    public CartResultVO getCart(String entity, String store, String number) {
        query(entity, store);
        CartInputPolicy.key(number);
        return scoped(
                () ->
                        read.execute(
                                tx ->
                                        new CartResultVO(
                                                view(head(entity, store, number, false), false))));
    }

    @Override
    public void requireDiningRoomCart(String entity, String store, String number) {
        CartInputPolicy.key(entity);
        CartInputPolicy.key(store);
        CartInputPolicy.key(number);
        scoped(
                () ->
                        write.execute(
                                tx -> {
                                    var snapshot = head(entity, store, number, false);
                                    if (!"DINE_IN".equals(snapshot.getFulfillment())
                                            || snapshot.getCheckNo() == null) conflict();
                                    var owner = check(entity, store, snapshot.getCheckNo(), true);
                                    if (!"OPEN".equals(owner.getServiceState())
                                            || !"POSTPAY".equals(owner.getPayMode())) conflict();
                                    var current = head(entity, store, number, true);
                                    if (!"OPEN".equals(current.getStatus())
                                            || !"DINE_IN".equals(current.getFulfillment())
                                            || !snapshot.getCheckNo().equals(current.getCheckNo())
                                            || !snapshot.getVersion().equals(current.getVersion()))
                                        conflict();
                                    return null;
                                }));
    }

    @Override
    public CartVersionVO replaceLine(
            String entity,
            String store,
            String number,
            String source,
            String actor,
            ReplaceCartLineReq req,
            String key) {
        context(entity, store, actor, key);
        lineInput(number, source, req);
        return scoped(() -> replace(entity, store, number, source, actor, req, key, null));
    }

    @Override
    public CartVersionVO archiveLine(
            String entity,
            String store,
            String number,
            String source,
            String actor,
            CartVersionReq req,
            String key) {
        context(entity, store, actor, key);
        CartInputPolicy.key(number);
        source(source);
        version(req);
        return scoped(() -> archive(entity, store, number, source, actor, req, key, null));
    }

    @Override
    public OperationResultVO archiveCart(
            String entity,
            String store,
            String number,
            String actor,
            CartVersionReq req,
            String key) {
        context(entity, store, actor, key);
        CartInputPolicy.key(number);
        version(req);
        return scoped(
                () ->
                        command(
                                "CLOSE_CART",
                                entity,
                                store,
                                actor,
                                key,
                                List.of(number, req),
                                OperationResultVO.class,
                                () -> {
                                    var row = locked(entity, store, number, null);
                                    editable(row, req.version());
                                    advance(row, actor, "CLOSED");
                                    if (row.getScheduledAt() != null) {
                                        if (serviceSlots == null) conflict();
                                        serviceSlots.releaseCart(entity, store, actor, number);
                                    }
                                    String operation = record(row, actor, key, "CLOSE_CART");
                                    return new OperationResultVO(
                                            new OperationVO(
                                                    operation,
                                                    "SUCCEEDED",
                                                    row.getVersion(),
                                                    number));
                                }));
    }

    @Override
    public CartSessionGrantVO authorizeSession(
            String entity,
            String store,
            String check,
            String actor,
            AuthorizeCartSessionReq req,
            String key) {
        context(entity, store, actor, key);
        CartInputPolicy.key(check);
        if (req == null) bad();
        CartInputPolicy.key(req.sessionNo());
        CartInputPolicy.version(req.version());
        return scoped(
                () ->
                        command(
                                "GRANT_CART",
                                entity,
                                store,
                                actor,
                                key,
                                List.of(check, req),
                                CartSessionGrantVO.class,
                                () -> {
                                    var snapshot = session(entity, store, req.sessionNo(), false);
                                    if (snapshot.getTableNo() == null) missing();
                                    resources.lock(
                                            entity,
                                            store,
                                            List.of(snapshot.getTableNo()),
                                            null,
                                            true);
                                    check(entity, store, check, true);
                                    var guest = session(entity, store, req.sessionNo(), true);
                                    live(guest);
                                    if (!Objects.equals(snapshot.getTableNo(), guest.getTableNo())
                                            || guest.getCheckNo() != null
                                                    && !check.equals(guest.getCheckNo())
                                            || !Objects.equals(guest.getVersion(), req.version())
                                            || occupancies.activeForCheck(entity, check).stream()
                                                    .noneMatch(
                                                            o ->
                                                                    guest.getTableNo()
                                                                            .equals(
                                                                                    o
                                                                                            .getTableNo())))
                                        conflict();
                                    var scope = json.readTree(guest.getScope());
                                    if (!scope.isArray() || !hasScope(guest, "TABLE_MENU"))
                                        missing();
                                    var next = new ArrayList<String>();
                                    scope.forEach(n -> next.add(n.asText()));
                                    if (!next.contains("CHECK_CART")) next.add("CHECK_CART");
                                    int changed =
                                            sessions.update(
                                                    null,
                                                    Wrappers.<FnbGuestSession>lambdaUpdate()
                                                            .eq(
                                                                    FnbGuestSession::getEntityNo,
                                                                    entity)
                                                            .eq(FnbGuestSession::getStoreNo, store)
                                                            .eq(
                                                                    FnbGuestSession::getSessionNo,
                                                                    req.sessionNo())
                                                            .eq(
                                                                    FnbGuestSession::getVersion,
                                                                    req.version())
                                                            .set(FnbGuestSession::getCheckNo, check)
                                                            .set(
                                                                    FnbGuestSession::getScope,
                                                                    json.writeValueAsString(next))
                                                            .set(
                                                                    FnbGuestSession::getVersion,
                                                                    req.version() + 1)
                                                            .set(
                                                                    FnbGuestSession::getUpdatedBy,
                                                                    actor)
                                                            .set(
                                                                    FnbGuestSession::getUpdatedAt,
                                                                    now()));
                                    if (changed != 1) conflict();
                                    var audit = new FnbActionLog();
                                    audit.setEntityNo(entity);
                                    audit.setStoreNo(store);
                                    audit.setActionNo(BizKey.next("FACT"));
                                    audit.setObjectType("GUEST_SESSION");
                                    audit.setObjectNo(req.sessionNo());
                                    audit.setAction("GRANT_CART");
                                    audit.setOperatorNo(actor);
                                    audit.setRequestNo(FoodCommandFingerprint.hash(key));
                                    actions.insert(audit);
                                    publisher.publish(
                                            new DiningSessionChanged(
                                                    entity,
                                                    store,
                                                    guest.getTableNo(),
                                                    req.sessionNo(),
                                                    "GRANT_CART",
                                                    req.version() + 1,
                                                    audit.getActionNo()),
                                            new PublishOptions(
                                                    "FOOD_GUEST_SESSION:" + req.sessionNo(), 1));
                                    return new CartSessionGrantVO(
                                            req.sessionNo(), check, req.version() + 1);
                                }));
    }

    @Override
    public CartResultVO customerCreateCart(
            String store, CartAccess access, CreateCustomerCartReq req, String key) {
        CartInputPolicy.key(store);
        accessInput(access);
        CartInputPolicy.replayKey(key);
        outer();
        if (req == null) bad();
        if (req.sessionNo() != null && !Objects.equals(req.sessionNo(), access.sessionNo()))
            missing();
        if (req.sessionNo() == null && access.sessionNo() != null) missing();
        if (access.sessionNo() == null) {
            if (access.buyerNo() == null
                    || access.accessToken() != null
                    || req.checkNo() != null
                    || !List.of("PICKUP", "DELIVERY")
                            .contains(req.fulfillment() == null ? "" : req.fulfillment())) bad();
            var card = stores.cards(List.of(store)).get(store);
            if (card == null || !store.equals(card.storeNo()) || !"ACTIVE".equals(card.status()))
                missing();
            String entity = card.entityNo();
            CartInputPolicy.key(entity);
            return scoped(
                    () ->
                            command(
                                    "H5_CREATE_CART",
                                    entity,
                                    store,
                                    access.buyerNo(),
                                    key,
                                    req,
                                    CartResultVO.class,
                                    () -> {
                                        var guest = new FnbGuestSession();
                                        guest.setEntityNo(entity);
                                        guest.setStoreNo(store);
                                        guest.setSessionNo(BizKey.next("FGST"));
                                        guest.setBuyerNo(access.buyerNo());
                                        guest.setTokenHash(digest(BizKey.next("FSEC")));
                                        guest.setScope("[\"CART_H5\"]");
                                        guest.setExpiresAt(now().plusMinutes(30));
                                        sessions.insert(guest);
                                        return new CartResultVO(
                                                view(
                                                        scheduled(
                                                                create(
                                                                        entity,
                                                                        store,
                                                                        access.buyerNo(),
                                                                        null,
                                                                        guest.getSessionNo(),
                                                                        access.buyerNo(),
                                                                        "H5",
                                                                        req.fulfillment(),
                                                                        key),
                                                                req.scheduledAt(),
                                                                access.buyerNo(),
                                                                key),
                                                        true));
                                    }));
        }
        createInput(req.checkNo(), req.sessionNo(), req.fulfillment());
        return scoped(
                () -> {
                    var snapshot = rawSession(access.sessionNo());
                    if (!store.equals(snapshot.getStoreNo())) missing();
                    authenticate(snapshot, access);
                    String entity = snapshot.getEntityNo();
                    String actor = customerActor(snapshot, access);
                    String check = req.checkNo();
                    if (!Objects.equals(check, snapshot.getCheckNo())) missing();
                    if (check != null && !hasScope(snapshot, "CHECK_CART")) missing();
                    if (hasScope(snapshot, "CART_DEVICE")) {
                        if (check != null
                                || !List.of("PICKUP", "DELIVERY").contains(req.fulfillment()))
                            bad();
                    } else if (!hasScope(snapshot, "CART_H5")
                            && !"DINE_IN".equals(req.fulfillment())) bad();
                    return command(
                            "C_CREATE_CART",
                            entity,
                            store,
                            actor,
                            key,
                            req,
                            CartResultVO.class,
                            () -> {
                                owner(entity, store, check, snapshot.getSessionNo(), true);
                                var guest = session(entity, store, snapshot.getSessionNo(), true);
                                authenticate(guest, access);
                                if (!Objects.equals(check, guest.getCheckNo())
                                        || check != null && !hasScope(guest, "CHECK_CART"))
                                    missing();
                                return new CartResultVO(
                                        view(
                                                scheduled(
                                                        create(
                                                                entity,
                                                                store,
                                                                actor,
                                                                check,
                                                                guest.getSessionNo(),
                                                                guest.getBuyerNo(),
                                                                hasScope(guest, "CART_DEVICE")
                                                                        ? "KIOSK"
                                                                        : hasScope(guest, "CART_H5")
                                                                                ? "H5"
                                                                                : "QR",
                                                                req.fulfillment(),
                                                                key),
                                                        req.scheduledAt(),
                                                        actor,
                                                        key),
                                                true));
                            });
                });
    }

    @Override
    public CartResultVO customerGetCart(String number, CartAccess access) {
        CartInputPolicy.key(number);
        accessInput(access);
        outer();
        return scoped(
                () ->
                        read.execute(
                                tx -> {
                                    var row = rawCart(number);
                                    boolean originalBuyerRead =
                                            "CLOSED".equals(row.getStatus())
                                                    && "H5".equals(row.getOrderSource())
                                                    && access.sessionNo() == null
                                                    && access.accessToken() == null
                                                    && access.buyerNo() != null
                                                    && access.buyerNo().equals(row.getBuyerNo())
                                                    && carts.submittedBuyerBindingCount(
                                                                    row.getEntityNo(),
                                                                    row.getStoreNo(),
                                                                    row.getCartNo(),
                                                                    access.buyerNo())
                                                            > 0;
                                    if (!originalBuyerRead) customer(row, access, false);
                                    return new CartResultVO(view(row, true));
                                }));
    }

    @Override
    public CartVersionVO customerReplaceLine(
            String number, String source, CartAccess access, ReplaceCartLineReq req, String key) {
        lineInput(number, source, req);
        accessInput(access);
        CartInputPolicy.replayKey(key);
        outer();
        return scoped(
                () -> {
                    var row = rawCart(number);
                    var guest = customer(row, access, false);
                    return replace(
                            row.getEntityNo(),
                            row.getStoreNo(),
                            number,
                            source,
                            customerActor(guest, access),
                            req,
                            key,
                            access);
                });
    }

    @Override
    public CartVersionVO customerArchiveLine(
            String number, String source, CartAccess access, CartVersionReq req, String key) {
        CartInputPolicy.key(number);
        source(source);
        version(req);
        accessInput(access);
        CartInputPolicy.replayKey(key);
        outer();
        return scoped(
                () -> {
                    var row = rawCart(number);
                    var guest = customer(row, access, false);
                    return archive(
                            row.getEntityNo(),
                            row.getStoreNo(),
                            number,
                            source,
                            customerActor(guest, access),
                            req,
                            key,
                            access);
                });
    }

    private CartVersionVO replace(
            String entity,
            String store,
            String number,
            String source,
            String actor,
            ReplaceCartLineReq req,
            String key,
            CartAccess access) {
        String operation = access == null ? "REPLACE_CART_LINE" : "C_REPLACE_CART_LINE";
        var payload = List.of(number, source, req);
        var replay = receipt(operation, entity, store, actor, key, payload, CartVersionVO.class);
        if (replay.isPresent()) return replay.get();
        var snapshot = head(entity, store, number, false);
        CartLinePolicy.validate(
                req.line(),
                snapshot.getScheduledAt() != null
                        ? catalog.scheduledCart(entity, store, number)
                        : catalog.read(
                                entity,
                                store,
                                new CatalogQuery(
                                        snapshot.getOrderSource(),
                                        snapshot.getFulfillment(),
                                        null)),
                json);
        if (access != null && !"H5".equals(snapshot.getOrderSource())) {
            var guestCatalog =
                    catalog.guest(
                            store, access.sessionNo(), access.accessToken(), access.buyerNo());
            CartLinePolicy.validate(
                    req.line(),
                    snapshot.getScheduledAt() != null
                            ? catalog.scheduledCart(entity, store, number)
                            : guestCatalog,
                    json);
        }
        return command(
                operation,
                entity,
                store,
                actor,
                key,
                payload,
                CartVersionVO.class,
                () -> {
                    var row = locked(entity, store, number, access);
                    editable(row, req.version());
                    var line = req.line();
                    if (line.courseNo() != null) bad();
                    if (line.seatNo() != null
                            && (row.getCheckNo() == null
                                    || seats.selectCount(
                                                    Wrappers.<FnbSeat>lambdaQuery()
                                                            .eq(FnbSeat::getEntityNo, entity)
                                                            .eq(
                                                                    FnbSeat::getCheckNo,
                                                                    row.getCheckNo())
                                                            .eq(FnbSeat::getSeatNo, line.seatNo()))
                                            != 1)) missing();
                    var previous = items.history(entity, number, source);
                    if (previous != null
                            && (!line.goodsNo().equals(previous.getGoodsNo())
                                    || !line.skuNo().equals(previous.getSkuNo()))) conflict();
                    if ((previous == null || Integer.valueOf(1).equals(previous.getDeleted()))
                            && items.selectCount(
                                            Wrappers.<FnbCheckCartItem>lambdaQuery()
                                                    .eq(FnbCheckCartItem::getEntityNo, entity)
                                                    .eq(FnbCheckCartItem::getCartNo, number))
                                    >= 300) conflict();
                    String selection = json.writeValueAsString(new Envelope(1, line));
                    if (previous == null) {
                        var item = new FnbCheckCartItem();
                        item.setEntityNo(entity);
                        item.setCartNo(number);
                        item.setCartItemNo(BizKey.next("FCIT"));
                        item.setSourceLineRef(source);
                        item.setGoodsNo(line.goodsNo());
                        item.setSkuNo(line.skuNo());
                        item.setQty(line.qty());
                        item.setSeatNo(line.seatNo());
                        item.setCourseNo(line.courseNo());
                        item.setSelection(selection);
                        item.setAddedBy(actor);
                        items.insert(item);
                    } else if (items.retain(
                                    entity,
                                    number,
                                    source,
                                    line.qty(),
                                    line.seatNo(),
                                    line.courseNo(),
                                    selection,
                                    0,
                                    actor,
                                    now())
                            != 1) conflict();
                    advance(row, actor, "OPEN");
                    record(row, actor, key, "REPLACE_CART_LINE");
                    return new CartVersionVO(number, row.getVersion());
                });
    }

    private CartVersionVO archive(
            String entity,
            String store,
            String number,
            String source,
            String actor,
            CartVersionReq req,
            String key,
            CartAccess access) {
        return command(
                access == null ? "ARCHIVE_CART_LINE" : "C_ARCHIVE_CART_LINE",
                entity,
                store,
                actor,
                key,
                List.of(number, source, req),
                CartVersionVO.class,
                () -> {
                    var row = locked(entity, store, number, access);
                    editable(row, req.version());
                    var line = items.history(entity, number, source);
                    if (line == null || !Integer.valueOf(0).equals(line.getDeleted())) missing();
                    if (items.retain(
                                    entity,
                                    number,
                                    source,
                                    line.getQty(),
                                    line.getSeatNo(),
                                    line.getCourseNo(),
                                    line.getSelection(),
                                    1,
                                    actor,
                                    now())
                            != 1) conflict();
                    advance(row, actor, "OPEN");
                    record(row, actor, key, "ARCHIVE_CART_LINE");
                    return new CartVersionVO(number, row.getVersion());
                });
    }

    private FnbCheckCart create(
            String entity,
            String store,
            String actor,
            String check,
            String session,
            String buyer,
            String source,
            String fulfillment,
            String key) {
        var filter =
                Wrappers.<FnbCheckCart>lambdaQuery()
                        .eq(FnbCheckCart::getEntityNo, entity)
                        .eq(FnbCheckCart::getStoreNo, store)
                        .ne(FnbCheckCart::getStatus, "CLOSED");
        if (check != null) filter.eq(FnbCheckCart::getCheckNo, check);
        else filter.isNull(FnbCheckCart::getCheckNo).eq(FnbCheckCart::getSessionNo, session);
        var found = carts.selectList(filter.last("FOR UPDATE"));
        if (found.size() > 1) conflict();
        if (!found.isEmpty()) {
            var existing = found.getFirst();
            if (!"OPEN".equals(existing.getStatus())
                    || !fulfillment.equals(existing.getFulfillment())) conflict();
            return existing;
        }
        var row = new FnbCheckCart();
        row.setEntityNo(entity);
        row.setStoreNo(store);
        row.setCartNo(BizKey.next("FCRT"));
        row.setCheckNo(check);
        row.setSessionNo(session);
        row.setBuyerNo(buyer);
        row.setStatus("OPEN");
        row.setOrderSource(source);
        row.setFulfillment(fulfillment);
        row.setVersion(0L);
        carts.insert(row);
        record(row, actor, key, "CREATE_CART");
        return row;
    }

    private FnbCheckCart scheduled(FnbCheckCart cart, Instant at, String actor, String key) {
        if (at == null) {
            if (cart.getScheduledAt() != null) conflict();
            return cart;
        }
        if (serviceSlots == null || !List.of("PICKUP", "DELIVERY").contains(cart.getFulfillment()))
            conflict();
        var local = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        if (cart.getScheduledAt() != null) {
            if (!local.equals(cart.getScheduledAt())) conflict();
            return cart;
        }
        serviceSlots.reserveScheduledCart(
                cart.getEntityNo(),
                cart.getStoreNo(),
                actor,
                cart.getCartNo(),
                at,
                "CART_SLOT_" + cart.getCartNo());
        cart.setScheduledAt(local);
        if (carts.updateById(cart) != 1) conflict();
        return cart;
    }

    private FnbCheckCart locked(String entity, String store, String number, CartAccess access) {
        var snapshot = head(entity, store, number, false);
        owner(
                entity,
                store,
                snapshot.getCheckNo(),
                snapshot.getCheckNo() == null ? snapshot.getSessionNo() : null,
                true);
        if (access != null) customer(snapshot, access, true);
        var row = head(entity, store, number, true);
        if (!Objects.equals(snapshot.getCheckNo(), row.getCheckNo())
                || !Objects.equals(snapshot.getSessionNo(), row.getSessionNo())) conflict();
        return row;
    }

    private void owner(String entity, String store, String check, String session, boolean lock) {
        if (check != null) check(entity, store, check, lock);
        if (session != null) {
            var guest = session(entity, store, session, lock);
            live(guest);
            if (check != null
                    && (!check.equals(guest.getCheckNo()) || !hasScope(guest, "CHECK_CART")))
                missing();
        }
        if (check == null && session == null) bad();
    }

    private FnbCheck check(String entity, String store, String check, boolean lock) {
        var filter =
                Wrappers.<FnbCheck>lambdaQuery()
                        .eq(FnbCheck::getEntityNo, entity)
                        .eq(FnbCheck::getStoreNo, store)
                        .eq(FnbCheck::getCheckNo, check);
        if (lock) filter.last("FOR UPDATE");
        var row = checks.selectOne(filter);
        if (row == null) missing();
        if (!"OPEN".equals(row.getStatus())) conflict();
        if (lock && businessGuard != null) businessGuard.assertMutable(entity, store, check);
        return row;
    }

    private FnbCheckCart head(String entity, String store, String number, boolean lock) {
        var filter =
                Wrappers.<FnbCheckCart>lambdaQuery()
                        .eq(FnbCheckCart::getEntityNo, entity)
                        .eq(FnbCheckCart::getStoreNo, store)
                        .eq(FnbCheckCart::getCartNo, number);
        if (lock) filter.last("FOR UPDATE");
        var row = carts.selectOne(filter);
        if (row == null) missing();
        return row;
    }

    private FnbCheckCart rawCart(String number) {
        var row =
                carts.selectOne(
                        Wrappers.<FnbCheckCart>lambdaQuery().eq(FnbCheckCart::getCartNo, number));
        if (row == null) missing();
        return row;
    }

    private FnbGuestSession rawSession(String number) {
        var row =
                sessions.selectOne(
                        Wrappers.<FnbGuestSession>lambdaQuery()
                                .eq(FnbGuestSession::getSessionNo, number));
        if (row == null) missing();
        return row;
    }

    private FnbGuestSession session(String entity, String store, String number, boolean lock) {
        var filter =
                Wrappers.<FnbGuestSession>lambdaQuery()
                        .eq(FnbGuestSession::getEntityNo, entity)
                        .eq(FnbGuestSession::getStoreNo, store)
                        .eq(FnbGuestSession::getSessionNo, number);
        if (lock) filter.last("FOR UPDATE");
        var row = sessions.selectOne(filter);
        if (row == null) missing();
        return row;
    }

    private FnbGuestSession customer(FnbCheckCart row, CartAccess access, boolean lock) {
        String number = access.sessionNo() == null ? row.getSessionNo() : access.sessionNo();
        if (number == null) missing();
        var guest = session(row.getEntityNo(), row.getStoreNo(), number, lock);
        authenticate(guest, access);
        if (row.getCheckNo() != null) {
            if (!row.getCheckNo().equals(guest.getCheckNo()) || !hasScope(guest, "CHECK_CART"))
                missing();
        } else if (!number.equals(row.getSessionNo())) missing();
        return guest;
    }

    private void authenticate(FnbGuestSession guest, CartAccess access) {
        live(guest);
        if (hasScope(guest, "CART_DEVICE")) {
            if (diningIdentity == null) missing();
            diningIdentity.validateDeviceSession(guest.getSessionNo());
        }
        if (guest.getCheckNo() != null) {
            if (!hasScope(guest, "CHECK_CART")
                    || guest.getTableNo() == null
                    || occupancies.activeForCheck(guest.getEntityNo(), guest.getCheckNo()).stream()
                            .noneMatch(o -> guest.getTableNo().equals(o.getTableNo()))) missing();
            var current =
                    checks.selectOne(
                            Wrappers.<FnbCheck>lambdaQuery()
                                    .eq(FnbCheck::getEntityNo, guest.getEntityNo())
                                    .eq(FnbCheck::getStoreNo, guest.getStoreNo())
                                    .eq(FnbCheck::getCheckNo, guest.getCheckNo())
                                    .eq(FnbCheck::getStatus, "OPEN"));
            if (current == null) missing();
        }
        if (hasScope(guest, "CART_H5")) {
            if (guest.getBuyerNo() == null || !guest.getBuyerNo().equals(access.buyerNo()))
                missing();
        } else {
            if (access.accessToken() == null
                    || guest.getTokenHash() == null
                    || !MessageDigest.isEqual(guest.getTokenHash(), digest(access.accessToken()))
                    || guest.getBuyerNo() != null && !guest.getBuyerNo().equals(access.buyerNo()))
                missing();
        }
    }

    private void live(FnbGuestSession guest) {
        if (guest.getRevokedAt() != null
                || guest.getExpiresAt() == null
                || !guest.getExpiresAt().isAfter(now())) missing();
    }

    private boolean hasScope(FnbGuestSession guest, String value) {
        var scope = json.readTree(guest.getScope());
        if (!scope.isArray()) return false;
        for (var node : scope) if (node.isString() && value.equals(node.asText())) return true;
        return false;
    }

    private String customerActor(FnbGuestSession guest, CartAccess access) {
        return access.buyerNo() == null ? guest.getSessionNo() : access.buyerNo();
    }

    private CartVO view(FnbCheckCart row, boolean customer) {
        var lines =
                items
                        .selectList(
                                Wrappers.<FnbCheckCartItem>lambdaQuery()
                                        .eq(FnbCheckCartItem::getEntityNo, row.getEntityNo())
                                        .eq(FnbCheckCartItem::getCartNo, row.getCartNo())
                                        .orderByAsc(FnbCheckCartItem::getId))
                        .stream()
                        .map(
                                item -> {
                                    var envelope =
                                            json.readValue(item.getSelection(), Envelope.class);
                                    if (envelope.schema() != 1 || envelope.line() == null)
                                        throw new IllegalStateException("Invalid draft");
                                    return envelope.line();
                                })
                        .toList();
        return new CartVO(
                row.getCartNo(),
                row.getStoreNo(),
                row.getCheckNo(),
                row.getSessionNo(),
                customer ? null : row.getBuyerNo(),
                row.getStatus(),
                row.getOrderSource(),
                row.getFulfillment(),
                row.getScheduledAt() == null
                        ? null
                        : row.getScheduledAt().toInstant(ZoneOffset.UTC),
                row.getVersion(),
                lines);
    }

    private void editable(FnbCheckCart row, long version) {
        if (!"OPEN".equals(row.getStatus()) || !Objects.equals(row.getVersion(), version))
            conflict();
        // Caller already holds the original cart row lock; placement takes that same lock before
        // staging.
        if (carts.activePlacementCount(row.getEntityNo(), row.getCartNo()) != 0) conflict();
    }

    private void advance(FnbCheckCart row, String actor, String status) {
        if (row.getVersion() == null || row.getVersion() == Long.MAX_VALUE) conflict();
        int affected =
                carts.update(
                        null,
                        Wrappers.<FnbCheckCart>lambdaUpdate()
                                .eq(FnbCheckCart::getEntityNo, row.getEntityNo())
                                .eq(FnbCheckCart::getStoreNo, row.getStoreNo())
                                .eq(FnbCheckCart::getCartNo, row.getCartNo())
                                .eq(FnbCheckCart::getVersion, row.getVersion())
                                .eq(FnbCheckCart::getStatus, "OPEN")
                                .set(FnbCheckCart::getVersion, row.getVersion() + 1)
                                .set(FnbCheckCart::getStatus, status)
                                .set(FnbCheckCart::getUpdatedBy, actor)
                                .set(FnbCheckCart::getUpdatedAt, now()));
        if (affected != 1) conflict();
        row.setVersion(row.getVersion() + 1);
        row.setStatus(status);
    }

    private String record(FnbCheckCart row, String actor, String key, String operation) {
        var log = new FnbActionLog();
        log.setEntityNo(row.getEntityNo());
        log.setStoreNo(row.getStoreNo());
        log.setActionNo(BizKey.next("FACT"));
        log.setObjectType("CHECK_CART");
        log.setObjectNo(row.getCartNo());
        log.setAction(operation);
        log.setOperatorNo(actor);
        log.setRequestNo(FoodCommandFingerprint.hash(key));
        log.setAfterRef(
                FoodCommandFingerprint.hash(
                        row.getCartNo(), row.getVersion().toString(), row.getStatus()));
        actions.insert(log);
        publisher.publish(
                new CartChanged(
                        row.getEntityNo(),
                        row.getStoreNo(),
                        row.getCartNo(),
                        operation,
                        row.getVersion(),
                        log.getActionNo()),
                new PublishOptions("FOOD_CART:" + row.getCartNo(), 1));
        return log.getActionNo();
    }

    private <T> Optional<T> receipt(
            String operation,
            String entity,
            String store,
            String actor,
            String key,
            Object payload,
            Class<T> type) {
        return read.execute(
                tx ->
                        idem.findCompleted(
                                key,
                                FoodCommandFingerprint.scope(operation, entity, store),
                                actor,
                                FoodCommandFingerprint.hash(
                                        operation, json.writeValueAsString(payload)),
                                type));
    }

    private <T> T command(
            String operation,
            String entity,
            String store,
            String actor,
            String key,
            Object payload,
            Class<T> type,
            Supplier<T> action) {
        var replay = receipt(operation, entity, store, actor, key, payload, type);
        if (replay.isPresent()) return replay.get();
        try {
            return write.execute(
                    tx ->
                            idem.execute(
                                    key,
                                    FoodCommandFingerprint.scope(operation, entity, store),
                                    actor,
                                    FoodCommandFingerprint.hash(
                                            operation, json.writeValueAsString(payload)),
                                    type,
                                    action));
        } catch (ConcurrencyFailureException | DuplicateKeyException concurrent) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static void lineInput(String number, String source, ReplaceCartLineReq req) {
        CartInputPolicy.key(number);
        source(source);
        if (req == null) bad();
        CartInputPolicy.version(req.version());
        CartInputPolicy.line(req.line());
        if (!source.equals(req.line().sourceLineRef())) bad();
    }

    private static void source(String source) {
        if (source == null
                || source.isBlank()
                || !source.equals(source.trim())
                || source.length() > 64
                || source.chars().anyMatch(Character::isISOControl)) bad();
    }

    private static void createInput(String check, String session, String fulfillment) {
        if (check == null && session == null) bad();
        if (check != null) CartInputPolicy.key(check);
        if (session != null) CartInputPolicy.key(session);
        if (!List.of("DINE_IN", "PICKUP", "DELIVERY")
                        .contains(fulfillment == null ? "" : fulfillment)
                || check != null && !"DINE_IN".equals(fulfillment)) bad();
    }

    private static void version(CartVersionReq req) {
        if (req == null) bad();
        CartInputPolicy.version(req.version());
    }

    private static void accessInput(CartAccess access) {
        if (access == null) bad();
        if (access.sessionNo() != null) CartInputPolicy.key(access.sessionNo());
        if (access.buyerNo() != null) CartInputPolicy.key(access.buyerNo());
        if (access.accessToken() != null
                && (access.accessToken().isBlank() || access.accessToken().length() > 256)) bad();
    }

    private static void context(String entity, String store, String actor, String key) {
        query(entity, store);
        CartInputPolicy.key(actor);
        CartInputPolicy.replayKey(key);
    }

    private static void query(String entity, String store) {
        outer();
        CartInputPolicy.key(entity);
        CartInputPolicy.key(store);
    }

    private static void outer() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cart commands require an owning transaction boundary");
    }

    private static <T> T scoped(Supplier<T> body) {
        return DataScopeContext.executeWithoutScope(body);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static byte[] digest(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void missing() {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
