package ai.neargo.shop.portal.internal;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.auth.LiveIdentityResolver;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.notify.WxSubscribeSender;
import ai.neargo.shop.spi.user.UserIdentityPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 主系统给<b>元器件独立服务</b>（elec-svc）开的内部端点：认令牌、取手机号、通知买家。
 *
 * <p>元器件是独立进程、独立库，<b>一行主系统的表都不读</b>，它借的东西全在这里。
 * 路径与 record 来自零依赖的 elec-api，elec-svc 的客户端引同一份 —— 漂了编译不过。
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

    private final TokenStore tokenStore;
    private final UserIdentityPort identity;
    private final ObjectProvider<LiveIdentityResolver> liveIdentity;
    private final ObjectProvider<LivePermResolver> livePerms;
    private final MessageService messages;
    private final WxSubscribeSender wxSubscribe;
    private final String token;

    public InternalElecEndpoint(TokenStore tokenStore, UserIdentityPort identity,
                                ObjectProvider<LiveIdentityResolver> liveIdentity,
                                ObjectProvider<LivePermResolver> livePerms,
                                MessageService messages, WxSubscribeSender wxSubscribe,
                                @Value("${shop.services.internal-token:}") String token) {
        this.tokenStore = tokenStore;
        this.identity = identity;
        this.liveIdentity = liveIdentity;
        this.livePerms = livePerms;
        this.messages = messages;
        this.wxSubscribe = wxSubscribe;
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

    /** 验证过的手机号（没绑为 null）。元器件询价与成为供应商都要它 */
    @GetMapping(ElecInternal.USER)
    public ResponseEntity<ElecInternal.User> user(
            @RequestHeader(value = ElecInternal.TOKEN_HEADER, required = false) String given,
            @PathVariable String userNo) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new ElecInternal.User(userNo, identity.phone(userNo).orElse(null)));
    }

    /**
     * 询价有结果了，告诉买家：<b>站内信（必达的记录）+ 微信订阅消息（加速通道）</b>，两条各自独立，
     * 一条失败不影响另一条。订阅消息没额度、没配模板都是「没发」，不是错误。
     *
     * <p>站内信的 dedupKey 带分钟：同一张单一分钟内的重复调用只留一条（元器件那边超时重发时），
     * 而平台隔了几分钟改价再报，买家会收到第二条 —— 那是一个新的结果。
     */
    @PostMapping(ElecInternal.NOTIFY_QUOTED)
    public ResponseEntity<ElecInternal.NoticeResult> notifyQuoted(
            @RequestHeader(value = ElecInternal.TOKEN_HEADER, required = false) String given,
            @RequestBody ElecInternal.QuotedNotice n) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean noSource = "NO_SOURCE".equals(n.result());
        String resultText = noSource ? "暂无货源" : "已报价";
        String title = noSource ? "询价暂无货源" : "询价有报价了";
        String body = n.summary() + (noSource ? "：平台暂时没找到货，可以换个料号或稍后再询" : "：平台已报价，报价有有效期，请尽快查看");
        boolean inApp;
        try {
            messages.pushTo(MsgMessage.RECEIVER_USER, n.userNo(), MessageService.TRADE, title, body,
                    "/" + n.page(), "ELEC_RFQ:" + n.rfqNo() + ":" + n.result() + ":"
                            + (System.currentTimeMillis() / 60_000));
            inApp = true;
        } catch (RuntimeException e) {
            inApp = false;
        }
        boolean wx = wxSubscribe.elecQuoted(n.userNo(), n.rfqNo(), n.summary(), resultText, n.page());
        return ResponseEntity.ok(new ElecInternal.NoticeResult(inApp, wx));
    }

    /**
     * 通知供应商（有新求购 / 报价被选中）。供应商与买家是同一个账号体系，所以与
     * {@link #notifyQuoted} 走同一条路：站内信必达，订阅消息是加速通道。
     *
     * <p>订阅消息用的是同一个模板（场景 ELEC_QUOTED）：报价结果与求购通知在模板上是同一类
     * 「服务进度」，没必要为它再报备一个 —— 而多一个模板就多一次授权，供应商多半不会点第二次。
     */
    @PostMapping(ElecInternal.NOTIFY_SUPPLIER)
    public ResponseEntity<ElecInternal.NoticeResult> notifySupplier(
            @RequestHeader(value = ElecInternal.TOKEN_HEADER, required = false) String given,
            @RequestBody ElecInternal.SupplierNotice n) {
        if (!authorized(given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean inApp;
        try {
            messages.pushTo(MsgMessage.RECEIVER_USER, n.userNo(), MessageService.TRADE, n.title(), n.body(),
                    "/" + n.page(), n.dedupKey());
            inApp = true;
        } catch (RuntimeException e) {
            inApp = false;
        }
        boolean wx = wxSubscribe.elecQuoted(n.userNo(), "-", n.title(),
                "DISPATCH".equals(n.kind()) ? "有新求购" : "已选中", n.page());
        return ResponseEntity.ok(new ElecInternal.NoticeResult(inApp, wx));
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
