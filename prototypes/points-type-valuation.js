(() => {
  const $ = id => document.getElementById(id);
  const currencies = {
    CNY: { name: '人民币', major: '元', units: [{ code: 'fen', name: '分', factor: 1n }, { code: 'jiao', name: '角', factor: 10n }, { code: 'yuan', name: '元', factor: 100n }] },
    USD: { name: '美元', major: '美元', units: [{ code: 'cent', name: '美分', factor: 1n }, { code: 'dollar', name: '美元', factor: 100n }] },
    AED: { name: '阿联酋迪拉姆', major: '迪拉姆', units: [{ code: 'fils', name: '费尔（Fils）', factor: 1n }, { code: 'dirham', name: '迪拉姆', factor: 100n }] },
    KWD: { name: '科威特第纳尔', major: '第纳尔', units: [{ code: 'fils', name: '费尔（Fils）', factor: 1n }, { code: 'dinar', name: '第纳尔', factor: 1000n }] },
  };
  const items = [
    { name: '商城积分', code: 'MALL_POINTS', currency: 'CNY', points: '100', unit: 'yuan', status: '生效中', updated: '2026-10-20 14:30', remark: '用于商城消费发放和抵扣。' },
    { name: '海外活动积分', code: 'EVENT_USD', currency: 'USD', points: '100', unit: 'dollar', status: '草稿', updated: '2026-10-20 14:20', remark: '美元计价活动配置，尚未启用。' },
    { name: '体验积分', code: 'TRIAL_POINTS', currency: 'CNY', points: '10', unit: 'jiao', status: '草稿', updated: '2026-10-19 16:00', remark: '新会员活动配置。' },
  ];
  const escape = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  let currency = 'CNY', unit = currencies.CNY.units[0], pendingCurrency = null, lastFocus;
  function pointsValue() {
    const value = $('points').value;
    if (!/^\d+$/.test(value) || BigInt(value) < 1n) throw new Error('对应积分数量必须为大于 0 的整数。');
    if (BigInt(value) > 9007199254740991n) throw new Error('积分数量过大，请调整后重试。');
    return BigInt(value);
  }
  function pointsError(message) {
    $('points-error').textContent = message;
    $('points').setAttribute('aria-invalid', String(!!message));
    $('points').setCustomValidity(message);
  }
  function refreshRate() {
    try {
      const points = pointsValue(), c = currencies[currency];
      pointsError('');
      $('equation').textContent = `1 ${unit.name}（${c.name}）＝ ${points} 积分`;
      const numerator = points * c.units.at(-1).factor;
      $('major').textContent = numerator % unit.factor === 0n ? `每 1 ${c.major}${c.name === c.major ? '' : c.name}可兑换 ${numerator / unit.factor} 积分` : '法币数量固定为 1，仅选择单位';
      $('submit').disabled = false;
      return points;
    } catch (e) {
      pointsError(e.message); $('equation').textContent = '请检查对应积分数量'; $('major').textContent = '—'; $('submit').disabled = true;
      return null;
    }
  }
  function setCurrency(code) {
    currency = code; unit = currencies[code].units[0]; $('currency').value = code;
    $('unit').innerHTML = currencies[code].units.map(u => `<option value="${u.code}">${u.name}</option>`).join('');
    $('points').value = '1';
    const c = currencies[code];
    $('rate-help').textContent = `可选择${c.units.map(u => u.name).join('、')}；法币数量固定为 1。切换单位按等值换算积分，积分数须为正整数。`;
    refreshRate();
  }
  function render() {
    const query = $('search').value.trim().toLowerCase();
    const visible = items.filter(r => (!query || r.name.toLowerCase().includes(query) || r.code.toLowerCase().includes(query)) && (!$('currency-filter').value || r.currency === $('currency-filter').value) && (!$('state-filter').value || r.status === $('state-filter').value));
    $('rows').innerHTML = visible.map(r => {
      const c = currencies[r.currency], u = c.units.find(u => u.code === r.unit);
      return `<tr tabindex="0" data-code="${escape(r.code)}" aria-label="查看${escape(r.name)}详情"><td>${escape(r.name)}</td><td class="pv-code">${escape(r.code)}</td><td>${c.name}</td><td>${r.currency}</td><td>${u.name}</td><td class="num">${r.points}</td><td><span class="ops-badge ${r.status === '草稿' ? 'off' : ''}">${r.status}</span></td><td>${r.updated}</td><td><button class="ops-btn link" tabindex="-1">查看详情 →</button></td></tr>`;
    }).join('');
    $('count').textContent = items.length; $('total').textContent = `共 ${visible.length} 条`; $('empty').hidden = !!visible.length;
  }
  function open(mode, row) {
    lastFocus = document.activeElement; $('drawer-layer').hidden = false;
    $('create-form').hidden = mode === 'detail'; $('detail').hidden = mode !== 'detail';
    $('drawer-title').textContent = mode === 'detail' ? '积分详情' : '新增积分类型';
    $('drawer-desc').textContent = mode === 'detail' ? '名称、代码、币种和兑换配置分别展示。' : '填写名称和代码，设置 1 个法币单位可兑换的积分数量。';
    if (mode !== 'detail') {
      $('create-form').reset(); ['name', 'code'].forEach(id => { $(id + '-error').textContent = ''; $(id).setAttribute('aria-invalid', 'false'); });
      setCurrency(mode === 'usd' ? 'USD' : 'CNY'); setTimeout(() => $('name').focus(), 0);
    } else {
      const c = currencies[row.currency], u = c.units.find(u => u.code === row.unit);
      const fields = [['积分名称', row.name], ['积分代码', row.code], ['法币类型', c.name], ['币种代码', row.currency], ['状态', row.status], ['更新时间', row.updated], ['备注', row.remark || '—']];
      $('detail-fields').innerHTML = fields.map(([k,v]) => `<dt>${k}</dt><dd>${escape(v)}</dd>`).join('');
      $('detail-rate').innerHTML = `<dt>货币类型</dt><dd>${c.name}（${row.currency}）</dd><dt>货币单位</dt><dd>${u.name}</dd><dt>兑换积分数量</dt><dd>${row.points}</dd>`;
      $('detail-preview').textContent = `1 ${u.name}（${c.name}）＝ ${row.points} 积分`;
      setTimeout(() => $('close').focus(), 0);
    }
  }
  function close() { $('drawer-layer').hidden = true; history.replaceState(null, '', '#list'); document.querySelectorAll('[data-view]').forEach(b => b.classList.toggle('on', b.dataset.view === 'list')); lastFocus?.focus(); }
  $('new').onclick = () => { history.replaceState(null, '', '#create'); show('create'); }; ['close','cancel','detail-close'].forEach(id => $(id).onclick = close);
  $('drawer-layer').onclick = e => { if (e.target === $('drawer-layer')) close(); };
  $('currency').onchange = e => {
    const next = e.target.value; $('currency').value = currency;
    if ($('points').value !== '1' || unit !== currencies[currency].units[0]) {
      pendingCurrency = next; $('confirm-preview').textContent = `切换后：1 ${currencies[next].units[0].name}（${currencies[next].name}）＝ 1 积分`;
      $('currency-confirm').showModal();
    } else setCurrency(next);
  };
  $('confirm-currency').onclick = () => { setCurrency(pendingCurrency); $('currency-confirm').close(); $('currency').focus(); };
  $('keep-currency').onclick = () => { $('currency-confirm').close(); $('currency').focus(); };
  $('unit').onchange = e => {
    const points = refreshRate();
    if (points === null) { $('unit').value = unit.code; return; }
    const next = currencies[currency].units.find(u => u.code === e.target.value);
    const numerator = points * next.factor;
    if (numerator % unit.factor !== 0n || numerator / unit.factor > 9007199254740991n) {
      $('unit').value = unit.code;
      $('points-error').textContent = '该单位下无法用有效整数积分表示当前比例，请先调整积分数量。';
      return;
    }
    $('points').value = String(numerator / unit.factor); unit = next; refreshRate();
  };
  $('points').oninput = refreshRate;
  ['name','code'].forEach(id => $(id).oninput = () => { $(id + '-error').textContent = ''; $(id).setAttribute('aria-invalid', 'false'); });
  $('create-form').onsubmit = e => {
    e.preventDefault(); let invalid = null;
    for (const id of ['name','code']) {
      const value = $(id).value.trim();
      const message = !value ? `请填写积分${id === 'name' ? '名称' : '代码'}。` : items.some(r => r[id].toLowerCase() === value.toLowerCase()) ? `积分${id === 'name' ? '名称' : '代码'}已存在，请修改。` : '';
      $(id + '-error').textContent = message; $(id).setAttribute('aria-invalid', String(!!message));
      if (message && !invalid) invalid = id;
    }
    if (invalid) { $(invalid).focus(); return; }
    const points = refreshRate(); if (points === null) { $('points').focus(); return; }
    items.unshift({ name: $('name').value.trim(), code: $('code').value.trim(), currency, points: String(points), unit: unit.code, status: '草稿', updated: '刚刚', remark: $('remark').value.trim() });
    $('search').value = ''; $('currency-filter').value = ''; $('state-filter').value = ''; render(); close();
    $('toast').textContent = '积分已创建，可在列表中点击查看详情（原型演示）'; $('toast').hidden = false; setTimeout(() => $('toast').hidden = true, 3500);
  };
  $('rows').onclick = e => { const row = e.target.closest('[data-code]'); if (row) open('detail', items.find(r => r.code === row.dataset.code)); };
  $('rows').onkeydown = e => { if (['Enter',' '].includes(e.key)) { e.preventDefault(); e.target.click(); } };
  ['search','currency-filter','state-filter'].forEach(id => $(id).addEventListener('input', render));
  $('reset').onclick = () => { $('search').value = ''; $('currency-filter').value = ''; $('state-filter').value = ''; render(); };
  function show(view) {
    document.querySelectorAll('[data-view]').forEach(b => b.classList.toggle('on', b.dataset.view === view));
    if (view === 'list') close(); else open(view, items[0]);
  }
  document.querySelectorAll('[data-view]').forEach(b => b.onclick = () => { history.replaceState(null, '', '#' + b.dataset.view); show(b.dataset.view); });
  document.addEventListener('keydown', e => {
    if ($('drawer-layer').hidden || $('currency-confirm').open) return;
    if (e.key === 'Escape') close();
    if (e.key === 'Tab') {
      const focusable = [...document.querySelector('.ops-drawer').querySelectorAll('button,input,select,textarea')].filter(x => !x.disabled && x.offsetParent !== null);
      if (e.shiftKey && document.activeElement === focusable[0]) { e.preventDefault(); focusable.at(-1).focus(); }
      else if (!e.shiftKey && document.activeElement === focusable.at(-1)) { e.preventDefault(); focusable[0].focus(); }
    }
  });
  render(); const start = location.hash.slice(1); if (['create','usd','detail'].includes(start)) show(start); else show('list');
})();
