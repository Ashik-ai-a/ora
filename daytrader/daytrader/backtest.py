"""Proof run: the ORB rule on every stock in a universe over the ~60 days Yahoo keeps.

Per stock and day it only uses what was known by then: liquidity and opening-range volume
come from earlier sessions. The portfolio takes signals in the order they fired, up to
max_trades_per_day, and stops for the day once the daily loss limit is used up.
"""
from __future__ import annotations

from collections import defaultdict
from datetime import date

from . import costs
from .money import size
from .nse import Bar, is_full
from .rules import Strategy, outcome, signal


def trades_for(symbol: str, sessions: dict[date, list[Bar]], s: Strategy, cfg: dict, min_value: float) -> list[dict]:
    rates = costs.Rates.from_config(cfg.get("costs"))
    full = [(d, b) for d, b in sessions.items() if is_full(b)]
    out = []
    for k, (d, bars) in enumerate(full):
        past = full[max(0, k - 10):k]
        if len(past) < 5:
            continue
        traded = sum(sum(b.close * b.volume for b in pb) for _, pb in past[-5:]) / 5
        if traded < min_value:
            continue
        plan = signal(symbol, bars, s)
        if not plan:
            continue
        or_vol = sum(b.volume for b in bars[:s.or_bars])
        past_or = sum(sum(b.volume for b in pb[:s.or_bars]) for _, pb in past) / len(past)
        res = outcome(bars, plan, s.flat_index)
        if res["r"] is None:
            continue
        order = size(plan, cfg)
        if not order.get("qty"):
            continue
        qty = order["qty"]
        gross = qty * (res["exit"] - plan.entry) * (1 if plan.side == "long" else -1)
        buy, sell = (plan.entry, res["exit"]) if plan.side == "long" else (res["exit"], plan.entry)
        charges = costs.round_trip(qty, buy, sell, rates)
        out.append({"day": d.isoformat(), "symbol": symbol, "side": plan.side, "time": plan.entry_time,
                    "entry": plan.entry, "exit": res["exit"], "result": res["result"], "qty": qty,
                    "r": res["r"], "pnl_gross": gross, "pnl_net": gross - charges["total"],
                    "charges": charges["total"], "risk_rs": qty * plan.risk,
                    "rel_volume": or_vol / past_or if past_or else 0.0, "traded_cr": traded / 1e7})
    return out


def portfolio(trades: list[dict], cfg: dict) -> list[dict]:
    """The trades a disciplined person would actually have taken, day by day."""
    by_day = defaultdict(list)
    for t in trades:
        by_day[t["day"]].append(t)
    taken = []
    for day in sorted(by_day):
        pnl, n = 0.0, 0
        for t in sorted(by_day[day], key=lambda t: (t["time"], -t["rel_volume"])):
            if n >= cfg["max_trades_per_day"] or pnl <= -cfg["daily_loss_limit"]:
                break
            taken.append(t)
            pnl += t["pnl_net"]
            n += 1
    return taken


def summary(trades: list[dict]) -> dict:
    if not trades:
        return {"trades": 0}
    n = len(trades)
    wins = [t for t in trades if t["pnl_net"] > 0]
    gross_win = sum(t["pnl_net"] for t in wins)
    gross_loss = -sum(t["pnl_net"] for t in trades if t["pnl_net"] <= 0)
    equity, peak, worst = 0.0, 0.0, 0.0
    days = defaultdict(float)
    for t in sorted(trades, key=lambda t: (t["day"], t["time"])):
        equity += t["pnl_net"]
        peak = max(peak, equity)
        worst = min(worst, equity - peak)
        days[t["day"]] += t["pnl_net"]
    return {
        "trades": n, "days": len(days),
        "target": sum(t["result"] == "target" for t in trades) / n,
        "stop": sum(t["result"] == "stop" for t in trades) / n,
        "flat": sum(t["result"] == "flat" for t in trades) / n,
        "win_rate": len(wins) / n,
        "avg_r_gross": sum(t["r"] for t in trades) / n,
        "avg_r_net": sum(t["pnl_net"] / t["risk_rs"] for t in trades) / n,
        "pnl_gross": sum(t["pnl_gross"] for t in trades),
        "charges": sum(t["charges"] for t in trades),
        "pnl_net": equity,
        "profit_factor": gross_win / gross_loss if gross_loss else float("inf"),
        "max_drawdown": worst,
        "green_days": sum(v > 0 for v in days.values()) / len(days),
        "worst_day": min(days.values()),
        "best_day": max(days.values()),
    }
