"""NSE market data: index member lists and 5-minute bars, India time.

Free, unofficial sources, standard library only:
  - index lists from niftyindices.com (NSE's index arm), with NSE archive mirrors
  - 5-minute bars from Yahoo Finance's chart endpoint (SYMBOL.NS), which keeps ~60 days

The NSE cash market trades 9:15 to 15:30 IST, so a full day is 75 five-minute bars, the
first starting 9:15 and the last 15:25. Days with fewer bars (holidays slipping in, Muhurat
trading, data gaps) are not "full" and are left out of anything statistical.
"""
from __future__ import annotations

import csv
import gzip
import io
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

IST = timezone(timedelta(hours=5, minutes=30))
OPEN = (9, 15)
CLOSE = (15, 30)
BAR_MIN = 5
BARS_PER_DAY = 75
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"

INDEX_FILES = {
    "smallcap250": "ind_niftysmallcap250list.csv",
    "midcap150": "ind_niftymidcap150list.csv",
    "nifty500": "ind_nifty500list.csv",
    "nifty100": "ind_nifty100list.csv",
}
INDEX_HOSTS = [
    "https://www.niftyindices.com/IndexConstituent/",
    "https://nsearchives.nseindia.com/content/indices/",
    "https://archives.nseindia.com/content/indices/",
]


@dataclass(frozen=True)
class Bar:
    start: datetime  # IST, naive
    open: float
    high: float
    low: float
    close: float
    volume: float

    @property
    def end(self) -> datetime:
        return self.start + timedelta(minutes=BAR_MIN)


def bar_index(t: datetime) -> int:
    """0 for the 9:15 bar, 74 for the 15:25 bar."""
    return ((t.hour * 60 + t.minute) - (OPEN[0] * 60 + OPEN[1])) // BAR_MIN


def slot_time(day: date, index: int) -> datetime:
    return datetime(day.year, day.month, day.day, *OPEN) + timedelta(minutes=BAR_MIN * index)


def hhmm(s: str) -> tuple[int, int]:
    h, m = s.strip().split(":")
    return int(h), int(m)


def _get(url: str, timeout: int = 30) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 - fixed https hosts
        data = r.read()
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def universe(name: str, cache: Path) -> list[dict]:
    """[{symbol, name, industry}] for an NSE index (smallcap250, midcap150, nifty500, nifty100)."""
    if name not in INDEX_FILES:
        raise SystemExit(f"Unknown universe {name!r}. Choose one of: {', '.join(INDEX_FILES)}")
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / INDEX_FILES[name]
    if not path.exists() or time.time() - path.stat().st_mtime > 7 * 86400:
        errors = []
        for host in INDEX_HOSTS:
            try:
                text = _get(host + INDEX_FILES[name]).decode("utf-8-sig")
                if "Symbol" in text.splitlines()[0]:
                    path.write_text(text)
                    break
                errors.append(f"{host}: not a CSV")
            except (urllib.error.URLError, OSError, UnicodeDecodeError) as e:
                errors.append(f"{host}: {e}")
        if not path.exists():
            raise SystemExit("Could not download the index list:\n  " + "\n  ".join(errors))
    rows = list(csv.DictReader(io.StringIO(path.read_text())))
    return [{"symbol": r["Symbol"].strip(), "name": r.get("Company Name", "").strip(),
             "industry": r.get("Industry", "").strip()} for r in rows if r.get("Symbol")]


def fetch_5m(symbol: str, cache: Path, days: int = 60, max_age_s: float = 240) -> list[Bar]:
    """5-minute bars for an NSE symbol (no .NS needed). Cached; [] if Yahoo has none."""
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / f"{symbol}-{days}d-5m.json"
    if not path.exists() or time.time() - path.stat().st_mtime > max_age_s:
        q = urllib.parse.urlencode({"range": f"{days}d", "interval": "5m", "includePrePost": "false"})
        url = f"https://query1.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(symbol)}.NS?{q}"
        for attempt in range(4):
            try:
                path.write_bytes(_get(url))
                break
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    return []
                time.sleep(3 * (attempt + 1))
            except (urllib.error.URLError, OSError):
                time.sleep(2 * (attempt + 1))
        else:
            return [] if not path.exists() else parse_chart(path.read_bytes())
    return parse_chart(path.read_bytes())


def parse_chart(raw: bytes) -> list[Bar]:
    """Yahoo chart JSON (optionally gzipped) -> bars in IST, completed fields only."""
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    try:
        res = json.loads(raw)["chart"]["result"][0]
        q = res["indicators"]["quote"][0]
        ts = res["timestamp"]
    except (KeyError, IndexError, TypeError, ValueError):
        return []
    bars = []
    for i, t in enumerate(ts):
        o, h, l, c, v = (q[k][i] for k in ("open", "high", "low", "close", "volume"))
        if None in (o, h, l, c):
            continue
        start = datetime.fromtimestamp(t, IST).replace(tzinfo=None, second=0, microsecond=0)
        if 0 <= bar_index(start) < BARS_PER_DAY:
            bars.append(Bar(start, float(o), float(h), float(l), float(c), float(v or 0)))
    return bars


def sessions(bars: list[Bar]) -> dict[date, list[Bar]]:
    """Bars grouped by day, in time order, one bar per 5-minute slot."""
    out: dict[date, dict[int, Bar]] = {}
    for b in bars:
        out.setdefault(b.start.date(), {})[bar_index(b.start)] = b
    return {d: [slots[i] for i in sorted(slots)] for d, slots in sorted(out.items())}


def is_full(day_bars: list[Bar]) -> bool:
    return len(day_bars) == BARS_PER_DAY and all(bar_index(b.start) == i for i, b in enumerate(day_bars))


def now_ist() -> datetime:
    return datetime.now(IST).replace(tzinfo=None)


def closed_bars(day_bars: list[Bar], now: datetime) -> list[Bar]:
    """Only bars that have finished by [now]: a forming bar is never used."""
    return [b for b in day_bars if b.end <= now]
