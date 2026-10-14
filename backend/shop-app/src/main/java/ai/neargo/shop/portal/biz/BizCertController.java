package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.CertVisionPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 证照识别：传一张营业执照或身份证，读出结构化字段**回给他确认**。
 *
 * <h2>这个接口不落任何东西</h2>
 *
 * <p>不写库、不进公开桶、不留文件。图片只在这一次请求里存在，
 * 内联发给模型，返回字段，然后就没了。理由见 {@link CertVisionPort}：
 * 身份证进公开桶等于把姓名住址身份证号交出去，而 URL 会被日志与转发带到各处。
 *
 * <p><b>识别结果也不自动落库</b>。端上拿去预填表单，商家看过、改过、点了保存，
 * 才走既有的 {@code /biz/qualifications/save}。模型今天准不代表每张都准，
 * 而信用代码错一位会一路错到进件与开票，中间没有任何一步会报错。
 *
 * <h2>为什么单独一个控制器</h2>
 *
 * <p>没并进 {@code BizMerchantController}：那个已经十七个依赖，
 * 而这一个的性质完全不同 —— 它是唯一一个**接收敏感图像且刻意不持久化**的端点，
 * 混进去之后「哪些端点会存图」这个问题就没人答得清了。
 */
@Profile("api")
@RestController
public class BizCertController {

    /**
     * 送给模型之前压到的长边像素。
     *
     * <p>实拍执照常见 5712×4284 / 7.3MB，base64 之后接近 10MB —— 多数网关直接拒收，
     * 而那个失败在端上看起来像「模型不可用」。1600 是实测过的：
     * 信用代码、成立日期、住所逐字对过，压到这个尺寸识别结果不变。
     */
    private static final int MAX_EDGE = 1600;

    /** 上传上限。比 /biz/upload/image 的 5MB 松一档 —— 证照本来就常是高清原图 */
    private static final long MAX_BYTES = 12L * 1024 * 1024;

    private final CertVisionPort certVision;

    public BizCertController(CertVisionPort certVision) {
        this.certVision = certVision;
    }

    /**
     * 认一张证照。
     *
     * <p>权限用 {@code biz:store} —— 与「传资质证件」同一档：能传证的人才能让系统替他读证。
     *
     * @return 认出来的字段；模型没开或没认出来时 {@code recognized=false}，
     *         端上据此让他手填，<b>而不是弹一个错误</b> —— 识别是锦上添花，
     *         没识别出来只是少省一次手打
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/qualifications/recognize")
    public CertVO recognize(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * ★ **先校验入参，再看模型开没开。**
         *
         * 反过来写的话（我第一版就是），模型没开时任何字节都会拿到一个
         * 「没认出来」的成功响应 —— 包括一段纯文本改名成 .jpg。
         * 于是端上分不清「这张证认不出」与「你传的根本不是图」，
         * 而后者他改多少次证件都没用。用例 rejectsNonImageBytes 逮到的就是这个。
         *
         * 代价是模型没开时也解一次图。一张 12MB 以内的图，值。
         */
        byte[] shrunk = downscale(file);
        if (shrunk == null) {
            // 解不出来的多半根本不是图（后缀是客户端说了算的）
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!certVision.isEnabled()) {
            // 没配模型：如实说「这次没认」，不要编一个空结果让端上以为认过了
            return CertVO.none();
        }
        var cert = certVision.recognize(shrunk, "image/jpeg");
        return cert == null ? CertVO.none() : CertVO.of(cert);
    }

    /**
     * 压到长边 {@link #MAX_EDGE}，统一转成 jpeg。
     *
     * <p>小于阈值的也重编码：一是统一 mime（模型对 webp/gif 的支持参差），
     * 二是**顺手扔掉 EXIF** —— 手机拍的证照常带 GPS，那是没必要跟着走的信息。
     *
     * @return null 表示这根本解不成图
     */
    private static byte[] downscale(MultipartFile file) throws IOException {
        BufferedImage src;
        try (InputStream in = file.getInputStream()) {
            src = ImageIO.read(in);
        }
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));

        // TYPE_INT_RGB 而不是 ARGB：目标是 jpeg，带 alpha 会编出一张偏色的图
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        var g = dst.createGraphics();
        try {
            g.drawImage(src.getScaledInstance(nw, nh, Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            g.dispose();
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(dst, "jpeg", out);
        return out.toByteArray();
    }

    /**
     * 回给端上的结果。
     *
     * @param recognized false = 模型没开或没认出来。端上据此让他手填，不要弹错误
     * @param code       信用代码 / 身份证号。<b>原样回给上传者本人</b> ——
     *                   他正要核对这一栏；但这个值不进日志、不回给第三方
     */
    public record CertVO(boolean recognized, String docType, String side, String name, String code,
                         String legalForm, String person, String address, String issuedAt,
                         String validTo, double confidence) {

        static CertVO none() {
            return new CertVO(false, null, null, null, null, null, null, null, null, null, 0);
        }

        static CertVO of(CertVisionPort.Cert c) {
            return new CertVO(true, c.docType(), c.side(), c.name(), c.code(), c.legalForm(),
                    c.person(), c.address(), c.issuedAt(), c.validTo(), c.confidence());
        }
    }
}
