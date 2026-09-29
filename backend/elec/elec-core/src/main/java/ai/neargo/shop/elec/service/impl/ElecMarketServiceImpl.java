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

    /** 一次读请求最多顺手重算多少个过期投影。剩下的由下一次读接着做 */
    private static final int STALE_BATCH = 200;

    private final StockMapper stockMapper;
    private final SupplierMapper supplierMapper;
    private final PartMarketMapper marketMapper;
    private final ElecProperties props;

    public ElecMarketServiceImpl(StockMapper stockMapper, SupplierMapper supplierMapper,
                             PartMarketMapper marketMapper, ElecProperties props) {
        this.stockMapper = stockMapper;
        this.supplierMapper = supplierMapper;
        this.marketMapper = marketMapper;
        this.props = props;
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
            // 被暂停的供应商的货不算。第一步没有暂停入口（运营直接改库），但读的一侧先守住
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
        Integer dcMax = null;
        LocalDate firstExpiry = null;
        for (ElcStock r : rows) {
            total += r.getQty();
            suppliers.add(r.getSupplierNo());
            if (r.getPriceE6() != null) {
                long withTax = Boolean.FALSE.equals(r.getTaxIncluded())
                        ? r.getPriceE6() * (10_000 + props.getVatBp()) / 10_000 : r.getPriceE6();
                minPrice = minPrice == null ? withTax : Math.min(minPrice, withTax);
            }
            if (r.getDcYear() != null) {
                dcMax = dcMax == null ? r.getDcYear() : Math.max(dcMax, r.getDcYear());
            }
            firstExpiry = firstExpiry == null || r.getValidUntil().isBefore(firstExpiry)
                    ? r.getValidUntil() : firstExpiry;
        }
        ElcPartMarket m = cur != null ? cur : new ElcPartMarket();
        m.setPartNo(partNo);
        m.setQtyBand(Bands.qty(total));
        m.setSourceBand(Bands.source(suppliers.size()));
        m.setPriceFromE6(minPrice == null ? null : withMarkup(minPrice));
        m.setDcYearMax(dcMax);
        // valid_until 是「最后有效的那一天」，过了那天的零点才算到期
        m.setNextExpiryAt(firstExpiry.plusDays(1).atStartOfDay());
        m.setRefreshedAt(LocalDateTime.now());
        if (cur == null) {
            marketMapper.insert(m);
        } else {
            marketMapper.updateById(m);
        }
    }

    /** 买家看到的是加过价的参考价：按比例加，至少加一个最小值（小单价按比例加出来是 0） */
    long withMarkup(long priceE6) {
        long add = Math.max(priceE6 * props.getMarkupBp() / 10_000, props.getMarkupMinE6());
        return priceE6 + add;
    }
}
