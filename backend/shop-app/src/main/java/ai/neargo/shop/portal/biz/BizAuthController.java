package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.community.dto.CommunityVO;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.community.service.CommunityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端登录与基础数据（[API 清单 §3.1]）。
 *
 * <p><b>商家账号与 C 端账号是同一套凭据</b>，不是两个账号池。
 * 共享类型 {@code MerchantLoginResp} 的注释说「两套账号、token 不通用」——
 * 那句话描述的是<b>没有实现的设计</b>：库里 {@code mch_entity.owner_user_no}
 * 指向的就是 C 端 userNo，{@code BizContextFilter} 也是靠它解析 merchantNo 的。
 *
 * <p>一套凭据是对的：入驻申请人此刻还只是个普通用户，审核通过后<b>同一个账号</b>
 * 长出了商家身份。分成两套就要回答「入驻通过时给他发个新账号吗、旧账号还能不能买东西」，
 * 而这两个问题在邻里场景下都没有好答案 —— 店主本来就是小区里的买家。
 *
 * <p>真正的隔离在<b>运营池</b>：C 端 token 打 {@code /ops/**} 一律 401（realm 前缀不符），
 * 那才是必须分开的两套。
 */
@Profile("api")
@RestController
public class BizAuthController {

    private final AuthService authService;
    private final CommunityService communityService;
    private final BizMerchantController merchantController;
    private final ai.neargo.shop.merchant.service.MerchantStaffService merchantStaffService;
    private final ai.neargo.shop.auth.TokenStore tokenStore;

    public BizAuthController(AuthService authService, CommunityService communityService,
                             BizMerchantController merchantController,
                             ai.neargo.shop.merchant.service.MerchantStaffService merchantStaffService,
                             ai.neargo.shop.auth.TokenStore tokenStore) {
        this.merchantStaffService = merchantStaffService;
        this.tokenStore = tokenStore;
        this.authService = authService;
        this.communityService = communityService;
        this.merchantController = merchantController;
    }

    /**
     * 商家登录。游客端点。
     *
     * <p>返回体里带 {@code merchant} 而不是让前端登录后再取一次：B 端在拿到 profile
     * 之前<b>没法决定进哪一屏</b>（经营台 / 入驻流程 / 驳回补料），
     * 分两次请求就会先闪一下错误的那一屏。
     */
    @PostMapping("/biz/auth/login")
    public MerchantLoginResp login(@RequestBody LoginReq req) {
        AuthService.LoginResult result = authService.login(new AuthService.LoginCommand(
                req.grantType(), req.principal(), req.credential(),
                // B 端登录不做进店归因：店主登录自己的后台，不是"从谁的分享进的店"
                null, null, req.agreed()));
        // 手机号直接从登录结果取：此刻 SecurityContext 里还没有人
        // （过滤器跑在发 token 之前），去查 userService.profile() 只会拿到空
        String phone = result.user().phone();
        BizMerchantController.MerchantProfileVO owner = merchantController.profileOf(result.user().userNo(), phone);
        /*
         * 查员工表要用**请求里那个原始号**，不能用登录结果里的 ——
         * 后者是打过码的（`185****8359`，B12：完整号码永远不出 UserQueryPort）。
         * 拿掩码去 `where login_phone = ?` 永远查不到，而表现是「店员登进去
         * 变成了没开店的新用户」：不报错，只是他的店不见了。
         */
        String rawPhone = AuthService.GRANT_PHONE_OTP.equals(req.grantType())
                ? nz(req.principal()) : "";

        /*
         * **登录页不问「你是老板还是店员」，后端按身份判**（2026-08-15 拍板）。
         *
         * 让人先选身份的问题不在于多点一下，而在于**他可能选错，而选错的表现
         * 是「验证码错误」或「你不是店员」**——两句都在说谎，真正的原因是他点了另一个 tab。
         * 店员这个身份还是老板给他的，他自己未必知道自己在系统里算什么。
         *
         * 判定顺序 **老板 → 店员 → 新用户**，理由是「自己的店优先」：
         * 一个人可能既开着自己的店、又被邻居的店加成店员（`mch_account` 里
         * 老板行的 `login_phone` 是 NULL、店员行才有值，所以这两行能同时存在）。
         * 那种情况下他打开这个 App 十有八九是要管自己的店。
         *
         * 反过来，店员没有主体时 `merchantNo` 为空，走下面这一支拿到员工会话。
         * 都不是就保持消费者会话——工作台会显示「还没有开店 / 去入驻」，
         * 这正是「登录即注册」承诺的那一屏。
         */
        if (owner != null && !nz(owner.merchantNo()).isBlank()) {
            return new MerchantLoginResp(result.token(), owner);
        }
        if (rawPhone.isBlank()) {
            // 微信/Apple 登录拿不到手机号，判不了店员身份 —— 他要走这条路就得先补绑
            return new MerchantLoginResp(result.token(), owner);
        }
        return merchantStaffService.issueStaffSession(rawPhone)
                .map(token -> new MerchantLoginResp(token,
                        merchantController.profileOf(principalOf(token), rawPhone)))
                .orElseGet(() -> new MerchantLoginResp(result.token(), owner));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 发验证码（B 端入口）。
     *
     * <p><b>它和 C 端的 {@code /mp/user/otp/send} 是同一个实现</b> ——
     * 短信通道只有一条，限流与计费口径也只有一份。开这条只是因为
     * <b>B 端不该去打 C 端的路径</b>：前缀纪律（ADR-007）之外，
     * 更实际的原因是这两个端的鉴权链、限流策略将来会分开。
     *
     * <p>此前 B 端根本没有这条链：登录页的「发送验证码」只是把 1234 填进输入框，
     * mock 下看不出问题，而真实环境里没有人收得到验证码。
     */
    @PostMapping("/biz/auth/otp/send")
    public void sendOtp(@RequestBody OtpReq req) {
        authService.sendOtp(req.phone());
    }

    /** @param phone 收码手机号 */
    public record OtpReq(String phone) {
    }

    /**
     * 设置 / 修改登录密码。**要求已登录** —— 当前会话即授权，不收旧密码
     * （理由见 {@code AuthService#setPassword}：要旧密码会把「忘了密码」变成死路）。
     *
     * <p>为什么 B 端要有密码：商家每天开好几次 App，每次都等一条短信是实打实的摩擦；
     * 而店里那台共用手机换人时，验证码还得找到号主本人。
     */
    @PostMapping("/biz/auth/password")
    public void setPassword(@RequestBody PasswordReq req) {
        authService.setPassword(SecurityUtils.currentUserNo(), req.password());
    }

    /** 我设过密码没有 —— 端上据此决定「我的」页里显示「设置密码」还是「修改密码」 */
    @GetMapping("/biz/auth/password")
    public HasPasswordResp hasPassword() {
        return new HasPasswordResp(
                authService.hasPassword(SecurityUtils.currentUserNo()));
    }

    /** @param password 新密码明文（HTTPS 传输，服务端 bcrypt 存储，**不回显、不入日志**） */
    public record PasswordReq(String password) {
    }

    /** @param hasPassword 是否已设过密码 */
    public record HasPasswordResp(boolean hasPassword) {
    }

    /**
     * 员工登录（**兼容端点**）。手机号 + 验证码，不需要 C 端账号。
     *
     * <p>登录页已经不再分「老板 / 店员」——{@code /biz/auth/login} 一条就够，
     * 身份由后端按手机号判（见那里的注释）。这条留着只为两件事：
     * 已发出去的旧版本 App，以及 `MerchantStaffService.loginByPhone` 的
     * 「不是员工就 403」这条语义在测试里还有人依赖。
     *
     * <p><b>新代码不要调它。</b>它与统一登录的差别是：这条**只认员工**，
     * 老板拿自己的手机号打它会得到 403，而那不是任何用户能理解的答复。
     */
    @Deprecated
    @PostMapping("/biz/auth/staff-login")
    public MerchantLoginResp staffLogin(@RequestBody StaffLoginReq req) {
        String token = merchantStaffService.loginByPhone(req.phone(), req.code());
        // 员工可能没有 C 端账号，档案里的 phone 用他的登录号
        return new MerchantLoginResp(token,
                merchantController.profileOf(principalOf(token), req.phone()));
    }

    /** 从刚发的令牌里取回 principal —— 此刻 SecurityContext 里还没有人。 */
    private String principalOf(String token) {
        return tokenStore.get(token).map(d -> d.user().userNo()).orElse("");
    }

    public record StaffLoginReq(String phone, String code) {
    }

    /**
     * 社区列表。入驻选覆盖范围、店铺配经营范围都要用它（ADR-009）。
     *
     * <p>不带定位参数：商家选的是「我送得到哪些小区」，那是他自己知道的经营半径，
     * 与他此刻站在哪儿无关。C 端的 {@code nearby} 才需要定位。
     */
    @GetMapping("/biz/communities")
    public List<CommunityVO> communities() {
        return communityService.all();
    }

    public record LoginReq(String grantType, String principal, String credential, Boolean agreed) {
    }

    /** 对齐 shared {@code MerchantLoginResp}。 */
    public record MerchantLoginResp(String token,
                                    BizMerchantController.MerchantProfileVO merchant) {
    }
}
