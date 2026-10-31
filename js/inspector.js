// Right-hand inspector panel
import { store, activeTrack, selectedNotes, commit, beginGesture, endGesture, emit } from './state.js';
import { SINGERS, STYLES, INSTRUMENTS, singerById } from './singers.js';
import { noteName, NOTE_NAMES, SCALES } from './util.js';
import { updateLiveMix } from './engine.js';

const closed = new Set(JSON.parse(safeGet('vf.closed') || '[]'));
function safeGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
function safeSet(k, v) { try { localStorage.setItem(k, v); } catch (e) { } }

function section(id, title, build) {
  const s = document.createElement('div'); s.className = 'sec' + (closed.has(id) ? ' closed' : '');
  const h = document.createElement('h3'); h.textContent = title;
  h.onclick = () => { s.classList.toggle('closed'); if (s.classList.contains('closed')) closed.add(id); else closed.delete(id); safeSet('vf.closed', JSON.stringify([...closed])); };
  const b = document.createElement('div'); b.className = 'body';
  build(b);
  s.append(h, b);
  return s;
}

function slider(parent, label, get, set, { min = 0, max = 1, step = 0.01, fmt = (v) => Math.round(v * 100), live, def } = {}) {
  const r = document.createElement('div'); r.className = 'row';
  r.innerHTML = `<label>${label}</label><input type="range" min="${min}" max="${max}" step="${step}"><span class="val"></span>`;
  const inp = r.querySelector('input'), val = r.querySelector('.val');
  inp.value = get(); val.textContent = fmt(+inp.value);
  inp.addEventListener('pointerdown', beginGesture);
  inp.addEventListener('input', () => { set(+inp.value); val.textContent = fmt(+inp.value); live && live(); emit('voice'); });
  inp.addEventListener('change', () => endGesture(label));
  if (def != null) inp.addEventListener('dblclick', () => { commit(label, () => set(def)); live && live(); });
  parent.appendChild(r);
  return inp;
}
function row(parent, label, el) {
  const r = document.createElement('div'); r.className = 'row';
  const l = document.createElement('label'); l.textContent = label; r.append(l, el); parent.appendChild(r); return el;
}
function select(options, value, onChange) {
  const s = document.createElement('select');
  for (const [v, l] of options) { const o = document.createElement('option'); o.value = v; o.textContent = l; s.appendChild(o); }
  s.value = value; s.onchange = () => onChange(s.value);
  s.addEventListener('keydown', (e) => e.stopPropagation());
  return s;
}
function button(label, fn, cls = 'btn') { const b = document.createElement('button'); b.className = cls; b.textContent = label; b.onclick = fn; return b; }
const cmd = (c) => import('./app.js').then((m) => m.runCmd(c));

