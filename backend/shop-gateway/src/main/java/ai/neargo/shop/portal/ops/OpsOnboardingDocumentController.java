package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.MarketPhonePolicyService;
import ai.neargo.shop.portal.common.CertDocumentReader;
import ai.neargo.shop.spi.product.CertVisionPort;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/** 临时识别建议；不修改主体、进件、附件、审核或渠道状态。 */
@Profile("ops")
@RestController
public class OpsOnboardingDocumentController {
    private final CertVisionPort vision;
    private final MarketPhonePolicyService markets;

    public OpsOnboardingDocumentController(CertVisionPort vision, MarketPhonePolicyService markets) {
        this.vision = vision;
        this.markets = markets;
    }

    @PreAuthorize("@perm.can('" + Perms.MERCHANT_ADMISSION_UPDATE + "') or @perm.can('" + Perms.MERCHANT_APPLY_ONBEHALF + "')")
    @PostMapping("/ops/onboarding-documents/recognize")
    public Result recognize(@RequestParam("file") MultipartFile file,
                            @RequestParam("documentType") CertVisionPort.DocumentType type,
                            HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!"TW".equals(markets.instancePolicy().market())) throw BizException.of(ErrorCode.BAD_REQUEST);
        var input = CertDocumentReader.read(file);
        if (!vision.isEnabled() || !vision.supportsDocuments()) return Result.none(type);
        var document = vision.recognizeDocument(input.bytes(), input.contentType(), type);
        return document == null ? Result.none(type) : new Result(true, document.documentType(), document.fields(), document.confidence());
    }

    public record Result(boolean recognized, CertVisionPort.DocumentType documentType, Map<String, String> fields, double confidence) {
        static Result none(CertVisionPort.DocumentType type) { return new Result(false, type, Map.of(), 0); }
    }
}
