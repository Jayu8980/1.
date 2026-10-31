// VoxForge Studio – main application wiring
import { store, subscribe, emit, commit, undo, redo, newProject, demoProject, newTrack, activeTrack, selectedNotes, setActiveTrack, ticksPerBar, tickToSec, songEndTick, resetHistory, secToTick } from './state.js';
import { PPQ, clamp, uid, fmtTime, downloadBlob, debounce } from './util.js';
import { initRoll, drawRoll, zoomToNotes, followPlayhead, startLyricEdit, sortNotes, fmtPos, KEYW, rollWidth } from './pianoroll.js';
import { initParams, drawParams } from './paramlane.js';
import { initArrange, drawArrange, renderTrackList } from './arrange.js';
import { renderInspector } from './inspector.js';
import { setDrawers, invalidate, modal, toast, setStatus } from './ui.js';
import { engine, play, stop, currentTick, exportWav, updateLiveMix } from './engine.js';
import { parseMidi, writeMidi } from './midi.js';
import { saveToFile, loadSerialized, autosave, loadAutosave, importAudioFile } from './io.js';
import { quantize, humanize, legato, fitScale, makeHarmony, autoExpression, applyLyrics, HARMONY_TYPES } from './tools.js';
import { syllabify } from './lyrics.js';
import { SINGERS } from './singers.js';

const $ = (id) => document.getElementById(id);
const doAutosave = debounce(autosave, 800);

// ---------- boot ----------
function boot() {
  store.ui.loop = { on: false, start: 0, end: PPQ * 16 };
  demoProject();
  store.ui.activeTrackId = store.project.tracks[0].id;
  const saved = loadAutosave();
  if (saved) {
    loadSerialized(saved).then(() => { afterLoad(); setStatus('Restored your last session'); }).catch(() => { demoProject(); store.ui.activeTrackId = store.project.tracks[0].id; afterLoad(); });
  }

  setDrawers({ roll: drawRoll, param: drawParams, arrange: drawArrange });
  initRoll($('rollCanvas'));
  initParams($('paramCanvas'), $('paramTabs'));
  initArrange($('arrangeCanvas'));
  initMenus();
  initTransport();
  initSplitters();
  initHScroll();
  initKeys();
  initDnD();

  subscribe(onChange);
  renderAll();
  requestAnimationFrame(() => zoomToNotes());
  requestAnimationFrame(loop);

  window.addEventListener('beforeunload', (e) => { autosave(); });
  if ('serviceWorker' in navigator && location.protocol.startsWith('http')) navigator.serviceWorker.register('sw.js').catch(() => { });
}

function afterLoad() {
  if (!activeTrack()) store.ui.activeTrackId = store.project.tracks[0]?.id;
  renderAll();
  requestAnimationFrame(() => zoomToNotes());
}

function renderAll() {
  renderTrackList();
  renderInspector();
  syncHeader();
  invalidate('roll', 'param', 'arrange');
}

function onChange(what) {
  switch (what) {
    case 'project':
      renderTrackList(); renderInspector(); syncHeader(); invalidate(); doAutosave(); updateScrollExtent(); break;
    case 'track': renderTrackList(); renderInspector(); invalidate(); break;
    case 'selection': renderInspectorDebounced(); invalidate('roll'); updateSelStatus(); break;
    case 'notes': invalidate('roll', 'arrange', 'param'); break;
    case 'params': case 'voice': invalidate('roll', 'param'); break;
    case 'view': invalidate('roll', 'param', 'arrange'); syncHScroll(); break;
    case 'playhead': case 'loop': invalidate(); updateLcd(); break;
  }
}
const renderInspectorDebounced = debounce(renderInspector, 30);

