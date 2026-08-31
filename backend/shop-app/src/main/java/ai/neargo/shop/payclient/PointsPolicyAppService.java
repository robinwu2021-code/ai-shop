package ai.neargo.shop.payclient;

import ai.neargo.shop.settle.PointsService.ClientPointsPolicy;

/**
 * 端积分策略 —— <b>主应用侧 app service 层的第一个样本</b>。
 *
 * <h2>这一层是干什么的</h2>
 * 支付域拆分之后，controller <b>只做 HTTP 的事</b>（路径、鉴权注解、参数绑定），
 * 业务动作在这一层：解析数据域、校验入参、调支付域、拼 VO、留痕。
 *
 * <p>它是被 ArchUnit 逼出来的，而那次报错很有意思：
 * 12 个 controller 从支付域搬到 {@code portal..} 之后，既有的
 * {@code controllersMustNotTouchMappers} 当场报了 4 处 ——
 * 那些 controller 在 web 层直接做 JSON 解析与校验。
 * <b>它们在支付域里时不在 {@code portal..} 包下，所以这条规则一直管不到。</b>
 * 搬家没有制造这个问题，只是让它第一次被看见。
 *
 * <p>（那 4 处命中的是 Jackson 的 {@code ObjectMapper}，
 * 按规则的字面意思算误报 —— 但按它的意图不算：<b>业务确实写在 web 层了</b>。
 * 所以这里选择把业务搬出来，而不是放宽规则。）
 *
 * <h2>它不碰支付域</h2>
 * 端策略是一条平台设置（{@code sys_setting}），不是资金数据 ——
 * 所以这一个 app service 只调 {@code SettingPort} 与 {@code AuditLogPort}，
 * 一次也不进支付域。<b>「app service 层」不等于「都要调 pay」</b>，
 * 它是 controller 与领域之间的那一层，领域是谁看这一条业务。
 */
public interface PointsPolicyAppService {

    /** 读端策略。没配过时返回默认值（全放开），而不是 null */
    ClientPointsPolicy policy();

    /**
     * 保存端策略。
     *
     * <p><b>取值域当场校验</b>：写进去一个拼错的端名，它不会报错，
     * 只会安安静静地谁也拦不住 —— 而运营会以为已经关掉了。
     * 这类「设置成功但不生效」的故障，事后极难从现象追回到那一行。
     *
     * @param operatorNo 留痕用。这个开关决定用户在某个端上能不能拿到 / 用掉积分，
     *                   而积分是平台对用户的负债 —— 没有留痕，
     *                   「用户说昨天还能抵、今天不能了」这类工单只能靠猜
     */
    ClientPointsPolicy save(ClientPointsPolicy req, String operatorNo);
}
