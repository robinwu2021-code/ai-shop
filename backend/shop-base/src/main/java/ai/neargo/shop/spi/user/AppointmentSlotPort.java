package ai.neargo.shop.spi.user;

/**
 * trade → merchant：预约时段的占位与释放。
 *
 * <p>时段是<b>门店的服务容量</b>，与门店渠道配置同属一类东西，所以归 merchant 域。
 * 交易域只需要三个动作，不需要知道它长什么样。
 */
public interface AppointmentSlotPort {

    /** 抢名额的三种结局。分开是因为**给买家看的话不一样**。 */
    enum BookOutcome {
        /** 抢到了 */
        BOOKED,
        /** 这个时段满了 —— 让他换一个**时间** */
        FULL,
        /** 时段不存在 / 已停约 / 不属于这家店 —— 让他重新**挑一个** */
        UNAVAILABLE
    }

    /**
     * 占一个名额。
     *
     * <p><b>storeNo 是越权闸不是筛选</b>：时段编号由端上传，不比对归属的话，
     * 买家可以拿别家店的时段号来下单 —— 占的是别人的名额，
     * 而那家店的师傅那天根本不知道有这一单。
     */
    BookResult tryBook(String slotNo, String storeNo);

    /**
     * @param startAt 抢到时带回时段的起止，<b>抢不到时为 null</b>。
     *                <p>带回来是为了让 {@code ord_sub_order.appointment_at} 由时段推出，
     *                而不是信端上传的那个时间戳 —— 两个来源写同一列的话，
     *                买家可以约 9 点的档、把 appointment_at 传成 15 点，
     *                商家的待服务列表会按 15 点排，而名额扣在 9 点那一格。
     */
    record BookResult(BookOutcome outcome, Long startAt, Long endAt) {

        public static BookResult of(BookOutcome outcome) {
            return new BookResult(outcome, null, null);
        }

        public boolean booked() {
            return outcome == BookOutcome.BOOKED;
        }
    }

    /**
     * 还一个名额。<b>幂等由调用方保证</b> —— 见
     * {@code ord_sub_order.appointment_released_at}：先条件 UPDATE 打标记，
     * 打上了才来调这里。反过来（先还再打标记）在重放下仍会多还一次。
     */
    void release(String slotNo);

    /**
     * 这家店有没有开过时段。
     *
     * <p>为了兼容：<b>一个时段都没开 = 还没迁到排期模型，按旧口径放行</b>
     * （买家自己填一个时间戳）。与门店渠道「一行都没有就全放行」同一条规矩 ——
     * 不这样的话，这批代码一上线，所有做上门服务的商家在开出时段之前
     * <b>一单都接不了</b>，而他们不会收到任何提示。
     */
    boolean hasOpenSlots(String storeNo);
}
