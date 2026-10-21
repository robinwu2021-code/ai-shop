package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.BundleMutationVO;
import ai.neargo.shop.food.dto.CreateBundleReq;
import ai.neargo.shop.food.dto.CreateBundleResultVO;
import ai.neargo.shop.food.dto.GetBundleVO;
import ai.neargo.shop.food.dto.ListBundlesQuery;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbBundle;
import ai.neargo.shop.food.entity.FnbBundleChoice;
import ai.neargo.shop.food.entity.FnbBundleGroup;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleChoiceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BundleMapper;
import ai.neargo.shop.food.policy.BundleDefinitionPolicy;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.BundleChanged;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Original SKUs remain authoritative; only editable serving composition lives in the food primary schema. */
public class BundleServiceImpl implements BundleService {
    private final BundleMapper heads;
    private final BundleGroupMapper groups;
    private final BundleChoiceMapper choices;
    private final ActionLogMapper actions;
    private final IdempotencyService idem;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final GoodsCatalogQueryPort catalog;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public BundleServiceImpl(BundleMapper heads, BundleGroupMapper groups, BundleChoiceMapper choices,
            ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort catalog) {
        this.heads = heads; this.groups = groups; this.choices = choices; this.actions = actions;
        this.idem = idem; this.publisher = publisher; this.json = json; this.catalog = catalog;
        write = new TransactionTemplate(manager);
        // Missing SKU/choice predicates must be range-locked as well as existing heads.
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public CreateBundleResultVO createBundle(String entity, String store, String actor,
            CreateBundleReq req, String key) {
        context(entity, store, actor, key);
        if (req == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        BundleDefinitionPolicy.identifier(req.skuNo());
        String scope = FoodCommandFingerprint.scope("CREATE_BUNDLE", entity, store);
        String hash = FoodCommandFingerprint.hash("create-bundle:v1", req.skuNo());
        var replay = completed(key, scope, actor, hash, CreateBundleResultVO.class);
        if (replay.isPresent()) return replay.get();
        sku(entity, store, req.skuNo());
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, CreateBundleResultVO.class, () -> {
                if (!choices.selectList(Wrappers.<FnbBundleChoice>lambdaQuery()
                        .eq(FnbBundleChoice::getEntityNo, entity).eq(FnbBundleChoice::getSkuNo, req.skuNo())
                        .last("FOR UPDATE")).isEmpty()
                        || !groups.selectList(Wrappers.<FnbBundleGroup>lambdaQuery()
                        .eq(FnbBundleGroup::getEntityNo, entity).eq(FnbBundleGroup::getBaseSkuNo, req.skuNo())
                        .last("FOR UPDATE")).isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
                var row = new FnbBundle();
                row.setEntityNo(entity); row.setSkuNo(req.skuNo()); row.setConfigVersion(0L);
                row.setVersion(0L); row.setStatus("DRAFT");
                heads.insert(row);
                record(entity, store, actor, key, "CREATE_BUNDLE", null, view(entity, row));
                return new CreateBundleResultVO(row.getSkuNo(), row.getVersion(), row.getStatus());
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public BundleMutationVO replaceDefinition(String entity, String store, String number, String actor,
            ReplaceBundleDefinitionReq input, String key) {
        context(entity, store, actor, key);
        BundleDefinitionPolicy.identifier(number);
        var req = BundleDefinitionPolicy.normalize(input);
        String scope = FoodCommandFingerprint.scope("REPLACE_BUNDLE_DEFINITION", entity, store);
        String hash = FoodCommandFingerprint.hash("replace-bundle:v1", number, json.writeValueAsString(req));
        var replay = completed(key, scope, actor, hash, BundleMutationVO.class);
        if (replay.isPresent()) return replay.get();
        var skus = new HashSet<String>(); skus.add(number);
        req.choices().forEach(c -> skus.add(c.skuNo()));
        req.groups().forEach(g -> { if (g.baseSkuNo() != null) skus.add(g.baseSkuNo()); });
        for (var sku : skus.stream().sorted().toList()) sku(entity, store, sku);
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, BundleMutationVO.class, () -> {
                var row = head(entity, number, true);
                if (!req.version().equals(row.getVersion()) || row.getVersion() == Long.MAX_VALUE
                        || row.getConfigVersion() == null || row.getConfigVersion() == Long.MAX_VALUE
                        || !("DRAFT".equals(row.getStatus()) || "ACTIVE".equals(row.getStatus()))) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                var before = view(entity, row);
                skus.remove(number);
                if (req.choices().stream().anyMatch(c -> number.equals(c.skuNo()))
                        || req.groups().stream().anyMatch(g -> number.equals(g.baseSkuNo()))
                        || !skus.isEmpty() && !heads.selectList(Wrappers.<FnbBundle>lambdaQuery()
                        .eq(FnbBundle::getEntityNo, entity).in(FnbBundle::getSkuNo, skus.stream().sorted().toList())
                        .orderByAsc(FnbBundle::getSkuNo).last("FOR UPDATE")).isEmpty()) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
                ownership(entity, number, req);
                choices.replaceDraftRows(entity, number);
                groups.replaceDraftRows(entity, number);
                for (var g : req.groups()) {
                    var child = new FnbBundleGroup(); child.setEntityNo(entity); child.setGroupNo(g.groupNo());
                    child.setBundleSkuNo(number); child.setName(g.name()); child.setMinQty(g.minQty());
                    child.setMaxQty(g.maxQty()); child.setBaseSkuNo(g.baseSkuNo()); child.setSortNo(g.sortNo());
                    groups.insert(child);
                }
                for (var c : req.choices()) {
                    var child = new FnbBundleChoice(); child.setEntityNo(entity); child.setChoiceNo(c.choiceNo());
                    child.setGroupNo(c.groupNo()); child.setSkuNo(c.skuNo()); child.setUnitsPerPick(c.unitsPerPick());
                    child.setPriceMode(c.priceMode()); child.setMinQty(c.minQty()); child.setMaxQty(c.maxQty());
                    child.setDefaultQty(c.defaultQty()); child.setSortNo(c.sortNo()); choices.insert(child);
                }
                row.setConfigVersion(row.getConfigVersion() + 1); row.setStatus("DRAFT");
                if (heads.updateById(row) != 1) throw BizException.of(ErrorCode.CONFLICT);
                record(entity, store, actor, key, "REPLACE_BUNDLE_DEFINITION", before, view(entity, row));
                return new BundleMutationVO(number, row.getVersion());
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void ownership(String entity, String number, ReplaceBundleDefinitionReq req) {
        for (var g : req.groups()) {
            // Global business-key lookup intentionally sees foreign owners only to deny collision.
            var old = groups.selectOne(Wrappers.<FnbBundleGroup>lambdaQuery()
                    .eq(FnbBundleGroup::getGroupNo, g.groupNo()).last("FOR UPDATE"));
            if (old != null && (!entity.equals(old.getEntityNo()) || !number.equals(old.getBundleSkuNo()))) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
        var existingGroups = groups.selectList(Wrappers.<FnbBundleGroup>lambdaQuery()
                .eq(FnbBundleGroup::getEntityNo, entity).eq(FnbBundleGroup::getBundleSkuNo, number)).stream()
                .map(FnbBundleGroup::getGroupNo).toList();
        for (var c : req.choices()) {
            var old = choices.selectOne(Wrappers.<FnbBundleChoice>lambdaQuery()
                    .eq(FnbBundleChoice::getChoiceNo, c.choiceNo()).last("FOR UPDATE"));
            if (old != null && (!entity.equals(old.getEntityNo()) || !existingGroups.contains(old.getGroupNo())
                    || !c.groupNo().equals(old.getGroupNo()) || !c.skuNo().equals(old.getSkuNo()))) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
    }

    @Override
    public GetBundleVO getBundle(String entity, String store, String number) {
        queryContext(entity, store); BundleDefinitionPolicy.identifier(number);
        return read.execute(tx -> new GetBundleVO(view(entity, head(entity, number, false))));
    }

    @Override
    public PageData<BundleConfigVO> listBundles(String entity, String store, ListBundlesQuery query) {
        queryContext(entity, store);
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        if (page < 1 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        return read.execute(tx -> {
            var result = heads.selectPage(new Page<FnbBundle>(page, size), Wrappers.<FnbBundle>lambdaQuery()
                    .eq(FnbBundle::getEntityNo, entity).orderByAsc(FnbBundle::getSkuNo));
            return PageData.of(result.getRecords().stream().map(row -> view(entity, row)).toList(),
                    result.getTotal(), page, size);
        });
    }

    private FnbBundle head(String entity, String number, boolean lock) {
        var query = Wrappers.<FnbBundle>lambdaQuery().eq(FnbBundle::getEntityNo, entity)
                .eq(FnbBundle::getSkuNo, number);
        if (lock) query.last("FOR UPDATE");
        var row = heads.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private BundleConfigVO view(String entity, FnbBundle row) {
        var children = groups.selectList(Wrappers.<FnbBundleGroup>lambdaQuery()
                .eq(FnbBundleGroup::getEntityNo, entity).eq(FnbBundleGroup::getBundleSkuNo, row.getSkuNo())
                .orderByAsc(FnbBundleGroup::getSortNo, FnbBundleGroup::getGroupNo));
        var numbers = children.stream().map(FnbBundleGroup::getGroupNo).toList();
        var options = numbers.isEmpty() ? List.<FnbBundleChoice>of()
                : choices.selectList(Wrappers.<FnbBundleChoice>lambdaQuery().eq(FnbBundleChoice::getEntityNo, entity)
                .in(FnbBundleChoice::getGroupNo, numbers).orderByAsc(FnbBundleChoice::getGroupNo,
                        FnbBundleChoice::getSortNo, FnbBundleChoice::getChoiceNo));
        return new BundleConfigVO(row.getSkuNo(), row.getVersion(), row.getStatus(), new BundleConfigVO.Data(
                row.getConfigVersion(), row.getPublishedRevisionNo(), children.stream()
                .map(g -> new BundleGroupInput(g.getGroupNo(), g.getName(), g.getMinQty(),
                        g.getMaxQty(), g.getBaseSkuNo(), g.getSortNo())).toList(),
                options.stream().map(c -> new BundleChoiceInput(c.getChoiceNo(), c.getGroupNo(), c.getSkuNo(),
                        c.getUnitsPerPick(), c.getPriceMode(), c.getDefaultQty(), c.getMinQty(),
                        c.getMaxQty(), c.getSortNo())).toList()));
    }

    private void sku(String entity, String store, String number) {
        var fact = catalog.getSku(entity, store, number);
        if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                || !number.equals(fact.skuNo()) || fact.goodsNo() == null || fact.goodsNo().isBlank()
                || fact.goodsNo().length() > 32 || !fact.goodsNo().equals(fact.goodsNo().trim()) || fact.version() < 0) {
            throw new IllegalStateException("Invalid original SKU identity");
        }
    }

    private <T> Optional<T> completed(String key, String scope, String actor, String hash, Class<T> type) {
        return read.execute(tx -> idem.findCompleted(key, scope, actor, hash, type));
    }

    private void record(String entity, String store, String actor, String key, String action,
            BundleConfigVO before, BundleConfigVO after) {
        var audit = new FnbActionLog(); audit.setEntityNo(entity); audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT")); audit.setObjectType("BUNDLE"); audit.setObjectNo(after.configNo());
        audit.setAction(action); audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(FoodCommandFingerprint.scope(action, entity, store), key));
        if (before != null) audit.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(before)));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after))); actions.insert(audit);
        publisher.publish(new BundleChanged(entity, store, after.configNo(), action, after.version(),
                audit.getActionNo(), after), new PublishOptions("FOOD_BUNDLE:" + after.configNo(), 1));
    }

    private static void context(String entity, String store, String actor, String key) {
        queryContext(entity, store); BundleDefinitionPolicy.identifier(actor);
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static void queryContext(String entity, String store) {
        BundleDefinitionPolicy.identifier(entity); BundleDefinitionPolicy.identifier(store);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Bundle operations cannot join an existing transaction");
        }
    }
}
