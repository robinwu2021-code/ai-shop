package ai.neargo.shop.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.ScopeDim;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数据域表注册 —— **越权防线的第 ③ 道**（TDD-backend §5.2/§5.3）。
 * 声明「哪张表在哪个维度用哪一列过滤」，由 {@code DataScopeHandler} 在 SQL 层自动追加条件，
 * <b>业务代码零 where</b>。
 *
 * <p><b>两条必须记住的性质</b>（powerbank 用事故换来的）：
 * <ol>
 *   <li><b>未注册的表 = 不过滤</b>。漏注册不报错、不告警，只会静默放行越权数据。
 *       所以「注册」是建表的一部分，不是可选项。</li>
 *   <li><b>已注册的表是 fail-closed</b>：当前会话的维度在该表锚点里找不到列时，
 *       handler 生成的是 {@code 1=0} 而不是放行。因此一张表被注册后，
 *       <b>所有可能访问它的主体维度都要登记</b> —— 漏一个，那类主体就全瞎。
 *       典型翻车：订单表登记了 MERCHANT 却漏了 SELF，C 端「我的订单」立刻空列表。</li>
 * </ol>
 *
 * <p>S0 只登记两张基础设施表之外的骨架；各域建表时在此补登记，
 * 由 {@code DataScopeCoverageTest} 校验「带归属列的表都已注册」。
 */
@Component
public class DataScopeRegistration implements DataScopeRegistrar {

    @Override
    public void register(DataScopeTableRegistry registry) {

        // —— 交易：子订单是商家视角的账本，也是 C 端「我的订单」的来源 ——
        // SELF 必须登记，理由见类注释第 2 条。
        registry.register("ord_sub_order", Map.of(
                ScopeDim.SELF, "user_no",
                ScopeDim.MERCHANT, "merchant_no",
                ScopeDim.PICKUP, "pickup_no"));

        registry.register("ord_order", Map.of(
                ScopeDim.SELF, "user_no"));

        // —— 商品：商家只能改自己的货 ——
        registry.register("prd_goods", Map.of(
                ScopeDim.MERCHANT, "merchant_no"));

        // —— 履约：自提点承接的任务含别家商品，行级过滤之外还要字段级裁剪（第 ④ 道防线）——
        registry.register("ful_pickup_task", Map.of(
                ScopeDim.SELF, "user_no",
                ScopeDim.PICKUP, "pickup_no",
                ScopeDim.MERCHANT, "merchant_no"));

        // —— 邻里自提：作用域是单个团，且发起人零报酬（ADR-005）——
        registry.register("ful_group_pickup", Map.of(
                ScopeDim.SELF, "user_no",
                ScopeDim.GROUP, "group_no"));

        // —— 结算：钱的可见性最敏感，只有商家自己和平台财务 ——
        registry.register("stl_bill", Map.of(
                ScopeDim.MERCHANT, "merchant_no"));
    }
}
