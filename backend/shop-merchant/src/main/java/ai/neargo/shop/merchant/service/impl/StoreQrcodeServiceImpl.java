package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchStoreQrcodePrint;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityCommunityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.QrcodeMappers.StoreQrcodePrintMapper;
import ai.neargo.shop.merchant.service.StoreQrcodeService;
import ai.neargo.shop.spi.marketing.StoreVisitQueryPort;
import ai.neargo.shop.spi.user.CommunityQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StoreQrcodeServiceImpl implements StoreQrcodeService {

    private final MchEntityMapper merchantMapper;
    private final MchEntityCommunityMapper communityMapper;
    private final StoreQrcodePrintMapper printMapper;
    /*
     * 埋点域在兄弟模块，只能走 Port。用 ObjectProvider 惰性取：
     * 店铺码页不该因为埋点域没装配（比如只跑 merchant 域的测试）而整页起不来 ——
     * 扫码数缺了是「少一列」，起不来是「这页没了」。
     */
    private final ObjectProvider<StoreVisitQueryPort> visitPort;
    private final ObjectProvider<CommunityQueryPort> communityPort;

    public StoreQrcodeServiceImpl(MchEntityMapper merchantMapper,
                                  MchEntityCommunityMapper communityMapper,
                                  StoreQrcodePrintMapper printMapper,
                                  ObjectProvider<StoreVisitQueryPort> visitPort,
                                  ObjectProvider<CommunityQueryPort> communityPort) {
        this.merchantMapper = merchantMapper;
        this.communityMapper = communityMapper;
        this.printMapper = printMapper;
        this.visitPort = visitPort;
        this.communityPort = communityPort;
    }

    @Override
    public PageData<QrcodeRow> list(String keyword, long from, long to, long page, long size) {
        var w = Wrappers.<MchEntity>lambdaQuery()
                // 没生成过码的主体不在这张表上 —— 这一页回答的是「已经有码的店怎么样了」
                .isNotNull(MchEntity::getStoreCode)
                .ne(MchEntity::getStoreCode, "");
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(MchEntity::getName, keyword)
                    .or().like(MchEntity::getEntityNo, keyword)
                    .or().like(MchEntity::getStoreCode, keyword));
        }
        w.orderByDesc(MchEntity::getId);
        /*
         * ★ **接数据域**：配了「只看某商家」的运营，就该只看到那一家的码。
         * 第一版这里解了域，等于让被限定的运营看到全平台的店铺码与印刷量。
         */
        List<MchEntity> rows = merchantMapper.selectList(w);
        if (rows.isEmpty()) {
            return PageData.of(List.of(), 0, page, size);
        }

        Set<String> entityNos = rows.stream().map(MchEntity::getEntityNo).collect(Collectors.toSet());
        Map<String, Long> scans = scansOf(entityNos, from, to);
        Map<String, int[]> printed = printedOf(entityNos);   // entityNo -> [累计张数]
        Map<String, String> sizes = lastSizeOf(entityNos);
        Map<String, String> communities = communityNameOf(entityNos);

        List<QrcodeRow> all = new ArrayList<>();
        for (MchEntity m : rows) {
            String no = m.getEntityNo();
            int[] p = printed.get(no);
            all.add(new QrcodeRow(no, m.getName(), communities.get(no), m.getStoreCode(),
                    sizes.get(no),
                    // ★ 从没登记过给 null 而不是 0：「没登记」与「印了 0 张」是两件事
                    p == null ? null : p[0],
                    scans.getOrDefault(no, 0L)));
        }
        return PageData.ofAll(all, page, size);
    }

    @Override
    public void recordPrint(String merchantNo, int qty, String size, String remark, String operatorNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (qty == 0) {
            // 登记 0 张没有任何含义：既不是印了，也不是冲减。挡在这里比留一行噪声好
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchStoreQrcodePrint row = new MchStoreQrcodePrint();
        row.setPrintNo(BizKey.next(BizKey.QRCODE_PRINT));
        row.setEntityNo(merchantNo);
        row.setQty(qty);
        row.setSize(size);
        row.setRemark(remark);
        row.setOperatorNo(operatorNo);
        row.setAt(System.currentTimeMillis());
        row.setTenantNo("MAIN");
        row.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> printMapper.insert(row));
    }

    private Map<String, Long> scansOf(Set<String> entityNos, long from, long to) {
        StoreVisitQueryPort port = visitPort.getIfAvailable();
        return port == null ? Map.of() : port.scanCounts(entityNos, from, to);
    }

    /** entityNo -> [累计张数]。用数组包一层只是为了把「没有这一项」与「合计为 0」分开。 */
    private Map<String, int[]> printedOf(Set<String> entityNos) {
        List<MchStoreQrcodePrint> rows = printMapper.selectList(
                Wrappers.<MchStoreQrcodePrint>lambdaQuery()
                        .in(MchStoreQrcodePrint::getEntityNo, entityNos));
        Map<String, int[]> out = new LinkedHashMap<>();
        for (MchStoreQrcodePrint r : rows) {
            out.computeIfAbsent(r.getEntityNo(), k -> new int[]{0})[0] +=
                    r.getQty() == null ? 0 : r.getQty();
        }
        return out;
    }

    /** 最近一次印刷的尺寸。尺寸属于那一次印刷，不是门店属性，所以取最新的那次。 */
    private Map<String, String> lastSizeOf(Set<String> entityNos) {
        List<MchStoreQrcodePrint> rows = printMapper.selectList(
                Wrappers.<MchStoreQrcodePrint>lambdaQuery()
                        .in(MchStoreQrcodePrint::getEntityNo, entityNos)
                        .orderByAsc(MchStoreQrcodePrint::getId));
        Map<String, String> out = new LinkedHashMap<>();
        for (MchStoreQrcodePrint r : rows) {
            if (r.getSize() != null && !r.getSize().isBlank()) {
                out.put(r.getEntityNo(), r.getSize());   // 升序遍历，最后一次覆盖前面的
            }
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
}
