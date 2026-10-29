"""Small-cap momentum rules. Everything here only looks at data up to the decision day.

Ranking follows the idea behind NSE's momentum indices: 6- and 12-month returns, skipping the
latest month, divided by volatility, so a steady climb beats a single wild jump. On top:

  - trend: price above its 200-day average
  - liquidity: median daily traded value over 60 days >= min_value (you can get in and out)
  - no pumps: a stock up more than spike_limit in the last month is skipped, not chased
  - market regime: when small caps as a whole are below their 200-day average, hold cash
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

DAY_6M, DAY_12M, SKIP = 126, 252, 21


@dataclass(frozen=True)
class Rules:
    top_n: int = 10
    min_value: float = 5e7  # Rs 5 crore a day
    spike_limit: float = 0.60
    use_regime: bool = True


def market_index(adj: pd.DataFrame) -> pd.Series:
    """Equal-weight index of the whole universe (average daily return)."""
    rets = adj.pct_change(fill_method=None).clip(-0.5, 1.0)
    return (1 + rets.mean(axis=1).fillna(0)).cumprod()


def regime_on(index: pd.Series) -> pd.Series:
    return index > index.rolling(200, min_periods=200).mean()


def scores(adj: pd.DataFrame, value: pd.DataFrame, day: pd.Timestamp, rules: Rules) -> pd.DataFrame:
    """Every stock on [day] with its numbers, whether it qualifies and why not."""
    hist = adj.loc[:day]
    if len(hist) < DAY_12M + 1:
        return pd.DataFrame()
    p = hist.iloc[-1]
    p_skip = hist.iloc[-1 - SKIP]
    p6, p12 = hist.iloc[-1 - DAY_6M], hist.iloc[-1 - DAY_12M]
    r6, r12 = p_skip / p6 - 1, p_skip / p12 - 1
    r1 = p / p_skip - 1
    daily = hist.iloc[-DAY_12M:].pct_change(fill_method=None)
    vol = daily.std() * np.sqrt(252)
    sma200 = hist.iloc[-200:].mean()
    liquidity = value.loc[:day].iloc[-60:].median()
    enough = hist.iloc[-DAY_12M - 1:].notna().sum() >= DAY_12M * 0.95

    df = pd.DataFrame({"price": p, "ret_1m": r1, "ret_6m": r6, "ret_12m": r12, "vol": vol,
                       "sma200": sma200, "value_cr": liquidity / 1e7})
    reason = pd.Series("", index=df.index)
    reason[~enough] = "under a year of prices"
    reason[(reason == "") & (df.price <= df.sma200)] = "below 200-day average"
    reason[(reason == "") & ((df.ret_6m <= 0) | (df.ret_12m <= 0))] = "no momentum"
    reason[(reason == "") & (liquidity < rules.min_value)] = "too little trading"
    reason[(reason == "") & (df.ret_1m > rules.spike_limit)] = "spiked this month"
    reason[(reason == "") & ~(df.vol > 0)] = "no price movement"
    df["eligible"] = reason == ""
    df["reason"] = reason

    ok = df.eligible
    m6, m12 = df.ret_6m / df.vol, df.ret_12m / df.vol
    z = lambda s: (s - s[ok].mean()) / (s[ok].std() or 1)  # noqa: E731
    df["score"] = np.where(ok, 0.5 * z(m6) + 0.5 * z(m12), np.nan)
    return df.sort_values("score", ascending=False, na_position="last")


def picks(adj: pd.DataFrame, value: pd.DataFrame, day: pd.Timestamp, rules: Rules,
          regime: pd.Series | None = None) -> list[str]:
    if rules.use_regime and regime is not None and not bool(regime.loc[:day].iloc[-1]):
        return []
    s = scores(adj, value, day, rules)
    if s.empty:
        return []
    return list(s[s.eligible].head(rules.top_n).index)
