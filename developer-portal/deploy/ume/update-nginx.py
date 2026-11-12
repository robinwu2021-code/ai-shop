#!/usr/bin/env python3
"""Keep existing nearshop application locations; route only root/fallback to docs."""
from datetime import datetime
from pathlib import Path

config = Path('/etc/nginx/conf.d/nearshop.umeshop.ai.conf')
original = config.read_text()
proxy = '''proxy_pass http://127.0.0.1:3210;
  proxy_http_version 1.1;
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $http_x_real_ip;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto https;
  proxy_set_header Connection "";
  proxy_read_timeout 30s;'''
replacements = {
    'location = / { return 302 /ops-shop/; }': 'location = / { ' + proxy + ' }',
    'location / { return 404; }': 'location / { ' + proxy + ' }',
}
for old in replacements:
    if original.count(old) != 1:
        raise SystemExit('Expected nearshop baseline changed; review config before applying')
backup = config.with_name(config.name + '.bak-neargo-' + datetime.utcnow().strftime('%Y%m%dT%H%M%SZ'))
backup.write_text(original)
updated = original
for old, new in replacements.items():
    updated = updated.replace(old, new)
config.write_text(updated)
print('Backup: ' + str(backup))
