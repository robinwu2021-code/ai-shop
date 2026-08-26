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
import ai.neargo.shop.merchant.dto.StoreVO;
import ai.neargo.shop.merchant.dto.StoreProfileVO;
import ai.neargo.shop.merchant.service.MerchantPaymentService;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.merchant.service.StoreAdminService;
import ai.neargo.shop.merchant.service.StoreCategoryService;
import ai.neargo.shop.merchant.service.MerchantStoreService;
import ai.neargo.shop.merchant.service.MerchantService;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
    /** 资金路径 —— B 端价格字段叫什么由它决定，判据与积分能力同一根轴 */
    private final MerchantQueryPort merchantQueryPort;
    /** 资质：商家自己传证、看有效期、看这张证能解锁哪几类 */
    private final ai.neargo.shop.merchant.service.MerchantGovernService governService;
    /** 类目树：把门槛码翻成商家看得懂的类目名（跨域拼接放应用层） */
    private final ai.neargo.shop.product.service.CategoryService categoryService;
    /** 无证照快速开店：建占位主体 + 默认门店，不进审核队列 */
    private final MerchantAdminPort merchantAdminPort;
    /** 多证照：本次操作作用在哪张证照上，唯一判定点（见 {@code requireOwned}） */
    private final ai.neargo.shop.merchant.service.MerchantEntityService entityService;

    public BizMerchantController(MerchantService merchantService, OpsService opsService,
                                 UserService userService, BizIdentityResolver identityResolver,
                                 MerchantStoreService storeService,
                                 MerchantPaymentService paymentService,
                                 StoreAdminService storeAdminService,
                                 MerchantStaffService staffService,
                                 MerchantQueryPort merchantQueryPort,
                                 StoreCategoryService storeCategoryService,
                                 ai.neargo.shop.merchant.service.MerchantGovernService governService,
                                 ai.neargo.shop.product.service.CategoryService categoryService,
                                 MerchantAdminPort merchantAdminPort,
                                 ai.neargo.shop.merchant.service.MerchantEntityService entityService) {
        this.entityService = entityService;
        this.categoryService = categoryService;
        this.merchantAdminPort = merchantAdminPort;
        this.governService = governService;
        this.storeCategoryService = storeCategoryService;
        this.merchantQueryPort = merchantQueryPort;
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
     * 本次操作作用在哪张证照上。
     *
     * <p>薄薄一层，只为把「当前登录人」这个参数锁死 —— 归属校验本身在
     * {@link ai.neargo.shop.merchant.service.MerchantEntityService#requireOwned} 里，
     * 那是唯一一份。这里绝不能把 {@code userNo} 也做成参数：那样端上传谁的都行，
     * 校验就等于没有。
     */
    private String ownedEntity(String entityNoParam) {
        return entityService.requireOwned(SecurityUtils.currentUserNo(), entityNoParam);
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

    /**
     * 无证照快速开店：填个店名就把店开起来，<b>不进审核队列</b>。
     *
     * <p>与 {@code /biz/merchant/apply} 是两条路：那条要交执照、等平台审；
     * 这条让老板先把准备工作做完（录商品、配范围、加员工），
     * <b>补齐证照之前买家看不到、也下不了单</b>。
     *
     * <p><b>刻意不要求 {@code BizContext}</b>：还没有任何主体的账号，
     * {@code BizContext.merchantNo} 是空的，所有需要它的接口都会 403 ——
     * 那正是「第一家店」的处境。这里与入驻申请一样只认登录身份
     * （{@code SecurityUtils.currentUserNo()}）。
     *
     * <p>已经有待补证照的占位主体时原样返回它，不建第二个（防连点，见 Port 注释）。
     */
    @PostMapping("/biz/merchant/quick-start")
    public MerchantProfileVO quickStart(@RequestBody QuickStartReq req) {
        String userNo = SecurityUtils.currentUserNo();
        var created = merchantAdminPort.quickStart(new MerchantAdminPort.QuickStartCommand(
                userNo, req.storeName(), req.address()));
        /*
         * **不能用 profile()** —— 它读的是 BizContext，而那是过滤器在<b>请求进来那一刻</b>
         * 解析的：那时这个人还没有任何主体，merchantNo 是空的。主体是刚刚在这个请求里
         * 建出来的，ThreadLocal 里那份不会跟着变，于是端上拿到一个「建成功了但没有主体号」
         * 的响应，只能靠再刷一次才看得到自己的店。
         *
         * 走 profileOf 现算一次作用域 —— 与登录接口同一条路（那里 BizContext 也还是空的）。
         *
         * **要把刚建出来的门店号喂进解析**：这个账号名下可能已经有另一张执照
         * （多证照，比如老板开第二门生意），不带门店号的话解析出来的是他的<b>默认主体</b>——
         * 端上会拿到一个「建成功了」的响应，里面却是上一家店的名字和状态。
         */
        return build(userNo, identityResolver.resolve(userNo, created.storeNo()), phoneOf(userNo));
    }

    /** @param storeName 店名，必填；<b>同时用作主体名</b>，补证照时再被执照上的正式名称覆盖 */
    public record QuickStartReq(String storeName, String address) {
    }

    // ---------------------------------------------------------------- 店铺资料

    /**
     * 店铺资料。从没保存过时返回空表单而不是 404 —— 新店打开设置页看到的应当是待填的表单。
     */
    // ---------------------------------------------------------------- 我的资质

    /**
     * 本店已登记的资质。
     *
     * <p><b>此前商家侧没有任何入口</b>：只有入驻申请那一步能传（而线上入驻申请 0 条，
     * 商家都是直接建的），传完也看不到。于是「上架被拒 → 去哪补证」这条路在
     * B 端是断的 —— 商家看到「你还没有该授权」，然后没有下一步。
     *
     * <p>顺带告诉他<b>这张证能解锁哪几类</b>（按 {@code sys_auth_code.qual_type} 反查），
     * 否则他传完仍旧不知道自己换来了什么。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/qualifications")
    public MyQualificationsVO qualifications(
            @RequestParam(required = false) String entityNo) {
        // 多证照：证照管理页要能直接看另一张的证件，不必先切到那张证照下的某家店去。
        // 不传 = 当前证照（原行为）；传了别人的 = 403，不是静默回落
        String merchantNo = ownedEntity(entityNo);
        /*
         * **码 → 类目名在这一层拼**，不在商家域拼：商家域不读商品域的类目
         * （见 CategoryUsagePort 的说明，那条边界立过一次）。应用层同时看得见两个域，
         * 拼接放这里既不破边界，也不用为此新开一个 port。
         *
         * 为什么非拼不可：商家看的是「食品经营许可证能解锁：肉禽蛋、水产海鲜、熟食卤味」。
         * 只给码名的话，四条「食品经营许可证」在界面上一模一样，他分不出自己缺的是哪一类。
         */
        Map<String, List<String>> namesByCode = new java.util.LinkedHashMap<>();
        for (var lv1 : categoryService.tree()) {
            for (var lv2 : lv1.children()) {
                String code = lv2.requiredCode();
                if (code != null && !code.isBlank()) {
                    namesByCode.computeIfAbsent(code, k -> new java.util.ArrayList<>()).add(lv2.name());
                }
            }
        }
        List<AuthCodeInfoVO> catalog = governService.authCodeCatalog().stream()
                .map(a -> new AuthCodeInfoVO(a.code(), a.name(), a.requiredQualification(),
                        a.qualType(), namesByCode.getOrDefault(a.code(), List.of())))
                .toList();
        return new MyQualificationsVO(
                governService.qualifications(merchantNo),
                merchantQueryPort.authorizedCategoryCodes(merchantNo).stream().toList(),
                catalog);
    }

    /** @param categoryNames 挂着这个码的在售类目名 —— 商家看的是类目，不是码 */
    public record AuthCodeInfoVO(String code, String name, String requiredQualification,
                                 String qualType, List<String> categoryNames) {
    }

    /**
     * 传一张证。<b>传完不自动授码</b> —— 授权是平台看过证之后的动作。
     *
     * <p>这一点要在界面上说清楚：不说的话，商家传完就去上架，撞上同一句拒绝，
     * 而这一次他会认为是系统坏了。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/qualifications/save")
    public ai.neargo.shop.merchant.service.MerchantGovernService.QualificationVO saveQualification(
            @RequestBody SaveQualReq req) {
        String merchantNo = ownedEntity(req.entityNo());
        return governService.saveQualification(merchantNo,
                new ai.neargo.shop.merchant.service.MerchantGovernService.SaveQualificationCommand(req.qualNo(), req.qualType(),
                        req.qualName(), req.qualNumber(), req.imageUrl(), req.expireAt()),
                merchantNo);
    }

    /**
     * @param items          已登记的证
     * @param grantedCodes   已获授权的类目码 —— 端上据此把「已解锁 / 待授权」标出来
     * @param catalog        码字典：这个码要哪一类证、对应哪些类目
     */
    public record MyQualificationsVO(List<ai.neargo.shop.merchant.service.MerchantGovernService.QualificationVO> items,
                                     List<String> grantedCodes,
                                     List<AuthCodeInfoVO> catalog) {
    }

    /** @param entityNo 传给哪张证照，可空 = 当前证照（存量单证照账号永远不传） */
    public record SaveQualReq(String qualNo, String qualType, String qualName,
                              String qualNumber, String imageUrl, Long expireAt, String entityNo) {
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/store")
    public StoreProfileVO store() {
        // 门面（公告/营业时间/地址/坐标）是**门店级**的，跟着 X-Store-No 走。
        // 不带门店号时后端取默认店 —— 单店商家的端上不用感知门店号
        return storeService.profile(BizContext.requireMerchantNo(),
                BizContext.current().currentStoreNo());
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
                BizContext.current().currentStoreNo(),
                new MerchantStoreService.SaveCommand(
                        req.announcement(), req.announcementUntil(),
                        req.openHours(), req.address(), req.addressDetail(),
                        req.featured(),
                        req.serviceScope(), req.serviceCommunityNos(), req.serviceCityCode(),
                        req.fulfillmentReach(), req.serviceAreas(), req.latE6(), req.lngE6()));
    }

    /**
     * 只改公告。**与整份门面资料分开的一条路** —— 公告一天可能改两次，
     * 而地址、营业时间一年改几次；混在一个保存里，改一句话要连带提交全部字段。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/store/announcement")
    public StoreProfileVO saveAnnouncement(@RequestBody AnnouncementReq req) {
        return storeService.saveAnnouncement(BizContext.requireMerchantNo(),
                BizContext.current().currentStoreNo(), req.announcement(), req.announcementUntil(),
                req.alsoStoreNos());
    }

    /**
     * 从「常用」里删一条。**独立一条路而不是塞进保存** ——
     * 删掉一句候选语不该顺带把当前公告改掉。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/store/announcement/recent/remove")
    public StoreProfileVO dropRecentAnnouncement(@RequestBody RecentReq req) {
        return storeService.dropRecentAnnouncement(BizContext.requireMerchantNo(),
                BizContext.current().currentStoreNo(), req.text());
    }

    /**
     * @param announcementUntil 失效时刻（epoch 毫秒），空 = 长期
     * @param alsoStoreNos      同时发到这些门店（多店主体）。空 = 只发当前店 ——
     *                          「南门店今天停电」只对一家成立，所以默认不带
     */
    public record AnnouncementReq(String announcement, Long announcementUntil,
                                  java.util.List<String> alsoStoreNos) {
    }

    /** @param text 要从常用里删掉的那一句，按原文匹配 */
    public record RecentReq(String text) {
    }


    /**
     * 我的收款进件状态（每通道一条）。
     *
     * <p><b>它回答的是「我能收钱了吗、卡在哪」</b> —— 与入驻审核是两件事：
     * 入驻过了店就能开、货能上架，但通道没批就收不了钱，
     * 而这个状态此前在 B 端完全看不到。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/biz/merchant/payment")
    public List<PaymentApplymentVO> payments(@RequestParam(required = false) String entityNo) {
        return paymentService.list(ownedEntity(entityNo));
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
        return paymentService.submit(ownedEntity(req.entityNo()),
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
                             String storeNo, String entityNo) {
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
        /*
         * 「这家店挂在哪张证照下」（02 屏）。不传 = 当前证照，与单证照时代一模一样。
         *
         * 注意额度是**按证照**算的（mch_entity_plan 挂在 entity_no 上），所以挂到
         * 另一张证照下时，撞的是那张的额度 —— 这正是应该的：额度是那张证照买的。
         */
        String merchantNo = ownedEntity(req.entityNo());
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
        /*
         * **认 "default"**，与同一个控制器里的送货方式那两条一致（见 storeFulfillment）。
         *
         * <p>端上深链进来时门店列表还没加载完，那一刻它只知道「我要当前门店的」——
         * 建品页就是这么撞上的：它按约定发了 default，而这条端点当时不认，
         * 于是「本店常卖」整段不显示，看起来与改版前一模一样，没有任何迹象说明少了一段。
         * 一个端点认、另一个不认，是最容易在深链场景下露出来的那种不一致。
         */
        String merchantNo = BizContext.requireMerchantNo();
        String resolved = "default".equals(storeNo) ? defaultStoreNo(merchantNo) : storeNo;
        return resolved == null ? List.of() : storeCategoryService.list(merchantNo, resolved);
    }

    /** 这家主体的默认门店号；一家店都没有时返回 null（新入驻的那一刻） */
    private String defaultStoreNo(String merchantNo) {
        var stores = storeAdminService.list(merchantNo);
        return stores.stream().filter(x -> Boolean.TRUE.equals(x.isDefault())).findFirst()
                .or(() -> stores.stream().findFirst())
                .map(StoreVO::storeNo)
                .orElse(null);
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
    /**
     * @param entityNo 这家店挂在哪张证照下（02 屏「选/建证照」）。
     *                 <b>可空 = 当前证照</b> —— 只有一张证照的账号端上整个不渲染这一步
     */
    public record StoreCreateReq(String name, String address, List<String> categoryNos,
                                 String entityNo) {
    }

    public record StoreCategoriesReq(List<StoreCategoryItemReq> items) {
    }

    public record StoreCategoryItemReq(String categoryNo, String displayName, Integer sort) {
    }

    public record StatusReq(Boolean active) {
    }

    public record StorePaymentReq(String payMerchantNo) {
    }

    /** 对齐 shared {@code StoreProfile}。 */
    /** @param latE6 门店坐标（gcj02，E6）；不传 = 不改 */
    public record StoreReq(String announcement, Long announcementUntil,
                           String openHours, String address, String addressDetail,
                           List<String> featured, String serviceScope,
                           List<String> serviceCommunityNos, String serviceCityCode,
                           String fulfillmentReach,
                           List<MerchantStoreService.AreaCommand> serviceAreas,
                           Integer latE6, Integer lngE6) {
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
        if ("ACTIVE".equals(status)) {
            return "ACTIVE";
        }
        /*
         * **待补证照要单独有一个词，不能折叠进 SUSPENDED。**
         *
         * 其余非 ACTIVE 状态（SUSPENDED / FROZEN / 未知）折叠是对的 ——
         * 冻结与封禁对「我现在能不能干活」的答案一样：不能。
         * 但快速开店建出来的占位主体答案是「能干活，只是还不能开张营业」：
         * 他要进经营台录商品、配范围、加员工。折叠成 SUSPENDED 的话
         * b-app 会把他当成被封禁的店，整个工作台按停业渲染 —— 而他什么也没做错。
         */
        if (ai.neargo.shop.merchant.entity.MchEntity.PENDING_LICENSE.equals(status)) {
            return "PENDING_LICENSE";
        }
        return "SUSPENDED";
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
