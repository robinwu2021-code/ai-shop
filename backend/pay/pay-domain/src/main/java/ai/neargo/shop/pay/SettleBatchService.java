package ai.neargo.shop.pay;

/**
 * 账期批次：把「时间到了该发生」的三件事真的发生。
 *
 * <p>今天结算单生成之后<b>没有任何东西推动它</b> —— 这个服务是那个推动者的前半段
 * （定 T2、入批、截批）。后半段（对账三道门、放行）在它之后，另立。
 *
 * <p>方案见 {@code docs/technical/design/账期与对账放款-方案.md}。
 */
public interface SettleBatchService {

    /**
     * ① 定 T2：履约完成 + 售后期已过 + <b>无进行中售后</b> → 写 {@code settleable_at}。
     *
     * <p>三个条件缺一不可。第三条是硬闸：<b>售后没闭环就解冻，
     * 等于把争议中的钱先给了一方</b>。
     *
     * <p><b>幂等</b>：已经有 T2 的单不重算 —— 重算会让 T2 随「这一轮什么时候跑」漂移，
     * 而 T2 一动，应结日跟着动。
     *
     * @return 本轮新定下 T2 的单数
     */
    int markSettleable();

    /**
     * ② 入批：可结算且未入批的单，按<b>主体 × 通道</b>归到当期批次。
     *
     * <p>批次不存在就开一个（{@code DRAFT}）；已 {@code COLLECTED} 之后的批次
     * <b>不再接新单</b> —— 那时它的合计数已经被对账用过了，再塞进去两边就对不上。
     * 这种单会落进下一期。
     *
     * @return 本轮入批的单数
     */
    int collectIntoBatches();

    /**
     * ③ 截批：{@code due_at} 已到的 {@code DRAFT} 批次 → {@code COLLECTED}。
     *
     * <p>截批时才算合计（笔数、基数、应放款）与 {@code freeze_expire_at}：
     * 收单期间算的话，每进一单都要改一次，而中途的值没有任何人会用。
     *
     * @return 本轮截掉的批次数
     */
    int closeDueBatches();

    /** 某商家的账期批次，倒序。<b>商家问的是「这一批什么时候放、卡在哪」</b> */
    /**
     * <b>核验 R6：批次合计 ≡ 其下结算单之和。</b>
     *
     * <h2>为什么要有这一条</h2>
     * 合计是在<b>截批那一刻算完写死的</b>（不在收单期间维护，那样并发入批会丢更新）。
     * 写对了当然一致 —— 而写错了<b>没有任何东西会发现</b>：
     * 放款按合计数走，明细页按结算单算，两处各自都「对」，
     * 只有把它们摆在一起才看得出差。
     *
     * <p>这类账目错误的特点是<b>不报错、只是数字不对</b>，
     * 而它错的是「给商家打多少钱」。
     *
     * <h2>只告警，不自动改</h2>
     * 合计数是放款依据。自动「修正」等于让巡检去改一个正在被用来打款的数字 ——
     * 而巡检自己也可能算错（比如窗口取错、把已回退的单算进去）。
     * <b>一个算错的修复比一个已知的差异危险得多。</b>
     *
     * @param limit 单轮上限。不扫全量：巡检要能在一次任务窗口里跑完
     * @return 对不上的批次
     */
    java.util.List<BatchMismatch> checkBatchTotals(int limit);

    /**
     * @param batchNo        批次号
     * @param batchNetMinor  批次上记的净额
     * @param billsNetMinor  其下结算单实际之和
     * @param batchBillCount 批次上记的单数
     * @param billsCount     其下结算单实际条数
     */
    record BatchMismatch(String batchNo, String entityNo,
                         long batchNetMinor, long billsNetMinor,
                         int batchBillCount, int billsCount) {

        /** 差额（分）。正数 = 批次记多了，负数 = 记少了 */
        public long diffMinor() {
            return batchNetMinor - billsNetMinor;
        }
    }

    java.util.List<BatchVO> merchantBatches(String entityNo);

    /** 平台端：全部批次，可按状态筛 */
    java.util.List<BatchVO> opsBatches(String status, String entityNo);

    /**
     * 人工放行一批。
     *
     * @param remark <b>必填</b> —— 放行与继续挂起都要写原因，否则事后没人说得清当时凭什么放
     */
    BatchVO release(String batchNo, String operator, String remark);

    /** 继续挂起。同样必须写原因 */
    BatchVO hold(String batchNo, String operator, String reason);

    /**
     * @param reconScope    SELF_ONLY = 仅我方自查。<b>界面要如实标注</b>，
     *                      不能显示成「已对账」—— 没有 B 侧时那是一句自证的话
     * @param blockedReason 直接展示给商家的原话
     */
    record BatchVO(String batchNo, String entityNo, String payChannel, String settleCycle,
                   long periodFrom, long dueAt, Long releasedAt, Long freezeExpireAt,
                   String status, int billCount, long grossMinor, long netMinor,
                   String reconScope, String blockedReason, Long blockedAt, Long blockExpireAt,
                   String decidedBy, String decideRemark) {
    }
}