function syncHeader() {
  const p = store.project;
  if (document.activeElement !== $('bpm')) $('bpm').value = p.bpm;
  $('timeSig').value = p.timeSig.join('/');
  if (document.activeElement !== $('projName')) $('projName').textContent = p.name;
  document.querySelectorAll('#toolSeg button').forEach((b) => b.classList.toggle('on', b.dataset.tool === store.ui.tool));
  $('snap').value = String(store.ui.snap);
  $('btnLoop').classList.toggle('on', !!store.ui.loop?.on);
  $('btnMetro').classList.toggle('on', store.ui.metronome);
  document.querySelectorAll('[data-check]').forEach((c) => c.classList.toggle('on', !!store.ui[c.dataset.check]));
  document.title = `${p.name} – VoxForge Studio`;
  updateLcd(); updateSelStatus();
}
function updateSelStatus() {
  const n = store.ui.selection.size;
  $('statusSel').textContent = n ? `${n} note${n > 1 ? 's' : ''} selected` : '';
  $('statusZoom').textContent = `Zoom ${Math.round(store.ui.view.pxPerTick * PPQ)} px/beat`;
}

// ---------- animation loop ----------
function loop() {
  if (engine.playing) {
    const t = currentTick();
    const lp = store.ui.loop;
    if (lp && lp.on && t >= lp.end && lp.end > lp.start) { store.ui.playhead = lp.start; play(lp.start); }
    else if (!lp?.on && t > songEndTick() + ticksPerBar() * 2 && !store.ui.metronome) { stopPlayback(true); }
    if (store.ui.follow) followPlayhead(currentTick());
    invalidate();
    updateLcd();
    $('statusAudio').textContent = `Audio: playing · ${engine.ctx.sampleRate} Hz`;
  }
  requestAnimationFrame(loop);
}
function updateLcd() {
  const t = currentTick();
  $('posBars').textContent = fmtPos(Math.max(0, Math.round(t)));
  $('posTime').textContent = fmtTime(tickToSec(t));
}

// ---------- transport ----------
export function togglePlay() { engine.playing ? stopPlayback(false) : startPlayback(); }
function startPlayback() {
  let from = store.ui.playhead;
  const lp = store.ui.loop;
  if (lp && lp.on && (from < lp.start || from >= lp.end)) from = lp.start;
  try { play(from); } catch (e) { console.error(e); toast('Audio error: ' + e.message, true); return; }
  $('btnPlay').classList.add('on');
  setStatus('Playing', true);
}
function stopPlayback(returnToStart) {
  const t = currentTick();
  stop();
  if (!returnToStart) store.ui.playhead = Math.max(0, Math.round(t));
  $('btnPlay').classList.remove('on');
  $('statusAudio').textContent = 'Audio: idle';
  setStatus('');
  emit('playhead');
}
export function restartPlayback() { if (engine.playing) play(store.ui.playhead); }

function initTransport() {
  $('btnPlay').onclick = togglePlay;
  $('btnStop').onclick = () => { stopPlayback(true); store.ui.playhead = store.ui.loop?.on ? store.ui.loop.start : 0; emit('playhead'); };
  $('btnHome').onclick = () => { store.ui.playhead = 0; restartPlayback(); store.ui.view.scrollX = 0; emit('view'); emit('playhead'); };
  $('btnLoop').onclick = () => runCmd('toggleLoop');
  $('btnMetro').onclick = () => runCmd('toggleMetro');
  $('bpm').addEventListener('change', () => { const v = clamp(+$('bpm').value || 120, 30, 300); commit('Tempo', () => { store.project.bpm = v; }); restartPlayback(); });
  $('bpm').addEventListener('keydown', (e) => { e.stopPropagation(); if (e.key === 'Enter') $('bpm').blur(); });
  $('timeSig').addEventListener('change', () => commit('Time signature', () => { store.project.timeSig = $('timeSig').value.split('/').map(Number); }));
  $('snap').addEventListener('change', () => { store.ui.snap = +$('snap').value; invalidate('roll'); });
  document.querySelectorAll('#toolSeg button').forEach((b) => (b.onclick = () => setTool(b.dataset.tool)));
  const pn = $('projName');
  pn.addEventListener('keydown', (e) => { e.stopPropagation(); if (e.key === 'Enter') { e.preventDefault(); pn.blur(); } });
  pn.addEventListener('blur', () => { const v = pn.textContent.trim() || 'Untitled Song'; if (v !== store.project.name) commit('Rename project', () => { store.project.name = v; }); });
}
function setTool(t) { store.ui.tool = t; syncHeader(); invalidate('roll'); setStatus({ select: 'Select tool — drag to move, edges to resize, Alt-drag to copy', draw: 'Draw tool — click & drag to create notes, right-drag to erase', erase: 'Erase tool — click or drag over notes', split: 'Split tool — click a note to split it at the grid' }[t]); }

