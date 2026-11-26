package ai.neargo.shop.merchant.violation;

import java.util.List;

/**
 * 违规与处置记录的入口。
 *
 * <p>新的库表设计把违规并入平台服务库的风险事件，和商户不在一个库。商户治理仍然决定
 * 「这次违规怎么处置商户」，但记录本身从这里进出；等风控模块对齐新库时，这个接口的实现换过去，
 * 商户域的调用不用改。
 */
public interface ViolationLedger {

    /** 记一条违规。调用方负责数据域：运营端代商户记录时要先绕过商户域过滤。 */
    void record(MchViolation violation);

    /** 按主体查，最新的在前；{@code entityNo} 为空查全部。数据域过滤照常生效。 */
    List<MchViolation> list(String entityNo);
}
