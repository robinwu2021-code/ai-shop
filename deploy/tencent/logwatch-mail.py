#!/usr/bin/env python3
"""巡检告警的邮件出口。正文从 stdin 读，配置从环境变量来。

**SMTP 凭据不经过命令行、不经过 shell 变量、不落日志** —— 本脚本自己去
MAIL_ENV_FILE 里逐行解析。理由见 logwatch.sh 里同一段的注释：
`set -a; . env` 会把含 `&` 的值截断，而且会把整份 env 灌进进程环境，`ps e` 就能看见。

用的是**应用已经在用的那套 M365 凭据**，不另抄一份：这台机器上再抄一份
就是多一个要轮换的地方，而抄的过程本身就是一次泄露机会。

环境变量：
  MAIL_ENV_FILE   从哪份 env 读 SMTP 凭据（MAIL_HOST/PORT/USERNAME/PASSWORD/FROM）
  ALERT_MAIL_TO   收件人，多个用逗号分隔
  ALERT_SUBJECT   标题（手机锁屏上只看得到它，所以要自带结论）
  ALERT_COUNT     这次几条，进正文

退出码：0 送达；非 0 由调用方决定重试（logwatch 不会记「已推」，下一轮再试）。
"""
import os
import smtplib
import ssl
import sys
from email.message import EmailMessage
from email.utils import formatdate


def read_env(path):
    """逐行解析 KEY=VALUE。不用 shell source —— 见模块注释。"""
    out = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.rstrip("\n")
                if not line or line.lstrip().startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip().strip('"').strip("'")
    except OSError as e:
        print(f"读不到 {path}: {e.strerror}", file=sys.stderr)
        return None
    return out


def main():
    to = os.environ.get("ALERT_MAIL_TO", "").strip()
    if not to:
        print("没有收件人（ALERT_MAIL_TO 为空）", file=sys.stderr)
        return 2

    env = read_env(os.environ.get("MAIL_ENV_FILE", ""))
    if env is None:
        return 3

    host = env.get("MAIL_HOST", "")
    port = int(env.get("MAIL_PORT") or 587)
    user = env.get("MAIL_USERNAME", "")
    pwd = env.get("MAIL_PASSWORD", "")
    # **MAIL_FROM 必须与 MAIL_USERNAME 一致**，除非在 M365 后台授了「发送为」权限：
    # 实测认证会成功而投递被拒（554 SendAsDenied），而那个错误看起来像凭据配错。
    sender = env.get("MAIL_FROM") or user

    missing = [k for k, v in (("MAIL_HOST", host), ("MAIL_USERNAME", user),
                              ("MAIL_PASSWORD", pwd)) if not v]
    if missing:
        # 只说缺哪个键，不说值
        print("SMTP 配置不全，缺: " + ", ".join(missing), file=sys.stderr)
        return 4

    text = sys.stdin.read().rstrip("\n")
    count = os.environ.get("ALERT_COUNT", "?")
    subject = os.environ.get("ALERT_SUBJECT") or "巡检告警"

    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = sender
    msg["To"] = to
    msg["Date"] = formatdate(localtime=True)
    # 让邮件客户端把同一台机器的告警归到一个会话里
    msg["X-Logwatch-Host"] = os.uname().nodename
    msg.set_content(
        f"{text}\n\n"
        f"—— 共 {count} 条 · 主机 {os.uname().nodename}\n"
        f"完整巡检结果：/data/log/ai-shop/ops/logwatch.log\n"
        f"同一项 6 小时内只发一次；恢复后不会再发，"
        f"所以「不再收到」既可能是好了、也可能是巡检自己停了。\n"
    )

    try:
        ctx = ssl.create_default_context()
        if port == 465:
            with smtplib.SMTP_SSL(host, port, timeout=20, context=ctx) as smtp:
                smtp.login(user, pwd)
                smtp.send_message(msg)
        else:
            with smtplib.SMTP(host, port, timeout=20) as smtp:
                smtp.starttls(context=ctx)
                smtp.login(user, pwd)
                smtp.send_message(msg)
    except Exception as e:  # noqa: BLE001 —— 失败原因要说出来，但不能带出凭据
        print(f"发信失败: {type(e).__name__}: {e}", file=sys.stderr)
        return 5
    return 0


if __name__ == "__main__":
    sys.exit(main())
