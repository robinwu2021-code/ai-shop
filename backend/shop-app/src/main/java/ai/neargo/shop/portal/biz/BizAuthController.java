package ai.neargo.shop.portal.biz;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.LoginAuditor;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.community.dto.CommunityVO;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.community.service.CommunityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** B 端复用个人登录即注册。返回的商家会话记录主体种类，当前门店由每次请求选择。 */
@Profile("api")
@RestController
public class BizAuthController {

    private final AuthService authService;

    private final LoginAuditor auditor;
    private final CommunityService communityService;
    private final BizMerchantController merchantController;
    private final MerchantStaffService merchantStaffService;
    private final TokenStore tokenStore;

    public BizAuthController(AuthService authService,
                             LoginAuditor auditor, CommunityService communityService,
                             BizMerchantController merchantController,
                             MerchantStaffService merchantStaffService,
                             TokenStore tokenStore) {
        this.merchantStaffService = merchantStaffService;
        this.tokenStore = tokenStore;
        this.authService = authService;
        this.auditor = auditor;
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
        AuthService.LoginResult result;
        try {
            result = authService.login(new AuthService.LoginCommand(
                    req.grantType(), req.principal(), req.credential(),
                    // B 端登录不做进店归因：店主登录自己的后台，不是"从谁的分享进的店"
                    null, null, req.agreed()));
        } catch (BizException e) {
            /*
             * **记进 MERCHANT 池，而不是会话所在的 CONSUMER 池。**
             *
             * 审计问的是「哪个端在被刷」，那与会话存在哪里是两件事。
             * A7 之后成功与失败都落在 mch_login_log，两者合到了一处；
             * 在此之前它们是分开的，因为把 B 端的失败混进 C 端那张表，
             * 「有人在刷商家登录」就再也看不出来了。
             *
             * 只包住 authService.login 这一步：后面查商家档案失败不是登录失败。
             */
            auditor.failed(Realm.MERCHANT,
                    LoginAuditor.maskPrincipal(req.principal()),
                    e.errorCode() == null ? "UNKNOWN" : e.errorCode().name());
            throw e;
        }
        // The shared login service issued a consumer credential. Retire it before profile
        // lookups or audit writes, either of which can fail.
        tokenStore.revoke(result.token());
        var personal = LoginUser.merchantByUser(result.user().userNo(), result.user().nickname());
        var profile = merchantController.profileOf(personal, null, result.user().phone());
        if (profile.merchantNo().isBlank() && AuthService.GRANT_PHONE_OTP.equals(req.grantType())) {
            var staffToken = merchantStaffService.issueStaffSession(req.principal());
            if (staffToken.isPresent()) return staffResponse(staffToken.get());
        }
        auditor.succeeded(Realm.MERCHANT, personal.userNo());
        return new MerchantLoginResp(tokenStore.issue(TokenStore.SessionData.of(personal)), profile);
    }

    @PostMapping("/biz/auth/logout")
    public void logout() {
        var token = SecurityUtils.currentToken().filter(t -> t.startsWith(Realm.MERCHANT.tokenPrefix()))
                .orElseThrow(() -> BizException.of(ErrorCode.TOKEN_EXPIRED));
        tokenStore.revoke(token);
    }

    static LoginUser personalUser() {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT || user.subjectKind() != SubjectKind.USR)
            throw BizException.of(ErrorCode.FORBIDDEN);
        return user;
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
        authService.setPassword(personalUser().userNo(), req.password());
    }

    /** 我设过密码没有 —— 端上据此决定「我的」页里显示「设置密码」还是「修改密码」 */
    @GetMapping("/biz/auth/password")
    public HasPasswordResp hasPassword() {
        return new HasPasswordResp(
                authService.hasPassword(personalUser().userNo()));
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
        String token;
        try {
            token = merchantStaffService.loginByPhone(req.phone(), req.code());
        } catch (BizException e) {
            auditor.failed(Realm.MERCHANT,
                    LoginAuditor.maskPrincipal(req.phone()),
                    e.errorCode() == null ? "UNKNOWN" : e.errorCode().name());
            throw e;
        }
        return staffResponse(token);
    }

    private MerchantLoginResp staffResponse(String token) {
        try {
            var user = tokenStore.get(token).map(TokenStore.SessionData::user)
                    .filter(u -> u.realm() == Realm.MERCHANT && u.subjectKind() == SubjectKind.MCH)
                    .orElseThrow(() -> BizException.of(ErrorCode.TOKEN_EXPIRED));
            var profile = merchantController.profileOf(user, null, null);
            auditor.succeeded(Realm.MERCHANT, user.userNo());
            return new MerchantLoginResp(token, profile);
        } catch (RuntimeException failure) {
            tokenStore.revoke(token);
            throw failure;
        }
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
