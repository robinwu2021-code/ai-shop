package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.spi.product.CertVisionPort;
import ai.neargo.shop.portal.common.CertDocumentReader;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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
        var document = CertDocumentReader.read(file);
        if (!certVision.isEnabled() || ("application/pdf".equals(document.contentType()) && !certVision.supportsDocuments())) {
            // 没配模型：如实说「这次没认」，不要编一个空结果让端上以为认过了
            return CertVO.none();
        }
        var cert = certVision.recognize(document.bytes(), document.contentType());
        return cert == null ? CertVO.none() : CertVO.of(cert);
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
