package ai.neargo.shop.portal.biz;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.service.ActivityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 常驻 + 无门槛 + 直减：保存前要商家确认一次（待办设计 P7）。
 *
 * <p>线上「abc」就是这个组合：每一单都减 ¥10，直到 100 份用完。它是<b>合法配置</b>，
 * 所以这里是提醒不是拒绝 —— 带 {@code riskConfirmed=true} 重提就放行；开关关掉则不问。
 */
class AlwaysOnCutConfirmTest {

    private static ActivityDraft draft(String schedule, String trigger, String benefit) {
        return new ActivityDraft(null, "abc", null, null, trigger, null, null, benefit, 1000L, null,
                null, schedule, null, null, null, 100, null, java.util.List.of(), java.util.List.of(),
                null, null, null, null, null, null, null, null);
    }

    private static BizActivityController controller(boolean flagOn) {
        return new BizActivityController(Mockito.mock(ActivityService.class), (key, def) ->
                BizActivityController.FLAG_ALWAYS_ON_CUT_CONFIRM.equals(key) ? flagOn : def);
    }

    /** 抛的是不是「要确认」那个码。其余异常（这里没有商家上下文）说明已经过了这道闸 */
    private static boolean askedToConfirm(Runnable r) {
        try {
            r.run();
            return false;
        } catch (BizException e) {
            return e.errorCode() == ErrorCode.ACTIVITY_RISK_UNCONFIRMED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    @DisplayName("★★★ 常驻 + 无门槛 + 直减，没确认过 → 要确认")
    void asksBeforeSaving() {
        var c = controller(true);
        assertThat(askedToConfirm(() -> c.save(draft("ALWAYS_ON", "NONE", "CUT"), false))).isTrue();
    }

    @Test
    @DisplayName("★★★ 确认过就放行 —— 这是提醒，不是规则")
    void confirmedPasses() {
        var c = controller(true);
        assertThat(askedToConfirm(() -> c.save(draft("ALWAYS_ON", "NONE", "CUT"), true))).isFalse();
    }

    @Test
    @DisplayName("★★ 开关关掉 = 不问（关着的那一半也要测）")
    void switchOffSkips() {
        var c = controller(false);
        assertThat(askedToConfirm(() -> c.save(draft("ALWAYS_ON", "NONE", "CUT"), false))).isFalse();
    }

    @Test
    @DisplayName("有门槛、非常驻、不是直减的，一律不问")
    void otherCombosUntouched() {
        var c = controller(true);
        assertThat(askedToConfirm(() -> c.save(draft("ALWAYS_ON", "AMOUNT", "CUT"), false))).isFalse();
        assertThat(askedToConfirm(() -> c.save(draft("ONE_OFF", "NONE", "CUT"), false))).isFalse();
        assertThat(askedToConfirm(() -> c.save(draft("ALWAYS_ON", "NONE", "PRICE"), false))).isFalse();
    }
}
