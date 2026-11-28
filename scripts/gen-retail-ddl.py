#!/usr/bin/env python3
"""门店交易库 n_trade、行业库 n_industry 的建库建表语句：在字段草案生成的基础 DDL 上套用零售域第二段已定的改动。

用法：
    python3 scripts/gen-retail-ddl.py <基础 DDL 目录>          # 生成
    python3 scripts/gen-retail-ddl.py <基础 DDL 目录> --check  # 只核对产物是否最新

基础 DDL 是 ai-shop-slim 的 docs/technical/design/schema/ddl/05-n_trade.sql、06-n_industry.sql（字段草案生成，
14 个库共用一个生成器，那边不动）。改动的依据：
    docs/technical/零售域-第二段建议方案与表清单.md   A1（D1–D7 统一修法）、A2、A3、A4、A6、B1–B3、C
    docs/technical/零售域-状态短值换值表.md           状态列注释里的取值
美业服务执行的表取自 docs/technical/design/schema/retail-ddl/beauty-execution-tables.sql。
餐饮（TDD-餐饮域迁移新库）：餐饮在行业库的 27 张一律取 retail-ddl/food-industry-tables.sql 的定稿结构（与代码测过的一致）；
其中门店设备、厨房事件、语音指令取代基础 DDL 的 fnb_kds_event，ADR-068 修订一、二收回的 8 张按位置补上；
平台库去掉搬走的 mch_store_device。
服务定义（TDD-美业域迁移新库 §2.8，用户 2026-11-28 定）：交易库新增 prd_service_definition，取自
retail-ddl/service-definition-tables.sql，接在 prd_fulfillment_requirement 之后；后者加 definition_version。
产物：docs/technical/design/schema/ddl/01-n_platform.sql、05-n_trade.sql、06-n_industry.sql。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs/technical/design/schema/ddl'
BEAUTY = ROOT / 'docs/technical/design/schema/retail-ddl/beauty-execution-tables.sql'
FOOD_DEVICE = ROOT / 'docs/technical/design/schema/retail-ddl/food-industry-tables.sql'
# 设备出口（TDD-餐饮域迁移新库 §2.8）：行业库里被取代的表 -> 取代它的表（放在原位置）
INDUSTRY_REPLACED = {'fnb_kds_subscription': ['ind_store_device', 'fnb_kds_subscription'],
                     'fnb_kds_event': ['fnb_kitchen_event', 'fnb_kds_voice_command']}
INDUSTRY_ADDED = {  # ADR-068 修订一、二收回行业库的表：接在哪张表之后
    'fnb_kitchen_ticket': ['fnb_kitchen_task'],
    'fnb_table': ['fnb_table_occupancy', 'fnb_table_operation', 'fnb_reservation', 'fnb_reservation_table',
                  'fnb_service_slot', 'fnb_capacity_claim', 'fnb_service_calendar'],
}
PLATFORM_MOVED = 'mch_store_device'  # 平台库搬到行业库的表
SERVICE_DEFINITION = ROOT / 'docs/technical/design/schema/retail-ddl/service-definition-tables.sql'
TRADE_ADDED = {  # 交易库新增的表（取自定稿结构文件，原样保留）：接在哪张表之后
    'prd_fulfillment_requirement': ['prd_service_definition'],
}
STATUS = ROOT / 'docs/technical/零售域-状态短值换值表.md'

# ---------- 表的去留（B1、B3、C1） ----------
TRADE_TO_INDUSTRY = {  # 交易库移到行业库：旧名 -> 新名
    'ord_assignment': 'ind_assignment',
    'ord_queue_ticket': 'fnb_queue_ticket',
    'ord_custody': 'fnb_custody',
    'ord_custody_movement': 'fnb_custody_movement',
    'ord_commission_rule': 'ind_commission_rule',
}
TRADE_DROP = {
    'ord_address': '收货地址属于用户资料，归平台基础库（C1）',
    'prd_spec_value_usage': '并入 prd_spec_usage（C1）',
}
TABLE_COMMENT = {
    'ind_assignment': '人员指派与交接：谁在负责这张到店单、这个服务任务；美业服务参与、餐饮服务交接与责任都记在这里，交接就是换一条',
    'ind_commission_rule': '提成规则：按什么算、多少；每行一版，带生效起止，提成按当时那一版算。提成在行业库计提，不进账务结算库（只管支付收款）',
}

# ---------- 加列（B2 等） ----------
ADD_COLUMNS = {
    # 服务的要求挂服务定义版本，非服务留空；revision_no 仍只表示商品修订号（用户 2026-11-28 定，TDD-美业域迁移新库 §2.8）
    'prd_fulfillment_requirement': [
        "`definition_version` int DEFAULT NULL COMMENT '所属服务定义版本（prd_service_definition）；服务的要求挂这一列，非服务为空'",
    ],
    'ord_price_quote': [
        "`confirmation_no` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '顾客确认编号（原柜台结账确认）'",
        "`confirmed_at` datetime(3) DEFAULT NULL COMMENT '顾客确认时间'",
        "`confirmed_by` varchar(64) DEFAULT NULL COMMENT '确认人'",
    ],
    'ord_payment': [
        "`request_key` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '调用方业务身份键（同一来源同一键只记一笔）'",
        "`offline_request_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '线下记账原请求摘要'",
        "`offline_history` json NULL COMMENT '线下记账创建、更正、作废和退款留痕'",
    ],
    'ord_refund_obligation': [
        "`input_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '义务输入摘要（防重放）'",
    ],
    'ord_after_sale_item': [
        "`coupon_return_minor` bigint NOT NULL DEFAULT 0 COMMENT '本行本次退回的券额（分）：分到本行的券额按已退件数累计取整，补贴结算与对账用'",
    ],
    'ord_execution': [
        "`source_type` varchar(16) DEFAULT NULL COMMENT '执行来源：ORDER 订单、MANUAL 手工受理'",
        "`source_no` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '来源编号'",
    ],
    'ord_fulfillment': [
        "`evidence_json` json NULL COMMENT '渠道履约证据（原渠道履约关联与证据账本）'",
    ],
    'rsv_booking': [
        "`current_key` varchar(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS ((case when (`is_current` = 1) then `source_no` end)) STORED COMMENT '当前占用的仲裁键：当前行等于来源编号，否则为空（取代 mch_booking_owner）'",
    ],
    'prd_stock_lock': [
        "`source_manifest_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '来源清单摘要'",
        "`lifecycle_version` int NOT NULL DEFAULT 0 COMMENT '生命周期版本'",
        "`confirmation_operation_no` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '确认操作号'",
        "`claim_no` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '餐饮限量领用编号（并入）'",
    ],
    'prd_spec_usage': [
        "`value_no` varchar(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '规格值编号；为空表示整个维度（并入原规格值使用范围）'",
        "`label_override` varchar(64) DEFAULT NULL COMMENT '改叫的名字'",
        "`favorite` tinyint NOT NULL DEFAULT 0 COMMENT '是否常用'",
    ],
    'prd_store_catalog': [
        # 不用 json 类型：MySQL 会把 JSON 规范化（键排序、去空白），读回来的文本与写入时不同，
        # 快照摘要按写入文本算，读出即对不上（商品领域迁移批次 2-g，用户 2026-11-28 确认改 DDL）
        "`frozen_content` mediumtext NULL COMMENT '冻结内容（结账对账用）：发布时的原文，快照摘要按它算'",
        "`snapshot_hash` char(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '快照摘要'",
    ],
    'ord_appointment': [
        "`work_order_no` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '已开的服务工单（行业库）；预约侧的护栏看这一列'",
        # 取消与爽约的凭证（原来只记在美业操作日志里，日志退役后归回预约；TDD-美业域迁移新库 §2.6 第 12 项，用户 2026-11-28 定）
        "`cancel_evidence_ref` varchar(128) DEFAULT NULL COMMENT '取消或爽约的凭证引用（如登记爽约时的证明材料）；没有为空'",
    ],
}

# ---------- 改列（整行替换基础 DDL 里的列定义） ----------
REPLACE_COLUMNS = {
    # 取消原因放宽到 500：取消与爽约请求的原因最长 500 字，原来只记在美业操作日志里（TDD-美业域迁移新库 §2.6 第 12 项）
    ('ord_appointment', 'cancel_reason'): "`cancel_reason` varchar(500) DEFAULT NULL COMMENT '取消或爽约原因'",
}

# ---------- 唯一约束（B2、附录一的降级与无处安放） ----------
ADD_UNIQUE = {
    'ord_payment': [('record_no',), ('entity_no', 'source', 'request_key'), ('pay_order_no',), ('redemption_no',), ('reversal_no',)],
    'ord_refund_obligation': [('execution_no', 'kind', 'source_no')],
    'ord_execution': [('execution_no',), ('unit_no', 'attempt_no')],
    'ord_fulfillment': [('fulfillment_no',)],
    'rsv_booking': [('entity_no', 'hold_no'), ('entity_no', 'store_no', 'source_type', 'current_key')],
    'ord_visit_attendee': [('attendee_no',)],
    'ord_sub_order': [('sub_order_no',), ('pickup_no', 'verify_code')],
    'ord_order': [('order_no',)],
    'prd_stock_lock': [('restriction_no', 'batch_no'), ('claim_no',)],
    'prd_spec_usage': [('scope_type', 'scope_ref', 'dim_no', 'value_no')],
    'ind_assignment': [('assignment_no',)],
    'chn_order': [('channel_store_key', 'external_order_key')],
    'chn_connection': [('connection_no',)],
    'ord_appointment': [('tenant_no', 'entity_no', 'work_order_no')],
    'ord_split_share': [('share_no',)],
    'prd_sku_barcode': [('entity_no', 'barcode')],
    'ord_cart_item': [('cart_item_no',)],
    'ord_item': [('sub_order_no', 'line_no')],
    'prd_catalog_item_sku': [('catalog_item_no', 'sku_no')],
    # 商品领域迁移批次 2-g（用户 2026-11-28 确认）：目录按版本一行，保留定时生效；版本号即并发闸门
    'prd_store_catalog': [('tenant_no', 'entity_no', 'catalog_no', 'version_no'), ('tenant_no', 'catalog_version_no')],
    # 门店级的上下架行（原 prd_store_goods）：目录版本号存空串，一店一商品一行
    'prd_catalog_item': [('tenant_no', 'store_no', 'goods_no', 'catalog_version_no')],
    'prd_fulfillment_requirement': [('requirement_no', 'revision_no')],
    'ind_commission_rule': [('rule_no', 'version_no')],
}
# 去掉的唯一约束：prd_store_catalog 一店一行装不下按版本存的目录（定时生效要并存多版）
DROP_KEYS = {
    'prd_store_catalog': ['uk_prd_catalog_no', 'uk_prd_catalog_scope'],
}
ADD_INDEX = {
    'prd_fulfillment_requirement': [('goods_no', 'definition_version')],
    'ind_assignment': [('target_type', 'target_no'), ('entity_no', 'store_no', 'planned_start_at')],
    'ord_visit_attendee': [('check_no',)],
}

# ---------- D5、D6、D7 的列规则 ----------
WIDE = re.compile(r'^(created_by|updated_by|confirmed_by|actor_no|operator_no|request_key|client_order_no|channel_order_no'
                  r'|external_[a-z_]+|[a-z_]*_external_[a-z_]+|[a-z_]*_ref|trade_no|pay_trade_no|pay_order_no|out_trade_no'
                  r'|channel_store_key|external_order_key)$')
NULL_MEANS = re.compile(r"COMMENT '[^']*(空表示|未配时|可不填)")
MONEY_COUNT = re.compile(r'^([a-z_]*_minor|[a-z_]*_qty|qty|quantity|[a-z_]*_count|count|[a-z_]*_units|units|persons'
                         r'|party_size|seats|capacity|[a-z_]*_persons)$')
BIN_COLLATE = re.compile(r'^([a-z_]*_no|[a-z_]*_key|[a-z_]*_hash|code|[a-z_]*_code)$')


def status_map():
    text = STATUS.read_text(encoding='utf-8')
    body = text[text.index('## 3 换值表'):text.index('## 4 ')]
    pairs = re.findall(r'^\| `([A-Z_]+)` \| \d+ \| `([A-Z_]+)`', body, re.M)
    return dict(pairs)


def parse(sql):
    head, tables = [], {}
    order = []
    pos = 0
    for m in re.finditer(r'CREATE TABLE `(\w+)` \((.*?)\n\)([^;]*);', sql, re.S):
        if not order:
            head = sql[:m.start()].rstrip('\n').split('\n')
        name, inner, tail = m.group(1), m.group(2), m.group(3)
        items = [l for l in re.split(r',\n(?=\s*(?:`|PRIMARY|UNIQUE|KEY|FULLTEXT|CONSTRAINT|INDEX))', inner.strip('\n'))]
        cols = [i for i in items if i.strip().startswith('`')]
        keys = [i for i in items if not i.strip().startswith('`')]
        tables[name] = {'cols': cols, 'keys': keys, 'tail': tail}
        order.append(name)
        pos = m.end()
    return head, tables, order


def col_name(line):
    return re.match(r'\s*`(\w+)`', line).group(1)


def fix_column(table, line, statuses):
    name = col_name(line)
    ln = line
    # D6：乐观锁、时间精度
    if name == 'version':
        ln = re.sub(r'`version` int\b', '`version` bigint', ln)
    if name == 'created_at':
        ln = re.sub(r'`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP\b',
                    '`created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)', ln)
    if name == 'updated_at':
        ln = re.sub(r'`updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP\b',
                    '`updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)', ln)
    # D5：外部来源号、人员列放宽到 64
    if WIDE.match(name) or (table, name) == ('ord_payment', 'biz_no'):
        ln = re.sub(r'`%s` varchar\((\d+)\)' % name,
                    lambda m: '`%s` varchar(%d)' % (name, max(64, int(m.group(1)))), ln)
    # D6：金额与计数不为空、默认 0。例外：注释写明「空」有含义的（空表示…、未配时…、可不填）保持可空 ——
    # 0 与空含义不同（团购价 0 元 ≠ 未开拼团，可售量 0 ≠ 按规则换算），用户 2026-11-27 确认
    if MONEY_COUNT.match(name) and not NULL_MEANS.search(ln) and re.search(r'`%s` (bigint|int|smallint|tinyint|decimal\([0-9,]+\))( unsigned)? DEFAULT NULL' % name, ln):
        ln = re.sub(r'(`%s` (?:bigint|int|smallint|tinyint|decimal\([0-9,]+\))(?: unsigned)?) DEFAULT NULL' % name,
                    r'\1 NOT NULL DEFAULT 0', ln)
    # D7：编号、键、摘要按二进制比较
    if BIN_COLLATE.match(name) and re.search(r'`%s` (var)?char\(\d+\) (DEFAULT|NOT|NULL)' % name, ln):
        ln = re.sub(r'(`%s` (?:var)?char\(\d+\)) ' % name, r'\1 COLLATE utf8mb4_bin ', ln)
    # D4：状态列注释里的取值换成短值
    if re.search(r'`%s` varchar\(10\)' % name, ln):
        for long, short in statuses.items():
            ln = re.sub(r'\b%s\b' % long, short, ln)
    return ln


def fix_keys(table, keys, cols):
    out = []
    names = {col_name(c) for c in cols}
    have = set()
    for k in keys:
        dm = re.search(r'KEY `([^`]+)`', k)
        if dm and dm.group(1) in DROP_KEYS.get(table, []):
            continue
        k2 = k
        m = re.match(r'(\s*)KEY `(uk_[^`]+)` \((.*)\)$', k)
        if m:  # D1：以 uk_ 命名的一律是唯一约束
            k2 = '%sUNIQUE KEY `%s` (%s)' % (m.group(1), m.group(2), m.group(3))
        cm = re.search(r'\((.*)\)$', k2)
        if cm and ('UNIQUE' in k2):
            have.add(tuple(re.findall(r'`(\w+)`', cm.group(1))))
        out.append(k2)
    for cols_ in ADD_UNIQUE.get(table, []):
        missing = [c for c in cols_ if c not in names]
        if missing:
            raise SystemExit('%s: unique key columns missing %s' % (table, missing))
        if cols_ in have or (('tenant_no',) + cols_) in have:
            continue
        out.append('  UNIQUE KEY `uk_%s_%s` (%s)' % (table, '_'.join(c[:12] for c in cols_)[:40], ', '.join('`%s`' % c for c in cols_)))
    for cols_ in ADD_INDEX.get(table, []):
        out.append('  KEY `idx_%s_%s` (%s)' % (table, '_'.join(c[:12] for c in cols_)[:40], ', '.join('`%s`' % c for c in cols_)))
    return out


OLD_SCHEMA = ROOT / 'backend/shop-app/src/test/resources/schema-test.sql'
# 不照搬旧库的默认值：旧库的市场、币种默认是大陆时期的 CN / CNY，当前主场景是台湾（部署配置
# SHOP_DEFAULT_MARKET=TW，正式库订单币种全是 TWD）。代码里的「本市场」读同一个部署配置，不写死。
DEFAULT_OVERRIDES = {'market': "'TW'", 'currency': "'TWD'"}
MANIFEST = ROOT / 'docs/technical/design/schema/db-split-manifest.csv'


def old_defaults():
    """旧库每张表的列默认值：表 -> 列 -> (默认值, 是否 NOT NULL)。

    来源是按旧迁移重放出来的 schema-test.sql（可复现、离线可查）。字段草案生成器没有带默认值，
    而代码大量依赖库里的默认值（插入时不写 tenant_no、status、audit_status……），丢了就是行为变了。
    新表名到旧表名按拆库清单的「清理后表名」和本文件的 TRADE_TO_INDUSTRY 反查。
    """
    import csv
    old = {}
    pat = re.compile(r'CREATE TABLE IF NOT EXISTS (\w+)\s*\((.*?)\n\);', re.S)
    for m in pat.finditer(OLD_SCHEMA.read_text(encoding='utf-8')):
        cols = {}
        for line in m.group(2).split('\n'):
            c = re.match(r'\s*`?(\w+)`?\s+\S+(.*)', line)
            if not c or c.group(1).upper() in ('PRIMARY', 'UNIQUE', 'KEY', 'CONSTRAINT', 'INDEX'):
                continue
            d = re.search(r"DEFAULT\s+('(?:[^']|'')*'|[-\w.()]+)", c.group(2), re.I)
            cols[c.group(1).lower()] = (d.group(1) if d else None, 'NOT NULL' in c.group(2).upper())
        old[m.group(1)] = cols
    sources = {}
    with open(MANIFEST, encoding='utf-8') as f:
        for row in csv.DictReader(f):
            new = (row.get('清理后表名') or '').strip()
            if new and row.get('处置') in ('保留', '改归属', '改名'):
                sources.setdefault(new, []).append(row['表'])
    for old_name, new_name in TRADE_TO_INDUSTRY.items():
        sources.setdefault(new_name, []).append(old_name)
    return {'tables': old, 'sources': sources}


def restore_default(table, line, olddefs, statuses):
    """新列没有默认值、旧库同名列有字面量默认值时补回（不改类型；旧列 NOT NULL 就一并补上）。"""
    name = col_name(line)
    if re.search(r'AUTO_INCREMENT|GENERATED ALWAYS', line, re.I):
        return line
    if re.search(r"\bDEFAULT\s+(?!NULL\b)", line, re.I):
        return line
    found = None
    for src in [table] + olddefs['sources'].get(table, []):
        col = olddefs['tables'].get(src, {}).get(name)
        if col and col[0] is not None and col[0].upper() != 'NULL':
            found = col
            break
    if not found:
        return line
    value, not_null = found
    if re.search(r'CURRENT_TIMESTAMP|NOW\(', value, re.I):
        return line                         # 时间列由公共字段规则管，不在这里补
    value = {'TRUE': '1', 'FALSE': '0'}.get(value.upper(), value)
    q = re.match(r"'([A-Z_]+)'$", value)
    if q and q.group(1) in statuses:       # 默认值是被换成短值的状态，跟着换
        value = "'%s'" % statuses[q.group(1)]
    if name in DEFAULT_OVERRIDES:          # 旧库是大陆时期的默认值，按当前主场景改（用户 2026-11-27）
        value = DEFAULT_OVERRIDES[name]
    body = re.sub(r'\s+DEFAULT\s+NULL\b', '', line, flags=re.I)
    m = re.match(r"(\s*`\w+`\s+\w+(?:\([^)]*\))?(?:\s+unsigned)?)((?:\s+(?:CHARACTER SET|COLLATE)\s+\w+)*)(\s+NOT NULL)?(.*)$",
                 body)
    if not m:
        return line
    head, charset, nn, rest = m.group(1), m.group(2) or '', m.group(3), m.group(4) or ''
    nn = ' NOT NULL' if (nn or not_null) else ''
    return '%s%s%s DEFAULT %s%s' % (head, charset, nn, value, rest)


def ensure_deleted(t):
    if not any(col_name(c) == 'deleted' for c in t['cols']):
        i = next(k for k, c in enumerate(t['cols']) if col_name(c) == 'version')
        t['cols'].insert(i, "  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常、1 已删除'")


def render(name, t):
    tail = t['tail']
    if name in TABLE_COMMENT:
        tail = re.sub(r"COMMENT='[^']*'", "COMMENT='%s'" % TABLE_COMMENT[name], tail)
    return 'CREATE TABLE `%s` (\n%s\n)%s;\n' % (name, ',\n'.join(t['cols'] + t['keys']), tail)


def build(base_dir):
    statuses = status_map()
    th, trade, torder = parse((base_dir / '05-n_trade.sql').read_text(encoding='utf-8'))
    ih, ind, iorder = parse((base_dir / '06-n_industry.sql').read_text(encoding='utf-8'))
    # 表的去留
    moved = []
    for old, new in TRADE_TO_INDUSTRY.items():
        ind[new] = trade.pop(old)
        torder.remove(old)
        moved.append(new)
    for old in TRADE_DROP:
        trade.pop(old)
        torder.remove(old)
    iorder += moved
    _, beauty, border = parse(BEAUTY.read_text(encoding='utf-8'))
    for name in border:
        if name in ind:
            raise SystemExit('beauty table already in industry: ' + name)
        ind[name] = beauty[name]
    iorder += border
    # 设备出口：取代的表放在原位置，结构原样保留
    _, device, _ = parse(FOOD_DEVICE.read_text(encoding='utf-8'))
    for old, news in INDUSTRY_REPLACED.items():
        k = iorder.index(old)
        ind.pop(old)
        iorder[k:k + 1] = news
        for n in news:
            ind[n] = device[n]
    for after, news in INDUSTRY_ADDED.items():
        k = iorder.index(after) + 1
        iorder[k:k] = news
        for n in news:
            ind[n] = device[n]
    # 其余餐饮表也以定稿结构为准（代码测过的结构，含按 ADR-068 第 4 条补回的防重列与唯一键）
    for n in device:
        if n not in ind:
            raise SystemExit('food table not placed in n_industry: ' + n)
        ind[n] = device[n]
    fixed = border + list(device)
    # 交易库新增的表：定稿结构原样并入
    _, added, aorder = parse(SERVICE_DEFINITION.read_text(encoding='utf-8'))
    for after, news in TRADE_ADDED.items():
        k = torder.index(after) + 1
        torder[k:k] = news
        for n in news:
            if n in trade:
                raise SystemExit('trade table already exists: ' + n)
            trade[n] = added[n]
    fixed += aorder
    ensure_deleted(ind['ind_assignment'])
    # 改列
    for (name, column), line in REPLACE_COLUMNS.items():
        t = trade.get(name) or ind.get(name)
        k = next((k for k, c in enumerate(t['cols']) if col_name(c) == column), None) if t else None
        if k is None:
            raise SystemExit('replace column: unknown %s.%s' % (name, column))
        t['cols'][k] = '  ' + line + (',' if t['cols'][k].rstrip().endswith(',') else '')
    # 加列
    for name, cols in ADD_COLUMNS.items():
        t = trade.get(name) or ind.get(name)
        if t is None:
            raise SystemExit('add columns: unknown table ' + name)
        have = {col_name(c) for c in t['cols']}
        anchor = next((k for k, c in enumerate(t['cols']) if col_name(c) == 'version'), len(t['cols']))
        for c in cols:
            n = re.match(r'`(\w+)`', c).group(1)
            if n in have:
                raise SystemExit('%s.%s already exists' % (name, n))
            t['cols'].insert(anchor, '  ' + c)
            anchor += 1
    # 列规则与键
    olddefs = old_defaults()
    for tables in (trade, ind):
        for name, t in tables.items():
            if name in fixed:
                continue  # 美业执行表、设备出口表取自实际结构，列与约束原样保留
            t['cols'] = [fix_column(name, c, statuses) for c in t['cols']]
            t['cols'] = [restore_default(name, c, olddefs, statuses) for c in t['cols']]
            t['keys'] = fix_keys(name, t['keys'], t['cols'])
    header = ('-- 门店交易库 n_trade：建库建表（零售域第二段定稿，ADR-068）。由 scripts/gen-retail-ddl.py 生成，不要手改。\n'
              '-- 基础：ai-shop-slim 字段草案生成的 05-n_trade.sql；改动：方案与表清单 A1–A6、B1–B2、C1，状态短值换值表。\n\n'
              'CREATE DATABASE IF NOT EXISTS `n_trade` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n'
              'USE `n_trade`;\n')
    trade_sql = header + '\n' + '\n'.join(render(n, trade[n]) for n in torder)
    header = ('-- 行业库 n_industry：建库建表（零售域第二段定稿，ADR-068）。由 scripts/gen-retail-ddl.py 生成，不要手改。\n'
              '-- 基础：ai-shop-slim 字段草案生成的 06-n_industry.sql；加上从交易库移来的 5 张与美业服务执行的 9 张（B3、A6）；\n'
              '-- 餐饮（TDD-餐饮域迁移新库）：门店设备从平台库搬来，厨显订阅精简，厨房事件取代厨显事件；\n'
              '-- 制作任务、占台、换台记录、订位、订位占桌、时段、容量占用、营业日历按 ADR-068 修订一、二归行业库。\n\n'
              'CREATE DATABASE IF NOT EXISTS `n_industry` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n'
              'USE `n_industry`;\n')
    ind_sql = header + '\n' + '\n'.join(render(n, ind[n]) for n in iorder)
    # 平台库：基础 DDL 原样，只去掉搬到行业库的门店设备
    platform = (base_dir / '01-n_platform.sql').read_text(encoding='utf-8')
    m = re.search(r'CREATE TABLE `%s` \(.*?\n\)[^;]*;\n\n?' % PLATFORM_MOVED, platform, re.S)
    if m is None:
        raise SystemExit('platform: %s not found' % PLATFORM_MOVED)
    lines = platform[:m.start()].split('\n')
    lines.insert(2, '-- 门店设备 %s 已搬到行业库 ind_store_device（TDD-餐饮域迁移新库 §2.8），由 scripts/gen-retail-ddl.py 从基础 DDL 去掉。' % PLATFORM_MOVED)
    platform_sql = '\n'.join(lines) + platform[m.end():]
    return platform_sql, trade_sql, ind_sql, len(torder), len(iorder)


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    base = Path(sys.argv[1])
    platform_sql, trade_sql, ind_sql, nt, ni = build(base)
    targets = {OUT / '01-n_platform.sql': platform_sql, OUT / '05-n_trade.sql': trade_sql,
               OUT / '06-n_industry.sql': ind_sql}
    if '--check' in sys.argv:
        stale = [str(p) for p, s in targets.items() if not p.exists() or p.read_text(encoding='utf-8') != s]
        if stale:
            raise SystemExit('stale: ' + ', '.join(stale))
        print('ok: n_trade %d tables, n_industry %d tables' % (nt, ni))
        return
    OUT.mkdir(parents=True, exist_ok=True)
    for p, s in targets.items():
        p.write_text(s, encoding='utf-8')
    print('n_trade %d tables, n_industry %d tables' % (nt, ni))


if __name__ == '__main__':
    main()
