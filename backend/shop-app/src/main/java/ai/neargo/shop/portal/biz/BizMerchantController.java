package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.merchant.dto.MerchantAccountVO;
import ai.neargo.shop.merchant.dto.PaymentApplymentVO;
import ai.neargo.shop.merchant.dto.StaffVO;
import ai.neargo.shop.merchant.dto.StoreVO;
import ai.neargo.shop.merchant.dto.StoreProfileVO;
import ai.neargo.shop.merchant.service.MerchantPaymentService;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.merchant.service.StoreAdminService;
import ai.neargo.shop.merchant.service.MerchantStoreService;
import ai.neargo.shop.merchant.service.MerchantService;
import ai.neargo.shop.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端入驻与商家资料（[API 清单 §3.1]）—— 入驻闭环的第三段。
 *
 * <p>前两段已经通了：C 端提交（{@code /mp/merchant/apply}）、平台审核
 * （{@code /ops/merchant/apply/{applyNo}/audit}）。缺的正是这一段 ——
 * <b>审核通过之后，商家自己在 B 端看到了什么</b>。缺了它，
 * 商家登录 B 端只会看到一个 404，既不知道过没过，也拿不到驳回原因。
 *
 * <p><b>本控制器对「还不是商家的人」必须可用</b>，这是它与其他 {@code /biz/**} 的关键差别：
 * 申请人此刻手里没有 merchantNo，如果照搬 {@link BizContext#requireMerchantNo()} 一律 403，
 * 那么被驳回的人就永远看不到驳回原因，也就永远补不了料 —— 闭环在这里断掉。
 */
@Profile("api")
@RestController
public class BizMerchantController {

    private final MerchantService merchantService;
    private final OpsService opsService;
    private final UserService userService;
    private final BizIdentityResolver identityResolver;
    private final MerchantStoreService storeService;
    private final MerchantPaymentService paymentService;
    private final StoreAdminService storeAdminService;
    private final MerchantStaffService staffService;

    public BizMerchantController(MerchantService merchantService, OpsService opsService,
                                 UserService userService, BizIdentityResolver identityResolver,
                                 MerchantStoreService storeService,
                                 MerchantPaymentService paymentService,
                                 StoreAdminService storeAdminService,
                                 MerchantStaffService staffService) {
        this.storeService = storeService;
        this.paymentService = paymentService;
        this.storeAdminService = storeAdminService;
        this.staffService = staffService;
        this.merchantService = merchantService;
        this.opsService = opsService;
        this.userService = userService;
        this.identityResolver = identityResolver;
    }

    /**
     * 商家资料。B 端进入任何页面前先拿它，据此决定是进经营台还是进入驻流程。
     *
     * <p><b>status 把两件事合成了一个字段</b>：审核状态（申请单）与经营状态（商家主体）。
     * 合并是对的 —— B 端要回答的只有一个问题「我现在能不能干活」，
     * 而「审核没过」和「被封了」对这个问题的答案是一样的。
     * 但两者的数据源不同，所以下面按「有没有商家主体」分岔取。
     */
    @GetMapping("/biz/merchant/profile")
    public MerchantProfileVO profile() {
        // 常规请求走 BizContext：过滤器已经按本次 token 解析过，不必再查一次库
        var me = userService.profile();
        return build(SecurityUtils.currentUserNo(), BizContext.current(),
                me == null ? "" : nz(me.phone()));
    }

    /**
     * 按 userNo 组装档案，供<b>登录接口</b>用。
     *
     * <p>登录时 {@link BizContext} 还是空的 —— 过滤器跑在发 token <b>之前</b>，
     * 那一刻请求上还没有任何凭据。所以这里用 {@link BizIdentityResolver} 现算一次作用域，
     * 与过滤器走的是同一段逻辑，不会两处口径分岔。
     */
    MerchantProfileVO profileOf(String userNo, String phone) {
        return build(userNo, identityResolver.resolve(userNo), nz(phone));
    }

    private MerchantProfileVO build(String userNo, BizContext ctx, String phone) {
        MerchantAccountVO account = merchantService.account(ctx.merchantNo());
        MerchantApplyVO apply = opsService.myApply(userNo);
        // 自提点作用域与商家作用域正交（一家店可以不做自提点），所以读作用域而不是查商家
        List<String> pickups = List.copyOf(ctx.pickupNos());

        if (account == null) {
            // 还不是商家：状态完全由申请单决定。没申请过就是 NONE —— 不是错误，是「你还没开始」
            return new MerchantProfileVO(
                    "", apply == null ? "" : apply.name(), "",
                    applyStatus(apply),
                    apply == null ? "PERSONAL" : apply.subject(), "SMALL",
                    phone, false, null,
                    apply == null ? null : apply.rejectReason(),
                    apply == null ? null : apply.industry(),
                    apply == null ? null : apply.desc());
        }
        return new MerchantProfileVO(
                account.merchantNo(), account.name(), account.logo(),
                bizStatus(account.status()), account.subject(), account.tier(),
                phone, !pickups.isEmpty(), pickups.isEmpty() ? null : pickups.get(0),
                null, account.industry(), account.description());
    }

    /**
     * 上次入驻申请，用于<b>驳回后回填</b>。
     *
     * <p>驳回往往只是缺一张执照。让人从头重填一遍，是把「补交」变成「重来」——
     * 而重来的人有相当一部分就不回来了。
     *
     * @return 没申请过返回 null（HTTP 200 + 空体），不是 404 ——
     *         「没申请过」是正常状态，不该让前端去 catch
     */
    @GetMapping("/biz/merchant/apply")
    public MerchantApplyVO myApply() {
        return opsService.myApply(SecurityUtils.currentUserNo());
    }

    /**
     * 提交入驻申请。
     *
     * <p>与 C 端的 {@code POST /mp/merchant/apply} <b>是同一件事的两个入口</b>，
     * 都落到 {@link OpsService#createApply}，「一人一份进行中申请」的唯一键在库上，
     * 两个入口谁先提交都拦得住。
     *
     * <p>保留 B 端这个入口而不是只留 C 端，是因为 <b>被驳回后重提发生在 B 端</b> ——
     * 那时人已经装了 B 端 App，把他赶回 C 端去改一张执照是没道理的。
     *
     * @return 提交后的最新资料，前端直接替换本地状态；被驳回重提失败时状态仍是 REJECTED，
     *         前端据此留在原页显示驳回原因，而不是跳去一个空的工作台
     */
    @PostMapping("/biz/merchant/apply")
    public MerchantProfileVO apply(@RequestBody ApplyReq req) {
        opsService.createApply(new OpsService.SubmitApplyCommand(
                SecurityUtils.currentUserNo(), req.name(), req.subject(),
                req.contactName(), req.contactPhone(), req.category(), req.desc(),
                req.serviceScope(), req.communityNos(), req.licenses(),
                Boolean.TRUE.equals(req.asPickupPoint()), req.industry()));
        return profile();
    }

    // ---------------------------------------------------------------- 店铺资料

    /**
     * 店铺资料。从没保存过时返回空表单而不是 404 —— 新店打开设置页看到的应当是待填的表单。
     */
    @GetMapping("/biz/store")
    public StoreProfileVO store() {
        return storeService.profile(BizContext.requireMerchantNo());
    }

    /**
     * 保存店铺资料。
     *
     * <p><b>「仅本社区」却一个社区都没选时会被拒</b>（ADR-009）：那等于把自己的货
     * 对所有人隐藏，而商家看到的只会是「保存成功、商品在架、订单为零」。
     */
    @PostMapping("/biz/store")
    public StoreProfileVO saveStore(@RequestBody StoreReq req) {
        return storeService.save(BizContext.requireMerchantNo(),
                new MerchantStoreService.SaveCommand(
                        req.announcement(), req.openHours(), req.address(), req.featured(),
                        req.serviceScope(), req.serviceCommunityNos(), req.serviceCityCode()));
    }

    // ---------------------------------------------------------------- 收款进件

    /**
     * 我的收款进件状态（每通道一条）。
     *
     * <p><b>它回答的是「我能收钱了吗、卡在哪」</b> —— 与入驻审核是两件事：
     * 入驻过了店就能开、货能上架，但通道没批就收不了钱，
     * 而这个状态此前在 B 端完全看不到。
     */
    @GetMapping("/biz/merchant/payment")
    public List<PaymentApplymentVO> payments() {
        return paymentService.list(BizContext.requireMerchantNo());
    }

    /**
     * 补交资料并提交进件。
     *
     * <p>结算账号<b>明文只在这一次请求里存在</b>：转给通道，库里只留掩码，
     * 回显给任何端的也只有掩码 —— 包括商家自己（ADR-002 §5）。
     */
    @PostMapping("/biz/merchant/payment")
    public PaymentApplymentVO submitPayment(@RequestBody PaymentReq req) {
        return paymentService.submit(BizContext.requireMerchantNo(),
                new MerchantPaymentService.SubmitCommand(
                        req.payChannel(), req.settleAccountType(), req.settleAccount(),
                        req.licenses(), req.contactName(), req.contactPhone()));
    }

    /**
     * 主动回查通道结果。
     *
     * <p>留这个入口是因为<b>回调会丢</b>。没有它的话，回调丢了商家就永远停在「审核中」，
     * 只能打电话给运营 —— 而运营也没有别的办法。
     */
    @PostMapping("/biz/merchant/payment/{payChannel}/refresh")
    public PaymentApplymentVO refreshPayment(@PathVariable String payChannel) {
        return paymentService.refresh(BizContext.requireMerchantNo(), payChannel);
    }

    /**
     * @param settleAccount 结算账号明文。**不落库、不进日志**
     */
    public record PaymentReq(String payChannel, String settleAccountType, String settleAccount,
                             List<String> licenses, String contactName, String contactPhone) {
    }

    // ---------------------------------------------------------------- 门店管理（M6）

    /** 我的门店（含停用的）。停用的也要看得见 —— 看不见的话商家会以为店被删了。 */
    @GetMapping("/biz/store/list")
    public List<StoreVO> storeList() {
        return storeAdminService.list(BizContext.requireMerchantNo());
    }

    /**
     * 新建门店。**超出额度直接拒** —— 建出来却打不开的店比拒绝更难解释。
     * 额度现在来自配置（默认 1，与单店时代一致），M4 Plan 落地后由订阅档位决定。
     */
    @PostMapping("/biz/store/create")
    public StoreVO createStore(@RequestBody StoreCreateReq req) {
        return storeAdminService.create(BizContext.requireMerchantNo(), req.name(), req.address());
    }

    /** 改门店名与地址。门面其余部分（公告/营业时间/主推）走 {@code POST /biz/store}。 */
    @PostMapping("/biz/store/{storeNo}/rename")
    public StoreVO renameStore(@PathVariable String storeNo, @RequestBody StoreCreateReq req) {
        return storeAdminService.rename(BizContext.requireMerchantNo(), storeNo,
                req.name(), req.address());
    }

    /** 停用 / 启用。**默认店不能停用** —— 停掉之后「这个主体的店在哪」就没有答案了。 */
    @PostMapping("/biz/store/{storeNo}/status")
    public StoreVO setStoreStatus(@PathVariable String storeNo, @RequestBody StatusReq req) {
        return storeAdminService.setStatus(BizContext.requireMerchantNo(), storeNo,
                Boolean.TRUE.equals(req.active()));
    }

    /** 转移默认店。显式动作 —— 「新店可勾选默认」会出现两家默认或零家默认的中间态。 */
    @PostMapping("/biz/store/{storeNo}/default")
    public StoreVO setDefaultStore(@PathVariable String storeNo) {
        return storeAdminService.setDefault(BizContext.requireMerchantNo(), storeNo);
    }

    /**
     * 换这家店的收款商户号。
     *
     * <p>只能挑本主体已开通的号；传空 = 回到主体默认号（合法操作，不是清空错误）。
     */
    @PostMapping("/biz/store/{storeNo}/payment")
    public StoreVO setStorePayment(@PathVariable String storeNo, @RequestBody StorePaymentReq req) {
        return storeAdminService.setPayment(BizContext.requireMerchantNo(), storeNo,
                req.payMerchantNo());
    }

    public record StoreCreateReq(String name, String address) {
    }

    public record StatusReq(Boolean active) {
    }

    public record StorePaymentReq(String payMerchantNo) {
    }

    // ---------------------------------------------------------------- 员工与授权（B-11.10）

    /** 员工列表（含停用的）。手机号脱敏 —— 完整号回显等于给店长一份可导出的通讯录。 */
    @GetMapping("/biz/staff")
    public List<StaffVO> staffList() {
        return staffService.list(BizContext.requireMerchantNo());
    }

    /**
     * 加员工。**不发密码、不建 C 端账号** —— 他用自己的手机号 + 验证码登录。
     * 已存在（含已停用）时重新启用，而不是报「已存在」：离职再回来是常事。
     */
    @PostMapping("/biz/staff")
    public StaffVO addStaff(@RequestBody StaffReq req) {
        return staffService.add(BizContext.requireMerchantNo(), req.loginPhone());
    }

    /** 停用 / 启用。**老板不能被停用** —— 那是个能把自己锁在门外的按钮。 */
    @PostMapping("/biz/staff/{mchAccountNo}/status")
    public StaffVO setStaffStatus(@PathVariable String mchAccountNo, @RequestBody StatusReq req) {
        return staffService.setStatus(BizContext.requireMerchantNo(), mchAccountNo,
                Boolean.TRUE.equals(req.active()));
    }

    /**
     * 授权到店：给这个员工在某家门店一个角色（MANAGER / CLERK）。
     *
     * <p><b>逐店授权</b> —— A 店店长可以同时是 B 店店员，这是小连锁的常态。
     * {@code role} 传空表示收回这家店的授权。
     */
    @PostMapping("/biz/staff/{mchAccountNo}/store")
    public StaffVO grantStore(@PathVariable String mchAccountNo, @RequestBody GrantReq req) {
        return staffService.grantStore(BizContext.requireMerchantNo(), mchAccountNo,
                req.storeNo(), req.role());
    }

    public record StaffReq(String loginPhone) {
    }

    public record GrantReq(String storeNo, String role) {
    }

    /** 对齐 shared {@code StoreProfile}。 */
    public record StoreReq(String announcement, String openHours, String address,
                           List<String> featured, String serviceScope,
                           List<String> serviceCommunityNos, String serviceCityCode) {
    }

    /** 申请单状态 → B 端口径。PENDING 在端上叫 APPLYING（「已提交，等着」）。 */
    static String applyStatus(MerchantApplyVO apply) {
        if (apply == null) {
            return "NONE";
        }
        return switch (apply.status()) {
            case "PENDING" -> "APPLYING";
            case "REVIEWING" -> "REVIEWING";
            case "REJECTED" -> "REJECTED";
            // APPROVED 但查不到商家主体：审核事务只提交了一半，属于故障而不是某个状态。
            // 报 APPLYING 会让商家一直等一个不会来的结果，所以照实说「被驳回了」更不对 ——
            // 这里返回 NONE 让前端引导重新提交，同时运营侧的审计日志里查得到那次通过
            default -> "NONE";
        };
    }

    /** 商家主体状态 → B 端口径。BANNED 与 SUSPENDED 在端上不区分：都是「不能干活」。 */
    /**
     * 经营状态 → B 端口径。
     *
     * <p><b>FROZEN 被折叠进 SUSPENDED</b>，这是有意的：B 端要回答的只有
     * 「我现在能不能干活」，冻结与封禁对这个问题的答案一样。
     * 所以 shared 的 {@code MerchantStatus} 里**不该有 FROZEN** ——
     * 它永远不会被下发，写进端上契约只会变成一个筛不出东西的死分支。
     *
     * <p>兜底方向是 SUSPENDED 而不是 ACTIVE：将来库里多出一个没人认识的状态时，
     * 宁可误挡也不能误放 —— 放错了是让一家本该停业的店继续卖货。
     */
    static String bizStatus(String status) {
        return "ACTIVE".equals(status) ? "ACTIVE" : "SUSPENDED";
    }

    /** 登录手机号已在 UserVO 里按 C 端同口径脱敏，B 端只用来展示「已绑定 138****8000」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 与 C 端 {@code MpCatalogController.ApplyReq} 同构 —— 同一件事的两个入口，
     * 入参分岔迟早让两边的校验走偏。
     */
    public record ApplyReq(String name, String subject, String contactName, String contactPhone,
                           String category, String desc, String serviceScope,
                           List<String> communityNos, List<String> licenses,
                           Boolean asPickupPoint,
                           /** 行业。**决定可选的主体类型** —— 线上业态不能选小微 */
                           String industry) {
    }

    /** 对齐 shared {@code MerchantProfile}。 */
    /**
     * @param industry    行业。B 端要展示它，也要在「改主体」时据此判断哪些主体可选
     * @param description 店铺简介。C 端门店页展示的就是它
     */
    public record MerchantProfileVO(String merchantNo, String name, String logo, String status,
                                    String subject, String tier, String phone,
                                    boolean isPickupPoint, String pickupNo, String rejectReason,
                                    String industry, String description) {
    }
}
