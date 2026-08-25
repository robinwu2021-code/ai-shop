package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchAppointmentSlot;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.AppointmentSlotMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.AppointmentSlotService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AppointmentSlotServiceImpl implements AppointmentSlotService {

    private final AppointmentSlotMapper slotMapper;
    private final MchStoreMapper storeMapper;

    public AppointmentSlotServiceImpl(AppointmentSlotMapper slotMapper, MchStoreMapper storeMapper) {
        this.slotMapper = slotMapper;
        this.storeMapper = storeMapper;
    }

    @Override
    @Transactional
    public SlotVO open(String merchantNo, String storeNo, long startAt, long endAt, int capacity) {
        requireOwnStore(merchantNo, storeNo);
        if (endAt <= startAt || capacity < 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 过去的时段开出来也没人约得上，只会让列表变长
        if (endAt <= System.currentTimeMillis()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchAppointmentSlot slot = new MchAppointmentSlot();
        slot.setSlotNo(BizKey.next(BizKey.APPOINTMENT_SLOT));
        slot.setEntityNo(merchantNo);
        slot.setStoreNo(storeNo);
        slot.setStartAt(startAt);
        slot.setEndAt(endAt);
        slot.setCapacity(capacity);
        slot.setBooked(0);
        slot.setStatus(MchAppointmentSlot.OPEN);
        slotMapper.insert(slot);
        return toVO(slot);
    }

    @Override
    @Transactional
    public SlotVO close(String merchantNo, String slotNo) {
        MchAppointmentSlot slot = DataScopeContext.executeWithoutScope(() ->
                slotMapper.selectOne(Wrappers.<MchAppointmentSlot>lambdaQuery()
                        .eq(MchAppointmentSlot::getSlotNo, slotNo).last("LIMIT 1")));
        if (slot == null || !slot.getEntityNo().equals(merchantNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * **已经约进来的单不受影响**：停约的语义是「别再往里放人」，
         * 不是「把约上的赶走」。赶人得先有一套通知与补偿的规则，那是另一件事；
         * 而在有那套规则之前，悄悄取消别人的预约比不支持停约糟得多。
         */
        slot.setStatus(MchAppointmentSlot.CLOSED);
        DataScopeContext.executeWithoutScope(() -> slotMapper.updateById(slot));
        return toVO(slot);
    }

    @Override
    public List<SlotVO> list(String storeNo, long fromAt, long toAt, boolean onlyBookable) {
        /*
         * ⚠️ 绕过数据域：C 端要看别人家的时段（挑服务时间就是在看商家的档期），
         * 而 C 端会话的维度对不上商家表 —— fail-closed 会拼成 1=0，
         * 症状是「这家店一个可约时段都没有」，而商家那边明明开着。
         * 这里只读时段，不含任何隐私字段。
         */
        List<MchAppointmentSlot> rows = DataScopeContext.executeWithoutScope(() ->
                slotMapper.selectList(Wrappers.<MchAppointmentSlot>lambdaQuery()
                        .eq(MchAppointmentSlot::getStoreNo, storeNo)
                        .ge(MchAppointmentSlot::getStartAt, fromAt)
                        .lt(MchAppointmentSlot::getStartAt, toAt)
                        .orderByAsc(MchAppointmentSlot::getStartAt)));
        return rows.stream()
                .filter(r -> !onlyBookable
                        || (MchAppointmentSlot.OPEN.equals(r.getStatus())
                            && r.getBooked() < r.getCapacity()))
                .map(this::toVO)
                .toList();
    }

    /** 归属闸：只能给自己的店开时段。 */
    private void requireOwnStore(String merchantNo, String storeNo) {
        MchStore store = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getStoreNo, storeNo).last("LIMIT 1")));
        if (store == null || !store.getEntityNo().equals(merchantNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private SlotVO toVO(MchAppointmentSlot s) {
        int cap = s.getCapacity() == null ? 0 : s.getCapacity();
        int booked = s.getBooked() == null ? 0 : s.getBooked();
        return new SlotVO(s.getSlotNo(), s.getStoreNo(), s.getStartAt(), s.getEndAt(),
                cap, booked, Math.max(0, cap - booked), s.getStatus());
    }
}