// ---------- menus ----------
function initMenus() {
  const menus = document.querySelectorAll('.menu');
  let openMenu = null;
  const close = () => { menus.forEach((m) => m.classList.remove('open')); openMenu = null; };
  menus.forEach((m) => {
    const btn = m.querySelector(':scope > button');
    btn.addEventListener('click', (e) => { e.stopPropagation(); const was = m.classList.contains('open'); close(); if (!was) { m.classList.add('open'); openMenu = m; syncHeader(); } });
    btn.addEventListener('mouseenter', () => { if (openMenu && openMenu !== m) { close(); m.classList.add('open'); openMenu = m; } });
  });
  document.addEventListener('click', (e) => { if (!e.target.closest('.menu')) close(); });
  document.querySelectorAll('[data-cmd]').forEach((b) => b.addEventListener('click', (e) => { close(); runCmd(b.dataset.cmd); }));
  $('fileOpen').addEventListener('change', async (e) => {
    const f = e.target.files[0]; e.target.value = ''; if (!f) return;
    try { stopPlayback(true); await loadSerialized(await f.text()); afterLoad(); toast('Opened ' + f.name); } catch (err) { toast('Could not open: ' + err.message, true); }
  });
  $('fileMidi').addEventListener('change', async (e) => { const f = e.target.files[0]; e.target.value = ''; if (f) importMidi(f); });
  $('fileAudio').addEventListener('change', async (e) => { const f = e.target.files[0]; e.target.value = ''; if (f) importAudio(f); });
}

// ---------- commands ----------
function targetNotes() { const tr = activeTrack(); if (!tr) return []; const s = selectedNotes(); return s.length ? s : tr.notes; }

