package ai.neargo.shop.elec.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UploadFileStoreTest {

    @TempDir
    Path root;

    private static final byte[] XLSX = {'P', 'K', 3, 4, 0};
    private static final byte[] CSV = "型号,数量\n".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("★★★ AC19 原名保留，放在 failed/日期/供应商号/ 下，后缀带批次号；扩展名按魔数定")
    void pathKeepsNameUnderDateAndSupplier() throws Exception {
        UploadFileStore s = new UploadFileStore(root);
        UploadFileStore.Stored st = s.storeFailed(LocalDate.of(2026, 10, 8), "S001", "EB123", "9月库存（华强）.csv", XLSX);
        assertThat(st.relPath()).isEqualTo("2026-10-08/S001/9月库存（华强）_EB123.xlsx");
        assertThat(Files.readAllBytes(root.resolve("failed").resolve(st.relPath()))).isEqualTo(XLSX);
        assertThat(st.sha256()).hasSize(64);
        assertThat(Files.list(root.resolve("failed/2026-10-08/S001")).toList()).as("没有残留的 .part").hasSize(1);
    }

    @Test
    @DisplayName("★★★ 原名净化：去路径穿越、非法字符、首尾点；超长按字节截（不切断汉字）；全非法 → upload")
    void sanitizesTraversalAndLongNames() {
        assertThat(UploadFileStore.safeBase("../../etc/passwd")).isEqualTo("passwd");
        assertThat(UploadFileStore.safeBase("C:\\Users\\a\\库存.xlsx")).isEqualTo("库存");
        assertThat(UploadFileStore.safeBase("a<b>:c?.xlsx")).isEqualTo("a_b__c_");
        assertThat(UploadFileStore.safeBase("..x..y.csv")).isEqualTo("_x_y");
        assertThat(UploadFileStore.safeBase("???.xlsx")).isEqualTo("___");
        assertThat(UploadFileStore.safeBase("   ")).isEqualTo("upload");
        assertThat(UploadFileStore.safeBase(null)).isEqualTo("upload");
        String longName = "库".repeat(100) + ".xlsx";
        String cut = UploadFileStore.safeBase(longName);
        assertThat(cut.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(UploadFileStore.NAME_MAX_BYTES);
        assertThat(cut).isEqualTo("库".repeat(50));
    }

    @Test
    @DisplayName("★★ .xls 也落盘（解析失败的原件恰恰常是它）；认不出魔数的按 csv")
    void extByMagic() {
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0};
        assertThat(UploadFileStore.ext(ole)).isEqualTo("xls");
        assertThat(UploadFileStore.ext(CSV)).isEqualTo("csv");
    }

    @Test
    @DisplayName("★★★ 移到已入库区是同一相对路径；找文件先看记录的区、找不到再看另一区")
    void moveAndLocate() {
        UploadFileStore s = new UploadFileStore(root);
        String rel = s.storeFailed(LocalDate.of(2026, 10, 8), "S1", "B1", "a.csv", CSV).relPath();
        assertThat(s.locate(rel, UploadFileStore.APPLIED)).as("记录说在已入库区、其实还在未入库区：照样找得到").isPresent();
        assertThat(s.moveToApplied(rel)).isTrue();
        assertThat(Files.exists(root.resolve("applied").resolve(rel))).isTrue();
        assertThat(Files.exists(root.resolve("failed").resolve(rel))).isFalse();
        assertThat(s.locate(rel, UploadFileStore.FAILED)).isPresent();
        assertThat(s.moveToApplied(rel)).as("已经在已入库区也算成功").isTrue();
        assertThat(s.locate("../../x", UploadFileStore.FAILED)).as("库里的路径被改成 ../ 也出不去").isEmpty();
    }

    @Test
    @DisplayName("★★★ AC19b 按目录名里的日期删，不看 mtime；跳过的日期、不像日期的目录都不动")
    void purgeOnlyOldFailedDirsByName() throws Exception {
        UploadFileStore s = new UploadFileStore(root);
        s.storeFailed(LocalDate.of(2026, 9, 1), "S1", "B1", "old.csv", CSV);
        s.storeFailed(LocalDate.of(2026, 9, 2), "S1", "B2", "keep.csv", CSV);
        s.storeFailed(LocalDate.of(2026, 10, 8), "S1", "B3", "today.csv", CSV);
        Files.createDirectories(root.resolve("failed/not-a-date"));
        // 旧目录被 touch 成「刚改过」：按 mtime 判就删不掉它
        Files.setLastModifiedTime(root.resolve("failed/2026-09-01"), FileTime.from(Instant.now()));
        List<String> unknown = new ArrayList<>();
        List<UploadFileStore.Purged> done = s.purge(UploadFileStore.FAILED, LocalDate.of(2026, 10, 1),
                Set.of("2026-09-02"), unknown);
        assertThat(done).extracting(UploadFileStore.Purged::day).containsExactly("2026-09-01");
        assertThat(done.get(0).bytes()).isEqualTo(CSV.length);
        assertThat(Files.exists(root.resolve("failed/2026-09-02"))).isTrue();
        assertThat(Files.exists(root.resolve("failed/2026-10-08"))).isTrue();
        assertThat(unknown).containsExactly("failed/not-a-date");
    }

    @Test
    @DisplayName("★★ 启动自检：两区建好；目录写不了就抛（服务起不来）")
    void selfCheck() throws Exception {
        new UploadFileStore(root).selfCheck();
        assertThat(Files.isDirectory(root.resolve("failed"))).isTrue();
        assertThat(Files.isDirectory(root.resolve("applied"))).isTrue();
        Path file = Files.writeString(root.resolve("plain-file"), "x");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new UploadFileStore(file).selfCheck())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不可写");
    }
}
