package ai.neargo.shop.user.api.mp;

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
    private final UserService userService;
    private final AddressService addressService;

    public MpUserController(AuthService authService, UserService userService,
                            AddressService addressService) {
        this.authService = authService;
        this.userService = userService;
        this.addressService = addressService;
    }

    /** 登录建户。游客端点。 */
    @PostMapping("/login")
    public AuthService.LoginResult login(@RequestBody LoginReq req) {
        return authService.login(new AuthService.LoginCommand(
                req.grantType(), req.principal(), req.credential(),
                req.merchantNo(), req.inviterNo(), req.agreed()));
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

    @PostMapping("/logout")
    public void logout(@RequestHeader("Authorization") String authorization) {
        authService.logout(bearer(authorization));
    }

    @PostMapping("/phone/bind")
    public UserVO bindPhone(@RequestBody BindPhoneReq req) {
        return userService.bindPhone(req.phone(), req.code());
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
                req.addressId(), req.name(), req.phone(), req.province(), req.city(),
                req.district(), req.detail(), req.isDefault(), req.tag()));
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

    public record SaveAddressReq(String addressId, @NotBlank String name, @NotBlank String phone,
                                 String province, String city, String district,
                                 @NotBlank String detail, Boolean isDefault, String tag) {
    }

    public record LoginReq(@NotBlank String grantType, @NotBlank String principal, String credential,
                           String merchantNo, String inviterNo, Boolean agreed) {
    }

    public record OtpReq(@NotBlank String phone) {
    }

    public record BindCommunityReq(@NotBlank String communityNo, @NotBlank String pickupNo) {
    }
}