export async function runCmd(c) {
  const tr = activeTrack();
  const ui = store.ui;
  switch (c) {
    case 'new':
      if (ui.dirty && !confirm('Start a new project? Unsaved changes will be lost.')) return;
      stopPlayback(true); store.audioFiles.clear(); newProject(); store.ui.activeTrackId = store.project.tracks[0].id; ui.selection.clear(); ui.playhead = 0; resetHistory(); afterLoad(); emit('project'); break;
    case 'demo':
      if (ui.dirty && !confirm('Load the demo song? Unsaved changes will be lost.')) return;
      stopPlayback(true); store.audioFiles.clear(); demoProject(); store.ui.activeTrackId = store.project.tracks[0].id; ui.selection.clear(); ui.playhead = 0; resetHistory(); afterLoad(); emit('project'); break;
    case 'open': $('fileOpen').click(); break;
    case 'save': saveToFile(); toast('Project saved'); break;
    case 'importMidi': $('fileMidi').click(); break;
    case 'importAudio': $('fileAudio').click(); break;
    case 'exportMidi': downloadBlob(writeMidi(store.project), fileBase() + '.mid'); toast('MIDI exported'); break;
    case 'exportWav': await doExport(null); break;
    case 'exportStem': if (tr) await doExport(tr.id); break;
    case 'undo': { const l = undo(); setStatus(l ? 'Undo: ' + l : 'Nothing to undo'); break; }
    case 'redo': { const l = redo(); setStatus(l ? 'Redo: ' + l : 'Nothing to redo'); break; }
    case 'copy': case 'cut': {
      const sel = selectedNotes(); if (!sel.length) return;
      const min = Math.min(...sel.map((n) => n.start));
      ui.clipboard = sel.map((n) => ({ ...n, start: n.start - min }));
      if (c === 'cut') commit('Cut', () => { tr.notes = tr.notes.filter((n) => !ui.selection.has(n.id)); ui.selection.clear(); });
      setStatus(`${sel.length} note(s) ${c === 'cut' ? 'cut' : 'copied'}`); break;
    }
    case 'paste': {
      if (!ui.clipboard || !tr || tr.type === 'audio') return;
      const at = Math.round(ui.playhead / Math.max(1, ui.snap)) * Math.max(1, ui.snap);
      commit('Paste', () => {
        const ns = ui.clipboard.map((n) => ({ ...n, id: uid(), start: n.start + at }));
        tr.notes.push(...ns); sortNotes(); ui.selection = new Set(ns.map((n) => n.id));
      }); break;
    }
    case 'duplicate': {
      const sel = selectedNotes(); if (!sel.length) return;
      const min = Math.min(...sel.map((n) => n.start)), max = Math.max(...sel.map((n) => n.start + n.dur));
      const bar = ticksPerBar(); const off = Math.ceil((max - min) / bar) * bar || bar;
      commit('Duplicate', () => { const ns = sel.map((n) => ({ ...n, id: uid(), start: n.start + off })); tr.notes.push(...ns); sortNotes(); ui.selection = new Set(ns.map((n) => n.id)); });
      break;
    }
    case 'delete': {
      if (!ui.selection.size) return;
      commit('Delete notes', () => { tr.notes = tr.notes.filter((n) => !ui.selection.has(n.id)); ui.selection.clear(); }); break;
    }
    case 'selectAll': if (tr) { ui.selection = new Set(tr.notes.map((n) => n.id)); emit('selection'); } break;
    case 'deselect': ui.selection.clear(); emit('selection'); break;
    case 'addVocal': case 'addInstrument': case 'addAudio': {
      const type = c === 'addVocal' ? 'vocal' : c === 'addInstrument' ? 'instrument' : 'audio';
      let t;
      commit('Add track', () => { t = newTrack(type); if (type === 'vocal') t.singer = SINGERS[store.project.tracks.length % SINGERS.length].id; store.project.tracks.push(t); });
      setActiveTrack(t.id); break;
    }
    case 'dupTrack': {
      if (!tr) return;
      let t;
      commit('Duplicate track', () => { t = JSON.parse(JSON.stringify(tr)); t.id = uid(); t.name = tr.name + ' copy'; t.notes.forEach((n) => { n.id = uid(); }); t.clips.forEach((cl) => { cl.id = uid(); }); store.project.tracks.splice(store.project.tracks.indexOf(tr) + 1, 0, t); });
      setActiveTrack(t.id); break;
    }
    case 'delTrack': {
      if (!tr) return;
      if (store.project.tracks.length <= 1) { toast('A project needs at least one track', true); return; }
      if (tr.notes.length && !confirm(`Delete track "${tr.name}"?`)) return;
      const i = store.project.tracks.indexOf(tr);
      commit('Delete track', () => { store.project.tracks.splice(i, 1); });
      setActiveTrack(store.project.tracks[Math.max(0, i - 1)].id); break;
    }
    case 'octUp': case 'octDown': case 'semiUp': case 'semiDown': {
      const d = { octUp: 12, octDown: -12, semiUp: 1, semiDown: -1 }[c];
      const ns = targetNotes(); if (!ns.length) return;
      commit('Transpose', () => ns.forEach((n) => { n.pitch = clamp(n.pitch + d, 0, 127); })); break;
    }
    case 'quantize': { const ns = targetNotes(); if (!ns.length) return; commit('Quantize', () => { quantize(ns, ui.snap); sortNotes(); }); toast(`Quantized ${ns.length} notes`); break; }
    case 'humanize': { const ns = targetNotes(); if (!ns.length) return; commit('Humanize', () => { humanize(ns); sortNotes(); }); toast('Humanized timing and dynamics'); break; }
    case 'legato': { const ns = targetNotes(); if (!ns.length) return; commit('Legato', () => legato(ns)); toast('Notes connected legato'); break; }
    case 'fitScale': { const ns = targetNotes(); if (!ns.length) return; commit('Snap to key', () => fitScale(ns, store.project.key, store.project.scale)); toast('Notes snapped to key'); break; }
    case 'autoBreath': if (tr && tr.type === 'vocal' && tr.notes.length) { commit('Auto expression', () => autoExpression(tr)); toast('Generated dynamics, breath and tension curves'); } break;
    case 'clearParams': if (tr && tr.type === 'vocal') commit('Reset curves', () => { for (const k of Object.keys(tr.params)) tr.params[k] = []; }); break;
    case 'lyrics': lyricsDialog(); break;
    case 'harmony': harmonyDialog(); break;
    case 'togglePitch': ui.showPitch = !ui.showPitch; syncHeader(); invalidate('roll'); break;
    case 'toggleGhosts': ui.showGhosts = !ui.showGhosts; syncHeader(); invalidate('roll'); break;
    case 'toggleFollow': ui.follow = !ui.follow; syncHeader(); break;
    case 'toggleLoop': {
      if (!ui.loop.on) {
        const sel = selectedNotes();
        if (sel.length) { const bar = ticksPerBar(); ui.loop.start = Math.floor(Math.min(...sel.map((n) => n.start)) / bar) * bar; ui.loop.end = Math.ceil(Math.max(...sel.map((n) => n.start + n.dur)) / bar) * bar; }
      }
      ui.loop.on = !ui.loop.on; syncHeader(); emit('loop'); break;
    }
    case 'toggleMetro': ui.metronome = !ui.metronome; syncHeader(); restartPlayback(); break;
    case 'zoomIn': zoomBy(1.4); break;
    case 'zoomOut': zoomBy(1 / 1.4); break;
    case 'zoomFit': zoomToNotes(); break;
    case 'shortcuts': shortcutsDialog(); break;
    case 'about': aboutDialog(); break;
  }
}
function zoomBy(f) {
  const v = store.ui.view; const W = rollWidth();
  const center = v.scrollX + (W - KEYW) / 2 / v.pxPerTick;
  v.pxPerTick = clamp(v.pxPerTick * f, 0.01, 2);
  v.scrollX = Math.max(0, center - (W - KEYW) / 2 / v.pxPerTick);
  emit('view'); updateSelStatus();
}
const fileBase = () => (store.project.name || 'song').replace(/[^\w\- ]+/g, ' ').replace(/\s+/g, ' ').trim() || 'song';

