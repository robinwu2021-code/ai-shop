package ai.neargo.shop.invbridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvInboundOrder;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.service.InvManagedService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 线下卖出（TDD-商品纳入进销存开关 §5.2 / §9，第三期）。
 *
 * <p>柜台把货卖掉了，账上要减 —— 否则线上还按原来的实存放货，同一袋米会被再卖一次。
 * 一次卖出 = 一张 {@code OFFLINE_SALE} 出库单，直接过账；撤销 = 一张 {@code OFFLINE_RETURN}
 * 入库单指回原单号。<b>两张单都留着</b>：删单等于账上从没发生过，对不上账时无从查起。
 *
 * <p><b>不记售价、不收款</b>：线下收银是另一件事，这里只回答「货少了几件」。
 * 出库单本来就不带售价（见 {@code OutboundService}），线下卖出也就不进销售额。
 *
 * <p>过账后的写回由 {@link InventoryWritebackConsumer} 那条链接手（{@code DocumentPosted}），
 * 与进货、盘点同一条路 —— 这里不自己调写回。
 */
@Service
@ConditionalOnInventory
public class OfflineSaleService {

    private final InventoryAclService acl;
    private final LocationService locations;
    private final OutboundService outbound;
    private final InboundService inbound;
    private final OutboundOrderMapper orders;
    private final OutboundLineMapper lines;
    private final InboundOrderMapper inbounds;
    private final InvManagedService invManaged;

    public OfflineSaleService(InventoryAclService acl, LocationService locations, OutboundService outbound,
                              InboundService inbound, OutboundOrderMapper orders, OutboundLineMapper lines,
                              InboundOrderMapper inbounds, InvManagedService invManaged) {
        this.acl = acl;
        this.locations = locations;
        this.outbound = outbound;
        this.inbound = inbound;
        this.orders = orders;
        this.lines = lines;
        this.inbounds = inbounds;
        this.invManaged = invManaged;
    }

    /** @param qty 卖出件数，必须为正 —— 负数「卖出」是撤销，走 {@link #revoke} */
    public record SaleLine(String skuNo, int qty) {
    }

    /** 一行卖出记录（当天列表用）。{@code revoked} 为真时这一单已被撤销 */
    public record SaleRow(String docNo, LocalDateTime occurredAt, int totalQty, boolean revoked,
                          List<ItemRow> items) {
    }

    public record ItemRow(String skuNo, String title, String spec, int qty) {
    }

    /**
     * 记一笔线下卖出并当场过账。
     *
     * @return 出库单号
     * @throws BizException {@code BAD_REQUEST} 没有有效行；{@code NOT_FOUND} 这件货在进销存里没有账
     */
    @Transactional
    public String sell(String entityNo, String storeNo, List<SaleLine> saleLines, String remark, String operator) {
        List<SaleLine> valid = saleLines == null ? List.of()
                : saleLines.stream().filter(l -> l.skuNo() != null && l.qty() > 0).toList();
        if (valid.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String ownerId = acl.ownerIdOf(entityNo);
        // 写路径：按需建库位，与进货、盘点同一套解析（只读的 stockLocationOf 在新店上是空）
        String locationId = locations.resolveStockLocation(ownerId, acl.locationIdOf(entityNo, storeNo));
        List<OutboundService.Line> docLines = new ArrayList<>();
        for (SaleLine l : valid) {
            String itemId = acl.itemIdOf(entityNo, l.skuNo());
            if (itemId == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            docLines.add(new OutboundService.Line(itemId, l.qty(), null));
        }
        /*
         * **没有去向，也不挂预留**（§5.2）：柜台卖出不是线上订单，没有占用可对，
         * 挂了预留反而会把线上锁的那几件当成这一笔卖掉的。
         */
        return outbound.postDirectly(new OutboundService.Draft(ownerId, locationId,
                InvEnums.OutboundPurpose.OFFLINE_SALE, null, null, null, LocalDateTime.now(), remark, docLines),
                operator);
    }

    /** 这家店某一天记的线下卖出，新的在前 */
    public List<SaleRow> list(String entityNo, String storeNo, LocalDate date) {
        String ownerId = acl.ownerIdOf(entityNo);
        String locationId = acl.stockLocationOf(entityNo, storeNo);
        if (ownerId == null || locationId == null) {
            return List.of();
        }
        LocalDate day = date == null ? LocalDate.now() : date;
        List<InvOutboundOrder> rows = orders.selectList(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOwnerId, ownerId)
                .eq(InvOutboundOrder::getLocationId, locationId)
                .eq(InvOutboundOrder::getPurpose, InvEnums.OutboundPurpose.OFFLINE_SALE)
                .eq(InvOutboundOrder::getStatus, InvEnums.DocStatus.POSTED)
                .ge(InvOutboundOrder::getOccurredAt, day.atStartOfDay())
                .lt(InvOutboundOrder::getOccurredAt, day.plusDays(1).atStartOfDay())
                .orderByDesc(InvOutboundOrder::getOccurredAt));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> docNos = rows.stream().map(InvOutboundOrder::getOutboundNo).toList();
        Map<String, List<InvOutboundLine>> byDoc = lines.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                        .in(InvOutboundLine::getOutboundNo, docNos)).stream()
                .collect(Collectors.groupingBy(InvOutboundLine::getOutboundNo));
        List<String> revoked = inbounds.selectList(Wrappers.<InvInboundOrder>lambdaQuery()
                        .eq(InvInboundOrder::getOwnerId, ownerId)
                        .eq(InvInboundOrder::getSourceType, InvEnums.InboundSource.OFFLINE_RETURN)
                        .in(InvInboundOrder::getSourceRef, docNos)).stream()
                .map(InvInboundOrder::getSourceRef).toList();

        Map<String, String[]> names = namesOfItems(entityNo);
        // itemId → skuNo：列表要把物料号翻回商品名，一次查完
        Map<String, String> skuOfItem = new LinkedHashMap<>();
        acl.itemIdsOf(entityNo, names.keySet()).forEach((sku, item) -> skuOfItem.put(item, sku));
        List<SaleRow> out = new ArrayList<>();
        for (InvOutboundOrder o : rows) {
            List<ItemRow> items = new ArrayList<>();
            for (InvOutboundLine l : byDoc.getOrDefault(o.getOutboundNo(), List.of())) {
                String skuNo = skuOfItem.get(l.getItemId());
                String[] n = skuNo == null ? null : names.get(skuNo);
                items.add(new ItemRow(skuNo, n == null ? "" : n[0], n == null ? "" : n[1], l.getQty()));
            }
            out.add(new SaleRow(o.getOutboundNo(), o.getOccurredAt(),
                    o.getTotalQty() == null ? 0 : o.getTotalQty(),
                    revoked.contains(o.getOutboundNo()), items));
        }
        return out;
    }

