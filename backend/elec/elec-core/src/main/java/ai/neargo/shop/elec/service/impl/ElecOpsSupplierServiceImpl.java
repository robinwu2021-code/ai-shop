package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.OpsDtos.DispatchStats;
import ai.neargo.shop.elec.dto.OpsDtos.OpsSupplierDetail;
import ai.neargo.shop.elec.dto.OpsDtos.OpsSupplierRow;
import ai.neargo.shop.elec.dto.SupplierDtos.RegisterReq;
import ai.neargo.shop.elec.dto.SupplierDtos.StockView;
import ai.neargo.shop.elec.entity.ElcQuote;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.mapper.ElecMappers.DispatchMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.DispatchStatsRow;
import ai.neargo.shop.elec.mapper.ElecMappers.QuoteMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierLastUpload;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierStockStats;
import ai.neargo.shop.elec.service.ElecMarketService;
import ai.neargo.shop.elec.service.ElecOpsSupplierService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 运营端 · 供应商。
 *
 * <h2>暂停为什么要重算投影</h2>
 * 买家看到的是投影表 {@code elc_part_market}，不是库存本身。投影只在重算时才排除暂停的供应商 ——
 * 只改 {@code elc_supplier.status} 的话，他的货会一直挂在买家面上，直到哪天那些料号碰巧被别人的上传触发重算。
 * 所以暂停与恢复都<b>当场</b>重算他名下的全部料号。
 */
@ConditionalOnElec
@Service
public class ElecOpsSupplierServiceImpl implements ElecOpsSupplierService {

    /** 派单响应统计的窗口 */
    static final int STATS_DAYS = 30;

    private static final Set<String> STATUSES = Set.of(ElcSupplier.STATUS_ACTIVE, ElcSupplier.STATUS_SUSPENDED);

    private final SupplierMapper supplierMapper;
    private final StockMapper stockMapper;
    private final StockBatchMapper batchMapper;
    private final DispatchMapper dispatchMapper;
    private final QuoteMapper quoteMapper;
    private final ElecMarketService market;
    private final ElecStockViews views;
    private final TransactionTemplate tx;

    public ElecOpsSupplierServiceImpl(SupplierMapper supplierMapper, StockMapper stockMapper,
                                      StockBatchMapper batchMapper, DispatchMapper dispatchMapper,
                                      QuoteMapper quoteMapper, ElecMarketService market, ElecStockViews views,
                                      @Qualifier("elecTransactionManager") PlatformTransactionManager tm) {
        this.supplierMapper = supplierMapper;
        this.stockMapper = stockMapper;
        this.batchMapper = batchMapper;
        this.dispatchMapper = dispatchMapper;
        this.quoteMapper = quoteMapper;
        this.market = market;
        this.views = views;
        this.tx = new TransactionTemplate(tm);
    }

    @Override
    public List<OpsSupplierRow> list(String keyword, String status, int page, int size) {
        LambdaQueryWrapper<ElcSupplier> q = Wrappers.<ElcSupplier>lambdaQuery();
        if (status != null && STATUSES.contains(status)) {
            q.eq(ElcSupplier::getStatus, status);
        }
        String kw = likeSafe(keyword);
        if (kw != null) {
            q.and(w -> w.like(ElcSupplier::getCompanyName, kw)
                    .or().likeRight(ElcSupplier::getContactPhone, kw)
                    .or().eq(ElcSupplier::getSupplierNo, kw)
                    .or().eq(ElcSupplier::getMaskCode, kw));
        }
        q.orderByDesc(ElcSupplier::getId);
        List<ElcSupplier> rows = supplierMapper.selectList(ElecSupplierServiceImpl.page(q, page, size));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> nos = rows.stream().map(ElcSupplier::getSupplierNo).toList();
        Map<String, SupplierStockStats> stats = stockStats(nos);
        Map<String, LocalDateTime> uploads = lastUploads(nos);
        return rows.stream().map(s -> {
            SupplierStockStats st = stats.get(s.getSupplierNo());
            return new OpsSupplierRow(s.getSupplierNo(), s.getCompanyName(), s.getKind(), s.getCity(),
                    s.getContactName(), s.getContactPhone(), s.getMaskCode(), s.getStatus(),
                    st == null ? 0 : st.getOnCnt().intValue(), st == null ? 0 : st.getExpiringCnt().intValue(),
                    uploads.get(s.getSupplierNo()), s.getCreatedAt());
        }).toList();
    }

    @Override
    public OpsSupplierDetail detail(String supplierNo) {
        return detailOf(or404(supplierNo));
    }

    @Override
    public List<StockView> stocks(String supplierNo, String keyword, String filter, int page, int size) {
        ElcSupplier s = or404(supplierNo);
        LocalDate today = LocalDate.now();
        LambdaQueryWrapper<ElcStock> q = ElecSupplierServiceImpl.stockQuery(s.getSupplierNo(), keyword, filter, today)
                .orderByAsc(ElcStock::getValidUntil).orderByAsc(ElcStock::getMpnNorm);
        return stockMapper.selectList(ElecSupplierServiceImpl.page(q, page, size)).stream()
                .map(r -> views.view(r, today)).toList();
    }

    @Override
    public OpsSupplierDetail update(String staffNo, String supplierNo, RegisterReq req) {
        ElcSupplier s = or404(supplierNo);
        ElecSupplierServiceImpl.applyUpdate(s, req == null ? new RegisterReq(null, null, null, null, null) : req,
                staffNo);
        supplierMapper.updateById(s);
        return detailOf(s);
    }