async function doExport(trackId) {
  if (!songEndTick()) { toast('Nothing to export yet', true); return; }
  const dlg = modal({ title: 'Rendering audio…', body: '<div class="muted">Synthesizing vocals and mixing tracks offline at 44.1 kHz.</div><div class="progress"><div></div></div>', buttons: [] });
  try {
    await new Promise((r) => setTimeout(r, 50));
    const blob = await exportWav({ onlyTrack: trackId });
    const tr = trackId && store.project.tracks.find((t) => t.id === trackId);
    downloadBlob(blob, fileBase() + (tr ? ' - ' + tr.name : '') + '.wav');
    toast('WAV exported');
  } catch (e) { console.error(e); toast('Export failed: ' + e.message, true); }
  dlg.close();
}

async function importMidi(file) {
  try {
    const r = parseMidi(await file.arrayBuffer());
    if (!r.tracks.length) { toast('No notes found in MIDI file', true); return; }
    let first;
    commit('Import MIDI', () => {
      const p = store.project;
      const empty = p.tracks.length === 1 && !p.tracks[0].notes.length;
      if (empty) p.tracks = [];
      if (r.bpm && (empty || confirm(`Use the MIDI tempo (${r.bpm} BPM)?`))) p.bpm = r.bpm;
      if (r.timeSig && empty) p.timeSig = r.timeSig;
      r.tracks.forEach((mt, i) => {
        const t = newTrack(i === 0 ? 'vocal' : 'instrument', { name: mt.name });
        if (t.type === 'vocal') { t.singer = SINGERS[i % SINGERS.length].id; t.notes = monophonic(mt.notes); }
        else t.notes = mt.notes;
        p.tracks.push(t); if (!first) first = t;
      });
    });
    setActiveTrack(first.id); zoomToNotes();
    toast(`Imported ${r.tracks.length} track(s) from ${file.name}`);
  } catch (e) { toast('MIDI import failed: ' + e.message, true); }
}
function monophonic(notes) {
  const out = [];
  const ns = notes.slice().sort((a, b) => a.start - b.start || b.pitch - a.pitch);
  for (const n of ns) {
    const last = out[out.length - 1];
    if (last && n.start === last.start) continue; // keep highest of chord
    if (last && last.start + last.dur > n.start) last.dur = n.start - last.start;
    out.push({ ...n });
  }
  return out.filter((n) => n.dur > 0);
}

