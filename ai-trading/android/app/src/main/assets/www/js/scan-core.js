// The scan itself, shared by the Web Worker and the main-thread fallback.
import { runScan } from './engine/agents.js';
import { syntheticBars } from './engine/data.js';

export async function executeScan(data, post) {
  const { cfg, symbols, frames, benchmark, volIndex, account, now, synthetic, bars, yieldMs = 60 } = data;
  try {
    if (synthetic) for (const s of [...symbols, benchmark]) frames[s] = syntheticBars(s, bars, now);
    let last = 0;
    const result = await runScan({
      cfg, symbols, frames, benchmark, volIndex, now,
      account: { ...account, held: new Set(account.held || []) },
      emit: (e) => post({ type: 'event', event: e }),
      // yield regularly so progress reaches the screen
      tick: async () => { const t = Date.now(); if (t - last > yieldMs) { last = t; await new Promise((r) => setTimeout(r, 0)); } },
    });
    post({ type: 'result', result });
  } catch (err) {
    post({ type: 'error', message: err?.message || String(err) });
  }
}
