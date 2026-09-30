package ai.neargo.shop.portal.internal;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.auth.LiveIdentityResolver;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.spi.notify.SmsPort;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import ai.neargo.shop.spi.user.WxAuthPort;
import ai.neargo.shop.spi.user.WxPhonePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 主系统给<b>元器件独立服务 hxkey</b>（独立项目 ai-hxkey）开的内部端点：认运营令牌，以及四件「代办」——
 * 发短信、code2Session、取号、按 openid 发订阅。都是虹选的资质与凭据，不复制到第二个地方。
 *
 * <p>hxkey 是独立进程、独立库、独立账号，<b>一行主系统的表都不读</b>，它借的东西全在这里。
 * 路径与 record 来自零依赖的 elec-api，hxkey 的客户端引同一份 —— 漂了编译不过。
 * 按 usr_no 做事的三条（取手机号、两条通知）2026-09-30 独立账号上线后删掉。
 *
 * <p>四条硬要求（照 {@link JobHandlerEndpoint}）：
 * <ol>
 *   <li><b>共享密钥</b> {@code shop.services.internal-token}，常量时间比较；<b>没配就一律拒绝</b></li>
 *   <li><b>不经 nginx</b>：nginx 不反代 /internal/**，elec-svc 走 127.0.0.1</li>
 *   <li><b>不被全局信封包</b>：ApiResponseWrapper 按路径跳过 /internal/**，客户端直接解 record</li>
 *   <li><b>只给最少的事实</b>：运营令牌只回元器件那两个权限码判完的结果，不把整张权限表交出去</li>
 * </ol>
 */
@RestController
public class InternalElecEndpoint {

    private static final Logger log = LoggerFactory.getLogger(InternalElecEndpoint.class);

    private final TokenStore tokenStore;
    private final ObjectProvider<LiveIdentityResolver> liveIdentity;
    private final ObjectProvider<LivePermResolver> livePerms;
    private final SmsPort sms;
    private final WxAuthPort wxAuth;
    private final WxPhonePort wxPhone;
    private final WxSubscribePort wxPort;
    private final String appId;
    private final String token;

    public InternalElecEndpoint(TokenStore tokenStore,
                                ObjectProvider<LiveIdentityResolver> liveIdentity,
                                ObjectProvider<LivePermResolver> livePerms,
                                SmsPort sms, WxAuthPort wxAuth, WxPhonePort wxPhone, WxSubscribePort wxPort,
                                @Value("${shop.wx.appid:}") String appId,
                                @Value("${shop.services.internal-token:}") String token) {
        this.tokenStore = tokenStore;
        this.liveIdentity = liveIdentity;
        this.livePerms = livePerms;
        this.sms = sms;
        this.wxAuth = wxAuth;
        this.wxPhone = wxPhone;
        this.wxPort = wxPort;
        this.appId = appId;
        this.token = token;
    }

