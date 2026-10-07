package ai.neargo.shop.channel.media.api;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.media.ImageProbe;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.MediaUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 压缩包导入：**服务端解压**（小程序端用）。
 *
 * <p>App 端解压靠 {@code plus.zip} 这个原生能力，小程序没有。那一端改成
 * 从微信对话选 zip（{@code uni.chooseMessageFile}）整包传上来，由这里拆开，
 * 顺手把每张图过完校验落进媒体库，直接回带 URL 的清单 —— 端上不再逐张上传。
 *
 * <p>住在 shop-channel 而不是商品域：理由同 {@link BizUploadController} ——
 * 它做的仍然只是「把字节存到某处并给回 URL」，只不过字节是从 zip 里来的。
 * 分类/排序（哪些是主图、哪些是详情）是纯逻辑，留在端上（`classifyZipTree`，两端同一套、已单测）。
 *
 * <h2>这个端点收的是用户上传的压缩包，所以下面几条是必须的</h2>
 *
 * <ul>
 *   <li><b>Zip Slip</b>：条目名带 {@code ..}、以 {@code /} 开头、或含反斜杠的，整条跳过。
 *       更根本的是**这里不往磁盘解压** —— 每个 entry 读进内存就直接交给
 *       {@link MediaUploadService}，落点由我们自己拼的 key 决定，
 *       压缩包里的路径从头到尾只被当作「分类用的字符串」，没有任何落点可穿越。</li>
 *   <li><b>解压炸弹</b>：三道闸 —— 单条目、总解压量、条目数。
 *       只看压缩包本身的大小是不够的：几百 KB 能解出几个 G。</li>
 *   <li><b>伪装成图片的字节</b>：每个图片条目都过 {@link ImageProbe#looksLikeImage}
 *       （看头几个字节），与 {@code /biz/upload/image} 同一道闸。
 *       只认后缀的话，一段纯文本改名 {@code x.png} 就能以 {@code image/png}
 *       落进**公开桶**并被公开取回。</li>
 * </ul>
 */
@Slf4j
@Profile({"api", "ops"})
@RestController
public class BizZipImportController {

    /** 单个条目解压后的上限。与 /biz/upload/image 的单图上限对齐 */
    private static final long MAX_ENTRY_BYTES = 5L * 1024 * 1024;

    /**
     * 整包解压后的总量上限。
     *
     * <p>**只限压缩包本身的大小挡不住炸弹** —— 高压缩比的构造几百 KB 就能解出几个 G。
     * 所以这里数的是「已经解出来多少」，边读边累加，超了立刻中止。
     */
    private static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;

    /** 条目数上限。真实商品包几十张顶天了；成千上万条的多半不是来建品的 */
    private static final int MAX_ENTRIES = 200;

    /** 单个 txt 只取前 64KB —— 它只是拿去喂模型的商品文案，不是文件存储 */
    private static final int MAX_TEXT_BYTES = 64 * 1024;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final MediaStore mediaStore;
    private final MediaUploadService mediaUpload;

    public BizZipImportController(MediaStore mediaStore, MediaUploadService mediaUpload) {
        this.mediaStore = mediaStore;
        this.mediaUpload = mediaUpload;
    }

    /** 清单里的一条图片。{@code path} 是压缩包里的相对路径，端上据它分类/排序 */
    public record ZipImportedFile(String path, Integer width, Integer height, String url) {
    }

    /** @param texts 压缩包里的 txt：相对路径 → 内容。端上没有本地文件可读，只能随清单带回去 */
    public record ZipImportResult(List<ZipImportedFile> files, Map<String, String> texts) {
    }

    /*
     * 权限与 zip-plan 同档（BizPerms.GOODS）：它比 zip-plan 更重 —— **真的往媒体库写**。
     * 表里登记了还不够，这行注解才是执行的那一半（BizEndpointPermTest 两条分别钉这两件事）。
     */
    @org.springframework.security.access.prepost.PreAuthorize(
            "@perm.canBiz('" + ai.neargo.shop.auth.BizPerms.GOODS + "')")
    @PostMapping("/biz/goods/zip-import")
    public ZipImportResult importZip(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String entityNo = BizContext.requireMerchantNo();
        String storeNo = BizContext.requireStoreNo();
        String uploadedBy = BizContext.current().merchantNo();

        List<ZipImportedFile> files = new ArrayList<>();
        Map<String, String> texts = new LinkedHashMap<>();
        long total = 0;
        int count = 0;

        try (ZipInputStream zis = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw BizException.of(ErrorCode.ZIP_TOO_LARGE);
                }
                String path = entry.getName();
                if (unsafe(path)) {
                    log.warn("[zip-import] 跳过不安全的条目名：{}", path);
                    continue;
                }
                String ext = ImageProbe.extensionOf(path);
                boolean isImage = ImageProbe.ALLOWED_EXT.contains(ext);
                boolean isText = "txt".equals(ext);
                if (!isImage && !isText) {
                    // 商家打包时常夹带 .DS_Store、缩略图之类 —— 忽略，不报错
                    continue;
                }
                byte[] bytes = readEntry(zis, total);
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw BizException.of(ErrorCode.ZIP_TOO_LARGE);
                }
                if (isText) {
                    int n = Math.min(bytes.length, MAX_TEXT_BYTES);
                    texts.put(path, new String(bytes, 0, n, StandardCharsets.UTF_8));
                    continue;
                }
                /*
                 * 真正的类型判定在这里：看头几个字节。只认后缀的话，
                 * 一段纯文本改名 x.png 就能以 image/png 落进公开桶（BizUploadController 实测过）。
                 * 不过就跳过，而不是整包失败 —— 一张坏图不该让商家重打一次包。
                 */
                if (!ImageProbe.looksLikeImage(() -> new ByteArrayInputStream(bytes), ext)) {
                    log.warn("[zip-import] 跳过不是图片的条目：{}", path);
                    continue;
                }
                files.add(storeImage(path, bytes, ext, entityNo, storeNo, uploadedBy));
            }
        }
        return new ZipImportResult(files, texts);
    }

    /**
     * 条目名安全性。压缩包里的路径**只被当作分类用的字符串**，但它还是会回到端上、
     * 并被当成 key 用，所以这几种一律不收。
     */
    private static boolean unsafe(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String p = path.replace('\\', '/');
        if (p.startsWith("/") || p.contains("../") || p.equals("..") || p.endsWith("/..")) {
            return true;
        }
        // Windows 盘符（C:\...）在某些解压实现里同样是绝对路径
        return p.length() > 1 && p.charAt(1) == ':';
    }

    /**
     * 读一个条目。**边读边数**：{@code entry.getSize()} 来自压缩包自己的头，
     * 是攻击者说了算的，不能拿它预分配、也不能拿它当判据。
     */
    private static byte[] readEntry(ZipInputStream zis, long already) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long size = 0;
        while ((n = zis.read(buf)) > 0) {
            size += n;
            if (size > MAX_ENTRY_BYTES || already + size > MAX_TOTAL_BYTES) {
                throw BizException.of(ErrorCode.ZIP_TOO_LARGE);
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 落进媒体库。key 的四层结构与 /biz/upload/image 逐字一致 —— 切 COS 时不需要任何映射 */
    private ZipImportedFile storeImage(String path, byte[] bytes, String ext,
                                       String entityNo, String storeNo, String uploadedBy) throws IOException {
        String type = SysMediaAsset.GOODS;
        String key = String.join("/",
                entityNo,
                storeNo,
                type.toLowerCase(Locale.ROOT),
                LocalDateTime.now().format(MONTH),
                java.util.UUID.randomUUID().toString().replace("-", "") + "." + ext);

        ImageProbe.InputStreamSource src = () -> new ByteArrayInputStream(bytes);
        mediaUpload.store(key, src, bytes.length, "image/" + ext, type, entityNo, storeNo, uploadedBy);

        int[] wh = ImageProbe.dimensionsOf(src);
        Integer w = wh != null && wh.length == 2 && wh[0] > 0 ? wh[0] : null;
        Integer h = wh != null && wh.length == 2 && wh[1] > 0 ? wh[1] : null;
        return new ZipImportedFile(path, w, h, mediaStore.publicUrl(key));
    }
}
