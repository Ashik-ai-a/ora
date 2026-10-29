"""Time Machine: replay the rest of the session 10,000 times before risking a rupee.

Method from the MIT-licensed Day Trader skill (github.com/Jakeschincariol/day-trader-skill),
moved to NSE hours and with Indian charges taken off every result:

  1. Pool: every FULL session of this stock before the plan's day. Each bar becomes four
     ratios to the bar before it (open, high, low, close over the previous close).
  2. One replay: start at the entry; for each bar left until flat_by, draw a past bar from
     the same time of day (+/- window bars) and scale it to the current price. Stop is
     checked before target inside a bar; a gap through the stop fills at the open.
  3. History: the same rule on every one of those past sessions. That really happened.
  4. Verdict: among replays that hit a side, how often the target came first, against the
     break-even rate once costs are paid: (1 + c) / (1 + R), c = costs in units of risk.

It is a simulation built from the last ~60 days, not a prediction.
"""
from __future__ import annotations

import random
from datetime import date

from .nse import BARS_PER_DAY, Bar, is_full
from .rules import Plan, Strategy, outcome, signal


def ratio_pool(sessions: dict[date, list[Bar]], before: date) -> list[list[tuple[float, float, float, float]]]:
    pool = []
    for d, bars in sessions.items():
        if d >= before or not is_full(bars):
            continue
        row = [(1.0, 1.0, 1.0, 1.0)]
        for prev, b in zip(bars, bars[1:]):
            pc = prev.close
            row.append((b.open / pc, b.high / pc, b.low / pc, b.close / pc))
        pool.append(row)
    return pool


def replay(plan: Plan, pool: list, flat_index: int, replays: int = 10_000, seed: int = 19, window: int = 2) -> dict:
    rng = random.Random(seed)
    long = plan.side == "long"
    n_target = n_stop = n_flat = n_green = 0
    r_sum = 0.0
    finals = []
    for _ in range(replays):
        p = plan.entry
        hit = None
        for i in range(plan.entry_bar + 1, flat_index + 1):
            day = pool[rng.randrange(len(pool))]
            j = min(BARS_PER_DAY - 1, max(1, i + rng.randint(-window, window)))
            ro, rh, rl, rc = day[j]
            o, h, l, c = p * ro, p * rh, p * rl, p * rc
            if long and l <= plan.stop or not long and h >= plan.stop:
                p, hit = (min(o, plan.stop) if long else max(o, plan.stop)), "stop"
                break
            if long and h >= plan.target or not long and l <= plan.target:
                p, hit = (max(o, plan.target) if long else min(o, plan.target)), "target"
                break
            p = c
        r = (p - plan.entry) / plan.risk * (1 if long else -1)
        r_sum += r
        finals.append(p)
        if hit == "target":
            n_target += 1
        elif hit == "stop":
            n_stop += 1
        else:
            n_flat += 1
        if hit == "target" or (hit is None and r > 0):
            n_green += 1
    finals.sort()
    q = lambda f: round(finals[min(len(finals) - 1, int(f * len(finals)))], 2)  # noqa: E731
    return {"replays": replays, "seed": seed, "target": n_target / replays, "stop": n_stop / replays,
            "neither": n_flat / replays, "green": n_green / replays, "avg_r": r_sum / replays,
            "exit_p5": q(0.05), "exit_median": q(0.5), "exit_p95": q(0.95)}


def history(symbol: str, sessions: dict[date, list[Bar]], before: date, s: Strategy) -> list[dict]:
    out = []
    for d, bars in sessions.items():
        if d >= before or not is_full(bars):
            continue
        plan = signal(symbol, bars, s)
        if plan:
            res = outcome(bars, plan, s.flat_index)
            out.append({"day": d.isoformat(), "side": plan.side, "entry": plan.entry, "risk": plan.risk, **res})
    return out


def verdict(target: float, stop: float, target_r: float, cost_r: float) -> str:
    hits = target + stop
    if hits <= 0:
        return "Almost every replay was still open at the flat-by time: no edge to read."
    rate = target / hits
    breakeven = (1 + cost_r) / (1 + target_r)
    gap = (rate - breakeven) * 100
    base = (f"Target first in {rate * 100:.0f}% of the replays that hit a side; after charges it needs "
            f"{breakeven * 100:.0f}% to break even. ")
    if abs(gap) < 5:
        return base + ("Close to a coin flip." if abs(target_r - 1) < 1e-9 else "Close to break-even.")
    if gap > 0:
        return base + ("Leans toward the target." if gap < 15 else "Strongly toward the target.")
    return base + ("Leans toward the stop." if gap > -15 else "Strongly toward the stop.")
