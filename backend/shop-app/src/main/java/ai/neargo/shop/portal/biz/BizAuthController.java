package ai.neargo.shop.portal.biz;

import ai.neargo.shop.community.dto.CommunityVO;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.community.service.CommunityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
        return new MerchantLoginResp(result.token(),
                merchantController.profileOf(result.user().userNo(), result.user().phone()));
    }

    /**
     * 员工登录（App 路径）。手机号 + 验证码，**不需要 C 端账号**。
     *
     * <p>与 {@code /biz/auth/login} 并存而不是合并：那条走的是 C 端账号池
     * （老板从 C 端发起入驻，他本来就是消费者），这条走员工自己的登录身份。
     * 合成一条就要在里面判断「这个手机号是消费者还是店员」——
     * 而同一个手机号完全可能两者都是。
     *
     * <p>验证码沿用 {@code /mp/user/otp/send} 发送 —— 短信通道只有一条，
     * 再开一条只会多一份限流与计费口径。
     */
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
