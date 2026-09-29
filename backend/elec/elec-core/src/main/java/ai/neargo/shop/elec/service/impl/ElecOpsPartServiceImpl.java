package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.OpsDtos.OpsPartDetail;
import ai.neargo.shop.elec.dto.OpsDtos.OpsPartRow;
import ai.neargo.shop.elec.dto.OpsDtos.OpsStockRow;
import ai.neargo.shop.elec.entity.ElcManufacturer;
import ai.neargo.shop.elec.entity.ElcPart;
import ai.neargo.shop.elec.entity.ElcPartMarket;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.mapper.ElecMappers.ManufacturerMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.PartMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.PartMarketMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.PartStockStats;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierMapper;
import ai.neargo.shop.elec.service.ElecOpsPartService;
import ai.neargo.shop.elec.service.ElecPartService;
import ai.neargo.shop.elec.service.ElecPartService.PartMatch;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 运营端 · 料号与库存。
 *
 * <p>「谁有货」一屏是运营报价时最常看的：精确数量、真名、电话、阶梯价都在。
 * 家数与合计数量与买家面<b>同一个口径</b>（在售、未到期、供应商未暂停），只是不降成档位 ——
 * 两边口径不一的话，运营看到「3 家有货」、买家那边却是「暂无」，会以为投影坏了。
 */
@ConditionalOnElec
@Service
public class ElecOpsPartServiceImpl implements ElecOpsPartService {

    static final int SEARCH_LIMIT = 50;
    static final int SOURCES_LIMIT = 50;

    private final ElecPartService parts;
    private final PartMapper partMapper;
    private final PartMarketMapper marketMapper;
    private final ManufacturerMapper mfrMapper;
    private final StockMapper stockMapper;
    private final SupplierMapper supplierMapper;
    private final ElecStockViews views;

    public ElecOpsPartServiceImpl(ElecPartService parts, PartMapper partMapper, PartMarketMapper marketMapper,
                                  ManufacturerMapper mfrMapper, StockMapper stockMapper,
                                  SupplierMapper supplierMapper, ElecStockViews views) {
        this.parts = parts;
        this.partMapper = partMapper;
        this.marketMapper = marketMapper;
        this.mfrMapper = mfrMapper;
        this.stockMapper = stockMapper;
        this.supplierMapper = supplierMapper;
        this.views = views;
    }

    @Override
    public List<OpsPartRow> search(String q) {
        List<PartMatch> matches = parts.matchParts(q, SEARCH_LIMIT);
        if (matches.isEmpty()) {
            return List.of();
        }
        List<String> nos = matches.stream().map(PartMatch::partNo).toList();
        Map<String, ElcPart> byNo = partMapper.selectList(Wrappers.<ElcPart>lambdaQuery()
                        .in(ElcPart::getPartNo, nos)).stream()
                .collect(Collectors.toMap(ElcPart::getPartNo, Function.identity()));
        Map<String, PartStockStats> stats = stats(nos);
        Map<String, ElcPartMarket> market = market(nos);
        Map<String, String> mfrNames = mfrNames();
        return matches.stream().filter(m -> byNo.containsKey(m.partNo()))
                .map(m -> row(byNo.get(m.partNo()), m.match(), stats, market, mfrNames))
                .toList();
    }

    @Override
    public OpsPartDetail detail(String partNo) {
        ElcPart p = partMapper.selectOne(Wrappers.<ElcPart>lambdaQuery().eq(ElcPart::getPartNo, partNo));
        if (p == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        List<String> one = List.of(partNo);
        Map<String, ElcPartMarket> market = market(one);
        ElcPartMarket m = market.get(partNo);
        return new OpsPartDetail(row(p, null, stats(one), market, mfrNames()), p.getDescription(),
                m == null ? null : m.getQtyBand(), m == null ? null : m.getSourceBand(),
                stockMapper.sourcesOf(partNo, LocalDate.now(), SOURCES_LIMIT).stream().map(views::source).toList());
    }

    @Override
    public List<OpsStockRow> stocks(String q, String supplierNo, String filter, int page, int size) {
        LocalDate today = LocalDate.now();
        String supplier = supplierNo == null || supplierNo.isBlank() ? null : supplierNo.trim();
        LambdaQueryWrapper<ElcStock> w = ElecSupplierServiceImpl.stockQuery(supplier, q, filter, today)
                .orderByAsc(ElcStock::getMpnNorm).orderByAsc(ElcStock::getValidUntil);
        List<ElcStock> rows = stockMapper.selectList(ElecSupplierServiceImpl.page(w, page, size));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, ElcSupplier> suppliers = supplierMapper.selectList(Wrappers.<ElcSupplier>lambdaQuery()
                        .in(ElcSupplier::getSupplierNo, rows.stream().map(ElcStock::getSupplierNo).distinct().toList()))
                .stream().collect(Collectors.toMap(ElcSupplier::getSupplierNo, Function.identity()));
        return rows.stream().map(r -> {
            ElcSupplier s = suppliers.get(r.getSupplierNo());
            return new OpsStockRow(r.getSupplierNo(), s == null ? null : s.getCompanyName(),
                    s == null ? null : s.getStatus(), r.getPartNo(), views.view(r, today));
        }).toList();
    }

    // ── 小件 ────────────────────────────────────────────────────────────────

    private OpsPartRow row(ElcPart p, String match, Map<String, PartStockStats> stats,
                           Map<String, ElcPartMarket> market, Map<String, String> mfrNames) {
        PartStockStats st = stats.get(p.getPartNo());
        ElcPartMarket m = market.get(p.getPartNo());
        return new OpsPartRow(p.getPartNo(), p.getMpn(), p.getMfrCode(), mfrNames.get(p.getMfrCode()),
                p.getMfrNameRaw(), p.getPkg(), p.getStatus(), match,
                st == null ? 0 : st.getSupplierCnt().intValue(),
                st == null || st.getTotalQty() == null ? 0 : st.getTotalQty(),
                m == null ? null : m.getPriceFromE6());
    }

    private Map<String, PartStockStats> stats(Collection<String> partNos) {
        return stockMapper.statsByPart(partNos, LocalDate.now()).stream()
                .collect(Collectors.toMap(PartStockStats::getPartNo, Function.identity()));
    }

    private Map<String, ElcPartMarket> market(Collection<String> partNos) {
        return marketMapper.selectList(Wrappers.<ElcPartMarket>lambdaQuery()
                        .in(ElcPartMarket::getPartNo, partNos)).stream()
                .collect(Collectors.toMap(ElcPartMarket::getPartNo, Function.identity()));
    }

    /** 厂牌代码 → 显示名（有中文名用中文名）。厂牌只有几十家，整张读 */
    private Map<String, String> mfrNames() {
        Map<String, String> out = new HashMap<>();
        for (ElcManufacturer m : mfrMapper.selectList(null)) {
            out.put(m.getMfrCode(), m.getNameCn() != null ? m.getNameCn() : m.getNameEn());
        }
        return out;
    }
}
