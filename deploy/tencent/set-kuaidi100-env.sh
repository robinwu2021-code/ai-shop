#!/usr/bin/env bash
# 把快递100 商家寄件的凭据写进线上 shop-app.env（TDD-快递100商家寄件 §6）。
#
#   deploy/tencent/set-kuaidi100-env.sh
#
# 正式 / 测试环境**不在这里选**：两个地址常驻，由运营端功能开关「快递测试模式」(express.test-mode) 切，
# 按取件单落库（TDD-快递100商家寄件 §7）。同一套 key / secret 两边通用。
#
# **密钥只在你的终端里输入**：不回显、不进命令行参数（ps 看不到）、不进日志，
# 经 ssh 的标准输入送到服务器。回调签名盐 salt 在服务器上随机生成，谁都不用看见它。
#
# 只改文件、不重启 —— 重启时机由部署的人定（会让在线会话重连）。
# 改之前自动备份到同目录 shop-app.env.bak-<时间>。
set -euo pipefail

SSH_HOST="${SSH_HOST:-soukmind-tx}"
ENV_FILE=/data/app/ai-shop/shop-app/shop-app.env

read -rsp "快递100 授权 key（输入不回显）: " KEY; echo
read -rsp "快递100 secret（输入不回显）: " SECRET; echo
# 只收字母数字：EnvironmentFile 不做 shell 转义，混进空格或引号会被 systemd 按字面读进去
for v in "$KEY" "$SECRET"; do
  [[ "$v" =~ ^[A-Za-z0-9]+$ ]] || { echo "✗ key / secret 只能是字母数字，请检查是否多粘了空格"; exit 1; }
done

ssh "$SSH_HOST" 'sudo bash -s' <<REMOTE
set -euo pipefail
f=$ENV_FILE
cp -p "\$f" "\$f.bak-\$(date +%Y%m%d%H%M%S)"
sed -i '/^KUAIDI100_/d;/^SHOP_EXPRESS_KUAIDI100_STUB=/d' "\$f"
{
  echo "SHOP_EXPRESS_KUAIDI100_STUB=false"
  echo "KUAIDI100_KEY=$KEY"
  echo "KUAIDI100_SECRET=$SECRET"
  echo "KUAIDI100_SALT=\$(openssl rand -hex 16)"
} >> "\$f"
chown deploy:deploy "\$f"
chmod 600 "\$f"
echo "✓ 已写入 \$(grep -c '^KUAIDI100_\|^SHOP_EXPRESS_KUAIDI100_STUB=' "\$f") 行（值不显示）"
REMOTE
echo "下一步：告诉部署的人重启后端（systemctl restart ai-shop）。"
