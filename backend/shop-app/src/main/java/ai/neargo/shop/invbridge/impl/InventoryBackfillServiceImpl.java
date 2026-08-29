package ai.neargo.shop.invbridge.impl;

import ai.neargo.shop.invbridge.InventoryBackfillService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.inventory.dto.InventoryVOs.ItemDetailVO;
import ai.neargo.shop.inventory.entity.InvInboundOrder;
import ai.neargo.shop.inventory.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.service.StockQueryService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStockLock;
import ai.neargo.shop.product.entity.PrdStoreStock;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 搬运实现。**读平台、写进销存，两边各自的事务，不跨库**。
 *
 * <p>不加 {@code @Transactional}：跨两个数据源的事务需要 XA，而这件事本来就不需要 ——
 * 每条余额是独立的一笔，搬到一半中断了，重跑会从没搬的那条继续（靠 INIT 单幂等）。
 * 硬套一个事务反而会把「可以分批、可以中断、可以重来」这三个好处全丢掉。
 */
@Service
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class InventoryBackfillServiceImpl implements InventoryBackfillService {

    private static final Logger log = LoggerFactory.getLogger(InventoryBackfillServiceImpl.class);

    private final LocationService locations;
    private final StockLockMapper lockMapper;
    private final ReservationService reservations;
    private final SkuMapper skuMapper;
    private final GoodsMapper goodsMapper;
    private final StoreStockMapper storeStockMapper;
    private final InboundOrderMapper inboundOrderMapper;
    private final InventoryAclService acl;
    private final InboundService inbound;
    private final StockQueryService query;

    public InventoryBackfillServiceImpl(LocationService locations,
                                        StockLockMapper lockMapper, ReservationService reservations,
                                        SkuMapper skuMapper, GoodsMapper goodsMapper,
                                        StoreStockMapper storeStockMapper,
                                        InboundOrderMapper inboundOrderMapper,
                                        InventoryAclService acl, InboundService inbound,
                                        StockQueryService query) {
        this.locations = locations;
        this.lockMapper = lockMapper;
        this.reservations = reservations;
        this.skuMapper = skuMapper;
        this.goodsMapper = goodsMapper;
        this.storeStockMapper = storeStockMapper;
        this.inboundOrderMapper = inboundOrderMapper;
        this.acl = acl;
        this.inbound = inbound;
        this.query = query;
    }

    /** 一次对差最多翻多少轮。防的是「平台侧无限长」把一次日常对差跑成一场全表扫描 */
    private static final int DIFF_PAGES_MAX = 200;

    /**
     * 搬过来的在途预留活多久。与平台的「未支付自动关单」同一个量级 ——
     * 比它短会把还能付款的单先释放掉，比它长会让已关的单继续占着货。
     */
    private static final long HELD_TTL_SECONDS = 30 * 60L;

    /**
     * {@inheritDoc}
     *
     * <p>幂等靠 {@code external_ref}（= {@code lockNo}）：进销存那边同一个 ref
     * 第二次进来会被认出是同一笔。所以重跑安全，中断了下一轮接着来。
     */
    @Override
    public int migrateHeldLocks(int limit) {
        List<PrdStockLock> held = DataScopeContext.executeWithoutScope(() ->
                lockMapper.selectList(Wrappers.<PrdStockLock>lambdaQuery()
                        .eq(PrdStockLock::getStatus, PrdStockLock.LOCKED)
                        .orderByAsc(PrdStockLock::getId)
                        .last("LIMIT " + limit)));

        int moved = 0;
        for (PrdStockLock lock : held) {
            /*
             * **吃预售额度的那些不搬**：它们占的不是现货，而是「明天要采的量」。
             * 进销存里没有「预售额度」这个概念 —— 搬过去会变成占用实存，
             * 而那批货此刻根本不在仓里。
             */
            if (Boolean.TRUE.equals(lock.getPresale())) {
                continue;
            }
            String owner = acl.ownerOfSku(lock.getSkuNo());
            if (owner == null) {
                // 这个 SKU 还没搬过余额 —— 先搬余额再搬它的预留，顺序反了会扣成负数
                continue;
            }
            String locationId = locations.resolveStockLocation(
                    owner, acl.locationOfStore(owner, lock.getStoreNo()));
            try {
                reservations.reserve(owner, lock.getLockNo(),
                        List.of(new ReservationService.Line(
                                acl.itemIdOfSku(lock.getSkuNo()), locationId, nz(lock.getQty()))),
                        HELD_TTL_SECONDS);
                moved++;
            } catch (RuntimeException e) {
                // 已经搬过（同 ref）或此刻可用不足，都不该中断整批
                log.debug("在途预留 {} 未搬：{}", lock.getLockNo(), e.getMessage());
            }
        }
        if (moved > 0) {
            log.info("搬运在途预留 {} 笔 —— 少了这一步，切换那天这些货会重新变成可售", moved);
        }
        return moved;
    }

    @Override
    public Report run(boolean dryRun, int limit, Long afterId) {
        return doRun(dryRun, limit, afterId);
    }

    /**
     * 对差**翻到底**，不是只看一批。
     *
     * <p>一道只抽样的闸门比没有闸门更坏：它给的是「看过的那些没问题」，
     * 而读的人以为是「没问题」。翻不完时 {@code clean} 一律 false 且日志说明 ——
     * <b>不静默截断</b>。
     */
    @Override
    public Report diffOnly(int maxScan) {
        int page = Math.max(1, Math.min(maxScan, 500));
        Long cursor = null;
        int scanned = 0;
        int moved = 0;
        int skipped = 0;
        int pending = 0;
        List<Diff> diffs = new ArrayList<>();

        for (int i = 0; i < DIFF_PAGES_MAX; i++) {
            Report r = doRun(true, page, cursor);
            scanned += r.scannedSkus();
            moved += r.moved();
            skipped += r.skipped();
            pending += r.pending();
            diffs.addAll(r.diffs());
            cursor = r.nextAfterId();
            if (cursor == null) {
                return new Report(scanned, moved, skipped, pending, null, diffs);
            }
            if (scanned >= maxScan) {
                break;
            }
        }
        // 没翻完：**明说**，并强制 clean=false。少报一个 pending 都会让闸门放行
        log.warn("对差未扫完（已扫 {}，上限 {}）—— 本次结论不得当作 G3 判据", scanned, maxScan);
        return new Report(scanned, moved, skipped, pending, false, cursor, diffs);
    }

    private Report doRun(boolean dryRun, int limit, Long afterId) {
        /*
         * 平台的 prd_sku 是「一市场一行」（唯一键 entity_no, sku_no, market），
         * 而**库存不分市场**（货就那么多，卖到哪个市场都是同一批）。
         * 所以按 sku_no 去重，只取第一行 —— 不去重的话，一个 SKU 会被搬 N 遍，
         * 每个市场一遍，而 N 是多少取决于运营开了几个市场。
         */
        Map<String, PrdSku> bySku = new LinkedHashMap<>();
        Long lastId = null;
        boolean exhausted = true;
        List<PrdSku> rows = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .gt(afterId != null, PrdSku::getId, afterId)
                        .orderByAsc(PrdSku::getId).last("LIMIT " + (limit * 4L))));
        for (PrdSku row : rows) {
            lastId = row.getId();
            bySku.putIfAbsent(row.getSkuNo(), row);
            if (bySku.size() >= limit) {
                // 还有没读到的行 —— 下一轮从 lastId 之后继续
                exhausted = false;
                break;
            }
        }
        // 取满了这一页也可能还有下一页（去重后不足 limit，但行数取满了）
        if (rows.size() >= limit * 4L) {
            exhausted = false;
        }

        /*
         * **商品名要一起搬。**
         *
         * 原来 name 传的是 `sku.getGoodsNo()` —— 那是货号。于是商家点开「库存」
         * 看到的是一列 `G0001 · 10斤装`、`G202608172140220000026 · 500g`，
         * 认不出是什么货。可读的名字在 `prd_goods.title` 上，SKU 上没有。
         *
         * **一次查完这一批**，不在循环里逐个查 —— 那是 N+1，一轮 500 个 SKU
         * 就是 500 次往返。
         */
        Set<String> goodsNos = bySku.values().stream()
                .map(PrdSku::getGoodsNo).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> titleByGoodsNo = goodsNos.isEmpty() ? Map.of()
                : DataScopeContext.executeWithoutScope(() ->
                        goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                                        .in(PrdGoods::getGoodsNo, goodsNos))
                                .stream()
                                .filter(g -> g.getTitle() != null && !g.getTitle().isBlank())
                                .collect(Collectors.toMap(PrdGoods::getGoodsNo, PrdGoods::getTitle,
                                        (a, b) -> a)));

        int moved = 0;
        int skipped = 0;
        /*
         * 扫到了但还没搬的。**原来它一个字都不出现** —— moveOne 只算不写时返回 -1，
         * 而这里只统计 1 与 0，于是「还有几百个没搬」在报告里是不可见的，
         * 而 clean 又只看 diffs。切真相源的判据于是守着一个它没在看的东西。
         */
        int pending = 0;
        List<Diff> diffs = new ArrayList<>();

        java.util.Set<String> touchedOwners = new java.util.HashSet<>();
        for (PrdSku sku : bySku.values()) {
            String entityNo = sku.getEntityNo();

            List<PrdStoreStock> storeRows = DataScopeContext.executeWithoutScope(() ->
                    storeStockMapper.selectList(Wrappers.<PrdStoreStock>lambdaQuery()
                            .eq(PrdStoreStock::getSkuNo, sku.getSkuNo())));

            /*
             * **只算不写的时候，一行主数据都不许建。**
             *
             * ownerIdOf / upsertItem / locationIdOf 三个都是「没有就建」，而它们原来
             * 在 dryRun 之前无条件执行 —— 于是一次「只算不写」的排练，在一个空库上
             * 写出了 owner、item、item_ref、location 四类主数据。
             * 2026-08-27 线上排练一次写了 414 行，而报告如实地写着 moved=0。
             *
             * 改成只读反查：主数据都还没有，就是「这个 SKU 一定没搬过」，记 pending。
             */
            String ownerId;
            String itemId;
            if (dryRun) {
                ownerId = acl.ownerOfSku(sku.getSkuNo());
                itemId = acl.itemIdOfSku(sku.getSkuNo());
                if (ownerId == null || itemId == null) {
                    pending += storeRows.isEmpty() ? 1 : storeRows.size();
                    /*
                     * **待搬必须能说出是哪一个。** Report 只带 pending 计数、不带身份，
                     * 于是「哪几个、走的哪条分支」只能靠外部 SQL 反推 ——
                     * 2026-08-29 为此反推了四轮仍对不上。先用日志补上身份，
                     * 代价比改 Report 的 JSON 契约小得多。
                     */
                    log.warn("[对差] 待搬·无物料 sku={} owner={} item={} storeRows={}",
                            sku.getSkuNo(), ownerId, itemId, storeRows.size());
                    continue;
                }
            } else {
                ownerId = acl.ownerIdOf(entityNo);
                // 查不到标题时退回货号 —— 比空名字强，且下一轮商品补了标题就会被改过来
                // （upsertItem 的更新分支会 setName）
                String name = titleByGoodsNo.getOrDefault(sku.getGoodsNo(), sku.getGoodsNo());
                itemId = acl.upsertItem(entityNo, sku.getSkuNo(), name,
                        sku.getSpec(), sku.getBarcode(), sku.getMerchantSkuCode(), sku.getSaleUnit());
                touchedOwners.add(ownerId);
            }

            if (storeRows.isEmpty()) {
                // 主体级：落到默认库位。**这是「主体级库存 = 一个默认库位」在数据上的兑现**
                String locationId = dryRun ? null : acl.locationIdOf(entityNo, null);
                int r = moveOne(ownerId, entityNo, null, sku.getSkuNo(), itemId, locationId,
                        nz(sku.getStock()), nz(sku.getLockedStock()), dryRun, diffs);
                moved += r == 1 ? 1 : 0;
                skipped += r == 0 ? 1 : 0;
                pending += r == -1 ? 1 : 0;
                if (r == -1) {
                    log.warn("[对差] 待搬·主体级 sku={} entity={} qty={} held={}",
                            sku.getSkuNo(), entityNo, nz(sku.getStock()), nz(sku.getLockedStock()));
                }
            } else {
                for (PrdStoreStock st : storeRows) {
                    String locationId = dryRun ? null : acl.locationIdOf(entityNo, st.getStoreNo());
                    int r = moveOne(ownerId, entityNo, st.getStoreNo(), sku.getSkuNo(), itemId,
                            locationId, nz(st.getStock()), nz(st.getLockedStock()), dryRun, diffs);
                    moved += r == 1 ? 1 : 0;
                    skipped += r == 0 ? 1 : 0;
                    pending += r == -1 ? 1 : 0;
                    if (r == -1) {
                        log.warn("[对差] 待搬·门店级 sku={} store={} qty={} held={}",
                                sku.getSkuNo(), st.getStoreNo(),
                                nz(st.getStock()), nz(st.getLockedStock()));
                    }
                }
            }
        }

        if (!dryRun) {
            repairStoreSources(touchedOwners);
        }

        Report report = new Report(bySku.size(), moved, skipped, pending,
                exhausted ? null : lastId, diffs);
        log.info("库存搬运{}：扫描 {} 个 SKU，搬 {} 条，跳过 {} 条，对差 {} 条{}",
                dryRun ? "（只算不写）" : "", report.scannedSkus(), report.moved(),
                report.skipped(), report.diffs().size(), report.clean() ? " —— 干净" : " ★");
        return report;
    }

    /**
     * @return 1=搬了 · 0=已搬过跳过 · -1=只算不写
     */

    /**
     * 把**空的、且没配过发货源的**门店库位指向主体默认仓。
     *
     * <p>为什么要有这一步：门店库位是**懒创建**的 —— 商家第一次点开「库存」那一刻才建出来。
     * 所以「新建时带上发货源」（{@code InventoryAclServiceImpl.locationIdOf}）只管得住以后，
     * 管不住在那次修复之前就已经被创建出来的那些。线上 2026-08-27 就有一个：
     * 门店库位 0 条余额，而两个默认仓里躺着 204 + 2 件，商家点进去是一片空。
     *
     * <p><b>只碰「一件都没有」的库位。</b>门店自己真备了货（有余额）就说明商家是按店管的，
     * 这时把它指向仓库，是把它自己的真数据换成别人的 —— 比看到空列表更糟。
     */
    private void repairStoreSources(java.util.Set<String> owners) {
        for (String ownerId : owners) {
            String defaultLoc = null;
            for (var loc : locations.list(ownerId)) {
                boolean isStore = InvEnums.LocationKind.STORE.equals(loc.getKind());
                boolean noSource = loc.getSourceLocationId() == null || loc.getSourceLocationId().isBlank();
                if (!isStore || !noSource) {
                    continue;
                }
                if (!query.balances(ownerId, loc.getLocationId(), null, 1).isEmpty()) {
                    continue;   // 自己有货 = 按店管，别动
                }
                if (defaultLoc == null) {
                    defaultLoc = locations.defaultLocation(ownerId);
                }
                if (!defaultLoc.equals(loc.getLocationId())) {
                    locations.setSource(ownerId, loc.getLocationId(), defaultLoc, "SYSTEM");
                    log.info("库存搬运：门店库位 {} 是空的，指向主体默认仓 {}", loc.getLocationId(), defaultLoc);
                }
            }
        }
    }

    private int moveOne(String ownerId, String entityNo, String storeNo, String skuNo,
                        String itemId, String locationId, int platformQty, int platformHeld,
                        boolean dryRun, List<Diff> diffs) {
        int inventoryQty = onHandOf(ownerId, itemId);
        int inventoryHeld = reservedOf(ownerId, itemId);
        String sourceRef = skuNo + "|" + (storeNo == null ? "-" : storeNo);
        boolean already = alreadyMoved(ownerId, sourceRef);

        if (already || dryRun) {
            // **对差只在「已经搬过」或「只算不写」时才有意义**：
            // 没搬过的当然对不上，把它算成差异会让报告永远不干净
            /*
             * **实存与预留都要比。** 只比实存的话，「实存一样、预留差 5 件」
             * 会被报成干净 —— 而切过去那 5 件就重新可售了，
             * 是闸门放行之后发生的超卖。
             */
            if (already && (platformQty != inventoryQty || platformHeld != inventoryHeld)) {
                diffs.add(new Diff(entityNo, storeNo, skuNo,
                        platformQty, inventoryQty, platformHeld, inventoryHeld));
            }
            /*
             * **没搬过、但本来就不该搬的，算「跳过」不算「待搬」。**
             *
             * 写路径下面第一件事就是 `if (platformQty <= 0) return 0`
             * （0 库存不落单，理由见那里）。而这里原来一律 return -1，
             * 于是同一个 SKU 在写路径叫「跳过」、在只读对差里叫「待搬」——
             * 两条路对同一件事给了两个答案。
             *
             * 后果不是数字难看，是**闸门恒红**：Report 的 clean 要求 pending==0，
             * 而零库存是常态（每个新建 SKU 在第一次入库前都是 0），
             * 于是「对差连续 N 天为零」这个 D2 判据**永远不可能成立**。
             * 2026-08-29 inv-recon 上线首跑就撞上了：209 个 SKU、差异 0，
             * 却因为 3 个零库存 SKU 报 FAILED。一道永远红的闸门与没有闸门等价。
             *
             * 只对 qty<=0 放行，**held>0 仍算待搬**：那说明有人占着货而进销存侧
             * 一无所知，切过去那部分会重新可售。写路径此处也跳过（它只看 qty），
             * 那是另一笔账，不在这次改动范围内 —— 但只读侧不能跟着一起漏报。
             */
            if (!already && platformQty <= 0 && platformHeld <= 0) {
                return 0;
            }
            return already ? 0 : -1;
        }
        if (platformQty <= 0) {
            // 0 库存不落单：几百个从没进过货的 SKU 会产生几百张「入 0 件」的期初单，
            // 而它们对账上一点用都没有。将来第一次入库自然会建余额行
            return 0;
        }
        inbound.postDirectly(new InboundService.Draft(
                ownerId, locationId, InvEnums.InboundSource.INIT, sourceRef,
                null, LocalDateTime.now(), "存量搬运",
                List.of(new InboundService.Line(itemId, platformQty, null, null))), "SYSTEM");
        return 1;
    }

    /** 幂等标记：这条余额有没有落过 INIT 单。**不看余额是不是 0** —— 搬完之后它会变。 */
    private boolean alreadyMoved(String ownerId, String sourceRef) {
        Long n = inboundOrderMapper.selectCount(Wrappers.<InvInboundOrder>lambdaQuery()
                .eq(InvInboundOrder::getOwnerId, ownerId)
                .eq(InvInboundOrder::getSourceType, InvEnums.InboundSource.INIT)
                .eq(InvInboundOrder::getSourceRef, sourceRef));
        return n != null && n > 0;
    }

    private int onHandOf(String ownerId, String itemId) {
        ItemDetailVO d = query.itemDetail(ownerId, itemId);
        return d == null ? 0 : d.onHand();
    }

    /** 进销存侧的预留量。**对差要比它** —— 只比实存会漏掉「切过去就重新可售」那一类 */
    private int reservedOf(String ownerId, String itemId) {
        ItemDetailVO d = query.itemDetail(ownerId, itemId);
        return d == null ? 0 : d.reserved();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