async function importAudio(file) {
  try {
    setStatus('Decoding ' + file.name + '…', true);
    const fileId = await importAudioFile(file);
    let tr = activeTrack();
    commit('Import audio', () => {
      if (!tr || tr.type !== 'audio') { tr = newTrack('audio', { name: file.name.replace(/\.[^.]+$/, '') }); store.project.tracks.push(tr); }
      tr.clips.push({ id: uid(), fileId, start: 0, gain: 1 });
    });
    setActiveTrack(tr.id);
    toast('Imported ' + file.name); setStatus('');
  } catch (e) { toast('Could not decode audio: ' + e.message, true); setStatus(''); }
}

// ---------- dialogs ----------
function lyricsDialog() {
  const tr = activeTrack();
  if (!tr || tr.type !== 'vocal') { toast('Select a vocal track first', true); return; }
  if (!tr.notes.length) { toast('Draw some notes first', true); return; }
  const sel = selectedNotes();
  const ns = tr.notes.slice().sort((a, b) => a.start - b.start);
  const startIdx = sel.length ? ns.indexOf(sel.sort((a, b) => a.start - b.start)[0]) : 0;
  const current = ns.slice(startIdx).map((n) => n.lyric).join(' ');
  modal({
    title: 'Enter Lyrics',
    body: `<div class="muted">Type or paste lyrics. Syllables are assigned to notes in order, starting at ${sel.length ? 'the first selected note' : 'the first note'}. Use <b>-</b> to hold a vowel over several notes. English, romaji and Japanese kana are supported.</div>
      <textarea id="lyrText" spellcheck="false"></textarea>
      <label class="row" style="gap:8px"><input type="checkbox" id="lyrSplit" checked style="width:auto"> Split words into syllables automatically</label>
      <div class="muted" id="lyrInfo"></div>`,
    onOpen: (c) => {
      const ta = c.querySelector('#lyrText'); ta.value = current; ta.focus(); ta.select();
      const upd = () => { const s = syllabify(ta.value, c.querySelector('#lyrSplit').checked); c.querySelector('#lyrInfo').textContent = `${s.length} syllables → ${ns.length - startIdx} notes available`; };
      ta.addEventListener('input', upd); c.querySelector('#lyrSplit').addEventListener('change', upd); upd();
    },
    buttons: [{ label: 'Cancel' }, {
      label: 'Apply', primary: true, action: (c) => {
        const s = syllabify(c.querySelector('#lyrText').value, c.querySelector('#lyrSplit').checked);
        let k = 0; commit('Enter lyrics', () => { k = applyLyrics(tr, s, startIdx); });
        toast(`Assigned ${k} syllables`);
      },
    }],
  });
}

function harmonyDialog() {
  const tr = activeTrack();
  if (!tr || tr.type !== 'vocal' || !tr.notes.length) { toast('Select a vocal track with notes first', true); return; }
  const opts = Object.entries(HARMONY_TYPES).map(([k, v]) => `<option value="${k}">${v.label}</option>`).join('');
  const singers = SINGERS.map((s) => `<option value="${s.id}">${s.name} — ${s.desc}</option>`).join('');
  modal({
    title: 'Generate Harmony',
    body: `<div class="muted">Creates a new vocal track with a diatonic harmony line in <b>${['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'][store.project.key]} ${store.project.scale}</b> (change key in the Project panel). Lyrics and expression are copied.</div>
      <div class="row"><label>Interval</label><select id="hInt">${opts}</select></div>
      <div class="row"><label>Singer</label><select id="hSinger">${singers}</select></div>`,
    onOpen: (c) => { const i = SINGERS.findIndex((s) => s.id === tr.singer); c.querySelector('#hSinger').value = SINGERS[(i + 1) % (SINGERS.length - 1)].id; },
    buttons: [{ label: 'Cancel' }, {
      label: 'Generate', primary: true, action: (c) => {
        let h;
        commit('Generate harmony', () => { h = makeHarmony(tr, c.querySelector('#hInt').value, c.querySelector('#hSinger').value); store.project.tracks.splice(store.project.tracks.indexOf(tr) + 1, 0, h); });
        setActiveTrack(h.id); toast('Harmony track created');
      },
    }],
  });
}

