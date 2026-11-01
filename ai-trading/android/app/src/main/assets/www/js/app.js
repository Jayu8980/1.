// AI Trading Desk - phone app. Screens: Home, Scan, Portfolio, Desk (server), Settings.
import { DEFAULTS, withStyle, merge } from './engine/config.js';
import { LAYERS } from './engine/agents.js';
import { chartUrl, parseChart, packBars, unpackBars } from './engine/data.js';
import { PaperBroker, newAccount } from './engine/paper.js';
import presetData from './engine/presets.js';
import { http, store, cacheGet, cachePut, cacheClear, setNativeTheme, appVersion, insets, isNative } from './native.js';
import { esc, $, $$, num, money, moneyFull, pct, signed, price, short, cls, ago, avatarColor, icon, sparkline, priceChart, equityCurve, toast, sheet, dialog, closeTop } from './ui.js';

const PRESETS = presetData.presets;
const SETTINGS_DEFAULT = {
  theme: 'system', source: 'yahoo', preset: 'nifty50', style: 'delivery', years: 6,
  risk_per_trade: 0.01, max_open_positions: 5, top_n: 3, min_conviction: 0.55, halt_on_crisis: true, trail: true,
  paper_equity: 1000000, deskUrl: '', deskToken: '', deskSegment: 'delivery', deskAi: true,
};
const S = {
  route: 'home',
  settings: { ...SETTINGS_DEFAULT, ...store.get('settings', {}) },
  paper: store.get('paper') || null,
  lastScan: store.get('lastScan'),
  scan: null, // the scan in progress (or just finished) on this phone
  desk: { info: null, error: null, job: null, result: null, status: null },
  openLayer: null,
};
if (!S.paper) S.paper = newAccount(S.settings.paper_equity);
const saveSettings = () => store.set('settings', S.settings);
const savePaper = () => store.set('paper', S.paper);
const broker = () => new PaperBroker(S.paper, { fee: DEFAULTS.paper.fee_pct, slip: DEFAULTS.paper.slippage_pct });
const isIndia = (key) => !/^(us_|crypto)/.test(key || '');
const cur = () => (isIndia(S.settings.preset) ? '₹' : '$');
const presetOf = (key) => PRESETS[key] || PRESETS.nifty50;

// ---- theme & shell ---------------------------------------------------------------------------
const mq = window.matchMedia('(prefers-color-scheme: dark)');
function applyTheme() {
  const t = S.settings.theme === 'system' ? (mq.matches ? 'dark' : 'light') : S.settings.theme;
  document.documentElement.dataset.theme = t;
  document.querySelector('meta[name=theme-color]')?.setAttribute('content', t === 'dark' ? '#070b14' : '#f3f5fa');
  setNativeTheme(t === 'dark');
}
mq.addEventListener?.('change', applyTheme);
window.__setInsets = (top, bottom) => {
  document.documentElement.style.setProperty('--inset-top', `${top}px`);
  document.documentElement.style.setProperty('--inset-bottom', `${bottom}px`);
};
const ins = insets();
window.__setInsets(ins.top || 0, ins.bottom || 0);

const TABS = [['home', 'Home', 'home'], ['scan', 'Scan', 'scan'], ['portfolio', 'Portfolio', 'wallet'], ['desk', 'Desk', 'server'], ['settings', 'Settings', 'gear']];
const TITLES = { home: ['AI Trading Desk', '12 agents · one decision'], scan: ['AI Scan', 'layer-by-layer filtration'], portfolio: ['Portfolio', 'paper trading account'], desk: ['Desk server', 'live brokers & Claude review'], settings: ['Settings', 'risk, data and appearance'] };

function shell() {
  document.getElementById('app').innerHTML = `
    <header class="topbar" id="topbar">
      <div class="logo">${icon('bolt')}</div>
      <div><h1 id="title"></h1><div class="sub" id="subtitle"></div></div>
      <div class="spacer"></div><span class="chip-mode" id="mode"></span>
    </header>
    <main id="main"></main>
    <nav class="bottomnav">${TABS.map(([r, label, ic]) => `<button data-nav="${r}" aria-label="${label}">${icon(ic)}<span>${label}</span></button>`).join('')}</nav>`;
  window.addEventListener('scroll', () => $('#topbar').classList.toggle('scrolled', window.scrollY > 4), { passive: true });
  document.addEventListener('click', onClick);
  document.addEventListener('change', onChange);
}

function go(route) {
  if (!TITLES[route]) route = 'home';
  S.route = route;
  if (location.hash !== `#/${route}`) history.replaceState(null, '', `#/${route}`);
  render();
  window.scrollTo(0, 0);
}
window.addEventListener('hashchange', () => go(location.hash.replace('#/', '')));
window.appBack = () => {
  if (closeTop()) return true;
  if (S.route !== 'home') { go('home'); return true; }
  return false;
};

function render() {
  const [t, sub] = TITLES[S.route];
  $('#title').textContent = t;
  $('#subtitle').textContent = sub;
  const live = S.desk.info?.live;
  const mode = $('#mode');
  mode.textContent = S.route === 'desk' && S.desk.info ? (live ? 'LIVE' : S.desk.info.mode) : S.settings.source === 'synthetic' ? 'Demo data' : 'Paper';
  mode.classList.toggle('live', S.route === 'desk' && !!live);
  $$('.bottomnav button').forEach((b) => b.classList.toggle('active', b.dataset.nav === S.route));
  const open = Object.values(S.paper.positions).length;
  const pb = $('.bottomnav [data-nav=portfolio]');
  pb.querySelector('.badge')?.remove();
  if (open) pb.insertAdjacentHTML('beforeend', `<span class="badge">${open}</span>`);
  $('#main').innerHTML = `<div class="screen">${SCREENS[S.route]()}</div>`;
  AFTER[S.route]?.();
}

// ---- shared pieces ------------------------------------------------------------------------------
function accountStats() {
  const a = broker().account(), closed = S.paper.closed.filter((t) => t.status === 'closed');
  const wins = closed.filter((t) => t.pnl > 0).length, totalR = closed.reduce((t, x) => t + x.r_multiple, 0);
  let eq = S.paper.start_equity;
  const curve = [eq, ...closed.map((t) => (eq += t.pnl))];
  if (Math.abs(a.equity - eq) > 1e-6) curve.push(a.equity);
  return { ...a, pnl: a.equity - a.start_equity, closed, winRate: closed.length ? wins / closed.length : null, avgR: closed.length ? totalR / closed.length : null, curve };
}

const REGIME_ICON = { bull: 'up', bear: 'down', crisis: 'alert', neutral: 'flat' };
function regimeCard(m, when) {
  if (!m) return '';
  return `<div class="card"><div class="regime">
    <div class="icon ${m.regime === 'bull' ? 'up' : m.regime === 'neutral' ? '' : 'down'}">${icon(REGIME_ICON[m.regime] || 'flat')}</div>
    <div class="grow"><div class="row"><b style="font-size:16px;text-transform:capitalize">${esc(m.regime)} market</b><span class="tag ${esc(m.regime)}">${esc(m.volatility)} vol</span></div>
    <div class="small muted">${esc(m.vol_measure || '')}${m.breadth != null ? ` · breadth ${(m.breadth * 100).toFixed(0)}%` : ''} · size ×${(m.risk_multiplier ?? 1).toFixed(2)}${when ? ` · ${ago(when)}` : ''}</div></div>
  </div></div>`;
}

