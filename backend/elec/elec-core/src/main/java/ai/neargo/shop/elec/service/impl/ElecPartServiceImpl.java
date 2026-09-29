package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.PartDtos.LookupLine;
import ai.neargo.shop.elec.dto.PartDtos.Market;
import ai.neargo.shop.elec.dto.PartDtos.PartHit;
import ai.neargo.shop.elec.dto.PartDtos.SearchResult;
import ai.neargo.shop.elec.entity.ElcManufacturer;
import ai.neargo.shop.elec.entity.ElcPart;
import ai.neargo.shop.elec.entity.ElcPartKey;
import ai.neargo.shop.elec.entity.ElcSearchDaily;
import ai.neargo.shop.elec.mapper.ElecMappers.ManufacturerMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.PartHitRow;
import ai.neargo.shop.elec.mapper.ElecMappers.PartKeyMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.PartMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SearchDailyMapper;
import ai.neargo.shop.elec.service.ElecMarketService;
import ai.neargo.shop.elec.service.ElecPartService;
import ai.neargo.shop.elec.support.Mpn;
import ai.neargo.shop.elec.support.Query;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 买家查料号。
 *
 * <p><b>依赖面刻意窄</b>：料号、分段键、投影、搜索日志 —— <b>这个类里没有 StockMapper</b>，
 * 于是买家侧无论怎么改都读不到供应商与精确数量。
 *
 * <h2>一次搜索怎么走</h2>
 * <ol>
 *   <li>拆词：{@code TI TPS5433 2000} → 料号 TPS5433、厂牌 TI（数量在搜索里忽略，批量查才用）</li>
 *   <li>两路候选，都走 B-tree 前缀：料号本身的前缀（EXACT / PREFIX），分段键的前缀（CONTAINS，
 *       「F103C8」「C8T6」这种中段）</li>
 *   <li>写了厂牌就只留那家</li>
 *   <li>排序：命中档（完全一致 → 开头 → 中段）→ 有没有货 → 库存档位高的 → 料号短的 → 字典序</li>
 *   <li>一条都没有、且词够长：逐字符往回退（最多退 4 个），退到有结果为止，标 NEAR ——
 *       采购多敲了一个卷带后缀（…C8T6TR）或少记了尾巴，不该看到一片空白</li>
 *   <li>记一笔搜索需求（按天聚合、不记人）。「搜了没结果」就是平台该去找的货</li>
 * </ol>
 */
@ConditionalOnElec
@Service
public class ElecPartServiceImpl implements ElecPartService {

    private static final Logger log = LoggerFactory.getLogger(ElecPartServiceImpl.class);

    /** 一次最多返回几条。前缀越短命中越多，返回再多也不会有人往下翻 */
    private static final int LIMIT = 30;
    private static final int SUGGEST_LIMIT = 8;
    /** 每一路最多取多少候选再排序。取多了排序白做，取少了有货的可能排不进来 */
    private static final int CANDIDATES = 120;
    /** 近似结果：词至少这么长才退，最多退几个字符，最短退到几位 */
    private static final int NEAR_MIN_INPUT = 5;
    private static final int NEAR_MAX_DROP = 4;
    private static final int NEAR_MIN_PREFIX = 4;
    private static final int LOOKUP_MAX_LINES = 50;

    static final String EXACT = "EXACT";
    static final String PREFIX = "PREFIX";
    static final String CONTAINS = "CONTAINS";
    static final String NEAR = "NEAR";

    private final PartMapper partMapper;
    private final PartKeyMapper keyMapper;
    private final ManufacturerMapper mfrMapper;
    private final SearchDailyMapper dailyMapper;
    private final ElecPartCatalog catalog;
    private final ElecMarketService market;

    public ElecPartServiceImpl(PartMapper partMapper, PartKeyMapper keyMapper, ManufacturerMapper mfrMapper,
                               SearchDailyMapper dailyMapper, ElecPartCatalog catalog, ElecMarketService market) {
        this.partMapper = partMapper;
        this.keyMapper = keyMapper;
        this.mfrMapper = mfrMapper;
        this.dailyMapper = dailyMapper;
        this.catalog = catalog;
        this.market = market;
    }

    // ── 搜索 ────────────────────────────────────────────────────────────────

