package ai.neargo.elec.api;

import java.util.List;

/**
 * 元器件（独立项目 ai-hxkey，生产服务 hxkey）向 ai-shop 借的东西：认运营令牌，以及发短信、code2Session、
 * 取号、按 openid 发订阅四件代办。
 *
 * <p><b>hxkey 一行 ai-shop 的表都不读</b>，全部经这几个 {@code /internal/elec/**} 走 HTTP。
 * 按 ai-shop 用户号做事的三条（取手机号、两条通知）随独立账号上线于 2026-09-30 删掉。
 *
 * <p>路径不经 nginx（它不反代 /internal），elec-svc 走 127.0.0.1；鉴权是共享密钥
 * （请求头 {@link #TOKEN_HEADER}，两边都读 {@code shop.services.internal-token}）。
 */
public final class ElecInternal {

    /** 认令牌：C 端令牌（ctk_）或运营令牌（otk_）→ 是谁 */
    public static final String SESSION = "/internal/elec/session";

    // ---- 元器件独立账号之后借的四件事（ai-hxkey TDD-元器件-独立账号 §2.4）----
    // 虹选的 appsecret、短信通道、access_token 都不复制到 ai-hxkey：这四条只做「代办」，不按 usr_no 做任何事。

    /** 发登录验证码短信。码由 ai-hxkey 生成与校验，这里只负责投递 */
    public static final String SMS_OTP = "/internal/elec/sms/otp";

    /** 小程序 wx.login 的 code → (appid, openid)。过渡期借虹选的 appid */
    public static final String WX_SESSION = "/internal/elec/wx/session";

    /** 小程序 getPhoneNumber 的 code → 手机号 */
    public static final String WX_PHONE = "/internal/elec/wx/phone";

    /** 按 openid 发一条元器件订阅消息（模板 ELEC_QUOTED）。<b>不查额度</b>：额度记在 ai-hxkey */
    public static final String WX_SEND = "/internal/elec/wx/send";

    /** 与 shop-base 的 InternalHttp.TOKEN_HEADER 同值（这里不能引 shop-base，只能写字面量） */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    /** 运营端的权限码。主系统按它自己的规则判，把结果放进 {@link Session#perms} */
    public static final String PERM_RFQ_READ = "elec:rfq:read";
    public static final String PERM_RFQ_QUOTE = "elec:rfq:quote";
    public static final String PERM_SUPPLIER_READ = "elec:supplier:read";
    /** 暂停 / 恢复 / 改资料。暂停后他的货不再给买家看 */
    public static final String PERM_SUPPLIER_MANAGE = "elec:supplier:manage";
    /** 料号与库存查询：看得到每家的精确库存与电话 */
    public static final String PERM_PART_READ = "elec:part:read";
    /** 厂牌与别名维护。加一条别名会改认既有库存 */
    public static final String PERM_BASE_MANAGE = "elec:base:manage";

    /**
     * 元器件用得到的全部运营码。主系统<b>只把这张表里的码</b>判给元器件 —— 不把整张权限表交出去；
     * 新增一个码只改这里，两边自动对齐（此前两个码写死在主系统的过滤里，加码时两处都要记得改）。
     */
    public static final java.util.List<String> OPS_PERMS = java.util.List.of(
            PERM_RFQ_READ, PERM_RFQ_QUOTE, PERM_SUPPLIER_READ, PERM_SUPPLIER_MANAGE, PERM_PART_READ,
            PERM_BASE_MANAGE);

    private ElecInternal() {
    }

    public record SessionReq(String token) {
    }

    /**
     * @param valid    令牌有效。无效时其余字段都为空
     * @param realm    CONSUMER / OPERATOR（商家端令牌一律 valid=false：元器件不接它）
     * @param userNo   C 端是 usr_no，运营端是 staff_no
     * @param perms    只在运营端有值，且<b>只含 {@link #OPS_PERMS} 里的码</b>：主系统判完给结果，不把整张权限表交出去
     * @param expired  令牌带了但会话过期了（端上据此清 token 重新登录，而不是当成「没登录」）
     */
    public record Session(boolean valid, String realm, String userNo, String nickname, List<String> perms,
                          boolean expired) {

        public static Session invalid(boolean expired) {
            return new Session(false, null, null, null, List.of(), expired);
        }
    }

    public record SmsOtpReq(String phone, String code) {
    }

    /** @param retryable 通道说「重试可能成功」（限流、网络）；false = 这个号发不了 */
    public record SmsOtpResult(boolean sent, boolean retryable) {
    }

    public record WxCodeReq(String code) {
    }

    /** @param ok false = code 无效、过期或微信不可达（其余字段为空）；unionId 没绑开放平台时为空 */
    public record WxSession(boolean ok, String appId, String openId, String unionId) {
    }

    /** @param ok false = 取号失败（小程序没认证、code 过期） */
    public record WxPhone(boolean ok, String phone) {
    }

    /**
     * @param resultText 「已报价 / 有新报价 / 暂无货源 / 有新求购 / 已选中」（≤5 字，微信 phrase 字段）
     * @param summary    ≤20 字（thing 字段）
     */
    public record WxSendReq(String openId, String rfqNo, String summary, String resultText, String page) {
    }

    /** @param sent 发出去了。没配模板、微信拒了都是 false，不是错误 */
    public record WxSendResult(boolean sent) {
    }

    /** 询价结果：平台报了价。hxkey 据此选订阅消息里「结果」那一格的人话 */
    public static final String RESULT_QUOTED = "QUOTED";
    /** 平台关单：暂无货源 */
    public static final String RESULT_NO_SOURCE = "NO_SOURCE";
    /** 有供应商报了价（首次报价才发，改价不发） */
    public static final String RESULT_OFFER = "OFFER";
    /** 某一行：收到求购的供应商都回了「没货」，平台也没报 */
    public static final String RESULT_LINE_NO_OFFER = "LINE_NO_OFFER";

}
