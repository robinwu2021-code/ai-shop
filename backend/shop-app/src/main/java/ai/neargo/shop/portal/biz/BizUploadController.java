package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图片上传（B-11.3 商品图 / B-11.6 售后凭证）。
 *
 * <p><b>一期落本地磁盘，生产必须换对象存储。</b> 这不是"以后优化"级别的待办：
 * 本地磁盘意味着多实例部署时 A 机上传的图 B 机读不到，且机器一换图全没了。
 * 之所以现在这样做，是因为接 OSS 需要一套凭据与回源域名，
 * 而这条链路在没有它们之前完全跑不通 —— 空着的话 B 端连一张商品图都上传不了。
 *
 * <p>换 OSS 时改的是这一个类，端上拿到的仍然只是一个 URL。
 */
@RestController
public class BizUploadController {

    /**
     * 只认这几种。<b>白名单而不是黑名单</b> —— 黑名单要穷举所有危险后缀，
     * 而漏一个就是往可访问目录里放了一个可执行文件。
     */
    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /** 5MB。手机直出照片常有 3–4MB，再大多半是没压缩，不该由服务端替他存。 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final Path root;
    private final String publicPrefix;

    public BizUploadController(@Value("${shop.upload.dir:./data/uploads}") String dir,
                               @Value("${shop.upload.public-prefix:/uploads}") String publicPrefix) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.publicPrefix = publicPrefix;
    }

    @PostMapping("/biz/upload/image")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String merchantNo = BizContext.requireMerchantNo();
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (file.getSize() > MAX_BYTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED.contains(ext)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * 按商家分目录：出问题时能一眼看出是谁传的，清理某家店的数据也不用全盘扫。
         * 文件名用随机串而不是原名 —— 原名可能是中文、可能带路径分隔符，
         * 也可能两个人同时传 "IMG_0001.jpg" 互相覆盖。
         */
        Path dir = root.resolve(merchantNo);
        Files.createDirectories(dir);
        String name = java.util.UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(name);
        // 再确认一次落点在根目录内：merchantNo 来自登录态，不该含 ..，但这行的成本是零
        if (!target.normalize().startsWith(root)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        file.transferTo(target);

        return Map.of("url", publicPrefix + "/" + merchantNo + "/" + name);
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
