"""The opening-range breakout (ORB), the trade plan, and what a plan did on a real day.

  Opening range: the first or_bars five-minute bars (default 3: 9:15 to 9:30 IST).
  Signal: the first CLOSED bar after the range that closes above its high (long) or, if
           shorts are on, below its low (short). Only bars ending by last_entry count.
  Plan: stop at the range midpoint (or the far side), target = target_r times the risk,
        and out by flat_by either way, before the broker's own intraday square-off.
"""
from __future__ import annotations

import math
from dataclasses import asdict, dataclass, field
from datetime import date

from .nse import Bar, bar_index, hhmm, slot_time

TICK = 0.05


@dataclass(frozen=True)
class Strategy:
    or_bars: int = 3
    last_entry: str = "14:30"
    flat_by: str = "15:15"
    stop_rule: str = "or-mid"  # or-mid | or-far
    target_r: float = 1.5  # 1R barely pays: after Indian charges it is often ~0.7R
    max_or_pct: float = 0.03  # skip days whose opening range is wider than this (stop too far)
    sides: tuple = ("long",)

    @classmethod
    def from_config(cls, cfg: dict | None) -> "Strategy":
        cfg = dict(cfg or {})
        if "sides" in cfg:
            cfg["sides"] = tuple(cfg["sides"])
        known = set(cls.__dataclass_fields__)
        return cls(**{k: v for k, v in cfg.items() if k in known})

    @property
    def flat_index(self) -> int:
        """Index of the last bar held: the one that ends at flat_by."""
        h, m = hhmm(self.flat_by)
        return bar_index(slot_time(date(2000, 1, 1), 0).replace(hour=h, minute=m)) - 1


@dataclass
class Plan:
    symbol: str
    day: str
    side: str
    entry: float
    entry_bar: int
    entry_time: str
    stop: float
    target: float
    or_high: float
    or_low: float
    stop_rule: str
    target_r: float
    flat_by: str
    extra: dict = field(default_factory=dict)

    @property
    def risk(self) -> float:
        return abs(self.entry - self.stop)

    def to_json(self) -> dict:
        d = asdict(self)
        d["risk_per_share"] = round(self.risk, 2)
        return d

    @classmethod
    def from_json(cls, d: dict) -> "Plan":
        known = set(cls.__dataclass_fields__)
        return cls(**{k: v for k, v in d.items() if k in known})


def _down(x: float) -> float:
    return math.floor(round(x / TICK, 6)) * TICK


def _up(x: float) -> float:
    return math.ceil(round(x / TICK, 6)) * TICK


def opening_range(bars: list[Bar], n: int) -> tuple[float, float] | None:
    if len(bars) < n or any(bar_index(b.start) != i for i, b in enumerate(bars[:n])):
        return None
    return max(b.high for b in bars[:n]), min(b.low for b in bars[:n])


def signal(symbol: str, bars: list[Bar], s: Strategy) -> Plan | None:
    """The day's first breakout and its plan, or None. [bars] must be closed bars only."""
    rng = opening_range(bars, s.or_bars)
    if not rng:
        return None
    hi, lo = rng
    if lo <= 0 or (hi - lo) / lo > s.max_or_pct:
        return None
    lh, lm = hhmm(s.last_entry)
    for i, b in enumerate(bars[s.or_bars:], start=s.or_bars):
        if (b.end.hour, b.end.minute) > (lh, lm):
            return None
        side = "long" if b.close > hi and "long" in s.sides else "short" if b.close < lo and "short" in s.sides else None
        if not side:
            continue
        entry = b.close
        if side == "long":
            stop = _down((hi + lo) / 2 if s.stop_rule == "or-mid" else lo)
            if stop >= entry:
                return None
            target = _up(entry + s.target_r * (entry - stop))
        else:
            stop = _up((hi + lo) / 2 if s.stop_rule == "or-mid" else hi)
            if stop <= entry:
                return None
            target = _down(entry - s.target_r * (stop - entry))
        return Plan(symbol, b.start.date().isoformat(), side, round(entry, 2), bar_index(b.start), b.end.strftime("%H:%M"),
                    round(stop, 2), round(target, 2), hi, lo, s.stop_rule, s.target_r, s.flat_by)
    return None


def outcome(bars: list[Bar], plan: Plan, flat_index: int) -> dict:
    """What the plan did on a real day: target, stop or flat (out at flat_by), and R before costs.

    The stop is checked before the target inside each bar (when a bar touches both we
    can't know which came first, so assume the worse). A gap through the stop fills at the
    bar's open, which is worse than the stop.
    """
    long = plan.side == "long"
    sign = 1 if long else -1
    exit_price, result, when = None, "flat", None
    last = None
    for b in bars:
        i = bar_index(b.start)
        if i <= plan.entry_bar:
            continue
        if i > flat_index:
            break
        last = b
        if long and b.low <= plan.stop or not long and b.high >= plan.stop:
            exit_price = min(b.open, plan.stop) if long else max(b.open, plan.stop)
            result, when = "stop", b.start
            break
        if long and b.high >= plan.target or not long and b.low <= plan.target:
            exit_price = max(b.open, plan.target) if long else min(b.open, plan.target)
            result, when = "target", b.start
            break
    if exit_price is None:
        if last is None:
            return {"result": "open", "exit": None, "r": None}
        exit_price, when = last.close, last.start
    r = sign * (exit_price - plan.entry) / plan.risk
    return {"result": result, "exit": round(exit_price, 2), "exit_time": when.strftime("%H:%M"), "r": r}
