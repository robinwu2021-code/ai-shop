package ai.neargo.shop.platform;

import ai.neargo.shop.platform.port.MasterDataPortImpl;
import ai.neargo.shop.platform.MasterDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 区划反查不许逐个走祖先链（TDD-区划反查 N+1）。
 *
 * <p><b>这是慢日志上线当天抓到的第一条。</b> 生产实测 {@code GET /biz/communities}
 * 两次调用都是 <b>77 秒</b>：23657 个社区 × 两个方法 × 4 层祖先链
 * ≈ 189,000 次 {@code selectOne}，而 {@code name} 与 {@code rural} 都是
 * {@code sys_region} 上的普通列，一条 {@code IN} 就能拿全。
 *
 * <p><b>判据是查询次数，不是毫秒。</b> 同一个测试类在这台十会话共用的机器上
 * 一小时内跑 7 次，耗时 13.27–20.88 秒（1.57 倍）—— 毫秒断言在这里只会随机变红。
 * 而次数是确定值：N 个码就是 1 次，跑一百遍都一样，与机器、与数据量都无关。
 * 更要紧的是，<b>次数就是这个缺陷的本体</b>：它慢不是因为某行代码慢，
 * 是因为多查了 189,000 次。量次数是量根因，量毫秒只是量症状。
 */
@DisplayName("区划反查：批量一次，不走祖先链")
class RegionBatchLookupTest {

    private static RegionService.RegionBrief brief(String name, boolean rural) {
        return new RegionService.RegionBrief(name, rural);
    }

    /** 20 个码足以让「逐个」与「批量」在次数上拉开：逐个是 20 次，批量是 1 次 */
    private static final List<String> CODES =
            IntStream.range(0, 20).mapToObj(i -> "4403040%02d".formatted(i)).toList();

    private final RegionService region = mock(RegionService.class);
    private final MasterDataPortImpl port =
            new MasterDataPortImpl(mock(MasterDataService.class), region);

    private void stubBatch() {
        when(region.byCodes(any())).thenAnswer(inv -> {
            java.util.Collection<String> codes = inv.getArgument(0);
            return codes.stream().collect(java.util.stream.Collectors.toMap(
                    c -> c, c -> brief("街道" + c, c.endsWith("7"))));
        });
    }

    @Test
    @DisplayName("★★★ AC1 regionNames 只查一次，且一次都不走 path()")
    void regionNamesQueriesOnce() {
        stubBatch();
        Map<String, String> names = port.regionNames(CODES);

        assertThat(names).hasSize(20);
        assertThat(names.get(CODES.getFirst())).isEqualTo("街道" + CODES.getFirst());
        verify(region, times(1)).byCodes(any());
        // path() 是逐级 selectOne 的那条路 —— 一次都不许走，否则 N 个码就是 N×4 次往返
        verify(region, never()).path(anyString());
    }

    @Test
    @DisplayName("★★★ AC2 regionRural 同样只查一次，不走 path()")
    void regionRuralQueriesOnce() {
        stubBatch();
        Map<String, Boolean> rural = port.regionRural(CODES);

        assertThat(rural).hasSize(20);
        assertThat(rural.get("440304007")).as("按桩的约定，尾号 7 是乡村").isTrue();
        assertThat(rural.get("440304001")).isFalse();
        verify(region, times(1)).byCodes(any());
        verify(region, never()).path(anyString());
    }

    @Test
    @DisplayName("★★★ AC3 查不到的码不进 map —— 与改前逐字相同，调用方自己兜底")
    void missingCodesStayOut() {
        when(region.byCodes(any())).thenReturn(Map.of("440304001", brief("新安街道", false)));

        assertThat(port.regionNames(List.of("440304001", "999999999")))
                .containsOnlyKeys("440304001");
        assertThat(port.regionRural(List.of("440304001", "999999999")))
                .containsOnlyKeys("440304001");
    }

    @Test
    @DisplayName("★★ AC3 空集与 null 不查库 —— 省掉一次没意义的往返")
    void emptyInputDoesNotQuery() {
        assertThat(port.regionNames(List.of())).isEmpty();
        assertThat(port.regionNames(null)).isEmpty();
        assertThat(port.regionRural(List.of())).isEmpty();
        assertThat(port.regionRural(null)).isEmpty();
        verify(region, never()).byCodes(any());
    }

    /*
     * 空白码的过滤**从 Port 挪进了 byCodes**（改之前是 Port 的循环里 `if blank continue`）。
     * 所以这里验的是契约的结果 —— 空白不出现在返回里、也不让调用炸 ——
     * 而不是「Port 调 byCodes 之前先滤了一遍」：那是实现细节，钉它只会挡住下次重构。
     * byCodes 自己「空白不进 IN」由 RegionServiceImpl 侧保证（见它的实现与 javadoc）。
     */
    @Test
    @DisplayName("★★ AC3 掺了 null 与空串也不炸，且空白不出现在结果里")
    void blankCodesDoNotBreakOrAppear() {
        when(region.byCodes(any())).thenReturn(Map.of("440304001", brief("新安街道", false)));
        var mixed = java.util.Arrays.asList("440304001", null, "  ");

        assertThat(port.regionNames(mixed)).containsOnlyKeys("440304001");
        assertThat(port.regionRural(mixed)).containsOnlyKeys("440304001");
    }
}
