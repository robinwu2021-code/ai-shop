package ai.neargo.shop.fulfillment.service.impl;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ratelimit.InMemoryRateLimiter;
import ai.neargo.shop.fulfillment.dto.VerifyResultVO;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShortageReportMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.VerifyLogMapper;
import ai.neargo.shop.fulfillment.service.PickupService;
import ai.neargo.shop.spi.trade.FulfillmentQueryPort;
import ai.neargo.shop.spi.trade.FulfillmentQueryPort.PickupOrder;
import ai.neargo.shop.spi.user.PickupQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 核销码不能被当成订单探测器用。
 *
 * <p>核销码是 6 位数字，一个店员账号能在核销台上逐个试：此前「码不存在」与「码存在但不是你的点」
 * 回两种不同的原因、后者还带回子单号，等于告诉他「这个码是活的、单号是这个」；
 * 批量接口不设上限、也没有失败次数闸，一次请求就能扫完一段码空间。
 */
class PickupVerifyEnumerationGuardTest {

    private final FulfillmentQueryPort orderPort = mock(FulfillmentQueryPort.class);
    private final PickupQueryPort pickupPort = mock(PickupQueryPort.class);
    private final VerifyLogMapper logMapper = mock(VerifyLogMapper.class);
    private final ShortageReportMapper shortageMapper = mock(ShortageReportMapper.class);
    private PickupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PickupServiceImpl(orderPort, pickupPort, logMapper, shortageMapper,
                new InMemoryRateLimiter());
        BizContext.set(new BizContext("M1", Set.of("PP_MINE"), Set.of()));
        login("STAFF1");
    }

    @AfterEach
    void tearDown() {
        BizContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static void login(String userNo) {
        var user = new LoginUser(ai.neargo.shop.auth.Realm.MERCHANT, ai.neargo.auth.store.SubjectKind.MCH,
                userNo, userNo, List.of(), List.of(), "MAIN", null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "", List.of()));
    }

    private static PickupOrder order(String subOrderNo, String code, String pickupNo, String status) {
        return new PickupOrder(subOrderNo, code, status, pickupNo, "M9", "别家", "买家", "1234",
                null, List.of());
    }

    @Test
    @DisplayName("★★★ 别的点的码与不存在的码回同一个原因，且不带子单号")
    void otherPickupCodeLooksExactlyLikeMissingCode() {
        when(orderPort.findByVerifyCode("111111"))
                .thenReturn(Optional.of(order("SUB_OTHER", "111111", "PP_OTHER", "FULFILLING")));
        when(orderPort.findByVerifyCode("222222")).thenReturn(Optional.empty());

        VerifyResultVO foreign = service.verify("111111", false);
        VerifyResultVO missing = service.verify("222222", false);

        assertThat(foreign)
                .as("回 NOT_THIS_PICKUP 或带回子单号 = 告诉试码的人「这个码是活的」")
                .isEqualTo(missing);
        assertThat(foreign.reason()).isEqualTo(VerifyResultVO.NOT_FOUND);
        assertThat(foreign.subOrderNo()).isNull();
        verify(orderPort, never()).complete(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★★ 批量核销有上限：超过上限整批拒绝，一条都不查")
    void batchIsCapped() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i <= PickupServiceImpl.MAX_BATCH; i++) {
            codes.add(String.format("%06d", i));
        }
        assertThatThrownBy(() -> service.verifyBatch(codes))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(orderPort, never()).findByVerifyCode(anyString());

        // 恰好在上限内照常处理
        PickupService.BatchResult ok = service.verifyBatch(codes.subList(0, 3));
        assertThat(ok.failed()).hasSize(3);
    }

    @Test
    @DisplayName("★★★ 同一店员连续失败到上限即锁定；换个店员不受影响；成功清零")
    void consecutiveFailuresLockTheStaff() {
        when(orderPort.findByVerifyCode(anyString())).thenReturn(Optional.empty());
        for (int i = 0; i < PickupServiceImpl.MAX_CONSECUTIVE_FAILURES; i++) {
            assertThat(service.verify(String.format("%06d", i), false).success()).isFalse();
        }
        assertThatThrownBy(() -> service.verify("999999", false))
                .as("错满上限还能接着试 = 6 位码空间迟早被试穿")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        // 锁住之后连查都不该再查（否则锁的只是回包，不是探测）
        verify(orderPort, never()).findByVerifyCode("999999");

        // 批量走同一个闸：不能换个入口绕过去
        assertThatThrownBy(() -> service.verifyBatch(List.of("888888")))
                .isInstanceOf(BizException.class);

        // 计数按人：别的店员不被连坐
        login("STAFF2");
        when(orderPort.findByVerifyCode("333333"))
                .thenReturn(Optional.of(order("SUB_MINE", "333333", "PP_MINE", "FULFILLING")));
        when(orderPort.complete(anyString(), anyString(), anyString())).thenReturn(true);
        assertThat(service.verify("333333", false).success()).isTrue();
    }

    @Test
    @DisplayName("★★ 成功一次即清零：按「连续失败」计，不按累计")
    void successResetsCounter() {
        when(orderPort.findByVerifyCode(anyString())).thenReturn(Optional.empty());
        when(orderPort.findByVerifyCode("333333"))
                .thenReturn(Optional.of(order("SUB_MINE", "333333", "PP_MINE", "FULFILLING")));
        when(orderPort.complete(anyString(), anyString(), anyString())).thenReturn(true);
        for (int i = 0; i < PickupServiceImpl.MAX_CONSECUTIVE_FAILURES - 1; i++) {
            service.verify(String.format("%06d", i), false);
        }
        assertThat(service.verify("333333", false).success()).isTrue();
        for (int i = 0; i < PickupServiceImpl.MAX_CONSECUTIVE_FAILURES - 1; i++) {
            assertThat(service.verify(String.format("%06d", i), false).success()).isFalse();
        }
    }
}
