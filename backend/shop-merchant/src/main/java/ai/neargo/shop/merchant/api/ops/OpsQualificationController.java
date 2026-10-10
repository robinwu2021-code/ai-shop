package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.merchant.service.MerchantGovernService.QualificationVO;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 商家资质登记（落地清单 P1-7）。
 *
 * <p>此前资质只以图片 URL 的形式留在入驻申请单上，审核通过后没有转存到主体——
 * 运营在商家详情里看不到「这家店有哪些证」，更不知道证什么时候到期。
 *
 * <p>这里把资质变成**结构化记录**（类型 / 编号 / 有效期），
 * 从而让两件事成为可能：定时扫到期、上架时当场拦。
 */
@Profile("ops")
@RestController
@Validated
public class OpsQualificationController {

    private final MerchantGovernService governService;
    private final AuditLogPort auditLogPort;

    public OpsQualificationController(MerchantGovernService governService, AuditLogPort auditLogPort) {
        this.governService = governService;
        this.auditLogPort = auditLogPort;
    }

    @GetMapping("/ops/merchants/{merchantNo}/qualifications")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_CATEGORY_READ + "')")
    public List<QualificationVO> list(@PathVariable String merchantNo) {
        return governService.qualifications(merchantNo);
    }

    /**
     * 登记或更新一条资质。{@code expireAt} 留空表示**长期有效**——
     * 与「已过期」是两回事，扫描任务不会碰它。
     */
    @PostMapping("/ops/merchants/{merchantNo}/qualifications")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_CATEGORY_GRANT + "')")
    public QualificationVO save(@PathVariable String merchantNo, @RequestBody SaveReq req) {
        String operator = SecurityUtils.currentUserNo();
        QualificationVO vo = governService.saveQualification(merchantNo,
                new MerchantGovernService.SaveQualificationCommand(req.qualNo(), req.qualType(),
                        req.qualName(), req.qualNumber(), req.imageUrl(), req.expireAt()),
                operator);
        auditLogPort.record("QUALIFICATION_SAVE", merchantNo,
                vo.qualName() + "｜有效期至 " + (vo.expireAt() == null ? "长期" : vo.expireAt()));
        return vo;
    }

    /** 撤销。不物理删——「当初有没有这张证」是要能查的。 */
    @PostMapping("/ops/qualifications/{qualNo}/revoke")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_CATEGORY_GRANT + "')")
    public QualificationVO revoke(@PathVariable String qualNo) {
        QualificationVO vo = governService.revokeQualification(qualNo, SecurityUtils.currentUserNo());
        auditLogPort.record("QUALIFICATION_REVOKE", qualNo, vo.qualName());
        return vo;
    }

    public record SaveReq(String qualNo, String qualType, String qualName,
                          String qualNumber, String imageUrl, Long expireAt) {
    }
}