    @Override
    public OpsSupplierDetail suspend(String staffNo, String supplierNo, String reason) {
        String why = ElecSupplierServiceImpl.trimmed(reason, 255);
        if (why == null || why.length() < 2) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        ElcSupplier s = or404(supplierNo);
        tx.executeWithoutResult(st -> {
            supplierMapper.update(null, Wrappers.<ElcSupplier>lambdaUpdate()
                    .eq(ElcSupplier::getId, s.getId())
                    .set(ElcSupplier::getStatus, ElcSupplier.STATUS_SUSPENDED)
                    .set(ElcSupplier::getSuspendReason, why)
                    .set(ElcSupplier::getSuspendedAt, LocalDateTime.now())
                    .set(ElcSupplier::getUpdatedBy, staffNo));
            market.refresh(partsOf(s.getSupplierNo()));
        });
        return detail(supplierNo);
    }

    @Override
    public OpsSupplierDetail resume(String staffNo, String supplierNo) {
        ElcSupplier s = or404(supplierNo);
        if (ElcSupplier.STATUS_ACTIVE.equals(s.getStatus())) {
            return detailOf(s);
        }
        tx.executeWithoutResult(st -> {
            // 理由与暂停时间留着：「上次为什么停过」下次还用得上
            supplierMapper.update(null, Wrappers.<ElcSupplier>lambdaUpdate()
                    .eq(ElcSupplier::getId, s.getId())
                    .set(ElcSupplier::getStatus, ElcSupplier.STATUS_ACTIVE)
                    .set(ElcSupplier::getUpdatedBy, staffNo));
            market.refresh(partsOf(s.getSupplierNo()));
        });
        return detail(supplierNo);
    }

    // ── 小件 ────────────────────────────────────────────────────────────────

    private OpsSupplierDetail detailOf(ElcSupplier s) {
        String no = s.getSupplierNo();
        SupplierStockStats st = stockStats(List.of(no)).get(no);
        LocalDateTime since = LocalDateTime.now().minusDays(STATS_DAYS);
        DispatchStatsRow d = dispatchMapper.statsOf(no, since);
        long accepted = quoteMapper.selectCount(Wrappers.<ElcQuote>lambdaQuery()
                .eq(ElcQuote::getSupplierNo, no)
                .eq(ElcQuote::getStatus, ElcQuote.STATUS_ACCEPTED)
                .ge(ElcQuote::getCreatedAt, since));
        DispatchStats stats = new DispatchStats(STATS_DAYS, num(d == null ? null : d.getSent()),
                num(d == null ? null : d.getViewed()), num(d == null ? null : d.getResponded()),
                num(d == null ? null : d.getQuoted()), (int) accepted);
        return new OpsSupplierDetail(no, s.getCompanyName(), s.getKind(), s.getCity(), s.getContactName(),
                s.getContactPhone(), s.getMaskCode(), s.getStatus(), s.getSuspendReason(), s.getSuspendedAt(),
                st == null ? 0 : st.getOnCnt().intValue(), st == null ? 0 : st.getExpiringCnt().intValue(),
                st == null ? 0 : st.getExpiredCnt().intValue(), lastUploads(List.of(no)).get(no),
                s.getNotifiedAt() != null, s.getCreatedAt(), stats);
    }

    private Map<String, SupplierStockStats> stockStats(Collection<String> supplierNos) {
        LocalDate today = LocalDate.now();
        return stockMapper.statsBySupplier(supplierNos, today, today.plusDays(ElecSupplierServiceImpl.EXPIRING_DAYS))
                .stream().collect(Collectors.toMap(SupplierStockStats::getSupplierNo, Function.identity()));
    }

    private Map<String, LocalDateTime> lastUploads(Collection<String> supplierNos) {
        return batchMapper.lastAppliedBySupplier(supplierNos).stream()
                .filter(r -> r.getLastAt() != null)
                .collect(Collectors.toMap(SupplierLastUpload::getSupplierNo, SupplierLastUpload::getLastAt));
    }

    /** 他名下挂着在售库存的全部料号 —— 暂停 / 恢复要重算的就是这些 */
    private List<String> partsOf(String supplierNo) {
        return stockMapper.selectList(Wrappers.<ElcStock>lambdaQuery()
                        .select(ElcStock::getPartNo)
                        .eq(ElcStock::getSupplierNo, supplierNo)
                        .eq(ElcStock::getStatus, ElcStock.STATUS_ON))
                .stream().map(ElcStock::getPartNo).distinct().toList();
    }

    private ElcSupplier or404(String supplierNo) {
        ElcSupplier s = supplierMapper.selectOne(Wrappers.<ElcSupplier>lambdaQuery()
                .eq(ElcSupplier::getSupplierNo, supplierNo));
        if (s == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return s;
    }

    /** 关键字进 LIKE 之前去掉 % 与 _：不然「_」一个字符就能把全部供应商拉出来 */
    static String likeSafe(String keyword) {
        if (keyword == null) {
            return null;
        }
        String s = keyword.replace("%", "").replace("_", "").trim();
        return s.isEmpty() ? null : (s.length() > 64 ? s.substring(0, 64) : s);
    }

    private static int num(Long v) {
        return v == null ? 0 : v.intValue();
    }
}
