package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.MfrDtos.AliasResult;
import ai.neargo.shop.elec.dto.MfrDtos.AliasRow;
import ai.neargo.shop.elec.dto.MfrDtos.MfrReq;
import ai.neargo.shop.elec.dto.MfrDtos.MfrRow;
import ai.neargo.shop.elec.dto.MfrDtos.UnknownMfrRow;
import ai.neargo.shop.elec.entity.ElcManufacturer;
import ai.neargo.shop.elec.entity.ElcMfrAlias;
import ai.neargo.shop.elec.entity.ElcPart;
import ai.neargo.shop.elec.entity.ElcPartKey;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.mapper.ElecMappers.CodeCount;
import ai.neargo.shop.elec.mapper.ElecMappers.ManufacturerMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.MfrAliasMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.PartKeyMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.PartMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.UnknownRawRow;
import ai.neargo.shop.elec.service.ElecMarketService;
import ai.neargo.shop.elec.service.ElecMfrService;
import ai.neargo.shop.elec.support.MfrSuggest;
import ai.neargo.shop.elec.support.Mpn;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 运营端 · 厂牌与别名。
 *
 * <h2>补别名为什么要当场改认既有库存</h2>
 * 「认不出的厂牌」列表是从挂在 UNKNOWN 料号下的库存算出来的。只写别名、不改认的话，
 * 已经在库里的那些行永远挂在 UNKNOWN 下 —— 运营点完那一行不会从列表消失，会以为没点上，再点一次。
 * 买家那边同一个料号也会一直分成「德州仪器」和「厂牌不明」两条。
 *
 * <h2>为什么逐行改认，而不是整条料号换厂牌</h2>
 * <ul>
 *   <li>UNKNOWN 名下一个料号会汇集多家的库存，各家写的厂牌原文可能分属两家厂牌 ——
 *       只有写着这种写法的那几行该搬</li>
 *   <li>目标厂牌名下可能已经有这个料号串，而 {@code (mfr_code, mpn_norm)} 是唯一键 —— 整条换会撞键</li>
 * </ul>
 * 所以逐行走上传用的那条 {@link ElecPartCatalog.Session#resolve}：别名写进去之后它就认得出了，
 * 挂到目标厂牌下已有的料号，或新建一个。UNKNOWN 料号的行搬空了就标 MERGED，并删掉它的分段键
 * （不删的话，买家搜料号中段时会搜出一条空的「厂牌不明」重影 —— 那一路不按状态过滤）。
 */
@ConditionalOnElec
@Service
public class ElecMfrServiceImpl implements ElecMfrService {

    static final int UNKNOWN_DEFAULT = 50;
    static final int UNKNOWN_MAX = 200;
    /** 「认不出的厂牌」最多扫多少组 (原文, 供应商, 料号)。上万组时列表前面几十条的排序已经稳了 */
    static final int UNKNOWN_SCAN = 20_000;

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9]{2,32}$");

    private final ManufacturerMapper mfrMapper;
    private final MfrAliasMapper aliasMapper;
    private final PartMapper partMapper;
    private final PartKeyMapper keyMapper;
    private final StockMapper stockMapper;
    private final ElecPartCatalog catalog;
    private final ElecMarketService market;
    private final TransactionTemplate tx;

    public ElecMfrServiceImpl(ManufacturerMapper mfrMapper, MfrAliasMapper aliasMapper, PartMapper partMapper,
                              PartKeyMapper keyMapper, StockMapper stockMapper, ElecPartCatalog catalog,
                              ElecMarketService market,
                              @Qualifier("elecTransactionManager") PlatformTransactionManager tm) {
        this.mfrMapper = mfrMapper;
        this.aliasMapper = aliasMapper;
        this.partMapper = partMapper;
        this.keyMapper = keyMapper;
        this.stockMapper = stockMapper;
        this.catalog = catalog;
        this.market = market;
        this.tx = new TransactionTemplate(tm);
    }

    // ── 厂牌 ────────────────────────────────────────────────────────────────

    @Override
    public List<MfrRow> list(String q) {
        String kw = ElecOpsSupplierServiceImpl.likeSafe(q);
        var w = Wrappers.<ElcManufacturer>lambdaQuery().orderByAsc(ElcManufacturer::getMfrCode);
        if (kw != null) {
            w.and(x -> x.like(ElcManufacturer::getMfrCode, kw.toUpperCase(Locale.ROOT))
                    .or().like(ElcManufacturer::getNameEn, kw)
                    .or().like(ElcManufacturer::getNameCn, kw));
        }
        Map<String, Long> aliasCnt = counts(aliasMapper.countsByMfr());
        Map<String, Long> partCnt = counts(partMapper.countsByMfr());
        return mfrMapper.selectList(w).stream().map(m -> row(m, aliasCnt, partCnt)).toList();
    }

    @Override
    public MfrRow create(String staffNo, MfrReq req) {
        String code = req == null || req.mfrCode() == null ? null : req.mfrCode().trim().toUpperCase(Locale.ROOT);
        if (code == null || !CODE.matcher(code).matches()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String nameEn = ElecSupplierServiceImpl.trimmed(req.nameEn(), 128);
        if (nameEn == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String nameCn = ElecSupplierServiceImpl.trimmed(req.nameCn(), 64);
        tx.executeWithoutResult(st -> {
            ElcManufacturer m = new ElcManufacturer();
            m.setMfrCode(code);
            m.setNameEn(nameEn);
            m.setNameCn(nameCn);
            m.setStatus(ElcManufacturer.STATUS_ACTIVE);
            m.setCreatedBy(staffNo);
            m.setUpdatedBy(staffNo);
            try {
                mfrMapper.insert(m);
            } catch (DuplicateKeyException e) {
                throw BizException.of(ErrorCode.ELEC_MFR_EXISTS);
            }
            ownNamesAsAliases(code, staffNo, code, nameEn, nameCn);
        });
        return one(code);
    }

    @Override
    public MfrRow rename(String staffNo, String mfrCode, MfrReq req) {
        ElcManufacturer m = activeOr404(mfrCode);
        String nameEn = req == null ? null : ElecSupplierServiceImpl.trimmed(req.nameEn(), 128);
        String nameCn = req == null ? null : ElecSupplierServiceImpl.trimmed(req.nameCn(), 64);
        if (nameEn == null && nameCn == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        tx.executeWithoutResult(st -> {
            var u = Wrappers.<ElcManufacturer>lambdaUpdate().eq(ElcManufacturer::getId, m.getId())
                    .set(ElcManufacturer::getUpdatedBy, staffNo);
            if (nameEn != null) {
                u.set(ElcManufacturer::getNameEn, nameEn);
            }
            if (nameCn != null) {
                u.set(ElcManufacturer::getNameCn, nameCn);
            }
            mfrMapper.update(null, u);
            // 旧名字的别名留着：供应商表里那么写过的，以后还会那么写
            ownNamesAsAliases(m.getMfrCode(), staffNo, nameEn, nameCn);
        });
        return one(m.getMfrCode());
    }

    // ── 别名 ────────────────────────────────────────────────────────────────

    @Override
    public List<AliasRow> aliases(String mfrCode) {
        ElcManufacturer m = or404(mfrCode);
        return aliasMapper.selectList(Wrappers.<ElcMfrAlias>lambdaQuery()
                        .eq(ElcMfrAlias::getMfrCode, m.getMfrCode())
                        .orderByAsc(ElcMfrAlias::getAliasNorm)).stream()
                .map(a -> new AliasRow(a.getAliasNorm(), a.getMfrCode(), a.getSource(), a.getCreatedAt(),
                        a.getCreatedBy()))
                .toList();
    }

    @Override
    public AliasResult addAlias(String staffNo, String mfrCode, String alias) {
        ElcManufacturer m = activeOr404(mfrCode);
        String norm = Mpn.mfrNorm(alias);
        if (norm.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int[] moved = tx.execute(st -> {
            ElcMfrAlias cur = aliasMapper.selectOne(Wrappers.<ElcMfrAlias>lambdaQuery()
                    .eq(ElcMfrAlias::getAliasNorm, norm));
            if (cur != null && !cur.getMfrCode().equals(m.getMfrCode())) {
                throw BizException.of(ErrorCode.ELEC_ALIAS_TAKEN, cur.getMfrCode());
            }
            if (cur == null) {
                insertAlias(norm, m.getMfrCode(), staffNo);
            }
            return reattribute(Set.of(norm), staffNo);
        });
        return new AliasResult(norm, m.getMfrCode(), moved[0], moved[1]);
    }

    @Override
    public List<UnknownMfrRow> unknown(int limit) {
        int n = limit <= 0 ? UNKNOWN_DEFAULT : Math.min(UNKNOWN_MAX, limit);
        /*
         * 按规范化后的写法聚合：「Texas Instrument」「TEXAS INSTRUMENT」「Texas Instrument Inc.」是同一种写法。
         * 规范化只能在 Java 里做，所以 SQL 按 (原文, 供应商, 料号) 分组，这里再并。
         */
        Map<String, Agg> byNorm = new LinkedHashMap<>();
        for (UnknownRawRow r : stockMapper.unknownRows(UNKNOWN_SCAN)) {
            String norm = Mpn.mfrNorm(r.getMfrRaw());
            if (norm.isEmpty()) {
                continue;
            }
            byNorm.computeIfAbsent(norm, k -> new Agg()).add(r);
        }
        if (byNorm.isEmpty()) {
            return List.of();
        }
        Map<String, String> aliases = catalog.aliases();
        Map<String, String> names = new HashMap<>();
        for (ElcManufacturer m : mfrMapper.selectList(null)) {
            names.put(m.getMfrCode(), m.getNameCn() != null ? m.getNameCn() : m.getNameEn());
        }
        return byNorm.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Agg> e) -> -e.getValue().rows)
                        .thenComparing(Map.Entry::getKey))
                .limit(n)
                .map(e -> {
                    String code = MfrSuggest.suggest(e.getKey(), aliases);
                    Agg a = e.getValue();
                    return new UnknownMfrRow(e.getKey(), a.sample(), a.rows, a.suppliers.size(), a.parts.size(),
                            code, code == null ? null : names.get(code));
                })
                .toList();
    }

    // ── 改认 ────────────────────────────────────────────────────────────────

    /**
     * 把「厂牌不明」名下、原文规范化后落在 norms 里的库存逐行改认。<b>必须在事务里调</b>，
     * 且别名已经写进去了 —— resolve 靠现读的别名表认厂牌。
     *
     * @return {搬了几行, 碰了几个料号}
     */
    private int[] reattribute(Set<String> norms, String actor) {
        List<ElcStock> rows = stockMapper.underUnknown().stream()
                .filter(r -> norms.contains(Mpn.mfrNorm(r.getMfrRaw())))
                .toList();
        if (rows.isEmpty()) {
            return new int[] {0, 0};
        }
        ElecPartCatalog.Session session = catalog.session();
        session.preload(rows.stream().map(ElcStock::getMpnNorm).distinct().toList());
        Map<String, List<Long>> idsByTarget = new LinkedHashMap<>();
        Map<String, String> oldToNew = new LinkedHashMap<>();
        for (ElcStock r : rows) {
            String target = session.resolve(r.getMpnRaw(), r.getMpnNorm(), r.getMfrRaw(), r.getPkg(), actor);
            if (target.equals(r.getPartNo())) {
                continue;
            }
            idsByTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(r.getId());
            oldToNew.put(r.getPartNo(), target);
        }
        // 新料号先落库：库存行马上要指着它们
        session.flush();
        int moved = 0;
        for (Map.Entry<String, List<Long>> e : idsByTarget.entrySet()) {
            moved += stockMapper.update(null, Wrappers.<ElcStock>lambdaUpdate()
                    .in(ElcStock::getId, e.getValue())
                    .set(ElcStock::getPartNo, e.getKey())
                    .set(ElcStock::getUpdatedBy, actor));
        }
        for (Map.Entry<String, String> e : oldToNew.entrySet()) {
            String old = e.getKey();
            long left = stockMapper.selectCount(Wrappers.<ElcStock>lambdaQuery().eq(ElcStock::getPartNo, old));
            if (left > 0) {
                continue;   // 还有别家写着别的原文挂在它下面：留着
            }
            partMapper.update(null, Wrappers.<ElcPart>lambdaUpdate()
                    .eq(ElcPart::getPartNo, old)
                    .set(ElcPart::getStatus, ElcPart.STATUS_MERGED)
                    .set(ElcPart::getMergedInto, e.getValue())
                    .set(ElcPart::getUpdatedBy, actor));
            keyMapper.delete(Wrappers.<ElcPartKey>lambdaQuery().eq(ElcPartKey::getPartNo, old));
        }
        Set<String> touched = new LinkedHashSet<>(oldToNew.keySet());
        touched.addAll(idsByTarget.keySet());
        market.refresh(touched);
        return new int[] {moved, touched.size()};
    }

    /** 厂牌自己的代码与名字登成别名；已被别家占用的写法跳过（不报错：建厂牌不该因为「ST」已被占而失败） */
    private void ownNamesAsAliases(String mfrCode, String actor, String... names) {
        Set<String> added = new HashSet<>();
        for (String name : names) {
            String norm = Mpn.mfrNorm(name);
            if (norm.isEmpty() || added.contains(norm)) {
                continue;
            }
            ElcMfrAlias cur = aliasMapper.selectOne(Wrappers.<ElcMfrAlias>lambdaQuery()
                    .eq(ElcMfrAlias::getAliasNorm, norm));
            if (cur == null) {
                insertAlias(norm, mfrCode, actor);
                added.add(norm);
            }
        }
        if (!added.isEmpty()) {
            reattribute(added, actor);
        }
    }

    private void insertAlias(String norm, String mfrCode, String actor) {
        ElcMfrAlias a = new ElcMfrAlias();
        a.setAliasNorm(norm);
        a.setMfrCode(mfrCode);
        a.setSource(ElcMfrAlias.SOURCE_OPS);
        a.setCreatedBy(actor);
        aliasMapper.insert(a);
    }

    // ── 小件 ────────────────────────────────────────────────────────────────

    private MfrRow one(String mfrCode) {
        return row(or404(mfrCode), counts(aliasMapper.countsByMfr()), counts(partMapper.countsByMfr()));
    }

    private static MfrRow row(ElcManufacturer m, Map<String, Long> aliasCnt, Map<String, Long> partCnt) {
        return new MfrRow(m.getMfrCode(), m.getNameEn(), m.getNameCn(), m.getStatus(), m.getMergedInto(),
                aliasCnt.getOrDefault(m.getMfrCode(), 0L).intValue(),
                partCnt.getOrDefault(m.getMfrCode(), 0L).intValue());
    }

    private static Map<String, Long> counts(List<CodeCount> rows) {
        return rows.stream().collect(Collectors.toMap(CodeCount::getCode, CodeCount::getCnt));
    }

    private ElcManufacturer or404(String mfrCode) {
        ElcManufacturer m = mfrCode == null ? null : mfrMapper.selectOne(Wrappers.<ElcManufacturer>lambdaQuery()
                .eq(ElcManufacturer::getMfrCode, mfrCode.trim().toUpperCase(Locale.ROOT)));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return m;
    }

    /**
     * 能挂别名、能改名的厂牌：存在、没被并掉、<b>不是 UNKNOWN</b>。
     * 给 UNKNOWN 挂别名 = 把一种写法永久认成「厂牌不明」，那正是这一页要消灭的东西。
     */
    private ElcManufacturer activeOr404(String mfrCode) {
        ElcManufacturer m = or404(mfrCode);
        if (ElcManufacturer.UNKNOWN.equals(m.getMfrCode())
                || !ElcManufacturer.STATUS_ACTIVE.equals(m.getStatus())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return m;
    }

    /** 一种写法的聚合 */
    private static final class Agg {
        int rows;
        final Set<String> suppliers = new HashSet<>();
        final Set<String> parts = new HashSet<>();
        final Map<String, Integer> raws = new HashMap<>();

        void add(UnknownRawRow r) {
            int c = r.getCnt() == null ? 0 : r.getCnt().intValue();
            rows += c;
            suppliers.add(r.getSupplierNo());
            parts.add(r.getPartNo());
            raws.merge(r.getMfrRaw(), c, Integer::sum);
        }

        /** 出现最多的那个原文；一样多取字典序小的，结果稳定 */
        String sample() {
            return raws.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> -e.getValue())
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
        }
    }
}
