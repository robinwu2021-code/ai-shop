"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth, type Role } from "@/lib/auth";
import { api } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

// 顺序 = 需求矩阵 §2.3 的角色表顺序，便于逐行对照。
const ROLES: Role[] = [
  "SUPER_ADMIN", "PRODUCT_OPS", "CAMPAIGN_OPS", "COMMUNITY_OPS", "MERCHANT_BD",
  "AUDITOR", "CS", "FINANCE", "RISK", "ANALYST", "TECH_OPS",
];

/**
 * 受限数据域的演示账号（矩阵 §2.3 权限模型：RBAC + 数据域）。
 * 选这些角色时自动带上归属键，用来验证「列表被裁到只剩自己范围」这条规则 ——
 * 不带 scope 的话，数据域在前端开发期是完全看不见的一维。
 */
const DEMO_SCOPE: Partial<Record<Role, { merchantNo?: string; communityNo?: string }>> = {
  COMMUNITY_OPS: { communityNo: "C001" },
};

// MVP 登录（mock）：选角色 + 用户名即可进入。接后端后换真实凭据（STAFF 池 Bearer + RBAC）。
export default function LoginPage() {
  const router = useRouter();
  const login = useAuth((s) => s.login);
  const { t } = useI18n();
  const [username, setUsername] = useState("admin");
  const [role, setRole] = useState<Role>("SUPER_ADMIN");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr("");
    try {
      // 换后端 token（mock 模式返回 mock token）；**后端据 token 里的角色鉴权**
      const r = await api.login(username, role, DEMO_SCOPE[role]);
      login({
        username: r.username,
        role: r.role,
        token: r.token,
        merchantNo: r.merchantNo,
        communityNo: r.communityNo,
      });
      router.replace("/");
    } catch (e) {
      setErr((e as Error).message || t("common.failed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-screen items-center justify-center bg-muted/30">
      <Card className="w-[360px]">
        <CardHeader>
          <div className="mb-1 flex size-9 items-center justify-center rounded-field bg-primary text-sm font-medium text-primary-foreground">
            邻
          </div>
          <CardTitle>{t("common.appTitle")}</CardTitle>
          <p className="text-sm text-muted-foreground">{t("login.subtitle")}</p>
        </CardHeader>
        <CardContent>
          <form className="space-y-3" onSubmit={submit}>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground">{t("login.username")}</label>
              <Input value={username} onChange={(e) => setUsername(e.target.value)} placeholder={t("login.username")} />
            </div>
            <div className="space-y-1">
              <label className="text-sm text-muted-foreground">{t("login.role")}</label>
              <Select className="w-full" value={role} onChange={(e) => setRole(e.target.value as Role)}>
                {ROLES.map((r) => <option key={r} value={r}>{t(`role.${r}`)}</option>)}
              </Select>
            </div>
            {err && <div className="rounded-field bg-destructive/10 px-3.5 py-2 text-sm text-destructive">{err}</div>}
            <Button className="w-full" type="submit" disabled={busy}>
              {busy ? t("common.loading") : t("login.submit")}
            </Button>
            <p className="txt-caption text-muted-foreground">{t("login.hint")}</p>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
