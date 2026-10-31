// Virtual singer voice banks. Each singer is a set of synthesis parameters
// for the formant voice engine.
export const SINGERS = [
  { id: 'aria', name: 'Aria', desc: 'Bright pop soprano', gender: 'F', range: [60, 84], formant: 1.18, brightness: 0.75, breath: 0.18, tension: 0.55, vibRate: 5.6, vibDepth: 28, wave: 'glottal', color: '#ff5c8a' },
  { id: 'mira', name: 'Mira', desc: 'Soft, breathy alto', gender: 'F', range: [55, 77], formant: 1.1, brightness: 0.45, breath: 0.42, tension: 0.38, vibRate: 5.0, vibDepth: 22, wave: 'glottal', color: '#e65cff' },
  { id: 'kai', name: 'Kai', desc: 'Clear rock tenor', gender: 'M', range: [48, 72], formant: 0.98, brightness: 0.7, breath: 0.15, tension: 0.62, vibRate: 5.4, vibDepth: 30, wave: 'glottal', color: '#4aa8ff' },
  { id: 'ronan', name: 'Ronan', desc: 'Warm baritone', gender: 'M', range: [41, 65], formant: 0.88, brightness: 0.4, breath: 0.2, tension: 0.45, vibRate: 4.8, vibDepth: 26, wave: 'glottal', color: '#22c3a6' },
  { id: 'yuki', name: 'Yuki', desc: 'Light anime-style voice', gender: 'F', range: [62, 88], formant: 1.26, brightness: 0.85, breath: 0.12, tension: 0.6, vibRate: 6.2, vibDepth: 20, wave: 'glottal', color: '#ffb02e' },
  { id: 'echo', name: 'Echo', desc: 'Synthetic robo-vocal', gender: 'X', range: [48, 84], formant: 1.0, brightness: 0.9, breath: 0.02, tension: 0.7, vibRate: 0, vibDepth: 0, wave: 'square', color: '#8bd450' },
];
export const singerById = (id) => SINGERS.find((s) => s.id === id) || SINGERS[0];

export const STYLES = {
  Neutral: { breath: 0.5, tension: 0.5, vibDepth: 0.5, gender: 0.5 },
  Soft: { breath: 0.75, tension: 0.3, vibDepth: 0.4, gender: 0.5 },
  Powerful: { breath: 0.3, tension: 0.85, vibDepth: 0.65, gender: 0.45 },
  Airy: { breath: 0.95, tension: 0.35, vibDepth: 0.3, gender: 0.55 },
  Bright: { breath: 0.35, tension: 0.7, vibDepth: 0.5, gender: 0.65 },
  Dark: { breath: 0.45, tension: 0.4, vibDepth: 0.5, gender: 0.3 },
};

export const INSTRUMENTS = [
  { id: 'piano', name: 'Piano' },
  { id: 'strings', name: 'Strings' },
  { id: 'pad', name: 'Warm Pad' },
  { id: 'lead', name: 'Synth Lead' },
  { id: 'pluck', name: 'Pluck' },
  { id: 'bass', name: 'Bass' },
];
