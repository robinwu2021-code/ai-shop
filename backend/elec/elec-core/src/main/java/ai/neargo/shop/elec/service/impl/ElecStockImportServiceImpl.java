package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.dto.SupplierDtos.BatchPreview;
import ai.neargo.shop.elec.dto.SupplierDtos.Issue;
import ai.neargo.shop.elec.dto.SupplierDtos.PreviewRow;
import ai.neargo.shop.elec.dto.SupplierDtos.PriceTier;
import ai.neargo.shop.elec.dto.SupplierDtos.RowProblem;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcStockBatch;
import ai.neargo.shop.elec.entity.ElcStockBatchRow;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchRowMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierMapper;
import ai.neargo.shop.elec.service.ElecMarketService;
import ai.neargo.shop.elec.service.ElecStockImportService;
import ai.neargo.shop.elec.service.impl.StockSheetParser.Parsed;
import ai.neargo.shop.elec.support.Columns;
import ai.neargo.shop.elec.support.Columns.Field;
import ai.neargo.shop.elec.support.ElecKeys;
import ai.neargo.shop.elec.support.IssueText;
import ai.neargo.shop.elec.support.SheetReader;
import ai.neargo.shop.elec.support.SheetWriter;
import ai.neargo.shop.elec.support.UploadFileStore;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 上传库存。
 *
 * <p><b>先算后做</b>：上传只落原件、写一条批次元数据，解析结果放内存（{@link PendingBatchCache}）给他看
 * 「新增 / 更新 / 下架 / 未变」；他点确认之后，<b>按此刻的库存重算一遍</b>再写 —— 预览和确认之间库存可能变了。
 *
 * <p><b>空格子 = 不改，不是清空</b>：更新一行时，表里批号、封装、价格、起订量为空的，保留原值。
 * 数量不一样：数量认不出这一行就不上架（空 ≠ 0）。
 *
 * <p><b>全量替换</b>：本次表里没有的在售行下架。最危险的情况是他传了一张只有半截的表，
 * 所以预览里「将下架 N 行」连同料号样本一起给他看；过了护栏的线还要他确认<b>此刻</b>的下架数。
 */
@Slf4j
@ConditionalOnElec
@Service
public class ElecStockImportServiceImpl implements ElecStockImportService {

    public static final String MODE_MERGE = "MERGE";
    public static final String MODE_REPLACE = "REPLACE";

    private static final int MAX_ISSUES = 100;
    private static final int MAX_PROBLEMS = 50;
    private static final int MAX_DELIST_SAMPLE = 20;
    private static final int MAX_PAGE = 100;
    private static final int CHUNK = 500;

    private final ElecSupplierAccess access;
    private final SupplierMapper supplierMapper;
    private final StockBatchMapper batchMapper;
    private final StockBatchRowMapper rowMapper;
    private final StockMapper stockMapper;
    private final ElecPartCatalog catalog;
    private final ElecMarketService market;
    private final ElecProperties props;
    private final ObjectMapper json;
    private final ColumnResolver resolver;
    private final HeaderAliases headerAliases;
    private final PendingBatchCache cache;
    private final UploadFileStore files;
    private final BatchFailureRecorder failures;

    public ElecStockImportServiceImpl(ElecSupplierAccess access, SupplierMapper supplierMapper,
                                      StockBatchMapper batchMapper, StockBatchRowMapper rowMapper,
                                      StockMapper stockMapper, ElecPartCatalog catalog, ElecMarketService market,
                                      ElecProperties props, ObjectMapper json, ColumnResolver resolver,
                                      HeaderAliases headerAliases, PendingBatchCache cache, UploadFileStore files,
                                      BatchFailureRecorder failures) {
        this.access = access;
        this.supplierMapper = supplierMapper;
        this.batchMapper = batchMapper;
        this.rowMapper = rowMapper;
        this.stockMapper = stockMapper;
        this.catalog = catalog;
        this.market = market;
        this.props = props;
        this.json = json;
        this.resolver = resolver;
        this.headerAliases = headerAliases;
        this.cache = cache;
        this.files = files;
        this.failures = failures;
    }

