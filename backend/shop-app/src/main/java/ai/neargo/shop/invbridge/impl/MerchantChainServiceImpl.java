package ai.neargo.shop.invbridge.impl;

import ai.neargo.shop.invbridge.MerchantChainService;

import ai.neargo.shop.inventory.entity.InvItemRef;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemRefMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 链条画像的实现。**行集由 {@code mch_entity} 驱动**，这一点是有意的：
 *
 * <p>{@code mch_entity} 与 {@code prd_goods} 都在 {@code DataScopeRegistration} 里，
 * 而 {@code inv_*} 是独立库、没有数据域锚点。要是让进销存那侧驱动行集，
 * 配了商家域的 BD 会看到域外商家的进销存数字。
 * <b>让带域的那一侧决定有哪些行，域就只需要在一处生效。</b>
 *
 * <p>每个指标一条聚合查询（group by），不是「每家查五次」——
 * 6 家时两者没区别，600 家时后者是 3000 次往返。
 */
@Service
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class MerchantChainServiceImpl implements MerchantChainService {

    /** 多久没记账算「停了」。与库存健康度的 STALE 是两个判据：那个是单件货，这个是整本账 */
    private static final int STALE_DAYS = 30;

    private final MchEntityMapper merchantMapper;
    private final GoodsMapper goodsMapper;
    private final OwnerMapper ownerMapper;
    private final ItemRefMapper itemRefMapper;
    private final LedgerMapper ledgerMapper;

    public MerchantChainServiceImpl(MchEntityMapper merchantMapper, GoodsMapper goodsMapper,
                                    OwnerMapper ownerMapper, ItemRefMapper itemRefMapper,
                                    LedgerMapper ledgerMapper) {
        this.merchantMapper = merchantMapper;
        this.goodsMapper = goodsMapper;
        this.ownerMapper = ownerMapper;
        this.itemRefMapper = itemRefMapper;
        this.ledgerMapper = ledgerMapper;
    }

    @Override
    public List<ChainRow> profile(int limit, boolean stuckOnly) {
        // 带域的一侧：这一句决定了整页有哪些行
        List<MchEntity> merchants = merchantMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getDeleted, 0)
                .orderByAsc(MchEntity::getEntityNo));
        if (merchants.isEmpty()) {
            return List.of();
        }
        Set<String> entityNos = new java.util.LinkedHashSet<>();
        Map<String, String> names = new HashMap<>();
        for (MchEntity m : merchants) {
            entityNos.add(m.getEntityNo());
            names.put(m.getEntityNo(), m.getName());
        }

        Map<String, long[]> goods = goodsStats(entityNos);
        Map<String, String> ownerOf = ownerIds(entityNos);
        Map<String, Long> itemCount = itemCounts(ownerOf.values());
        Map<String, LocalDateTime[]> ledger = ledgerSpan(ownerOf.values());

        LocalDateTime staleBefore = LocalDateTime.now().minusDays(STALE_DAYS);
        List<ChainRow> out = new ArrayList<>();
        for (String entityNo : entityNos) {
            long[] g = goods.getOrDefault(entityNo, new long[]{0, 0, 0});
            String ownerId = ownerOf.get(entityNo);
            long items = ownerId == null ? 0L : itemCount.getOrDefault(ownerId, 0L);
            LocalDateTime[] span = ownerId == null ? null : ledger.get(ownerId);
            LocalDateTime firstIn = span == null ? null : span[0];
            LocalDateTime last = span == null ? null : span[1];

            String stuck = stuckAt(g, items, firstIn, last, staleBefore);
            if (stuckOnly && stuck == null) {
                continue;
            }
            out.add(new ChainRow(entityNo, names.get(entityNo),
                    g[0], g[1], g[2], items, firstIn, last, stuck));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /**
     * 取**第一个**断掉的环。顺序即链条顺序，改动这段等于改动运营看到的结论。
     */
    private static String stuckAt(long[] g, long items, LocalDateTime firstIn,
                                  LocalDateTime last, LocalDateTime staleBefore) {
        if (g[0] == 0) {
            return Stuck.NO_GOODS;
        }
        if (g[2] == 0) {
            // 全卡在审核，和「审完了没上架」是两回事：前者该催的是平台的审核员
            return g[1] > 0 ? Stuck.IN_AUDIT : Stuck.NOT_ON_SALE;
        }
        if (items == 0) {
            return Stuck.NO_ACCOUNT;
        }
        if (firstIn == null) {
            return Stuck.NO_INBOUND;
        }
        if (last == null || last.isBefore(staleBefore)) {
            return Stuck.STALE_LEDGER;
        }
        return null;
    }

    /** entityNo → [建品, 待审, 上架]。一条查询三个数 */
    private Map<String, long[]> goodsStats(Set<String> entityNos) {
        Map<String, long[]> out = new HashMap<>();
        List<Map<String, Object>> rows = goodsMapper.selectMaps(Wrappers.<PrdGoods>query()
                .select("entity_no",
                        "COUNT(*) AS total",
                        "SUM(CASE WHEN audit_status = 'AUDITING' THEN 1 ELSE 0 END) AS auditing",
                        "SUM(CASE WHEN on_sale = 1 THEN 1 ELSE 0 END) AS on_sale_cnt")
                .eq("deleted", 0)
                .in("entity_no", entityNos)
                .groupBy("entity_no"));
        for (Map<String, Object> r : rows) {
            out.put(str(r.get("entity_no")), new long[]{
                    num(r.get("total")), num(r.get("auditing")), num(r.get("on_sale_cnt"))});
        }
        return out;
    }

    /** entityNo → ownerId。没搬进进销存的商家不在这张表里，那正是 NO_ACCOUNT */
    private Map<String, String> ownerIds(Set<String> entityNos) {
        Map<String, String> out = new HashMap<>();
        for (InvOwner o : ownerMapper.selectList(Wrappers.<InvOwner>lambdaQuery()
                .in(InvOwner::getExternalRef, entityNos))) {
            out.put(o.getExternalRef(), o.getOwnerId());
        }
        return out;
    }

    private Map<String, Long> itemCounts(java.util.Collection<String> ownerIds) {
        Map<String, Long> out = new HashMap<>();
        if (ownerIds.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> rows = itemRefMapper.selectMaps(Wrappers.<InvItemRef>query()
                .select("owner_id", "COUNT(*) AS c")
                .in("owner_id", ownerIds)
                .groupBy("owner_id"));
        for (Map<String, Object> r : rows) {
            out.put(str(r.get("owner_id")), num(r.get("c")));
        }
        return out;
    }

    /**
     * ownerId → [第一笔入库, 最近一笔任意流水]。
     *
     * <p>第一笔**只算入库**（{@code doc_kind = 'IN'}）：「进过货」问的是他有没有真开始用，
     * 而出库可以由订单自动产生 —— 用任意流水的最小值会把「系统替他扣了一笔」
     * 当成「他进过货」。
     */
    private Map<String, LocalDateTime[]> ledgerSpan(java.util.Collection<String> ownerIds) {
        Map<String, LocalDateTime[]> out = new HashMap<>();
        if (ownerIds.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> rows = ledgerMapper.selectMaps(Wrappers.<InvLedger>query()
                .select("owner_id",
                        "MIN(CASE WHEN doc_kind = 'IN' THEN occurred_at END) AS first_in",
                        "MAX(occurred_at) AS last_at")
                .in("owner_id", ownerIds)
                .groupBy("owner_id"));
        for (Map<String, Object> r : rows) {
            out.put(str(r.get("owner_id")),
                    new LocalDateTime[]{time(r.get("first_in")), time(r.get("last_at"))});
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** H2 给 LocalDateTime，MariaDB 驱动给 Timestamp —— 两种都要收 */
    private static LocalDateTime time(Object o) {
        return o instanceof LocalDateTime t ? t
                : o instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null;
    }
}
