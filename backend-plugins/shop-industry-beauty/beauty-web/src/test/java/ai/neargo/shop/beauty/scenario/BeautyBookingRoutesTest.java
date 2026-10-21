package ai.neargo.shop.beauty.scenario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
/** Booking resources must be real public controllers, not DTO-only delivery. */
class BeautyBookingRoutesTest {
    @Test void bookingControllersExistWithBaselineResourcePaths() {
        for (String[] route : new String[][] {
                {"biz.BizAvailabilityController", "/biz/x/beauty/availability"},
                {"mp.MpAvailabilityController", "/mp/x/beauty/availability"},
                {"biz.BizAppointmentController", "/biz/x/beauty/appointments"},
                {"mp.MpAppointmentController", "/mp/x/beauty/appointments"},
                {"biz.BizServiceOperationController", "/biz/x/beauty/operations"}}) {
            Class<?> type = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.api." + route[0]));
            assertThat(type.getAnnotation(RequestMapping.class).value()).containsExactly(route[1]);
        }
    }
}
