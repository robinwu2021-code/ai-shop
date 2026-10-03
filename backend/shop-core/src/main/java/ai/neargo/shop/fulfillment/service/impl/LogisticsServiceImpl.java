package ai.neargo.shop.fulfillment.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.IsoTime;
import ai.neargo.shop.fulfillment.dto.CarrierConfigVO;
import ai.neargo.shop.fulfillment.dto.FreightTemplateVO;
import ai.neargo.shop.fulfillment.dto.ShipmentVO;
import ai.neargo.shop.fulfillment.entity.FulCarrier;
import ai.neargo.shop.fulfillment.entity.FulFreightTemplate;
import ai.neargo.shop.fulfillment.entity.FulShipment;
import ai.neargo.shop.fulfillment.entity.FulShipmentTrace;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.CarrierMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.FreightTemplateMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShipmentMapper;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.ShipmentTraceMapper;
import ai.neargo.shop.fulfillment.service.LogisticsService;
import ai.neargo.shop.spi.trade.FulfillmentStatsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link LogisticsService} 实现。
 *
 * <p><b>不接任何承运商 API</b>：这一层只做「存住 + 展示 + 校验」。
 * 校验逐条对齐 ops-web 的 mock —— 后端不能比 mock 宽，
 * 反过来就是「mock 比后端好看」那类缺陷。
 */
@Service
public class LogisticsServiceImpl implements LogisticsService {

    /** 在途：还没签收、也没异常终止的单。停用运力时按它判断有没有单卡在手上 */
    private static final Set<String> IN_FLIGHT =
            Set.of(FulShipment.CREATED, FulShipment.PICKED_UP, FulShipment.IN_TRANSIT);

    private final ShipmentMapper shipmentMapper;
    private final ShipmentTraceMapper traceMapper;
    private final FreightTemplateMapper templateMapper;
    private final CarrierMapper carrierMapper;
    private final FulfillmentStatsPort statsPort;
    private final ObjectMapper json;

    public LogisticsServiceImpl(ShipmentMapper shipmentMapper, ShipmentTraceMapper traceMapper,
                                FreightTemplateMapper templateMapper, CarrierMapper carrierMapper,
                                FulfillmentStatsPort statsPort, ObjectMapper json) {
        this.shipmentMapper = shipmentMapper;
        this.traceMapper = traceMapper;
        this.templateMapper = templateMapper;
        this.carrierMapper = carrierMapper;
        this.statsPort = statsPort;
        this.json = json;
    }

    // ---------------------------------------------------------------- 运单

