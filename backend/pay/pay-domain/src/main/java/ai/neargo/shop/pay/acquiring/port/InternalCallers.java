package ai.neargo.shop.pay.acquiring.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 内部调用方的 {@link PayCaller} 工厂 —— 与开放 API、终端、收银台的鉴权组件并列的合法构造点之一（代码清单 §5.2）。
 *
 * <p>两种身份，区别是能不能退款：
 * <ul>
 *   <li>{@link #system}：商城 / 餐饮<b>系统本身</b>（{@code INTERNAL}）—— 建单、查单、关单；<b>不能退款</b>；</li>
 *   <li>{@link #merchantOperator}：商家操作员在业务系统里点了退款（{@code BIZ}，callerId 仍是内部 app）—— 必须带操作员。
 *       一个代码 bug 不能以系统身份批量把钱退出去（设计 v4 §2.8.2）。</li>
 * </ul>
 * 内部系统受信但不是全能：门店范围同样圈到这一单的门店。
 */
@Component
public class InternalCallers {

    /** 内部 app 码（{@code pay_app.app_no}，V7 种子） */
    public static final String MALL = "MALL";
    public static final String FOOD = "FOOD";
    /** 收银台接入方（V11 种子）：静态码与商家代客收款建的单记在它名下 */
    public static final String CHECKOUT = "CHECKOUT";

    private final PayAppMapper apps;
    private final Map<String, Long> ids = new ConcurrentHashMap<>();

    public InternalCallers(PayAppMapper apps) {
        this.apps = apps;
    }

    public PayCaller system(String appCode, Long storeId) {
        return new PayCaller(CallerType.INTERNAL, appId(appCode), Set.of(storeId), null, null, null);
    }

    public PayCaller merchantOperator(String appCode, Long storeId, String operatorNo) {
        if (operatorNo == null || operatorNo.isBlank()) {
            throw BizException.of(ErrorCode.PAY_REFUND_OPERATOR_REQUIRED);
        }
        return new PayCaller(CallerType.BIZ, appId(appCode), Set.of(storeId), operatorNo, null, null);
    }

    /**
     * 支付运营端的调用方（TDD-支付服务-I5接入层 S9）：平台身份、不按门店圈（{@code MatrixCallerPolicy}）；
     * 操作员是运营账号 —— 复核、重推、处置都记在他名下。{@code callerId} 记 0（平台），不对应任何接入方
     */
    public PayCaller opsOperator(String operatorNo) {
        return new PayCaller(CallerType.OPS, 0L, Set.of(), operatorNo, null, null);
    }

    /**
     * 托管收银台与静态码上的顾客（{@code CHECKOUT}）：没有账号，身份是收银台接入方本身，门店圈到这一单的门店。
     * 收银台令牌 / 码由 {@code CheckoutService} 验过才走到这里；接入方是否启用也由它每次现查（停用即刻生效），
     * 所以这里只拼身份、不查库。
     */
    public static PayCaller checkoutVisitor(Long checkoutAppId, Long storeId) {
        return new PayCaller(CallerType.CHECKOUT, checkoutAppId, Set.of(storeId), null, null, null);
    }

    public Long appId(String appCode) {
        return ids.computeIfAbsent(appCode, code -> {
            PayApp app = DataScopeContext.executeWithoutScope(() -> apps.selectOne(Wrappers.<PayApp>lambdaQuery()
                    .eq(PayApp::getAppNo, code).eq(PayApp::getOwnerType, "INTERNAL").eq(PayApp::getStatus, "ACTIVE")));
            if (app == null) {
                throw BizException.of(ErrorCode.PAY_FORBIDDEN);
            }
            return app.getId();
        });
    }
}