function shortcutsDialog() {
  const rows = [
    ['Space', 'Play / pause'], ['Enter', 'Return to start'], ['1 / 2 / 3 / 4', 'Select / Draw / Erase / Split tool'],
    ['Double-click', 'Create note (empty) · edit lyric (note)'], ['Tab / Shift+Tab', 'Next / previous lyric while editing'],
    ['Alt + drag', 'Duplicate notes while dragging'], ['Shift + drag', 'Move without changing timing'], ['Ctrl + wheel', 'Zoom horizontally'],
    ['Alt + wheel', 'Zoom vertically'], ['Shift + wheel', 'Scroll horizontally'], ['Middle-drag', 'Pan the piano roll'],
    ['↑ / ↓', 'Transpose selection (Shift = octave)'], ['← / →', 'Nudge selection by grid'], ['Ctrl+Z / Ctrl+Shift+Z', 'Undo / redo'],
    ['Ctrl+C / X / V / D', 'Copy / cut / paste / duplicate'], ['Ctrl+A', 'Select all notes'], ['Delete', 'Delete selected notes'],
    ['Ctrl+L', 'Enter lyrics'], ['L', 'Toggle loop'], ['M', 'Toggle metronome'], ['Q', 'Quantize'], ['F', 'Zoom to notes'],
    ['Shift + drag ruler', 'Set loop range'], ['Ctrl+S / Ctrl+O', 'Save / open project'], ['Ctrl+E', 'Export WAV'],
  ];
  modal({ title: 'Keyboard Shortcuts', body: `<table>${rows.map(([k, v]) => `<tr><td>${k}</td><td>${v}</td></tr>`).join('')}</table>` });
}
function aboutDialog() {
  modal({
    title: 'About VoxForge Studio',
    body: `<div class="muted">VoxForge is a browser-based singing voice synthesis studio. Draw a melody, type lyrics, choose a virtual singer and shape the performance with pitch, dynamics, breath, tension, vibrato and gender curves. Everything runs locally with the Web Audio API — no uploads, no account.</div>
    <div class="muted">Features: 6 virtual singers with voice blending, formant vocal engine with consonants & diphthongs, English / romaji / kana lyrics, multitrack vocal, instrument and audio tracks, mixer with reverb, harmony generator, auto-expression, humanize, quantize, MIDI import/export, WAV export, project files, autosave and offline install.</div>`,
  });
}

// ---------- keyboard ----------
function initKeys() {
  document.addEventListener('keydown', (e) => {
    const tag = e.target.tagName;
    if ((tag === 'INPUT' && e.target.type !== 'range' && e.target.type !== 'checkbox') || tag === 'TEXTAREA' || tag === 'SELECT' || e.target.isContentEditable) return;
    const mod = e.ctrlKey || e.metaKey;
    const k = e.key.toLowerCase();
    const tr = activeTrack();
    const handled = () => e.preventDefault();
    if (mod) {
      if (k === 'z' && !e.shiftKey) { handled(); runCmd('undo'); }
      else if ((k === 'z' && e.shiftKey) || k === 'y') { handled(); runCmd('redo'); }
      else if (k === 'c') { handled(); runCmd('copy'); }
      else if (k === 'x') { handled(); runCmd('cut'); }
      else if (k === 'v') { handled(); runCmd('paste'); }
      else if (k === 'd') { handled(); runCmd('duplicate'); }
      else if (k === 'a') { handled(); runCmd('selectAll'); }
      else if (k === 's') { handled(); runCmd('save'); }
      else if (k === 'o') { handled(); runCmd('open'); }
      else if (k === 'e') { handled(); runCmd('exportWav'); }
      else if (k === 'l') { handled(); runCmd('lyrics'); }
      else if (k === 'n' && e.altKey) { handled(); runCmd('new'); }
      else if (k === '=' || k === '+') { handled(); runCmd('zoomIn'); }
      else if (k === '-') { handled(); runCmd('zoomOut'); }
      return;
    }
    switch (e.key) {
      case ' ': handled(); togglePlay(); break;
      case 'Enter': handled(); if (selectedNotes().length === 1 && tr.type === 'vocal') startLyricEdit(selectedNotes()[0]); else { $('btnHome').click(); } break;
      case 'Home': handled(); $('btnHome').click(); break;
      case 'Delete': case 'Backspace': handled(); runCmd('delete'); break;
      case 'Escape': runCmd('deselect'); break;
      case '1': setTool('select'); break;
      case '2': setTool('draw'); break;
      case '3': setTool('erase'); break;
      case '4': setTool('split'); break;
      case 'l': case 'L': runCmd('toggleLoop'); break;
      case 'm': case 'M': runCmd('toggleMetro'); break;
      case 'q': case 'Q': runCmd('quantize'); break;
      case 'f': case 'F': runCmd('zoomFit'); break;
      case '?': runCmd('shortcuts'); break;
      case 'ArrowUp': case 'ArrowDown': {
        const sel = selectedNotes(); if (!sel.length) return; handled();
        const d = (e.key === 'ArrowUp' ? 1 : -1) * (e.shiftKey ? 12 : 1);
        commit('Transpose', () => sel.forEach((n) => { n.pitch = clamp(n.pitch + d, 0, 127); }));
        import('./engine.js').then((m) => m.previewNote(tr, sel[0].pitch, sel[0].lyric));
        break;
      }
      case 'ArrowLeft': case 'ArrowRight': {
        const sel = selectedNotes(); if (!sel.length) return; handled();
        const step = Math.max(10, store.ui.snap) * (e.key === 'ArrowLeft' ? -1 : 1);
        if (e.shiftKey) commit('Resize', () => sel.forEach((n) => { n.dur = Math.max(10, n.dur + step); }));
        else if (Math.min(...sel.map((n) => n.start)) + step >= 0) commit('Nudge', () => { sel.forEach((n) => { n.start += step; }); sortNotes(); });
        break;
      }
    }
  });
}

