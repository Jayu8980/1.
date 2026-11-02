// Live prices: straight from Yahoo Finance on the phone (no login, may be a little delayed), or the
// broker's real-time last traded price through the desk server's /api/quotes.
import { http } from './native.js';

export const INDICES = [
  ['^NSEI', 'NIFTY 50'], ['^NSEBANK', 'BANK NIFTY'], ['^BSESN', 'SENSEX'], ['^INDIAVIX', 'INDIA VIX'],
];
export const isIndian = (s) => /\.(NS|BO)$/i.test(s) || /^\^(NSE|BSE|INDIA|CNX)/i.test(s) || /^NIFTY/i.test(s);

const IST_OFFSET = 330 * 60e3;
/** today's date in India, YYYY-MM-DD */
export const istDay = (now = Date.now()) => new Date(now + IST_OFFSET).toISOString().slice(0, 10);
/** NSE/BSE regular session, 09:15-15:30 IST on weekdays (exchange holidays are not known) */
export function nseOpen(now = Date.now()) {
  const d = new Date(now + IST_OFFSET), mins = d.getUTCHours() * 60 + d.getUTCMinutes(), wd = d.getUTCDay();
  return wd >= 1 && wd <= 5 && mins >= 555 && mins <= 930;
}

export async function yahooQuote(symbol) {
  const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=1d&interval=1d`;
  const r = await http({ url, headers: { Accept: 'application/json' }, timeout: 10000 });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  const meta = r.json()?.chart?.result?.[0]?.meta;
  if (!meta?.regularMarketPrice) throw new Error('no price');
  return { price: meta.regularMarketPrice, prev_close: meta.chartPreviousClose ?? meta.previousClose ?? null, time: meta.regularMarketTime || null, source: 'yahoo' };
}

/** quotes for many symbols from Yahoo, a few at a time */
export async function yahooQuotes(symbols, concurrency = 4) {
  const out = {};
  let next = 0;
  const worker = async () => {
    while (next < symbols.length) {
      const s = symbols[next++];
      try { out[s] = await yahooQuote(s); } catch { /* keep the last good quote */ }
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, symbols.length) }, worker));
  return out;
}

/** day change as a fraction, or null */
export const change = (q) => (q && q.prev_close ? q.price / q.prev_close - 1 : null);
