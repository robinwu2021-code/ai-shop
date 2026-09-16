package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.Phones;
import ai.neargo.shop.merchant.service.SelfOperatedService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 建<b>平台自营商家</b>。
 *
 * <p>第三方商家走「C 端提交进件资料 → 运营审核 → 通过后建主体」。
 * 平台自己走那条路就是<b>向自己提交执照、再自己审自己</b>，所以这里开一个特殊入口。
 * 为什么可以不要进件资料、哪几条反而一条都不能省，见 {@link SelfOperatedService}。
 *
 * <p><b>权限刻意只给超管</b>：{@link Perms#MERCHANT_SELFOP_CREATE} 不在任何角色的码表里，
 * 于是只有持 {@code "*"} 的 {@code SUPER_ADMIN} 能调。自营主体建出来之后，
 * 平台就是那批货的销售主体、售后直接进平台仲裁 —— 不该是招商日常能点的东西。
 */
@Profile("ops")
@RestController
@Validated
public class OpsSelfOperatedController {

    private final SelfOperatedService selfOperatedService;
    private final AuditLogPort auditLogPort;

    public OpsSelfOperatedController(SelfOperatedService selfOperatedService,
                                     AuditLogPort auditLogPort) {
        this.selfOperatedService = selfOperatedService;
        this.auditLogPort = auditLogPort;
    }

    @PostMapping("/ops/merchants/self-operated")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_SELFOP_CREATE + "')")
    public SelfOperatedService.ResultVO create(@Valid @RequestBody CreateReq req) {
        String operator = SecurityUtils.currentUserNo();
        var vo = selfOperatedService.create(new SelfOperatedService.CreateCommand(
                req.phone(), req.name(), req.communityNos(), req.industry(), req.description()),
                operator);
        /*
         * 幂等命中也记一条，且**把是不是新建写进摘要**。
         * 否则日后查「这个平台主体是谁什么时候建的」时，会看到一串一模一样的记录，
         * 分不清哪一条才是真正建出它的那次。
         */
        auditLogPort.record("MERCHANT_SELFOP_CREATE", vo.merchantNo(),
                (vo.created() ? "新建" : "幂等命中已有") + "平台自营商家 " + req.name()
                        + "，覆盖社区 " + req.communityNos().size() + " 个");
        return vo;
    }

    /**
     * @param communityNos 覆盖社区，<b>必填</b>。空集合在这里就拒，不是「先建了再说」——
     *                     没有覆盖社区的商家上着架却对谁都不可见，而这个故障没有任何报错（ADR-009）
     */
    public record CreateReq(
            @NotBlank @Pattern(regexp = Phones.CN_MOBILE, message = Phones.MESSAGE) String phone,
            @NotBlank String name,
            @NotEmpty List<String> communityNos,
            String industry,
            String description) {
    }
}
