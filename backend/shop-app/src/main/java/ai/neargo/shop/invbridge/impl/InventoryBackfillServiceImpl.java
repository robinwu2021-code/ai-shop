package ai.neargo.shop.invbridge.impl;

import ai.neargo.shop.invbridge.InventoryBackfillService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.inventory.dto.InventoryVOs.ItemDetailVO;
import ai.neargo.shop.inventory.entity.InvInboundOrder;
import ai.neargo.shop.inventory.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.StockQueryService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStoreStock;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
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

    private final SkuMapper skuMapper;
    private final StoreStockMapper storeStockMapper;
    private final InboundOrderMapper inboundOrderMapper;
    private final InventoryAclService acl;
    private final InboundService inbound;
    private final StockQueryService query;

    public InventoryBackfillServiceImpl(SkuMapper skuMapper, StoreStockMapper storeStockMapper,
                                        InboundOrderMapper inboundOrderMapper,
                                        InventoryAclService acl, InboundService inbound,
                                        StockQueryService query) {
        this.skuMapper = skuMapper;
        this.storeStockMapper = storeStockMapper;
        this.inboundOrderMapper = inboundOrderMapper;
        this.acl = acl;
        this.inbound = inbound;
        this.query = query;
    }

    @Override
    public Report run(boolean dryRun, int limit) {
        return doRun(dryRun, limit);
    }

    @Override
    public Report diffOnly(int limit) {
        return doRun(true, limit);
    }

    private Report doRun(boolean dryRun, int limit) {
        /*
         * 平台的 prd_sku 是「一市场一行」（唯一键 entity_no, sku_no, market），
         * 而**库存不分市场**（货就那么多，卖到哪个市场都是同一批）。
         * 所以按 sku_no 去重，只取第一行 —— 不去重的话，一个 SKU 会被搬 N 遍，
         * 每个市场一遍，而 N 是多少取决于运营开了几个市场。
         */
        Map<String, PrdSku> bySku = new LinkedHashMap<>();
        for (PrdSku row : DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .orderByAsc(PrdSku::getId).last("LIMIT " + (limit * 4L))))) {
            bySku.putIfAbsent(row.getSkuNo(), row);
            if (bySku.size() >= limit) {
                break;
            }
        }

        int moved = 0;
        int skipped = 0;
        List<Diff> diffs = new ArrayList<>();

        for (PrdSku sku : bySku.values()) {
            String entityNo = sku.getEntityNo();
            String ownerId = acl.ownerIdOf(entityNo);
            String itemId = acl.upsertItem(entityNo, sku.getSkuNo(), sku.getGoodsNo(),
                    sku.getSpec(), sku.getBarcode(), sku.getMerchantSkuCode(), sku.getSaleUnit());

            List<PrdStoreStock> storeRows = DataScopeContext.executeWithoutScope(() ->
                    storeStockMapper.selectList(Wrappers.<PrdStoreStock>lambdaQuery()
                            .eq(PrdStoreStock::getSkuNo, sku.getSkuNo())));

            if (storeRows.isEmpty()) {
                // 主体级：落到默认库位。**这是「主体级库存 = 一个默认库位」在数据上的兑现**
                String locationId = acl.locationIdOf(entityNo, null);
                int r = moveOne(ownerId, entityNo, null, sku.getSkuNo(), itemId, locationId,
                        nz(sku.getStock()), dryRun, diffs);
                moved += r == 1 ? 1 : 0;
                skipped += r == 0 ? 1 : 0;
            } else {
                for (PrdStoreStock st : storeRows) {
                    String locationId = acl.locationIdOf(entityNo, st.getStoreNo());
                    int r = moveOne(ownerId, entityNo, st.getStoreNo(), sku.getSkuNo(), itemId,
                            locationId, nz(st.getStock()), dryRun, diffs);
                    moved += r == 1 ? 1 : 0;
                    skipped += r == 0 ? 1 : 0;
                }
            }
        }

        Report report = new Report(bySku.size(), moved, skipped, diffs);
        log.info("库存搬运{}：扫描 {} 个 SKU，搬 {} 条，跳过 {} 条，对差 {} 条{}",
                dryRun ? "（只算不写）" : "", report.scannedSkus(), report.moved(),
                report.skipped(), report.diffs().size(), report.clean() ? " —— 干净" : " ★");
        return report;
    }

    /**
     * @return 1=搬了 · 0=已搬过跳过 · -1=只算不写
     */
    private int moveOne(String ownerId, String entityNo, String storeNo, String skuNo,
                        String itemId, String locationId, int platformQty,
                        boolean dryRun, List<Diff> diffs) {
        int inventoryQty = onHandOf(ownerId, itemId);
        String sourceRef = skuNo + "|" + (storeNo == null ? "-" : storeNo);
        boolean already = alreadyMoved(ownerId, sourceRef);

        if (already || dryRun) {
            // **对差只在「已经搬过」或「只算不写」时才有意义**：
            // 没搬过的当然对不上，把它算成差异会让报告永远不干净
            if (already && platformQty != inventoryQty) {
                diffs.add(new Diff(entityNo, storeNo, skuNo, platformQty, inventoryQty));
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

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
