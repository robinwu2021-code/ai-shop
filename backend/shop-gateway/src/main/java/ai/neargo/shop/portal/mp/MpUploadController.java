package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.media.ImageProbe;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.MediaUploadService;
import ai.neargo.shop.media.SysMediaAsset;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * C 端凭证图上传：售后问题照、评价晒单。
 *
 * <p><b>为什么新开一条，而不是复用头像 {@link MpAvatarController}：</b>
 * 头像传完要<b>当场落到账号上</b>，返回整个 UserVO；凭证图只是换一个公开 URL 交回给调用方，
 * 由它一张一张塞进售后单 / 评价里，所以这里只返回 {@code {url}}。字节怎么存、怎么校验、
 * 怎么对账，与头像那条完全一致（先记账 → 落盘 → 改 ACTIVE，刻意不加 {@code @Transactional}，
 * 理由见 {@code BizUploadController}）。
 *
 * <p><b>为什么现在才有这条端点：</b> C 端此前除头像外没有任何上传端点。售后页 / 评价页
 * 选图只拿到 {@code wxfile://} 本地临时路径（{@code chooseImages} 的注释写明"上传前用于预览"），
 * 直接当图片 URL 提交。后端不校验、照存，提交回 200 —— 但那路径换页 / 重开小程序就失效，
 * 图全裂。店主看到的"上传图片错误、提交无数据"就是它（2026-10-10；查生产日志提交其实都成功了）。
 *
 * <p><b>为什么不复用 {@code /biz/upload/image}：</b> 那条第一句就是
 * {@code BizContext.requireMerchantNo()} —— 买家没有商家号，一打就 403。
 * 两端的鉴权链不是一条（与头像端点同一个理由）。
 */
@Profile("api")
@RestController
public class MpUploadController {

    /** 凭证图 5MB，与商品图同档（头像那条紧到 2MB 是因为显示只有几十 px，凭证要看清细节）。 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    /** 目录按月分片：ext4 单目录几万文件之后 readdir 明显变慢（与头像同口径）。 */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 允许的用途。<b>白名单之外一律拒</b> —— {@code scene} 原样进 key 的目录段，
     * 放开任意字符串既是目录注入，也让运营端存储页多出一堆没人认得的分类。
     * 两个用途都落 {@link SysMediaAsset#EVIDENCE}（公开读），靠目录段区分售后 / 评价。
     */
    private static final Set<String> SCENES = Set.of("aftersale", "review");

    private final MediaStore mediaStore;
    private final MediaUploadService mediaUpload;

    public MpUploadController(MediaStore mediaStore, MediaUploadService mediaUpload) {
        this.mediaStore = mediaStore;
        this.mediaUpload = mediaUpload;
    }

    /**
     * 传一张凭证图，返回它的公开 URL。调用方拿到后塞进 {@code images} 再提交售后 / 评价。
     *
     * @param file  图片字节（非必填，缺了由下面判 —— 让鉴权判定排在参数判定前，见下）
     * @param scene {@code aftersale} | {@code review}
     * @return {@code {"url": "<公开可取的图片地址>"}}
     */
    @PostMapping("/mp/upload/image")
    public Map<String, String> uploadImage(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "scene", required = false) String scene) throws IOException {
        /*
         * 先取当前用户：把"谁在调"排在"调得对不对"前面（与头像端点同一条）。
         * file / scene 都做成非必填，缺了由方法体判 —— 必填的话 Spring 在进方法前就拒，
         * 匿名请求回"参数有误"而不是 401，鉴权守卫的实弹探测就打不到这条的身份判定。
         */
        String userNo = SecurityUtils.currentUserNo();

        if (scene == null || !SCENES.contains(scene)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String ext = ImageProbe.extensionOf(file.getOriginalFilename());
        if (!ImageProbe.ALLOWED_EXT.contains(ext)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 后缀只认文件名，而文件名是客户端说了算的 —— 真正的类型看头几个字节。
         * 少这一道，任意字节都能以 image/png 落进<b>公开</b>目录并被公开取回。
         */
        if (!ImageProbe.looksLikeImage(file::getInputStream, ext)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * 归属两列都用 _USER 哨兵，具体是谁记在 uploaded_by —— 把 userNo 塞进
         * entity_no/store_no 会让运营端存储页把一个买家显示成一家门店（见 SysMediaAsset.USER_SCOPE）。
         * key 里带 userNo 与 scene：前者是磁盘上的唯一线索、将来的 COS object key；
         * 后者把售后图与评价图分到两个目录，运营端一眼分得清。
         */
        String key = String.join("/",
                SysMediaAsset.USER_SCOPE,
                userNo,
                SysMediaAsset.EVIDENCE.toLowerCase(Locale.ROOT),
                scene,
                LocalDateTime.now().format(MONTH),
                java.util.UUID.randomUUID().toString().replace("-", "") + "." + ext);

        mediaUpload.store(key, file::getInputStream, file.getSize(), file.getContentType(),
                SysMediaAsset.EVIDENCE, SysMediaAsset.USER_SCOPE, SysMediaAsset.USER_SCOPE, userNo);

        /*
         * 公开路径，不是签名 URL。它要存进 ord_after_sale.images / rvw_review.images 并长期直接渲染，
         * 签名 URL 带有效期，存下去就是定时炸弹：存的时候能开，几分钟后同一行变死链且不报错
         * （与头像存 publicUrl 同一个理由）。凭证选公开读正是为了这一步，见 SysMediaAsset.EVIDENCE。
         */
        return Map.of("url", mediaStore.publicUrl(key));
    }
}
