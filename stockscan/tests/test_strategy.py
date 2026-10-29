import numpy as np
import pandas as pd

from stockscan import backtest
from stockscan.strategy import Rules, picks, regime_on, market_index, scores

DAYS = pd.bdate_range("2018-01-01", periods=700)


def panel(trends: dict[str, float], noise: float = 0.003, seed: int = 1):
    """Stocks with a steady daily drift, plus noise."""
    rng = np.random.default_rng(seed)
    adj = pd.DataFrame({s: 100 * np.exp(np.cumsum(d + noise * rng.standard_normal(len(DAYS)))) for s, d in trends.items()}, index=DAYS)
    value = pd.DataFrame(1e8, index=DAYS, columns=adj.columns)  # Rs 10 crore a day each
    return adj, value


def test_steady_climbers_are_ranked_first():
    adj, value = panel({"UP1": 0.002, "UP2": 0.0015, "FLAT": 0.0, "DOWN": -0.001})
    got = picks(adj, value, DAYS[-1], Rules(top_n=2, use_regime=False))
    assert set(got) == {"UP1", "UP2"}  # the two climbers, not the flat or falling stock


def test_illiquid_and_spiking_stocks_are_skipped():
    adj, value = panel({"UP1": 0.002, "THIN": 0.002, "PUMP": 0.001})
    value["THIN"] = 1e6
    adj.loc[DAYS[-15]:, "PUMP"] *= 2.0  # doubled in the last month
    s = scores(adj, value, DAYS[-1], Rules(use_regime=False))
    assert s.loc["THIN", "reason"] == "too little trading"
    assert s.loc["PUMP", "reason"] == "spiked this month"
    assert list(s[s.eligible].index) == ["UP1"]


def test_cash_when_small_caps_fall():
    adj, value = panel({f"S{i}": -0.002 for i in range(8)} | {"UP": 0.003})
    regime = regime_on(market_index(adj))
    assert not regime.iloc[-1]
    assert picks(adj, value, DAYS[-1], Rules(), regime) == []
    assert picks(adj, value, DAYS[-1], Rules(use_regime=False), regime) == ["UP"]


def test_backtest_never_uses_future_prices():
    adj, value = panel({"A": 0.001, "B": 0.001, "C": 0.001})
    day = DAYS[500]
    before = scores(adj, value, day, Rules(use_regime=False))
    later = adj.copy()
    later.loc[DAYS[501]:, "C"] *= 5  # a future jump must not change today's decision
    after = scores(later, value, day, Rules(use_regime=False))
    pd.testing.assert_frame_equal(before, after)


def test_costs_are_paid_and_equity_tracks_holdings():
    adj, value = panel({"UP1": 0.003, "UP2": 0.001, "DOWN": -0.001}, noise=0.001)
    free = backtest.run(adj, value, Rules(top_n=1, use_regime=False), cost_rate=0.0)
    paid = backtest.run(adj, value, Rules(top_n=1, use_regime=False), cost_rate=0.01)
    assert paid.equity.iloc[-1] < free.equity.iloc[-1]
    # Holding UP1 from its first trade: equity grows exactly with its price.
    assert all(h == [] for d, h in free.holdings.items() if d < DAYS[260]), "no picks before a year of history"
    first = min(d for d, h in free.holdings.items() if h)
    assert all(h == ["UP1"] for d, h in free.holdings.items() if d >= first)
    growth = free.equity.iloc[-1] / free.equity.loc[first]
    assert abs(growth - adj["UP1"].iloc[-1] / adj["UP1"].loc[first]) < 1e-9
    m = backtest.metrics(free.equity)
    assert m["cagr"] > 0 and m["max_drawdown"] > -0.01
