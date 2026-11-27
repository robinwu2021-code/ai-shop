#!/usr/bin/env python3
"""Compare one tenant in frozen legacy and v2 MySQL snapshots using the mysql client."""
import argparse
import json
import subprocess
import sys
from collections import Counter
from contextlib import ExitStack
from dataclasses import dataclass
from itertools import groupby
from heapq import merge
from pathlib import Path


@dataclass(frozen=True)
class Projection:
    table: str
    keys: str
    fields: str
    where: str = "deleted=0"

    def query(self, tenant):
        # Only the tenant comes from the command line. Hex encoding is independent of SQL mode.
        tenant_hex = tenant.encode("utf-8").hex()
        return (f"SELECT JSON_ARRAY({self.keys}) AS k, JSON_ARRAY({self.fields}) FROM {self.table} "
                f"WHERE tenant_no=CONVERT(X'{tenant_hex}' USING utf8mb4) AND ({self.where}) ORDER BY BINARY k")


def comparisons(include_merchant):
    if include_merchant:
        for table, key, fields, where in [
            ("sys_merchant_plan_def", "plan_code", "name,store_quota,staff_quota,cross_store_stats,trial_days,enabled,sort,version,deleted", "TRUE"),
            ("mch_plan_offer", "offer_no", "version,snapshot_json", "TRUE"),
            ("mch_entity_plan", "entity_no", "plan_code,store_quota,staff_quota,store_quota_override,staff_quota_override,cross_store_stats,status,start_at,expire_at,granted_by,trial_used,downgraded_at,version,deleted", "TRUE"),
        ]:
            projection = Projection(table, key, fields, where)
            yield table, "merchant", projection, projection, fields.split(",")
        yield ("entity_payment_attempt", "mall",
               Projection("mch_plan_purchase", "order_no",
                          "snapshot_json->>'$.intent.paymentAttemptNo',snapshot_json->>'$.intent.platformMerchantNo',"
                          "snapshot_json->>'$.intent.amount.currency',snapshot_json->'$.intent.amount.minor',snapshot_json->'$.intent'", "TRUE"),
               Projection("mall_payment_ref p", "order_no",
                          "payment_attempt_no,receiver_account_no,currency,amount_minor,"
                          "CAST(COALESCE(request_snapshot->'$.payment',request_snapshot->'$.original',request_snapshot) AS JSON)",
                          "deleted=0 AND (payment_business_type='ENTITY_PLAN' OR (payment_business_type='PLATFORM_MALL' "
                          "AND EXISTS (SELECT 1 FROM mall_order o WHERE o.tenant_no=p.tenant_no AND o.order_no=p.order_no "
                          "AND o.stage='ORDERED' AND o.pricing_policy_version='entity-plan-cash-v1')))"),
               ["payment_attempt_no", "receiver_account_no", "currency", "amount_minor", "frozen_intent"])
    yield ("catalog_version", "foundation", Projection("sys_business_type", "business_type_code", "plan_catalog_version"),
           Projection("sys_dict_item", "item_code", "attrs->>'$.plan_catalog_version'",
                      "dict_type='BUSINESS_TYPE' AND item_level='TYPE' AND deleted=0"), ["catalog_version"])
    order_fields = ("entity_no,store_no,quote_no,currency,gross_minor,discount_minor,deal_minor,credit_used_minor,"
                    "cash_due_minor,cash_paid_minor,cash_refunded_minor,pay_status,fulfillment_status,refund_status")
    yield ("order", "mall", Projection("mall_order", "order_no", order_fields),
           Projection("mall_order", "order_no", order_fields, "stage='ORDERED' AND deleted=0"), order_fields.split(","))
    # v2 writes the order lines when quoting. Only compare lines of confirmed orders.
    for table, key, fields, is_quote in [
        ("mall_order_item", "order_item_no", "order_no,sku_no,sku_version_no,quantity,unit_price_minor,gross_minor,discount_minor,deal_minor",
         "SELECT 1 FROM mall_order o WHERE o.tenant_no=i.tenant_no AND o.order_no=i.order_no AND o.stage='QUOTE'"),
        ("mall_order_component", "order_component_no", "order_item_no,component_no,unit_count,allocated_deal_minor,allocated_cash_minor,allocated_credit_minor,eligible_basis_minor,fulfillment_status",
         "SELECT 1 FROM mall_order_item l JOIN mall_order o ON o.tenant_no=l.tenant_no AND o.order_no=l.order_no "
         "WHERE l.tenant_no=i.tenant_no AND l.order_item_no=i.order_item_no AND o.stage='QUOTE'"),
    ]:
        yield table, "mall", Projection(table, key, fields), Projection(table + " i", key, fields,
                "deleted=0 AND NOT EXISTS (" + is_quote + ")"), fields.split(",")
    refund_fields = "order_no,store_no,requested_cash_minor,approved_cash_minor,restore_credit_minor,pay_refund_no,status"
    yield ("refund", "mall", Projection("mall_refund", "refund_no", refund_fields),
           Projection("mall_refund", "refund_no", refund_fields,
                      "deleted=0 AND JSON_CONTAINS_PATH(policy_snapshot,'one','$.refundNo')"), refund_fields.split(","))
    yield ("offline_refund", "mall",
           Projection("mall_offline_cash_refund", "refund_no", "order_no,store_no,payment_no,status,snapshot_json", "TRUE"),
           Projection("mall_refund", "refund_no", "order_no,store_no,payment_no,command_snapshot->>'$.offlinePayout.status',command_snapshot->'$.offlinePayout'",
                      "deleted=0 AND JSON_CONTAINS_PATH(command_snapshot,'one','$.offlinePayout')"),
           ["order_no", "store_no", "payment_no", "status", "payout"])
    for table, keys, fields in [
        ("mall_refund_item", "refund_item_no", "refund_no,order_component_no,period_no,cash_refund_minor,credit_restore_minor"),
        ("mch_subscription_account", "store_no", "entity_no,business_type_code,entitlement_version,pending_change_order_no"),
        ("mch_subscription_period", "period_no", "store_no,entity_no,plan_version_no,stream_key,source_type,source_ref_no,state,effective_start_at,effective_end_at,currency,eligible_basis_minor,cash_basis_minor,credit_basis_minor"),
        ("mch_subscription_credit_account", "store_no,currency", "balance_minor,reserved_minor"),
        ("mch_subscription_credit_lot", "credit_lot_no", "store_no,currency,issued_minor,remaining_minor,reserved_minor"),
        ("mch_subscription_credit_ledger", "ledger_no", "store_no,currency,credit_lot_no,event_type,amount_minor,balance_delta_minor,reserved_delta_minor"),
    ]:
        projection = Projection(table, keys, fields)
        yield table, "mall", projection, projection, fields.split(",")
    grant_key = "store_no,period_no,entitlement_code,schedule_revision,segment_seq"
    yield ("grant_schedule", "platform", Projection("mch_store_end_grant g", grant_key,
           "end_code,start_at,end_at,(SELECT e.client_id FROM sys_business_end e WHERE e.tenant_no=g.tenant_no "
           "AND e.end_code=g.end_code AND e.entitlement_code=g.entitlement_code AND e.deleted=0)",
           "deleted=0 AND state='NORMAL'"),
           Projection("mch_store_end_grant", grant_key, "end_code,start_at,end_at,client_id",
                      "deleted=0 AND state IN ('SCHEDULED','ACTIVE','ENDED')"), ["end_code", "start_at", "end_at", "client_id"])


