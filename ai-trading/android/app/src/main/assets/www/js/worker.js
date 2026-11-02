// Runs the agent funnel off the UI thread so the app stays smooth during a scan.
import { executeScan } from './scan-core.js';

self.postMessage({ type: 'ready' });
self.onmessage = ({ data }) => executeScan(data, (m) => self.postMessage(m));
