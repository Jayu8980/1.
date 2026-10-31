// Lyric → phoneme analysis for the formant vocal engine.
// Supports English-ish spelling, romaji and Japanese kana.

export const FORMANTS = {
  a: [800, 1200, 2500, 3500],
  e: [480, 1850, 2550, 3500],
  i: [300, 2250, 2950, 3700],
  o: [500, 850, 2450, 3400],
  u: [340, 750, 2350, 3350],
  '@': [550, 1400, 2500, 3500],
  l: [380, 1100, 2600, 3500],
  r: [450, 1250, 1650, 3200],
  m: [280, 1000, 2300, 3300],
  n: [280, 1500, 2500, 3300],
  w: [320, 650, 2300, 3300],
  y: [280, 2300, 3000, 3700],
};

export const CONS = {
  s: { type: 'fric', f: 6500, q: 1.2, g: 0.5, d: 0.11 },
  z: { type: 'fric', f: 6000, q: 1.2, g: 0.3, d: 0.08, voiced: true },
  sh: { type: 'fric', f: 2800, q: 1.5, g: 0.5, d: 0.12 },
  ch: { type: 'affr', f: 3000, q: 1.5, g: 0.55, d: 0.09 },
  j: { type: 'affr', f: 2800, q: 1.5, g: 0.35, d: 0.07, voiced: true },
  ts: { type: 'affr', f: 5500, q: 1.3, g: 0.5, d: 0.08 },
  f: { type: 'fric', f: 4500, q: 0.7, g: 0.22, d: 0.1 },
  ph: { type: 'fric', f: 4500, q: 0.7, g: 0.22, d: 0.1 },
  v: { type: 'fric', f: 4000, q: 0.7, g: 0.15, d: 0.07, voiced: true },
  th: { type: 'fric', f: 5000, q: 0.6, g: 0.18, d: 0.08 },
  h: { type: 'asp', f: 1500, q: 0.5, g: 0.1, d: 0.08 },
  wh: { type: 'glide', formant: 'w', d: 0.07 },
  x: { type: 'affr', f: 5500, q: 1.2, g: 0.45, d: 0.1 },
  p: { type: 'stop', f: 900, q: 0.8, g: 0.5, d: 0.06 },
  b: { type: 'stop', f: 900, q: 0.8, g: 0.25, d: 0.045, voiced: true },
  t: { type: 'stop', f: 4200, q: 1, g: 0.55, d: 0.06 },
  d: { type: 'stop', f: 4000, q: 1, g: 0.3, d: 0.045, voiced: true },
  k: { type: 'stop', f: 2200, q: 1, g: 0.55, d: 0.07 },
  c: { type: 'stop', f: 2200, q: 1, g: 0.55, d: 0.07 },
  ck: { type: 'stop', f: 2200, q: 1, g: 0.55, d: 0.07 },
  q: { type: 'stop', f: 2200, q: 1, g: 0.55, d: 0.07 },
  kh: { type: 'fric', f: 2000, q: 0.8, g: 0.35, d: 0.09 },
  g: { type: 'stop', f: 2000, q: 1, g: 0.3, d: 0.05, voiced: true },
  gh: { type: 'stop', f: 2000, q: 1, g: 0.3, d: 0.05, voiced: true },
  m: { type: 'nasal', formant: 'm', d: 0.07 },
  n: { type: 'nasal', formant: 'n', d: 0.06 },
  ng: { type: 'nasal', formant: 'n', d: 0.07 },
  l: { type: 'liquid', formant: 'l', d: 0.06 },
  r: { type: 'liquid', formant: 'r', d: 0.07 },
  w: { type: 'glide', formant: 'w', d: 0.07 },
  y: { type: 'glide', formant: 'y', d: 0.06 },
};

const DIGRAPHS = ['sh', 'ch', 'th', 'ts', 'ph', 'ng', 'ck', 'wh', 'kh', 'gh'];
const DIPH = {
  ee: ['i'], ea: ['i'], ie: ['i'], ei: ['e', 'i'], ai: ['a', 'i'], oi: ['o', 'i'], au: ['a', 'u'],
  ou: ['o', 'u'], oo: ['u'], ue: ['u'], ui: ['u', 'i'], oa: ['o', 'u'], io: ['i', 'o'], ia: ['i', 'a'],
  eu: ['e', 'u'], ae: ['a', 'e'], ao: ['a', 'o'], ua: ['u', 'a'], uo: ['u', 'o'],
};

const KANA_ROWS = {
  'あいうえお': ['a', 'i', 'u', 'e', 'o'], 'かきくけこ': ['ka', 'ki', 'ku', 'ke', 'ko'], 'さしすせそ': ['sa', 'shi', 'su', 'se', 'so'],
  'たちつてと': ['ta', 'chi', 'tsu', 'te', 'to'], 'なにぬねの': ['na', 'ni', 'nu', 'ne', 'no'], 'はひふへほ': ['ha', 'hi', 'fu', 'he', 'ho'],
  'まみむめも': ['ma', 'mi', 'mu', 'me', 'mo'], 'やゆよ': ['ya', 'yu', 'yo'], 'らりるれろ': ['ra', 'ri', 'ru', 're', 'ro'],
  'わをん': ['wa', 'o', 'n'], 'がぎぐげご': ['ga', 'gi', 'gu', 'ge', 'go'], 'ざじずぜぞ': ['za', 'ji', 'zu', 'ze', 'zo'],
  'だぢづでど': ['da', 'ji', 'zu', 'de', 'do'], 'ばびぶべぼ': ['ba', 'bi', 'bu', 'be', 'bo'], 'ぱぴぷぺぽ': ['pa', 'pi', 'pu', 'pe', 'po'],
};
const KANA = {};
for (const [chars, roms] of Object.entries(KANA_ROWS)) {
  [...chars].forEach((ch, i) => {
    KANA[ch] = roms[i];
    KANA[String.fromCharCode(ch.charCodeAt(0) + 0x60)] = roms[i]; // katakana
  });
}
const SMALL = { 'ゃ': 'ya', 'ゅ': 'yu', 'ょ': 'yo', 'ャ': 'ya', 'ュ': 'yu', 'ョ': 'yo' };

