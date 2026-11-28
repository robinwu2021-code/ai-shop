#!/usr/bin/env python3
"""商品领域搬迁：旧库 → 门店交易库 + 会员营销库（TDD-商品领域迁到门店交易库 §2.5）。

用法：
    python3 scripts/migrate-product-to-n-trade.py --src ai_shop_ume --trade n_trade_v2 --member n_member \\
        --mysql "mysql -uroot -h127.0.0.1"            # 搬迁 + 对账报告
    python3 scripts/migrate-product-to-n-trade.py ... --report-only   # 只出对账报告
    python3 scripts/migrate-product-to-n-trade.py ... --print         # 只打印换好库名的 SQL，不执行

--mysql 是能连到目标实例的 mysql 批处理命令（三库须在同一实例）；.61 上用 root，经跳板机时把整条
ssh … docker exec … mysql -uroot 写进去。搬迁 SQL 在 backend/scripts/migrate-product-to-n-trade.sql：
一个事务，检查不过整批回滚；要求目标表为空。
"""
import argparse
import re

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SQL = ROOT / 'backend/scripts/migrate-product-to-n-trade.sql'
NAME = re.compile(r'^[A-Za-z0-9_]+$')

REPORT = """
SELECT 'prd_category' AS `项`, (SELECT COUNT(*) FROM {src}.prd_category) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_category) AS `新`
UNION ALL SELECT 'prd_goods' AS `项`, (SELECT COUNT(*) FROM {src}.prd_goods) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_goods) AS `新`
UNION ALL SELECT 'prd_sku' AS `项`, (SELECT COUNT(*) FROM {src}.prd_sku) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_sku) AS `新`
UNION ALL SELECT 'prd_spu_std → prd_standard_goods' AS `项`, (SELECT COUNT(*) FROM {src}.prd_spu_std) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_standard_goods) AS `新`
UNION ALL SELECT 'prd_sell_rule → prd_sellable_rule' AS `项`, (SELECT COUNT(*) FROM {src}.prd_sell_rule) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_sellable_rule) AS `新`
UNION ALL SELECT 'prd_spec_dim' AS `项`, (SELECT COUNT(*) FROM {src}.prd_spec_dim) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_spec_dim) AS `新`
UNION ALL SELECT 'prd_spec_value' AS `项`, (SELECT COUNT(*) FROM {src}.prd_spec_value) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_spec_value) AS `新`
UNION ALL SELECT '类目绑定 → prd_spec_usage CATEGORY 维度级' AS `项`, (SELECT COUNT(*) FROM {src}.prd_category_spec WHERE deleted=0) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_spec_usage WHERE scope_type='CATEGORY' AND value_no='') AS `新`
UNION ALL SELECT '类目取值子集 → CATEGORY 取值级' AS `项`, (SELECT COUNT(*) FROM {src}.prd_category_spec_value WHERE deleted=0) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_spec_usage WHERE scope_type='CATEGORY' AND value_no<>'') AS `新`
UNION ALL SELECT '商家覆盖 → MCH_CATEGORY' AS `项`, (SELECT COUNT(*) FROM {src}.prd_merchant_spec_override WHERE deleted=0) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_spec_usage WHERE scope_type='MCH_CATEGORY') AS `新`
UNION ALL SELECT '商家常用 → MERCHANT' AS `项`, (SELECT COUNT(*) FROM {src}.prd_merchant_spec WHERE deleted=0)
       + (SELECT COUNT(*) FROM {src}.prd_merchant_spec_value WHERE deleted=0) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_spec_usage WHERE scope_type='MERCHANT') AS `新`
UNION ALL SELECT '平台品类兜底模板 → CATEGORY_TYPE 维度级' AS `项`, (SELECT COUNT(*) FROM {src}.prd_spec_template
        WHERE deleted=0 AND scope='PLATFORM' AND category_no IS NULL) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_spec_usage WHERE scope_type='CATEGORY_TYPE' AND value_no='') AS `新`
UNION ALL SELECT '  其中名字对不上平台维度的模板（未搬，需人工）' AS `项`, (SELECT COUNT(*) FROM {src}.prd_spec_template t
        WHERE t.deleted=0 AND t.scope='PLATFORM' AND t.category_no IS NULL AND NOT EXISTS (
          SELECT 1 FROM {trade}.prd_spec_dim d WHERE d.scope='PLATFORM' AND d.name=t.name AND d.deleted=0)) AS `旧`, '' AS `新`
UNION ALL SELECT '类目级平台模板（不搬：类目选定后只用类目绑定）' AS `项`, (SELECT COUNT(*) FROM {src}.prd_spec_template
        WHERE deleted=0 AND scope='PLATFORM' AND category_no IS NOT NULL) AS `旧`, '' AS `新`
UNION ALL SELECT '商家自存模板（未搬，需人工转为自建维度）' AS `项`, (SELECT COUNT(*) FROM {src}.prd_spec_template
        WHERE deleted=0 AND scope='MERCHANT') AS `旧`, '' AS `新`
UNION ALL SELECT 'prd_goods_revision（含草稿）' AS `项`, (SELECT COUNT(*) FROM {src}.prd_goods_revision)
       + (SELECT COUNT(*) FROM {src}.prd_goods_draft WHERE deleted=0) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_goods_revision) AS `新`
UNION ALL SELECT '  其中草稿（EDITING/SUBMITTED）' AS `项`, (SELECT COUNT(*) FROM {src}.prd_goods_draft WHERE deleted=0) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_goods_revision WHERE status IN ('EDITING','SUBMITTED')) AS `新`
UNION ALL SELECT '门店价 + 店级库存 + 库存池 → prd_store_sku' AS `项`, (SELECT COUNT(*) FROM {src}.prd_store_price WHERE deleted=0)
       + (SELECT COUNT(*) FROM {src}.prd_store_stock WHERE deleted=0) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_store_sku) AS `新`
UNION ALL SELECT '  主体级库存（旧 SKU 数）→ 新：并进单店本店行 + 库存池' AS `项`, (SELECT COUNT(DISTINCT sku_no) FROM {src}.prd_sku WHERE deleted=0 AND stock IS NOT NULL) AS `旧`, CONCAT((SELECT COUNT(DISTINCT s.sku_no) FROM {trade}.prd_store_sku s WHERE s.store_no<>'' AND s.sellable_qty IS NOT NULL AND NOT EXISTS (SELECT 1 FROM {src}.prd_store_stock st WHERE st.deleted=0 AND st.store_no=s.store_no AND st.sku_no=s.sku_no)), ' + ', (SELECT COUNT(*) FROM {trade}.prd_store_sku WHERE store_no='')) AS `新`
UNION ALL SELECT '  库存总件数 / 锁量（旧：主体 + 店级）' AS `项`, CONCAT((SELECT COALESCE(SUM(st),0) FROM (SELECT MIN(stock) st FROM {src}.prd_sku WHERE deleted=0 GROUP BY sku_no) a)
              + (SELECT COALESCE(SUM(stock),0) FROM {src}.prd_store_stock WHERE deleted=0), ' / ',
              (SELECT COALESCE(SUM(lk),0) FROM (SELECT MIN(locked_stock) lk FROM {src}.prd_sku WHERE deleted=0 GROUP BY sku_no) b)
              + (SELECT COALESCE(SUM(locked_stock),0) FROM {src}.prd_store_stock WHERE deleted=0)) AS `旧`, CONCAT((SELECT COALESCE(SUM(sellable_qty),0) FROM {trade}.prd_store_sku), ' / ',
              (SELECT COALESCE(SUM(locked_qty),0) FROM {trade}.prd_store_sku)) AS `新`
UNION ALL SELECT 'prd_store_catalog_version → prd_store_catalog' AS `项`, (SELECT COUNT(*) FROM {src}.prd_store_catalog_version) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_store_catalog) AS `新`
UNION ALL SELECT 'prd_store_goods → prd_catalog_item' AS `项`, (SELECT COUNT(*) FROM {src}.prd_store_goods) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_catalog_item WHERE catalog_version_no='') AS `新`
UNION ALL SELECT 'prd_entity_category_inv → prd_store_category 主体级' AS `项`, (SELECT COUNT(*) FROM {src}.prd_entity_category_inv WHERE deleted=0) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_store_category WHERE store_no='') AS `新`
UNION ALL SELECT 'prd_stock_lock' AS `项`, (SELECT COUNT(*) FROM {src}.prd_stock_lock) AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_stock_lock) AS `新`
UNION ALL SELECT 'prd_goods_favorite → mbr_favorite GOODS' AS `项`, (SELECT COUNT(*) FROM {src}.prd_goods_favorite WHERE deleted=0) AS `旧`, (SELECT COUNT(*) FROM {member}.mbr_favorite WHERE favorite_type='GOODS') AS `新`
UNION ALL SELECT 'prd_topic' AS `项`, (SELECT COUNT(*) FROM {src}.prd_topic) AS `旧`, (SELECT COUNT(*) FROM {member}.prd_topic) AS `新`
UNION ALL SELECT 'prd_topic_goods' AS `项`, (SELECT COUNT(*) FROM {src}.prd_topic_goods WHERE deleted=0) AS `旧`, (SELECT COUNT(*) FROM {member}.prd_topic_goods) AS `新`
UNION ALL SELECT 'SKU 市场 CN 改写为 TW' AS `项`, (SELECT COUNT(*) FROM {src}.prd_sku WHERE market='CN') AS `旧`, (SELECT COUNT(*) FROM {trade}.prd_sku WHERE market='CN') AS `新`;
"""


