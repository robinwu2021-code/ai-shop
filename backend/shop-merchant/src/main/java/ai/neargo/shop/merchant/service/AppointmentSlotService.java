package ai.neargo.shop.merchant.service;

import java.util.List;

/**
 * 门店的预约排期。
 *
 * <p><b>只有开与停，没有改</b>：改容量得先回答「已经约进来的怎么办」——
 * 把 capacity 调到比 booked 还小，那几个已经约上的单立刻处于「超卖」状态，
 * 而系统里没有任何地方会报错。要支持的话得先有一套「挤出去谁、怎么通知」的规则，
 * 那是另一件事。现在的做法是：停掉旧的、开一个新的，已约的单不受影响。
 */
public interface AppointmentSlotService {

    /**
     * 开一个时段。
     *
     * @param capacity 这个时段能接几单，必须 ≥ 1 —— 0 等于开了个约不上的档，
     *                 而它在列表里看着和正常的一样
     */
    SlotVO open(String merchantNo, String storeNo, long startAt, long endAt, int capacity);

    /**
     * 停约。<b>不删行</b> —— 已经约进来的单还指着它，删掉的话取消时不知道
     * 该把名额还给谁，商家也查不到那天到底接了几单。
     */
    SlotVO close(String merchantNo, String slotNo);

    /**
     * 列时段。
     *
     * @param onlyBookable true = 只列**买家还约得上的**（可约且没满）。
     *                     C 端传 true，B 端传 false —— 商家要看见自己开的全部，
     *                     包括约满的和停掉的，否则他不知道为什么没人约
     */
    List<SlotVO> list(String storeNo, long fromAt, long toAt, boolean onlyBookable);

    /**
     * @param remaining 剩余名额 = capacity - booked。<b>派生值，不落库</b> ——
     *                  落库就成了第三个可能与前两列对不上的数
     */
    record SlotVO(String slotNo, String storeNo, long startAt, long endAt,
                  int capacity, int booked, int remaining, String status) {
    }
}
