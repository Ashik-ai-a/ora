"""Market data: the Nifty Smallcap 250 list and daily prices.

Free sources, so they can break: the index list comes from NSE's index site and prices from
Yahoo Finance's public chart endpoint (NSE symbols with a .NS suffix). Everything downloaded
is cached under the data directory so a rerun doesn't fetch it again.
"""
from __future__ import annotations

import io
import json
import time
from pathlib import Path

import numpy as np
import pandas as pd
import requests

UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"}

UNIVERSE_URLS = [
    "https://www.niftyindices.com/IndexConstituent/ind_niftysmallcap250list.csv",
    "https://nsearchives.nseindia.com/content/indices/ind_niftysmallcap250list.csv",
    "https://archives.nseindia.com/content/indices/ind_niftysmallcap250list.csv",
]

# Benchmarks on Yahoo: Nifty Smallcap 100 and Nifty 50. Missing ones are skipped.
BENCHMARKS = {"Nifty Smallcap 100": "^CNXSC", "Nifty 50": "^NSEI"}


def smallcap_universe(cache: Path) -> pd.DataFrame:
    """Company name, industry and NSE symbol of today's Nifty Smallcap 250 members."""
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / "smallcap250.csv"
    errors = []
    for url in UNIVERSE_URLS:
        try:
            r = requests.get(url, headers=UA, timeout=30)
            r.raise_for_status()
            df = pd.read_csv(io.StringIO(r.text))
            if "Symbol" in df.columns and len(df) > 200:
                df.to_csv(path, index=False)
                break
            errors.append(f"{url}: unexpected file")
        except Exception as e:  # noqa: BLE001 - try the next mirror
            errors.append(f"{url}: {e}")
    if not path.exists():
        raise RuntimeError("Could not download the Nifty Smallcap 250 list:\n" + "\n".join(errors))
    df = pd.read_csv(path)
    return df.rename(columns={"Company Name": "name", "Industry": "industry", "Symbol": "symbol"})[["name", "industry", "symbol"]]


_last_request = [0.0]


def _pace(gap: float = 0.4) -> None:
    """At most one request per [gap] seconds, so Yahoo doesn't answer "too many requests"."""
    wait = _last_request[0] + gap - time.time()
    if wait > 0:
        time.sleep(wait)
    _last_request[0] = time.time()


def yahoo_daily(ticker: str, cache: Path, years: int = 12, max_age_hours: float = 12) -> pd.DataFrame | None:
    """Daily close (split/dividend adjusted), raw close and volume. None if Yahoo has nothing."""
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / f"{ticker.replace('^', '_')}.json"
    if not path.exists() or time.time() - path.stat().st_mtime > max_age_hours * 3600:
        url = f"https://query1.finance.yahoo.com/v8/finance/chart/{ticker}"
        params = {"range": f"{years}y", "interval": "1d", "events": "div,split", "includeAdjustedClose": "true"}
        for attempt in range(4):
            try:
                _pace()
                r = requests.get(url.replace("query1", "query2") if attempt % 2 else url, params=params, headers=UA, timeout=20)
                if r.status_code == 404:
                    return None
                if r.status_code == 429:
                    time.sleep(3 * (attempt + 1))
                    continue
                r.raise_for_status()
                path.write_text(r.text)
                break
            except requests.RequestException:
                time.sleep(2 * (attempt + 1))
        else:
            return None
    try:
        result = json.loads(path.read_text())["chart"]["result"][0]
        q = result["indicators"]["quote"][0]
        adj = result["indicators"].get("adjclose", [{}])[0].get("adjclose") or q["close"]
        df = pd.DataFrame(
            {"adj": adj, "close": q["close"], "volume": q["volume"]},
            index=pd.to_datetime(result["timestamp"], unit="s").tz_localize("UTC").tz_convert("Asia/Kolkata").normalize().tz_localize(None),
        )
    except (KeyError, IndexError, TypeError, ValueError):
        return None
    df = df[~df.index.duplicated(keep="last")].dropna(subset=["adj", "close"])
    return df if len(df) else None


def price_panel(symbols: list[str], cache: Path, years: int = 12) -> tuple[pd.DataFrame, pd.DataFrame, list[str]]:
    """(adjusted close, traded value in rupees, symbols with no data), one column per symbol."""
    adj, value, missing = {}, {}, []
    for i, s in enumerate(symbols):
        df = yahoo_daily(f"{s}.NS", cache, years)
        if df is None:
            missing.append(s)
        else:
            adj[s] = df["adj"]
            value[s] = df["close"] * df["volume"].fillna(0)
        if (i + 1) % 25 == 0:
            print(f"  prices: {i + 1}/{len(symbols)}", flush=True)
    a = pd.DataFrame(adj).sort_index()
    v = pd.DataFrame(value).reindex(a.index)
    # Days when no stock traded are holidays that slipped in; drop them.
    keep = a.notna().sum(axis=1) > 0.2 * max(1, a.shape[1])
    return a[keep], v[keep].replace([np.inf, -np.inf], np.nan), missing


def benchmarks(cache: Path, years: int = 12) -> pd.DataFrame:
    out = {}
    for name, ticker in BENCHMARKS.items():
        df = yahoo_daily(ticker, cache, years)
        if df is not None:
            out[name] = df["adj"]
    return pd.DataFrame(out)
