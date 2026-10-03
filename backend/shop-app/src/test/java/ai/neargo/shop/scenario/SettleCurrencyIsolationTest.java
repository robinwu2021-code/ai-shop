package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.SettleBatchService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlSettleBatch;
import ai.neargo.shop.pay.mapper.SettleMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>不同币种的钱不能相加。</b>
 *
 * <h2>为什么这组在单币种系统里也要有</h2>
 * 今天线上只有人民币，所以「按币种分组」这件事<b>做不做，结果都一样</b> ——
 * 而这正是它危险的地方：等接第二个市场那天才发现算错，
 * 那时错的已经是真账，且<b>不报错、只是数字不对</b>。
 *
 * <p>一条台币的单混进人民币的批里，合计会把 100 台币当成 100 元加进去。
 * 商家看到的放款金额是错的，而系统里每一步都「成功」。
 *
 * <p>所以这组用例<b>手工造出第二个币种</b>，把那一天提前到今天。
 */
@SpringBootTest
@ActiveProfiles("test")
class SettleCurrencyIsolationTest {

    /**
     * <b>每条用例一个独立主体。</b>
     *
     * 共用一个主体的话，前一条用例造的结算单会被后一条的 collectIntoBatches
     * 一起收进批里 —— 合计数于是多出几万，而报错说的是「币种校验没拦住」，
     * 与真因（测试之间互相污染）毫不相干。<b>撞过一次才改成这样。</b>
     *
     * 这些账走支付域自己的事务管理器，不跟着测试方法回滚。
     */
    private String entity() {
        return "M-CUR-" + (++seq);
    }
    private static final String CHANNEL = "WECHAT";

    @Autowired
    private SettleBatchService batchService;
    @Autowired
    private SettleMappers.BillMapper billMapper;
    @Autowired
    private SettleMappers.SettleBatchMapper batchMapper;

    private static int seq = 0;

    /** 造一张可结算的结算单 */
    private StlBill bill(String entity, String currency, long netMinor) {
        StlBill b = new StlBill();
        b.setSettleNo("STL-CUR-" + (++seq));
        b.setSubOrderNo("SUB-CUR-" + seq);
        b.setOrderNo("OD-CUR-" + seq);
        b.setEntityNo(entity);
        b.setPayChannel(CHANNEL);
        b.setCurrency(currency);
        b.setGrossMinor(netMinor);
        b.setNetMinor(netMinor);
        b.setStatus(StlBill.PENDING);
        // 同一个应结时刻：不这样的话它们本来就会分到不同的批，用例就白测了
        b.setSettleableAt(1_700_000_000_000L);
        b.setAccruedAt(1_700_000_000_000L);
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));
        return b;
    }

    private List<StlSettleBatch> batchesOf(String entityNo) {
        return DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectList(Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getEntityNo, entityNo)
                        .orderByAsc(StlSettleBatch::getId)));
    }

    @Test
    @DisplayName("★★★ 台币的单不能进人民币的批 —— 混进去合计就把 100 台币当成 100 元")
    void differentCurrenciesGoToDifferentBatches() {
        String e = entity();
        bill(e, "CNY", 10_000L);
        bill(e, "CNY", 20_000L);
        bill(e, "TWD", 50_000L);

        batchService.collectIntoBatches();

        List<StlSettleBatch> batches = batchesOf(e);
        assertThat(batches)
                .as("三张单（两 CNY 一 TWD）应当分成两批。分成一批说明币种没进分批键 —— "
                        + "而那一批的合计数会把台币当人民币加进去，不报错，只是数字不对")
                .hasSize(2);
        assertThat(batches).extracting(StlSettleBatch::getCurrency)
                .containsExactlyInAnyOrder("CNY", "TWD");
    }

    @Test
    @DisplayName("★★★ 截批合计只加同币种 —— 这是「数字对不对」的最后一道")
    void batchTotalSumsOnlySameCurrency() {
        String e = entity();
        bill(e, "CNY", 10_000L);
        bill(e, "CNY", 20_000L);
        bill(e, "TWD", 50_000L);
        batchService.collectIntoBatches();
        batchService.closeDueBatches();

        List<StlSettleBatch> batches = batchesOf(e);
        var cny = batches.stream().filter(b -> "CNY".equals(b.getCurrency())).findFirst().orElseThrow();
        var twd = batches.stream().filter(b -> "TWD".equals(b.getCurrency())).findFirst().orElseThrow();

        assertThat(cny.getNetMinor())
                .as("人民币批的合计混进了台币 —— 30000 是对的，80000 说明三张单被加到了一起")
                .isEqualTo(30_000L);
        assertThat(twd.getNetMinor()).isEqualTo(50_000L);
        // 对照量：两批的单数也要对，否则「合计对」可能是因为压根没收进单
        assertThat(cny.getBillCount()).isEqualTo(2);
        assertThat(twd.getBillCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 结算单生成时币种取自通道，不是靠 DEFAULT 活着")
    void currencyIsWrittenExplicitly() {
        StlBill b = bill(entity(), "TWD", 1_000L);

        StlBill loaded = DataScopeContext.executeWithoutScope(() -> billMapper.selectById(b.getId()));
        assertThat(loaded.getCurrency())
                .as("实体没有 currency 字段的话，这里会读出 null —— "
                        + "而库里其实有值（列有 DEFAULT）。加了列不补字段就是这个症状")
                .isEqualTo("TWD");
    }

    @Test
    @DisplayName("★★★ 第二道防线：错币种的单被硬塞进批里，合计也不能算它")
    void foreignCurrencyBillIsExcludedFromTotal() {
        String e = entity();
        bill(e, "CNY", 10_000L);
        batchService.collectIntoBatches();
        StlSettleBatch batch = batchesOf(e).getFirst();
        assertThat(batch.getCurrency()).isEqualTo("CNY");

        /*
         * **绕过分批直接挂上去。**
         *
         * 正常路径下这不会发生（分批已经按币种分了），所以第一道防线
         * 让第二道永远不触发 —— 而<b>「永远不触发」正是它最需要被测的理由</b>：
         * 它一旦真的触发，加法照做、合计照出，没有任何地方会说话。
         *
         * 试过只靠前三条用例，把截批里的币种校验整个摘掉，测试<b>全绿</b>。
         * 这条就是为了让那次消融变红而写的。
         */
        StlBill sneaky = bill(e, "TWD", 99_999L);
        StlBill patch = new StlBill();
        patch.setId(sneaky.getId());
        patch.setBatchNo(batch.getBatchNo());
        DataScopeContext.executeWithoutScope(() -> billMapper.updateById(patch));

        batchService.closeDueBatches();

        StlSettleBatch closed = DataScopeContext.executeWithoutScope(
                () -> batchMapper.selectById(batch.getId()));
        assertThat(closed.getNetMinor())
                .as("台币那单被算进了人民币批的合计 —— 109999 说明加法把 99999 台币"
                        + "当成了 99999 元。截批时那道币种校验没有拦住")
                .isEqualTo(10_000L);
        assertThat(closed.getBillCount())
                .as("单数也不能算它：合计与单数要一致，否则下一个人会以为是漏了一单")
                .isEqualTo(1);
    }
}
