// Engine tests for the phone app: run with `node --test tests/js`.
import test from 'node:test';
import assert from 'node:assert/strict';
import { syntheticBars, parseChart, crc32 } from '../../android/app/src/main/assets/www/js/engine/data.js';
import { computeAll } from '../../android/app/src/main/assets/www/js/engine/series.js';
import { runScan, LAYERS } from '../../android/app/src/main/assets/www/js/engine/agents.js';
import { DEFAULTS, withStyle, merge } from '../../android/app/src/main/assets/www/js/engine/config.js';
import { PaperBroker, newAccount } from '../../android/app/src/main/assets/www/js/engine/paper.js';
import { optimize, countBacktests } from '../../android/app/src/main/assets/www/js/engine/strategy.js';
import { auc } from '../../android/app/src/main/assets/www/js/engine/ml.js';
import presets from '../../android/app/src/main/assets/www/js/engine/presets.js';

const NOW = Date.UTC(2026, 9, 30);

test('crc32 matches zlib', () => assert.equal(crc32('RELIANCE.NS'), 0xf74ea650));

test('synthetic bars are deterministic and well formed', () => {
  const a = syntheticBars('TCS.NS', 800, NOW), b = syntheticBars('TCS.NS', 800, NOW);
  assert.deepEqual(Array.from(a.c), Array.from(b.c));
  for (let i = 0; i < 800; i++) {
    assert.ok(a.h[i] >= Math.max(a.o[i], a.c[i]) && a.l[i] <= Math.min(a.o[i], a.c[i]) && a.l[i] > 0);
  }
  assert.ok(a.t.every((t, i) => i === 0 || t > a.t[i - 1]));
});

test('Yahoo chart parsing adjusts prices and drops empty rows', () => {
  const json = { chart: { result: [{ meta: { gmtoffset: 19800 }, timestamp: [1700000000, 1700086400, 1700172800],
    indicators: { quote: [{ open: [10, null, 12], high: [11, null, 13], low: [9, null, 11], close: [10, null, 12], volume: [100, null, 300] }],
      adjclose: [{ adjclose: [5, null, 6] }] } }] } };
  const b = parseChart(json);
  assert.equal(b.c.length, 2);
  assert.equal(b.c[0], 5);
  assert.equal(b.h[1], 6.5);
  assert.equal(b.v[1], 300);
});

test('optimizer runs the full 540-backtest grid', () => {
  assert.equal(countBacktests(DEFAULTS.optimizer), 540);
  const bars = syntheticBars('INFY.NS', 1500, NOW), ind = computeAll(bars, null);
  const variants = optimize(ind, new Float64Array(1500).fill(0), 1, DEFAULTS.optimizer);
  assert.ok(variants.length > 0);
  for (const v of variants) assert.ok(v.robustness >= 0 && v.robustness <= 1);
});

test('auc of a perfect ranking is 1', () => assert.equal(auc([0, 0, 1, 1], [0.1, 0.2, 0.8, 0.9]), 1));

test('every preset is well formed', () => {
  for (const [key, p] of Object.entries(presets.presets)) {
    assert.ok(p.label && p.symbols.length > 0 && p.benchmark, key);
  }
});

async function scan(style, n = 12) {
  const preset = presets.presets.nifty50, symbols = preset.symbols.slice(0, n);
  const frames = {};
  for (const s of [...symbols, preset.benchmark]) frames[s] = syntheticBars(s, 1500, NOW);
  const cfg = withStyle(merge(DEFAULTS, { style }));
  const events = [];
  const res = await runScan({ cfg, symbols, frames, benchmark: preset.benchmark, volIndex: null,
    account: { equity: 1e6, cash: 1e6, positions: [], held: new Set() }, emit: (e) => events.push(e), now: NOW });
  return { res, events, frames, cfg };
}

test('the funnel runs every layer on synthetic data', async () => {
  const { res, events } = await scan('delivery');
  assert.equal(res.funnel.length, LAYERS.length);
  for (let i = 1; i < res.funnel.length; i++) assert.ok(res.funnel[i].entered === res.funnel[i - 1].passed);
  assert.equal(events[0].topic, 'start');
  assert.equal(events.at(-1).topic, 'done');
  for (const t of res.trades) {
    assert.ok(t.plan.qty > 0 && t.rank >= 1);
    assert.ok(t.side === 'long' ? t.plan.stop < t.plan.entry_ref && t.plan.target > t.plan.entry_ref : t.plan.stop > t.plan.entry_ref);
  }
  // nothing survives a layer without a report from it
  for (const t of res.trades) for (const L of LAYERS) assert.ok(t.reports[L.name], `${t.symbol} ${L.name}`);
});

