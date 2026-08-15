package ai.neargo.shop.channel.media.api;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.SysMediaAssetMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图片上传（B-11.3 商品图 / B-11.6 售后凭证 / 进件资质件）。
 *
 * <p><b>一期落本地磁盘，生产必须换对象存储。</b> 决定性的理由是带宽：云主机按固定带宽计费，
 * 十来个人同时刷首页就打满了（见 <i>资源需求评估-JDK21与native</i> §L3-8）。
 * 之所以现在这样做，是因为接 COS 需要一套凭据与回源域名，
 * 而这条链路在没有它们之前完全跑不通 —— 空着的话 B 端连一张商品图都上传不了。
 *
 * <p><b>换 COS 时这个类几乎不用动</b>：它只跟 {@link MediaStore} 打交道，
 * 而返回给端上的仍然只是一个相对路径。
 *
 * <p>住在 shop-channel 而不是某个业务域（S7）：它不属于商品也不属于售后，
 * 它只是「把字节存到某处并给回一个 URL」——和支付通道一样是外部适配。
 */
@Slf4j
@Profile({"api", "ops"})
@RestController
public class BizUploadController {

    /**
     * 只认这几种。<b>白名单而不是黑名单</b> —— 黑名单要穷举所有危险后缀，
     * 而漏一个就是往可访问目录里放了一个可执行文件。
     */
    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /** 用途白名单。同样不用黑名单 —— 它决定文件落进公开目录还是私有目录。 */
    private static final Set<String> BIZ_TYPES =
            Set.of(SysMediaAsset.GOODS, SysMediaAsset.QUAL, SysMediaAsset.AFTERSALE);

    /** 5MB。手机直出照片常有 3–4MB，再大多半是没压缩，不该由服务端替他存。 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    /** 目录按月分片：ext4 单目录几万文件之后 readdir 明显变慢。 */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final MediaStore mediaStore;
    private final SysMediaAssetMapper assetMapper;

    public BizUploadController(MediaStore mediaStore, SysMediaAssetMapper assetMapper) {
        this.mediaStore = mediaStore;
        this.assetMapper = assetMapper;
    }

    /**
     * <b>注意这个方法没有 {@code @Transactional}，是故意的。</b>
     *
     * <p>三步的顺序是「写 PENDING 行 → 落盘 → 改 ACTIVE」，而这三步<b>不能在同一个事务里</b>：
     * 包在一个事务里的话，落盘成功而事务回滚就留下「磁盘有文件、库里没有」的孤儿 ——
     * 而孤儿是查不出来的，统计永远少算，回收清单里永远不出现，只能靠人去 du 才发现。
     *
     * <p>不用事务则两种崩法都只留下可对账的 PENDING 行：
     * 有行无文件就删行，有行有文件就补成 ACTIVE，都由对账任务处理。
     */
    @PostMapping("/biz/upload/image")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "bizType", required = false) String bizType)
            throws IOException {

        String type = (bizType == null || bizType.isBlank())
                ? SysMediaAsset.GOODS : bizType.toUpperCase(Locale.ROOT);
        if (!BIZ_TYPES.contains(type)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED.contains(ext)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        String entityNo = BizContext.requireMerchantNo();
        /*
         * 证件是**主体级**的：营业执照属于经营主体，不属于「文三路店」。
         * 所以它不取当前门店，而落在 _ENTITY 这一档。
         *
         * 这同时避开一个真问题：进件阶段商家还没建店，
         * 照搬 requireStoreNo() 会直接 403 —— 传不了证件也就进不了件。
         */
        String storeNo = SysMediaAsset.QUAL.equals(type)
                ? SysMediaAsset.ENTITY_SCOPE : BizContext.requireStoreNo();

        /*
         * 四层 key：主体 / 门店 / 用途 / 年月 / 随机名。
         * 每一层都在为一个具体动作服务（TDD §L3-2），而这串字
         * **逐字就是将来的 COS object key**，切对象存储时不需要任何映射。
         *
         * 文件名用随机串而不是原名 —— 原名可能是中文、可能带路径分隔符，
         * 也可能两个人同时传 "IMG_0001.jpg" 互相覆盖。
         */
        String key = String.join("/",
                entityNo,
                storeNo,
                type.toLowerCase(Locale.ROOT),
                LocalDateTime.now().format(MONTH),
                java.util.UUID.randomUUID().toString().replace("-", "") + "." + ext);

        // ① 先记账。拿不到 id 就不落盘 —— 顺序反了会产生查不出来的孤儿
        SysMediaAsset asset = new SysMediaAsset();
        asset.setAssetKey(key);
        asset.setEntityNo(entityNo);
        asset.setStoreNo(storeNo);
        asset.setBizType(type);
        asset.setBytes(file.getSize());
        asset.setContentType(file.getContentType());
        asset.setStatus(SysMediaAsset.PENDING);
        asset.setUploadedBy(BizContext.current().merchantNo());
        int[] wh = dimensionsOf(file);
        asset.setWidth(wh[0] > 0 ? wh[0] : null);
        asset.setHeight(wh[1] > 0 ? wh[1] : null);
        LocalDateTime now = LocalDateTime.now();
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetMapper.insert(asset);

        // ② 落字节
        try (InputStream in = file.getInputStream()) {
            mediaStore.put(key, in, file.getSize(), file.getContentType());
        }

        // ③ 这一刻起才算进门店空间
        SysMediaAsset done = new SysMediaAsset();
        done.setId(asset.getId());
        done.setStatus(SysMediaAsset.ACTIVE);
        done.setUpdatedAt(LocalDateTime.now());
        assetMapper.updateById(done);

        /*
         * 返回**稳定的相对路径**，不是签名 URL。
         * 签名带有效期，存进 mch_qualification.image_url 那种字段就是一颗定时炸弹：
         * 存的时候能打开，几分钟后同一行数据变成死链，而且不报错。
         * 签名是渲染那一刻的事，见 MediaStore#signedUrl。
         */
        String url = SysMediaAsset.GOODS.equals(type)
                ? mediaStore.publicUrl(key) : mediaStore.privatePath(key);
        return Map.of("url", url);
    }

    /**
     * 读宽高。<b>只读文件头，不解码像素</b> ——
     * {@code ImageIO.read} 会把整张图解成 BufferedImage，
     * 一个 5MB 的 JPEG 可能是 5000×5000，解出来上百 MB，几个人同时传就能把堆打满。
     *
     * <p>webp 没有内置 reader，取不到就返回 0 —— 记账表那两列本来就允许为空，
     * 运营端少显示一个尺寸，不值得为它引一个解码库。
     */
    private static int[] dimensionsOf(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                return new int[]{0, 0};
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return new int[]{0, 0};
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            // 读不出尺寸不该让上传失败：它只是运营端的一列展示
            log.debug("读取图片尺寸失败，跳过", e);
            return new int[]{0, 0};
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
