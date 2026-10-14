package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.trade.service.MerchantOrderService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「开始备货」（TDD-售后规则 §2.1、AC12）。
 *
 * <p>备货只记时刻、不改状态 —— 它是售后判进度的分水岭之一（生鲜备货后非质量退款要商家确认），
 * 却不是履约状态的推进。所以这里守三件事：**时刻确实落了库、再点不覆盖（幂等）、状态没被动过**。
 *
 * <p>同时这是 {@code V400} 迁移（给 {@code ord_sub_order} 加 {@code prepared_at}）在 H2 上的
 * 第一处实证 —— 列没建出来的话，下面写回与读出这一列就直接炸。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantOrderPrepareTest {

    @Autowired
    private MerchantOrderService merchantOrderService;
    @Autowired
    private TradeMappers.SubOrderMapper subOrderMapper;

    private static int seq = 0;

    /** 落一张待发货快递子单，返回子单号。 */
    private String aWaitFulfillExpressSub(String entityNo) {
        String subNo = "SUBPR" + System.nanoTime() % 100_000_000L;
        OrdSubOrder sub = new OrdSubOrder();
        sub.setSubOrderNo(subNo);
        sub.setOrderNo("ORDPR" + System.nanoTime() % 100_000_000L);
        sub.setUserNo("UPR" + System.nanoTime() % 100_000_000L);
        sub.setEntityNo(entityNo);
        sub.setStatus(OrdSubOrder.WAIT_FULFILL);
        sub.setFulfillment(Fulfillments.EXPRESS);
        sub.setPayAmount(1_000L);
        DataScopeContext.executeWithoutScope(() -> subOrderMapper.insert(sub));
        return subNo;
    }

    private OrdSubOrder reload(String subNo) {
        return DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectOne(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getSubOrderNo, subNo).last("LIMIT 1")));
    }

    @Test
    @DisplayName("★★★ 点备货 → 落时刻、状态不动；再点不覆盖（幂等）")
    void prepare_marks_prepared_at_idempotent() {
        String entityNo = "EPR" + (++seq);
        String subNo = aWaitFulfillExpressSub(entityNo);

        OrderVO vo1 = merchantOrderService.prepare(entityNo, null, subNo);
        assertThat(vo1.preparedAt())
                .as("备货时刻要下发给 b-app —— 空的话界面分不清「开始备货」还是「已备货」")
                .isNotNull();

        OrdSubOrder after1 = reload(subNo);
        assertThat(after1.getPreparedAt())
                .as("V400 的 prepared_at 没写进库的话，b 端下次打开又是未备货")
                .isNotNull();
        assertThat(after1.getStatus())
                .as("备货只记时刻、不推进状态 —— 动了状态会把「待发货」标签页里的单弄丢")
                .isEqualTo(OrdSubOrder.WAIT_FULFILL);

        Long firstAt = after1.getPreparedAt();
        OrderVO vo2 = merchantOrderService.prepare(entityNo, null, subNo);
        assertThat(vo2.preparedAt())
                .as("幂等：再点不覆盖，留住第一次的真实备货时间")
                .isEqualTo(firstAt);
        assertThat(reload(subNo).getPreparedAt()).isEqualTo(firstAt);
    }

    @Test
    @DisplayName("★★★ 非待发货单点备货 → 明确拒（ORDER_STATE_ILLEGAL），不静默")
    void prepare_rejects_non_wait_fulfill() {
        String entityNo = "EPR" + (++seq);
        String subNo = aWaitFulfillExpressSub(entityNo);
        DataScopeContext.executeWithoutScope(() -> {
            OrdSubOrder sub = reload(subNo);
            sub.setStatus(OrdSubOrder.FULFILLING);
            subOrderMapper.updateById(sub);
            return null;
        });

        assertThatThrownBy(() -> merchantOrderService.prepare(entityNo, null, subNo))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL);

        assertThat(reload(subNo).getPreparedAt())
                .as("拒了就不该留下任何备货痕迹")
                .isNull();
    }
}
