package ai.neargo.shop.settle;

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
}
