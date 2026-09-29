package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.entity.ElcPartMarket;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.mapper.ElecMappers.PartMarketMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierMapper;
import ai.neargo.shop.elec.service.ElecMarketService;
import ai.neargo.shop.elec.support.Bands;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 买家面库存投影的唯一维护者。
 *
 * <p><b>过期不靠定时任务</b>：每行投影记着「最早一行库存到期的时刻」（next_expiry_at），
 * 读路径先把过了这个时刻的重算掉。第一步的量级下这比多一个要登记、要加锁、要有人盯的任务简单，
 * 而正确性一样 —— 买家永远看不到已经到期的库存。
 */
@ConditionalOnElec
@Service
public class ElecMarketServiceImpl implements ElecMarketService {

    private static final Logger log = LoggerFactory.getLogger(ElecMarketServiceImpl.class);

    /** 一次读请求最多顺手重算多少个过期投影。剩下的由下一次读接着做 */
    private static final int STALE_BATCH = 200;

    private final StockMapper stockMapper;
    private final SupplierMapper supplierMapper;
    private final PartMarketMapper marketMapper;
    private final ElecProperties props;
    private final tools.jackson.databind.ObjectMapper json;

    public ElecMarketServiceImpl(StockMapper stockMapper, SupplierMapper supplierMapper,
                             PartMarketMapper marketMapper, ElecProperties props,
                             tools.jackson.databind.ObjectMapper json) {
        this.stockMapper = stockMapper;
        this.supplierMapper = supplierMapper;
        this.marketMapper = marketMapper;
        this.props = props;
        this.json = json;
    }

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public void refresh(Collection<String> partNos) {
        Set<String> seen = new HashSet<>();
        for (String partNo : partNos) {
            if (partNo != null && seen.add(partNo)) {
                refreshOne(partNo);
            }
        }
    }

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public int refreshStale() {
        List<ElcPartMarket> stale = marketMapper.selectList(Wrappers.<ElcPartMarket>lambdaQuery()
                .le(ElcPartMarket::getNextExpiryAt, LocalDateTime.now())
                .last("LIMIT " + STALE_BATCH));
        stale.forEach(m -> refreshOne(m.getPartNo()));
        return stale.size();
    }

