"use client";

// 商家的人员与门店授权（**只读**）。挂在商家档案抽屉的最后一段。
//
// 为什么运营要看得到：客服接到「我们店的配送员看不到订单」这类电话时，
// 在此之前只能让老板自己截图 —— 而问题往往正是「他以为授了、其实没授」，
// 截图里看不出这一点。这一块把它变成一眼可见。
//
// **刻意没有任何写操作**：谁能进这家店是商家的雇佣关系，
// 平台替商家改授权，等于平台替商家决定谁能动他的钱。
// 要处置该商家走封禁 —— 那是另一个层级的动作，有单独的权限码与审计。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCopy } from "@/lib/use-copy";
import { Badge } from "@/components/ui/badge";
import { Field } from "@/components/ui/drawer";
import { MERCHANTS_COPY } from "./copy";

export function StaffBlock({ merchantNo }: { merchantNo: string }) {
  const c = useCopy(MERCHANTS_COPY);
  const { data = [], isLoading } = useQuery({
    queryKey: ["merchant-staff", merchantNo],
    queryFn: () => api.merchantStaff(merchantNo),
  });

  /**
   * 角色码 → 人话。**界面上不出现 `MANAGER` 这种码**。
   *
   * 查不到就回落成码本身 —— 商家自定义的角色（V71）平台没有第二份可抄的名字，
   * 而那串码至少是真的；编一个「未知角色」反而抹掉了唯一有用的线索。
   */
  const roleName = (code: string) => (c as Record<string, string>)[`role${code}`] ?? code;

  if (isLoading) return <Field label={c.fieldStaff}>…</Field>;

  return (
    <Field label={c.fieldStaff}>
      {data.length === 0 ? (
        <span className="text-muted-foreground">-</span>
      ) : (
        <ul className="space-y-1.5">
          {data.map((s) => (
            <li key={s.mchAccountNo} className="flex flex-wrap items-center gap-1.5 text-sm">
              <span>{s.displayName || s.loginPhone}</span>
              {s.isOwner && <Badge tone="info">{c.staffOwner}</Badge>}
              {!s.isOwner && s.status !== "ACTIVE" && <Badge tone="muted">{c.staffDisabled}</Badge>}
              <span className="text-muted-foreground">
                {s.isOwner
                  ? c.staffOwnerNote
                  : s.roles.length === 0
                    // 授权为空 = 登录进去什么都看不到。**这正是最常被投诉的那一种**，
                    // 而在商家自己的界面上它和正常人长得一样
                    ? c.staffNoGrant
                    : s.roles.map((r) => `${r.storeName}·${roleName(r.role)}`).join("，")}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Field>
  );
}
