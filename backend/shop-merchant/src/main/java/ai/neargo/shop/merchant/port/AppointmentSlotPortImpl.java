package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchAppointmentSlot;
import ai.neargo.shop.merchant.mapper.MerchantMappers.AppointmentSlotMapper;
import ai.neargo.shop.spi.user.AppointmentSlotPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/**
 * 预约时段占位的实现。
 *
 * <p><b>单独一个 Component，不并进 {@code MerchantPortImpl}</b> —— 那个类挂在一条
 * 最近才炸过的构造器环上，往里加依赖是给自己找麻烦。这里只用一个 Mapper。
 */
@Component
public class AppointmentSlotPortImpl implements AppointmentSlotPort {

    private final AppointmentSlotMapper slotMapper;

    public AppointmentSlotPortImpl(AppointmentSlotMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    /**
     * ⚠️ 全部绕过数据域：下单是 C 端会话，而这几张表是商家维度的 ——
     * fail-closed 会把语句拼成 {@code 1=0}，症状是「明明还有名额却说约满了」，
     * 或者更糟：{@link #hasOpenSlots} 恒返回 false，于是**所有商家都退回旧口径**，
     * 整个排期功能静默失效而没有一条报错。
     *
     * <p>归属由 SQL 里的 {@code store_no} 判，绕的是数据域不是鉴权。
     */
    @Override
    public BookResult tryBook(String slotNo, String storeNo) {
        if (slotNo == null || slotNo.isBlank() || storeNo == null || storeNo.isBlank()) {
            return BookResult.of(BookOutcome.UNAVAILABLE);
        }
        int hit = DataScopeContext.executeWithoutScope(() -> slotMapper.tryBook(slotNo, storeNo));
        if (hit == 1) {
            /*
             * 抢到之后再读一次，只为把时段的起止带回去 —— 让子单的 appointment_at
             * 由时段推出而不是信端上传的时间戳。判定已经在上面那条 UPDATE 里做完了。
             */
            MchAppointmentSlot booked = DataScopeContext.executeWithoutScope(() ->
                    slotMapper.selectOne(Wrappers.<MchAppointmentSlot>lambdaQuery()
                            .eq(MchAppointmentSlot::getSlotNo, slotNo).last("LIMIT 1")));
            return new BookResult(BookOutcome.BOOKED,
                    booked == null ? null : booked.getStartAt(),
                    booked == null ? null : booked.getEndAt());
        }
        /*
         * 到这里已经**判完了** —— 下面这次读只是为了说清楚为什么没抢到，
         * 不参与任何决定。所以它不是「先查再改」：真正的判定发生在上面那条
         * 带条件的 UPDATE 里，读到的哪怕是过时的数据，也只影响给买家的那句话。
         */
        MchAppointmentSlot slot = DataScopeContext.executeWithoutScope(() ->
                slotMapper.selectOne(Wrappers.<MchAppointmentSlot>lambdaQuery()
                        .eq(MchAppointmentSlot::getSlotNo, slotNo)
                        .eq(MchAppointmentSlot::getStoreNo, storeNo)
                        .last("LIMIT 1")));
        if (slot == null || !MchAppointmentSlot.OPEN.equals(slot.getStatus())) {
            return BookResult.of(BookOutcome.UNAVAILABLE);
        }
        return BookResult.of(BookOutcome.FULL);
    }

    @Override
    public void release(String slotNo) {
        if (slotNo == null || slotNo.isBlank()) {
            return;
        }
        DataScopeContext.executeWithoutScope(() -> slotMapper.release(slotNo));
    }

    @Override
    public boolean hasOpenSlots(String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return false;
        }
        return DataScopeContext.executeWithoutScope(() ->
                slotMapper.selectCount(Wrappers.<MchAppointmentSlot>lambdaQuery()
                        .eq(MchAppointmentSlot::getStoreNo, storeNo)
                        .eq(MchAppointmentSlot::getStatus, MchAppointmentSlot.OPEN))) > 0;
    }
}
