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
