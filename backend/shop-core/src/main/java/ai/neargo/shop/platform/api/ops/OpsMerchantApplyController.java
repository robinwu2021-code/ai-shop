package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.Phones;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.user.UserProvisionPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · <b>代商家提交入驻申请</b>（三期）。BD 在店里把执照拍下来、当场替老板填完。
 *
 * <p><b>它与「建平台自营商家」不是同一件事</b>，虽然两个界面长得几乎一模一样，
 * 最容易被合成一条路。自营能跳过进件是因为「进件资料的意义是核验那个第三方是谁，
 * 而自营不存在第三方」—— <b>代填的时候第三方是存在的</b>，论证不成立，豁免也就不成立：
 * 证件照要、进件照走、订阅额度照吃、单子照进审核队列。本期只改「谁来填这张表」。
 *
 * <p><b>刻意不做「填完即激活」。</b> 制单与审核分离在这个仓库里是有先例的，
 * 而代填最需要那一层：资料是运营录的，核验不该也是同一个人。
 *
 * <p>路径用复数 {@code /ops/merchants/**}：同域里 {@code /ops/merchant/apply} 那一族
 * 是存量单数，新端点跟随现行约定（{@code /mp} {@code /biz} 单数、{@code /ops} 复数），
 * 与 {@code /ops/merchants/self-operated} 同一族。
 */
@Profile("ops")
@RestController
@Validated
public class OpsMerchantApplyController {

    private final OpsService opsService;
    private final UserProvisionPort userProvision;
    private final AuditLogPort auditLog;

    public OpsMerchantApplyController(OpsService opsService,
                                      UserProvisionPort userProvision,
                                      AuditLogPort auditLog) {
        this.opsService = opsService;
        this.userProvision = userProvision;
        this.auditLog = auditLog;
    }

    /**
     * 代商户提交进件申请。
     *
     * <p><b>会给这个手机号建一个账号</b>（{@code ensureUserByPhone}，与自营入口同一条路），
     * 而<b>本人当时并不知道</b>。不建的话审核通过时没有 owner 可挂，所以只能建 ——
     * 代价是商户首次登录必须看到「你的资料由谁在什么时候代为提交」，
     * 而不是发现自己名下凭空有一家店。那一屏由 {@code submitted_by} 驱动。
     *
     * <p><b>协议不在这里勾。</b> 单子落库时 {@code agreed_at} 一律为空 ——
     * 运营不能替商户同意《商家服务协议》。
     */
    @PostMapping("/ops/merchants/apply-on-behalf")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_APPLY_ONBEHALF + "')")
    public ApplyOnBehalfVO applyOnBehalf(@Valid @RequestBody OnBehalfReq req) {
        String operator = SecurityUtils.currentUserNo();
        String ownerUserNo = userProvision.ensureUserByPhone(req.phone());
        String applyNo = opsService.createApplyOnBehalf(new OpsService.SubmitApplyCommand(
                ownerUserNo, req.name(), req.subject(),
                req.contactName(), req.contactPhone(), req.category(), req.description(),
                req.serviceScope(), req.communityNos(), req.qualifications(),
                Boolean.TRUE.equals(req.asPickupPoint()), req.industry(),
                req.qualificationItems()), operator);
        /*
         * 审计里写「进队列待审」而不是「已创建」。
         * 代填最容易被误解成「运营把这家店开出来了」—— 而它只是录了一张表，
         * 主体要等审核通过才存在。日后查「这家店谁开的」时，这一条要能自己说清楚。
         */
        auditLog.record("MERCHANT_APPLY_ONBEHALF", applyNo,
                "代商户提交进件 " + req.name() + "，已进审核队列待审；协议未勾（商户需自行补）");
        return new ApplyOnBehalfVO(applyNo, ownerUserNo);
    }

    /**
     * @param applyNo     落库的申请单号
     * @param ownerUserNo 商户本人的 userNo。<b>可能是这一刻新建的</b> ——
     *                    界面上要据此提示「这个号是新开的，本人还不知道」
     */
    public record ApplyOnBehalfVO(String applyNo, String ownerUserNo) {
    }

    /**
     * @param phone 商户本人手机号。<b>它决定这张单最终挂给谁</b> ——
     *              填错就是把一家店挂到别人名下，而两边都不会报错
     */
    public record OnBehalfReq(
            @NotBlank @Pattern(regexp = Phones.CN_MOBILE, message = Phones.MESSAGE) String phone,
            @NotBlank String name,
            @NotBlank String subject,
            String contactName,
            String contactPhone,
            String category,
            String description,
            String serviceScope,
            List<String> communityNos,
            List<String> qualifications,
            Boolean asPickupPoint,
            String industry,
            List<OpsService.QualificationItem> qualificationItems) {
    }
}
