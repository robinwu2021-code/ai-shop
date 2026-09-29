package ai.neargo.elec.api;

import java.util.List;

/**
 * 元器件服务向主系统借的三样东西：认令牌、取手机号、通知买家。
 *
 * <p><b>元器件一行主系统的表都不读</b>，全部经这三个 {@code /internal/elec/**} 走 HTTP。
 * 将来元器件换成自己的账号体系，只换这三个调用的实现。
 *
 * <p>路径不经 nginx（它不反代 /internal），elec-svc 走 127.0.0.1；鉴权是共享密钥
 * （请求头 {@link #TOKEN_HEADER}，两边都读 {@code shop.services.internal-token}）。
 */
public final class ElecInternal {

    /** 认令牌：C 端令牌（ctk_）或运营令牌（otk_）→ 是谁 */
    public static final String SESSION = "/internal/elec/session";

    /** 取一个用户验证过的手机号 */
    public static final String USER = "/internal/elec/user/{userNo}";

    /** 询价有结果了：发微信订阅消息 + 站内信给买家 */
    public static final String NOTIFY_QUOTED = "/internal/elec/notify/quoted";

    /** 与 shop-base 的 InternalHttp.TOKEN_HEADER 同值（这里不能引 shop-base，只能写字面量） */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    /** 运营端两个权限码。主系统按它自己的规则判，把结果放进 {@link Session#perms} */
    public static final String PERM_RFQ_READ = "elec:rfq:read";
    public static final String PERM_RFQ_QUOTE = "elec:rfq:quote";

    private ElecInternal() {
    }

    public record SessionReq(String token) {
    }

    /**
     * @param valid    令牌有效。无效时其余字段都为空
     * @param realm    CONSUMER / OPERATOR（商家端令牌一律 valid=false：元器件不接它）
     * @param userNo   C 端是 usr_no，运营端是 staff_no
     * @param perms    只在运营端有值，且<b>只含元器件的那两个码</b>：主系统判完给结果，不把整张权限表交出去
     * @param expired  令牌带了但会话过期了（端上据此清 token 重新登录，而不是当成「没登录」）
     */
    public record Session(boolean valid, String realm, String userNo, String nickname, List<String> perms,
                          boolean expired) {

        public static Session invalid(boolean expired) {
            return new Session(false, null, null, null, List.of(), expired);
        }
    }

    /** @param phone 验证过的手机号；没绑为 null */
    public record User(String userNo, String phone) {
    }

    /**
     * @param result  QUOTED 已报价 / NO_SOURCE 暂无货源
     * @param summary 料号概述，如「STM32F103C8T6 等 3 项」（≤20 字，微信 thing 字段的上限）
     * @param page    点开后落到的小程序页面
     */
    public record QuotedNotice(String userNo, String rfqNo, String result, String summary, String page) {
    }

    /**
     * @param inApp 站内信写进去了
     * @param wx    订阅消息发出去了（没授权额度、没配模板都是 false，不是错误）
     */
    public record NoticeResult(boolean inApp, boolean wx) {
    }
}
