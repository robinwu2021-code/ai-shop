package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.merchant.dto.StaffVO;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 商家与门店的人员信息（<b>只读</b>）。
 *
 * <p>为什么运营要看得到：客服接到「我们店的配送员看不到订单」这种电话时，
 * 在此之前唯一的办法是让老板自己截图 —— 而问题往往正出在
 * 「他以为授了、其实没授」，截图里看不出这一点。
 *
 * <p><b>只读，而且刻意不做写</b>：谁能进这家店是商家的雇佣关系，
 * 平台替商家改授权，等于平台替商家决定谁能动他的钱。
 * 真要处置（比如该商家在搞事）走封禁，那是另一个层级的动作，
 * 有单独的权限码与审计。
 *
 * <p>手机号<b>照旧脱敏</b>（{@link StaffVO#loginPhone()}）：
 * 收窄可见范围不等于该把号发出去 —— 这里的可见范围反而更宽，
 * 一个运营账号能看到全平台商家的员工，那就是一份可导出的通讯录。
 */
@Profile("ops")
@RestController
public class OpsMerchantStaffController {

    private final MerchantStaffService staffService;

    public OpsMerchantStaffController(MerchantStaffService staffService) {
        this.staffService = staffService;
    }

    /**
     * 这家商家的员工与他们在各门店的角色。
     *
     * <p>用 {@link Perms#MERCHANT_READ} 而不是新造一个码：
     * 「看得到这家商家」和「看得到这家商家有哪些人」是同一件事的粗细两档，
     * 为它单开一个码只会让权限表多一行没人配的东西。
     */
    @GetMapping("/ops/merchants/{merchantNo}/staff")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public List<StaffVO> staff(@PathVariable String merchantNo) {
        return staffService.list(merchantNo);
    }
}
