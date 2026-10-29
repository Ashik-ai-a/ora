"""Budget and risk: how many shares, what it costs, and whether the trade is allowed at all.

Everything is in rupees and read from trader/config.json (defaults below). Leverage is 1 by
default: intraday margin (MIS) multiplies losses exactly as much as gains.
"""
from __future__ import annotations

import json
import math
from datetime import datetime
from pathlib import Path

from . import costs
from .nse import hhmm
from .rules import Plan

DEFAULTS = {
    "capital": 150000,
    "max_loss_per_trade": 1500,  # 1% of capital
    "daily_loss_limit": 3000,  # stop for the day after this, win or lose later
    "max_trades_per_day": 3,
    "leverage": 1.0,
    "min_reward_risk": 1.0,
    "entry_window": ["09:30", "14:30"],
    "strategy": {},
    "costs": {},
}


def load_config(state: Path) -> dict:
    cfg = json.loads(json.dumps(DEFAULTS))
    path = state / "config.json"
    if path.exists():
        cfg.update(json.loads(path.read_text()))
    return cfg


def size(plan: Plan, cfg: dict) -> dict:
    """The order: shares, value, and the rupee result at the stop and at the target, after charges."""
    rates = costs.Rates.from_config(cfg.get("costs"))
    budget = cfg["capital"] * cfg.get("leverage", 1.0)
    qty = min(math.floor(budget / plan.entry), math.floor(cfg["max_loss_per_trade"] / plan.risk))
    # Charges count toward the max loss: shrink until the loss at the stop, all in, fits.
    while qty > 0:
        at_stop = _net(qty, plan, plan.stop, rates)
        if -at_stop <= cfg["max_loss_per_trade"]:
            break
        qty -= 1
    if qty <= 0:
        return {"qty": 0, "reason": "One share risks more than your max loss per trade (or costs more than your capital)."}
    at_stop, at_target = _net(qty, plan, plan.stop, rates), _net(qty, plan, plan.target, rates)
    rt = costs.round_trip(qty, *((plan.entry, plan.target) if plan.side == "long" else (plan.target, plan.entry)), rates)
    return {
        "symbol": plan.symbol, "side": plan.side, "qty": qty, "entry": plan.entry, "stop": plan.stop, "target": plan.target,
        "value": round(qty * plan.entry, 2), "margin_needed": round(qty * plan.entry / cfg.get("leverage", 1.0), 2),
        "loss_at_stop": round(at_stop, 2), "profit_at_target": round(at_target, 2),
        "charges_round_trip": round(rt["fees"], 2), "slippage_assumed": round(rt["slippage"], 2),
        "reward_risk_after_costs": round(at_target / -at_stop, 2) if at_stop < 0 else None,
        "flat_by": plan.flat_by, "day": plan.day, "entry_time": plan.entry_time,
    }


def _net(qty: int, plan: Plan, exit_price: float, rates: costs.Rates) -> float:
    gross = qty * (exit_price - plan.entry) * (1 if plan.side == "long" else -1)
    buy, sell = (plan.entry, exit_price) if plan.side == "long" else (exit_price, plan.entry)
    return gross - costs.round_trip(qty, buy, sell, rates)["total"]


def check(order: dict, cfg: dict, journal: list[dict], now: datetime) -> list[tuple[str, bool, str]]:
    """Every rule, PASS or BLOCK, with the reason. The trade goes ahead only if all pass."""
    today = order.get("day") or now.date().isoformat()
    todays = [t for t in journal if t.get("day") == today]
    realised = sum(t.get("pnl", 0) for t in todays if t.get("status") == "closed")
    open_risk = sum(-t.get("loss_at_stop", 0) for t in todays if t.get("status") == "open")
    worst = realised - open_risk + order.get("loss_at_stop", 0)
    lo, hi = (hhmm(x) for x in cfg["entry_window"])
    t = (now.hour, now.minute)
    out = [
        ("shares", order.get("qty", 0) > 0, f"{order.get('qty', 0)} shares" if order.get("qty") else order.get("reason", "no size")),
        ("max loss per trade", -order.get("loss_at_stop", 0) <= cfg["max_loss_per_trade"] + 0.01,
         f"Rs {-order.get('loss_at_stop', 0):,.0f} at the stop, limit Rs {cfg['max_loss_per_trade']:,.0f}"),
        ("daily loss limit", -worst <= cfg["daily_loss_limit"] + 0.01,
         f"today so far Rs {realised:,.0f}, open risk Rs {open_risk:,.0f}; worst case with this trade Rs {worst:,.0f}, "
         f"limit -Rs {cfg['daily_loss_limit']:,.0f}"),
        ("trades per day", len(todays) < cfg["max_trades_per_day"], f"{len(todays)} taken today, limit {cfg['max_trades_per_day']}"),
        ("reward to risk", (order.get("reward_risk_after_costs") or 0) >= cfg["min_reward_risk"],
         f"{order.get('reward_risk_after_costs')} after charges, need {cfg['min_reward_risk']}"),
        ("entry window", lo <= t <= hi, f"now {now:%H:%M}, allowed {cfg['entry_window'][0]} to {cfg['entry_window'][1]} IST"),
        ("capital", order.get("margin_needed", 0) <= cfg["capital"] - sum(x.get("margin_needed", 0) for x in todays if x.get("status") == "open"),
         f"needs Rs {order.get('margin_needed', 0):,.0f} of Rs {cfg['capital']:,.0f}"),
    ]
    return out