def rows(stack, config, projection, tenant, side):
    args = ["mysql", f"--defaults-file={config}", "--default-character-set=utf8mb4", "--batch", "--raw",
            "--skip-column-names", "--quick", "--execute", projection.query(tenant)]
    process = stack.enter_context(subprocess.Popen(args, stdout=subprocess.PIPE, encoding="utf-8"))
    for line in process.stdout:
        key, values = line.rstrip("\n").split("\t", 1)
        yield key, side, json.loads(values)
    if process.wait():
        raise RuntimeError(f"MySQL query failed on {side} ({projection.table})")


def reconcile(args):
    differences = 0
    compared_rows = 0
    for name, target, old, new, fields in comparisons(args.merchant is not None):
        counts = Counter()
        with ExitStack() as stack:
            streams = (rows(stack, args.legacy, old, args.tenant, "legacy"),
                       rows(stack, getattr(args, target), new, args.tenant, "v2"))
            for key, group in groupby(merge(*streams, key=lambda row: row[0]), key=lambda row: row[0]):
                pair = {}
                duplicate = False
                for _, side, values in group:
                    counts[side] += 1
                    duplicate |= side in pair
                    pair[side] = values
                if duplicate:
                    problem = "duplicate business key"
                elif len(pair) == 1:
                    problem = "missing in v2" if "legacy" in pair else "extra in v2"
                else:
                    left, right = pair["legacy"], pair["v2"]
                    if name == "catalog_version":
                        left = [str(left[0])]
                    changed = [field for field, a, b in zip(fields, left, right) if a != b]
                    if not changed:
                        continue
                    problem = "changed " + ", ".join(changed)
                if differences < args.limit:
                    print(f"{name} {key} {problem}")
                differences += 1
                counts["differences"] += 1
        compared_rows += counts["legacy"] + counts["v2"]
        print(f"{name}: legacy={counts['legacy']} v2={counts['v2']} differences={counts['differences']}")
    if not compared_rows:
        raise ValueError("no records found for this tenant in the selected checks")
    print(f"total differences={differences}")
    return int(bool(differences))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("legacy", "mall", "foundation", "platform"):
        parser.add_argument("--" + name, type=Path, required=True, help="MySQL option file with [client] database")
    parser.add_argument("--merchant", type=Path, help="merchant domain snapshot for entity-plan checks")
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--limit", type=int, default=100, help="maximum detail lines, always count all differences")
    args = parser.parse_args()
    if not args.tenant.strip() or args.limit < 0:
        parser.error("provide a tenant and a nonnegative limit")
    try:
        return reconcile(args)
    except (OSError, ValueError, RuntimeError) as error:
        print(error, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