def render(text, args):
    return text.replace('{src}', args.src).replace('{trade}', args.trade).replace('{member}', args.member)


def run(cmd, sql):
    # 整条命令交给 bash：本机 mysql 与经跳板机的 ssh … mysql 一样写法；SQL 从标准输入进
    proc = subprocess.run(['bash', '-c', cmd], input=sql, text=True, capture_output=True)
    sys.stdout.write(proc.stdout)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit('mysql 返回 %d：整批已回滚（事务内）' % proc.returncode)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--src', required=True, help='旧库名，如 ai_shop_ume')
    p.add_argument('--trade', required=True, help='门店交易库名，如 n_trade_v2')
    p.add_argument('--member', required=True, help='会员营销库名，如 n_member')
    p.add_argument('--mysql', default='mysql', help='mysql 批处理命令')
    g = p.add_mutually_exclusive_group()
    g.add_argument('--report-only', action='store_true')
    g.add_argument('--print', action='store_true')
    args = p.parse_args()
    for n in (args.src, args.trade, args.member):
        if not NAME.match(n):
            raise SystemExit('库名只能是字母数字下划线：' + n)
    migrate = render(SQL.read_text(encoding='utf-8'), args)
    report = render(REPORT, args)
    if args.print:
        print(migrate)
        return
    if not args.report_only:
        run(args.mysql, migrate)
        print('搬迁已提交。')
    run(args.mysql, report)


if __name__ == '__main__':
    main()
