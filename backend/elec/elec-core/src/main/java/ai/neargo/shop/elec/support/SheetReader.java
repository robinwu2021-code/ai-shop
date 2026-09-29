package ai.neargo.shop.elec.support;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 把供应商传来的表读成「行 × 单元格」的字符串矩阵。支持 {@code .xlsx} 与 {@code .csv}。
 *
 * <p><b>不引 Apache POI</b>：xlsx 就是一个 zip 里的几份 XML，第一张表 + 共享字符串就够用，
 * 为此往 jar 里加十几 MB 不值得（也会给 GraalVM 那条线添麻烦）。代价是<b>不认 .xls</b>（二进制格式），
 * 碰到就明说「另存为 xlsx 或 csv」。
 *
 * <p>三道防线：解压总量封顶（zip 炸弹）、XML 关掉 DTD 与外部实体（XXE）、行数封顶。
 */
public final class SheetReader {

    /** 解压后的总字节上限。5MB 的 xlsx 正常解开也就几十 MB */
    private static final long MAX_INFLATED = 64L * 1024 * 1024;

    private SheetReader() {
    }

    /**
     * @param maxRows 不含表头的最大行数；超了直接拒，不静默截断 —— 截断会让「全量替换」把后半截全下架
     */
    public static List<List<String>> read(byte[] bytes, int maxRows) {
        if (bytes == null || bytes.length == 0) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_FORMAT);
        }
        List<List<String>> rows;
        if (isZip(bytes)) {
            rows = readXlsx(bytes);
        } else if (isOle(bytes)) {
            // .xls（Excel 97-2003）。认不了，明说怎么办
            throw BizException.of(ErrorCode.ELEC_UPLOAD_FORMAT);
        } else {
            rows = readCsv(decode(bytes));
        }
        // 去掉尾部的全空行（Excel 里被格式化过的空行会出现在 sheet.xml 里）
        while (!rows.isEmpty() && blank(rows.get(rows.size() - 1))) {
            rows.remove(rows.size() - 1);
        }
        if (rows.size() - 1 > maxRows) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_TOO_MANY_ROWS, maxRows);
        }
        return rows;
    }

    public static boolean blank(List<String> row) {
        for (String c : row) {
            if (c != null && !c.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isZip(byte[] b) {
        return b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    private static boolean isOle(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF
                && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0;
    }

    // ── csv ────────────────────────────────────────────────────────────────

    /**
     * 先按 UTF-8 严格解，失败再按 GBK —— 中文版 Excel「另存为 CSV」默认是 GBK，
     * 按 UTF-8 宽松解的话中文表头全成乱码，而表头认不出等于整张表认不出。
     */
    static String decode(byte[] bytes) {
        int off = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /** 逗号或制表符分隔（看第一行哪个多），支持双引号包裹与 "" 转义 */
    static List<List<String>> readCsv(String text) {
        String firstLine = text.lines().findFirst().orElse("");
        char sep = count(firstLine, '\t') > count(firstLine, ',') ? '\t' : ',';
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == sep) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().trim());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString().trim());
            rows.add(row);
        }
        return rows;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    // ── xlsx ───────────────────────────────────────────────────────────────

    private static List<List<String>> readXlsx(byte[] bytes) {
        Map<String, byte[]> parts = unzip(bytes);
        List<String> shared = parts.containsKey("xl/sharedStrings.xml")
                ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
        byte[] sheet = parts.get(firstSheetPath(parts));
        if (sheet == null) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_FORMAT);
        }
        return sheet(sheet, shared);
    }

    private static Map<String, byte[]> unzip(byte[] bytes) {
        Map<String, byte[]> out = new HashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                // 只要这几份，其余（样式、主题、图片）不解 —— 也就不计入解压总量
                if (!name.equals("xl/workbook.xml") && !name.equals("xl/_rels/workbook.xml.rels")
                        && !name.equals("xl/sharedStrings.xml") && !name.startsWith("xl/worksheets/sheet")) {
                    continue;
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_INFLATED) {
                        throw BizException.of(ErrorCode.ELEC_UPLOAD_FORMAT);
                    }
                    bo.write(buf, 0, n);
                }
                out.put(name, bo.toByteArray());
            }
        } catch (IOException e) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_FORMAT);
        }
        return out;
    }

    /**
     * 第一张<b>工作表</b>在哪。不能直接取 sheet1.xml —— 用户把表挪过顺序之后，
     * sheet1 可能是第三个标签页。按 workbook.xml 第一个 sheet 的 r:id 去 rels 里查。
     */
    private static String firstSheetPath(Map<String, byte[]> parts) {
        String fallback = "xl/worksheets/sheet1.xml";
        byte[] wb = parts.get("xl/workbook.xml");
        byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
        if (wb == null || rels == null) {
            return fallback;
        }
        try {
            String rid = null;
            XMLStreamReader r = xml(wb);
            while (r.hasNext() && rid == null) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(r.getLocalName())) {
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        if ("id".equals(r.getAttributeLocalName(i))) {
                            rid = r.getAttributeValue(i);
                        }
                    }
                }
            }
            if (rid == null) {
                return fallback;
            }
            XMLStreamReader rr = xml(rels);
            while (rr.hasNext()) {
                if (rr.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(rr.getLocalName())
                        && rid.equals(rr.getAttributeValue(null, "Id"))) {
                    String target = rr.getAttributeValue(null, "Target");
                    if (target == null) {
                        return fallback;
                    }
                    return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                }
            }
        } catch (XMLStreamException e) {
            return fallback;
        }
        return fallback;
    }

    private static List<String> sharedStrings(byte[] xml) {
        List<String> out = new ArrayList<>();
        try {
            XMLStreamReader r = xml(xml);
            StringBuilder cur = null;
            boolean inT = false;
            boolean inRph = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "si" -> cur = new StringBuilder();
                        case "t" -> inT = true;
                        // 注音（rPh）里的 <t> 不是正文，跳过
                        case "rPh" -> inRph = true;
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "si" -> out.add(cur == null ? "" : cur.toString());
                        case "t" -> inT = false;
                        case "rPh" -> inRph = false;
                        default -> { }
                    }
                } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA)
                        && inT && !inRph && cur != null) {
                    cur.append(r.getText());
                }
            }
        } catch (XMLStreamException e) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_FORMAT);
        }
        return out;
    }

    private static List<List<String>> sheet(byte[] xml, List<String> shared) {
        List<List<String>> rows = new ArrayList<>();
        try {
            XMLStreamReader r = xml(xml);
            List<String> row = null;
            int col = 0;
            String type = null;
            StringBuilder val = null;
            // 只收 <v> 与 <t> 里的字：<f>（公式原文）也在 <c> 里，收进来就成了「=SUM(B2:B9)1200」
            boolean capture = false;
            int lastRow = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> {
                            // 空行在 sheet.xml 里不出现：按 r 属性补齐，行号才对得上 Excel 里看到的
                            String rn = r.getAttributeValue(null, "r");
                            int idx = rn == null ? lastRow + 1 : Integer.parseInt(rn);
                            while (lastRow + 1 < idx) {
                                rows.add(new ArrayList<>());
                                lastRow++;
                            }
                            lastRow = idx;
                            row = new ArrayList<>();
                            col = 0;
                        }
                        case "c" -> {
                            String ref = r.getAttributeValue(null, "r");
                            col = ref == null ? col : colIndex(ref);
                            type = r.getAttributeValue(null, "t");
                            val = new StringBuilder();
                        }
                        case "v", "t" -> {
                            if (val == null) {
                                val = new StringBuilder();
                            }
                            capture = true;
                        }
                        default -> { }
                    }
                } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA)
                        && capture && val != null) {
                    val.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "v", "t" -> capture = false;
                        case "c" -> {
                            if (row != null) {
                                while (row.size() < col) {
                                    row.add("");
                                }
                                row.add(cellValue(type, val == null ? "" : val.toString(), shared));
                                col++;
                            }
                            val = null;
                        }
                        case "row" -> {
                            if (row != null) {
                                rows.add(row);
                            }
                            row = null;
                        }
                        default -> { }
                    }
                }
            }
        } catch (XMLStreamException | NumberFormatException e) {
            throw BizException.of(ErrorCode.ELEC_UPLOAD_FORMAT);
        }
        return rows;
    }

    private static String cellValue(String type, String raw, List<String> shared) {
        String v = raw.trim();
        if ("s".equals(type)) {
            try {
                int i = Integer.parseInt(v);
                return i >= 0 && i < shared.size() ? shared.get(i).trim() : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if (type == null || "n".equals(type)) {
            // 数字格：1.0E4 → 10000，5.0 → 5，0.0015 原样
            try {
                return v.isEmpty() ? "" : new BigDecimal(v).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return v;
            }
        }
        if ("b".equals(type)) {
            return "1".equals(v) ? "TRUE" : "FALSE";
        }
        return v;
    }

    /** {@code AB12} → 27（从 0 起） */
    static int colIndex(String ref) {
        int n = 0;
        for (char c : ref.toCharArray()) {
            if (c < 'A' || c > 'Z') {
                break;
            }
            n = n * 26 + (c - 'A' + 1);
        }
        return n - 1;
    }

    private static XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        InputStream in = new ByteArrayInputStream(bytes);
        return f.createXMLStreamReader(in);
    }
}