    // ── 上传 ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public BatchPreview upload(String userNo, String fileName, byte[] bytes, String mode, Boolean taxIncluded) {
        ElcSupplier supplier = access.requireActive(userNo);
        String supplierNo = supplier.getSupplierNo();
        LocalDateTime now = LocalDateTime.now();
        int dailyMax = props.getUpload().getDailyMax();
        if (dailyMax > 0 && batchMapper.countSince(supplierNo, now.toLocalDate().atStartOfDay()) >= dailyMax) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_DAILY_LIMIT, dailyMax);
        }

        ElcStockBatch b = new ElcStockBatch();
        b.setBatchNo(ElecKeys.next(ElecKeys.BATCH));
        b.setSupplierNo(supplierNo);
        b.setFileName(fileName == null || fileName.isBlank() ? null : ElecPartCatalog.truncate(fileName.strip(), 128));
        b.setMode(MODE_REPLACE.equals(mode) ? MODE_REPLACE : MODE_MERGE);
        b.setCurrency("CNY");
        // 显式给创建时刻：有效期按它算，必须与判定时用的是同一个时钟（JVM），不能交给库的默认值
        b.setCreatedAt(now);
        b.setCreatedBy(userNo);
        b.setUpdatedBy(userNo);

        // 原件先落未入库区 —— 解析失败的也要留下来
        UploadFileStore.Stored st;
        try {
            st = files.storeFailed(now.toLocalDate(), supplierNo, b.getBatchNo(), fileName, bytes);
        } catch (UncheckedIOException e) {
            log.error("上传原件写盘失败：{} {}", files.root(), e.toString());
            throw BizException.of(ErrorCode.ELEC_UPLOAD_STORE);
        }
        b.setFilePath(st.relPath());
        b.setFileArea(ElcStockBatch.AREA_FAILED);
        b.setFileSize((int) st.size());
        b.setFileSha256(st.sha256());

        List<List<String>> rows;
        try {
            rows = SheetReader.read(bytes, props.getUploadMaxRows());
        } catch (BizException e) {
            failures.record(b, e.errorCode().msgKey());
            throw e;
        }
        ColumnResolver.Resolution r = resolver.resolve(supplierNo, rows, remembered(supplierNo, rows));
        if (r == null) {
            failures.record(b, ErrorCode.ELEC_UPLOAD_NO_HEADER.msgKey());
            throw BizException.of(ErrorCode.ELEC_UPLOAD_NO_HEADER);
        }
        b.setHeaderRow(r.headerRow());
        b.setHeaders(write(rows.get(r.headerRow())));
        b.setColumnMap(write(toWire(r.map())));
        b.setColumnSource(write(toWireSource(r.source())));
        b.setTierCols(write(r.tiers().stream().map(c -> List.of(c.col(), c.minQty())).toList()));
        b.setAiUsed(r.aiUsed());
        b.setTaxIncluded(taxIncluded != null ? taxIncluded : r.taxHint() == null || r.taxHint());

        PendingBatch pb = null;
        if (r.complete()) {
            pb = build(b, rows, r.map(), r.tiers());
            fill(b, pb);
            b.setStatus(ElcStockBatch.STATUS_PARSED);
        } else {
            b.setStatus(ElcStockBatch.STATUS_NEED_MAPPING);
        }
        supersedePending(supplierNo, userNo);
        batchMapper.insert(b);
        if (pb != null) {
            cache.put(pb);
        }
        return preview(b, pb);
    }

    /** 一家只留一张待确认：之前待确认的作废（两张并存时确认顺序不同结果就不同，他自己也说不清哪张准） */
    private void supersedePending(String supplierNo, String userNo) {
        for (ElcStockBatch old : batchMapper.selectList(Wrappers.<ElcStockBatch>lambdaQuery()
                .eq(ElcStockBatch::getSupplierNo, supplierNo)
                .in(ElcStockBatch::getStatus, ElcStockBatch.STATUS_PARSED, ElcStockBatch.STATUS_NEED_MAPPING))) {
            // 已经过期的不改：记录里它该显示「已过期」，不是「已作废」
            if (!alive(old)) {
                continue;
            }
            old.setStatus(ElcStockBatch.STATUS_SUPERSEDED);
            old.setUpdatedBy(userNo);
            batchMapper.updateById(old);
            cache.evict(old.getBatchNo());
        }
    }

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public BatchPreview remap(String userNo, String batchNo, Map<String, Integer> columns) {
        ElcStockBatch b = pendingBatch(userNo, batchNo);
        Map<Field, Integer> map = fromWire(columns);
        if (!map.containsKey(Field.MPN) || !map.containsKey(Field.QTY)) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_NO_HEADER);
        }
        List<List<String>> rows = readRows(b).orElseThrow(() -> BizException.of(ErrorCode.ELEC_BATCH_EXPIRED));
        Map<Field, Integer> before = fromWire(read(b.getColumnMap(), new TypeReference<Map<String, Integer>>() { }));
        Map<Field, String> beforeSource = sourceOf(b);
        b.setColumnMap(write(toWire(map)));
        b.setColumnSource(write(toWireSource(ColumnResolver.manualSource(before, beforeSource, map))));
        // 阶梯价列不跟着改：它是按表头认的，与「这一列是什么字段」是两件事。被新映射占走的列除外
        Set<Integer> taken = new HashSet<>(map.values());
        List<Columns.PriceTierCol> tiers = tierColsOf(b).stream().filter(t -> !taken.contains(t.col())).toList();
        PendingBatch pb = build(b, rows, map, tiers);
        fill(b, pb);
        b.setStatus(ElcStockBatch.STATUS_PARSED);
        b.setUpdatedBy(userNo);
        batchMapper.updateById(b);
        cache.put(pb);
        return preview(b, pb);
    }

    // ── 预览里的行 ──────────────────────────────────────────────────────────

    @Override
    public List<PreviewRow> rows(String userNo, String batchNo, String view, int page, int size) {
        ElcStockBatch b = pendingBatch(userNo, batchNo);
        if (!ElcStockBatch.STATUS_PARSED.equals(b.getStatus())) {
            return List.of();
        }
        PendingBatch pb = pending(b);
        int sz = Math.max(1, Math.min(MAX_PAGE, size));
        int from = Math.max(0, page - 1) * sz;
        String v = view == null ? PendingBatch.INSERT : view;
        if (PendingBatch.DELIST.equals(v)) {
            return pb.delist().stream().skip(from).limit(sz).map(s -> new PreviewRow(0, PendingBatch.DELIST,
                    s.getMpnRaw(), s.getMfrRaw(), s.getQty(), s.getDateCode(), s.getPkg(), s.getMoq(), s.getSpq(),
                    null, s.getCurrency(), s.getPacking(), s.getCondGrade(), s.getLeadDays(), s.getRegion(),
                    null, List.of())).toList();
        }
        List<StockSheetParser.Row> picked = pb.parsed().rows().stream().filter(r -> switch (v) {
            case PendingBatch.PROBLEM -> !r.issues().isEmpty();
            default -> !r.hasError() && v.equals(pb.kinds().get(r.row()));
        }).skip(from).limit(sz).toList();
        Map<String, ElcStock> current = PendingBatch.UPDATE.equals(v) ? currentByKey(b.getSupplierNo(), picked)
                : Map.of();
        List<PreviewRow> out = new ArrayList<>(picked.size());
        for (StockSheetParser.Row r : picked) {
            Parsed p = r.parsed();
            if (p == null) {
                out.add(new PreviewRow(r.row(), PendingBatch.PROBLEM, r.mpnRaw(), r.mfrRaw(), null, null, null, null,
                        null, null, null, null, null, null, null, null, r.issues()));
                continue;
            }
            ElcStock cur = current.get(p.lineKey());
            out.add(new PreviewRow(r.row(), pb.kinds().get(r.row()), p.mpnRaw(), p.mfrRaw(), p.qty(), p.dateCode(),
                    p.pkg(), p.moq(), p.spq(), tiersOf(p.tiers()), p.currency() != null ? p.currency() : b.getCurrency(),
                    p.packing(), p.cond(), p.leadDays(), p.region(), cur == null ? null : before(cur, p),
                    r.issues()));
        }
        return out;
    }

    /** 更新的行：变了的那几个字段的旧值（空格子不算变） */
    private static Map<String, Object> before(ElcStock cur, Parsed p) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (cur.getQty() != p.qty()) {
            m.put("qty", cur.getQty());
        }
        putIfChanged(m, "dateCode", p.dateCode(), cur.getDateCode());
        putIfChanged(m, "packageName", p.pkg(), cur.getPkg());
        putIfChanged(m, "moq", p.moq(), cur.getMoq());
        putIfChanged(m, "spq", p.spq(), cur.getSpq());
        putIfChanged(m, "priceE6", p.priceE6(), cur.getPriceE6());
        putIfChanged(m, "packing", p.packing(), cur.getPacking());
        putIfChanged(m, "cond", p.cond(), cur.getCondGrade());
        putIfChanged(m, "leadDays", p.leadDays(), cur.getLeadDays());
        putIfChanged(m, "region", p.region(), cur.getRegion());
        return m;
    }

    private static void putIfChanged(Map<String, Object> m, String k, Object now, Object old) {
        if (now != null && !now.equals(old)) {
            m.put(k, old);
        }
    }

    private Map<String, ElcStock> currentByKey(String supplierNo, List<StockSheetParser.Row> rows) {
        List<String> keys = rows.stream().filter(r -> r.parsed() != null).map(r -> r.parsed().lineKey()).toList();
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, ElcStock> m = new HashMap<>();
        for (ElcStock s : stockMapper.selectList(Wrappers.<ElcStock>lambdaQuery()
                .eq(ElcStock::getSupplierNo, supplierNo).in(ElcStock::getLineKey, keys))) {
            m.put(s.getLineKey(), s);
        }
        return m;
    }

    // ── 确认上架 ────────────────────────────────────────────────────────────

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public BatchPreview apply(String userNo, String batchNo, Integer expectDelist) {
        ElcSupplier supplier = access.requireActive(userNo);
        // 第一句就锁这家：同一家两张预览同时确认时，第二个在这里等第一个提交，再按提交后的库存重算
        supplierMapper.lockBySupplierNo(supplier.getSupplierNo());
        ElcStockBatch b = pendingBatch(userNo, batchNo);
        if (!ElcStockBatch.STATUS_PARSED.equals(b.getStatus())) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_NO_HEADER);
        }
        PendingBatch cached = pending(b);
        // 按此刻的库存重算：缓存里的分类只是给预览看的
        PendingBatch pb = classify(b, cached.parsed(), cached.deadline());
        List<Parsed> valid = pb.parsed().rows().stream().map(StockSheetParser.Row::parsed).filter(Objects::nonNull)
                .toList();
        if (valid.isEmpty()) {
            // 一行能上架的都没有时，全量替换会把他的库存全部下架 —— 这一步必须拦住
            throw BizException.of(ErrorCode.ELEC_BATCH_EMPTY);
        }
        if (needDelistConfirm(pb) && !Objects.equals(expectDelist, pb.delist().size())) {
            throw BizException.of(ErrorCode.ELEC_DELIST_CONFIRM, pb.delist().size());
        }

        LocalDate validUntil = LocalDate.now().plusDays(props.getStockTtlDays());
        LocalDateTime now = LocalDateTime.now();
        Set<String> touchedParts = new HashSet<>();
        Map<String, ElcStock> existing = existingByKey(b.getSupplierNo());

        ElecPartCatalog.Session parts = catalog.session();
        parts.preload(valid.stream().map(Parsed::mpnNorm).toList());
        List<ElcStock> inserts = new ArrayList<>();
        for (Parsed p : valid) {
            ElcStock cur = existing.get(p.lineKey());
            if (cur == null) {
                ElcStock s = new ElcStock();
                s.setStockNo(ElecKeys.next(ElecKeys.STOCK));
                s.setSupplierNo(b.getSupplierNo());
                s.setLineKey(p.lineKey());
                s.setPartNo(parts.resolve(p.mpnRaw(), p.mpnNorm(), p.mfrRaw(), p.pkg(), userNo));
                s.setMpnRaw(p.mpnRaw());
                s.setMfrRaw(p.mfrRaw());
                s.setMpnNorm(p.mpnNorm());
                s.setQty(p.qty());
                s.setDateCode(p.dateCode());
                s.setDcYear(p.dcYear());
                s.setPkg(p.pkg());
                s.setMoq(p.moq());
                s.setSpq(p.spq());
                s.setPriceTiers(tiersJson(p.tiers()));
                s.setPriceE6(p.priceE6());
                s.setCurrency(p.currency() != null ? p.currency() : b.getCurrency());
                s.setTaxIncluded(b.getTaxIncluded());
                s.setPacking(p.packing());
                s.setCondGrade(p.cond());
                s.setLeadDays(p.leadDays());
                s.setRegion(p.region());
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
                cur.setQty(p.qty());
                if (p.dateCode() != null) {
                    cur.setDateCode(p.dateCode());
                    cur.setDcYear(p.dcYear());
                }
                if (p.pkg() != null) {
                    cur.setPkg(p.pkg());
                }
                if (p.moq() != null) {
                    cur.setMoq(p.moq());
                }
                if (p.spq() != null) {
                    cur.setSpq(p.spq());
                }
                if (p.priceE6() != null) {
                    cur.setPriceTiers(tiersJson(p.tiers()));
                    cur.setPriceE6(p.priceE6());
                    cur.setCurrency(p.currency() != null ? p.currency() : b.getCurrency());
                    cur.setTaxIncluded(b.getTaxIncluded());
                }
                if (p.packing() != null) {
                    cur.setPacking(p.packing());
                }
                if (p.cond() != null) {
                    cur.setCondGrade(p.cond());
                }
                if (p.leadDays() != null) {
                    cur.setLeadDays(p.leadDays());
                }
                if (p.region() != null) {
                    cur.setRegion(p.region());
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
        for (ElcStock gone : pb.delist()) {
            gone.setStatus(ElcStock.STATUS_DELISTED);
            gone.setBatchNo(batchNo);
            gone.setUpdatedBy(userNo);
            stockMapper.updateById(gone);
            touchedParts.add(gone.getPartNo());
        }
        market.refresh(touchedParts);

        // 问题行入库：原件将来被清掉之后，记录里的「导出问题行」照样能用
        List<ElcStockBatchRow> issueRows = new ArrayList<>();
        for (StockSheetParser.Row r : pb.parsed().rows()) {
            if (!r.issues().isEmpty()) {
                ElcStockBatchRow row = new ElcStockBatchRow();
                row.setBatchNo(batchNo);
                row.setRowIdx(r.row());
                row.setCells(write(r.cells()));
                row.setIssues(issuesJson(r.issues()));
                row.setIssueLevel(r.hasError() ? StockSheetParser.ERROR : StockSheetParser.WARN);
                row.setCreatedBy(userNo);
                issueRows.add(row);
            }
        }
        for (int i = 0; i < issueRows.size(); i += CHUNK) {
            rowMapper.insertAll(issueRows.subList(i, Math.min(issueRows.size(), i + CHUNK)));
        }

        fill(b, pb);
        b.setStatus(ElcStockBatch.STATUS_APPLIED);
        b.setAppliedAt(now);
        b.setUpdatedBy(userNo);
        batchMapper.updateById(b);
        learn(b, userNo);
        afterCommit(() -> {
            cache.evict(batchNo);
            moveToApplied(b);
        });
        return preview(b, pb);
    }

    /** AI 或手工认出的列，把表头写法记成这家的别名 —— 下次同样写法不用再问大模型 */
    private void learn(ElcStockBatch b, String userNo) {
        Map<Field, Integer> map = fromWire(read(b.getColumnMap(), new TypeReference<Map<String, Integer>>() { }));
        Map<Field, String> source = sourceOf(b);
        List<String> headers = read(b.getHeaders(), new TypeReference<List<String>>() { });
        Map<Field, String> learnt = new EnumMap<>(Field.class);
        map.forEach((f, col) -> {
            String src = source.get(f);
            if ((ColumnResolver.SRC_AI.equals(src) || ColumnResolver.SRC_MANUAL.equals(src))
                    && col < headers.size() && headers.get(col) != null && !headers.get(col).isBlank()) {
                learnt.put(f, headers.get(col));
            }
        });
        if (!learnt.isEmpty()) {
            headerAliases.learn(b.getSupplierNo(), learnt, userNo);
        }
    }

    /** 事务提交后移区。移动失败不影响上架（库存已提交）：文件留在未入库区，每周清理先补移再删 */
    private void moveToApplied(ElcStockBatch b) {
        if (b.getFilePath() == null) {
            return;
        }
        if (files.moveToApplied(b.getFilePath())) {
            batchMapper.setFileArea(b.getBatchNo(), ElcStockBatch.AREA_APPLIED);
        } else {
            log.error("批次 {} 已上架，但原件移到已入库区失败：{}（每周清理会补移）", b.getBatchNo(), b.getFilePath());
        }
    }

    private boolean needDelistConfirm(PendingBatch pb) {
        int n = pb.delist().size();
        return n > 0 && (long) n * 10000 >= (long) pb.onSale() * props.getUpload().getDelistConfirmBp();
    }

    // ── 放弃 / 详情 / 导出 ──────────────────────────────────────────────────

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public BatchPreview cancel(String userNo, String batchNo) {
        ElcStockBatch b = own(userNo, batchNo);
        if (ElcStockBatch.STATUS_CANCELLED.equals(b.getStatus())) {
            return preview(b, null);
        }
        if (!ElcStockBatch.STATUS_PARSED.equals(b.getStatus())
                && !ElcStockBatch.STATUS_NEED_MAPPING.equals(b.getStatus())) {
            throw BizException.of(ErrorCode.ELEC_BATCH_EXPIRED);
        }
        b.setStatus(ElcStockBatch.STATUS_CANCELLED);
        b.setUpdatedBy(userNo);
        batchMapper.updateById(b);
        // 只清内存。原件留在未入库区：放弃之后记录里还看得到他传过什么，运营也还能下载
        afterCommit(() -> cache.evict(batchNo));
        return preview(b, null);
    }

    @Override
    public BatchPreview detail(String userNo, String batchNo) {
        ElcStockBatch b = own(userNo, batchNo);
        if (ElcStockBatch.STATUS_PARSED.equals(b.getStatus()) && alive(b)) {
            return preview(b, pending(b));
        }
        return stored(b);
    }

    @Override
    public ProblemsFile problems(String userNo, String batchNo) {
        ElcStockBatch b = own(userNo, batchNo);
        List<String> headers = b.getHeaders() == null ? List.of()
                : read(b.getHeaders(), new TypeReference<List<String>>() { });
        List<List<String>> out = new ArrayList<>();
        List<String> head = new ArrayList<>(headers);
        head.add("原行号");
        head.add("问题");
        out.add(head);
        Set<Long> red = new HashSet<>();
        List<IssueRow> rows;
        if (ElcStockBatch.STATUS_PARSED.equals(b.getStatus()) && alive(b)) {
            rows = pending(b).parsed().rows().stream().filter(r -> !r.issues().isEmpty())
                    .map(r -> new IssueRow(r.row(), r.cells(), r.issues())).toList();
        } else if (ElcStockBatch.STATUS_APPLIED.equals(b.getStatus())) {
            rows = storedIssueRows(b.getBatchNo());
        } else {
            throw BizException.of(ErrorCode.ELEC_BATCH_EXPIRED);
        }
        for (IssueRow r : rows) {
            List<String> line = new ArrayList<>(r.cells());
            while (line.size() < headers.size()) {
                line.add("");
            }
            List<String> texts = new ArrayList<>();
            for (Issue x : r.issues()) {
                texts.add(IssueText.of(x.row(), x.col(), x.header(), x.value(), x.code()));
                if (x.col() >= 0) {
                    red.add(SheetWriter.cell(out.size(), x.col()));
                }
            }
            line = new ArrayList<>(line.subList(0, Math.max(headers.size(), 0)));
            line.add(String.valueOf(r.row()));
            line.add(String.join("；", texts));
            out.add(line);
        }
        String base = UploadFileStore.safeBase(b.getFileName());
        return new ProblemsFile(base + "_问题行.xlsx", SheetWriter.xlsx("问题行", out, red));
    }

    private record IssueRow(int row, List<String> cells, List<Issue> issues) {
    }

    private List<IssueRow> storedIssueRows(String batchNo) {
        return rowMapper.selectList(Wrappers.<ElcStockBatchRow>lambdaQuery()
                        .eq(ElcStockBatchRow::getBatchNo, batchNo).isNotNull(ElcStockBatchRow::getIssues)
                        .orderByAsc(ElcStockBatchRow::getRowIdx)).stream()
                .map(r -> new IssueRow(r.getRowIdx(), read(r.getCells(), new TypeReference<List<String>>() { }),
                        issuesOf(r.getIssues(), r.getRowIdx()))).toList();
    }

    /** 不在内存里的批次（已上架、放弃、失败、过期）：计数取库里的，问题取入库的问题行 */
    private BatchPreview stored(ElcStockBatch b) {
        List<Issue> issues = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (ElcStockBatch.STATUS_APPLIED.equals(b.getStatus())) {
            for (IssueRow r : storedIssueRows(b.getBatchNo())) {
                for (Issue x : r.issues()) {
                    counts.merge(x.code(), 1, Integer::sum);
                    if (issues.size() < MAX_ISSUES) {
                        issues.add(x);
                    }
                }
            }
        }
        return preview(b, null, issues, counts);
    }

    // ── 计算 ────────────────────────────────────────────────────────────────

    private PendingBatch build(ElcStockBatch b, List<List<String>> rows, Map<Field, Integer> map,
                               List<Columns.PriceTierCol> tiers) {
        StockSheetParser.Result parsed = StockSheetParser.parse(rows, b.getHeaderRow(), map, tiers, catalog.aliases());
        return classify(b, parsed, b.deadline(ttl()).atZone(ZoneId.systemDefault()).toInstant());
    }

    /** 与这家此刻的库存比对：新增 / 更新 / 未变，全量替换时要下架哪些 */
    private PendingBatch classify(ElcStockBatch b, StockSheetParser.Result parsed, java.time.Instant deadline) {
        Map<String, ElcStock> existing = existingByKey(b.getSupplierNo());
        Map<Integer, String> kinds = new HashMap<>();
        Set<String> keysInFile = new HashSet<>();
        int ins = 0;
        int upd = 0;
        int same = 0;
        for (StockSheetParser.Row r : parsed.rows()) {
            Parsed p = r.parsed();
            if (p == null) {
                continue;
            }
            keysInFile.add(p.lineKey());
            ElcStock cur = existing.get(p.lineKey());
            if (cur == null || !ElcStock.STATUS_ON.equals(cur.getStatus())) {
                kinds.put(r.row(), PendingBatch.INSERT);
                ins++;
            } else if (same(cur, p, b)) {
                kinds.put(r.row(), PendingBatch.UNCHANGED);
                same++;
            } else {
                kinds.put(r.row(), PendingBatch.UPDATE);
                upd++;
            }
        }
        List<ElcStock> delist = new ArrayList<>();
        int onSale = 0;
        for (ElcStock s : existing.values()) {
            if (ElcStock.STATUS_ON.equals(s.getStatus())) {
                onSale++;
                if (MODE_REPLACE.equals(b.getMode()) && !keysInFile.contains(s.getLineKey())) {
                    delist.add(s);
                }
            }
        }
        return new PendingBatch(b.getBatchNo(), b.getSupplierNo(), deadline, parsed, kinds, delist, ins, upd, same,
                onSale);
    }

    private Map<String, ElcStock> existingByKey(String supplierNo) {
        Map<String, ElcStock> existing = new HashMap<>();
        for (ElcStock s : stockMapper.selectList(Wrappers.<ElcStock>lambdaQuery()
                .eq(ElcStock::getSupplierNo, supplierNo))) {
            existing.put(s.getLineKey(), s);
        }
        return existing;
    }

    /** 「未变」= 表里写了的每一项都和库里一样（空格子不算变化）。未变的行确认时照样续期 */
    private boolean same(ElcStock cur, Parsed p, ElcStockBatch b) {
        return cur.getQty() == p.qty()
                && (p.dateCode() == null || p.dateCode().equals(cur.getDateCode()))
                && (p.pkg() == null || p.pkg().equals(cur.getPkg()))
                && (p.moq() == null || p.moq().equals(cur.getMoq()))
                && (p.spq() == null || p.spq().equals(cur.getSpq()))
                && (p.packing() == null || p.packing().equals(cur.getPacking()))
                && (p.cond() == null || p.cond().equals(cur.getCondGrade()))
                && (p.leadDays() == null || p.leadDays().equals(cur.getLeadDays()))
                && (p.region() == null || p.region().equals(cur.getRegion()))
                && (p.priceE6() == null || (Objects.equals(tiersJson(p.tiers()), cur.getPriceTiers())
                && Objects.equals(p.currency() != null ? p.currency() : b.getCurrency(), cur.getCurrency())
                && Objects.equals(b.getTaxIncluded(), cur.getTaxIncluded())));
    }

    private void fill(ElcStockBatch b, PendingBatch pb) {
        b.setRowTotal(pb.parsed().total());
        b.setRowValid(pb.valid());
        b.setRowInvalid(pb.parsed().invalid());
        b.setRowWarn(pb.parsed().warn());
        b.setToInsert(pb.toInsert());
        b.setToUpdate(pb.toUpdate());
        b.setToDelist(pb.delist().size());
        b.setUnchanged(pb.unchanged());
    }

    private BatchPreview preview(ElcStockBatch b, PendingBatch pb) {
        if (pb == null) {
            return preview(b, null, List.of(), Map.of());
        }
        List<Issue> issues = new ArrayList<>();
        for (StockSheetParser.Row r : pb.parsed().rows()) {
            for (Issue x : r.issues()) {
                if (issues.size() >= MAX_ISSUES) {
                    break;
                }
                issues.add(x);
            }
        }
        return preview(b, pb, issues, pb.parsed().issueCounts());
    }

    private BatchPreview preview(ElcStockBatch b, PendingBatch pb, List<Issue> issues, Map<String, Integer> counts) {
        List<String> sample = pb == null ? List.of()
                : pb.delist().stream().limit(MAX_DELIST_SAMPLE).map(ElcStock::getMpnRaw).toList();
        // 过渡字段：老版本小程序读 problems（只有错误，一行一条）
        List<RowProblem> problems = new ArrayList<>();
        Set<Integer> seenRows = new HashSet<>();
        for (Issue x : issues) {
            if (StockSheetParser.ERROR.equals(x.level()) && problems.size() < MAX_PROBLEMS && seenRows.add(x.row())) {
                problems.add(new RowProblem(x.row(), x.code(),
                        "MPN_INVALID".equals(x.code()) ? x.value() : null));
            }
        }
        List<String> headers = b.getHeaders() == null ? List.of()
                : read(b.getHeaders(), new TypeReference<List<String>>() { });
        Map<String, Integer> columns = b.getColumnMap() == null ? Map.of()
                : read(b.getColumnMap(), new TypeReference<Map<String, Integer>>() { });
        Map<String, String> source = b.getColumnSource() == null ? Map.of()
                : read(b.getColumnSource(), new TypeReference<Map<String, String>>() { });
        return new BatchPreview(b.getBatchNo(), b.getFileName(), b.getMode(), Boolean.TRUE.equals(b.getTaxIncluded()),
                headers, b.getHeaderRow() == null ? -1 : b.getHeaderRow(), columns, source,
                n(b.getRowTotal()), n(b.getRowValid()), n(b.getRowInvalid()), n(b.getRowWarn()),
                n(b.getToInsert()), n(b.getToUpdate()), n(b.getToDelist()), n(b.getUnchanged()),
                counts, issues, problems, sample, pb != null && needDelistConfirm(pb),
                b.displayStatus(LocalDateTime.now(), ttl()), b.deadline(ttl()), b.getCreatedAt(), b.getAppliedAt());
    }

    private static int n(Integer v) {
        return v == null ? 0 : v;
    }

    // ── 批次、原件与缓存 ────────────────────────────────────────────────────

    private int ttl() {
        return props.getUpload().getPendingTtlMinutes();
    }

    private boolean alive(ElcStockBatch b) {
        return b.pendingAlive(LocalDateTime.now(), ttl());
    }

    /** 这家的批次；别人的一律 404（不泄露批次存在） */
    private ElcStockBatch own(String userNo, String batchNo) {
        ElcSupplier supplier = access.requireActive(userNo);
        ElcStockBatch b = batchMapper.selectOne(Wrappers.<ElcStockBatch>lambdaQuery()
                .eq(ElcStockBatch::getBatchNo, batchNo)
                .eq(ElcStockBatch::getSupplierNo, supplier.getSupplierNo()));
        if (b == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return b;
    }

    /** 还在有效期内的待确认批次。「过没过期」以库里的状态与创建时刻为准，不以缓存为准 */
    private ElcStockBatch pendingBatch(String userNo, String batchNo) {
        ElcStockBatch b = own(userNo, batchNo);
        if (!alive(b)) {
            throw BizException.of(ErrorCode.ELEC_BATCH_EXPIRED);
        }
        return b;
    }

    /** 内存里取；不在（被挤出、重启过）就从原件按存下的映射重建。原件也没了 = 过期 */
    private PendingBatch pending(ElcStockBatch b) {
        PendingBatch pb = cache.getOrRebuild(b.getBatchNo(), () -> rebuild(b));
        if (pb == null) {
            throw BizException.of(ErrorCode.ELEC_BATCH_EXPIRED);
        }
        return pb;
    }

    /** 从原件重建：<b>不重新认列</b>（不调大模型），用批次里存下的表头行、映射与阶梯价列 */
    private PendingBatch rebuild(ElcStockBatch b) {
        Optional<List<List<String>>> rows = readRows(b);
        if (rows.isEmpty() || b.getHeaderRow() == null) {
            return null;
        }
        Map<Field, Integer> map = fromWire(read(b.getColumnMap(), new TypeReference<Map<String, Integer>>() { }));
        return build(b, rows.get(), map, tierColsOf(b));
    }

    private Optional<List<List<String>>> readRows(ElcStockBatch b) {
        Optional<Path> p = files.locate(b.getFilePath(), UploadFileStore.APPLIED.equals(area(b))
                ? UploadFileStore.APPLIED : UploadFileStore.FAILED);
        if (p.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(SheetReader.read(Files.readAllBytes(p.get()), props.getUploadMaxRows()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String area(ElcStockBatch b) {
        return ElcStockBatch.AREA_APPLIED.equals(b.getFileArea()) ? UploadFileStore.APPLIED : UploadFileStore.FAILED;
    }

    /** 这家上一次<b>确认上架过</b>的批次，表头与这次一模一样时，沿用它的映射 */
    private ColumnResolver.Remembered remembered(String supplierNo, List<List<String>> rows) {
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
                return new ColumnResolver.Remembered(i,
                        fromWire(read(last.getColumnMap(), new TypeReference<Map<String, Integer>>() { })));
            }
        }
        return null;
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    // ── 序列化 ──────────────────────────────────────────────────────────────

    private Map<Field, String> sourceOf(ElcStockBatch b) {
        Map<Field, String> m = new EnumMap<>(Field.class);
        if (b.getColumnSource() == null) {
            return m;
        }
        read(b.getColumnSource(), new TypeReference<Map<String, String>>() { }).forEach((k, v) -> {
            Field f = fieldOf(k);
            if (f != null) {
                m.put(f, v);
            }
        });
        return m;
    }

    private static Map<String, Integer> toWire(Map<Field, Integer> map) {
        Map<String, Integer> m = new LinkedHashMap<>();
        map.forEach((k, v) -> m.put(k.name(), v));
        return m;
    }

    private static Map<String, String> toWireSource(Map<Field, String> map) {
        Map<String, String> m = new LinkedHashMap<>();
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
            Field f = fieldOf(k);
            if (f != null && v != null && v >= 0) {
                m.put(f, v);
            }
        });
        return m;
    }

    private static Field fieldOf(String name) {
        for (Field f : Field.values()) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** 存下来的阶梯价列。换列映射时按它重算 —— 重猜一次结果可能不同，而供应商看到的数会变 */
    private List<Columns.PriceTierCol> tierColsOf(ElcStockBatch b) {
        if (b.getTierCols() == null || b.getTierCols().isBlank()) {
            return List.of();
        }
        List<List<Number>> raw = read(b.getTierCols(), new TypeReference<List<List<Number>>>() { });
        return raw.stream().filter(x -> x.size() == 2)
                .map(x -> new Columns.PriceTierCol(x.get(0).intValue(), x.get(1).longValue())).toList();
    }

    /** 阶梯价 JSON：[{"minQty":1,"e6":1850000},…]。空列表存 null（「没报价」与「报了个空表」不是一回事） */
    private String tiersJson(List<long[]> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return null;
        }
        return write(tiers.stream().map(t -> Map.of("minQty", t[0], "e6", t[1])).toList());
    }

    private static List<PriceTier> tiersOf(List<long[]> tiers) {
        return tiers == null ? List.of() : tiers.stream().map(t -> new PriceTier(t[0], t[1])).toList();
    }

    /** 问题行的 issues 列：短键省字节（列宽 1024） */
    private String issuesJson(List<Issue> issues) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Issue x : issues) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("c", x.code());
            m.put("l", x.level());
            m.put("col", x.col());
            m.put("h", x.header() == null ? "" : truncate(x.header(), 24));
            m.put("v", truncate(x.value(), 48));
            out.add(m);
        }
        String s = write(out);
        // 一行的问题极少超过 4 处；真超了宁可丢原值也不丢码
        return s.length() <= 1024 ? s : write(out.stream().map(m -> {
            Map<String, Object> t = new LinkedHashMap<>(m);
            t.put("v", "");
            t.put("h", "");
            return t;
        }).toList());
    }

    private List<Issue> issuesOf(String s, int row) {
        List<Map<String, Object>> raw = read(s, new TypeReference<List<Map<String, Object>>>() { });
        return raw.stream().map(m -> new Issue(row, ((Number) m.getOrDefault("col", -1)).intValue(),
                (String) m.get("h"), (String) m.get("v"), (String) m.get("c"), (String) m.get("l"))).toList();
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }

    private String write(Object v) {
        return json.writeValueAsString(v);
    }

    private <T> T read(String s, TypeReference<T> type) {
        return json.readValue(s, type);
    }
}
