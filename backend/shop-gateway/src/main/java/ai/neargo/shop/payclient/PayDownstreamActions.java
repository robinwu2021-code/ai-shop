package ai.neargo.shop.payclient;

import static ai.neargo.shop.pay.access.PayAction.CLOSE;
import static ai.neargo.shop.pay.access.PayAction.CREATE_ONLINE;
import static ai.neargo.shop.pay.access.PayAction.QUERY;
import static ai.neargo.shop.pay.access.PayAction.READ_LEDGER;
import static ai.neargo.shop.pay.access.PayAction.REQUEST_REFUND;
import static ai.neargo.shop.pay.access.PayAction.REVIEW_REFUND;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.proxy.DownstreamActions;
import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.svc.ServiceName;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 功能点 → 支付动作（TDD-网关层与统一权限 §2.7、AC6、AC17；开发计划 §2.6 任务 14）。
 *
 * <p><b>这是唯一定义处。</b>网关调支付时，把这次判过、并且这个人持有的功能点在这里换成支付的动作，签进上下文；
 * 支付据此核对这次调用的动作是不是网关批过的（支付侧的核对在 near-pay 仓库做，目前只比对、不拒绝）。
 *
 * <p>每一格都来自真实的调用链：入口上的权限码 → ai-shop 的支付客户端 → 支付内部入口 → 支付在那里判的动作
 * （{@code CallerPolicy.check}）。逐条依据见 {@code docs/technical/TDD-网关层与统一权限-开发计划.md} §2.6b 的对照表。
 * 契约测试 {@code PayDownstreamActionsTest} 钉住两件事：每个码都是登记过的功能点；每个动作在这一端的能力矩阵里允许。
 *
 * <p>改这张表的时机：新加了调支付的入口，或支付改了某个内部入口判的动作。没有写进来的码，签出去的动作为空。
 * 用户 2026-11-19 定：表放在代码里，不放库。
 */
@Component
public class PayDownstreamActions implements DownstreamActions {

    /** B 端：商户操作员在支付侧是 {@code BIZ} 调用方。 */
    private static final Map<String, Set<PayAction>> MERCHANT = Map.of(
            // 建收款单（CREATE_ONLINE）；重发付款链接（QUERY）；作废付款链接（QUERY + CLOSE + READ_LEDGER）
            BizPerms.PAY_COLLECT, EnumSet.of(CREATE_ONLINE, QUERY, CLOSE, READ_LEDGER),
            // 收款码列表（QUERY）、新建收款码（CREATE_ONLINE）；停用收款码支付侧不判动作
            BizPerms.PAY_QR, EnumSet.of(QUERY, CREATE_ONLINE),
            // 发起退款（REQUEST_REFUND，支付另要求有操作员）；退款记录（QUERY）
            BizPerms.PAY_REFUND, EnumSet.of(REQUEST_REFUND, QUERY),
            // 对账单、收款汇总（READ_LEDGER）；退款记录与 PAY_REFUND 任一即可（QUERY）
            BizPerms.PAY_STATEMENT, EnumSet.of(READ_LEDGER, QUERY),
            // 收款单列表、查找、详情、收款状态、付款链接、业务单收款（READ_LEDGER）；向渠道查单补账（QUERY）
            BizPerms.PAY_ORDER_VIEW, EnumSet.of(READ_LEDGER, QUERY),
            // 第三方应用授权的列表与新建（QUERY）；撤销支付侧不判动作
            BizPerms.PAY_APP_GRANT, EnumSet.of(QUERY));
    // 不写进来的：PAY_STORE_METHOD（门店付款方式）—— near-pay 的 collection-methods、method-preferences 四个内部入口
    // 只核对「店归这个操作员」（PayBizFacade.ownerOf），不经 CallerPolicy，没有动作可批（2026-11-19 核对 near-pay develop/s1）

    /** 运营端：支付侧是 {@code OPS} 调用方。其余运营端支付入口在支付侧都不判动作。 */
    private static final Map<String, Set<PayAction>> OPERATOR = Map.of(
            Perms.PAY_REFUND_REVIEW, EnumSet.of(REVIEW_REFUND));

    @Override
    public String service() {
        return ServiceName.PAY;
    }

    @Override
    public List<String> actionsOf(Realm realm, Collection<String> points) {
        Map<String, Set<PayAction>> table = table(realm);
        return points.stream().flatMap(code -> table.getOrDefault(code, Set.of()).stream())
                .map(Enum::name).distinct().sorted().toList();
    }

    /** 一端的整张表；别的端（C 端没有权限码）为空。 */
    public static Map<String, Set<PayAction>> table(Realm realm) {
        return switch (realm) {
            case MERCHANT -> MERCHANT;
            case OPERATOR -> OPERATOR;
            default -> Map.of();
        };
    }
}