function pickCard(t, i, source) {
  const p = t.plan || {}, ml = t.reports?.ml?.data || {};
  const conv = t.conviction ?? 0;
  return `<div class="card tap" data-act="trade" data-src="${source}" data-i="${i}">
    <div class="pick">
      <div class="rank ${t.rank === 1 ? 'r1' : ''}">#${t.rank ?? i + 1}</div>
      <div class="grow"><div class="row"><span class="sym">${esc(short(t.symbol))}</span><span class="tag ${esc(t.side)}">${esc((t.side || '').toUpperCase())}</span></div>
        <div class="meta ellipsis">${esc(p.setup || t.setup?.label || '')}</div></div>
      ${t.closes?.length ? sparkline(t.closes.slice(-60)) : `<div class="ring" style="--p:${conv}"><span>${Math.round(conv * 100)}</span></div>`}
    </div>
    <div class="pick-levels">
      <div><small>Entry</small><b class="num">${price(p.entry_ref)}</b></div>
      <div><small>Stop</small><b class="num down">${price(p.stop)}</b></div>
      <div><small>Target</small><b class="num up">${price(p.target)}</b></div>
    </div>
    <div class="row between small muted" style="margin-top:10px">
      <span>Conviction <b class="num" style="color:var(--text)">${(conv * 100).toFixed(0)}</b></span>
      <span>EV <b class="num ${cls(t.expected_r)}">${signed(t.expected_r, 2, 'R')}</b></span>
      <span>P(win) <b class="num" style="color:var(--text)">${ml.prob != null ? (ml.prob * 100).toFixed(0) + '%' : '-'}</b></span>
    </div>
  </div>`;
}

function resultBlock(res, source) {
  if (!res) return '';
  const trades = res.trades || [];
  let html = `<div class="section-title"><h2>Final decision</h2><span class="small muted">${res.universe ?? ''} scanned · ${ago(res.generated)}</span></div>`;
  if (res.synthetic) html += `<div class="notice info" style="margin-bottom:12px">${icon('info')}<div>Demo data: these picks come from generated prices and can't be traded. Switch to live market data to scan real NSE stocks.</div></div>`;
  html += trades.length ? trades.map((t, i) => pickCard(t, i, source)).join('')
    : `<div class="card flat empty">${icon('shield')}<b>No trade today</b>Nothing passed all 12 layers. Staying flat is a valid decision.</div>`;
  if (res.orders?.length) {
    html += `<div class="section-title"><h2>Orders</h2></div><div class="card">${res.orders.map((o) => `<div class="list-row"><div class="grow"><b>${esc(short(o.symbol))}</b> <span class="muted small">${esc(o.side)} ${num(o.qty, 0)}</span></div><span class="small">${esc(o.status)}</span></div><div class="small muted" style="margin:-6px 0 6px">${esc(o.reason || '')}</div>`).join('')}</div>`;
  }
  if (res.misses?.length) {
    html += `<div class="section-title"><h2>Closest misses</h2></div><div class="card">${res.misses.map((m) => `<div class="list-row"><div class="avatar" style="background:${avatarColor(m.symbol)}">${esc(short(m.symbol).slice(0, 2))}</div><div class="grow"><b>${esc(short(m.symbol))}</b> <span class="small faint">stopped at L${m.layer}</span><div class="small muted">${esc(m.reason)}</div></div></div>`).join('')}</div>`;
  }
  return html;
}

function funnelBlock(state) {
  // state: {layers:[{status, entered, passed}], universe, rejected: {name: [[sym, why]]}}
  return `<div class="card funnel" id="funnel">${LAYERS.map((L, i) => layerRow(L, state.layers[i], state)).join('')}</div>`;
}
function layerRow(L, st = {}, state) {
  const total = Math.max(1, state.universe || 1);
  const status = st.status || 'wait';
  const desk = ['sentiment', 'fundamental', 'ai_review'].includes(L.name) && !state.server;
  const rej = state.rejected?.[L.name] || [];
  const open = S.openLayer === L.name && rej.length;
  return `<div class="layer ${status}${desk ? ' skip' : ''}" data-act="layer" data-name="${L.name}" id="layer-${L.name}">
    <div class="ln">L${L.layer}</div>
    <div class="grow"><div class="t ellipsis">${esc(L.title)}</div><div class="bar"><i style="width:${st.passed != null ? (100 * st.passed) / total : 0}%"></i></div></div>
    <div class="cnt num">${st.entered != null ? `${st.entered}<small> → </small>${st.passed ?? '…'}` : desk ? '<small>desk</small>' : '<small>-</small>'}</div>
    ${open ? `<div class="layer-rej">${rej.slice(0, 30).map(([s, why]) => `<div><b>${esc(short(s))}</b> ${esc(why)}</div>`).join('')}${rej.length > 30 ? `<div>… ${rej.length - 30} more</div>` : ''}</div>` : ''}
  </div>`;
}
function funnelFromResult(res, server = false) {
  const rejected = {}, layers = LAYERS.map((L) => {
    const f = (res.funnel || []).find((x) => x.agent === L.name);
    if (f) rejected[L.name] = f.rejected || [];
    return f ? { status: 'done', entered: f.entered, passed: f.passed } : {};
  });
  return { layers, universe: res.universe ?? (res.funnel?.[0]?.entered || 1), rejected, server };
}

// ---- HOME ---------------------------------------------------------------------------------------
function homeScreen() {
  const a = accountStats(), c = cur(), last = S.lastScan;
  const open = Object.values(S.paper.positions);
  return `
  <div class="hero">
    ${equityCurve(a.curve.length > 1 ? a.curve : [1, 1.02, 1.01, 1.04, 1.03, 1.07])}
    <div class="label">Paper equity</div>
    <div class="big num">${moneyFull(a.equity, c)}</div>
    <span class="delta num">${icon(a.pnl >= 0 ? 'up' : 'down', 'width="16" height="16"')} ${money(a.pnl, c)} · ${pct(a.pnl / a.start_equity, 2)}</span>
    <div class="stats"><div><b class="num">${open.length}</b>positions</div><div><b class="num">${a.winRate == null ? '-' : (a.winRate * 100).toFixed(0) + '%'}</b>win rate</div><div><b class="num">${a.avgR == null ? '-' : signed(a.avgR, 2, 'R')}</b>avg trade</div></div>
  </div>
  <div style="margin-top:14px"><button class="btn primary block" data-act="quickscan">${icon('play')} Run AI scan · ${esc(presetOf(S.settings.preset).label)}</button></div>
  ${last ? `<div class="section-title"><h2>Market</h2><button class="link" data-nav="scan">Details</button></div>${regimeCard(last.market, last.generated)}` : ''}
  ${last ? resultBlock({ ...last, misses: [] }, 'last') : `<div class="section-title"><h2>Welcome</h2></div>
    <div class="card"><div class="stack"><div class="row">${icon('brain', 'width="22" height="22" style="color:var(--accent)"')}<b>How the desk decides</b></div>
    <div class="muted small">Every stock in your universe meets 12 specialist agents in turn. Each one checks a different thing and can veto. Only trades that survive every layer - backtested, validated out of sample, scored by machine learning and sized by the risk manager - reach the final decision.</div>
    <button class="btn block" data-nav="scan">Choose a universe</button></div></div>`}
  <div class="section-title"><h2>The agents</h2></div>
  <div class="chips">${LAYERS.map((L) => `<span class="chip" data-act="agentinfo" data-name="${L.name}">L${L.layer} · ${esc(L.title.split(' ')[0])}${L.title.includes('Claude') ? ' AI' : ''}</span>`).join('')}</div>
  <p class="small faint center" style="margin-top:22px">Backtests and model probabilities describe the past, not the future. Paper trade first and risk only money you can afford to lose.</p>`;
}

