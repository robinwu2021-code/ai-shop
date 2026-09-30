package ai.neargo.shop.elec.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 上传原件的存放。
 *
 * <pre>
 * &lt;root&gt;/failed/2026-10-08/S001/9月库存（华强）_B20261008xxxx.xlsx    未入库：上传一律先落这里
 * &lt;root&gt;/applied/2026-10-08/S001/9月库存（华强）_B20261008xxxx.xlsx   已入库：确认上架后原样移过来
 * </pre>
 *
 * <p><b>一律先落未入库区</b>：只在成功时才落盘的话，解析失败的原件就留不下来 —— 而那正是最需要看原件的时候。
 * 两区里的<b>相对路径相同</b>，库里只存相对路径与区，移动只改区。
 *
 * <p>不依赖 Spring，便于单测；清理按<b>目录名</b>里的日期判断，不看 mtime（拷贝、touch、备份恢复都会改它）。
 */
public final class UploadFileStore {

    public static final String FAILED = "failed";
    public static final String APPLIED = "applied";

    /** 原名净化后的上限。Linux 单个文件名 255 字节，要给 {@code _批次号.xlsx} 留出余量 */
    static final int NAME_MAX_BYTES = 150;

    private final Path root;

    public UploadFileStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** @param relPath 相对路径（不含区）；{@code ext} 按魔数定，不信原名 */
    public record Stored(String relPath, long size, String sha256, String ext) {
    }

    /** 清理掉的一个日期目录 */
    public record Purged(String area, String day, long bytes) {
    }

    /**
     * 启动自检：两区建好并各写一个探针。<b>失败就让服务起不来</b> ——
     * 目录没权限时服务照常起来的话，第一个供应商上传才炸，那时看到的只是一个 500。
     */
    public void selfCheck() {
        for (String area : List.of(FAILED, APPLIED)) {
            Path dir = root.resolve(area);
            try {
                Files.createDirectories(dir);
                Path probe = Files.createTempFile(dir, ".probe", ".tmp");
                Files.delete(probe);
            } catch (IOException e) {
                throw new IllegalStateException("上传目录不可写：" + dir + "（运行用户 " + System.getProperty("user.name")
                        + "）。生产上要 mkdir 并 chown 给服务用户", e);
            }
        }
    }

