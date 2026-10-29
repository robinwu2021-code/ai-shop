package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Custody;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Deposit;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Movement;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Result;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Withdraw;
import ai.neargo.shop.food.entity.FnbBottleCustody;
import ai.neargo.shop.food.entity.FnbBottleMovement;
import ai.neargo.shop.food.mapper.BottleCustodyMappers.CustodyMapper;
import ai.neargo.shop.food.mapper.BottleCustodyMappers.MovementMapper;
import ai.neargo.shop.food.service.BottleCustodyService;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.user.CustomerAccountQueryPort;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Physical custody is atomic and auditable; it never posts SKU stock, generates sales or creates
 * customer assets.
 */
public final class BottleCustodyServiceImpl implements BottleCustodyService {
    private final CustodyMapper custodies;
    private final MovementMapper movements;
    private final StoreCardQueryPort stores;
    private final CustomerAccountQueryPort users;
    private final GoodsCatalogQueryPort goods;
    private final MaterialCatalogQueryPort materials;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public BottleCustodyServiceImpl(
            CustodyMapper custodies,
            MovementMapper movements,
            StoreCardQueryPort stores,
            CustomerAccountQueryPort users,
            GoodsCatalogQueryPort goods,
            MaterialCatalogQueryPort materials,
            ObjectMapper json,
            Clock clock,
            PlatformTransactionManager manager) {
        this.custodies = custodies;
        this.movements = movements;
        this.stores = stores;
        this.users = users;
        this.goods = goods;
        this.materials = materials;
        this.json = json;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    @Override
    public Result deposit(String entity, String store, String actor, Deposit request, String key) {
        scope(entity, store);
        text(actor, 32);
        text(key, 128);
        if (request == null) throw bad();
        text(request.customerNo(), 32);
        text(request.skuNo(), 32);
        text(request.unit(), 16);
        humanText(request.reason(), 255);
        text(request.originalReceiptRef(), 128);
        quantity(request.quantity());
        if (request.expiresAt() == null || request.expiresAt().getNano() % 1_000_000 != 0)
            throw bad();
        String hash = PricingSnapshotHash.of(List.of("DEPOSIT", entity, store, actor, request));
        var prior = replay(entity, store, actor, key, hash);
        if (prior != null) return prior;
        if (!request.expiresAt().isAfter(clock.instant())) throw bad();
        ownedStore(entity, store);
        var accountQuery = new CustomerAccountQueryPort.Query(entity, store, request.customerNo());
        var customer = users.find(accountQuery);
        if (customer == null || !accountQuery.equals(customer.query())) throw conflict();
        if (!customer.exists()) throw BizException.of(ErrorCode.NOT_FOUND);
        var sku = goods.getSku(entity, store, request.skuNo());
        if (sku == null
                || !entity.equals(sku.entityNo())
                || !store.equals(sku.storeNo())
                || !request.skuNo().equals(sku.skuNo())) throw conflict();
        var mappings = materials.getSkuMaterials(entity, store, List.of(sku.skuNo()));
        if (mappings == null
                || mappings.size() != 1
                || !sku.skuNo().equals(mappings.getFirst().skuNo())) throw conflict();
        var material = mappings.getFirst().material();
        if (!MaterialCatalogFacts.validFact(material)
                || !entity.equals(material.entityNo())
                || !request.unit().equals(material.baseUom())) throw conflict();
        return command(
                entity,
                store,
                actor,
                key,
                hash,
                () -> {
                    if (!request.expiresAt().isAfter(clock.instant())) throw conflict();
                    var row = new FnbBottleCustody();
                    stamp(row, actor);
                    row.setCustodyNo(BizKey.next("FBC"));
                    row.setEntityNo(entity);
                    row.setStoreNo(store);
                    row.setCustomerNo(request.customerNo());
                    row.setSkuNo(request.skuNo());
                    row.setDepositedQty(request.quantity());
                    row.setRemainingQty(request.quantity());
                    row.setUnit(request.unit());
                    row.setExpiresAt(
                            request.expiresAt().atOffset(ZoneOffset.UTC).toLocalDateTime());
                    row.setStatus("ACTIVE");
                    custodies.insert(row);
                    return record(
                            row,
                            actor,
                            request.customerNo(),
                            "DEPOSIT",
                            request.quantity(),
                            request.reason(),
                            request.originalReceiptRef(),
                            key,
                            hash);
                });
    }

    @Override
    public Result withdraw(
            String entity,
            String store,
            String actor,
            String number,
            Withdraw request,
            String key) {
        scope(entity, store);
        text(actor, 32);
        text(number, 32);
        text(key, 128);
        if (request == null
                || request.version() == null
                || request.version() < 0
                || !Set.of("CONSUME", "RETURN", "DISCARD").contains(request.action())) throw bad();
        humanText(request.reason(), 255);
        text(request.recipientNo(), 32);
        quantity(request.quantity());
        String hash =
                PricingSnapshotHash.of(List.of("WITHDRAW", entity, store, actor, number, request));
        var prior = replay(entity, store, actor, key, hash);
        if (prior != null) return prior;
        ownedStore(entity, store);
        return command(
                entity,
                store,
                actor,
                key,
                hash,
                () -> {
                    var row = required(entity, store, number, true);
                    if (row.getVersion().longValue() != request.version().longValue()
                            || !"ACTIVE".equals(row.getStatus())
                            || row.getRemainingQty().compareTo(request.quantity()) < 0
                            || !row.getCustomerNo().equals(request.recipientNo())
                            || "CONSUME".equals(request.action())
                                    && !row.getExpiresAt().isAfter(now())) throw conflict();
                    var remaining = row.getRemainingQty().subtract(request.quantity());
                    if (custodies.update(
                                    Wrappers.<FnbBottleCustody>update()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", entity)
                                            .eq("store_no", store)
                                            .eq("id", row.getId())
                                            .eq("version", request.version())
                                            .ge("remaining_qty", request.quantity())
                                            .set("remaining_qty", remaining)
                                            .set(
                                                    "status",
                                                    remaining.signum() == 0 ? "CLOSED" : "ACTIVE")
                                            .set("updated_at", now())
                                            .set("updated_by", actor)
                                            .setSql("version=version+1"))
                            != 1) throw conflict();
                    row.setRemainingQty(remaining);
                    row.setStatus(remaining.signum() == 0 ? "CLOSED" : "ACTIVE");
                    row.setVersion(Math.addExact(row.getVersion(), 1));
                    return record(
                            row,
                            actor,
                            request.recipientNo(),
                            request.action(),
                            request.quantity(),
                            request.reason(),
                            null,
                            key,
                            hash);
                });
    }

    @Override
    public Custody get(String entity, String store, String number) {
        scope(entity, store);
        text(number, 32);
        return query(() -> view(required(entity, store, number, false)));
    }

    @Override
    public PageData<Custody> list(
            String entity, String store, String customer, int page, int size) {
        scope(entity, store);
        text(customer, 32);
        long offset = offset(page, size);
        return query(
                () -> {
                    var filter =
                            Wrappers.<FnbBottleCustody>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("store_no", store)
                                    .eq("owner_ref", customer);
                    long total = custodies.selectCount(filter);
                    return PageData.of(
                            custodies
                                    .selectList(
                                            filter.orderByDesc("id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(this::view)
                                    .toList(),
                            total,
                            page,
                            size);
                });
    }

    @Override
    public PageData<Movement> movements(
            String entity, String store, String number, int page, int size) {
        scope(entity, store);
        text(number, 32);
        long offset = offset(page, size);
        return query(
                () -> {
                    required(entity, store, number, false);
                    var filter =
                            Wrappers.<FnbBottleMovement>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", entity)
                                    .eq("store_no", store)
                                    .eq("custody_no", number);
                    long total = movements.selectCount(filter);
                    return PageData.of(
                            movements
                                    .selectList(
                                            filter.orderByAsc("id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(this::view)
                                    .toList(),
                            total,
                            page,
                            size);
                });
    }

    private Result record(
            FnbBottleCustody custody,
            String actor,
            String recipient,
            String action,
            BigDecimal quantity,
            String reason,
            String original,
            String key,
            String hash) {
        var row = new FnbBottleMovement();
        stamp(row, actor);
        row.setMovementNo(BizKey.next("FBCM"));
        row.setEntityNo(custody.getEntityNo());
        row.setStoreNo(custody.getStoreNo());
        row.setCustodyNo(custody.getCustodyNo());
        row.setActorNo(actor);
        row.setRecipientNo(recipient);
        row.setAction(action);
        row.setQuantity(quantity);
        row.setRemainingQty(custody.getRemainingQty());
        row.setUnit(custody.getUnit());
        row.setReason(reason);
        row.setOriginalReceiptRef(original);
        row.setKeyHash(PricingSnapshotHash.of(key));
        row.setRequestHash(hash);
        var result = new Result(view(custody), view(row));
        row.setResultSnapshot(json.writeValueAsString(result));
        movements.insert(row);
        return result;
    }

    private Result command(
            String entity,
            String store,
            String actor,
            String key,
            String hash,
            Supplier<Result> action) {
        try {
            return DataScopeContext.executeWithoutScope(
                    () ->
                            write.execute(
                                    tx -> {
                                        var prior = replay(entity, store, actor, key, hash);
                                        return prior == null ? action.get() : prior;
                                    }));
        } catch (DuplicateKeyException concurrent) {
            var prior = replay(entity, store, actor, key, hash);
            if (prior == null) throw conflict();
            return prior;
        }
    }

    private Result replay(String entity, String store, String actor, String key, String hash) {
        return query(
                () -> {
                    var row =
                            movements.selectOne(
                                    Wrappers.<FnbBottleMovement>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("entity_no", entity)
                                            .eq("store_no", store)
                                            .eq("key_hash", PricingSnapshotHash.of(key)));
                    if (row == null) return null;
                    if (!actor.equals(row.getActorNo()) || !hash.equals(row.getRequestHash()))
                        throw conflict();
                    return json.readValue(row.getResultSnapshot(), Result.class);
                });
    }

    private FnbBottleCustody required(String entity, String store, String number, boolean lock) {
        var filter =
                Wrappers.<FnbBottleCustody>query()
                        .eq("tenant_no", "MAIN")
                        .eq("entity_no", entity)
                        .eq("store_no", store)
                        .eq("custody_no", number);
        if (lock) filter.last("FOR UPDATE");
        var row = custodies.selectOne(filter);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private Custody view(FnbBottleCustody row) {
        return new Custody(
                row.getCustodyNo(),
                row.getEntityNo(),
                row.getStoreNo(),
                row.getCustomerNo(),
                row.getSkuNo(),
                row.getDepositedQty(),
                row.getRemainingQty(),
                row.getUnit(),
                row.getExpiresAt().toInstant(ZoneOffset.UTC),
                row.getStatus(),
                row.getVersion());
    }

    private Movement view(FnbBottleMovement row) {
        return new Movement(
                row.getMovementNo(),
                row.getCustodyNo(),
                row.getAction(),
                row.getQuantity(),
                row.getRemainingQty(),
                row.getUnit(),
                row.getActorNo(),
                row.getRecipientNo(),
                row.getReason(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC));
    }

    private void ownedStore(String entity, String store) {
        var card = stores.cards(List.of(store)).get(store);
        if (card == null || !entity.equals(card.entityNo()) || !store.equals(card.storeNo()))
            throw BizException.of(ErrorCode.NOT_FOUND);
    }

    private void stamp(BaseEntity row, String actor) {
        row.setTenantNo("MAIN");
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(now());
        row.setUpdatedAt(now());
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
    }

    private <T> T query(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> action.get()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static void scope(String entity, String store) {
        text(entity, 32);
        text(store, 32);
    }

    private static void text(String value, int max) {
        if (!MaterialCatalogFacts.validKey(value, max)) throw bad();
    }

    private static void humanText(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || !value.equals(value.trim())
                || value.codePoints().anyMatch(Character::isISOControl)) throw bad();
    }

    private static void quantity(BigDecimal value) {
        try {
            InventoryDemandPlanFacts.quantity(value, false);
        } catch (IllegalArgumentException invalid) {
            throw bad();
        }
        if (value.compareTo(new BigDecimal("999999")) > 0) throw bad();
    }

    private static long offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw bad();
        return Math.multiplyExact((long) page - 1, size);
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