test('intraday style allows shorts and one-bar holds', async () => {
  const { res } = await scan('intraday', 6);
  for (const t of res.trades) assert.equal(t.plan.max_hold, 1);
});

test('paper broker fills next open, re-anchors levels and exits', () => {
  const state = newAccount(100000), pb = new PaperBroker(state, { fee: 0, slip: 0 });
  const day = (k) => Date.UTC(2026, 0, k);
  pb.submit({ symbol: 'X', side: 'long', qty: 10, entry_ref: 100, stop: 95, target: 110, max_hold: 5, signal_date: '2026-01-01' });
  assert.throws(() => pb.submit({ symbol: 'X', side: 'long', qty: 1, entry_ref: 1, stop: 0.5, target: 2, max_hold: 1, signal_date: '2026-01-01' }));
  const bars = { t: [day(1), day(2), day(3)], o: [100, 102, 104], h: [101, 104, 113], l: [99, 101, 103], c: [100, 103, 112], v: [1, 1, 1] };
  const done = pb.manage({ X: bars });
  assert.equal(done.length, 1);
  assert.equal(done[0].reason, 'target');
  assert.equal(done[0].entry, 102);
  assert.equal(done[0].exit, 112); // target re-anchored: 110 + 2
  assert.equal(state.cash, 100000 + 100);
  assert.ok(Math.abs(done[0].r_multiple - 2) < 1e-9);
});

test('paper broker cancels an order that gaps through its stop', () => {
  const state = newAccount(1000), pb = new PaperBroker(state, { fee: 0, slip: 0 });
  pb.submit({ symbol: 'Y', side: 'long', qty: 1, entry_ref: 100, stop: 95, target: 110, max_hold: 5, signal_date: '2026-01-01' });
  const done = pb.manage({ Y: { t: [Date.UTC(2026, 0, 2)], o: [90], h: [91], l: [89], c: [90], v: [0] } });
  assert.equal(done[0].status, 'cancelled');
  assert.equal(state.cash, 1000);
});

test('an unfinished session candle fills and exits but is not counted or time-exited', () => {
  const state = newAccount(100000), pb = new PaperBroker(state, { fee: 0, slip: 0 });
  pb.submit({ symbol: 'Z', side: 'long', qty: 10, entry_ref: 100, stop: 95, target: 110, max_hold: 1, signal_date: '2026-01-01' });
  const today = '2026-01-02', liveDay = () => today;
  const partial = { t: [Date.UTC(2026, 0, 1), Date.UTC(2026, 0, 2)], o: [100, 100], h: [100, 103], l: [100, 99], c: [100, 102], v: [1, 1] };
  assert.deepEqual(pb.manage({ Z: partial }, true, liveDay), []);
  const p = state.positions.Z;
  assert.equal(p.status, 'open');
  assert.equal(p.bars_held, 0, 'the live candle is not a finished bar, so max_hold=1 does not close it');
  assert.equal(p.checked_through, '2026-01-01');
  assert.equal(state.cash, 100000 - 1000);
  // the same session later on: replayed without filling twice
  partial.h[1] = 104; partial.c[1] = 103;
  assert.deepEqual(pb.manage({ Z: partial }, true, liveDay), []);
  assert.equal(state.cash, 100000 - 1000);
  // live tick through the target
  const t = pb.liveTick('Z', 111, today);
  assert.equal(t.reason, 'target');
  assert.equal(t.exit, 111);
});

test('a finished day after a live session counts and can time-exit', () => {
  const state = newAccount(100000), pb = new PaperBroker(state, { fee: 0, slip: 0 });
  pb.submit({ symbol: 'Z', side: 'short', qty: 1, entry_ref: 100, stop: 105, target: 90, max_hold: 1, signal_date: '2026-01-01' });
  const bars = { t: [Date.UTC(2026, 0, 2)], o: [100], h: [101], l: [99], c: [99.5], v: [1] };
  pb.manage({ Z: bars }, true, () => '2026-01-02');
  assert.equal(state.positions.Z.status, 'open');
  const done = pb.manage({ Z: bars }, true, () => null); // the session has closed
  assert.equal(done[0].reason, 'time');
  assert.equal(pb.liveTick('NOPE', 1, 'x'), null);
});
