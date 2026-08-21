package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
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
import ai.neargo.shop.merchant.service.StoreCategoryService;
import ai.neargo.shop.merchant.service.MerchantStoreService;
import ai.neargo.shop.merchant.service.MerchantService;
import ai.neargo.shop.community.service.CommunityAdminService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
    /** 门店货架 —— 建店时一并摆上，之后商家自己调 */
    private final StoreCategoryService storeCategoryService;
    private final MerchantStaffService staffService;
    private final ai.neargo.shop.merchant.service.MerchantRoleService roleService;
    /** 提报新社区（ADR-013 阶段三）：社区是 community 域的主数据，商家只是提报方 */
    private final ai.neargo.shop.community.service.CommunityAdminService communityAdminService;
    /** 资金路径 —— B 端价格字段叫什么由它决定，判据与积分能力同一根轴 */
    private final MerchantQueryPort merchantQueryPort;

    public BizMerchantController(MerchantService merchantService, OpsService opsService,
                                 UserService userService, BizIdentityResolver identityResolver,
                                 MerchantStoreService storeService,
                                 MerchantPaymentService paymentService,
                                 StoreAdminService storeAdminService,
                                 MerchantStaffService staffService,
                                 ai.neargo.shop.merchant.service.MerchantRoleService roleService,
                                 ai.neargo.shop.community.service.CommunityAdminService communityAdminService,
                                 MerchantQueryPort merchantQueryPort,
                                 StoreCategoryService storeCategoryService) {
        this.storeCategoryService = storeCategoryService;
        this.merchantQueryPort = merchantQueryPort;
        this.communityAdminService = communityAdminService;
        this.roleService = roleService;
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
        String principal = SecurityUtils.currentUserNo();
        return build(principal, BizContext.current(), phoneOf(principal));
    }

    /**
     * 展示用手机号 —— 取<b>你登录时用的那个身份</b>的号。
     *
     * <p>原先无条件走 {@code userService.profile()}，而那个方法查不到 {@code usr_account}
     * 就抛 401。员工走 {@code /biz/auth/staff-login} 登录，principal 是
     * {@code mch_account_no}，<b>他可能根本没有 C 端账号</b> —— 于是：
     * 登录接口自己组装档案（{@link #profileOf}）时一切正常，
     * 但只要刷新一次页面，这个端点就报「登录已失效」，
     * b-app 拿不到 profile 便退回「还没有开店 · 去入驻」。
     * 店员看到的是自己被踢出了一家其实在正常营业的店。
     */
    private String phoneOf(String principal) {
        var me = userService.profileOrNull();
        if (me != null && !nz(me.phone()).isBlank()) {
            return nz(me.phone());
        }
        return nz(staffService.loginPhoneOf(principal));
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
                    apply == null ? null : apply.desc(),
                    // 还没进件：资金路径未定，不猜一个默认值。
                    // 猜 AGGREGATED 的话，申请人会在入驻页看到「期望收购价」——
                    // 而他此刻还不知道自己会被分到哪条路径
                    null);
        }
        return new MerchantProfileVO(
                account.merchantNo(), account.name(), account.logo(),
                bizStatus(account.status()), account.subject(), account.tier(),
                phone, !pickups.isEmpty(), pickups.isEmpty() ? null : pickups.get(0),
                null, account.industry(), account.description(),
                merchantQueryPort.fundsModeOf(account.merchantNo()));
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
                Boolean.TRUE.equals(req.asPickupPoint()), req.industry(),
                req.qualificationItems()));
        return profile();
    }

    // ---------------------------------------------------------------- 店铺资料

    /**
     * 店铺资料。从没保存过时返回空表单而不是 404 —— 新店打开设置页看到的应当是待填的表单。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
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
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/store")
    public StoreProfileVO saveStore(@RequestBody StoreReq req) {
        return storeService.save(BizContext.requireMerchantNo(),
                new MerchantStoreService.SaveCommand(
                        req.announcement(), req.openHours(), req.address(), req.featured(),
                        req.serviceScope(), req.serviceCommunityNos(), req.serviceCityCode(),
                        req.fulfillmentReach(), req.serviceAreas()));
    }

    // ---------------------------------------------------------------- 提报新社区（ADR-013 阶段三）

    /**
     * 提报一个平台还没有的小区。
     *
     * <p>在这之前商家<b>无路可走</b>：覆盖项只能从已有社区里勾，而「让平台加一个小区」
     * 没有入口 —— 只能找 BD 口头说，说完没人知道进展。
     *
     * <p>要 {@code biz:store} 权限：它与设经营范围是同一件事的两半 ——
     * 能决定「我做哪儿」的人，才该能提「这儿还没开」。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/communities/apply")
    public CommunityAdminService.ApplyVO applyCommunity(@RequestBody CommunityApplyReq req) {
        return communityAdminService.submitApply(BizContext.requireMerchantNo(),
                req.name(), req.address(), req.regionCode(), req.note());
    }

    /**
     * 我提报过的。
     *
     * <p>没有这个列表，提报出去等于石沉大海：商家不知道批没批、被驳回的理由是什么，
     * 只会隔几天再提一次同样的 —— 而那正是运营队列里出现重复条目的来源。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/communities/applies")
    public List<CommunityAdminService.ApplyVO> myCommunityApplies() {
        return communityAdminService.appliesOf(BizContext.requireMerchantNo());
    }

    /** @param regionCode 商家选的区划，**只是建议** —— 最终以运营裁决时填的为准 */
    public record CommunityApplyReq(String name, String address, String regionCode, String note) {
    }

    // ---------------------------------------------------------------- 收款进件

    /**
     * 我的收款进件状态（每通道一条）。
     *
     * <p><b>它回答的是「我能收钱了吗、卡在哪」</b> —— 与入驻审核是两件事：
     * 入驻过了店就能开、货能上架，但通道没批就收不了钱，
     * 而这个状态此前在 B 端完全看不到。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
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
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @PostMapping("/biz/merchant/payment")
    public PaymentApplymentVO submitPayment(@RequestBody PaymentReq req) {
        return paymentService.submit(BizContext.requireMerchantNo(),
                new MerchantPaymentService.SubmitCommand(
                        req.payChannel(), req.settleAccountType(), req.settleAccount(),
                        req.licenses(), req.contactName(), req.contactPhone(), req.storeNo()));
    }

    /**
     * 为某家门店<b>单独进件</b>，拿一个独立的收款号 —— 这是「分开结算」的入口。
     *
     * <p>不调它就是合并结算：门店不配号，走主体默认号。
     * 两种模式都是配置的结果，<b>没有开关</b>。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @PostMapping("/biz/merchant/payment/store/{storeNo}")
    public PaymentApplymentVO openStorePayment(@PathVariable String storeNo,
                                               @RequestBody(required = false) PaymentReq req) {
        return paymentService.openForStore(BizContext.requireMerchantNo(), storeNo,
                req == null ? null : req.payChannel());
    }

    /**
     * 主动回查通道结果。
     *
     * <p>留这个入口是因为<b>回调会丢</b>。没有它的话，回调丢了商家就永远停在「审核中」，
     * 只能打电话给运营 —— 而运营也没有别的办法。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @PostMapping("/biz/merchant/payment/{payChannel}/refresh")
    public PaymentApplymentVO refreshPayment(@PathVariable String payChannel,
                                             @RequestParam(required = false) String storeNo) {
        return paymentService.refresh(BizContext.requireMerchantNo(), payChannel, storeNo);
    }

    /**
     * @param settleAccount 结算账号明文。**不落库、不进日志**
     * @param storeNo       为哪家门店进件；**为空 = 主体级默认号**（单店永远走这条）
     */
    public record PaymentReq(String payChannel, String settleAccountType, String settleAccount,
                             List<String> licenses, String contactName, String contactPhone,
                             String storeNo) {
    }

    // ---------------------------------------------------------------- 门店管理（M6）

    /**
     * 我的门店（含停用的）。停用的也要看得见 —— 看不见的话商家会以为店被删了。
     *
     * <p><b>这是「我能进哪几家店」的自查，不是门店管理</b>，所以不要 {@code biz:store}：
     * 端上的门店切换器就靠它。要了管理权限的后果是店员一家店都切不了 ——
     * 而「A 店店长 + B 店店员」这种人恰恰是多门店授权的主要用途。
     *
     * <p>范围由 {@link BizContext#storeNos()} 划定，与其余 {@code /biz/**} 同一口径：
     * 老板拿到主体全部门店，店员只拿到被授权的那几家。
     * <b>放开权限的同时必须裁剪</b>，否则店员会看到他进不去的店。
     */
    @GetMapping("/biz/store/list")
    public List<StoreVO> storeList() {
        BizContext ctx = BizContext.current();
        return storeAdminService.list(BizContext.requireMerchantNo()).stream()
                .filter(s -> ctx.owner() || ctx.storeNos().contains(s.storeNo()))
                .toList();
    }

    /**
     * 新建门店。**超出额度直接拒** —— 建出来却打不开的店比拒绝更难解释。
     * 额度现在来自配置（默认 1，与单店时代一致），M4 Plan 落地后由订阅档位决定。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/store/create")
    public StoreVO createStore(@RequestBody StoreCreateReq req) {
        String merchantNo = BizContext.requireMerchantNo();
        StoreVO store = storeAdminService.create(merchantNo, req.name(), req.address());
        /*
         * 建店时把货架也摆上（TDD-品类约束全链路 §3.2）。
         *
         * **一个都不选是合法的** —— 这家店还没想好卖什么，建品时会自动加入。
         * 不勾时复制默认店的：多门店商家开分店卖的多半是同一批货，从零勾选是纯负担。
         */
        String defaultStore = storeAdminService.list(merchantNo).stream()
                .filter(s -> !s.storeNo().equals(store.storeNo()))
                .findFirst().map(StoreVO::storeNo).orElse(null);
        storeCategoryService.initForNewStore(merchantNo, store.storeNo(),
                req.categoryNos(), defaultStore);
        return store;
    }

    /**
     * 这家店的经营类目（货架）。
     *
     * <p>与 {@code mch_entity.category_codes} 是两件事：那是<b>平台批的证</b>
     * （能不能卖这类），这是<b>商家的货架</b>（店里怎么摆）。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/store/{storeNo}/categories")
    public List<StoreCategoryService.StoreCategoryVO> storeCategories(
            @PathVariable String storeNo) {
        return storeCategoryService.list(BizContext.requireMerchantNo(), storeNo);
    }

    /**
     * 整份替换这家店的类目 —— 勾选式界面的天然形状。
     *
     * <p><b>删掉一个底下还有商品的类目会被拒</b>：不拦的话那些商品会挂在一个
     * 这家店已经不存在的货架上，店铺页里就此消失，而商家在商品列表里还看得到它们。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/store/{storeNo}/categories")
    public List<StoreCategoryService.StoreCategoryVO> saveStoreCategories(
            @PathVariable String storeNo, @RequestBody StoreCategoriesReq req) {
        var items = req.items() == null ? List.<StoreCategoryService.Item>of()
                : req.items().stream()
                        .map(i -> new StoreCategoryService.Item(
                                i.categoryNo(), i.displayName(), i.sort()))
                        .toList();
        return storeCategoryService.replace(BizContext.requireMerchantNo(), storeNo, items);
    }

    /** 改门店名与地址。门面其余部分（公告/营业时间/主推）走 {@code POST /biz/store}。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/store/{storeNo}/rename")
    public StoreVO renameStore(@PathVariable String storeNo, @RequestBody StoreCreateReq req) {
        return storeAdminService.rename(BizContext.requireMerchantNo(), storeNo,
                req.name(), req.address());
    }

    /** 停用 / 启用。**默认店不能停用** —— 停掉之后「这个主体的店在哪」就没有答案了。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/store/{storeNo}/status")
    public StoreVO setStoreStatus(@PathVariable String storeNo, @RequestBody StatusReq req) {
        return storeAdminService.setStatus(BizContext.requireMerchantNo(), storeNo,
                Boolean.TRUE.equals(req.active()));
    }

    /** 转移默认店。显式动作 —— 「新店可勾选默认」会出现两家默认或零家默认的中间态。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/store/{storeNo}/default")
    public StoreVO setDefaultStore(@PathVariable String storeNo) {
        return storeAdminService.setDefault(BizContext.requireMerchantNo(), storeNo);
    }

    /**
     * 换这家店的收款商户号。
     *
     * <p>只能挑本主体已开通的号；传空 = 回到主体默认号（合法操作，不是清空错误）。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/store/{storeNo}/payment")
    public StoreVO setStorePayment(@PathVariable String storeNo, @RequestBody StorePaymentReq req) {
        return storeAdminService.setPayment(BizContext.requireMerchantNo(), storeNo,
                req.payMerchantNo());
    }

    /**
     * @param categoryNos 这家店摆哪些货架；<b>为空 = 复制默认店的</b>（多门店商家开分店
     *                    卖的多半是同一批货），没有默认店则先空着，建品时自动加入
     */
    public record StoreCreateReq(String name, String address, List<String> categoryNos) {
    }

    public record StoreCategoriesReq(List<StoreCategoryItemReq> items) {
    }

    public record StoreCategoryItemReq(String categoryNo, String displayName, Integer sort) {
    }

    public record StatusReq(Boolean active) {
    }

    public record StorePaymentReq(String payMerchantNo) {
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
    public List<ai.neargo.shop.merchant.dto.StaffLogVO> staffLogs(
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
    public List<ai.neargo.shop.merchant.dto.RoleVO> roles() {
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
    public ai.neargo.shop.merchant.dto.RoleVO createRole(@RequestBody RoleReq req) {
        return roleService.create(BizContext.requireMerchantNo(), req.name(), req.perms());
    }

    /** 改名 / 改权限码。**预置角色拒** —— 要改先「复制为自定义角色」 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PostMapping("/biz/role/{roleCode}")
    public ai.neargo.shop.merchant.dto.RoleVO updateRole(@PathVariable String roleCode,
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

    /** 对齐 shared {@code StoreProfile}。 */
    public record StoreReq(String announcement, String openHours, String address,
                           List<String> featured, String serviceScope,
                           List<String> serviceCommunityNos, String serviceCityCode,
                           String fulfillmentReach,
                           List<MerchantStoreService.AreaCommand> serviceAreas) {
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
                           String industry,
                           /**
                            * 结构化资质（V79）。与上面的 {@code licenses}（纯图片 URL）并存：
                            * 只有这一份带类型/证号/有效期，**审核通过时才转得进
                            * {@code mch_qualification}** —— 而上架的两个闸门读的就是那张表。
                            * 可空：存量端上还在只传 licenses。
                            */
                           List<OpsService.QualificationItem> qualificationItems) {
    }

    /** 对齐 shared {@code MerchantProfile}。 */
    /**
     * @param industry    行业。B 端要展示它，也要在「改主体」时据此判断哪些主体可选
     * @param description 店铺简介。C 端门店页展示的就是它
     * @param fundsMode   资金路径 AGGREGATED/DIRECT。<b>B 端靠它决定价格字段怎么叫</b> ——
     *                    归集路径下平台是销售主体、最终售价由平台定，商家填的是
     *                    「期望收购价」；直连路径下他自己就是销售主体，那就是售价。
     *                    还没进件的申请人为空 —— 那时资金路径尚未确定
     */
    public record MerchantProfileVO(String merchantNo, String name, String logo, String status,
                                    String subject, String tier, String phone,
                                    boolean isPickupPoint, String pickupNo, String rejectReason,
                                    String industry, String description, String fundsMode) {
    }
}