    @Override
    public SearchResult search(String keyword, boolean suggest) {
        Query.Parsed q = Query.parse(keyword, catalog.aliases());
        String mfrName = q.mfrCode() == null ? null : mfrName(q.mfrCode());
        if (q.mpnNorm().length() < Mpn.MIN_KEY) {
            return new SearchResult(q.mpnNorm(), mfrName, null, List.of());
        }
        market.refreshStale();
        int limit = suggest ? SUGGEST_LIMIT : LIMIT;

        List<PartHit> hits = rank(candidates(q.mpnNorm(), true), q.mpnNorm(), q.mfrCode(), limit, null);
        String nearFrom = null;
        if (hits.isEmpty() && !suggest && q.mpnNorm().length() >= NEAR_MIN_INPUT) {
            int floor = Math.max(NEAR_MIN_PREFIX, q.mpnNorm().length() - NEAR_MAX_DROP);
            for (int n = q.mpnNorm().length() - 1; n >= floor && hits.isEmpty(); n--) {
                String shorter = q.mpnNorm().substring(0, n);
                hits = rank(candidates(shorter, false), shorter, q.mfrCode(), limit, NEAR);
                if (!hits.isEmpty()) {
                    nearFrom = shorter;
                }
            }
        }
        if (!suggest) {
            logDemand(q.mpnNorm(), hits.isEmpty(), !hits.isEmpty() && hits.get(0).market() != null);
        }
        return new SearchResult(q.mpnNorm(), mfrName, nearFrom, hits);
    }