    /** 落进未入库区。先写 {@code .part} 再原子改名 —— 重建时读不到半个文件 */
    public Stored storeFailed(LocalDate day, String supplierNo, String batchNo, String originalName, byte[] bytes) {
        String ext = ext(bytes);
        String rel = day + "/" + safeSegment(supplierNo) + "/" + safeBase(originalName) + "_" + safeSegment(batchNo) + "." + ext;
        Path target = root.resolve(FAILED).resolve(rel);
        try {
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.write(part, bytes);
            move(part, target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Stored(rel, bytes.length, sha256(bytes), ext);
    }

    /**
     * 找原件：先看记录的那个区，找不到再看另一区 —— 移动成功了但库没来得及改（或反过来）时照样找得到。
     *
     * @param area {@link #FAILED} / {@link #APPLIED}；null 等同未入库
     */
    public Optional<Path> locate(String relPath, String area) {
        if (relPath == null || relPath.isBlank()) {
            return Optional.empty();
        }
        String first = APPLIED.equals(area) ? APPLIED : FAILED;
        String second = APPLIED.equals(first) ? FAILED : APPLIED;
        for (String a : List.of(first, second)) {
            Path p = inside(a, relPath);
            if (p != null && Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** @return 移完之后原件确实在已入库区（本来就在也算）；两区都没有为 false */
    public boolean moveToApplied(String relPath) {
        Path to = inside(APPLIED, relPath);
        if (to == null) {
            return false;
        }
        if (Files.isRegularFile(to)) {
            return true;
        }
        Path from = inside(FAILED, relPath);
        if (from == null || !Files.isRegularFile(from)) {
            return false;
        }
        try {
            Files.createDirectories(to.getParent());
            move(from, to);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 删掉某区里早于 {@code before} 的日期目录（整个目录，含下面的供应商目录）。
     *
     * @param skipDays 这次不删的日期（补移失败的那几天：里面有已上架的原件）
     * @param unknown  名字不像日期的目录名收集到这里（不删，由调用方记 WARN）
     */
    public List<Purged> purge(String area, LocalDate before, Set<String> skipDays, List<String> unknown) {
        Path dir = root.resolve(area);
        List<Purged> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> days = Files.newDirectoryStream(dir)) {
            for (Path d : days) {
                String name = d.getFileName().toString();
                LocalDate day;
                try {
                    day = LocalDate.parse(name);
                } catch (DateTimeParseException e) {
                    unknown.add(area + "/" + name);
                    continue;
                }
                if (!Files.isDirectory(d) || !day.isBefore(before) || skipDays.contains(name)) {
                    continue;
                }
                long bytes = sizeOf(d);
                deleteTree(d);
                out.add(new Purged(area, name, bytes));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 某区占用的字节数（清理日志里报） */
    public long usage(String area) {
        Path dir = root.resolve(area);
        return Files.isDirectory(dir) ? sizeOf(dir) : 0L;
    }

    // ── 文件名 ──────────────────────────────────────────────────────────────

    /**
     * 原名 → 能放心落盘的文件名主干（不含扩展名）。
     *
     * <ul>
     *   <li>去路径：只取最后一个 {@code /} 或 {@code \} 之后 —— 路径穿越</li>
     *   <li>去扩展名：扩展名按魔数定，原名的不可信</li>
     *   <li>控制字符丢掉；Windows 不认的 {@code <>:"|?*} 换成 {@code _}；{@code ..} 换成 {@code _}</li>
     *   <li>首尾的空白与点去掉（{@code .} 开头会变成隐藏文件）</li>
     *   <li>按 UTF-8 截到 {@value #NAME_MAX_BYTES} 字节，不切断一个字</li>
     *   <li>什么都不剩 → {@code upload}</li>
     * </ul>
     */
    public static String safeBase(String original) {
        if (original == null) {
            return "upload";
        }
        String s = original;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0 && s.length() - dot <= 6 && s.substring(dot + 1).chars().allMatch(Character::isLetterOrDigit)) {
            s = s.substring(0, dot);
        }
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (cp < 0x20 || cp == 0x7F) {
                return;
            }
            sb.appendCodePoint("<>:\"|?*".indexOf(cp) >= 0 ? '_' : cp);
        });
        s = sb.toString().replace("..", "_").strip();
        while (s.startsWith(".")) {
            s = s.substring(1).strip();
        }
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        s = truncateUtf8(s, NAME_MAX_BYTES);
        return s.isEmpty() ? "upload" : s;
    }

    /** 供应商号、批次号本来就是系统生成的，这里只是再保一道：只留字母数字与横杠下划线 */
    static String safeSegment(String s) {
        String t = s == null ? "" : s.replaceAll("[^A-Za-z0-9_-]", "");
        return t.isEmpty() ? "_" : t;
    }

    /** 按魔数定扩展名。{@code .xls} 也照样落盘 —— 解析失败的原件恰恰常是它 */
    public static String ext(byte[] b) {
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            return "xlsx";
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11
                && (b[3] & 0xFF) == 0xE0) {
            return "xls";
        }
        return "csv";
    }

    static String truncateUtf8(String s, int maxBytes) {
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int cp : s.codePoints().toArray()) {
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + n > maxBytes) {
                break;
            }
            sb.appendCodePoint(cp);
            used += n;
        }
        return sb.toString().strip();
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 解析相对路径并确认它还在这个区里面（库里的路径被人改成 ../ 也出不去） */
    private Path inside(String area, String relPath) {
        Path base = root.resolve(area);
        Path p = base.resolve(relPath).normalize();
        return p.startsWith(base) ? p : null;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long sizeOf(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