// ---- SCAN ---------------------------------------------------------------------------------------
function presetSelect(name, value) {
  return `<select class="input" name="${name}">${Object.entries(presetData.groups).map(([g, keys]) => `<optgroup label="${esc(g)}">${keys.map((k) => `<option value="${k}" ${k === value ? 'selected' : ''}>${esc(PRESETS[k].label)} (${PRESETS[k].symbols.length})</option>`).join('')}</optgroup>`).join('')}</select>`;
}
function scanScreen() {
  const st = S.settings, p = presetOf(st.preset), run = S.scan, busy = run?.running;
  const big = p.symbols.length > 150 && st.source === 'yahoo';
  let html = `<div class="card">
    <label class="field"><span>Universe</span>${presetSelect('preset', st.preset)}</label>
    <div class="small muted" style="margin:-6px 2px 14px">${p.symbols.length} symbols · benchmark ${esc(short(p.benchmark))}${big ? ` · large: downloads ~${Math.round(p.symbols.length * 0.15)} MB the first time` : ''}</div>
    <div class="grid2">
      <label class="field"><span>Trading style</span><div class="seg">${[['delivery', 'Delivery'], ['intraday', 'Intraday']].map(([v, l]) => `<button data-set="style" data-val="${v}" class="${st.style === v ? 'on' : ''}">${l}</button>`).join('')}</div></label>
      <label class="field"><span>Market data</span><div class="seg">${[['yahoo', 'Live'], ['synthetic', 'Demo']].map(([v, l]) => `<button data-set="source" data-val="${v}" class="${st.source === v ? 'on' : ''}">${l}</button>`).join('')}</div></label>
    </div>
    ${busy ? `<button class="btn danger block" data-act="stopscan">${icon('stop')} Stop scan</button>` : `<button class="btn primary block" data-act="scan">${icon('play')} Run the 12-agent scan</button>`}
  </div>`;
  if (run) {
    html += `<div class="section-title"><h2>Progress</h2><span class="small muted" id="phase">${esc(run.phase)}</span></div>
      <div class="progress" style="margin:0 4px 12px"><i id="prog" style="width:${run.progress * 100}%"></i></div>
      ${run.market ? regimeCard(run.market) : ''}
      <div class="section-title"><h2>Filtration funnel</h2><span class="small faint">tap a layer for its rejections</span></div>
      ${funnelBlock(run)}
      <div class="section-title"><h2>Agent conversation</h2></div>
      <div class="log mono" id="log">${run.log.slice(-200).map(logLine).join('')}</div>`;
    if (run.result) html += resultBlock(run.result, 'scan');
    if (run.error) html += `<div class="notice" style="margin-top:12px">${icon('alert')}<div>${esc(run.error)}</div></div>`;
  } else if (S.lastScan) {
    const f = funnelFromResult(S.lastScan);
    html += `<div class="section-title"><h2>Last scan</h2><span class="small muted">${esc(presetOf(S.lastScan.preset).label)} · ${ago(S.lastScan.generated)}</span></div>
      ${regimeCard(S.lastScan.market)}<div style="height:12px"></div>${funnelBlock(f)}${resultBlock(S.lastScan, 'last')}`;
  } else {
    html += `<div class="card flat empty" style="margin-top:14px">${icon('scan')}<b>Ready when you are</b>Pick a universe and run the scan. Live data comes from Yahoo Finance (end of day, adjusted prices).</div>`;
  }
  return html;
}
const logLine = (l) => `<div class="${l.k || ''}">${esc(l.t)}</div>`;
function scanLog(text, k = '') {
  const run = S.scan;
  run.log.push({ t: text, k });
  if (run.log.length > 600) run.log.splice(0, run.log.length - 400);
  const el = $('#log');
  if (el) {
    el.insertAdjacentHTML('beforeend', logLine({ t: text, k }));
    while (el.childElementCount > 220) el.firstElementChild.remove();
    el.scrollTop = el.scrollHeight;
  }
}
function scanPhase(text, progress) {
  const run = S.scan;
  run.phase = text;
  if (progress != null) run.progress = progress;
  const ph = $('#phase'), pr = $('#prog');
  if (ph) ph.textContent = text;
  if (pr) pr.style.width = `${run.progress * 100}%`;
}
function refreshLayer(name) {
  const i = LAYERS.findIndex((L) => L.name === name), el = $(`#layer-${name}`);
  if (el && S.scan) el.outerHTML = layerRow(LAYERS[i], S.scan.layers[i], S.scan);
}

function buildCfg(presetKey) {
  const st = S.settings, p = presetOf(presetKey);
  const cfg = merge(DEFAULTS, {
    style: st.style,
    universe: { preset: presetKey, history_years: st.years },
    liquidity: { min_turnover: st.source === 'synthetic' ? 0 : p.min_turnover || 0, min_bars: Math.min(500, Math.floor(st.years * 252 * 0.8)) },
    regime: { halt_on_crisis: st.halt_on_crisis },
    risk: { risk_per_trade: st.risk_per_trade, max_open_positions: st.max_open_positions },
    decision: { top_n: st.top_n, min_conviction: st.min_conviction },
  });
  return withStyle(cfg);
}

async function loadBars(symbol, years, maxAgeMs) {
  const key = `${symbol}|${years}`, c = await cacheGet(key);
  if (c && Date.now() - c.fetched < maxAgeMs) return unpackBars(c.bars);
  let lastErr = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const r = await http({ url: chartUrl(symbol, years), headers: { Accept: 'application/json' }, timeout: 25000 });
      if (r.status === 429) { await new Promise((res) => setTimeout(res, 1500 * (attempt + 1))); lastErr = new Error('rate limited'); continue; }
      if (r.status === 404) return null;
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const bars = parseChart(r.json());
      if (bars) await cachePut(key, { fetched: Date.now(), bars: packBars(bars) });
      return bars;
    } catch (e) { lastErr = e; }
  }
  if (c) return unpackBars(c.bars); // stale beats nothing
  throw lastErr || new Error('download failed');
}
async function downloadAll(symbols, years, maxAgeMs, onEach, shouldStop = () => false) {
  const frames = {}, failed = [];
  let next = 0, done = 0;
  const worker = async () => {
    while (next < symbols.length && !shouldStop()) {
      const s = symbols[next++];
      try { const b = await loadBars(s, years, maxAgeMs); if (b) frames[s] = b; else failed.push(s); } catch { failed.push(s); }
      onEach(++done, symbols.length, s);
    }
  };
  await Promise.all(Array.from({ length: Math.min(6, symbols.length) }, worker));
  return { frames, failed };
}

