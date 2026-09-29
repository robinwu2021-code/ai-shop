package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.dto.SupplierDtos.BatchPreview;
import ai.neargo.shop.elec.dto.SupplierDtos.RowProblem;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcStockBatch;
import ai.neargo.shop.elec.entity.ElcStockBatchRow;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchRowMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.service.ElecMarketService;
import ai.neargo.shop.elec.service.ElecStockImportService;
import ai.neargo.shop.elec.support.Cells;
import ai.neargo.shop.elec.support.Columns;
import ai.neargo.shop.elec.support.Columns.Field;
import ai.neargo.shop.elec.support.ElecKeys;
import ai.neargo.shop.elec.support.Mpn;
import ai.neargo.shop.elec.support.SheetReader;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 上传库存。
 *
 * <p><b>先算后做</b>：上传只写 batch 与原样行，算出「新增 / 更新 / 下架 / 未变」给供应商看；
 * 他点确认之后，<b>按此刻的库存重算一遍</b>再写 —— 预览和确认之间他可能又传了一张表。
 *
 * <p><b>空格子 = 不改，不是清空</b>：更新一行时，表里批号、封装、价格、起订量为空的，保留原值。
 * 数量不一样：数量认不出这一行就不上架（空 ≠ 0）。
 *
 * <p><b>全量替换</b>：本次表里没有的在售行下架。最危险的情况是他传了一张只有半截的表，
 * 所以预览里「将下架 N 行」连同料号样本一起给他看，确认前一行都不动。
 */
@ConditionalOnElec
@Service
public class ElecStockImportServiceImpl implements ElecStockImportService {

    public static final String MODE_MERGE = "MERGE";
    public static final String MODE_REPLACE = "REPLACE";

    private static final int MAX_PROBLEMS = 50;
    private static final int MAX_DELIST_SAMPLE = 20;
    private static final int CHUNK = 500;

    private final ElecSupplierAccess access;
    private final StockBatchMapper batchMapper;
    private final StockBatchRowMapper rowMapper;
    private final StockMapper stockMapper;
    private final ElecPartCatalog catalog;
    private final ElecMarketService market;
    private final ElecProperties props;
    private final ObjectMapper json;

    public ElecStockImportServiceImpl(ElecSupplierAccess access, StockBatchMapper batchMapper, StockBatchRowMapper rowMapper,
                                  StockMapper stockMapper, ElecPartCatalog catalog, ElecMarketService market,
                                  ElecProperties props, ObjectMapper json) {
        this.access = access;
        this.batchMapper = batchMapper;
        this.rowMapper = rowMapper;
        this.stockMapper = stockMapper;
        this.catalog = catalog;
        this.market = market;
        this.props = props;
        this.json = json;
    }

    // ── 上传 ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public BatchPreview upload(String userNo, String fileName, byte[] bytes, String mode, Boolean taxIncluded) {
        ElcSupplier supplier = access.requireActive(userNo);
        List<List<String>> rows = SheetReader.read(bytes, props.getUploadMaxRows());
        Columns.Guess guess = Columns.guess(rows);
        int headerRow = guess.headerRow();
        Map<Field, Integer> map = guess.map();
        if (!guess.ok()) {
            // 认不出表头时，用这家上次确认过的映射兜底 —— 前提是表头一模一样（同一个 ERP 导出的）
            Remembered r = remembered(supplier.getSupplierNo(), rows);
            if (r == null) {
                throw BizException.of(ErrorCode.ELEC_UPLOAD_NO_HEADER);
            }
            headerRow = r.headerRow;
            map = r.map;
        } else {
            Remembered r = remembered(supplier.getSupplierNo(), rows);
            if (r != null && r.headerRow == headerRow) {
                map = r.map;
            }
        }

