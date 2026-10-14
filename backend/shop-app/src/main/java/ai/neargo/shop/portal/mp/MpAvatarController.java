package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.media.ImageProbe;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.MediaUploadService;
import ai.neargo.shop.user.dto.UserVO;
import ai.neargo.shop.user.service.UserService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * C 端头像上传（C-AC-08）。
 *
 * <p><b>为什么住在 app 层而不是 {@code shop-core} 的 user 域里：</b>
 * 它要同时碰 {@link MediaStore}（在 {@code shop-store-mybatis}）和
 * {@link UserService}（在 {@code shop-core}）。让 user 域去依赖存储模块就是跨域依赖，
 * {@code ArchitectureTest} 会拦。跨域的组合住 app 层，与 {@code reportbridge} 同一口径。
 *
 * <p><b>为什么不复用 {@code /biz/upload/image}：</b> ADR-007 的前缀纪律之外，
 * 那条端点第一句就是 {@code BizContext.requireMerchantNo()} —— 买家没有商家号，
 * 他一打就是 403。两端的鉴权链本来就不是一条。
 *
 * <p>字节怎么存、怎么对账，与 B 端那条完全一致（先记账 → 落盘 → 改 ACTIVE，
 * <b>刻意不加 {@code @Transactional}</b>，理由见 {@code BizUploadController} 的说明：
 * 包进事务会留下「磁盘有文件、库里没有」的孤儿，而孤儿是查不出来的）。
 */
@Profile("api")
@RestController
public class MpAvatarController {

    /**
     * 2MB。<b>比商品图的 5MB 紧一档</b> —— 头像显示出来只有几十个 px 见方，
     * 一张 4MB 的直出照片存下来，99.9% 的字节从来没有被任何一个像素用到过。
     *
     * <p>不做成配置项：没有调它的场景，而配置项多一个就多一处没人读的开关。
     */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 目录按月分片：ext4 单目录几万文件之后 readdir 明显变慢。 */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final MediaStore mediaStore;
    private final MediaUploadService mediaUpload;
    private final UserService userService;

    public MpAvatarController(MediaStore mediaStore, MediaUploadService mediaUpload,
                              UserService userService) {
        this.mediaStore = mediaStore;
        this.mediaUpload = mediaUpload;
        this.userService = userService;
    }

    /**
     * 传一张头像并<b>当场落到账号上</b>。
     *
     * <p>返回整个 {@link UserVO} 而不是 {@code {url}}：端上拿到 url 还要再打一次
     * {@code POST /mp/user/profile} 才算改完，而中间那一步失败的话，
     * 字节已经存了、账号上还是旧头像 —— 用户看到「上传成功」但头像没变。
     * 一个端点做完整件事，就没有这个中间态。
     */
    @PostMapping("/mp/user/avatar")
    public UserVO uploadAvatar(
            @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        /*
         * `file` 刻意是**非必填**，缺了由下面那行判。
         *
         * 必填的话 Spring 在进方法体之前就拒掉，匿名请求回的是「参数有误」而不是 401 ——
         * 于是鉴权守卫的实弹探测永远打不到这条端点的身份判定。
         * 先取当前用户，就把「谁在调」排在「调得对不对」前面。
         */
        String userNo = SecurityUtils.currentUserNo();

        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String ext = ImageProbe.extensionOf(file.getOriginalFilename());
        if (!ImageProbe.ALLOWED_EXT.contains(ext)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 后缀白名单只认文件名，而文件名是客户端说了算的 ——
         * 真正的类型判定看头几个字节。少了这一道，任意字节都能以 image/png
         * 落进**公开**目录并公开取回（见 ImageProbe#looksLikeImage）。
         */
        if (!ImageProbe.looksLikeImage(file::getInputStream, ext)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * 归属两列都用 _USER 哨兵，具体是谁记在 uploaded_by ——
         * 理由见 SysMediaAsset.USER_SCOPE：把 userNo 塞进 entity_no/store_no
         * 会让运营端存储页把一个买家显示成一家门店。
         *
         * key 里仍然带 userNo：它是「这个人的头像」在磁盘上的唯一线索，
         * 而且这串字逐字就是将来的 COS object key。
         */
        String key = String.join("/",
                SysMediaAsset.USER_SCOPE,
                userNo,
                SysMediaAsset.AVATAR.toLowerCase(Locale.ROOT),
                LocalDateTime.now().format(MONTH),
                java.util.UUID.randomUUID().toString().replace("-", "") + "." + ext);

        mediaUpload.store(key, file::getInputStream, file.getSize(), file.getContentType(),
                SysMediaAsset.AVATAR, SysMediaAsset.USER_SCOPE, SysMediaAsset.USER_SCOPE, userNo);

        /*
         * 公开路径，不是签名 URL。签名带有效期，存进 usr_account.avatar
         * 那种字段就是一颗定时炸弹：存的时候能打开，几分钟后同一行数据变成死链，
         * 而且不报错。头像本来就要给别人看（参团邻居墙、评价、B 端订单里的买家）。
         */
        return userService.updateProfile(null, mediaStore.publicUrl(key));
    }
}