let scanWorker = null;
async function startScan() {
  if (S.scan?.running) return;
  const st = S.settings, key = st.preset, p = presetOf(key), cfg = buildCfg(key), synthetic = st.source === 'synthetic';
  const symbols = [...p.symbols];
  S.scan = { running: true, phase: 'Starting', progress: 0, layers: LAYERS.map(() => ({})), universe: symbols.length, rejected: {}, log: [], market: null, result: null, error: null, preset: key };
  S.openLayer = null;
  if (S.route !== 'scan') go('scan'); else render();
  scanLog(`Desk opens: ${symbols.length} symbols · ${p.label} · ${synthetic ? 'demo data' : 'live market data'} · ${st.style}`, 'hd');
  let frames = {};
  if (!synthetic) {
    scanPhase('Downloading market data', 0);
    const extra = [p.benchmark, p.volatility_index].filter(Boolean);
    const { frames: f, failed } = await downloadAll([...symbols, ...extra], st.years, 3 * 3600e3,
      (d, n, s) => { scanPhase(`Downloading ${d}/${n} · ${short(s)}`, 0.35 * (d / n)); }, () => !S.scan?.running);
    if (!S.scan?.running) return;
    frames = f;
    const got = symbols.filter((s) => frames[s]).length;
    scanLog(`Data agent: ${got}/${symbols.length} symbols downloaded${failed.length ? `, ${failed.length} unavailable` : ''}`, got ? '' : 'no');
    if (!got) { finishScan(null, 'No market data could be downloaded. Check your internet connection, or try Demo data.'); return; }
  }
  scanPhase('Agents at work', 0.35);
  const pb = broker(), acct = pb.account();
  const account = { equity: acct.equity, cash: acct.cash, positions: pb.positions(), held: [] };
  const payload = { cfg, symbols, frames, benchmark: p.benchmark, volIndex: synthetic ? null : p.volatility_index || null, account, now: Date.now(), synthetic, bars: Math.round(st.years * 252) };
  const onMsg = (data) => {
    if (!S.scan?.running) return;
    if (data.type === 'event') onScanEvent(data.event);
    else if (data.type === 'result') finishScan(data.result);
    else if (data.type === 'error') finishScan(null, `The scan failed: ${data.message}`);
  };
  // Prefer a background worker; if this WebView can't start one, run on the main thread.
  let started = false;
  const inline = async () => {
    if (started) return;
    started = true;
    scanWorker?.terminate();
    scanWorker = null;
    const { executeScan } = await import('./scan-core.js');
    executeScan({ ...payload, yieldMs: 16 }, onMsg);
  };
  try {
    scanWorker = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' });
    const fallback = setTimeout(inline, 4000);
    scanWorker.onmessage = ({ data }) => {
      if (data.type === 'ready') { if (!started) { started = true; clearTimeout(fallback); scanWorker.postMessage(payload); } return; }
      onMsg(data);
    };
    scanWorker.onerror = (e) => { e.preventDefault?.(); clearTimeout(fallback); if (!started) inline(); else finishScan(null, `The scan failed: ${e.message || 'worker error'}`); };
  } catch { inline(); }
}
function onScanEvent(e) {
  const run = S.scan;
  if (!run) return;
  const idx = (name) => LAYERS.findIndex((L) => L.name === name);
  if (e.topic === 'regime') {
    run.market = e;
    scanLog(`Regime analyst: market ${String(e.regime).toUpperCase()}, volatility ${e.volatility}${e.vol_measure ? ` (${e.vol_measure})` : ''}${e.breadth != null ? `, breadth ${(e.breadth * 100).toFixed(0)}%` : ''}`, 'hd');
    if (S.route === 'scan') { const f = $('#funnel'); f?.insertAdjacentHTML('beforebegin', regimeCard(e) + '<div style="height:12px"></div>'); }
  } else if (e.topic === 'layer_start') {
    run.layers[idx(e.name)] = { status: 'running', entered: e.entered };
    scanPhase(`L${e.layer} ${e.title}`, 0.35 + 0.65 * ((e.layer - 1) / LAYERS.length));
    if (e.entered) scanLog(`L${e.layer} ${e.title}: ${e.entered} candidate${e.entered === 1 ? '' : 's'} in`, 'hd');
    refreshLayer(e.name);
  } else if (e.topic === 'reject') {
    (run.rejected[e.agent] ||= []).push([e.symbol, e.reason]);
    scanLog(`  ✕ ${short(e.symbol)}: ${e.reason}`, 'no');
  } else if (e.topic === 'layer_done') {
    const L = LAYERS[e.layer - 1];
    run.layers[e.layer - 1] = { status: 'done', entered: e.entered, passed: e.passed };
    if (e.entered && e.passed) scanLog(`  ✓ ${e.passed} passed ${L.title.toLowerCase()}`, 'ok');
    refreshLayer(L.name);
  }
}
function finishScan(result, error = null) {
  scanWorker?.terminate();
  scanWorker = null;
  const run = S.scan;
  if (!run) return;
  run.running = false;
  if (error) { run.error = error; scanPhase('Stopped', run.progress); toast(error, { error: true }); render(); return; }
  run.result = { ...result, preset: run.preset, synthetic: S.settings.source === 'synthetic', style: S.settings.style };
  run.layers = funnelFromResult(result).layers;
  scanPhase('Done', 1);
  scanLog(result.trades.length ? `Decision: ${result.trades.map((t) => `#${t.rank} ${short(t.symbol)} ${t.side}`).join(', ')}` : 'Decision: no trade passed every layer - staying flat', 'hd');
  S.lastScan = run.result;
  if (!store.set('lastScan', S.lastScan)) store.set('lastScan', { ...S.lastScan, misses: [] });
  render();
  toast(result.trades.length ? `${result.trades.length} trade${result.trades.length > 1 ? 's' : ''} passed all 12 layers` : 'No trade today - nothing passed every layer');
}

// ---- trade detail sheet ------------------------------------------------------------------------
function tradeFrom(src, i) {
  if (src === 'scan') return [S.scan?.result?.trades?.[i], S.scan?.result];
  if (src === 'last') return [S.lastScan?.trades?.[i], S.lastScan];
  if (src === 'desk') return [S.desk.result?.trades?.[i], S.desk.result];
  return [null, null];
}
function openTrade(src, i) {
  const [t, res] = tradeFrom(src, i);
  if (!t) return;
  const p = t.plan || {}, r = t.reports || {}, opt = r.optimizer?.data || {}, ml = r.ml?.data || {}, c = isIndia(res?.preset) || /\.(NS|BO)$/.test(t.symbol) ? '₹' : '$';
  const ins = opt.in_sample || {}, oos = opt.out_of_sample || {};
  const layers = LAYERS.map((L) => {
    const rep = r[L.name];
    if (!rep) return '';
    const skip = rep.score == null;
    return `<div class="agent ${skip ? 'skip' : ''}"><div class="hdr"><div class="ln">L${L.layer}</div><div class="t">${esc(L.title)}</div>
      ${skip ? '<span class="small faint">desk</span>' : `<div class="score"><i style="width:${Math.round(rep.score * 100)}%"></i></div><span class="small num" style="width:28px;text-align:right">${Math.round(rep.score * 100)}</span>`}</div>
      <ul>${(rep.reasons || []).slice(0, 4).map((x) => `<li>${esc(x)}</li>`).join('')}</ul></div>`;
  }).join('');
  const canPaper = src !== 'desk' && !res?.synthetic;
  sheet(`
    <div class="row" style="margin-bottom:6px"><div class="avatar" style="background:${avatarColor(t.symbol)}">${esc(short(t.symbol).slice(0, 2))}</div>
      <div class="grow"><div class="row"><h2 style="font-size:20px">${esc(short(t.symbol))}</h2><span class="tag ${esc(t.side)}">${esc((t.side || '').toUpperCase())}</span><span class="tag neutral">#${t.rank ?? i + 1}</span></div>
      <div class="small muted ellipsis">${esc(p.setup || '')}</div></div></div>
    <div class="card flat" style="padding:10px 6px 4px;margin:10px 0">${priceChart(t.closes, { entry: p.entry_ref, stop: p.stop, target: p.target })}</div>
    <div class="grid3">
      <div class="cell"><small>Entry ~</small><b class="num">${price(p.entry_ref)}</b></div>
      <div class="cell"><small>Stop</small><b class="num down">${price(p.stop)}</b></div>
      <div class="cell"><small>Target</small><b class="num up">${price(p.target)}</b></div>
      <div class="cell"><small>Quantity</small><b class="num">${p.sized_by_broker ? 'broker' : num(p.qty, p.qty % 1 ? 4 : 0)}</b></div>
      <div class="cell"><small>Value</small><b class="num">${money(p.notional, c)}</b></div>
      <div class="cell"><small>Risk</small><b class="num">${money(p.risk_amount, c)}</b></div>
      <div class="cell"><small>Reward:risk</small><b class="num">${p.reward_risk ?? '-'}R</b></div>
      <div class="cell"><small>Max hold</small><b class="num">${p.max_hold ?? '-'} bars</b></div>
      <div class="cell"><small>Risk % equity</small><b class="num">${pct(p.risk_pct, 2, false)}</b></div>
    </div>
    <div class="section-title"><h2>Why this trade</h2></div>
    <div class="grid2">
      <div class="cell"><small>Conviction</small><b class="num">${((t.conviction ?? 0) * 100).toFixed(0)} / 100</b></div>
      <div class="cell"><small>Expected value</small><b class="num ${cls(t.expected_r)}">${signed(t.expected_r, 2, 'R')} / trade</b></div>
      <div class="cell"><small>ML P(profit)</small><b class="num">${ml.prob != null ? (ml.prob * 100).toFixed(0) + '%' : '-'} <span class="small muted">AUC ${ml.auc != null ? ml.auc.toFixed(2) : '-'}</span></b></div>
      <div class="cell"><small>Robustness</small><b class="num">${opt.robustness != null ? (opt.robustness * 100).toFixed(0) + '%' : '-'}</b></div>
      <div class="cell"><small>Backtest in-sample</small><b class="num">${ins.trades ?? '-'} · PF ${ins.profit_factor != null ? ins.profit_factor.toFixed(2) : '-'}</b></div>
      <div class="cell"><small>Out-of-sample</small><b class="num">${oos.trades ?? '-'} · ${signed(oos.expectancy, 2, 'R')}</b></div>
    </div>
    <div class="section-title"><h2>Agent reports</h2></div>
    <div class="card flat">${layers}</div>
    <div class="stack" style="margin-top:16px">
      ${canPaper ? `<button class="btn primary block" data-act="paper" data-src="${src}" data-i="${i}">${icon('check')} Paper trade this</button>` : ''}
      ${res?.synthetic ? '<div class="notice info">' + icon('info') + '<div>Picks from demo data are for exploring the app only.</div></div>' : ''}
    </div>
    <p class="small faint" style="margin-top:14px">Entry is the next session's open. Stops and targets keep their distance from the actual fill. This is not investment advice.</p>`);
}

function paperTrade(src, i) {
  const [t] = tradeFrom(src, i);
  if (!t?.plan) return;
  try {
    const r = broker().submit(t.plan);
    savePaper();
    closeTop();
    toast(`${short(t.symbol)}: order queued - ${r.reason}`);
    render();
  } catch (e) { toast(e.message, { error: true }); }
}

// ---- PORTFOLIO ----------------------------------------------------------------------------------
function portfolioScreen() {
  const a = accountStats(), c = cur(), positions = broker().positions();
  const pos = positions.map((p) => {
    const d = p.side === 'long' ? 1 : -1, pnl = p.status === 'open' ? d * (p.last_price - p.entry) * p.qty : 0;
    const at = p.status === 'open' ? (p.last_price - p.stop) / (p.target - p.stop || 1) : (p.entry_ref - p.stop) / (p.target - p.stop || 1); // 0 at the stop, 1 at the target
    return `<div class="card tap" data-act="position" data-sym="${esc(p.symbol)}">
      <div class="row"><div class="avatar" style="background:${avatarColor(p.symbol)}">${esc(short(p.symbol).slice(0, 2))}</div>
        <div class="grow"><div class="row"><b>${esc(short(p.symbol))}</b><span class="tag ${p.status}">${p.status}</span><span class="tag ${p.side}">${p.side}</span></div>
        <div class="small muted">${num(p.qty, p.qty % 1 ? 4 : 0)} @ ${p.entry ? price(p.entry) : `~${price(p.entry_ref)}`} · ${p.bars_held}/${p.max_hold} bars</div></div>
        <div style="text-align:right"><b class="num ${cls(pnl)}">${p.status === 'open' ? money(pnl, c) : 'next open'}</b><div class="small muted num">${price(p.last_price)}</div></div></div>
      <div style="margin-top:12px;position:relative;height:6px;border-radius:3px;background:linear-gradient(90deg, color-mix(in srgb, var(--down) 50%, transparent), var(--line) 45%, color-mix(in srgb, var(--up) 50%, transparent))">
        <i style="position:absolute;top:-4px;left:calc(${Math.max(0, Math.min(1, at)) * 100}% - 7px);width:14px;height:14px;border-radius:50%;background:var(--text);border:3px solid var(--card)"></i></div>
      <div class="row between small muted" style="margin-top:6px"><span>stop ${price(p.stop)}</span><span>target ${price(p.target)}</span></div>
    </div>`;
  }).join('');
  const closed = [...a.closed].reverse().slice(0, 50).map((t) => `<div class="list-row"><div class="avatar" style="background:${avatarColor(t.symbol)}">${esc(short(t.symbol).slice(0, 2))}</div>
    <div class="grow"><b>${esc(short(t.symbol))}</b> <span class="small muted">${esc(t.side)} · ${esc(t.reason)}</span><div class="small faint">${esc(t.opened)} → ${esc(t.closed)}</div></div>
    <div style="text-align:right"><b class="num ${cls(t.pnl)}">${money(t.pnl, c)}</b><div class="small num ${cls(t.r_multiple)}">${signed(t.r_multiple, 2, 'R')}</div></div></div>`).join('');
  return `
    <div class="kpis">
      <div class="kpi"><div class="k">Equity</div><div class="v num">${money(a.equity, c)}</div></div>
      <div class="kpi"><div class="k">Cash</div><div class="v num">${money(a.cash, c)}</div></div>
      <div class="kpi"><div class="k">Total P&L</div><div class="v num ${cls(a.pnl)}">${money(a.pnl, c)}</div></div>
      <div class="kpi"><div class="k">Trades</div><div class="v num">${a.closed.length}</div></div>
      <div class="kpi"><div class="k">Win rate</div><div class="v num">${a.winRate == null ? '-' : (a.winRate * 100).toFixed(0) + '%'}</div></div>
      <div class="kpi"><div class="k">Avg trade</div><div class="v num ${cls(a.avgR)}">${a.avgR == null ? '-' : signed(a.avgR, 2, 'R')}</div></div>
    </div>
    <div class="row" style="margin-top:12px"><button class="btn grow" data-act="update" id="updbtn">${icon('refresh')} Update with latest prices</button></div>
    <div class="section-title"><h2>Positions & orders</h2><span class="small muted">${positions.length}</span></div>
    ${pos || `<div class="card flat empty">${icon('wallet')}<b>No open positions</b>Paper trade a pick from a scan and it appears here. Orders fill at the next session's open.</div>`}
    <div class="section-title"><h2>Closed trades</h2></div>
    ${closed ? `<div class="card">${closed}</div>` : '<div class="card flat empty small">Closed trades and their R multiples will be listed here.</div>'}`;
}

async function updatePortfolio(silent = false) {
  const syms = Object.keys(S.paper.positions);
  if (!syms.length) { if (!silent) toast('No positions to update'); return; }
  const btn = $('#updbtn');
  if (btn) { btn.disabled = true; btn.innerHTML = '<span class="spinner" style="border-color:var(--line-2);border-top-color:var(--accent)"></span> Updating…'; }
  try {
    const { frames, failed } = await downloadAll(syms, S.settings.years, 10 * 60e3, () => {});
    const done = broker().manage(frames, S.settings.trail);
    savePaper();
    if (done.length) toast(done.map((t) => `${short(t.symbol)} ${t.status === 'cancelled' ? 'cancelled' : `closed (${t.reason}) ${signed(t.r_multiple, 2, 'R')}`}`).join(' · '), { ms: 6000 });
    else if (!silent) toast(failed.length ? `Updated; no data for ${failed.map(short).join(', ')}` : 'Up to date - no exits');
  } catch (e) { if (!silent) toast(`Update failed: ${e.message}`, { error: true }); }
  if (S.route === 'portfolio' || S.route === 'home') render();
}

function positionSheet(sym) {
  const p = S.paper.positions[sym];
  if (!p) return;
  sheet(`<h2 style="font-size:20px">${esc(short(sym))} <span class="tag ${p.status}">${p.status}</span></h2>
    <div class="grid3" style="margin-top:14px">
      <div class="cell"><small>Side</small><b>${p.side}</b></div><div class="cell"><small>Qty</small><b class="num">${num(p.qty, p.qty % 1 ? 4 : 0)}</b></div><div class="cell"><small>Entry</small><b class="num">${p.entry ? price(p.entry) : '-'}</b></div>
      <div class="cell"><small>Stop</small><b class="num down">${price(p.stop)}</b></div><div class="cell"><small>Target</small><b class="num up">${price(p.target)}</b></div><div class="cell"><small>Last</small><b class="num">${price(p.last_price)}</b></div>
    </div>
    <p class="small muted">${esc(p.setup || '')} · signal ${esc(p.signal_date)}${p.opened ? ` · opened ${esc(p.opened)}` : ''}</p>
    <button class="btn danger block" data-act="${p.status === 'pending' ? 'cancelpos' : 'closepos'}" data-sym="${esc(sym)}">${icon('x')} ${p.status === 'pending' ? 'Cancel order' : `Close at ${price(p.last_price)}`}</button>`);
}

// ---- DESK ---------------------------------------------------------------------------------------
async function deskCall(path, { method = 'GET', body = null, timeout = 20000 } = {}) {
  const base = S.settings.deskUrl.trim().replace(/\/+$/, '');
  if (!base) throw new Error('Set the desk server address first');
  const r = await http({ url: base + path, method, timeout, headers: { Authorization: `Bearer ${S.settings.deskToken.trim()}`, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : null });
  let j = null;
  try { j = r.json(); } catch { throw new Error(`unexpected reply (HTTP ${r.status})`); }
  if (!r.ok) throw new Error(j?.error || `HTTP ${r.status}`);
  return j;
}
function fromServer(res) {
  if (!res?.funnel) return res;
  return { generated: res.generated, market: res.market_regime, funnel: res.funnel, trades: res.trades || [], universe: res.universe?.symbols?.length,
    misses: [...(res.rejected || [])].sort((a, b) => (b.layer || 0) - (a.layer || 0)).slice(0, 8), orders: res.orders, preset: res.universe?.preset, server: true, synthetic: res.data_source === 'synthetic' };
}
function deskScreen() {
  const st = S.settings, d = S.desk, info = d.info;
  let html = '';
  if (!info) {
    html += `<div class="card"><div class="stack">
      <div class="row">${icon('link', 'width="22" height="22" style="color:var(--accent)"')}<b>Connect your desk server</b></div>
      <div class="small muted">Live broker orders (Zerodha, Angel One, Dhan, Groww), news sentiment, fundamentals and the Claude review run on your computer. SEBI rules require broker API orders to come from your registered static IP, so the phone sends instructions to your desk instead of trading directly.</div>
      <div class="mono small" style="background:var(--bg-2);border:1px solid var(--line);border-radius:12px;padding:10px">python -m trader serve --broker zerodha</div>
      <label class="field"><span>Server address</span><input class="input" name="deskUrl" placeholder="http://192.168.1.20:8765" value="${esc(st.deskUrl)}" inputmode="url" autocapitalize="off" spellcheck="false"></label>
      <label class="field"><span>Token</span><input class="input" name="deskToken" placeholder="printed when the server starts" value="${esc(st.deskToken)}" autocapitalize="off" spellcheck="false" type="password"></label>
      <button class="btn primary block" data-act="connect">${icon('link')} Connect</button>
      ${d.error ? `<div class="notice">${icon('alert')}<div>${esc(d.error)}</div></div>` : ''}
      <div class="small faint">Use your home network or a private VPN such as Tailscale. Never expose the server to the internet.</div></div></div>`;
    return html;
  }
  html += `<div class="card"><div class="row"><div class="avatar" style="background:var(--grad)">${icon('server', 'width="20" height="20"')}</div>
      <div class="grow"><b style="text-transform:capitalize">${esc(info.broker)}</b> <span class="tag ${info.live ? 'short' : 'neutral'}">${info.live ? 'LIVE MONEY' : esc(info.mode)}</span>
      <div class="small muted ellipsis">${esc(st.deskUrl)} · v${esc(info.version)} · AI review ${esc(info.ai)}</div></div>
      <button class="btn ghost small" data-act="disconnect">Change</button></div></div>
    ${info.live ? `<div class="notice" style="margin-top:12px">${icon('alert')}<div><b>This server places real orders.</b> "Place trades" sends the final decision to your ${esc(info.broker)} account after you type LIVE.</div></div>` : ''}
    <div class="card" style="margin-top:12px">
      <label class="field"><span>Universe</span>${presetSelect('preset', st.preset)}</label>
      <label class="field"><span>Segment</span><select class="input" name="deskSegment">${['delivery', 'intraday', 'futures', 'options'].map((s) => `<option ${st.deskSegment === s ? 'selected' : ''} value="${s}">${s[0].toUpperCase() + s.slice(1)}${s === 'futures' ? ' (leveraged)' : s === 'options' ? ' (buying)' : ''}</option>`).join('')}</select></label>
      <div class="setting" style="border:0;padding-top:0"><div class="grow"><b>Claude review & news</b><small>layers 8, 9 and 12 on the server</small></div><label class="switch"><input type="checkbox" name="deskAi" ${st.deskAi ? 'checked' : ''}><i></i></label></div>
      <div class="grid2">
        <button class="btn primary" data-act="deskjob" data-kind="scan" ${d.job?.status === 'running' ? 'disabled' : ''}>${icon('scan')} Scan</button>
        <button class="btn ${info.live ? 'danger' : ''}" data-act="deskjob" data-kind="trade" ${d.job?.status === 'running' ? 'disabled' : ''}>${icon('send')} Place trades</button>
        <button class="btn" data-act="deskjob" data-kind="monitor" ${d.job?.status === 'running' ? 'disabled' : ''}>${icon('refresh')} Monitor</button>
        <button class="btn" data-act="deskstatus">${icon('wallet')} Account</button>
      </div>
    </div>`;
  if (d.job) {
    html += `<div class="section-title"><h2>${esc(d.job.kind[0].toUpperCase() + d.job.kind.slice(1))} on the desk</h2><span class="small muted">${d.job.status === 'running' ? '<span class="spinner" style="display:inline-block;vertical-align:middle;border-color:var(--line-2);border-top-color:var(--accent)"></span>' : esc(d.job.status)}</span></div>
      <div class="log mono" id="desklog">${d.job.lines.slice(-300).map((t) => logLine({ t })).join('')}</div>
      ${d.job.error ? `<div class="notice" style="margin-top:12px">${icon('alert')}<div>${esc(d.job.error)}</div></div>` : ''}`;
  }
  if (d.result) {
    html += `<div style="height:12px"></div>${regimeCard(d.result.market, d.result.generated)}<div style="height:12px"></div>${funnelBlock(funnelFromResult(d.result, true))}${resultBlock(d.result, 'desk')}`;
  }
  if (d.monitor) {
    html += `<div class="section-title"><h2>Monitor</h2></div><div class="card">${d.monitor.length ? d.monitor.map((t) => `<div class="list-row"><b class="grow">${esc(short(t.symbol))}</b><span class="small">${esc(t.status || 'closed')} (${esc(t.reason || '')}) ${t.pnl != null ? `<b class="${cls(t.pnl)}">${num(t.pnl)}</b>` : ''}</span></div>`).join('') : '<div class="small muted">No exits or cancellations.</div>'}</div>`;
  }
  if (d.status) html += deskStatusBlock(d.status);
  return html;
}
function deskStatusBlock(s) {
  const a = s.account || {};
  return `<div class="section-title"><h2>Account</h2><span class="small muted">${esc(s.broker)}${s.live ? ' · live' : ''}</span></div>
    <div class="kpis"><div class="kpi"><div class="k">Equity</div><div class="v num">${money(a.equity)}</div></div><div class="kpi"><div class="k">Cash</div><div class="v num">${money(a.cash)}</div></div><div class="kpi"><div class="k">Positions</div><div class="v num">${(s.positions || []).length}</div></div></div>
    ${(s.positions || []).length ? `<div class="card" style="margin-top:12px">${s.positions.map((p) => `<div class="list-row"><div class="grow"><b>${esc(short(p.symbol))}</b> <span class="tag ${esc(p.status || 'open')}">${esc(p.status || 'open')}</span><div class="small muted">${esc(p.side || '')} ${num(p.qty, 0)} · stop ${price(p.stop)} · target ${price(p.target)}</div></div><span class="num">${price(p.last_price)}</span></div>`).join('')}</div>` : ''}
    ${(s.held_outside || []).length ? `<p class="small muted">Held outside the desk (never traded by it): ${s.held_outside.map((x) => esc(short(x))).join(', ')}</p>` : ''}`;
}
async function deskConnect() {
  const d = S.desk;
  d.error = null;
  try {
    await deskCall('/api/health', { timeout: 8000 });
    d.info = await deskCall('/api/info', { timeout: 10000 });
  } catch (e) { d.info = null; d.error = `Couldn't connect: ${e.message}`; }
  if (S.route === 'desk') render();
}
async function deskJob(kind) {
  const st = S.settings, d = S.desk;
  const body = { preset: st.preset, segment: st.deskSegment, ai: st.deskAi };
  if (kind === 'trade') {
    if (d.info?.live) {
      const typed = await dialog({ title: 'Place REAL orders?', body: `The desk will scan ${esc(presetOf(st.preset).label)} and send the final decision to your <b>${esc(d.info.broker)}</b> account as <b>${esc(st.deskSegment)}</b> orders. Type LIVE to confirm.`, confirm: 'Place orders', danger: true, input: 'LIVE' });
      if (typed !== 'LIVE') { if (typed !== false) toast('Not confirmed - nothing was sent'); return; }
      body.confirm = 'LIVE';
    } else if (!(await dialog({ title: 'Place trades?', body: `The desk will scan and send the final decision to its <b>${esc(d.info?.mode)}</b> ${esc(d.info?.broker)} account. No real money moves.`, confirm: 'Run' }))) return;
  }
  try {
    const { job } = await deskCall(`/api/${kind}`, { method: 'POST', body: kind === 'monitor' ? {} : body });
    d.job = { id: job, kind, status: 'running', lines: [], next: 0 };
    if (kind !== 'monitor') d.result = null;
    d.monitor = null; d.status = null;
    render();
    pollJob(job);
  } catch (e) { toast(e.message, { error: true }); }
}
async function pollJob(id) {
  const d = S.desk;
  while (d.job?.id === id && d.job.status === 'running') {
    await new Promise((r) => setTimeout(r, 1200));
    try {
      const v = await deskCall(`/api/jobs/${id}?since=${d.job.next}`);
      d.job.lines.push(...v.lines);
      d.job.next = v.next;
      const el = $('#desklog');
      if (el && v.lines.length) { el.insertAdjacentHTML('beforeend', v.lines.map((t) => logLine({ t })).join('')); el.scrollTop = el.scrollHeight; }
      if (v.status !== 'running') {
        Object.assign(d.job, { status: v.status, error: v.error });
        if (v.result) {
          if (d.job.kind === 'monitor') { d.monitor = v.result.closed; d.status = v.result.status; } else d.result = fromServer(v.result);
        }
        if (S.route === 'desk') render();
        toast(v.status === 'done' ? `Desk ${d.job.kind} finished` : `Desk ${d.job.kind} failed`, { error: v.status !== 'done' });
      }
    } catch (e) { scanLogDesk(`connection problem: ${e.message} (retrying)`); }
  }
}
function scanLogDesk(t) { S.desk.job?.lines.push(t); $('#desklog')?.insertAdjacentHTML('beforeend', logLine({ t, k: 'no' })); }

// ---- SETTINGS -----------------------------------------------------------------------------------
function settingsScreen() {
  const st = S.settings;
  const numField = (name, label, help, val, step, min, max) => `<div class="setting"><div class="grow"><b>${label}</b><small>${help}</small></div><input class="input num" type="number" inputmode="decimal" name="${name}" value="${val}" step="${step}" min="${min}" max="${max}"></div>`;
  const sw = (name, label, help) => `<div class="setting"><div class="grow"><b>${label}</b><small>${help}</small></div><label class="switch"><input type="checkbox" name="${name}" ${st[name] ? 'checked' : ''}><i></i></label></div>`;
  return `
    <div class="section-title"><h2>Appearance</h2></div>
    <div class="card"><div class="seg">${[['system', 'System'], ['dark', 'Dark'], ['light', 'Light']].map(([v, l]) => `<button data-set="theme" data-val="${v}" class="${st.theme === v ? 'on' : ''}">${l}</button>`).join('')}</div></div>
    <div class="section-title"><h2>Risk management</h2></div>
    <div class="card" style="padding-top:4px;padding-bottom:4px">
      ${numField('risk_pct', 'Risk per trade', '% of equity lost if the stop is hit', +(st.risk_per_trade * 100).toFixed(2), 0.1, 0.1, 5)}
      ${numField('max_open_positions', 'Max open positions', 'the risk manager stops adding beyond this', st.max_open_positions, 1, 1, 20)}
      ${numField('top_n', 'Picks per scan', 'the decision maker keeps at most this many', st.top_n, 1, 1, 10)}
      ${numField('min_conviction', 'Minimum conviction', 'weighted agreement of all agents, 0-1', st.min_conviction, 0.05, 0, 1)}
      ${sw('halt_on_crisis', 'Halt in a crisis regime', 'no new trades when volatility spikes in a falling market')}
      ${sw('trail', 'Break-even stop after +1R', 'paper positions lock in break-even once in profit')}
    </div>
    <div class="section-title"><h2>Data</h2></div>
    <div class="card" style="padding-top:4px;padding-bottom:4px">
      ${numField('years', 'History (years)', 'more history = better backtests, slower scans', st.years, 1, 3, 10)}
      <div class="setting"><div class="grow"><b>Cached market data</b><small>downloads are reused for 3 hours</small></div><button class="btn small" data-act="clearcache">${icon('trash')} Clear</button></div>
    </div>
    <div class="section-title"><h2>Paper account</h2></div>
    <div class="card" style="padding-top:4px;padding-bottom:4px">
      ${numField('paper_equity', 'Starting capital', 'used when the account is reset', st.paper_equity, 10000, 10000, 1e9)}
      <div class="setting"><div class="grow"><b>Reset paper account</b><small>clears positions and history</small></div><button class="btn small danger" data-act="resetpaper">Reset</button></div>
    </div>
    <div class="section-title"><h2>About</h2></div>
    <div class="card small muted"><div class="stack">
      <div><b style="color:var(--text)">AI Trading Desk</b> · app ${esc(appVersion())}${isNative ? '' : ' (browser preview)'}</div>
      <div>On the phone: liquidity, regime, technical, multi-timeframe, price action, 540-backtest optimizer, machine learning, risk and decision agents. On your desk server: news sentiment, fundamentals, Claude review and live broker orders.</div>
      <div>Market data: Yahoo Finance, end of day. Nothing here is investment advice. Backtests and model probabilities describe the past, not the future.</div>
    </div></div>`;
}

// ---- events -------------------------------------------------------------------------------------
async function onClick(e) {
  const nav = e.target.closest('[data-nav]');
  if (nav) { go(nav.dataset.nav); return; }
  const set = e.target.closest('[data-set]');
  if (set) {
    S.settings[set.dataset.set] = set.dataset.val;
    saveSettings();
    if (set.dataset.set === 'theme') applyTheme();
    render();
    return;
  }
  const a = e.target.closest('[data-act]');
  if (!a) return;
  const act = a.dataset.act;
  if (act === 'scan') startScan();
  else if (act === 'quickscan') startScan();
  else if (act === 'stopscan') { if (S.scan) { S.scan.running = false; finishScan(null, 'Scan stopped'); } }
  else if (act === 'layer') { S.openLayer = S.openLayer === a.dataset.name ? null : a.dataset.name; const st = S.route === 'desk' ? funnelFromResult(S.desk.result, true) : S.scan && (S.scan.running || S.scan.result) ? S.scan : S.lastScan ? funnelFromResult(S.lastScan) : null; if (st) { const L = LAYERS.find((x) => x.name === a.dataset.name); a.outerHTML = layerRow(L, st.layers[LAYERS.indexOf(L)], st); } }
  else if (act === 'trade') openTrade(a.dataset.src, +a.dataset.i);
  else if (act === 'paper') paperTrade(a.dataset.src, +a.dataset.i);
  else if (act === 'update') updatePortfolio();
  else if (act === 'position') positionSheet(a.dataset.sym);
  else if (act === 'cancelpos') { broker().cancel(a.dataset.sym); savePaper(); closeTop(); toast('Order cancelled'); render(); }
  else if (act === 'closepos') {
    const p = S.paper.positions[a.dataset.sym];
    if (p && (await dialog({ title: `Close ${short(a.dataset.sym)}?`, body: `Closes the paper position at the last price ${price(p.last_price)}.`, confirm: 'Close', danger: true }))) {
      const t = broker().closeNow(a.dataset.sym, p.last_price); savePaper(); closeTop(); toast(t ? `Closed ${signed(t.r_multiple, 2, 'R')}` : 'Cancelled'); render();
    }
  } else if (act === 'agentinfo') {
    const L = LAYERS.find((x) => x.name === a.dataset.name);
    sheet(`<div class="row"><div class="avatar" style="background:var(--grad)">L${L.layer}</div><h2 style="font-size:18px">${esc(L.title)}</h2></div><p class="muted">${esc(L.role)}</p>`);
  } else if (act === 'connect') {
    S.settings.deskUrl = $('[name=deskUrl]').value.trim();
    S.settings.deskToken = $('[name=deskToken]').value.trim();
    saveSettings();
    a.innerHTML = '<span class="spinner"></span> Connecting…';
    await deskConnect();
  } else if (act === 'disconnect') { S.desk.info = null; render(); }
  else if (act === 'deskjob') deskJob(a.dataset.kind);
  else if (act === 'deskstatus') {
    try { S.desk.status = await deskCall('/api/status'); render(); } catch (err) { toast(err.message, { error: true }); }
  } else if (act === 'clearcache') { await cacheClear(); toast('Market data cache cleared'); }
  else if (act === 'resetpaper') {
    if (await dialog({ title: 'Reset paper account?', body: `Positions and trade history are deleted and the account restarts with ${money(S.settings.paper_equity, cur())}.`, confirm: 'Reset', danger: true })) {
      S.paper = newAccount(+S.settings.paper_equity); savePaper(); toast('Paper account reset'); render();
    }
  }
}
function onChange(e) {
  const el = e.target, st = S.settings, name = el.name;
  if (!name) return;
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  if (name === 'preset') { st.preset = el.value; saveSettings(); render(); return; }
  if (name === 'deskSegment') st.deskSegment = el.value;
  else if (name === 'deskAi') st.deskAi = el.checked;
  else if (name === 'halt_on_crisis' || name === 'trail') st[name] = el.checked;
  else if (name === 'risk_pct') st.risk_per_trade = clamp(+el.value || 1, 0.1, 5) / 100;
  else if (name === 'max_open_positions') st.max_open_positions = clamp(Math.round(+el.value) || 5, 1, 20);
  else if (name === 'top_n') st.top_n = clamp(Math.round(+el.value) || 3, 1, 10);
  else if (name === 'min_conviction') st.min_conviction = clamp(+el.value, 0, 1);
  else if (name === 'years') st.years = clamp(Math.round(+el.value) || 6, 3, 10);
  else if (name === 'paper_equity') st.paper_equity = clamp(+el.value || 1e6, 1e4, 1e9);
  else return;
  saveSettings();
}

const SCREENS = { home: homeScreen, scan: scanScreen, portfolio: portfolioScreen, desk: deskScreen, settings: settingsScreen };
const AFTER = {
  scan: () => { const l = $('#log'); if (l) l.scrollTop = l.scrollHeight; },
  desk: () => { const l = $('#desklog'); if (l) l.scrollTop = l.scrollHeight; if (!S.desk.info && S.settings.deskUrl && !S.desk.tried) { S.desk.tried = true; deskConnect(); } },
};

// ---- start --------------------------------------------------------------------------------------
applyTheme();
shell();
go(location.hash.replace('#/', '') || 'home');
if (Object.keys(S.paper.positions).length) setTimeout(() => updatePortfolio(true), 800);
window.__app = { S, startScan, go };
