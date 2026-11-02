"""Regenerate the Android app's preset list from trader/config.py (run from ai-trading/)."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from trader.config import PRESETS  # noqa: E402

GROUPS = {
    "India: broad market": ["nifty50", "nifty_next50", "nifty100", "nifty200", "nifty500", "nifty_midcap150",
                            "nifty_smallcap250", "nifty_total_market", "nse_all"],
    "India: sectors": ["nifty_bank", "nifty_it", "nifty_pharma", "nifty_auto", "nifty_fmcg", "nifty_metal",
                       "nifty_energy", "nifty_realty", "nifty_psu_bank", "nifty_fin_service"],
    "India: F&O, ETFs, indices": ["nse_fo", "india_etfs", "india_indices"],
    "Global": ["us_large_cap", "us_etfs", "crypto"],
}
LABELS = {"nifty50": "NIFTY 50", "nifty_next50": "NIFTY Next 50", "nifty100": "NIFTY 100", "nifty200": "NIFTY 200",
          "nifty500": "NIFTY 500", "nifty_midcap150": "Midcap 150", "nifty_smallcap250": "Smallcap 250",
          "nifty_total_market": "Total Market", "nse_all": "All NSE shares", "nifty_bank": "Bank", "nifty_it": "IT",
          "nifty_pharma": "Pharma", "nifty_auto": "Auto", "nifty_fmcg": "FMCG", "nifty_metal": "Metal",
          "nifty_energy": "Energy", "nifty_realty": "Realty", "nifty_psu_bank": "PSU Bank",
          "nifty_fin_service": "Financial Services", "nse_fo": "F&O stocks", "india_etfs": "Indian ETFs",
          "india_indices": "Indices", "us_large_cap": "US large caps", "us_etfs": "US ETFs", "crypto": "Crypto"}

out = {"groups": GROUPS, "presets": {
    k: {"label": LABELS[k], "symbols": PRESETS[k]["symbols"], "benchmark": PRESETS[k]["benchmark"],
        "volatility_index": PRESETS[k]["volatility_index"], "min_turnover": PRESETS[k]["min_turnover"]}
    for group in GROUPS.values() for k in group}}
path = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/www/js/engine/presets.js"
path.write_text("// Generated from trader/config.py PRESETS (python scripts/export_presets.py). Do not edit by hand.\n"
                "export default " + json.dumps(out, indent=1) + ";\n")
print(f"wrote {path}")
