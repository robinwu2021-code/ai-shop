package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.SkuIdentityService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 身份三列的批量导入导出。口径与失败模式见 {@link SkuIdentityService} 的接口注释。
 */
@Service
public class SkuIdentityServiceImpl implements SkuIdentityService {

    /** 只导本土市场那一份：AE/US 是同一个 skuNo 的另一行价格，不是另一件货 */
    private static final String HOME_MARKET = "CN";

    /** 预览给几行。多了他也不会逐行看，少了看不出「改的是不是我想的那些」 */
    private static final int SAMPLE_LIMIT = 20;

    private final SkuMapper skuMapper;
    private final GoodsMapper goodsMapper;

    public SkuIdentityServiceImpl(SkuMapper skuMapper, GoodsMapper goodsMapper) {
        this.skuMapper = skuMapper;
        this.goodsMapper = goodsMapper;
    }

    // ------------------------------------------------------------------ 导出

    @Override
    public String exportCsv(String merchantNo) {
        List<PrdSku> skus = skusOf(merchantNo);
        Map<String, String> titles = titlesOf(skus);

        StringBuilder sb = new StringBuilder();
        /*
         * **BOM 不是可选项。** 没有它，Excel 会按本地代码页（简中是 GBK）读这个文件，
         * 表头「条码」「货号」直接是乱码 —— 而商家看到乱码的第一反应是「导出坏了」，
         * 不会想到是编码。多三个字节换掉一整类支持工单。
         */
        sb.append('﻿');
        sb.append(String.join(",", COL_SKU_NO, COL_GOODS, COL_SPEC, COL_BARCODE, COL_CODE, COL_UNIT))
                .append('\n');
        for (PrdSku s : skus) {
            sb.append(String.join(",",
                    q(s.getSkuNo()),
                    q(titles.getOrDefault(s.getGoodsNo(), "")),
                    q(s.getOptionValues()),
                    q(s.getBarcode()),
                    q(s.getMerchantSkuCode()),
                    q(s.getSaleUnit()))).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 导入

    @Override
    public ImportReport plan(String merchantNo, String csv) {
        return run(merchantNo, csv, false);
    }

    @Override
    @Transactional
    public ImportReport apply(String merchantNo, String csv) {
        return run(merchantNo, csv, true);
    }

    /**
     * 试算与真写走**同一条**代码路径，只在最后一步分叉。
     *
     * <p>分成两份实现的话，「试算说会改 12 行、真导入改了 9 行」这种事迟早发生，
     * 而那会让试算这一步失去全部意义 —— 他下次就直接跳过它了。
     */
    private ImportReport run(String merchantNo, String csv, boolean write) {
        List<String[]> rows = parseCsv(csv);
        List<Problem> problems = new ArrayList<>();
        List<Change> samples = new ArrayList<>();
        if (rows.isEmpty()) {
            problems.add(new Problem(1, "文件是空的"));
            return new ImportReport(0, 0, 0, problems, samples);
        }

        Map<String, Integer> col = header(rows.get(0));
        if (!col.containsKey(COL_SKU_NO) && !col.containsKey(COL_CODE)) {
            /*
             * 两个键都没有就没法认行。这里必须**明确报错而不是跳过所有行** ——
             * 「0 行更新、0 个错误」看起来像成功。
             */
            problems.add(new Problem(1, "表头里既没有 " + COL_SKU_NO + " 也没有 " + COL_CODE
                    + "，认不出每一行对应哪个规格。请用导出的文件改，别自己新建"));
            return new ImportReport(0, 0, 0, problems, samples);
        }

        List<PrdSku> skus = skusOf(merchantNo);
        Map<String, PrdSku> bySkuNo = new HashMap<>();
        Map<String, PrdSku> byCode = new HashMap<>();
        for (PrdSku s : skus) {
            bySkuNo.put(s.getSkuNo(), s);
            if (s.getMerchantSkuCode() != null && !s.getMerchantSkuCode().isBlank()) {
                byCode.put(s.getMerchantSkuCode().trim(), s);
            }
        }
        Map<String, String> titles = titlesOf(skus);

        // 这一份文件内部的货号占用：同一次导入里把两行写成同一个货号，也是冲突
        Map<String, Integer> codeTakenAt = new HashMap<>();
        Set<String> touched = new HashSet<>();
        List<PrdSku> toWrite = new ArrayList<>();
        int total = 0;
        int willSet = 0;
        int noChange = 0;

        for (int i = 1; i < rows.size(); i++) {
            String[] r = rows.get(i);
            int line = i + 1;               // 表头是第 1 行，报给他的要与 Excel 对得上
            if (isBlankRow(r)) {
                continue;                   // 尾部空行是常态，不算数据行也不算错
            }
            total++;

            String skuNo = cell(r, col, COL_SKU_NO);
            String code = cell(r, col, COL_CODE);
            PrdSku sku = null;
            if (skuNo != null && !skuNo.isBlank()) {
                sku = bySkuNo.get(skuNo.trim());
                if (sku == null) {
                    problems.add(new Problem(line, skuNo + " 不是本店的规格行"));
                    continue;
                }
            } else if (code != null && !code.isBlank() && !CLEAR.equals(code.trim())) {
                /*
                 * **货号回退。** 他从自己 ERP 导出的表只有货号，没有我们的 skuNo。
                 * 认得出就认，认不出明说 —— 这一条正是「按货号导入会把条码清空」
                 * 那个坑的解法：先把货号解析成行，再谈写什么。
                 */
                sku = byCode.get(code.trim());
                if (sku == null) {
                    problems.add(new Problem(line, "货号 " + code.trim() + " 在本店找不到对应的规格行"));
                    continue;
                }
            } else {
                problems.add(new Problem(line, "这一行既没有 " + COL_SKU_NO + " 也没有货号，认不出改哪一行"));
                continue;
            }

            if (!touched.add(sku.getSkuNo())) {
                problems.add(new Problem(line, "同一个规格行在文件里出现了不止一次"));
                continue;
            }

            String barcode = merge(cell(r, col, COL_BARCODE), sku.getBarcode(), col.containsKey(COL_BARCODE));
            String newCode = merge(code, sku.getMerchantSkuCode(), col.containsKey(COL_CODE));
            String unit = merge(cell(r, col, COL_UNIT), sku.getSaleUnit(), col.containsKey(COL_UNIT));

            // 货号是唯一键（V252），撞了就得当场说，别等数据库抛
            if (newCode != null && !newCode.equals(sku.getMerchantSkuCode())) {
                PrdSku owner = byCode.get(newCode);
                Integer dupLine = codeTakenAt.get(newCode);
                if (owner != null && !owner.getSkuNo().equals(sku.getSkuNo())) {
                    problems.add(new Problem(line, "货号 " + newCode + " 已经被本店另一个规格行占着"));
                    continue;
                }
                if (dupLine != null) {
                    problems.add(new Problem(line, "货号 " + newCode + " 与第 " + dupLine + " 行重复"));
                    continue;
                }
                codeTakenAt.put(newCode, line);
            }

            boolean same = eq(barcode, sku.getBarcode())
                    && eq(newCode, sku.getMerchantSkuCode())
                    && eq(unit, sku.getSaleUnit());
            if (same) {
                noChange++;
                continue;
            }

            if (samples.size() < SAMPLE_LIMIT) {
                samples.add(new Change(sku.getSkuNo(),
                        titles.getOrDefault(sku.getGoodsNo(), ""), sku.getOptionValues(),
                        sku.getBarcode(), barcode,
                        sku.getMerchantSkuCode(), newCode,
                        sku.getSaleUnit(), unit));
            }
            willSet++;

            PrdSku row = new PrdSku();
            row.setId(sku.getId());
            row.setBarcode(barcode);
            row.setMerchantSkuCode(newCode);
            row.setSaleUnit(unit);
            toWrite.add(row);
        }

        if (write) {
            /*
             * 逐行 updateById。**三列都声明了 updateStrategy=ALWAYS**（见 PrdSku），
             * 所以 null 会真的写成 NULL —— 这正是「清空」要的效果。
             * 反过来说：不想改的列必须在上面 merge 成「原值」，而不是留 null。
             */
            for (PrdSku row : toWrite) {
                DataScopeContext.executeWithoutScope(() -> skuMapper.updateById(row));
            }
        }
        return new ImportReport(total, willSet, noChange, problems, samples);
    }

    // ------------------------------------------------------------------ 取数

    private List<PrdSku> skusOf(String merchantNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return List.of();
        }
        // 带域表：B 端直查读写都要绕过数据域，否则 SELECT 空、UPDATE 静默 0 行
        return DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getEntityNo, merchantNo)
                        .eq(PrdSku::getMarket, HOME_MARKET)
                        .orderByAsc(PrdSku::getGoodsNo)
                        .orderByAsc(PrdSku::getId)));
    }

    private Map<String, String> titlesOf(List<PrdSku> skus) {
        Set<String> goodsNos = new HashSet<>();
        for (PrdSku s : skus) {
            if (s.getGoodsNo() != null) {
                goodsNos.add(s.getGoodsNo());
            }
        }
        if (goodsNos.isEmpty()) {
            return Map.of();
        }
        List<PrdGoods> gs = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                        .in(PrdGoods::getGoodsNo, goodsNos)));
        Map<String, String> out = new LinkedHashMap<>();
        for (PrdGoods g : gs) {
            out.put(g.getGoodsNo(), g.getTitle() == null ? "" : g.getTitle());
        }
        return out;
    }

    // ------------------------------------------------------------------ CSV

    /**
     * 合并一个格子与库里的现值。**这三行就是整个功能的安全边界**：
     *
     * <ul>
     *   <li>整列不在表头 → 原值（他删了这一列，就是不想动）</li>
     *   <li>格子是空的 → 原值（ERP 导出的表天生有空列）</li>
     *   <li>格子写着 {@code -} → {@code null}（显式清空）</li>
     * </ul>
     */
    private static String merge(String cell, String current, boolean columnPresent) {
        if (!columnPresent || cell == null) {
            return current;
        }
        String v = cell.trim();
        if (v.isEmpty()) {
            return current;
        }
        return CLEAR.equals(v) ? null : v;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String cell(String[] row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.length ? null : row[i];
    }

    private static boolean isBlankRow(String[] row) {
        for (String c : row) {
            if (c != null && !c.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** 表头 → 列名到下标。大小写与前后空格都不计较，Excel 里手抖很常见 */
    private static Map<String, Integer> header(String[] row) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < row.length; i++) {
            String h = row[i] == null ? "" : row[i].trim();
            if (h.isEmpty()) {
                continue;
            }
            for (String known : List.of(COL_SKU_NO, COL_GOODS, COL_SPEC, COL_BARCODE, COL_CODE, COL_UNIT)) {
                if (known.equalsIgnoreCase(h)) {
                    out.putIfAbsent(known, i);
                }
            }
        }
        return out;
    }

    /**
     * 够用的 CSV 解析：双引号包裹、引号内的逗号与换行、{@code ""} 转义。
     *
     * <p>不引第三方库：这里要读的是六列纯文本，而 commons-csv 会把一个
     * 「导入个商品编码」的功能变成一次依赖评审。
     */
    static List<String[]> parseCsv(String text) {
        List<String[]> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        if (text.charAt(0) == '﻿') {
            text = text.substring(1);       // 他导出去改完再传回来，BOM 还在
        }
        List<String> cur = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    sb.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> inQuote = true;
                case ',' -> {
                    cur.add(sb.toString());
                    sb.setLength(0);
                }
                case '\r' -> { /* \r\n 与 \n 都收 */ }
                case '\n' -> {
                    cur.add(sb.toString());
                    sb.setLength(0);
                    rows.add(cur.toArray(new String[0]));
                    cur = new ArrayList<>();
                }
                default -> sb.append(c);
            }
        }
        if (sb.length() > 0 || !cur.isEmpty()) {
            cur.add(sb.toString());
            rows.add(cur.toArray(new String[0]));
        }
        return rows;
    }

    /** 写一个格子：含逗号/引号/换行就包起来 */
    private static String q(String v) {
        if (v == null) {
            return "";
        }
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0) {
            return v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