    @Override
    @Transactional
    public List<ShipmentVO> shipments(String status, String carrier, String keyword) {
        ensureShipments();
        var w = Wrappers.<FulShipment>lambdaQuery()
                .eq(status != null && !status.isBlank(), FulShipment::getStatus, status)
                .eq(carrier != null && !carrier.isBlank(), FulShipment::getCarrier, carrier);
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(FulShipment::getWaybillNo, keyword)
                    .or().like(FulShipment::getSubOrderNo, keyword)
                    .or().like(FulShipment::getReceiver, keyword));
        }
        w.orderByDesc(FulShipment::getId);
        List<FulShipment> rows = DataScopeContext.executeWithoutScope(() -> shipmentMapper.selectList(w));
        if (rows.isEmpty()) {
            return List.of();
        }
        // 轨迹一次查回来按运单分组 —— 逐单查是 N+1，而这是列表页
        Map<String, List<FulShipmentTrace>> traces = DataScopeContext.executeWithoutScope(() ->
                        traceMapper.selectList(Wrappers.<FulShipmentTrace>lambdaQuery()
                                .in(FulShipmentTrace::getShipmentNo,
                                        rows.stream().map(FulShipment::getShipmentNo).toList())
                                .orderByAsc(FulShipmentTrace::getAt)))
                .stream().collect(java.util.stream.Collectors.groupingBy(FulShipmentTrace::getShipmentNo));
        return rows.stream().map(s -> toVO(s, traces.getOrDefault(s.getShipmentNo(), List.of()))).toList();
    }

    /**
     * 读时补齐（TDD-运营端履约调度 §4.6）。
     *
     * <p>「快递履约且已回填单号」的子单各对应一行运单记录。<b>没回填单号的不建</b> ——
     * 那种单还没发货，建出来是一条永远没有轨迹的空记录。
     *
     * <p>已存在的行只<b>同步状态</b>，<b>不覆盖运单号</b>：换单号是运营在这一页唯一的写动作，
     * 而 {@code ord_sub_order.express_no} 还留着旧号 —— 跟着回写等于每刷一次列表就把运营改的撤销一次。
     */
    private void ensureShipments() {
        List<FulfillmentStatsPort.ExpressOrder> orders = statsPort.expressOrders();
        if (orders.isEmpty()) {
            return;
        }
        List<FulShipment> existing = DataScopeContext.executeWithoutScope(() ->
                shipmentMapper.selectList(Wrappers.<FulShipment>lambdaQuery()
                        .in(FulShipment::getSubOrderNo,
                                orders.stream().map(FulfillmentStatsPort.ExpressOrder::subOrderNo).toList())));
        Map<String, FulShipment> bySubOrder = existing.stream()
                .collect(java.util.stream.Collectors.toMap(FulShipment::getSubOrderNo, s -> s, (a, b) -> a));

        String defaultCarrier = topCarrier();
        // (承运商, 运单号) 上有唯一键。冲突的那一行跳过而不是让整页 500 —— 见下方注释
        Set<String> taken = new HashSet<>();
        for (FulShipment s : DataScopeContext.executeWithoutScope(() ->
                shipmentMapper.selectList(Wrappers.<FulShipment>lambdaQuery()))) {
            taken.add(s.getCarrier() + "|" + s.getWaybillNo());
        }

        for (FulfillmentStatsPort.ExpressOrder o : orders) {
            FulShipment cur = bySubOrder.get(o.subOrderNo());
            if (cur != null) {
                /*
                 * 状态跟着订单走。**必须同步**：不同步的话运单永远停在建行那一刻的状态，
                 * 而「已签收不许改单号」这条闸就永远轮不到它生效。
                 */
                String next = statusOf(o.status());
                if (!next.equals(cur.getStatus())) {
                    cur.setStatus(next);
                    DataScopeContext.executeWithoutScope(() -> shipmentMapper.updateById(cur));
                }
                continue;
            }
            if (defaultCarrier == null) {
                // 一家启用的运力都没有：建不出运单记录（承运商是快照，不能留空）。
                // 这不是异常 —— 页面上就该是空的，而运力页会告诉运营为什么
                return;
            }
            if (!taken.add(defaultCarrier + "|" + o.expressNo())) {
                // 同承运商下这个单号已经被别的子单占了。补齐是个读操作，
                // 不该因为一条冲突数据把整张列表打不开 —— 冲突那一单在这里就是「没有运单记录」
                continue;
            }
            FulShipment s = new FulShipment();
            s.setShipmentNo(BizKey.next(BizKey.SHIPMENT));
            s.setSubOrderNo(o.subOrderNo());
            s.setCarrier(defaultCarrier);
            s.setWaybillNo(o.expressNo());
            s.setStatus(statusOf(o.status()));
            s.setReceiver(o.receiver());
            s.setRegion(o.region());
            s.setTenantNo("MAIN");
            s.setCreatedAt(LocalDateTime.now());
            s.setCreatedBy("SYSTEM");
            s.setUpdatedAt(LocalDateTime.now());
            s.setUpdatedBy("SYSTEM");
            s.setVersion(0L);
            s.setDeleted(0);
            DataScopeContext.executeWithoutScope(() -> shipmentMapper.insert(s));
        }
    }

    /** 当时优先级最高的启用运力。一家都没启用时给 null。 */
    private String topCarrier() {
        List<FulCarrier> enabled = DataScopeContext.executeWithoutScope(() ->
                carrierMapper.selectList(Wrappers.<FulCarrier>lambdaQuery()
                        .eq(FulCarrier::getEnabled, 1)
                        .orderByAsc(FulCarrier::getPriority)));
        return enabled.isEmpty() ? null : enabled.get(0).getCarrier();
    }

    /**
     * 运单状态由订单状态推导。
     *
     * <p><b>一期不接承运商回传</b>（ADR-005 §5）：编一个假的轨迹推进比没有更糟。
     * {@code EXCEPTION} 没有产生路径，这里也不会造出来。
     */
    private static String statusOf(String orderStatus) {
        return switch (orderStatus == null ? "" : orderStatus) {
            case "FULFILLING" -> FulShipment.IN_TRANSIT;
            case "COMPLETED" -> FulShipment.DELIVERED;
            default -> FulShipment.CREATED;
        };
    }

    @Override
    @Transactional
    public ShipmentVO updateWaybill(String shipmentNo, String waybillNo, String reason,
                                    String operatorNo) {
        // 原因必填：之后对不上时这是唯一线索，而用户可能已拿着旧号在查件
        if (waybillNo == null || waybillNo.isBlank() || reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        FulShipment s = requireShipment(shipmentNo);
        // 已签收的不许改 —— 等于把一条已完成的轨迹指向别处
        if (FulShipment.DELIVERED.equals(s.getStatus())) {
            throw BizException.of(ErrorCode.WAYBILL_LOCKED);
        }
        // 同承运商下不许重号：两单的轨迹会搅在一起，而且没法自动拆开
        Long dup = DataScopeContext.executeWithoutScope(() ->
                shipmentMapper.selectCount(Wrappers.<FulShipment>lambdaQuery()
                        .eq(FulShipment::getCarrier, s.getCarrier())
                        .eq(FulShipment::getWaybillNo, waybillNo)
                        .ne(FulShipment::getShipmentNo, shipmentNo)));
        if (dup != null && dup > 0) {
            throw BizException.of(ErrorCode.WAYBILL_DUPLICATED);
        }

        String old = s.getWaybillNo();
        s.setWaybillNo(waybillNo);
        DataScopeContext.executeWithoutScope(() -> shipmentMapper.updateById(s));

        // 换号本身要进轨迹：不写的话，之后看到的是一条凭空换了单号的运单
        FulShipmentTrace t = new FulShipmentTrace();
        t.setShipmentNo(shipmentNo);
        t.setAt(System.currentTimeMillis());
        t.setText("运单号由 " + (old == null ? "空" : old) + " 改为 " + waybillNo + "：" + reason.trim());
        /*
         * 轨迹表**不继承 BaseEntity**（它是 append-only 的，没有 updated_by / version / deleted），
         * 所以这两列没有任何地方替它填 —— 而 created_at 是 NOT NULL。
         * 漏掉的后果不是「少一列」：整个换单号动作会以 10500 收场。
         */
        t.setTenantNo("MAIN");
        t.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> traceMapper.insert(t));

        return toVO(s, tracesOf(shipmentNo));
    }

    // ---------------------------------------------------------------- 运费模板

    @Override
    public List<FreightTemplateVO> freightTemplates(boolean showArchived) {
        var w = Wrappers.<FulFreightTemplate>lambdaQuery()
                // 归档不是删除，得看得见（G1）
                .isNull(!showArchived, FulFreightTemplate::getArchivedAt)
                .orderByDesc(FulFreightTemplate::getIsDefault)
                .orderByDesc(FulFreightTemplate::getId);
        return DataScopeContext.executeWithoutScope(() -> templateMapper.selectList(w))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public FreightTemplateVO saveFreightTemplate(FreightTemplateCmd cmd, String operatorNo) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 负数运费/负重量存下去之后算价会得出负运费，那是白送还倒贴
        if (cmd.firstWeightGram() < 0 || cmd.firstFee() < 0
                || cmd.addWeightGram() < 0 || cmd.addFee() < 0 || cmd.freeThreshold() < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<FreightTemplateVO.OutOfRangeVO> ranges =
                cmd.outOfRange() == null ? List.of() : cmd.outOfRange();
        // 同一区域两条规则时，命中哪条取决于顺序 —— 那是随机行为，不是配置
        if (ranges.stream().map(FreightTemplateVO.OutOfRangeVO::region).distinct().count() != ranges.size()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        FulFreightTemplate row = cmd.templateNo() == null || cmd.templateNo().isBlank()
                ? null : requireTemplate(cmd.templateNo());
        boolean fresh = row == null;
        if (fresh) {
            row = new FulFreightTemplate();
            row.setTemplateNo(BizKey.next(BizKey.FREIGHT_TEMPLATE));
        }
        row.setName(cmd.name().trim());
        row.setFirstWeightGram(cmd.firstWeightGram());
        row.setFirstFee(cmd.firstFee());
        row.setAddWeightGram(cmd.addWeightGram());
        row.setAddFee(cmd.addFee());
        row.setFreeThreshold(cmd.freeThreshold());
        row.setIsDefault(cmd.isDefault() ? 1 : 0);
        row.setOutOfRange(writeJson(ranges));
        FulFreightTemplate toSave = row;
        DataScopeContext.executeWithoutScope(() ->
                fresh ? templateMapper.insert(toSave) : templateMapper.updateById(toSave));

        // 默认模板恰好一个：新的设成默认时把旧的摘掉，否则「默认是哪个」没有答案
        if (cmd.isDefault()) {
            for (FulFreightTemplate other : DataScopeContext.executeWithoutScope(() ->
                    templateMapper.selectList(Wrappers.<FulFreightTemplate>lambdaQuery()
                            .eq(FulFreightTemplate::getIsDefault, 1)
                            .ne(FulFreightTemplate::getTemplateNo, toSave.getTemplateNo())))) {
                other.setIsDefault(0);
                DataScopeContext.executeWithoutScope(() -> templateMapper.updateById(other));
            }
        }
        return toVO(row);
    }

    @Override
    @Transactional
    public FreightTemplateVO archiveFreightTemplate(String templateNo, String operatorNo) {
        FulFreightTemplate row = requireTemplate(templateNo);
        // 默认模板归档不了：归档之后新商家没有模板可用
        if (Integer.valueOf(1).equals(row.getIsDefault())) {
            throw BizException.of(ErrorCode.FREIGHT_DEFAULT_LOCKED);
        }
        row.setArchivedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> templateMapper.updateById(row));
        return toVO(row);
    }

    @Override
    @Transactional
    public FreightTemplateVO unarchiveFreightTemplate(String templateNo, String operatorNo) {
        FulFreightTemplate row = requireTemplate(templateNo);
        row.setArchivedAt(null);
        /*
         * **必须走 UpdateWrapper 显式 set(null)。**
         * MyBatis-Plus 的 updateById 默认 NOT_NULL 策略：置空的字段会被整条跳过 ——
         * 于是「取消归档」返回 200、archivedAt 在响应里也是 null，但库里那一列纹丝不动，
         * 刷新一下模板又回到归档列表里。归档能进不能出，等于软删除变成了硬删除。
         */
        DataScopeContext.executeWithoutScope(() -> templateMapper.update(null,
                Wrappers.<FulFreightTemplate>lambdaUpdate()
                        .set(FulFreightTemplate::getArchivedAt, null)
                        .set(FulFreightTemplate::getUpdatedBy, operatorNo)
                        .eq(FulFreightTemplate::getTemplateNo, templateNo)));
        return toVO(row);
    }

    // ---------------------------------------------------------------- 运力

    @Override
    public List<CarrierConfigVO> carriers() {
        // 按优先级升序 —— 页面上的顺序就是真实的选取顺序
        return DataScopeContext.executeWithoutScope(() ->
                        carrierMapper.selectList(Wrappers.<FulCarrier>lambdaQuery()
                                .orderByAsc(FulCarrier::getPriority)))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public CarrierConfigVO saveCarrier(String carrier, String name, int priority,
                                       String pickupCutoff, int slaHours, String operatorNo) {
        FulCarrier row = requireCarrier(carrier);
        /*
         * 优先级不许撞。
         *
         * 撞了之后「先选哪家」由数据库返回顺序决定 —— 那是随机的，
         * 而它决定每一单走哪家快递。
         */
        Long taken = DataScopeContext.executeWithoutScope(() ->
                carrierMapper.selectCount(Wrappers.<FulCarrier>lambdaQuery()
                        .eq(FulCarrier::getPriority, priority)
                        .ne(FulCarrier::getCarrier, carrier)));
        if (taken != null && taken > 0) {
            throw BizException.of(ErrorCode.CARRIER_PRIORITY_TAKEN);
        }
        if (name != null && !name.isBlank()) {
            row.setName(name.trim());
        }
        row.setPriority(priority);
        row.setPickupCutoff(pickupCutoff);
        row.setSlaHours(slaHours);
        DataScopeContext.executeWithoutScope(() -> carrierMapper.updateById(row));
        return toVO(row);
    }

    @Override
    @Transactional
    public CarrierConfigVO setCarrierEnabled(String carrier, boolean enabled, String operatorNo) {
        FulCarrier row = requireCarrier(carrier);
        if (enabled) {
            // 没配密钥就启用 = 单发出去、回传接不回来，而问题要到查件时才暴露
            if (!Integer.valueOf(1).equals(row.getApiKeyConfigured())) {
                throw BizException.of(ErrorCode.CARRIER_KEY_MISSING);
            }
        } else {
            // 还有在途单不能停：那些单的轨迹会就此断掉
            Long inFlight = DataScopeContext.executeWithoutScope(() ->
                    shipmentMapper.selectCount(Wrappers.<FulShipment>lambdaQuery()
                            .eq(FulShipment::getCarrier, carrier)
                            .in(FulShipment::getStatus, IN_FLIGHT)));
            if (inFlight != null && inFlight > 0) {
                throw BizException.of(ErrorCode.CARRIER_HAS_IN_FLIGHT);
            }
            // 不能停掉最后一家：停完之后所有快递单都发不出去
            Long others = DataScopeContext.executeWithoutScope(() ->
                    carrierMapper.selectCount(Wrappers.<FulCarrier>lambdaQuery()
                            .eq(FulCarrier::getEnabled, 1)
                            .ne(FulCarrier::getCarrier, carrier)));
            if (others == null || others == 0) {
                throw BizException.of(ErrorCode.CARRIER_LAST_ENABLED);
            }
        }
        row.setEnabled(enabled ? 1 : 0);
        DataScopeContext.executeWithoutScope(() -> carrierMapper.updateById(row));
        return toVO(row);
    }

    // ---------------------------------------------------------------- 装配

    private List<FulShipmentTrace> tracesOf(String shipmentNo) {
        return DataScopeContext.executeWithoutScope(() ->
                traceMapper.selectList(Wrappers.<FulShipmentTrace>lambdaQuery()
                        .eq(FulShipmentTrace::getShipmentNo, shipmentNo)
                        .orderByAsc(FulShipmentTrace::getAt)));
    }

    private FulShipment requireShipment(String shipmentNo) {
        FulShipment s = DataScopeContext.executeWithoutScope(() ->
                shipmentMapper.selectOne(Wrappers.<FulShipment>lambdaQuery()
                        .eq(FulShipment::getShipmentNo, shipmentNo).last("limit 1")));
        if (s == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return s;
    }

    private FulFreightTemplate requireTemplate(String templateNo) {
        FulFreightTemplate t = DataScopeContext.executeWithoutScope(() ->
                templateMapper.selectOne(Wrappers.<FulFreightTemplate>lambdaQuery()
                        .eq(FulFreightTemplate::getTemplateNo, templateNo).last("limit 1")));
        if (t == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return t;
    }

    private FulCarrier requireCarrier(String carrier) {
        FulCarrier c = DataScopeContext.executeWithoutScope(() ->
                carrierMapper.selectOne(Wrappers.<FulCarrier>lambdaQuery()
                        .eq(FulCarrier::getCarrier, carrier).last("limit 1")));
        if (c == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return c;
    }

    private ShipmentVO toVO(FulShipment s, List<FulShipmentTrace> traces) {
        return new ShipmentVO(s.getShipmentNo(), s.getSubOrderNo(), s.getCarrier(), s.getWaybillNo(),
                s.getStatus(), s.getReceiver(), s.getRegion(),
                IsoTime.toIso(s.getCreatedAt()), IsoTime.toIso(s.getUpdatedAt()),
                traces.stream().map(t -> new ShipmentVO.TraceVO(
                        IsoTime.toIso(t.getAt()), t.getText(), t.getLocation())).toList());
    }

    private FreightTemplateVO toVO(FulFreightTemplate t) {
        return new FreightTemplateVO(t.getTemplateNo(), t.getName(),
                nz(t.getFirstWeightGram()), nzL(t.getFirstFee()),
                nz(t.getAddWeightGram()), nzL(t.getAddFee()),
                nzL(t.getFreeThreshold()), Integer.valueOf(1).equals(t.getIsDefault()),
                readRanges(t.getOutOfRange()),
                IsoTime.toIso(t.getArchivedAt()), IsoTime.toIso(t.getUpdatedAt()), t.getUpdatedBy());
    }

    private CarrierConfigVO toVO(FulCarrier c) {
        return new CarrierConfigVO(c.getCarrier(), c.getName(),
                Integer.valueOf(1).equals(c.getEnabled()), nz(c.getPriority()),
                c.getAccountMasked(), Integer.valueOf(1).equals(c.getApiKeyConfigured()),
                c.getPickupCutoff(), nz(c.getSlaHours()),
                IsoTime.toIso(c.getUpdatedAt()), c.getUpdatedBy());
    }

    private List<FreightTemplateVO.OutOfRangeVO> readRanges(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return json.readValue(raw, new TypeReference<List<FreightTemplateVO.OutOfRangeVO>>() { });
    }

    private String writeJson(Object v) {
        return json.writeValueAsString(v);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nzL(Long v) {
        return v == null ? 0L : v;
    }
}