export function renderInspector() {
  const root = document.getElementById('inspector');
  const st = root.scrollTop;
  root.innerHTML = '';
  const tr = activeTrack();
  if (tr && tr.type === 'vocal') {
    root.appendChild(section('singer', 'Singer', (b) => {
      const grid = document.createElement('div'); grid.className = 'singers';
      for (const s of SINGERS) {
        const el = document.createElement('button'); el.className = 'singer' + (tr.singer === s.id ? ' on' : '');
        el.innerHTML = `<div class="av" style="background:${s.color}">${s.name[0]}</div><div><b>${s.name}</b><small>${s.desc}</small></div>`;
        el.onclick = () => commit('Change singer', () => { tr.singer = s.id; });
        el.title = `${s.name} · range ${noteName(s.range[0])}–${noteName(s.range[1])}`;
        grid.appendChild(el);
      }
      b.appendChild(grid);
      row(b, 'Blend with', select([['', '— none —'], ...SINGERS.filter((s) => s.id !== tr.singer).map((s) => [s.id, s.name])], tr.blendSinger || '', (v) => commit('Voice blend', () => { tr.blendSinger = v || null; if (v && !tr.blend) tr.blend = 0.5; })));
      if (tr.blendSinger) slider(b, 'Blend', () => tr.blend, (v) => { tr.blend = v; }, { def: 0.5 });
      const s = singerById(tr.singer);
      const range = document.createElement('div'); range.className = 'muted';
      range.textContent = `Comfortable range: ${noteName(s.range[0])} – ${noteName(s.range[1])}`;
      b.appendChild(range);
    }));
    root.appendChild(section('voice', 'Voice & Expression', (b) => {
      const chips = document.createElement('div'); chips.className = 'chips';
      for (const [name, st] of Object.entries(STYLES)) chips.appendChild(button(name, () => commit('Style ' + name, () => Object.assign(tr.voice, st)), 'chip'));
      b.appendChild(chips);
      const v = tr.voice;
      slider(b, 'Gender', () => v.gender, (x) => { v.gender = x; }, { def: 0.5, fmt: (x) => Math.round((x - 0.5) * 200) });
      slider(b, 'Breathiness', () => v.breath, (x) => { v.breath = x; }, { def: 0.5 });
      slider(b, 'Tension', () => v.tension, (x) => { v.tension = x; }, { def: 0.5 });
      slider(b, 'Vibrato depth', () => v.vibDepth, (x) => { v.vibDepth = x; }, { def: 0.5 });
      slider(b, 'Vibrato rate', () => v.vibRate, (x) => { v.vibRate = x; }, { def: 0.5 });
      slider(b, 'Portamento', () => v.portamento, (x) => { v.portamento = x; }, { def: 0.5 });
      slider(b, 'Naturalness', () => v.naturalness, (x) => { v.naturalness = x; }, { def: 0.6 });
      slider(b, 'Ensemble', () => v.voices, (x) => { v.voices = x; }, { min: 1, max: 6, step: 1, fmt: (x) => x + 'v', def: 1 });
    }));
  }
  if (tr && tr.type === 'instrument') {
    root.appendChild(section('inst', 'Instrument', (b) => {
      row(b, 'Sound', select(INSTRUMENTS.map((i) => [i.id, i.name]), tr.instrument, (v) => commit('Change instrument', () => { tr.instrument = v; })));
    }));
  }
  if (tr && tr.type === 'audio') {
    root.appendChild(section('audio', 'Audio Track', (b) => {
      const m = document.createElement('div'); m.className = 'muted';
      m.textContent = tr.clips.length ? `${tr.clips.length} clip(s). Drag clips in the arrangement to move them; right-click to delete.` : 'No audio yet. Import a backing track (WAV, MP3, OGG…).';
      b.appendChild(m);
      b.appendChild(button('Import Audio…', () => cmd('importAudio'), 'btn primary'));
    }));
  }
  // notes
  if (tr && tr.type !== 'audio') {
    const sel = selectedNotes();
    root.appendChild(section('notes', sel.length ? `Notes (${sel.length} selected)` : 'Notes', (b) => {
      if (!sel.length) {
        const m = document.createElement('div'); m.className = 'muted';
        m.innerHTML = `${tr.notes.length} notes on this track. Select notes to edit their lyric, velocity and vibrato.`;
        b.appendChild(m);
      } else {
        if (tr.type === 'vocal') {
          const inp = document.createElement('input'); inp.type = 'text';
          const lyr = [...new Set(sel.map((n) => n.lyric))];
          inp.value = lyr.length === 1 ? lyr[0] : ''; inp.placeholder = lyr.length > 1 ? '(multiple)' : 'lyric';
          inp.addEventListener('keydown', (e) => { e.stopPropagation(); if (e.key === 'Enter') inp.blur(); });
          inp.addEventListener('change', () => commit('Edit lyric', () => sel.forEach((n) => { n.lyric = inp.value.trim(); })));
          row(b, 'Lyric', inp);
        }
        const info = document.createElement('div'); info.className = 'muted';
        const lo = Math.min(...sel.map((n) => n.pitch)), hi = Math.max(...sel.map((n) => n.pitch));
        info.textContent = `Pitch ${lo === hi ? noteName(lo) : noteName(lo) + ' – ' + noteName(hi)}`;
        b.appendChild(info);
        const avg = (k, d) => sel.reduce((a, n) => a + (n[k] ?? d), 0) / sel.length;
        slider(b, 'Velocity', () => avg('vel', 0.8), (x) => sel.forEach((n) => { n.vel = x; }), { def: 0.8 });
        if (tr.type === 'vocal') slider(b, 'Note vibrato', () => avg('vib', 1), (x) => sel.forEach((n) => { n.vib = x; }), { max: 2, def: 1 });
        const g = document.createElement('div'); g.className = 'btn-grid';
        g.append(button('Octave −', () => cmd('octDown')), button('Octave +', () => cmd('octUp')), button('Semitone −', () => cmd('semiDown')), button('Semitone +', () => cmd('semiUp')));
        b.appendChild(g);
      }
    }));
    root.appendChild(section('ai', 'AI Tools', (b) => {
      const g = document.createElement('div'); g.className = 'btn-grid';
      if (tr.type === 'vocal') g.append(button('Enter lyrics…', () => cmd('lyrics')), button('Harmony…', () => cmd('harmony')), button('Auto expression', () => cmd('autoBreath')));
      g.append(button('Humanize', () => cmd('humanize')), button('Quantize', () => cmd('quantize')), button('Legato', () => cmd('legato')), button('Snap to key', () => cmd('fitScale')));
      b.appendChild(g);
      const m = document.createElement('div'); m.className = 'muted';
      m.textContent = 'Tools apply to selected notes, or to the whole track when nothing is selected.';
      b.appendChild(m);
    }));
  }
  if (tr) {
    root.appendChild(section('mix', 'Track Mix', (b) => {
      slider(b, 'Volume', () => tr.volume, (x) => { tr.volume = x; }, { max: 1.2, live: updateLiveMix, def: 0.8 });
      slider(b, 'Pan', () => tr.pan, (x) => { tr.pan = x; }, { min: -1, max: 1, live: updateLiveMix, def: 0, fmt: (x) => (x === 0 ? 'C' : (x < 0 ? 'L' : 'R') + Math.round(Math.abs(x) * 100)) });
      slider(b, 'Reverb send', () => tr.reverbSend, (x) => { tr.reverbSend = x; }, { live: updateLiveMix, def: 0.25 });
      const col = document.createElement('input'); col.type = 'color'; col.value = tr.color; col.style.padding = '0'; col.style.height = '26px';
      col.onchange = () => commit('Track color', () => { tr.color = col.value; });
      row(b, 'Color', col);
    }));
  }
  root.appendChild(section('project', 'Project', (b) => {
    const p = store.project;
    row(b, 'Key', select(NOTE_NAMES.map((n, i) => [i, n]), p.key, (v) => commit('Key', () => { p.key = +v; })));
    row(b, 'Scale', select(Object.keys(SCALES).map((s) => [s, s[0].toUpperCase() + s.slice(1)]), p.scale, (v) => commit('Scale', () => { p.scale = v; })));
    slider(b, 'Master', () => p.masterVolume, (x) => { p.masterVolume = x; }, { max: 1.2, live: updateLiveMix, def: 0.85 });
    slider(b, 'Reverb', () => p.reverb, (x) => { p.reverb = x; }, { live: updateLiveMix, def: 0.35 });
  }));
  root.scrollTop = st;
}