        ElcStockBatch b = new ElcStockBatch();
        b.setBatchNo(ElecKeys.next(ElecKeys.BATCH));
        b.setSupplierNo(supplier.getSupplierNo());
        b.setFileName(fileName == null ? null : ElecPartCatalog.truncate(fileName, 128));
        b.setMode(MODE_REPLACE.equals(mode) ? MODE_REPLACE : MODE_MERGE);
        b.setTaxIncluded(taxIncluded != null ? taxIncluded : guess.taxHint() == null || guess.taxHint());
        b.setHeaders(write(rows.get(headerRow)));
        b.setColumnMap(write(toWire(map)));
        b.setStatus(ElcStockBatch.STATUS_PARSED);
        b.setCreatedBy(userNo);
        b.setUpdatedBy(userNo);

        List<ElcStockBatchRow> raw = new ArrayList<>();
        for (int i = headerRow + 1; i < rows.size(); i++) {
            if (SheetReader.blank(rows.get(i))) {
                continue;
            }
            ElcStockBatchRow r = new ElcStockBatchRow();
            r.setBatchNo(b.getBatchNo());
            r.setRowIdx(i + 1);
            r.setCells(write(rows.get(i)));
            r.setCreatedBy(userNo);
            raw.add(r);
        }
        for (int i = 0; i < raw.size(); i += CHUNK) {
            rowMapper.insertAll(raw.subList(i, Math.min(raw.size(), i + CHUNK)));
        }
        Plan plan = plan(b, map, cellsOf(raw));
        fill(b, plan);
        batchMapper.insert(b);
        return preview(b, plan);
    }

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public BatchPreview remap(String userNo, String batchNo, Map<String, Integer> columns) {
        ElcStockBatch b = parsedBatch(userNo, batchNo);
        Map<Field, Integer> map = fromWire(columns);
        if (!map.containsKey(Field.MPN) || !map.containsKey(Field.QTY)) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_NO_HEADER);
        }
        b.setColumnMap(write(toWire(map)));
        Plan plan = plan(b, map, cellsOf(rowsOf(batchNo)));
        fill(b, plan);
        b.setUpdatedBy(userNo);
        batchMapper.updateById(b);
        return preview(b, plan);
    }

    // ── 确认上架 ────────────────────────────────────────────────────────────

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public BatchPreview apply(String userNo, String batchNo) {
        ElcStockBatch b = parsedBatch(userNo, batchNo);
        Map<Field, Integer> map = fromWire(read(b.getColumnMap(), new TypeReference<Map<String, Integer>>() { }));
        Plan plan = plan(b, map, cellsOf(rowsOf(batchNo)));
        if (plan.valid.isEmpty()) {
            // 一行能上架的都没有时，全量替换会把他的库存全部下架 —— 这一步必须拦住
            throw BizException.of(ErrorCode.ELEC_BATCH_EMPTY);
        }
        LocalDate validUntil = LocalDate.now().plusDays(props.getStockTtlDays());
        LocalDateTime now = LocalDateTime.now();
        Set<String> touchedParts = new HashSet<>();

        ElecPartCatalog.Session parts = catalog.session();
        parts.preload(plan.valid.stream().map(p -> p.mpnNorm).toList());
        List<ElcStock> inserts = new ArrayList<>();
        for (Parsed p : plan.valid) {
            ElcStock cur = plan.existing.get(p.lineKey);
            if (cur == null) {
                ElcStock s = new ElcStock();
                s.setStockNo(ElecKeys.next(ElecKeys.STOCK));
                s.setSupplierNo(b.getSupplierNo());
                s.setLineKey(p.lineKey);
                s.setPartNo(parts.resolve(p.mpnRaw, p.mpnNorm, p.mfrRaw, p.pkg, userNo));
                s.setMpnRaw(p.mpnRaw);
                s.setMfrRaw(p.mfrRaw);
                s.setMpnNorm(p.mpnNorm);
                s.setQty(p.qty);
                s.setDateCode(p.dateCode);
                s.setDcYear(p.dcYear);
                s.setPkg(p.pkg);
                s.setMoq(p.moq);
                s.setPriceE6(p.priceE6);
                s.setTaxIncluded(b.getTaxIncluded());
                s.setValidUntil(validUntil);
                s.setConfirmedAt(now);
                s.setStatus(ElcStock.STATUS_ON);
                s.setBatchNo(batchNo);
                s.setCreatedBy(userNo);
                s.setUpdatedBy(userNo);
                inserts.add(s);
                touchedParts.add(s.getPartNo());
            } else {
                // 已有这一行（在售或之前被下架的）：空格子保留原值
                cur.setQty(p.qty);
                if (p.dateCode != null) {
                    cur.setDateCode(p.dateCode);
                    cur.setDcYear(p.dcYear);
                }
                if (p.pkg != null) {
                    cur.setPkg(p.pkg);
                }
                if (p.moq != null) {
                    cur.setMoq(p.moq);
                }
                if (p.priceE6 != null) {
                    cur.setPriceE6(p.priceE6);
                    cur.setTaxIncluded(b.getTaxIncluded());
                }
                cur.setValidUntil(validUntil);
                cur.setConfirmedAt(now);
                cur.setStatus(ElcStock.STATUS_ON);
                cur.setBatchNo(batchNo);
                cur.setUpdatedBy(userNo);
                stockMapper.updateById(cur);
                touchedParts.add(cur.getPartNo());
            }
        }
        parts.flush();
        for (int i = 0; i < inserts.size(); i += CHUNK) {
            stockMapper.insertAll(inserts.subList(i, Math.min(inserts.size(), i + CHUNK)));
        }
        for (ElcStock gone : plan.delist) {
            gone.setStatus(ElcStock.STATUS_DELISTED);
            gone.setBatchNo(batchNo);
            gone.setUpdatedBy(userNo);
            stockMapper.updateById(gone);
            touchedParts.add(gone.getPartNo());
        }
        market.refresh(touchedParts);

        fill(b, plan);
        b.setStatus(ElcStockBatch.STATUS_APPLIED);
        b.setAppliedAt(now);
        b.setUpdatedBy(userNo);
        batchMapper.updateById(b);
        return preview(b, plan);
    }

    // ── 计算 ────────────────────────────────────────────────────────────────

    /** 一行解析的结果。只有 valid 的行会上架 */
    private record Parsed(int row, String mpnRaw, String mpnNorm, String mfrRaw, String lineKey, long qty,
                          String dateCode, Integer dcYear, String pkg, Integer moq, Long priceE6) {
    }

    private static final class Plan {
        final List<Parsed> valid = new ArrayList<>();
        final List<RowProblem> problems = new ArrayList<>();
        int invalid;
        int total;
        int toInsert;
        int toUpdate;
        int unchanged;
        /** 本供应商已有的全部行（含已下架的），按 line_key */
        Map<String, ElcStock> existing = Map.of();
        final List<ElcStock> delist = new ArrayList<>();
    }

    private Plan plan(ElcStockBatch b, Map<Field, Integer> map, List<IndexedCells> rows) {
        Plan plan = new Plan();
        Map<String, ElcStock> existing = new HashMap<>();
        for (ElcStock s : stockMapper.selectList(Wrappers.<ElcStock>lambdaQuery()
                .eq(ElcStock::getSupplierNo, b.getSupplierNo()))) {
            existing.put(s.getLineKey(), s);
        }
        plan.existing = existing;
        Set<String> keysInFile = new HashSet<>();
        for (IndexedCells r : rows) {
            plan.total++;
            String mpnRaw = Cells.text(cell(r.cells, map.get(Field.MPN)), 64);
            if (mpnRaw == null) {
                problem(plan, r.row, "MPN_MISSING", null);
                continue;
            }
            String norm = Mpn.norm(mpnRaw);
            if (!Mpn.looksLikeMpn(norm)) {
                problem(plan, r.row, "MPN_INVALID", mpnRaw);
                continue;
            }
            Long qty = Cells.qty(cell(r.cells, map.get(Field.QTY)));
            if (qty == null) {
                problem(plan, r.row, "QTY_INVALID", mpnRaw);
                continue;
            }
            String mfrRaw = Cells.text(cell(r.cells, map.get(Field.MFR)), 64);
            String dc = Cells.text(cell(r.cells, map.get(Field.DC)), 16);
            String key = lineKey(norm, mfrRaw, dc);
            if (!keysInFile.add(key)) {
                problem(plan, r.row, "DUPLICATE", mpnRaw);
                continue;
            }
            Parsed p = new Parsed(r.row, mpnRaw, norm, mfrRaw, key, qty, dc, Cells.dcYear(dc),
                    Cells.text(cell(r.cells, map.get(Field.PACKAGE)), 32),
                    Cells.moq(cell(r.cells, map.get(Field.MOQ))),
                    Cells.priceE6(cell(r.cells, map.get(Field.PRICE))));
            plan.valid.add(p);
            ElcStock cur = existing.get(key);
            if (cur == null || !ElcStock.STATUS_ON.equals(cur.getStatus())) {
                plan.toInsert++;
            } else if (same(cur, p, b.getTaxIncluded())) {
                plan.unchanged++;
            } else {
                plan.toUpdate++;
            }
        }
        if (MODE_REPLACE.equals(b.getMode())) {
            for (ElcStock s : existing.values()) {
                if (ElcStock.STATUS_ON.equals(s.getStatus()) && !keysInFile.contains(s.getLineKey())) {
                    plan.delist.add(s);
                }
            }
        }
        return plan;
    }

    /** 「未变」= 表里写了的每一项都和库里一样（空格子不算变化）。未变的行确认时照样续期 */
    private static boolean same(ElcStock cur, Parsed p, Boolean tax) {
        return cur.getQty() == p.qty
                && (p.dateCode == null || p.dateCode.equals(cur.getDateCode()))
                && (p.pkg == null || p.pkg.equals(cur.getPkg()))
                && (p.moq == null || p.moq.equals(cur.getMoq()))
                && (p.priceE6 == null || (p.priceE6.equals(cur.getPriceE6())
                && Objects.equals(tax, cur.getTaxIncluded())));
    }

    private static void problem(Plan plan, int row, String reason, String mpn) {
        plan.invalid++;
        if (plan.problems.size() < MAX_PROBLEMS) {
            plan.problems.add(new RowProblem(row, reason, mpn));
        }
    }

    /**
     * 认行的键：规范化料号 | 规范化厂牌 | 批号。同一料号不同批次是两行库存（价格、年份都可能不同），
     * 所以批号在键里；厂牌用原文规范化而不是解析后的厂牌码 —— 别名表一改，认行的结果不能跟着变。
     */
    static String lineKey(String mpnNorm, String mfrRaw, String dc) {
        String k = mpnNorm + "|" + Mpn.mfrNorm(mfrRaw) + "|" + (dc == null ? "" : dc.trim().toUpperCase(Locale.ROOT));
        return k.length() > 160 ? k.substring(0, 160) : k;
    }

    private static String cell(List<String> cells, Integer col) {
        return col == null || col < 0 || col >= cells.size() ? null : cells.get(col);
    }

    private void fill(ElcStockBatch b, Plan plan) {
        b.setRowTotal(plan.total);
        b.setRowValid(plan.valid.size());
        b.setRowInvalid(plan.invalid);
        b.setToInsert(plan.toInsert);
        b.setToUpdate(plan.toUpdate);
        b.setToDelist(plan.delist.size());
        b.setUnchanged(plan.unchanged);
    }

    private BatchPreview preview(ElcStockBatch b, Plan plan) {
        List<String> sample = plan.delist.stream().limit(MAX_DELIST_SAMPLE).map(ElcStock::getMpnRaw).toList();
        return new BatchPreview(b.getBatchNo(), b.getFileName(), b.getMode(), Boolean.TRUE.equals(b.getTaxIncluded()),
                read(b.getHeaders(), new TypeReference<List<String>>() { }),
                read(b.getColumnMap(), new TypeReference<Map<String, Integer>>() { }),
                plan.total, plan.valid.size(), plan.invalid, plan.toInsert, plan.toUpdate, plan.delist.size(),
                plan.unchanged, plan.problems, sample, b.getStatus());
    }

    // ── 批次与原样行 ────────────────────────────────────────────────────────

    private ElcStockBatch parsedBatch(String userNo, String batchNo) {
        ElcSupplier supplier = access.requireActive(userNo);
        ElcStockBatch b = batchMapper.selectOne(Wrappers.<ElcStockBatch>lambdaQuery()
                .eq(ElcStockBatch::getBatchNo, batchNo)
                .eq(ElcStockBatch::getSupplierNo, supplier.getSupplierNo()));
        if (b == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!ElcStockBatch.STATUS_PARSED.equals(b.getStatus())
                || b.getCreatedAt().plusHours(props.getBatchTtlHours()).isBefore(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.ELEC_BATCH_EXPIRED);
        }
        return b;
    }

    private List<ElcStockBatchRow> rowsOf(String batchNo) {
        return rowMapper.selectList(Wrappers.<ElcStockBatchRow>lambdaQuery()
                .eq(ElcStockBatchRow::getBatchNo, batchNo).orderByAsc(ElcStockBatchRow::getRowIdx));
    }

    private record IndexedCells(int row, List<String> cells) {
    }

    private List<IndexedCells> cellsOf(List<ElcStockBatchRow> raw) {
        List<IndexedCells> out = new ArrayList<>(raw.size());
        for (ElcStockBatchRow r : raw) {
            out.add(new IndexedCells(r.getRowIdx(), read(r.getCells(), new TypeReference<List<String>>() { })));
        }
        return out;
    }

    private record Remembered(int headerRow, Map<Field, Integer> map) {
    }

    /** 这家上一次<b>确认上架过</b>的批次，表头与这次一模一样时，沿用它的映射 */
    private Remembered remembered(String supplierNo, List<List<String>> rows) {
        ElcStockBatch last = batchMapper.selectOne(Wrappers.<ElcStockBatch>lambdaQuery()
                .eq(ElcStockBatch::getSupplierNo, supplierNo)
                .eq(ElcStockBatch::getStatus, ElcStockBatch.STATUS_APPLIED)
                .orderByDesc(ElcStockBatch::getId).last("LIMIT 1"));
        if (last == null || last.getHeaders() == null) {
            return null;
        }
        List<String> headers = read(last.getHeaders(), new TypeReference<List<String>>() { });
        for (int i = 0; i < Math.min(10, rows.size()); i++) {
            if (rows.get(i).equals(headers)) {
                return new Remembered(i,
                        fromWire(read(last.getColumnMap(), new TypeReference<Map<String, Integer>>() { })));
            }
        }
        return null;
    }

    private static Map<String, Integer> toWire(Map<Field, Integer> map) {
        Map<String, Integer> m = new LinkedHashMap<>();
        map.forEach((k, v) -> m.put(k.name(), v));
        return m;
    }

    /** 端上传来的映射：不认识的字段名丢掉，列号为负的丢掉 */
    private static Map<Field, Integer> fromWire(Map<String, Integer> wire) {
        Map<Field, Integer> m = new EnumMap<>(Field.class);
        if (wire == null) {
            return m;
        }
        wire.forEach((k, v) -> {
            if (v != null && v >= 0) {
                for (Field f : Field.values()) {
                    if (f.name().equals(k)) {
                        m.put(f, v);
                    }
                }
            }
        });
        return m;
    }

    private String write(Object v) {
        return json.writeValueAsString(v);
    }

    private <T> T read(String s, TypeReference<T> type) {
        return json.readValue(s, type);
    }
}
