"use client";

// 角色 × 权限对照表。**dev-only 工具页**：不在 lib/nav.ts 里、不进阶段门禁、不出现在任何菜单，
// 只能靠 URL 直达（/dev/perms）。
//
// 为什么值得存在：RBAC 的错误几乎都不是"报错"，而是**沉默的**——
// 某个角色少了一个码，他登录后只是"看不到那个菜单"，没人会收到任何提示；
// 多了一个码更糟，财务之外的人能点分账，要等出事才发现。
// 单测（lib/permissions.test.ts）锁住了关键几条，这一页负责让人**一眼扫完全局**。
import * as React from "react";
import { NAV } from "@/lib/nav";
import { CRITICAL_PERMS, ROLE_LABEL, can, canModule } from "@/lib/permissions";
import type { Role } from "@/lib/auth";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { PageTitle } from "@/components/ui/misc";
import { Table, THead, TBody, TR, TH, TD } from "@/components/ui/table";

const ROLES = Object.keys(ROLE_LABEL) as Role[];

/** 导航里出现过的全部权限码，按 section 归组（这就是"实际会影响界面"的码集合）。 */
function permGroups() {
  return NAV.map((s) => ({
    key: s.key,
    label: s.label,
    module: s.module,
    perms: [...new Set((s.children ?? []).map((l) => l.perm).filter(Boolean) as string[])],
  })).filter((g) => g.perms.length > 0);
}

const Mark = ({ on, critical }: { on: boolean; critical?: boolean }) =>
  on ? (
    <span className={cn("txt-strong", critical ? "text-[var(--destructive)]" : "text-[var(--success)]")}>✓</span>
  ) : (
    <span className="text-muted-foreground/40">·</span>
  );

export default function PermMatrixPage() {
  const groups = React.useMemo(permGroups, []);

  return (
    <div>
      <PageTitle title="角色 × 权限对照" desc="dev-only · 数据源 lib/permissions.ts 与 lib/nav.ts" />

      <div className="mb-4 rounded-card bg-muted/50 p-4 txt-body">
        <div className="mb-2 txt-strong">高危权限（矩阵 §2.3「高危权限」列）</div>
        <div className="space-y-1">
          {CRITICAL_PERMS.map((c) => (
            <div key={c} className="flex flex-wrap items-center gap-2">
              <code className="txt-caption">{c}</code>
              {ROLES.filter((r) => can(r, c)).map((r) => (
                <Badge key={r} tone="danger">{ROLE_LABEL[r]}</Badge>
              ))}
            </div>
          ))}
        </div>
        <p className="mt-2 txt-caption text-muted-foreground">
          这几行要能一眼数清持有者。多一个人 = 一次事故的可能，少一个人 = 一条业务流程走不通。
        </p>
      </div>

      <div className="overflow-x-auto rounded-card bg-card">
        <Table>
          <THead>
            <TR>
              <TH>模块 / 权限码</TH>
              {ROLES.map((r) => (
                <TH key={r} className="whitespace-nowrap text-center">{ROLE_LABEL[r]}</TH>
              ))}
            </TR>
          </THead>
          <TBody>
            {groups.map((g) => (
              <React.Fragment key={g.key}>
                <TR>
                  <TD className="txt-strong">{g.label}<span className="ms-2 txt-caption text-muted-foreground">{g.module}</span></TD>
                  {ROLES.map((r) => (
                    <TD key={r} className="text-center">
                      {/* 模块行看的是「这个菜单他能不能看见」（canModule），与叶子的细粒度码是两回事 */}
                      <Mark on={canModule(r, g.module)} />
                    </TD>
                  ))}
                </TR>
                {g.perms.map((p) => (
                  <TR key={p}>
                    <TD className="ps-6"><code className="txt-caption text-muted-foreground">{p}</code></TD>
                    {ROLES.map((r) => (
                      <TD key={r} className="text-center">
                        <Mark on={can(r, p)} critical={(CRITICAL_PERMS as readonly string[]).includes(p)} />
                      </TD>
                    ))}
                  </TR>
                ))}
              </React.Fragment>
            ))}
          </TBody>
        </Table>
      </div>
    </div>
  );
}
