package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.merchant.dto.RoleVO;
import ai.neargo.shop.merchant.dto.StaffLogVO;
import ai.neargo.shop.merchant.dto.StaffVO;
import ai.neargo.shop.merchant.service.MerchantRoleService;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · <b>谁能在这家店干什么</b>：员工、逐店授权、自定义角色。
 *
 * <p>从 {@code BizMerchantController} 抽出来的第二块（架构评审 §5.2）。
 * 一次带走四个资源（{@code staff}/{@code roles}/{@code role}/{@code role-perms}），
 * 是那个类里最大的一块。
 *
 * <p><b>这一整块共用同一档权限：{@code biz:store:admin}（只有老板）</b>，
 * 而且这不是巧合 —— 「谁给谁加了什么权限」本身就是权限信息，
 * 能看它的人不该比能改它的人多。放在一个类里，
 * 这条口径就成了看得见的类不变量，而不是散在九个节里各写一遍的注解。
 *
 * <p>纯搬家：方法体、注解、路径、权限码<b>逐字未动</b>。
 *
 * <p><b>遗留的命名不一致，故意没在这次改</b>：列表与新建走 {@code /biz/roles}，
 * 改名与删除却走 {@code /biz/role/{roleCode}}。内聚判据原本把这两种写法数成两个资源
 * （所以本类一度显示 4 个），已改成单复数归一 —— 但归一是<b>让判据别误报</b>，
 * 不是说这处不一致不存在。要统一得改 URL，那是<b>行为变化</b>：
 * 装在用户手机上的旧版 b-app 还在打老路径，跟这次搬家混在一起出问题会不知道回滚哪个。
 */
@Profile("api")
@RestController
public class BizStaffController {

    private final MerchantStaffService staffService;
    private final MerchantRoleService roleService;

    public BizStaffController(MerchantStaffService staffService, MerchantRoleService roleService) {
        this.staffService = staffService;
        this.roleService = roleService;
    }

    /**
     * 停用 / 启用的入参。
     *
     * <p><b>与 {@code BizMerchantController.StatusReq} 形状相同，但没有共用一个类型</b>：
     * 那个是「这家<b>门店</b>停不停业」，这个是「这个<b>人</b>还在不在职」。
     * 现在长得一样是巧合 —— 共用的话，将来门店停业要加一个「停业公告」字段，
     * 就会顺手加到员工停用的请求体上。
     */
    public record StatusReq(Boolean active) {
    }

    // ---------------------------------------------------------------- 员工与授权（B-11.10）

