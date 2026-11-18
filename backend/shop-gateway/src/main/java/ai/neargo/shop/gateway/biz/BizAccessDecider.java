package ai.neargo.shop.gateway.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.gateway.core.security.AbstractAccessDecider;
import ai.neargo.shop.gateway.core.security.Decision;

/**
 * B 端：按<b>当前门店</b>上持有的角色判（TDD-网关层与统一权限 §2.14；开发计划 D3）。
 *
 * <p>与方法注解里的 {@code @perm.canBiz} 问的是同一份上下文：{@code BizContextFilter} 排在本判定之前，
 * 已经按 {@code X-Store-No} 把这个人在这家店的角色解析好了。这里只回答「他能不能做这个动作」；
 * 「他能碰哪家店的数据」仍由各 Service 裁，网关不碰。
 *
 * <p>被拒时给的错误也与 {@code PermChecker#canBiz} 相同：不是商家 → 通用的「没有操作权限」；
 * 是商家但角色不够 → 70006，并说出缺的是哪一样（权限式里按书写顺序第一个缺的）。
 */
public class BizAccessDecider extends AbstractAccessDecider {

    @Override
    protected boolean has(String code) {
        return merchant() && BizContext.current().can(code);
    }

    @Override
    public BizException denial(Decision decision) {
        if (!merchant() || decision.missing().isEmpty()) {
            return BizException.of(ErrorCode.FORBIDDEN);
        }
        return BizException.of(ErrorCode.BIZ_ROLE_FORBIDDEN, BizPerms.labelOf(decision.missing().get(0)));
    }

    private static boolean merchant() {
        String merchantNo = BizContext.current().merchantNo();
        return merchantNo != null && !merchantNo.isBlank();
    }
}