// ---------- layout: splitters & scrollbar ----------
function initSplitters() {
  document.querySelectorAll('.splitter').forEach((s) => {
    s.addEventListener('mousedown', (e) => {
      e.preventDefault();
      const target = s.dataset.split === 'arrange' ? $('arrangeWrap') : $('paramWrap');
      const h0 = target.getBoundingClientRect().height, y0 = e.clientY;
      const mv = (ev) => { const d = ev.clientY - y0; target.style.height = clamp(s.dataset.split === 'arrange' ? h0 + d : h0 - d, 60, innerHeight * 0.6) + 'px'; };
      const up = () => { window.removeEventListener('mousemove', mv); window.removeEventListener('mouseup', up); };
      window.addEventListener('mousemove', mv); window.addEventListener('mouseup', up);
    });
  });
}
let syncingScroll = false;
function initHScroll() {
  const sc = $('hscroll');
  sc.addEventListener('scroll', () => {
    if (syncingScroll) { syncingScroll = false; return; }
    store.ui.view.scrollX = sc.scrollLeft / store.ui.view.pxPerTick;
    invalidate('roll', 'param', 'arrange');
  });
  updateScrollExtent();
}
function updateScrollExtent() {
  const v = store.ui.view;
  const total = Math.max(songEndTick() + ticksPerBar() * 16, ticksPerBar() * 64, v.scrollX + (rollWidth() - KEYW) / v.pxPerTick * 2);
  $('hscrollInner').style.width = total * v.pxPerTick + 'px';
}
function syncHScroll() {
  updateScrollExtent();
  const sc = $('hscroll');
  const want = store.ui.view.scrollX * store.ui.view.pxPerTick;
  if (Math.abs(sc.scrollLeft - want) > 1) { syncingScroll = true; sc.scrollLeft = want; }
}

// ---------- drag & drop files ----------
function initDnD() {
  window.addEventListener('dragover', (e) => e.preventDefault());
  window.addEventListener('drop', async (e) => {
    e.preventDefault();
    for (const f of e.dataTransfer.files) {
      const n = f.name.toLowerCase();
      if (n.endsWith('.mid') || n.endsWith('.midi')) await importMidi(f);
      else if (n.endsWith('.vfx') || n.endsWith('.json')) { try { await loadSerialized(await f.text()); afterLoad(); } catch (err) { toast('Could not open: ' + err.message, true); } }
      else if (f.type.startsWith('audio/')) await importAudio(f);
    }
  });
}

boot();