    /**
     * 令牌 → 是谁。C 端令牌与运营令牌认；<b>商家端令牌一律无效</b>（元器件不接 B 端 App 的会话 ——
     * 元器件的「B 端」是供应商，与小程序 C 端同一个账号）。
     *
     * <p>带了令牌却查不到会话 = 过期或被吊销，回 {@code expired=true}：
     * 与主系统自己的过滤器同一个口径，端上据此提示「登录已过期」而不是「请登录」。
     */
    @PostMapping(ElecInternal.SESSION)
    public ResponseEntity<ElecInternal.Session> session(
            @RequestHeader(value = ElecInternal.TOKEN_HEADER, required = false) String given,
            @RequestBody ElecInternal.SessionReq req) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (req == null || req.token() == null || req.token().isBlank()) {
            return ResponseEntity.ok(ElecInternal.Session.invalid(false));
        }
        TokenStore.SessionData d = tokenStore.get(req.token()).orElse(null);
        if (d == null) {
            return ResponseEntity.ok(ElecInternal.Session.invalid(true));
        }
        LoginUser u = d.user();
        if (u.realm() == Realm.CONSUMER) {
            return ResponseEntity.ok(new ElecInternal.Session(true, "CONSUMER", u.userNo(), u.nickname(),
                    List.of(), false));
        }
        if (u.realm() == Realm.OPERATOR) {
            return ResponseEntity.ok(new ElecInternal.Session(true, "OPERATOR", u.userNo(), u.nickname(),
                    elecPerms(u), false));
        }
        return ResponseEntity.ok(ElecInternal.Session.invalid(false));
    }

    /**
     * 发元器件的登录验证码。码是 ai-hxkey 生成的，这里只投递 —— 所以<b>不走 AuthService.sendOtp</b>
     * （那条会自己生成码、写 ai-shop 的 OtpStore）；限流在 ai-hxkey 那一侧（同一个 OtpSendGuard）。
     * 用途记成 ELEC_LOGIN：发送记录里与虹选自己的登录码分得开。
     */
    @PostMapping(ElecInternal.SMS_OTP)
    public ResponseEntity<ElecInternal.SmsOtpResult> smsOtp(
            @RequestHeader(value = ElecInternal.TOKEN_HEADER, required = false) String given,
            @RequestBody ElecInternal.SmsOtpReq req) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (req == null || req.phone() == null || req.code() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            sms.sendOtp(req.phone(), req.code(), "ELEC_LOGIN", null);
            return ResponseEntity.ok(new ElecInternal.SmsOtpResult(true, false));
        } catch (SmsPort.SmsException e) {
            log.warn("[elec] 验证码短信没发出去 retryable={} {}", e.retryable(), e.getMessage());
            return ResponseEntity.ok(new ElecInternal.SmsOtpResult(false, e.retryable()));
        }
    }

    /** wx.login 的 code → (appid, openid)。code 无效或微信不可达回 ok=false，不抛：那是「这次没认出来」，不是故障 */
    @PostMapping(ElecInternal.WX_SESSION)
    public ResponseEntity<ElecInternal.WxSession> wxSession(
            @RequestHeader(value = ElecInternal.TOKEN_HEADER, required = false) String given,
            @RequestBody ElecInternal.WxCodeReq req) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (req == null || req.code() == null || req.code().isBlank()) {
            return ResponseEntity.ok(new ElecInternal.WxSession(false, null, null, null));
        }
        try {
            WxAuthPort.WxSession s = wxAuth.codeToSession(req.code());
            return ResponseEntity.ok(new ElecInternal.WxSession(true, appId, s.openId(), s.unionId()));
        } catch (WxAuthPort.WxAuthException e) {
            log.info("[elec] code2Session 没成：{}", e.getMessage());
            return ResponseEntity.ok(new ElecInternal.WxSession(false, null, null, null));
        }
    }

    /** getPhoneNumber 的 code → 手机号。没开通（小程序未认证）或取号失败回 ok=false */
    @PostMapping(ElecInternal.WX_PHONE)
    public ResponseEntity<ElecInternal.WxPhone> wxPhone(
            @RequestHeader(value = ElecInternal.TOKEN_HEADER, required = false) String given,
            @RequestBody ElecInternal.WxCodeReq req) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (req == null || req.code() == null || req.code().isBlank() || !wxPhone.enabled()) {
            return ResponseEntity.ok(new ElecInternal.WxPhone(false, null));
        }
        try {
            String phone = wxPhone.phoneOf(req.code());
            return ResponseEntity.ok(new ElecInternal.WxPhone(phone != null && !phone.isBlank(), phone));
        } catch (RuntimeException e) {
            log.info("[elec] 一键取号没成：{}", e.getMessage());
            return ResponseEntity.ok(new ElecInternal.WxPhone(false, null));
        }
    }

    /**
     * 按 openid 发一条元器件订阅消息。<b>不查、不扣额度</b>：元器件账号的授权额度记在 ai-hxkey 自己的库里，
     * 它扣到了才来调；这里再扣一次就是查 ai-shop 的 msg_subscribe —— 那张表里没有元器件账号。
     */
    @PostMapping(ElecInternal.WX_SEND)
    public ResponseEntity<ElecInternal.WxSendResult> wxSend(
            @RequestHeader(value = ElecInternal.TOKEN_HEADER, required = false) String given,
            @RequestBody ElecInternal.WxSendReq req) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String tpl = wxPort.templateId(WxSubscribePort.SCENE_ELEC_QUOTED);
        if (req == null || req.openId() == null || req.openId().isBlank() || tpl == null || tpl.isBlank()) {
            return ResponseEntity.ok(new ElecInternal.WxSendResult(false));
        }
        try {
            wxPort.sendElecQuoted(req.openId(), req.rfqNo(), req.summary(), req.resultText(), req.page(), null);
            return ResponseEntity.ok(new ElecInternal.WxSendResult(true));
        } catch (RuntimeException e) {
            log.warn("[elec] 订阅消息没发出去：{}", e.getMessage());
            return ResponseEntity.ok(new ElecInternal.WxSendResult(false));
        }
    }

    /**
     * 运营在元器件那几个码（{@link ElecInternal#OPS_PERMS}）上的权限，<b>按主系统自己的规则现算</b>（与 {@code PermChecker.can} 同一条路）：
     * 角色现查、权限码按角色现算、认模块通配（{@code elec:*}）。改了角色配置，下一次缓存过期就生效。
     */
    private List<String> elecPerms(LoginUser u) {
        List<String> roles = u.roles();
        LiveIdentityResolver.Identity live = liveIdentity.getIfAvailable(() -> LiveIdentityResolver.NONE)
                .resolve(u.userNo());
        if (live != null && live.roles() != null) {
            roles = live.roles();
        }
        List<String> perms = null;
        if (roles != null && !roles.isEmpty()) {
            perms = livePerms.getIfAvailable(() -> LivePermResolver.NONE).resolve(roles);
        }
        if (perms == null) {
            perms = u.perms();
        }
        if (perms == null || perms.isEmpty()) {
            return List.of();
        }
        List<String> granted = perms;
        return ElecInternal.OPS_PERMS.stream()
                .filter(code -> ai.neargo.common.security.rbac.Permissions.matches(granted, code))
                .toList();
    }

    /** 常量时间比较（不用 equals：它的耗时随「猜对了几个字符」变化） */
    private boolean authorized(String given) {
        if (given == null || token == null || token.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
