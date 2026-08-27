package ai.neargo.shop.user.api.mp;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.WxPhonePort;
import ai.neargo.shop.user.dto.AddressVO;
import ai.neargo.shop.user.dto.UserVO;
import ai.neargo.shop.user.service.AddressService;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.user.service.UserService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C 端用户端点（[API 清单 §2.1]）。路径与 {@code c-app/src/api/endpoints.ts} 逐条对齐。
 */
@Profile("api")
@RestController
@RequestMapping("/mp/user")
@Validated
public class MpUserController {

    private final AuthService authService;

    private final ai.neargo.shop.auth.LoginAuditor auditor;
    private final UserService userService;
    private final AddressService addressService;
    /** 手机号快速验证通道。桩实现恒 false，端上据此回落到验证码 */
    private final WxPhonePort wxPhonePort;

    public MpUserController(AuthService authService, UserService userService,
                            AddressService addressService, WxPhonePort wxPhonePort, ai.neargo.shop.auth.LoginAuditor auditor) {
        this.authService = authService;
        this.auditor = auditor;
        this.userService = userService;
        this.addressService = addressService;
        this.wxPhonePort = wxPhonePort;
    }

    /** 登录建户。游客端点。 */
    @PostMapping("/login")
    public AuthService.LoginResult login(@RequestBody LoginReq req) {
        try {
            return authService.login(new AuthService.LoginCommand(
                    req.grantType(), req.principal(), req.credential(),
                    req.merchantNo(), req.inviterNo(), req.agreed()));
        } catch (ai.neargo.shop.common.BizException e) {
            /*
             * **失败要留痕。** 成功与登出在 TokenStore 的签发/撤销处自动落，
             * 唯独失败走不到那里 —— 而登录是最容易被刷的接口之一，
             * 失败日志是被刷时唯一的证据。
             *
             * 记错误码而不是给用户看的那句话：「密码错误」与「账号被停用」
             * 在排查时是两件事，而它们给用户的提示常常是同一句。
             */
            auditor.failed(ai.neargo.shop.auth.Realm.CONSUMER,
                    ai.neargo.shop.auth.LoginAuditor.maskPrincipal(req.principal()),
                    e.errorCode() == null ? "UNKNOWN" : e.errorCode().name());
            throw e;
        }
    }

    /** 发验证码。返回体不含验证码 —— 这条别为了联调方便破例。 */
    @PostMapping("/otp/send")
    public void sendOtp(@RequestBody OtpReq req) {
        authService.sendOtp(req.phone());
    }

    @GetMapping("/profile")
    public UserVO profile() {
        return userService.profile();
    }

    @PostMapping("/community")
    public UserVO bindCommunity(@RequestBody BindCommunityReq req) {
        return userService.bindCommunity(req.communityNo(), req.pickupNo());
    }

    /** 会话续期。旧 token 立即作废（token rotation）。 */
    @PostMapping("/token/refresh")
    public AuthService.LoginResult refresh(@RequestHeader("Authorization") String authorization) {
        return authService.refresh(bearer(authorization));
    }

    /**
     * 注销账号。**微信对有账号体系的小程序要求提供这个入口**（上架审核会查）。
     *
     * <p>做的是匿名化 + 解绑凭证，不是删行 —— 订单、结算、发票有留存义务。
     * 解绑之后同一个微信再进来是一个全新账号，那才是「注销」。
     */
    @PostMapping("/deregister")
    public void deregister() {
        userService.deregister();
    }

    @PostMapping("/logout")
    public void logout(@RequestHeader("Authorization") String authorization) {
        authService.logout(bearer(authorization));
    }

    @PostMapping("/phone/bind")
    public UserVO bindPhone(@RequestBody BindPhoneReq req) {
        return userService.bindPhone(req.phone(), req.code());
    }

    /**
     * 一键授权当前可不可用。
     *
     * <p>端上据此决定弹层里显示「微信一键获取」还是「手机号 + 验证码」。
     * <b>让后端说了算，不要在端上判</b>：这条通道的可用性取决于小程序认证状态与配置开关，
     * 端上判不出来，写死则认证下来之后还要发一次版。
     */
    @GetMapping("/phone/capable")
    public PhoneCapableResp phoneCapable() {
        return new PhoneCapableResp(wxPhonePort.enabled());
    }

    /**
     * 微信一键授权拿手机号并绑定。
     *
     * <p>与 {@code /phone/bind} 同一个出口：都落到 {@code usr_identity} 的 PHONE 凭证，
     * 冲突处理也一致（属于别人时报 CONFLICT，不自动合并）。
     */
    @PostMapping("/phone/wx")
    public UserVO bindPhoneByWx(@RequestBody WxPhoneReq req) {
        String phone = wxPhonePort.phoneOf(req.code());
        if (phone == null || phone.isBlank()) {
            /*
             * **通道没给出号码时明确报错，不要静默回落到验证码。**
             * 静默回落的话，用户点了「一键获取」却看到验证码表单，
             * 会以为自己点错了 —— 而真正发生的是通道没通。
             */
            throw BizException.of(ErrorCode.WX_PHONE_UNAVAILABLE);
        }
        return userService.bindPhoneTrusted(phone);
    }

    /** @param capable true = 显示一键按钮；false = 显示验证码表单 */
    public record PhoneCapableResp(boolean capable) {
    }

    public record WxPhoneReq(String code) {
    }

    @PostMapping("/profile")
    public UserVO updateProfile(@RequestBody UpdateProfileReq req) {
        return userService.updateProfile(req.nickname(), req.avatar());
    }

    // ---------------------------------------------------------------- 地址簿

    @GetMapping("/address")
    public List<AddressVO> addressList() {
        return addressService.list();
    }

    @PostMapping("/address")
    public List<AddressVO> saveAddress(@RequestBody SaveAddressReq req) {
        return addressService.save(new AddressService.SaveCommand(
                req.addressId(), req.name(), req.phone(), req.region(), req.province(), req.city(),
                req.district(), req.detail(), req.isDefault(), req.tag(),
                req.latE6(), req.lngE6()));
    }

    @PostMapping("/address/{addressId}/archive")
    public List<AddressVO> archiveAddress(@PathVariable String addressId) {
        return addressService.archive(addressId);
    }

    @PostMapping("/address/{addressId}/default")
    public List<AddressVO> setDefaultAddress(@PathVariable String addressId) {
        return addressService.setDefault(addressId);
    }

    /** 过滤器已校验过 Bearer 格式，这里只做裁剪。 */
    private String bearer(String authorization) {
        return authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
    }

    public record BindPhoneReq(@NotBlank String phone, @NotBlank String code) {
    }

    public record UpdateProfileReq(String nickname, String avatar) {
    }

    /** @param latE6 地图选点给的坐标（gcj02，E6）；不传 = 不改 */
    public record SaveAddressReq(String addressId, @NotBlank String name, @NotBlank String phone,
                                 String region,
                                 String province, String city, String district,
                                 @NotBlank String detail, Boolean isDefault, String tag,
                                 Integer latE6, Integer lngE6) {
    }

    public record LoginReq(@NotBlank String grantType, @NotBlank String principal, String credential,
                           String merchantNo, String inviterNo, Boolean agreed) {
    }

    public record OtpReq(@NotBlank String phone) {
    }

    public record BindCommunityReq(@NotBlank String communityNo, @NotBlank String pickupNo) {
    }
}
