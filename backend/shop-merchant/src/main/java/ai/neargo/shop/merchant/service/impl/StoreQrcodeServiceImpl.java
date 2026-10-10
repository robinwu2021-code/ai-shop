package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchStoreQrcodePrint;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityCommunityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.mapper.QrcodeMappers.StoreQrcodePrintMapper;
import ai.neargo.shop.merchant.service.StoreCodeService;
import ai.neargo.shop.merchant.service.StoreQrcodeService;
import ai.neargo.shop.spi.marketing.StoreVisitQueryPort;
import ai.neargo.shop.spi.user.CommunityQueryPort;
import ai.neargo.shop.spi.user.WxAcodePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StoreQrcodeServiceImpl implements StoreQrcodeService {

    /** C 端门店页，与 {@link StoreCodeServiceImpl} 同一个落地页。 */
    private static final String STORE_PAGE = "pages/store/index";

    private final MchEntityMapper merchantMapper;
    private final MchStoreMapper storeMapper;
    private final MchEntityCommunityMapper communityMapper;
    private final StoreQrcodePrintMapper printMapper;
    private final StoreCodeService storeCodeService;
    /*
     * 埋点域在兄弟模块，只能走 Port。用 ObjectProvider 惰性取：
     * 店铺码页不该因为埋点域没装配（比如只跑 merchant 域的测试）而整页起不来 ——
     * 扫码数缺了是「少一列」，起不来是「这页没了」。
     */
    private final ObjectProvider<StoreVisitQueryPort> visitPort;
    private final ObjectProvider<CommunityQueryPort> communityPort;
    private final ObjectProvider<WxAcodePort> acodePort;

    public StoreQrcodeServiceImpl(MchEntityMapper merchantMapper,
                                  MchStoreMapper storeMapper,
                                  MchEntityCommunityMapper communityMapper,
                                  StoreQrcodePrintMapper printMapper,
                                  StoreCodeService storeCodeService,
                                  ObjectProvider<StoreVisitQueryPort> visitPort,
                                  ObjectProvider<CommunityQueryPort> communityPort,
                                  ObjectProvider<WxAcodePort> acodePort) {
        this.merchantMapper = merchantMapper;
        this.storeMapper = storeMapper;
        this.communityMapper = communityMapper;
        this.printMapper = printMapper;
        this.storeCodeService = storeCodeService;
        this.visitPort = visitPort;
        this.communityPort = communityPort;
        this.acodePort = acodePort;
    }

    @Override
    public PageData<QrcodeRow> list(String keyword, long from, long to, boolean codeless,
                                    long page, long size) {
        List<QrcodeRow> all = rows(keyword, from, to, codeless);
        return PageData.ofAll(all, page, size);
    }

    @Override
    public List<ExportRow> exportRows(String keyword, long from, long to, boolean codeless,
                                      long limit) {
        List<QrcodeRow> rows = rows(keyword, from, to, codeless);
        List<ExportRow> out = new ArrayList<>();
        for (QrcodeRow r : rows) {
            if (out.size() >= limit) {
                break;
            }
            out.add(new ExportRow(r, acodeOf(r.merchantNo(), r.storeNo(), r.code())));
        }
        return out;
    }

    /**
     * 一行一家门店。
     *
     * <p><b>没有码的门店也在里面</b>：运营要发码，前提是先看得见谁没有。
     * 此前这一页按 {@code mch_entity.store_code IS NOT NULL} 过滤，
     * 于是「这家分店从没发过码」永远不出现在需要动手的清单上。
     */
    private List<QrcodeRow> rows(String keyword, long from, long to, boolean codeless) {
        var w = Wrappers.<MchStore>lambdaQuery();
        if (codeless) {
            // 「还没发码」= 列为空。空串不算：它是脏数据，不是状态
            w.and(x -> x.isNull(MchStore::getStoreCode).or().eq(MchStore::getStoreCode, ""));
        }
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(MchStore::getName, keyword)
                    .or().like(MchStore::getStoreNo, keyword)
                    .or().like(MchStore::getEntityNo, keyword)
                    .or().like(MchStore::getStoreCode, keyword));
        }
        w.orderByDesc(MchStore::getId);
        /*
         * ★ **接数据域**：配了「只看某商家」的运营，就该只看到那一家的码。
         * 第一版这里解了域，等于让被限定的运营看到全平台的店铺码与印刷量。
         */
        List<MchStore> stores = storeMapper.selectList(w);
        if (stores.isEmpty()) {
            return List.of();
        }

        Set<String> entityNos = stores.stream().map(MchStore::getEntityNo).collect(Collectors.toSet());
        Set<String> storeNos = stores.stream().map(MchStore::getStoreNo).collect(Collectors.toSet());
        Map<String, Long> scans = scansOf(stores, entityNos, storeNos, from, to);
        Map<String, int[]> printed = printedOf(storeNos);
        Map<String, String> sizes = lastSizeOf(storeNos);
        Map<String, String> communities = communityNameOf(entityNos);
        Map<String, String> merchantNames = merchantNameOf(entityNos);

        List<QrcodeRow> all = new ArrayList<>();
        for (MchStore s : stores) {
            int[] p = printed.get(s.getStoreNo());
            all.add(new QrcodeRow(s.getEntityNo(), merchantNames.get(s.getEntityNo()),
                    s.getStoreNo(), s.getName(), communities.get(s.getEntityNo()),
                    // 空串按「没有码」给出去：端上判 null 就够，不必两处都判
                    blankToNull(s.getStoreCode()),
                    sizes.get(s.getStoreNo()),
                    // ★ 从没登记过给 null 而不是 0：「没登记」与「印了 0 张」是两件事
                    p == null ? null : p[0],
                    scans.getOrDefault(s.getStoreNo(), 0L)));
        }
        return all;
    }

    @Override
    @Transactional
    public String issue(String merchantNo, String storeNo, String operatorNo) {
        requireStore(merchantNo, storeNo);
        // 幂等交给 ensureForStore：已经有码就原样返回，重复点不会把码换掉
        return storeCodeService.ensureForStore(merchantNo, storeNo);
    }

    @Override
    @Transactional
    public String reissue(String merchantNo, String storeNo, String reason, String operatorNo) {
        if (reason == null || reason.isBlank()) {
            /*
             * 换码会让已印物料全部失效。**没有理由就不许换** ——
             * 这一步的代价在线下，而线上只是一次点击，不挡的话代价与操作难度完全不匹配。
             */
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchStore s = requireStore(merchantNo, storeNo);
        /*
         * **必须走 lambdaUpdate 显式 set null**，不能 setStoreCode(null) + updateById：
         * MyBatis-Plus 的 updateById 会**跳过 null 字段**，那条 UPDATE 里根本没有这一列，
         * 于是「换码」执行成功、返回 200、码一个字没变 —— 零报错。
         * 这不是假设：第一版就是这么写的，被 opsIssueIsIdempotentAndReissueNeedsReason 抓了出来。
         *
         * 码图一起清掉。留着的话新码配旧图 —— 扫出来还是旧码，
         * 而界面上码变了、图也在，看不出任何异常。
         */
        DataScopeContext.executeWithoutScope(() -> storeMapper.update(null,
                Wrappers.<MchStore>lambdaUpdate()
                        .eq(MchStore::getId, s.getId())
                        .set(MchStore::getStoreCode, null)
                        .set(MchStore::getAcodeBase64, null)));
        return storeCodeService.ensureForStore(merchantNo, storeNo);
    }

    @Override
    public void recordPrint(String merchantNo, String storeNo, int qty, String size, String remark,
                            String operatorNo) {
        MchStore s = requireStore(merchantNo, storeNo);
        if (qty == 0) {
            // 登记 0 张没有任何含义：既不是印了，也不是冲减。挡在这里比留一行噪声好
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchStoreQrcodePrint row = new MchStoreQrcodePrint();
        row.setPrintNo(BizKey.next(BizKey.QRCODE_PRINT));
        row.setEntityNo(merchantNo);
        row.setStoreNo(s.getStoreNo());
        row.setQty(qty);
        row.setSize(size);
        row.setRemark(remark);
        row.setOperatorNo(operatorNo);
        row.setAt(System.currentTimeMillis());
        row.setTenantNo("MAIN");
        row.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> printMapper.insert(row));
    }

    /**
     * 门店必须属于这个主体。
     *
     * <p><b>不按 storeNo 单独查</b>：传错门店号会把码发到别人家店上，
     * 而这种错在界面上完全看不出来 —— 码是新的、扫得通、只是算到了另一家的账上。
     *
     * <p>{@code storeNo} 为空时取默认店：单店商家的运营操作不该被迫先查门店号。
     */
    private MchStore requireStore(String merchantNo, String storeNo) {
        /*
         * **不解数据域**：这里是 ops 侧的授权边界。配了「只看某商家」的运营
         * 对别家发码/换码/登记印量，应当在这一步 404，而不是查得到再动手。
         * 解了域的话越权操作完全不报错 —— 码发出去了，只是发在别人家店上。
         */
        MchStore s = storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                .eq(MchStore::getEntityNo, merchantNo)
                .eq(storeNo != null && !storeNo.isBlank(), MchStore::getStoreNo, storeNo)
                .eq(storeNo == null || storeNo.isBlank(), MchStore::getIsDefault, 1)
                .last("limit 1"));
        if (s == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return s;
    }

    /**
     * 这家店的码图，取到就落库复用。
     *
     * <p><b>额度</b>：微信永久码每个 appid 总量有限。导出是运营明确点一次的动作，
     * 这里现取现存可以接受；列表页不能走这条路。
     */
    private String acodeOf(String merchantNo, String storeNo, String code) {
        if (code == null) {
            return null;   // 还没发码，没有图可取 —— 不塞占位图，占位图会被直接送去印刷
        }
        MchStore s = requireStore(merchantNo, storeNo);
        if (s.getAcodeBase64() != null && !s.getAcodeBase64().isBlank()) {
            return s.getAcodeBase64();
        }
        WxAcodePort port = acodePort.getIfAvailable();
        if (port == null || !port.enabled()) {
            return null;
        }
        byte[] png = port.unlimited(code, STORE_PAGE);
        if (png == null || png.length == 0) {
            // 取不到不落库也不抛：下次导出再试。导出里少一张图，比整个导出失败好
            return null;
        }
        String b64 = java.util.Base64.getEncoder().encodeToString(png);
        /*
         * 同样不解域：这一行是上面 requireStore 接着域取回来的，本来就在权限内。
         * 解域写回等于给「越权读」补一条越权写 —— 而它藏在一个 GET 里。
         */
        s.setAcodeBase64(b64);
        storeMapper.updateById(s);
        return b64;
    }

    /**
     * 扫码数，**按门店号归集**（V298）。
     *
     * <p>历史埋点行的 {@code store_no} 为空（记录在一主体一码的年代，物理上分不出分店）。
     * 埋点域把这部分单独给出来，在这里并到该主体的<b>默认店</b> ——
     * 与旧码本身的去向一致（旧码回填给了默认店），所以「码 → 扫码数」这条链前后对得上。
     *
     * <p>不并的话，老商家的扫码数会在升级当天归零：数字不见了比数字归错店更难解释。
     */
    private Map<String, Long> scansOf(List<MchStore> stores, Set<String> entityNos,
                                      Set<String> storeNos, long from, long to) {
        StoreVisitQueryPort port = visitPort.getIfAvailable();
        if (port == null) {
            return Map.of();
        }
        StoreVisitQueryPort.ScanCounts counts = port.scanCountsByStore(entityNos, storeNos, from, to);
        Map<String, Long> out = new LinkedHashMap<>(counts.byStore());
        for (MchStore s : stores) {
            if (Boolean.TRUE.equals(s.getIsDefault())) {
                Long legacy = counts.legacyByEntity().get(s.getEntityNo());
                if (legacy != null) {
                    out.merge(s.getStoreNo(), legacy, Long::sum);
                }
            }
        }
        return out;
    }

    /** storeNo -> [累计张数]。用数组包一层只是为了把「没有这一项」与「合计为 0」分开。 */
    private Map<String, int[]> printedOf(Set<String> storeNos) {
        List<MchStoreQrcodePrint> rows = printMapper.selectList(
                Wrappers.<MchStoreQrcodePrint>lambdaQuery()
                        .in(MchStoreQrcodePrint::getStoreNo, storeNos));
        Map<String, int[]> out = new LinkedHashMap<>();
        for (MchStoreQrcodePrint r : rows) {
            out.computeIfAbsent(r.getStoreNo(), k -> new int[]{0})[0] +=
                    r.getQty() == null ? 0 : r.getQty();
        }
        return out;
    }

    /** 最近一次印刷的尺寸。尺寸属于那一次印刷，不是门店属性，所以取最新的那次。 */
    private Map<String, String> lastSizeOf(Set<String> storeNos) {
        List<MchStoreQrcodePrint> rows = printMapper.selectList(
                Wrappers.<MchStoreQrcodePrint>lambdaQuery()
                        .in(MchStoreQrcodePrint::getStoreNo, storeNos)
                        .orderByAsc(MchStoreQrcodePrint::getId));
        Map<String, String> out = new LinkedHashMap<>();
        for (MchStoreQrcodePrint r : rows) {
            if (r.getSize() != null && !r.getSize().isBlank()) {
                out.put(r.getStoreNo(), r.getSize());   // 升序遍历，最后一次覆盖前面的
            }
        }
        return out;
    }

    private Map<String, String> merchantNameOf(Set<String> entityNos) {
        // entityNos 全部来自上面**已接数据域**的门店查询，这里再解一次域没有任何用处，
        // 只会让越权的行有机会漏进来
        List<MchEntity> rows = merchantMapper.selectList(Wrappers.<MchEntity>lambdaQuery()
                .in(MchEntity::getEntityNo, entityNos));
        Map<String, String> out = new LinkedHashMap<>();
        for (MchEntity m : rows) {
            out.put(m.getEntityNo(), m.getName());
        }
        return out;
    }

    /**
     * 主体的社区名（取其覆盖的第一个）。BD 按社区领码地推，这一列是给他分堆用的。
     * 批量取名，逐行查是 N+1。
     */
    private Map<String, String> communityNameOf(Set<String> entityNos) {
        CommunityQueryPort port = communityPort.getIfAvailable();
        if (port == null) {
            return Map.of();
        }
        List<MchEntityCommunity> links = communityMapper.selectList(
                Wrappers.<MchEntityCommunity>lambdaQuery()
                        .in(MchEntityCommunity::getEntityNo, entityNos));
        Map<String, String> firstCommunity = new LinkedHashMap<>();
        for (MchEntityCommunity l : links) {
            firstCommunity.putIfAbsent(l.getEntityNo(), l.getCommunityNo());
        }
        Map<String, String> out = new LinkedHashMap<>();
        // 社区数远少于商家数，按社区号缓存一次名字，别对每家店都问一遍
        Map<String, String> nameCache = new LinkedHashMap<>();
        firstCommunity.forEach((entityNo, communityNo) -> {
            String name = nameCache.computeIfAbsent(communityNo, port::communityName);
            if (name != null) {
                out.put(entityNo, name);
            }
        });
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