    /** part_no → 命中档。两路都命中时取更靠前的那一档 */
    private Map<String, String> candidates(String norm, boolean withContains) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ElcPart p : partMapper.prefixCandidates(norm, CANDIDATES)) {
            out.put(p.getPartNo(), p.getMpnNorm().equals(norm) ? EXACT : PREFIX);
        }
        if (withContains) {
            for (ElcPartKey k : keyMapper.containCandidates(norm, CANDIDATES)) {
                out.putIfAbsent(k.getPartNo(), CONTAINS);
            }
        }
        return out;
    }

    /**
     * @param forceMatch 非 null 时所有结果都标成这一档（近似结果统一标 NEAR）
     */
    private List<PartHit> rank(Map<String, String> cands, String norm, String mfrCode, int limit,
                               String forceMatch) {
        if (cands.isEmpty()) {
            return List.of();
        }
        List<PartHitRow> rows = new ArrayList<>(partMapper.hitsOf(cands.keySet()));
        if (mfrCode != null) {
            rows.removeIf(r -> !mfrCode.equals(r.getMfrCode()));
        }
        Comparator<PartHitRow> order = Comparator
                .comparingInt((PartHitRow r) -> matchRank(cands.get(r.getPartNo())))
                .thenComparingInt(r -> visible(r) ? 0 : 1)
                .thenComparingInt(r -> -bandRank(visible(r) ? r.getQtyBand() : null))
                .thenComparingInt(r -> r.getMpnNorm().length())
                .thenComparing(PartHitRow::getMpnNorm);
        return rows.stream().sorted(order).limit(limit)
                .map(r -> toHit(r, forceMatch != null ? forceMatch : cands.get(r.getPartNo())))
                .toList();
    }

    private static int matchRank(String m) {
        return switch (m) {
            case EXACT -> 0;
            case PREFIX -> 1;
            default -> 2;
        };
    }

    private static int bandRank(String band) {
        if (band == null) {
            return 0;
        }
        return switch (band) {
            case "B1M" -> 6;
            case "B100K" -> 5;
            case "B10K" -> 4;
            case "B1K" -> 3;
            case "B100" -> 2;
            default -> 1;
        };
    }

    /**
     * 记一笔搜索需求。<b>失败不影响搜索</b> —— 这是统计，不是业务。
     * 先 UPDATE，0 行再 INSERT；两个并发同时 INSERT 时后一个撞唯一键，再 UPDATE 一次。
     */
    private void logDemand(String keyword, boolean zero, boolean stock) {
        try {
            LocalDate today = LocalDate.now();
            int z = zero ? 1 : 0;
            int s = stock ? 1 : 0;
            if (dailyMapper.bump(today, keyword, z, s) > 0) {
                return;
            }
            ElcSearchDaily row = new ElcSearchDaily();
            row.setStatDate(today);
            row.setKeyword(keyword);
            row.setSearchCnt(1);
            row.setZeroCnt(z);
            row.setStockCnt(s);
            try {
                dailyMapper.insert(row);
            } catch (DuplicateKeyException e) {
                dailyMapper.bump(today, keyword, z, s);
            }
        } catch (RuntimeException e) {
            log.warn("元器件搜索需求没记上 keyword={}：{}", keyword, e.toString());
        }
    }

    // ── 批量查 ──────────────────────────────────────────────────────────────

    @Override
    public List<LookupLine> lookup(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = text.lines().map(String::trim).filter(s -> !s.isEmpty()).limit(LOOKUP_MAX_LINES)
                .toList();
        Map<String, String> aliases = catalog.aliases();
        market.refreshStale();
        List<LookupLine> out = new ArrayList<>();
        for (String line : lines) {
            Query.Parsed q = Query.parse(line, aliases);
            if (!Mpn.looksLikeMpn(q.mpnNorm())) {
                out.add(new LookupLine(line, q.mpnRaw(), q.qty(), "NONE", null, 0));
                continue;
            }
            Map<String, String> cands = new LinkedHashMap<>();
            for (ElcPart p : partMapper.prefixCandidates(q.mpnNorm(), CANDIDATES)) {
                cands.put(p.getPartNo(), p.getMpnNorm().equals(q.mpnNorm()) ? EXACT : PREFIX);
            }
            List<PartHit> ranked = rank(cands, q.mpnNorm(), q.mfrCode(), LIMIT, null);
            String match;
            if (ranked.isEmpty()) {
                match = "NONE";
            } else if (!EXACT.equals(ranked.get(0).match())) {
                match = PREFIX;
            } else {
                long exact = ranked.stream().filter(h -> EXACT.equals(h.match())).count();
                // 同一料号多家厂牌、他又没写厂牌：给出最像的那条，但要告诉他「不确定是哪家」
                match = exact > 1 && q.mfrCode() == null ? "AMBIGUOUS" : EXACT;
            }
            logDemand(q.mpnNorm(), ranked.isEmpty(), !ranked.isEmpty() && ranked.get(0).market() != null);
            out.add(new LookupLine(line, q.mpnRaw(), q.qty(), match, ranked.isEmpty() ? null : ranked.get(0),
                    Math.max(0, ranked.size() - 1)));
        }
        return out;
    }

    // ── 详情 ────────────────────────────────────────────────────────────────

    @Override
    public PartHit detail(String partNo) {
        market.refreshStale();
        PartHitRow row = partMapper.findHit(partNo);
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return toHit(row, EXACT);
    }

    private String mfrName(String code) {
        ElcManufacturer m = mfrMapper.selectOne(Wrappers.<ElcManufacturer>lambdaQuery()
                .eq(ElcManufacturer::getMfrCode, code));
        return m == null ? code : (m.getNameCn() != null ? m.getNameCn() : m.getNameEn());
    }

    /** 逗号分隔 → 列表。空的就是空列表（不是 [""]） */
    private static java.util.List<String> conds(String set) {
        if (set == null || set.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(set.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 投影过期了还没来得及重算（一次读最多重算 200 个）时，宁可显示「暂无库存」也不显示过期的货 */
    private static boolean visible(PartHitRow r) {
        return r.getQtyBand() != null && r.getNextExpiryAt() != null
                && r.getNextExpiryAt().isAfter(LocalDateTime.now());
    }

    static PartHit toHit(PartHitRow r, String match) {
        boolean known = !ElcManufacturer.UNKNOWN.equals(r.getMfrCode());
        String mfr = known ? (r.getMfrNameCn() != null ? r.getMfrNameCn() : r.getMfrNameEn()) : r.getMfrNameRaw();
        Market m = visible(r) ? new Market(r.getQtyBand(), r.getSourceBand(), r.getPriceFromE6(),
                r.getPriceFromQty(), r.getDcYearMax(), Boolean.TRUE.equals(r.getSpot()), r.getLeadDaysMin(),
                conds(r.getCondSet())) : null;
        return new PartHit(r.getPartNo(), r.getMpn(), mfr, known, r.getPkg(), r.getDescription(), m, match);
    }
}
