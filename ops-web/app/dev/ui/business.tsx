"use client";

// 业务件区块（三层里的第三层）。判据见 components/README.md：**依赖方向**——
// 这些组件 import 了业务类型（MerchantStatus / OrderStatus）或表达了业务约定
// （「运营端怎么说权限不足」「归档怎么呈现」），所以它们不在 components/ui/ 下。
import * as React from "react";
import { Section, Row, Cell, Hint } from "./kit";
import {
  MerchantStatusBadge, OrderStatusBadge, VerifiedBadge,
  useFulfillTypeMap, useTrafficSourceMap,
} from "@/components/status";
import { StatusBadge } from "@/components/ui/status-badge";
import { ShowArchivedToggle, ArchivedAt, ArchiveActions } from "@/components/archive";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import type { MerchantStatus, OrderStatus, FulfillType, TrafficSource } from "@/lib/types";

// 顺序 = 状态机推进顺序（与 lib/types 里的迁移表同序），不是字母序。
const MERCHANT_STATES: MerchantStatus[] = [
  "DRAFT", "SUBMITTED", "REVIEWING", "APPROVED", "REJECTED", "SUSPENDED",
];
const ORDER_STATES: OrderStatus[] = [
  "PENDING_PAY", "PAID", "PREPARING", "DELIVERING", "ARRIVED", "COMPLETED", "CANCELLED", "AFTER_SALE",
];
const FULFILL_TYPES: FulfillType[] = [
  "PICKUP_STORE", "PICKUP_NEIGHBOR", "MERCHANT_DELIVERY", "EXPRESS", "SERVICE",
];
const TRAFFIC_SOURCES: TrafficSource[] = ["MERCHANT_OWNED", "PLATFORM", "INVITE", "CHANNEL"];

export function BusinessSections() {
  const [showArchived, setShowArchived] = React.useState(false);
  const fulfillMap = useFulfillTypeMap();
  const trafficMap = useTrafficSourceMap();

  return (
    <>
      <Section
        id="status-badges"
        name="StatusBadges"
        layer="业务件"
        file="components/status.tsx"
        purpose="各业务状态机的徽章。状态→文案→色调的映射是业务语义，故不在 ui/ 层。"
      >
        <Row label="商家审核状态（MerchantStatusBadge）">
          {MERCHANT_STATES.map((s) => (
            <Cell key={s} label={s}>
              <MerchantStatusBadge value={s} />
            </Cell>
          ))}
        </Row>
        <Row label="订单状态（OrderStatusBadge）">
          {ORDER_STATES.map((s) => (
            <Cell key={s} label={s}>
              <OrderStatusBadge value={s} />
            </Cell>
          ))}
        </Row>
        <Row label="履约方式（分类，不表达好坏）">
          {FULFILL_TYPES.map((s) => (
            <Cell key={s} label={s}>
              <StatusBadge map={fulfillMap} value={s} />
            </Cell>
          ))}
        </Row>
        <Row label="流量来源（P-12.1.7 分档计费依据）">
          {TRAFFIC_SOURCES.map((s) => (
            <Cell key={s} label={s}>
              <StatusBadge map={trafficMap} value={s} />
            </Cell>
          ))}
        </Row>
        <Row label="认证标（VerifiedBadge）">
          <Cell label="已认证"><VerifiedBadge verified /></Cell>
          <Cell label="未认证（刻意什么都不出）"><VerifiedBadge verified={false} /></Cell>
        </Row>
        <Hint>
          未认证不出徽标：满屏「未认证」是噪音，认证才是信息。
          履约方式全用中性色，是因为它是分类不是状态 —— 给它上色会让人误以为某种履约「更好」。
        </Hint>
      </Section>

      <Section
        id="archive"
        name="Archive"
        layer="业务件"
        file="components/archive.tsx"
        purpose="软删除的统一表达。契约零 delete：归档而非删除，可恢复，历史数据保留。"
      >
        <Row label="ShowArchivedToggle">
          <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} />
        </Row>
        <Row label="ArchivedAt">
          <Cell label="已归档">
            <ArchivedAt at="2026-06-01T10:00:00Z" />
          </Cell>
          <Cell label="未归档（应为 -）">
            <ArchivedAt at={null} />
          </Cell>
        </Row>
        <Row label="ArchiveActions">
          <Cell label="未归档 → 出「归档」">
            <ArchiveActions archived={false} canWrite onArchive={() => {}} onUnarchive={() => {}} />
          </Cell>
          <Cell label="已归档 → 只出「恢复」">
            <ArchiveActions archived canWrite onArchive={() => {}} onUnarchive={() => {}} />
          </Cell>
          <Cell label="无权限 → 什么都不出">
            <ArchiveActions archived={false} canWrite={false} onArchive={() => {}} onUnarchive={() => {}} />
          </Cell>
        </Row>
        <Hint>
          已归档行只出「恢复」，其它动作一律不渲染 —— 让「已归档还能编辑」这类错误在调用点就写不出来。
        </Hint>
      </Section>

      <Section
        id="read-only-notice"
        name="ReadOnlyNotice"
        layer="业务件"
        file="components/read-only-notice.tsx"
        purpose="权限降级提示。句式统一：主句恒定，差异落到 note。"
      >
        <Row label="单权限码">
          <ReadOnlyNotice what="商家入驻审核" perm="merchant:apply:audit" />
        </Row>
        <Row label="多权限码（缺任一即降级）">
          <ReadOnlyNotice what="分账执行 / 提现审批" perm={["finance:settle:execute", "finance:withdraw:approve"]} />
        </Row>
        <Row label="带 note">
          <ReadOnlyNotice what="自提点建档" perm="community:pickup:update" note="不能新增、编辑或归档" />
        </Row>
        <Hint>
          不静默隐藏操作：说清缺什么权限码，运营才能拿着码去找管理员开权限。
        </Hint>
      </Section>
    </>
  );
}