    private void refreshOne(String partNo) {
        LocalDate today = LocalDate.now();
        List<ElcStock> rows = stockMapper.selectList(Wrappers.<ElcStock>lambdaQuery()
                .eq(ElcStock::getPartNo, partNo)
                .eq(ElcStock::getStatus, ElcStock.STATUS_ON)
                .ge(ElcStock::getValidUntil, today));
        if (!rows.isEmpty()) {
            // 被暂停的供应商的货不算。运营端暂停一家时就是调这里重算他的全部料号 —— 暂停能生效全靠这一步
            Set<String> active = new HashSet<>();
            supplierMapper.selectList(Wrappers.<ElcSupplier>lambdaQuery()
                            .in(ElcSupplier::getSupplierNo, rows.stream().map(ElcStock::getSupplierNo).toList())
                            .eq(ElcSupplier::getStatus, ElcSupplier.STATUS_ACTIVE))
                    .forEach(s -> active.add(s.getSupplierNo()));
            rows = rows.stream().filter(r -> active.contains(r.getSupplierNo())).toList();
        }
        ElcPartMarket cur = marketMapper.selectOne(Wrappers.<ElcPartMarket>lambdaQuery()
                .eq(ElcPartMarket::getPartNo, partNo));
        if (rows.isEmpty()) {
            if (cur != null) {
                marketMapper.deleteById(cur.getId());
            }
            return;
        }
        long total = 0;
        Set<String> suppliers = new HashSet<>();
        Long minPrice = null;
        Long minPriceQty = null;
        Integer dcMax = null;
        LocalDate firstExpiry = null;
        boolean spot = false;
        Integer leadMin = null;
        java.util.TreeSet<String> conds = new java.util.TreeSet<>();
        for (ElcStock r : rows) {
            total += r.getQty();
            suppliers.add(r.getSupplierNo());
            /*
             * 参考起价取**阶梯里最便宜的那一档**，并记下它从多少起。
             * 只取一个价而不说数量，按 10 片来询的人会拿着 1000 片的价来质问 ——
             * 而那不是报错了，是我们没说清楚。
             */
            Long[] tier = lowestTier(r);
            if (tier != null) {
                long cny = toCnyWithTax(tier[1], r.getCurrency(), r.getTaxIncluded());
                if (minPrice == null || cny < minPrice) {
                    minPrice = cny;
                    minPriceQty = tier[0];
                }
            }
            if (r.getDcYear() != null) {
                dcMax = dcMax == null ? r.getDcYear() : Math.max(dcMax, r.getDcYear());
            }
            if (r.getLeadDays() != null) {
                spot = spot || r.getLeadDays() == 0;
                leadMin = leadMin == null ? r.getLeadDays() : Math.min(leadMin, r.getLeadDays());
            }
            if (r.getCondGrade() != null) {
                conds.add(r.getCondGrade());
            }
            firstExpiry = firstExpiry == null || r.getValidUntil().isBefore(firstExpiry)
                    ? r.getValidUntil() : firstExpiry;
        }
        ElcPartMarket m = cur != null ? cur : new ElcPartMarket();
        m.setPartNo(partNo);
        m.setQtyBand(Bands.qty(total));
        m.setSourceBand(Bands.source(suppliers.size()));
        m.setPriceFromE6(minPrice == null ? null : withMarkup(minPrice));
        m.setPriceFromQty(minPriceQty);
        m.setDcYearMax(dcMax);
        m.setSpot(spot);
        m.setLeadDaysMin(leadMin);
        m.setCondSet(conds.isEmpty() ? null : String.join(",", conds));
        // valid_until 是「最后有效的那一天」，过了那天的零点才算到期
        m.setNextExpiryAt(firstExpiry.plusDays(1).atStartOfDay());
        m.setRefreshedAt(LocalDateTime.now());
        if (cur == null) {
            marketMapper.insert(m);
        } else {
            marketMapper.updateById(m);
        }
    }

    /**
     * 阶梯里最便宜的那一档 → {@code [从多少起, 单价]}。
     *
     * <p>阶梯解不开或为空时回落到 {@code price_e6}（那是最低档的冗余），再没有就是没报价。
     * <b>回落而不是丢掉</b>：一条坏 JSON 不该让这个料号在买家面上变成「有货但无价」。
     */
    Long[] lowestTier(ElcStock r) {
        if (r.getPriceTiers() != null && !r.getPriceTiers().isBlank()) {
            try {
                var tiers = json.readValue(r.getPriceTiers(),
                        new tools.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Long>>>() { });
                Long[] best = null;
                for (var x : tiers) {
                    Long q = x.get("minQty");
                    Long e6 = x.get("e6");
                    if (q != null && e6 != null && (best == null || e6 < best[1])) {
                        best = new Long[]{q, e6};
                    }
                }
                if (best != null) {
                    return best;
                }
            } catch (RuntimeException e) {
                log.warn("阶梯价解不开，回落到 price_e6：stockNo={} {}", r.getStockNo(), e.toString());
            }
        }
        return r.getPriceE6() == null ? null : new Long[]{r.getMoq() == null ? 1L : r.getMoq(), r.getPriceE6()};
    }

    /** 换算成**人民币含税**：买家面只有这一种口径，不然两条报价没法比 */
    @Override
    public long toCnyWithTax(long priceE6, String currency, Boolean taxIncluded) {
        long v = priceE6;
        if ("USD".equals(currency)) {
            v = v * props.getUsdToCnyBp() / 10_000;
        } else if ("HKD".equals(currency)) {
            v = v * props.getHkdToCnyBp() / 10_000;
        }
        if (Boolean.FALSE.equals(taxIncluded)) {
            v = v * (10_000 + props.getVatBp()) / 10_000;
        }
        return v;
    }

    /** 买家看到的是加过价的参考价：按比例加，至少加一个最小值（小单价按比例加出来是 0） */
    @Override
    public long withMarkup(long priceE6) {
        long add = Math.max(priceE6 * props.getMarkupBp() / 10_000, props.getMarkupMinE6());
        return priceE6 + add;
    }
}
