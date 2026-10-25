package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.DeliverySnapshot;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.SaveReq;
import ai.neargo.shop.food.entity.FnbCartDelivery;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.mapper.DiningDeliveryMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningDeliveryService;
import ai.neargo.shop.spi.food.CartChanged;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Authenticates using the original cart owner, then freezes address changes under the original cart
 * CAS lock.
 */
public class DiningDeliveryServiceImpl implements DiningDeliveryService {
    private final CheckCartService carts;
    private final CheckCartMapper cartRows;
    private final DiningDeliveryMapper addresses;
    private final OutboxPublisher outbox;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DiningDeliveryServiceImpl(
            CheckCartService carts,
            CheckCartMapper cartRows,
            DiningDeliveryMapper addresses,
            OutboxPublisher outbox,
            ObjectMapper json,
            PlatformTransactionManager manager,
            Clock clock) {
        this.carts = carts;
        this.cartRows = cartRows;
        this.addresses = addresses;
        this.outbox = outbox;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @Override
    public DeliverySnapshot saveStaff(
            String e, String s, String actor, String cart, SaveReq req, String key) {
        scope(e, s, cart);
        id(actor);
        var authorized = carts.getCart(e, s, cart).cart();
        return save(e, s, actor, cart, authorized, req, key);
    }

    @Override
    public DeliverySnapshot saveCustomer(String cart, CartAccess access, SaveReq req, String key) {
        id(cart);
        if (access == null) {
            bad();
        }
        var authorized = carts.customerGetCart(cart, access).cart();
        String actor = access.sessionNo() != null ? access.sessionNo() : access.buyerNo();
        id(actor);
        return save(null, authorized.storeNo(), actor, cart, authorized, req, key);
    }

    @Override
    public DeliverySnapshot getStaff(String e, String s, String cart) {
        scope(e, s, cart);
        var authorized = carts.getCart(e, s, cart).cart();
        var result = get(e, s, cart);
        if (!authorized.version().equals(result.cartVersion())) {
            conflict();
        }
        return result;
    }

    @Override
    public DeliverySnapshot getCustomer(String cart, CartAccess access) {
        id(cart);
        var authorized = carts.customerGetCart(cart, access).cart();
        var result = read(null, authorized.storeNo(), cart);
        if (!authorized.version().equals(result.cartVersion())) {
            conflict();
        }
        return result;
    }

    @Override
    public DeliverySnapshot get(String e, String s, String cart) {
        scope(e, s, cart);
        return read(e, s, cart);
    }

    private DeliverySnapshot save(
            String expectedEntity,
            String store,
            String actor,
            String cart,
            CartVO authorized,
            SaveReq req,
            String key) {
        if (req == null
                || req.cartVersion() == null
                || req.cartVersion() < 0
                || req.cartVersion() == Long.MAX_VALUE) {
            bad();
        }
        text(key, 128);
        text(req.receiverName(), 64);
        text(req.receiverPhone(), 32);
        text(req.address(), 512);
        if (!req.receiverPhone().matches("[+0-9() .-]{3,32}")) {
            bad();
        }
        if (req.deliveryInstruction() != null) {
            text(req.deliveryInstruction(), 255);
        }
        String hash = FoodCommandFingerprint.hash(json.writeValueAsString(req)),
                keyHash = FoodCommandFingerprint.hash(actor, key);
        return scoped(
                () ->
                        write(
                                () -> {
                                    var row = cart(expectedEntity, store, cart, true);
                                    sameOwner(row, authorized);
                                    var address = address(row.getEntityNo(), store, cart, true);
                                    var history = history(address);
                                    for (var old : history) {
                                        if (keyHash.equals(old.keyHash())) {
                                            if (!hash.equals(old.requestHash())) {
                                                conflict();
                                            }
                                            return old.result();
                                        }
                                    }
                                    if (!"OPEN".equals(row.getStatus())
                                            || !req.cartVersion().equals(row.getVersion())) {
                                        conflict();
                                    }
                                    if (address == null) {
                                        address = new FnbCartDelivery();
                                        init(address, actor);
                                        address.setEntityNo(row.getEntityNo());
                                        address.setStoreNo(store);
                                        address.setCartNo(cart);
                                    }
                                    address.setReceiverName(req.receiverName());
                                    address.setReceiverPhone(req.receiverPhone());
                                    address.setAddress(req.address());
                                    address.setDeliveryInstruction(req.deliveryInstruction());
                                    address.setLatE6(req.latE6());
                                    address.setLngE6(req.lngE6());
                                    stamp(address, actor);
                                    stamp(row, actor);
                                    if (cartRows.updateById(row) != 1) {
                                        conflict();
                                    }
                                    boolean created = address.getId() == null;
                                    // A new address starts at revision zero; every replacement
                                    // advances its own immutable quote fact version.
                                    long revision =
                                            created ? 0 : Math.addExact(address.getVersion(), 1);
                                    var result =
                                            new DeliverySnapshot(
                                                    row.getEntityNo(),
                                                    store,
                                                    cart,
                                                    row.getVersion(),
                                                    revision,
                                                    req.receiverName(),
                                                    req.receiverPhone(),
                                                    req.address(),
                                                    req.deliveryInstruction(),
                                                    req.latE6(),
                                                    req.lngE6());
                                    history.add(new Receipt(keyHash, hash, result));
                                    address.setCommandHistory(json.writeValueAsString(history));
                                    if (created) {
                                        addresses.insert(address);
                                    } else if (addresses.updateById(address) != 1) {
                                        conflict();
                                    }
                                    outbox.publish(
                                            new CartChanged(
                                                    row.getEntityNo(),
                                                    store,
                                                    cart,
                                                    "DELIVERY_ADDRESS_CHANGED",
                                                    row.getVersion(),
                                                    BizKey.next("FDLV")),
                                            new PublishOptions("FOOD_CART:" + cart, 1));
                                    return result;
                                }));
    }

    private DeliverySnapshot read(String e, String s, String no) {
        return scoped(
                () ->
                        write(
                                () -> {
                                    var cart = cart(e, s, no, true);
                                    var address = address(cart.getEntityNo(), s, no, false);
                                    if (address == null) {
                                        throw BizException.of(ErrorCode.NOT_FOUND);
                                    }
                                    return new DeliverySnapshot(
                                            cart.getEntityNo(),
                                            s,
                                            no,
                                            cart.getVersion(),
                                            address.getVersion(),
                                            address.getReceiverName(),
                                            address.getReceiverPhone(),
                                            address.getAddress(),
                                            address.getDeliveryInstruction(),
                                            address.getLatE6(),
                                            address.getLngE6());
                                }));
    }

    private FnbCheckCart cart(String e, String s, String no, boolean lock) {
        var row =
                cartRows.selectOne(
                        Wrappers.<FnbCheckCart>lambdaQuery()
                                .eq(e != null, FnbCheckCart::getEntityNo, e)
                                .eq(FnbCheckCart::getStoreNo, s)
                                .eq(FnbCheckCart::getCartNo, no)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!"DELIVERY".equals(row.getFulfillment())) {
            bad();
        }
        return row;
    }

    private FnbCartDelivery address(String e, String s, String no, boolean lock) {
        return addresses.selectOne(
                Wrappers.<FnbCartDelivery>lambdaQuery()
                        .eq(FnbCartDelivery::getEntityNo, e)
                        .eq(FnbCartDelivery::getStoreNo, s)
                        .eq(FnbCartDelivery::getCartNo, no)
                        .last(lock ? "FOR UPDATE" : ""));
    }

    private void sameOwner(FnbCheckCart row, CartVO authorized) {
        if (authorized == null
                || !row.getCartNo().equals(authorized.cartNo())
                || !row.getStoreNo().equals(authorized.storeNo())
                || !Objects.equals(row.getCheckNo(), authorized.checkNo())
                || !Objects.equals(row.getSessionNo(), authorized.sessionNo())
                || !Objects.equals(row.getBuyerNo(), authorized.buyerNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    private record Receipt(String keyHash, String requestHash, DeliverySnapshot result) {}

    private List<Receipt> history(FnbCartDelivery row) {
        return row == null || row.getCommandHistory() == null
                ? new ArrayList<>()
                : new ArrayList<>(
                        Arrays.asList(json.readValue(row.getCommandHistory(), Receipt[].class)));
    }

    private <T> T write(Supplier<T> body) {
        try {
            return tx.execute(t -> body.get());
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static <T> T scoped(Supplier<T> body) {
        return DataScopeContext.executeWithoutScope(body);
    }

    private void init(BaseEntity row, String actor) {
        row.setTenantNo("MAIN");
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(now());
        row.setCreatedBy(actor);
        stamp(row, actor);
    }

    private void stamp(BaseEntity row, String actor) {
        row.setUpdatedAt(now());
        row.setUpdatedBy(actor);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void scope(String e, String s, String no) {
        id(e);
        id(s);
        id(no);
    }

    private static void id(String v) {
        if (v == null || !v.matches("[A-Za-z0-9_.:-]{1,32}")) {
            bad();
        }
    }

    private static void text(String v, int max) {
        if (v == null || v.isBlank() || v.length() > max || v.chars().anyMatch(c -> c < 32)) {
            bad();
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
