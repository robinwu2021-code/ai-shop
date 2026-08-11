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

/*
 * 登录：用户名 + 密码，**角色由后端返回**。
 *
 * 这里此前是「选角色即进入」的 mock 登录 —— 在 mock 上没问题，但一旦指向真实后端
 * 就是两件错事：一是后端要的是 {username, password}，收到 {username, role} 直接拒；
 * 二是**让用户自己挑角色**，那是把权限交给被鉴权的一方。
 * 真实后端只认凭据，角色来自 STAFF 账号自身。
 */
export default function LoginPage() {
  const router = useRouter();
  const login = useAuth((s) => s.login);
  const { t } = useI18n();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr("");
    try {
      // 后端据 token 里的角色鉴权；前端只负责把凭据递过去
      const r = await api.login(username, password);
      login({
        username: r.username,
        role: r.role,
        token: r.token,
        // 判权靠它。漏传的话 perms 是空数组 = 零权限 = 登录后一片空白，
        // 而且不报错 —— 这一处在改造时漏了，靠浏览器实测才发现
        perms: r.perms,
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
              <label className="text-sm text-muted-foreground">{t("login.password")}</label>
              <Input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t("login.password")}
              />
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