    /** 员工列表（含停用的）。手机号脱敏 —— 完整号回显等于一次交出全体员工的通讯录。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @GetMapping("/biz/staff")
    public List<StaffVO> staffList() {
        return staffService.list(BizContext.requireMerchantNo());
    }

    /**
     * 加员工。**不发密码、不建 C 端账号** —— 他用自己的手机号 + 验证码登录。
     * 已存在（含已停用）时重新启用，而不是报「已存在」：离职再回来是常事。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/staff")
    public StaffVO addStaff(@RequestBody StaffReq req) {
        return staffService.add(BizContext.requireMerchantNo(), req.loginPhone(), req.displayName());
    }

    /** 停用 / 启用。**老板不能被停用** —— 那是个能把自己锁在门外的按钮。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/staff/{mchAccountNo}/status")
    public StaffVO setStaffStatus(@PathVariable String mchAccountNo, @RequestBody StatusReq req) {
        return staffService.setStatus(BizContext.requireMerchantNo(), mchAccountNo,
                Boolean.TRUE.equals(req.active()));
    }

    /**
     * 授予或撤销这个员工在某家门店的**一个**角色。
     *
     * <p><b>逐店授权</b> —— A 店店长可以同时是 B 店店员，这是小连锁的常态。
     * <b>一人一店还可以有多个角色</b>（V18）：站收银台的顺手把货送了，
     * 就是「店员 + 配送员」。
     *
     * <p>{@code granted=false} 撤销这一个角色；撤到一个不剩 = 从这家店移除他。
     * 不传 granted 视为授予 —— 老接口只有「给」这一个语义，保持兼容。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/staff/{mchAccountNo}/store")
    public StaffVO grantStore(@PathVariable String mchAccountNo, @RequestBody GrantReq req) {
        return staffService.grantStore(BizContext.requireMerchantNo(), mchAccountNo,
                req.storeNo(), req.role(), req.granted() == null || req.granted());
    }

    /**
     * 员工与授权的变更记录（B-11.10.3）。
     *
     * <p>与员工管理三个端点<b>同一档权限</b>（`biz:store:admin`，只有老板）——
     * 「谁给谁加了什么权限」本身就是权限信息，能看它的人不该比能改它的人多。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @GetMapping("/biz/staff/logs")
    public List<StaffLogVO> staffLogs(
            @RequestParam(required = false) String mchAccountNo) {
        return staffService.logs(BizContext.requireMerchantNo(), mchAccountNo);
    }

    // ---------------------------------------------------------------- 角色（V71 自定义角色）

    /**
     * 本主体可用的角色：6 个平台预置（只读）+ 自定义，各带权限码、中文说明与「几个人在用」。
     *
     * <p>与员工管理同一档权限 —— 能改角色 = 能改所有持有者的能力。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @GetMapping("/biz/roles")
    public List<RoleVO> roles() {
        return roleService.list(BizContext.requireMerchantNo());
    }

    /**
     * 自定义角色<b>可以勾的权限点</b>，带中文说明。
     *
     * <p>为什么不让端上「把 6 个预置角色的权限并起来」当选项：那个并集<b>少一条</b> ——
     * {@code biz:finance}（结算账单与收款进件）只有老板有，而老板那行是 {@code *}。
     * 于是后端明明收这个码，界面上却根本勾不到，看起来像功能没做。
     *
     * <p>{@code biz:store:admin} 不在返回里（{@link BizPerms#assignableCodes()}）——
     * 与 {@link #createRole} 的拒绝是同一份定义，不是两处各写一遍。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @GetMapping("/biz/role-perms")
    public List<PermOption> rolePerms() {
        return BizPerms.assignableCodes().stream()
                .sorted()
                .map(c -> new PermOption(c, BizPerms.LABELS.get(c)))
                .toList();
    }

    /** @param label 中文短说明。端上有自己的中/英/阿三份文案，这份是兜底 */
    public record PermOption(String code, String label) {
    }

    /**
     * 建自定义角色。
     *
     * <p><b>{@code biz:store:admin} 会被拒</b>（{@code MerchantRoleServiceImpl.requirePerms}）——
     * 界面上它根本不出现，这里再挡一次：端点是公开的，绕过界面直接发一个带它的请求
     * 是最容易想到的事。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/roles")
    public RoleVO createRole(@RequestBody RoleReq req) {
        return roleService.create(BizContext.requireMerchantNo(), req.name(), req.perms());
    }

    /** 改名 / 改权限码。**预置角色拒** —— 要改先「复制为自定义角色」 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/role/{roleCode}")
    public RoleVO updateRole(@PathVariable String roleCode,
                                                        @RequestBody RoleReq req) {
        return roleService.update(BizContext.requireMerchantNo(), roleCode,
                req.name(), req.perms());
    }

    /** 删除。**还有人持有时拒** —— 删掉等于那些人的权限凭空消失，而他们看不到任何解释 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/role/{roleCode}/delete")
    public void deleteRole(@PathVariable String roleCode) {
        roleService.delete(BizContext.requireMerchantNo(), roleCode);
    }

    /** @param perms 权限码。不含 {@code biz:store:admin} —— 见 {@link #createRole} */
    public record RoleReq(String name, List<String> perms) {
    }

    /** @param displayName 备注名。为空时端上回落脱敏号 —— 不强制，但强烈建议填 */
    public record StaffReq(String loginPhone, String displayName) {
    }

    public record GrantReq(String storeNo, String role, Boolean granted) {
    }
}
