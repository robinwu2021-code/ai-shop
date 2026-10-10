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

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
     * 只认这几种 —— 判据与 C 端头像上传<b>共用同一份</b>（{@link ImageProbe#ALLOWED_EXT}）。
     * 各自一份常量表会漂：一端补了格式另一端没补，症状是「换个入口就说格式不对」。
     */
    private static final Set<String> ALLOWED = ImageProbe.ALLOWED_EXT;

    /** 用途白名单。同样不用黑名单 —— 它决定文件落进公开目录还是私有目录。 */
    private static final Set<String> BIZ_TYPES =
            Set.of(SysMediaAsset.GOODS, SysMediaAsset.QUAL, SysMediaAsset.AFTERSALE);

    /** 5MB。手机直出照片常有 3–4MB，再大多半是没压缩，不该由服务端替他存。 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    /** 目录按月分片：ext4 单目录几万文件之后 readdir 明显变慢。 */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final MediaStore mediaStore;
    private final MediaUploadService mediaUpload;

    public BizUploadController(MediaStore mediaStore, MediaUploadService mediaUpload) {
        this.mediaStore = mediaStore;
        this.mediaUpload = mediaUpload;
    }

    /**
     * 记账与落盘的三步（以及「刻意不用事务」的理由）都在
     * {@link MediaUploadService#store}。<b>这里不要再抄一份</b> ——
     * 抄的时候最容易丢的就是那个「不用事务」，而丢了它的症状是查不出来的孤儿文件。
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
        String ext = ImageProbe.extensionOf(file.getOriginalFilename());
        if (!ALLOWED.contains(ext)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **后缀白名单只认文件名，而文件名是客户端说了算的。**
         * 实测：一段纯文本改名 `x.png` 传进来，白名单放行、`dimensionsOf()` 读不出尺寸
         * 也不拦（它的注释明说「读不出尺寸不该让上传失败」—— 对尺寸而言没错），
         * 于是任意字节以 `Content-Type: image/png` 落进**公开桶**并可公开取回。
         * 记账表里 width/height 是 NULL，但没有任何人会去看那两列。
         *
         * 所以真正的类型判定放在这里：看头几个字节。它与 `dimensionsOf` 是两件事 ——
         * 那个答的是「多大」，这个答的是「是不是图」，后者不该因为前者失败而放行。
         */
        if (!ImageProbe.looksLikeImage(file::getInputStream, ext)) {
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

        mediaUpload.store(key, file::getInputStream, file.getSize(), file.getContentType(),
                type, entityNo, storeNo, BizContext.current().merchantNo());

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

}
