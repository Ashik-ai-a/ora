"""Monthly-rebalanced backtest with costs, and the numbers that matter for a real person.

Decisions use prices up to the last trading day of a month; trades happen at the next day's
close, so nothing sees the future. Each buy or sell pays cost_rate (brokerage, STT, stamp
duty and slippage together).
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from .strategy import Rules, market_index, picks, regime_on


@dataclass
class Result:
    equity: pd.Series  # portfolio value, starts at 1
    holdings: dict  # execution day -> list of symbols
    turnover: float  # average fraction of the portfolio traded per rebalance


def month_ends(index: pd.DatetimeIndex) -> list[pd.Timestamp]:
    s = pd.Series(index, index=index)
    return list(s.groupby([index.year, index.month]).max())


def run(adj: pd.DataFrame, value: pd.DataFrame, rules: Rules, cost_rate: float = 0.0025,
        start: pd.Timestamp | None = None) -> Result:
    prices = adj.ffill()
    regime = regime_on(market_index(adj))
    days = adj.index
    decisions = [d for d in month_ends(days) if (start is None or d >= start)]
    decision_set = set(decisions[:-1] if decisions and decisions[-1] == days[-1] else decisions)

    cash, units = 1.0, pd.Series(dtype=float)
    equity, holdings, turnovers = {}, {}, []
    pending = None
    first = decisions[0] if decisions else days[-1]
    for i, day in enumerate(days):
        if day < first:
            continue
        px = prices.loc[day]
        value_now = cash + float((units * px.reindex(units.index)).fillna(0).sum())
        if pending is not None:
            target = pending
            pending = None
            want = {s: value_now / len(target) for s in target if pd.notna(px.get(s))} if target else {}
            current = (units * px.reindex(units.index)).fillna(0)
            traded = sum(abs(want.get(s, 0) - current.get(s, 0)) for s in set(want) | set(current.index))
            cost = traded * cost_rate
            value_now -= cost
            turnovers.append(traded / max(value_now, 1e-12))
            if want:
                scale = value_now / sum(want.values())
                units = pd.Series({s: v * scale / px[s] for s, v in want.items()})
                cash = 0.0
            else:
                units, cash = pd.Series(dtype=float), value_now
            holdings[day] = list(want)
        equity[day] = value_now
        if day in decision_set and i + 1 < len(days):
            pending = picks(adj, value, day, rules, regime)
    return Result(pd.Series(equity), holdings, float(np.mean(turnovers)) if turnovers else 0.0)


def metrics(equity: pd.Series, rf: float = 0.065) -> dict:
    """CAGR, worst fall, volatility and the like. rf: yearly risk-free rate (Indian T-bills)."""
    equity = equity.dropna()
    if len(equity) < 30:
        return {}
    years = (equity.index[-1] - equity.index[0]).days / 365.25
    daily = equity.pct_change().dropna()
    cagr = (equity.iloc[-1] / equity.iloc[0]) ** (1 / years) - 1
    drawdown = equity / equity.cummax() - 1
    monthly = equity.resample("ME").last().pct_change().dropna()
    vol = daily.std() * np.sqrt(252)
    return {
        "start": equity.index[0].date().isoformat(),
        "end": equity.index[-1].date().isoformat(),
        "years": round(years, 1),
        "growth_of_1L": round(1e5 * equity.iloc[-1] / equity.iloc[0]),
        "cagr": cagr,
        "max_drawdown": drawdown.min(),
        "volatility": vol,
        "sharpe": (cagr - rf) / vol if vol > 0 else np.nan,
        "worst_month": monthly.min(),
        "best_month": monthly.max(),
        "months_up": (monthly > 0).mean(),
    }


def yearly(equity: pd.Series) -> pd.Series:
    e = equity.dropna()
    ends = e.groupby(e.index.year).last()
    starts = e.groupby(e.index.year).first()
    prev = ends.shift(1).fillna(starts)
    return ends / prev - 1
