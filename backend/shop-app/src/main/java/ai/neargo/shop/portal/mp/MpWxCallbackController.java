package ai.neargo.shop.portal.mp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 微信「消息推送」的接收端点（小程序后台 → 开发管理 → 消息推送）。
 *
 * <h2>它为什么必须存在</h2>
 * 微信侧的一批能力是**回调驱动**的，不接这个口就收不到：
 * 支付结果、发货后的「确认收货/结算」推送（见 TDD-微信发货信息录入）、
 * 客服消息、审核结果。现在先把口子开出来并通过后台校验，
 * 具体事件的处理随各自的功能再接。
 *
 * <h2>三个容易踩空的地方</h2>
 *
 * <p><b>一、返回值必须是 echostr 原文，不能套信封。</b>
 * 全局的 {@code ApiResponseWrapper} 会把返回值裹成 {@code {code,msg,data}} ——
 * 微信拿到那个就判校验失败，而它只会说「token 验证失败」，不会告诉你
 * 是内容不对。这里靠 {@code supports()} 对 {@code String} 返回类型跳过包装
 * 天然避开；**改动这个方法的返回类型前先想一遍这一条**。
 *
 * <p><b>二、挂在 {@code /mp/} 下不是随意选的。</b>
 * 生产的 nginx 只把 {@code /(mp|biz|ops|actuator|uploads|media)} 转给后端。
 * 换个前缀就得同时改 nginx，而漏改的症状是微信报「配置失败，请稍后再试」——
 * 看着像微信侧的问题。
 *
 * <p><b>三、Token 走环境变量。</b> 它和 EncodingAESKey 都是凭证，不进代码库。
 * 没配时**拒绝校验**而不是放行：放行等于任何人都能冒充微信给我们推事件。
 */
@RestController
@RequestMapping("/mp/wx")
@Profile("api")
public class MpWxCallbackController {

    private static final Logger log = LoggerFactory.getLogger(MpWxCallbackController.class);

    private final String token;

    public MpWxCallbackController(@Value("${shop.wx.push.token:}") String token) {
        this.token = token == null ? "" : token.trim();
    }

    /**
     * 后台点「提交」时微信发的校验请求：签名对上就把 {@code echostr} 原样回去。
     *
     * <p>签名算法是微信定的：token / timestamp / nonce 三个值**按字典序排**，
     * 拼接后取 SHA-1。排序这一步最容易漏 —— 漏了在本地自测时
     * （三个值恰好已经有序）也可能碰巧通过。
     */
    @GetMapping("/callback")
    public String verify(@RequestParam String signature,
                         @RequestParam String timestamp,
                         @RequestParam String nonce,
                         @RequestParam String echostr) {
        if (token.isEmpty()) {
            log.error("[wxpush] 未配置 shop.wx.push.token —— 拒绝校验（放行等于谁都能冒充微信推事件）");
            return "";
        }
        boolean ok = signatureOf(timestamp, nonce).equalsIgnoreCase(signature);
        log.info("[wxpush] 校验请求 ts={} nonce={} 结果={}", timestamp, nonce, ok ? "通过" : "签名不符");
        return ok ? echostr : "";
    }

    /**
     * 事件推送。**先只落日志并回 {@code success}**。
     *
     * <p>回 "success" 是微信要求的确认；不回或回错，微信会重推三次然后
     * 在后台标记失败。事件本身怎么处理，随各自功能接入时再补 ——
     * 但这个口现在就要能收，否则后台那一步配置过不去。
     */
    @PostMapping("/callback")
    public String receive(@RequestBody(required = false) String body) {
        log.info("[wxpush] 收到事件推送：{}", body == null ? "(空)" : body.substring(0, Math.min(500, body.length())));
        return "success";
    }

    private String signatureOf(String timestamp, String nonce) {
        String[] arr = {token, timestamp, nonce};
        Arrays.sort(arr);
        try {
            byte[] d = MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", arr).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }
}