export function kanaToRomaji(s) {
  if (!/[぀-ヿ]/.test(s)) return s;
  let out = '';
  for (const ch of s) {
    if (SMALL[ch] && out) { out = out.replace(/i$/, '') + SMALL[ch].slice(out.endsWith('sh') || out.endsWith('ch') || out.endsWith('j') ? 1 : 0); }
    else if (ch === 'ー') out += '-';
    else if (ch === 'っ' || ch === 'ッ') out += '';
    else out += KANA[ch] ?? ch;
  }
  return out;
}

function tokenize(cluster) {
  const out = [];
  let i = 0;
  while (i < cluster.length) {
    const two = cluster.slice(i, i + 2);
    if (DIGRAPHS.includes(two) && CONS[two]) { out.push(two); i += 2; continue; }
    const c = cluster[i];
    if (CONS[c]) {
      if (out[out.length - 1] !== c) out.push(c); // collapse double letters
    }
    i++;
  }
  return out;
}

export function analyzeLyric(raw, prev) {
  let s = (raw || '').trim().toLowerCase();
  s = kanaToRomaji(s);
  const prevVowel = prev ? (prev.vowelEnd || prev.vowel) : 'a';
  if (!s || /^[-+~ー]+$/.test(s)) return { onset: [], vowel: prevVowel, vowelEnd: null, coda: [], cont: true };
  s = s.replace(/[^a-z]/g, '');
  if (!s) return { onset: [], vowel: 'a', vowelEnd: null, coda: [], cont: false };
  const m = s.match(/[aeiou]+/) || s.match(/y/);
  if (!m) {
    // pure consonant syllable (e.g. "n", "mm") → hum
    return { onset: [], vowel: s.includes('m') ? 'm' : 'n', vowelEnd: null, coda: [], cont: false };
  }
  const on = s.slice(0, m.index);
  const grp = m[0];
  let after = s.slice(m.index + grp.length);
  let vowel, vowelEnd = null;
  if (grp === 'y') vowel = 'i';
  else if (grp.length >= 2 && DIPH[grp.slice(0, 2)]) { const d = DIPH[grp.slice(0, 2)]; vowel = d[0]; vowelEnd = d[1] || null; }
  else vowel = grp[0];
  if (!vowelEnd && after[0] === 'y') { vowelEnd = 'i'; after = after.slice(1); }
  else if (!vowelEnd && after[0] === 'w') { vowelEnd = 'u'; after = after.slice(1); }
  // keep only consonants up to the next vowel; drop silent final e
  const nextV = after.search(/[aeiouy]/);
  if (nextV >= 0) after = after.slice(0, nextV);
  if (vowelEnd === vowel) vowelEnd = null;
  return { onset: tokenize(on), vowel, vowelEnd, coda: tokenize(after).filter((c) => c !== 'gh'), cont: false };
}

function splitWord(word) {
  const isV = (c, i) => /[aeiou]/i.test(c) || (/y/i.test(c) && i > 0);
  const groups = []; // [start,end) of vowel groups
  for (let i = 0; i < word.length;) {
    if (isV(word[i], i)) { let j = i; while (j < word.length && isV(word[j], j)) j++; groups.push([i, j]); i = j; } else i++;
  }
  // silent final e (not "-le")
  if (groups.length > 1) {
    const [a, b] = groups[groups.length - 1];
    if (b === word.length && b - a === 1 && /e/i.test(word[a]) && !/[^aeiou]le$/i.test(word)) groups.pop();
  }
  if (groups.length <= 1) return [word];
  const cuts = [];
  for (let g = 1; g < groups.length; g++) {
    const cStart = groups[g - 1][1], cEnd = groups[g][0], n = cEnd - cStart;
    const isLe = g === groups.length - 1 && /[^aeiou]le$/i.test(word) && groups[g][0] === word.length - 1;
    if (isLe) cuts.push(cEnd - 2 >= cStart ? cEnd - 2 : cStart);
    else cuts.push(n <= 1 ? cStart : cStart + 1);
  }
  const parts = []; let prev = 0;
  for (const c of cuts) { parts.push(word.slice(prev, c)); prev = c; }
  parts.push(word.slice(prev));
  return parts.filter(Boolean);
}

// Split a line of text into singable syllables.
export function syllabify(text, splitWords = true) {
  const words = text.replace(/\n/g, ' ').split(/\s+/).filter(Boolean);
  const out = [];
  for (const w of words) {
    if (!splitWords || /[぀-ヿ一-鿿]/.test(w)) {
      if (/[぀-ヿ]/.test(w)) {
        // each kana is one syllable (small kana attaches to previous)
        for (const ch of w) { if (SMALL[ch] && out.length) out[out.length - 1] += ch; else out.push(ch); }
      } else out.push(w);
      continue;
    }
    const clean = w.replace(/[^A-Za-z'\-]/g, '');
    if (!clean) continue;
    out.push(...splitWord(clean));
  }
  return out;
}
