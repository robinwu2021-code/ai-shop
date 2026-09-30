package ai.neargo.shop.elec.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SheetWriterTest {

    @Test
    @DisplayName("★★★ AC8 全部写成文本格：0805、长料号、前导零读回来一个字不变；出错的格标红")
    void allCellsTextAndErrorCellsRed() throws Exception {
        List<List<String>> rows = List.of(
                List.of("型号", "数量", "原行号", "问题"),
                List.of("0805", "约2千", "12", "B12（数量）数量读不出：约2千"),
                List.of("1E5", "007", "13", "x & <y>"));
        byte[] x = SheetWriter.xlsx("问题行", rows, Set.of(SheetWriter.cell(1, 1)));
        assertThat(SheetReader.read(x, 100)).isEqualTo(rows);
        String sheet = entry(x, "xl/worksheets/sheet1.xml");
        assertThat(sheet).doesNotContain("t=\"n\"").contains("<c r=\"B2\" t=\"inlineStr\" s=\"1\">");
        assertThat(sheet).contains("<c r=\"A2\" t=\"inlineStr\">");
    }

    @Test
    @DisplayName("★ 列名：0→A、25→Z、26→AA、701→ZZ")
    void colName() {
        assertThat(SheetWriter.colName(0)).isEqualTo("A");
        assertThat(SheetWriter.colName(25)).isEqualTo("Z");
        assertThat(SheetWriter.colName(26)).isEqualTo("AA");
        assertThat(SheetWriter.colName(701)).isEqualTo("ZZ");
    }

    private static String entry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("没有 " + name);
    }
}
