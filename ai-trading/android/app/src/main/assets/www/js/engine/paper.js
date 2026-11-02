// Paper broker (a port of trader/brokers/paper.py): orders fill at the next session's open, then
// stops, targets, break-even trailing and time exits are replayed on each new daily bar.
// The state is a plain object; the app persists it on the phone.
const dayOf = (ms) => new Date(ms).toISOString().slice(0, 10);

export function newAccount(equity) {
  return { cash: equity, start_equity: equity, positions: {}, closed: [], created: new Date().toISOString() };
}

export class PaperBroker {
  constructor(state, { fee = 0.0005, slip = 0.0005 } = {}) {
    this.state = state; this.fee = fee; this.slip = slip;
  }

  account() {
    let equity = this.state.cash, exposure = 0;
    for (const p of Object.values(this.state.positions)) {
      if (p.status !== 'open') continue;
      const v = p.qty * p.last_price;
      equity += (p.side === 'long' ? 1 : -1) * v;
      exposure += v;
    }
    return { equity, cash: this.state.cash, start_equity: this.state.start_equity, exposure };
  }

  positions() { return Object.entries(this.state.positions).map(([symbol, p]) => ({ symbol, ...p })); }
  closedTrades() { return [...this.state.closed]; }

  submit(plan) {
    if (this.state.positions[plan.symbol]) throw new Error(`${plan.symbol}: a position or order is already open`);
    this.state.positions[plan.symbol] = {
      status: 'pending', side: plan.side, qty: plan.qty, entry: null, stop: plan.stop, initial_stop: plan.stop, target: plan.target,
      max_hold: plan.max_hold, setup: plan.setup || null, signal_date: plan.signal_date, checked_through: plan.signal_date,
      bars_held: 0, last_price: plan.entry_ref, entry_ref: plan.entry_ref, opened: null, segment: plan.segment || 'delivery',
    };
    return { id: `paper-${plan.symbol}-${Date.now()}`, status: 'pending', reason: "fills at the next session's open" };
  }

  cancel(symbol) {
    const p = this.state.positions[symbol];
    if (!p) return false;
    if (p.status !== 'pending') throw new Error(`${symbol} is already filled - close it instead`);
    delete this.state.positions[symbol];
    return true;
  }

  /** Close an open position now at the given price (manual exit). */
  closeNow(symbol, price) {
    const p = this.state.positions[symbol];
    if (!p) throw new Error(`${symbol}: no position`);
    if (p.status === 'pending') { this.cancel(symbol); return null; }
    return this._close(symbol, price, 'manual', dayOf(Date.now()));
  }

  /**
   * Replay new daily bars for every open or pending position. frames: {symbol: bars}.
   * liveDay(symbol) returns the date (YYYY-MM-DD) of a session still in progress for that symbol:
   * its candle is unfinished, so it can fill orders and hit stops or targets, but it isn't counted
   * as a finished bar (no time exit, and it's replayed again on the next update).
   */
  manage(frames, trail = true, liveDay = () => null) {
    const done = [];
    for (const [symbol, p] of Object.entries(this.state.positions)) {
      const b = frames[symbol];
      if (!b || !b.c.length) continue;
      const d = p.side === 'long' ? 1 : -1, live = liveDay(symbol);
      for (let i = 0; i < b.t.length; i++) {
        const day = dayOf(b.t[i]);
        if (day <= p.checked_through) continue;
        const o = b.o[i], h = b.h[i], l = b.l[i], c = b.c[i], partial = day === live;
        if (!partial) p.checked_through = day;
        let first = false;
        if (p.status === 'pending') {
          const fill = o * (1 + d * this.slip);
          if (d * (fill - p.stop) <= 0 || d * (p.target - fill) <= 0) {
            delete this.state.positions[symbol];
            done.push({ symbol, side: p.side, qty: p.qty, status: 'cancelled', reason: 'opened beyond the stop or target', closed: day });
            break;
          }
          this.state.cash -= d * fill * p.qty + fill * p.qty * this.fee;
          const shift = fill - (p.entry_ref ?? fill); // keep the planned distances around the real fill
          Object.assign(p, { status: 'open', entry: fill, opened: day, stop: p.stop + shift, target: p.target + shift });
          p.initial_stop = p.stop;
          first = true;
        } else if (p.opened === day) first = true; // filled in this candle on an earlier update
        if (!partial) p.bars_held += 1;
        p.last_price = c;
        let px = null, reason = null;
        if (d > 0) {
          if (l <= p.stop) [px, reason] = [first ? p.stop : Math.min(o, p.stop), 'stop'];
          else if (h >= p.target) [px, reason] = [first ? p.target : Math.max(o, p.target), 'target'];
        } else if (h >= p.stop) [px, reason] = [first ? p.stop : Math.max(o, p.stop), 'stop'];
        else if (l <= p.target) [px, reason] = [first ? p.target : Math.min(o, p.target), 'target'];
        if (px === null && !partial && p.bars_held >= p.max_hold) [px, reason] = [c, 'time'];
        if (px !== null) { done.push(this._close(symbol, px, reason, day)); break; }
        const oneR = Math.abs(p.entry - p.initial_stop);
        if (trail && d * (c - p.entry) >= oneR) p.stop = d > 0 ? Math.max(p.stop, p.entry) : Math.min(p.stop, p.entry);
      }
    }
    return done;
  }

  /**
   * A live price during the session: marks open positions to market and exits at the live price
   * when it is at or through the stop or target (the price you would really get, gaps included).
   */
  liveTick(symbol, price, day) {
    const p = this.state.positions[symbol];
    if (!p || p.status !== 'open' || !(price > 0)) return null;
    p.last_price = price;
    const d = p.side === 'long' ? 1 : -1;
    if (d * (price - p.stop) <= 0) return this._close(symbol, price, 'stop', day);
    if (d * (price - p.target) >= 0) return this._close(symbol, price, 'target', day);
    return null;
  }

  _close(symbol, price, reason, day) {
    const p = this.state.positions[symbol];
    delete this.state.positions[symbol];
    const d = p.side === 'long' ? 1 : -1, qty = p.qty, fill = price * (1 - d * this.slip);
    this.state.cash += d * fill * qty - fill * qty * this.fee;
    const pnl = d * (fill - p.entry) * qty - (p.entry + fill) * qty * this.fee;
    const risk = Math.abs(p.entry - p.initial_stop) * qty;
    const trade = { symbol, side: p.side, qty, entry: p.entry, exit: fill, pnl, r_multiple: risk ? pnl / risk : 0, opened: p.opened, closed: day,
      reason, setup: p.setup, status: 'closed' };
    this.state.closed.push(trade);
    return trade;
  }
}
