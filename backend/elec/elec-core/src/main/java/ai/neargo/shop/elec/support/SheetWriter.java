package ai.neargo.shop.elec.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 写一张最小的 xlsx（问题行导出用）。
 *
 * <p><b>每一格都是文本</b>（{@code t="inlineStr"}）：写成数字格的话 Excel 一打开 {@code 0805} 就成了 {@code 805}、
 * 长料号变成科学计数 —— 他改完回传时料号已经坏了，而「改完直接回传」正是导出的目的。
 *
 * <p>不引 POI（与 {@link SheetReader} 同一个理由）；不导 CSV（小程序 openDocument 不认 csv）。
 * 写出来的文件 {@link SheetReader} 能原样读回。
 */
public final class SheetWriter {

    /** 标红那一格的样式序号（styles.xml 的 cellXfs 第 2 个） */
    private static final int RED = 1;

    private SheetWriter() {
    }

    /**
     * @param rows 第 0 行是表头
     * @param red  要标红的格：{@link #cell(int, int)} 编出来的键（行号从 0 起，含表头行）
     */
    public static byte[] xlsx(String sheetName, List<List<String>> rows, Set<Long> red) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                    <Default Extension="xml" ContentType="application/xml"/>\
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>\
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                    </Relationships>""");
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                    <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets></workbook>""".formatted(esc(sheetName)));
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>\
                    </Relationships>""");
            // fills 的前两个是规范要求的占位（none、gray125），自定义的从第 3 个起
            put(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                    <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>\
                    <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>\
                    <fill><patternFill patternType="solid"><fgColor rgb="FFFFC7CE"/><bgColor indexed="64"/></patternFill></fill></fills>\
                    <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>\
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
                    <cellXfs count="2"><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
                    <xf numFmtId="49" fontId="0" fillId="2" borderId="0" xfId="0" applyNumberFormat="1" applyFill="1"/></cellXfs>\
                    </styleSheet>""");
            StringBuilder sb = new StringBuilder(64 + rows.size() * 128);
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                    .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            for (int r = 0; r < rows.size(); r++) {
                sb.append("<row r=\"").append(r + 1).append("\">");
                List<String> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    String v = row.get(c);
                    boolean isRed = red.contains(cell(r, c));
                    if (v == null || v.isEmpty()) {
                        // 空格也要标红：「没有料号」那一格恰恰是空的，而它是最该被看见的那一格
                        if (isRed) {
                            sb.append("<c r=\"").append(colName(c)).append(r + 1).append("\" s=\"").append(RED)
                                    .append("\"/>");
                        }
                        continue;
                    }
                    sb.append("<c r=\"").append(colName(c)).append(r + 1).append("\" t=\"inlineStr\"");
                    if (isRed) {
                        sb.append(" s=\"").append(RED).append('"');
                    }
                    sb.append("><is><t xml:space=\"preserve\">").append(esc(v)).append("</t></is></c>");
                }
                sb.append("</row>");
            }
            sb.append("</sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet1.xml", sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    /** 标红格的键 */
    public static long cell(int row, int col) {
        return ((long) row << 20) | col;
    }

    /** 0 → A，25 → Z，26 → AA */
    public static String colName(int col) {
        StringBuilder sb = new StringBuilder();
        int n = col + 1;
        while (n > 0) {
            int m = (n - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** XML 转义，并去掉 XML 1.0 不允许的控制字符（Excel 单元格里偶尔有，写进去整个文件就打不开） */
    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
