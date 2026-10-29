"""python -m stockscan [--capital 150000] [--out report] [--data data]

Downloads the Nifty Smallcap 250 and ~12 years of prices, backtests the momentum rules,
runs today's scan, and writes report.md + picks.json. With NTFY_TOPIC set, it also pushes a
phone notification when the picks change (https://ntfy.sh, free app on Android and iPhone).
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path

import pandas as pd
import requests

from . import backtest, data
from .strategy import Rules, market_index, regime_on, scores

pct = lambda x: "n/a" if x is None or pd.isna(x) else f"{x * 100:+.1f}%"  # noqa: E731
pct0 = lambda x: "n/a" if x is None or pd.isna(x) else f"{x * 100:.1f}%"  # noqa: E731
rupees = lambda x: f"Rs {x:,.0f}"  # noqa: E731


def table(rows: list[list], head: list[str]) -> str:
    out = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--capital", type=float, default=150_000)
    ap.add_argument("--data", type=Path, default=Path("data"))
    ap.add_argument("--out", type=Path, default=Path("report"))
    ap.add_argument("--years", type=int, default=12)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    print("Universe: Nifty Smallcap 250", flush=True)
    uni = data.smallcap_universe(args.data)
    names = dict(zip(uni.symbol, uni.name))
    industries = dict(zip(uni.symbol, uni.industry))
    adj, value, missing = data.price_panel(list(uni.symbol), args.data / "prices", args.years)
    bench = data.benchmarks(args.data / "prices", args.years)
    print(f"  {adj.shape[1]} stocks with prices, {len(missing)} missing, {adj.index[0].date()} to {adj.index[-1].date()}", flush=True)

    rules = Rules()
    start = adj.index[0] + pd.Timedelta(days=400)  # a year of history before the first pick
    res = backtest.run(adj, value, rules, start=start)
    eq = res.equity
    ew = market_index(adj).loc[eq.index[0]:]
    lines = []
    compare = {"Momentum strategy (top 10, monthly)": eq, "All 250 small caps, equal weight": ew}
    for name, s in bench.items():
        compare[name] = s.dropna().loc[eq.index[0]:]
    rows = []
    for name, s in compare.items():
        m = backtest.metrics(s)
        if m:
            rows.append([name, pct(m["cagr"]), pct(m["max_drawdown"]), pct(m["worst_month"]), rupees(m["growth_of_1L"]),
                         f"{m['sharpe']:.2f}"])
    m = backtest.metrics(eq)

    # Robustness: does it only work for one lucky setting?
    grid = []
    for n in (5, 10, 15, 20):
        for reg in (True, False):
            r = backtest.run(adj, value, Rules(top_n=n, use_regime=reg), start=start)
            gm = backtest.metrics(r.equity)
            grid.append([n, "yes" if reg else "no", pct(gm["cagr"]), pct(gm["max_drawdown"]), f"{gm['sharpe']:.2f}"])
    half = eq.index[len(eq) // 2]
    halves = [[f"{s.index[0].date()} to {s.index[-1].date()}", pct(backtest.metrics(s)["cagr"]), pct(backtest.metrics(s)["max_drawdown"]),
               pct(backtest.metrics(ew.loc[s.index[0]:s.index[-1]])["cagr"])] for s in (eq.loc[:half], eq.loc[half:])]
    yr_s, yr_ew = backtest.yearly(eq), backtest.yearly(ew)
    years_rows = [[y, pct(yr_s.get(y)), pct(yr_ew.get(y))] for y in yr_s.index]
    for name, s in bench.items():
        yb = backtest.yearly(s.dropna().loc[eq.index[0]:])
        for row in years_rows:
            row.append(pct(yb.get(row[0])))

    # Today's scan
    today = adj.index[-1]
    regime = regime_on(market_index(adj))
    market_up = bool(regime.iloc[-1])
    sc = scores(adj, value, today, rules)
    # Whole shares only: a stock priced above one slice can't be bought, so take the next one.
    each = args.capital / rules.top_n
    ranked = sc[sc.eligible]
    chosen = ranked[ranked.price <= each].head(rules.top_n)
    too_dear = [s for s in ranked.head(rules.top_n).index if s not in chosen.index]
    pick_rows, pick_json = [], []
    for sym, r in chosen.iterrows():
        qty = math.floor(each / r.price) if market_up else 0
        pick_rows.append([sym, names.get(sym, ""), industries.get(sym, ""), f"{r.price:,.2f}", pct(r.ret_6m), pct(r.ret_12m),
                          f"{r.value_cr:,.0f}", qty, rupees(qty * r.price)])
        pick_json.append({"symbol": sym, "name": names.get(sym, ""), "price": round(float(r.price), 2), "qty": qty,
                          "ret_6m": round(float(r.ret_6m), 4), "ret_12m": round(float(r.ret_12m), 4)})
    reasons = sc.reason[~sc.eligible].value_counts()

    lines += [
        "# Small-cap momentum scan",
        "",
        f"Data to **{today.date()}**. {adj.shape[1]} of {len(uni)} Nifty Smallcap 250 stocks had prices"
        + (f" (missing: {', '.join(missing[:15])}{'...' if len(missing) > 15 else ''})." if missing else "."),
        "",
        "## Would it have made money?",
        "",
        f"Backtest {m['start']} to {m['end']} ({m['years']} years), rebalanced monthly, {rules.top_n} stocks, "
        "0.25% cost on every buy and sell, trades at the next day's close.",
        "",
        table(rows, ["", "Yearly return (CAGR)", "Worst fall from a peak", "Worst month", "Rs 1 lakh became", "Sharpe"]),
        "",
        f"Average share of the portfolio replaced each month: {pct0(res.turnover)}. "
        f"Months that ended up: {pct0(m['months_up'])}.",
        "",
        "**Read this before trusting the numbers:** the test uses *today's* Smallcap 250 list. Companies that "
        "collapsed or were delisted over these years are not in it, and some of today's members were tiny "
        "stocks that rose into the index. Both make any small-cap backtest look better than real life "
        "(survivorship bias). Treat these returns as an upper bound, and the worst-fall column as a floor: "
        "real falls can be deeper.",
        "",
        "### Year by year",
        "",
        table(years_rows, ["Year", "Strategy", "All small caps"] + list(bench.columns)),
        "",
        "### Is it one lucky setting?",
        "",
        "Same rules with a different number of stocks, with and without the move to cash when small caps fall:",
        "",
        table(grid, ["Stocks held", "Cash in downtrends", "CAGR", "Worst fall", "Sharpe"]),
        "",
        "First half against second half (the rules were not tuned on either):",
        "",
        table(halves, ["Period", "Strategy CAGR", "Worst fall", "All small caps CAGR"]),
        "",
        f"## Today's scan ({today.date()})",
        "",
        ("Small caps as a group are **above** their 200-day average: the rules would be invested."
         if market_up else
         "Small caps as a group are **below** their 200-day average: the rules would hold **cash** now. "
         "The ranking below is for watching only."),
        "",
        f"Split of {rupees(args.capital)} across the top {len(chosen)} (whole shares, so a little stays in cash):"
        + (f" {', '.join(too_dear)} ranked higher but one share costs more than {rupees(each)}, so the next stocks take their place."
           if too_dear else ""),
        "",
        table(pick_rows, ["Symbol", "Company", "Industry", "Price", "6 months", "12 months", "Traded/day (Rs cr)", "Qty", "Amount"]),
        "",
        "Why the other stocks were left out: " + ", ".join(f"{k} {v}" for k, v in reasons.items()) + ".",
        "",
        "## How to use it",
        "",
        "- Buy the list at the start of a month, hold it, and swap only at the next monthly scan. "
        "Checking daily and reacting is what loses money.",
        "- If the scan says cash, sell and wait. That rule is most of what limits the worst falls.",
        "- Check each company's latest results and announcements on NSE/BSE before buying. This scan "
        "only reads prices; it does not read accounts or news.",
        "- Only invest money you can leave alone for years and could see fall by a third or more.",
        "",
        "This is a mechanical screen, not investment advice, and past results do not promise future ones.",
    ]
    report = "\n".join(lines) + "\n"
    (args.out / "report.md").write_text(report)
    (args.out / "picks.json").write_text(json.dumps({"date": str(today.date()), "invested": market_up, "picks": pick_json}, indent=2))
    eq.to_csv(args.out / "equity.csv", header=["strategy"])
    print(report)
    notify(args.data / "last_picks.json", today, market_up, pick_json)


def notify(state: Path, today: pd.Timestamp, invested: bool, picks: list[dict]) -> None:
    """Push to the phone only when it matters: the monthly buy list, or a switch to/from cash.

    Daily ranking wobbles are not sent; reacting to them is how people over-trade.
    """
    topic = os.environ.get("NTFY_TOPIC")
    month = today.strftime("%Y-%m")
    before = json.loads(state.read_text()) if state.exists() else {}
    symbols = [p["symbol"] for p in picks] if invested else []
    flipped = before.get("invested") is not None and before.get("invested") != invested
    new_month = before.get("month") != month
    if not (flipped or new_month):
        return
    state.parent.mkdir(parents=True, exist_ok=True)
    state.write_text(json.dumps({"invested": invested, "month": month, "symbols": symbols}))
    if not topic:
        return
    if not invested:
        title = "Small caps: switch to cash" if flipped else f"Small caps {month}: stay in cash"
        msg = "Small caps as a group fell below their 200-day average. The rules say: sell and hold cash until the next signal."
    else:
        old, new = set(before.get("symbols", [])), set(symbols)
        title = "Small caps: back in" if flipped else f"Small caps buy list for {month}"
        msg = "\n".join(f"{p['symbol']}: {p['qty']} shares at Rs {p['price']:,.2f}" for p in picks)
        if old:
            msg += f"\nSell: {', '.join(sorted(old - new)) or 'nothing'}\nNew: {', '.join(sorted(new - old)) or 'nothing'}"
    try:
        requests.post(f"https://ntfy.sh/{topic}", data=msg.encode(), timeout=20,
                      headers={"Title": title, "Tags": "chart_with_upwards_trend", "Priority": "high" if flipped else "default"})
    except requests.RequestException as e:
        print(f"notification failed: {e}")


if __name__ == "__main__":
    main()
