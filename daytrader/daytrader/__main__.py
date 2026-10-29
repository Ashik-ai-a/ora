"""python3 -m daytrader <command>   (run from the repository root; state lives in ./trader)

  scan         opening-range breakouts on an NSE universe right now (or --date/--at to replay)
  plan         turn one breakout into a plan: entry, stop, target, out by 15:15
  timemachine  10,000 replays + the real history of the same rule, after Indian charges
  size         shares for your max loss, with every charge, as a plain order ticket
  risk         PASS / BLOCK against your limits; --record logs the trade as taken
  close        record how a trade ended (for the daily loss limit)
  backtest     proof run: the rule on a whole universe over the last ~60 days, after charges
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date, datetime
from pathlib import Path

from . import backtest, costs, money, nse, timemachine
from .rules import Plan, Strategy, outcome, signal

rs = lambda x: f"Rs {x:,.0f}" if x >= 0 else f"-Rs {-x:,.0f}"  # noqa: E731
pc = lambda x: f"{x * 100:.1f}%"  # noqa: E731


def strategy(cfg: dict, a: argparse.Namespace) -> Strategy:
    s = dict(cfg.get("strategy") or {})
    for key in ("target_r", "stop_rule", "flat_by", "last_entry"):
        if getattr(a, key, None) is not None:
            s[key] = getattr(a, key)
    if getattr(a, "shorts", False):
        s["sides"] = ["long", "short"]
    return Strategy.from_config(s)


def clock(a: argparse.Namespace) -> datetime:
    if getattr(a, "date", None) or getattr(a, "at", None):
        d = date.fromisoformat(a.date) if a.date else nse.now_ist().date()
        h, m = nse.hhmm(a.at or "15:30")
        return datetime(d.year, d.month, d.day, h, m)
    return nse.now_ist()


def save(state: Path, name: str, obj) -> None:
    state.mkdir(parents=True, exist_ok=True)
    (state / name).write_text(json.dumps(obj, indent=2, default=str))


def load(state: Path, name: str, what: str):
    path = state / name
    if not path.exists():
        sys.exit(f"No {path}. Run {what} first.")
    return json.loads(path.read_text())


def cmd_scan(a, cfg, state):
    s, now = strategy(cfg, a), clock(a)
    days = 5 if (nse.now_ist().date() - now.date()).days < 3 else 60
    uni = nse.universe(a.universe, state / "cache")
    names = {u["symbol"]: u["name"] for u in uni}
    found, watch = [], []
    for i, u in enumerate(uni):
        sym = u["symbol"]
        sess = nse.sessions(nse.fetch_5m(sym, state / "cache", days))
        today = sess.get(now.date())
        if not today:
            continue
        past = [b for d, b in sess.items() if d < now.date() and nse.is_full(b)][-5:]
        traded = sum(sum(x.close * x.volume for x in b) for b in past) / len(past) if past else 0
        if traded < a.min_traded_cr * 1e7:
            continue
        bars = nse.closed_bars(today, now)
        plan = signal(sym, bars, s)
        or_vol = sum(b.volume for b in bars[:s.or_bars])
        past_or = sum(sum(x.volume for x in b[:s.or_bars]) for b in past) / len(past) if past else 0
        rel = or_vol / past_or if past_or else 0
        if plan:
            plan.extra = {"rel_volume": round(rel, 2), "traded_cr": round(traded / 1e7, 1), "name": names.get(sym, "")}
            found.append(plan)
        elif len(bars) > s.or_bars:
            hi = max(b.high for b in bars[:s.or_bars])
            if bars[-1].close >= hi * 0.995:
                watch.append((sym, bars[-1].close, hi))
        if (i + 1) % 50 == 0:
            print(f"  scanned {i + 1}/{len(uni)}", file=sys.stderr, flush=True)
    found.sort(key=lambda p: (p.entry_time, -p.extra["rel_volume"]))
    print(f"Opening-range breakouts, {a.universe}, as of {now:%Y-%m-%d %H:%M} IST "
          f"(range {s.or_bars * 5} min, liquidity >= Rs {a.min_traded_cr:g} cr/day): {len(found)}")
    print(f"{'#':>2}  {'symbol':<12}{'side':<6}{'at':<7}{'entry':>10}{'stop':>10}{'target':>10}{'risk/sh':>9}{'rel vol':>8}{'Rs cr/day':>10}")
    for k, p in enumerate(found[:a.top], 1):
        print(f"{k:>2}  {p.symbol:<12}{p.side:<6}{p.entry_time:<7}{p.entry:>10.2f}{p.stop:>10.2f}{p.target:>10.2f}"
              f"{p.risk:>9.2f}{p.extra['rel_volume']:>8.1f}{p.extra['traded_cr']:>10.1f}")
    if watch:
        print("Near the top of the range, no breakout yet: " + ", ".join(f"{w[0]} {w[1]:.2f}/{w[2]:.2f}" for w in watch[:15]))
    if a.write:
        save(state, "scan.json", {"as_of": now.isoformat(), "universe": a.universe, "plans": [p.to_json() for p in found]})
        print(f"Wrote {state / 'scan.json'}")


def cmd_plan(a, cfg, state):
    s, now = strategy(cfg, a), clock(a)
    sess = nse.sessions(nse.fetch_5m(a.symbol, state / "cache", 60))
    bars = nse.closed_bars(sess.get(now.date(), []), now)
    plan = signal(a.symbol, bars, s)
    if not plan:
        sys.exit(f"{a.symbol}: no opening-range breakout by {now:%H:%M} on {now.date()} (or the range was wider than "
                 f"{s.max_or_pct * 100:g}%).")
    print(f"{plan.symbol} {plan.side.upper()} {plan.day}: opening range {plan.or_low:.2f} to {plan.or_high:.2f}; the "
          f"{plan.entry_time} close {plan.entry:.2f} broke out.\n  stop {plan.stop:.2f} ({plan.stop_rule}), risk "
          f"{plan.risk:.2f}/share; target {plan.target:.2f} ({plan.target_r:g}R); out by {plan.flat_by} either way.")
    save(state, "plan.json", plan.to_json())
    print(f"Wrote {state / 'plan.json'}")


def cmd_timemachine(a, cfg, state):
    plan = Plan.from_json(load(state, "plan.json", "/nse-plan"))
    s = Strategy.from_config({**(cfg.get("strategy") or {}), "stop_rule": plan.stop_rule, "target_r": plan.target_r,
                              "flat_by": plan.flat_by, "sides": [plan.side]})
    sess = nse.sessions(nse.fetch_5m(plan.symbol, state / "cache", 60))
    day = date.fromisoformat(plan.day)
    pool = timemachine.ratio_pool(sess, day)
    if len(pool) < 20:
        sys.exit(f"Only {len(pool)} full sessions before {day}: too few for a fair replay (need 20).")
    order = money.size(plan, cfg)
    qty = order.get("qty") or 1
    rates = costs.Rates.from_config(cfg.get("costs"))
    cost_r = costs.per_share(qty, plan.entry, plan.target, plan.side, rates) / plan.risk
    mc = timemachine.replay(plan, pool, s.flat_index, a.replays, a.seed)
    hist = timemachine.history(plan.symbol, sess, day, s)
    hits = {k: sum(h["result"] == k for h in hist) for k in ("target", "stop", "flat")}
    hist_r = sum(h["r"] for h in hist) / len(hist) if hist else 0
    v = timemachine.verdict(mc["target"], mc["stop"], plan.target_r, cost_r)
    print(f"Time Machine: {plan.symbol} {plan.side} {plan.day}, entry {plan.entry} at {plan.entry_time}, stop {plan.stop}, "
          f"target {plan.target}, out by {plan.flat_by}")
    print(f"  {mc['replays']:,} replays from {len(pool)} full sessions (seed {mc['seed']}):")
    print(f"    target first {pc(mc['target'])}   stop first {pc(mc['stop'])}   still open at {plan.flat_by} {pc(mc['neither'])}"
          f"   green {pc(mc['green'])}")
    print(f"    exit price: median {mc['exit_median']}, 5% to 95%: {mc['exit_p5']} to {mc['exit_p95']}")
    print(f"    average R {mc['avg_r']:+.2f} before charges, {mc['avg_r'] - cost_r:+.2f} after "
          f"(charges + slippage = {cost_r:.2f}R for {qty} shares)")
    print(f"  History: the same rule fired on {len(hist)} of {len(pool)} past sessions: {hits['target']} target, "
          f"{hits['stop']} stop, {hits['flat']} out at {plan.flat_by}; average {hist_r:+.2f}R before charges, "
          f"{hist_r - cost_r:+.2f}R after.")
    print(f"  Verdict: {v}")
    if mc["neither"] > 0.4:
        print("  Over 40% of replays were still open at the flat-by time: the exit price decides those.")
    print("  Built from this stock's last ~60 days. Not a prediction: results, news and circuits can do what those days never did.")
    if a.write:
        save(state, "timemachine.json", {"plan": plan.to_json(), "mc": mc, "cost_r": cost_r, "history": hist, "verdict": v})


def cmd_size(a, cfg, state):
    plan = Plan.from_json(load(state, "plan.json", "/nse-plan"))
    order = money.size(plan, cfg)
    if not order.get("qty"):
        sys.exit(order["reason"])
    verb = "BUY" if plan.side == "long" else "SELL (short)"
    print(f"Order ticket ({plan.day}), intraday / MIS:\n  {verb} {order['qty']} {plan.symbol} at about {plan.entry}"
          f" = {rs(order['value'])}" + (f" (margin {rs(order['margin_needed'])})" if cfg.get('leverage', 1) > 1 else ""))
    print(f"  Stop-loss {plan.stop}: you lose {rs(-order['loss_at_stop'])} all in (limit {rs(cfg['max_loss_per_trade'])}).")
    print(f"  Target {plan.target}: you make {rs(order['profit_at_target'])} after charges.")
    print(f"  Charges {rs(order['charges_round_trip'])} + assumed slippage {rs(order['slippage_assumed'])}. "
          f"Reward to risk after costs: {order['reward_risk_after_costs']}.")
    print(f"  Exit everything by {plan.flat_by}, before the broker's own square-off.")
    save(state, "order.json", order)


def cmd_risk(a, cfg, state):
    order = load(state, "order.json", "/nse-size")
    journal = json.loads((state / "journal.json").read_text()) if (state / "journal.json").exists() else []
    now = clock(a)
    results = money.check(order, cfg, journal, now)
    for name, ok, detail in results:
        print(f"  {'PASS' if ok else 'BLOCK'}  {name}: {detail}")
    passed = all(ok for _, ok, _ in results)
    print("PASS: every limit holds." if passed else "BLOCK: do not take this trade.")
    if passed and a.record:
        journal.append({**order, "status": "open", "taken_at": now.isoformat()})
        save(state, "journal.json", journal)
        print("Logged as taken. Record the result with: python3 -m daytrader close --symbol "
              f"{order['symbol']} --price <exit price>")
    sys.exit(0 if passed else 2)


def cmd_close(a, cfg, state):
    journal = json.loads((state / "journal.json").read_text()) if (state / "journal.json").exists() else []
    rates = costs.Rates.from_config(cfg.get("costs"))
    for t in reversed(journal):
        if t["symbol"] == a.symbol and t["status"] == "open":
            sign = 1 if t["side"] == "long" else -1
            gross = t["qty"] * (a.price - t["entry"]) * sign
            buy, sell = (t["entry"], a.price) if t["side"] == "long" else (a.price, t["entry"])
            t.update(status="closed", exit=a.price, pnl=round(gross - costs.round_trip(t["qty"], buy, sell, rates)["fees"], 2))
            save(state, "journal.json", journal)
            day = sum(x.get("pnl", 0) for x in journal if x["day"] == t["day"] and x["status"] == "closed")
            print(f"{a.symbol} closed at {a.price}: {rs(t['pnl'])} after charges. Today: {rs(day)} "
                  f"(daily limit -{rs(cfg['daily_loss_limit'])}).")
            return
    sys.exit(f"No open trade in {a.symbol} in {state / 'journal.json'}.")


def cmd_backtest(a, cfg, state):
    s = strategy(cfg, a)
    uni = nse.universe(a.universe, state / "cache")
    trades = []
    for i, u in enumerate(uni):
        sess = nse.sessions(nse.fetch_5m(u["symbol"], state / "cache", 60, max_age_s=6 * 3600))
        trades += backtest.trades_for(u["symbol"], sess, s, cfg, a.min_traded_cr * 1e7)
        if (i + 1) % 50 == 0:
            print(f"  {i + 1}/{len(uni)} stocks", file=sys.stderr, flush=True)
    taken = backtest.portfolio(trades, cfg)
    every, real = backtest.summary(trades), backtest.summary(taken)
    lines = [f"# Opening-range breakout on NSE {a.universe}: proof run", ""]
    if not every.get("trades"):
        lines.append("No signals at all: check the data download.")
    else:
        lines += [
            f"Rule: {s.or_bars * 5}-minute opening range, first 5-minute close beyond it ({'/'.join(s.sides)}), stop "
            f"{s.stop_rule}, target {s.target_r:g}R, out by {s.flat_by}, no entries after {s.last_entry}. Stocks trading at "
            f"least Rs {a.min_traded_cr:g} crore a day. Capital {rs(cfg['capital'])}, max loss {rs(cfg['max_loss_per_trade'])} "
            f"a trade, {cfg['max_trades_per_day']} trades a day, stop for the day at -{rs(cfg['daily_loss_limit'])}. "
            "Charges: brokerage, STT, exchange, SEBI, stamp duty, GST and 0.05% slippage each way.",
            "",
            "| | Every signal | What you could actually take |",
            "|---|---|---|",
        ]
        rows = [("Trades", "trades", "{:,}"), ("Trading days", "days", "{:,}"), ("Hit target", "target", "pct"),
                ("Hit stop", "stop", "pct"), ("Out at the flat-by time", "flat", "pct"), ("Won after charges", "win_rate", "pct"),
                ("Average R before charges", "avg_r_gross", "{:+.2f}"), ("Average R after charges", "avg_r_net", "{:+.2f}"),
                ("Profit before charges", "pnl_gross", "rs"), ("Charges and slippage", "charges", "rs"),
                ("Profit after charges", "pnl_net", "rs"), ("Profit factor", "profit_factor", "{:.2f}"),
                ("Worst drop from a peak", "max_drawdown", "rs"), ("Green days", "green_days", "pct"),
                ("Worst day", "worst_day", "rs"), ("Best day", "best_day", "rs")]
        for label, key, fmt in rows:
            cell = lambda m: ("n/a" if key not in m else pc(m[key]) if fmt == "pct" else rs(m[key]) if fmt == "rs"  # noqa: E731
                              else fmt.format(m[key]))
            lines.append(f"| {label} | {cell(every)} | {cell(real)} |")
        net = real.get("pnl_net", 0)
        lines += ["", "## Verdict", "",
                  (f"Taken with discipline, the rule made {rs(net)} after charges over {real['days']} trading days. "
                   if net > 0 else f"Taken with discipline, the rule lost {rs(-net)} after charges over {real['days']} trading days. ")
                  + ("Charges took more than the rule earned before costs. "
                     if every.get("pnl_gross", 0) > 0 and every.get("pnl_net", 0) <= 0 else "")
                  + "About 60 trading days is a short test, so even a positive result is weak evidence; paper trade "
                    "it for a few weeks first. Yahoo's free 5-minute data can have gaps or lag.", ""]
    report = "\n".join(lines) + "\n"
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "daytrade-report.md").write_text(report)
    (out / "daytrade-trades.json").write_text(json.dumps(taken, indent=1))
    print(report)


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(prog="daytrader", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--state", default="trader", help="folder for config, plans and the journal (default ./trader)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p, when=True, rule=True):
        if when:
            p.add_argument("--date", help="YYYY-MM-DD, to replay a past day")
            p.add_argument("--at", help="HH:MM IST, pin the clock")
        if rule:
            p.add_argument("--target-r", type=float, dest="target_r")
            p.add_argument("--stop-rule", choices=["or-mid", "or-far"], dest="stop_rule")
            p.add_argument("--flat-by", dest="flat_by")
            p.add_argument("--last-entry", dest="last_entry")
            p.add_argument("--shorts", action="store_true", help="also short breakdowns below the range")
        return p

    p = common(sub.add_parser("scan"))
    p.add_argument("--universe", default="midsmall400", choices=list(nse.INDEX_FILES))
    p.add_argument("--min-traded-cr", type=float, default=10, dest="min_traded_cr")
    p.add_argument("--top", type=int, default=20)
    p.add_argument("--write", action="store_true")
    p = common(sub.add_parser("plan"))
    p.add_argument("--symbol", required=True)
    p = sub.add_parser("timemachine")
    p.add_argument("--replays", type=int, default=10_000)
    p.add_argument("--seed", type=int, default=19)
    p.add_argument("--write", action="store_true")
    sub.add_parser("size")
    p = common(sub.add_parser("risk"), rule=False)
    p.add_argument("--record", action="store_true")
    p = sub.add_parser("close")
    p.add_argument("--symbol", required=True)
    p.add_argument("--price", type=float, required=True)
    p = common(sub.add_parser("backtest"), when=False)
    p.add_argument("--universe", default="midsmall400", choices=list(nse.INDEX_FILES))
    p.add_argument("--min-traded-cr", type=float, default=10, dest="min_traded_cr")
    p.add_argument("--out", default="trader/report")

    a = ap.parse_args(argv)
    state = Path(a.state)
    cfg = money.load_config(state)
    {"scan": cmd_scan, "plan": cmd_plan, "timemachine": cmd_timemachine, "size": cmd_size, "risk": cmd_risk,
     "close": cmd_close, "backtest": cmd_backtest}[a.cmd](a, cfg, state)


if __name__ == "__main__":
    main()