    /**
     * 撤销一笔：按原单的行开一张 {@code OFFLINE_RETURN} 入库单把货加回去。
     *
     * <p><b>不改原单</b>，也<b>不允许撤两次</b>（同一原单已有退回单就拒）—— 撤两次等于凭空多出一批货。
     */
    @Transactional
    public String revoke(String entityNo, String storeNo, String docNo, String operator) {
        String ownerId = acl.ownerIdOf(entityNo);
        String locationId = acl.stockLocationOf(entityNo, storeNo);
        InvOutboundOrder o = orders.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOwnerId, ownerId)
                .eq(InvOutboundOrder::getOutboundNo, docNo)
                .eq(InvOutboundOrder::getPurpose, InvEnums.OutboundPurpose.OFFLINE_SALE)
                .eq(InvOutboundOrder::getStatus, InvEnums.DocStatus.POSTED)
                .last("limit 1"));
        if (o == null || locationId == null || !locationId.equals(o.getLocationId())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        Long done = inbounds.selectCount(Wrappers.<InvInboundOrder>lambdaQuery()
                .eq(InvInboundOrder::getOwnerId, ownerId)
                .eq(InvInboundOrder::getSourceType, InvEnums.InboundSource.OFFLINE_RETURN)
                .eq(InvInboundOrder::getSourceRef, docNo));
        if (done != null && done > 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<InboundService.Line> docLines = lines.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                        .eq(InvOutboundLine::getOutboundNo, docNo)).stream()
                .map(l -> new InboundService.Line(l.getItemId(), l.getQty(), l.getUom(), l.getUnitCostMinor()))
                .toList();
        if (docLines.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return inbound.postDirectly(new InboundService.Draft(ownerId, locationId,
                InvEnums.InboundSource.OFFLINE_RETURN, docNo, null, null, LocalDateTime.now(),
                null, docLines), operator);
    }

    /** skuNo → {商品名, 规格}，只查本主体接入进销存的货 */
    private Map<String, String[]> namesOfItems(String entityNo) {
        List<PrdGoods> goods = invManaged.managedGoods(entityNo);
        Map<String, String> titles = goods.stream()
                .collect(Collectors.toMap(PrdGoods::getGoodsNo, PrdGoods::getTitle, (a, b) -> a));
        Map<String, String[]> out = new LinkedHashMap<>();
        for (PrdSku s : invManaged.skusOf(titles.keySet())) {
            out.put(s.getSkuNo(), new String[]{titles.getOrDefault(s.getGoodsNo(), ""),
                    s.getSpec() == null ? "" : s.getSpec()});
        }
        return out;
    }
}
