package ai.neargo.shop.platform.dto;

import java.util.List;

/**
 * 主数据快照 —— <b>三端共用的一份取值域</b>。
 *
 * <p>为什么合成一个响应而不是三条接口：这三样东西在<b>同一屏</b>上被同时用到。
 * 入驻表单要「选行业 → 据此过滤可选主体 → 主体决定要不要传营业执照」，
 * 分三次请求就会出现「行业已回来、主体还没回来」的中间态，
 * 而那个中间态里表单不知道该不该禁用某个选项。
 *
 * <p>它<b>不是配置中心</b>：只下发取值域与展示名，不下发密钥、不下发平台账户。
 *
 * @param subjects  商家主体类型。带 {@code needLicense} / {@code industryGated}，
 *                  端上据此决定表单往下走哪一步 —— 这些判断此前在三端各写了一遍
 * @param channels  支付渠道。只给端上需要知道的：叫什么、开没开、支持哪些支付方式
 * @param serviceScopes <b>这一期开放的经营范围档位</b>（ADR-009 三档的启用子集）。
 *                      端上照它渲染选项 —— 这一项不下发的话，端只能把三档写死，
 *                      于是商家能选到一个<b>必被后端拒</b>的档位：一期自营模式关掉了
 *                      PLATFORM，而 B 端照样把「全平台发货」摆在那里，
 *                      点下去得到的是「当前不支持这个经营范围」。
 *                      实测撞到过（2026-08-11 E2E）。
 */
public record MasterDataVO(List<Industry> industries,
                           List<Subject> subjects,
                           List<Channel> channels,
                           List<String> serviceScopes) {

    /**
     * @param microAllowed 该行业能否以小微主体进件。<b>端上据此禁用「小微」这个选项</b>，
     *                     而不是等提交后被后端拒 —— 让人填完再拒是最差的一种告知方式
     */
    public record Industry(String industry, String name, boolean microAllowed) {
    }

    /**
     * @param needLicense   要不要营业执照。决定表单下一步显示什么
     * @param industryGated 是否受行业白名单限制（仅小微）。端上据此知道
     *                      「这个主体在某些行业下会不可选」，而不是凭空猜
     */
    public record Subject(String subjectType, String name, boolean needLicense,
                          boolean industryGated, String settleAccountType) {
    }

    public record Channel(String payChannel, String name, boolean enabled, List<String> payMethods) {
    }
}
