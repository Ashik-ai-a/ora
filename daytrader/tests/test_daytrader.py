"""Offline tests on hand-built NSE sessions (IST, 75 five-minute bars a day)."""
import io
import json
import random
import time
import unittest
from contextlib import redirect_stdout
from datetime import date, datetime, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory

from daytrader import backtest, costs, money, nse, timemachine
from daytrader.__main__ import main
from daytrader.rules import Strategy, outcome, signal

IST_OFFSET = 19800


def day_bars(d: date, path: list[float], vol: float = 10_000) -> list[nse.Bar]:
    """One bar per close in [path]; each bar opens at the previous close, wicks 0.1% each way."""
    bars, prev = [], path[0]
    for i, c in enumerate(path):
        o = prev
        bars.append(nse.Bar(nse.slot_time(d, i), o, max(o, c) * 1.001, min(o, c) * 0.999, c, vol))
        prev = c
    return bars


def flat_day(d: date, price: float = 100.0, drift: float = 0.0, seed: int = 0) -> list[nse.Bar]:
    rng = random.Random(seed)
    p, path = price, []
    for _ in range(nse.BARS_PER_DAY):
        p *= 1 + drift + rng.uniform(-0.001, 0.001)
        path.append(p)
    return day_bars(d, path)


def breakout_day(d: date, then: str) -> list[nse.Bar]:
    """Range 99.5-100.5 for 15 min, breaks out at the 9:35 close, then goes to target or stop."""
    path = [100.0, 100.3, 99.8, 100.9]  # bars 0-2 = range, bar 3 closes above 100.5ish
    path += [101.5, 102.5, 103.5] if then == "target" else [100.4, 99.9, 99.6]
    path += [path[-1]] * (nse.BARS_PER_DAY - len(path))
    return day_bars(d, path)


def chart_json(bars: list[nse.Bar]) -> dict:
    ts = [int((b.start - datetime(1970, 1, 1)).total_seconds()) - IST_OFFSET for b in bars]
    q = {k: [getattr(b, k) for b in bars] for k in ("open", "high", "low", "close", "volume")}
    return {"chart": {"result": [{"timestamp": ts, "indicators": {"quote": [q]}}]}}


class Rules(unittest.TestCase):
    D = date(2026, 10, 20)

    def test_session_shape(self):
        bars = flat_day(self.D)
        self.assertEqual(nse.slot_time(self.D, 74).strftime("%H:%M"), "15:25")
        self.assertTrue(nse.is_full(bars))
        self.assertEqual(Strategy().flat_index, 71)  # the 15:10 bar ends at 15:15

    def test_parse_chart_round_trip_in_ist(self):
        bars = flat_day(self.D)
        parsed = nse.parse_chart(json.dumps(chart_json(bars)).encode())
        self.assertEqual(parsed[0].start, datetime(2026, 10, 20, 9, 15))
        self.assertEqual(len(nse.sessions(parsed)[self.D]), 75)

    def test_breakout_signal_and_plan(self):
        p = signal("ABC", breakout_day(self.D, "target"), Strategy())
        self.assertEqual((p.side, p.entry_bar, p.entry_time), ("long", 3, "09:35"))
        self.assertAlmostEqual(p.entry, 100.9)
        self.assertLess(p.stop, p.entry)
        self.assertAlmostEqual(p.target - p.entry, 1.5 * (p.entry - p.stop), delta=0.051)

    def test_forming_bar_is_never_used(self):
        bars = breakout_day(self.D, "target")
        early = nse.closed_bars(bars, datetime(2026, 10, 20, 9, 33))  # the 9:30 bar is still forming
        self.assertIsNone(signal("ABC", early, Strategy()))
        self.assertIsNotNone(signal("ABC", nse.closed_bars(bars, datetime(2026, 10, 20, 9, 35)), Strategy()))

    def test_outcomes(self):
        s = Strategy()
        win = breakout_day(self.D, "target")
        self.assertEqual(outcome(win, signal("A", win, s), s.flat_index)["result"], "target")
        lose = breakout_day(self.D, "stop")
        res = outcome(lose, signal("A", lose, s), s.flat_index)
        self.assertEqual(res["result"], "stop")
        self.assertLessEqual(res["r"], -1.0)
        quiet = flat_day(self.D)
        self.assertIsNone(signal("A", quiet, Strategy(max_or_pct=0.0001)))

    def test_gap_through_stop_fills_worse(self):
        bars = breakout_day(self.D, "target")
        plan = signal("A", bars, Strategy())
        gap = bars[:4] + [nse.Bar(bars[4].start, 95.0, 95.5, 94.0, 95.0, 1)] + bars[5:]
        res = outcome(gap, plan, Strategy().flat_index)
        self.assertEqual((res["result"], res["exit"]), ("stop", 95.0))


class Money(unittest.TestCase):
    def test_one_r_after_charges_is_less_than_one(self):
        plan = signal("A", breakout_day(date(2026, 10, 20), "target"), Strategy(target_r=1.0))
        order = money.size(plan, money.load_config(Path("/nonexistent")))
        self.assertLess(order["reward_risk_after_costs"], 0.8)
        checks = dict((n, ok) for n, ok, _ in money.check(order, money.load_config(Path("/x")), [], datetime(2026, 10, 20, 9, 40)))
        self.assertFalse(checks["reward to risk"])

    def test_charges_match_a_hand_count(self):
        c = costs.round_trip(100, 500.0, 510.0, costs.Rates(slippage_pct=0))
        self.assertAlmostEqual(c["brokerage"], 15.0 + 15.3)  # 0.03% each side, under Rs 20
        self.assertAlmostEqual(c["stt"], 51000 * 0.00025)
        self.assertAlmostEqual(c["stamp"], 50000 * 0.00003)
        self.assertAlmostEqual(c["gst"], (30.3 + 101000 * (0.0000297 + 0.000001)) * 0.18)
        self.assertGreater(costs.round_trip(1000, 500, 510)["brokerage"], 39.99)  # capped at Rs 20 x 2

    def test_size_keeps_the_all_in_loss_under_the_limit(self):
        plan = signal("A", breakout_day(date(2026, 10, 20), "target"), Strategy())
        cfg = money.load_config(Path("/nonexistent"))
        order = money.size(plan, cfg)
        self.assertGreater(order["qty"], 0)
        self.assertLessEqual(-order["loss_at_stop"], cfg["max_loss_per_trade"])
        self.assertLessEqual(order["value"], cfg["capital"])
        self.assertLess(order["profit_at_target"], order["qty"] * (plan.target - plan.entry))  # charges come off

    def test_risk_blocks_after_the_daily_limit(self):
        plan = signal("A", breakout_day(date(2026, 10, 20), "target"), Strategy(target_r=2.0))
        cfg = money.load_config(Path("/nonexistent"))
        order = money.size(plan, cfg)
        at = datetime(2026, 10, 20, 9, 40)
        self.assertTrue(all(ok for _, ok, _ in money.check(order, cfg, [], at)))
        bad_day = [{"day": "2026-10-20", "status": "closed", "pnl": -2900}]
        checks = dict((n, ok) for n, ok, _ in money.check(order, cfg, bad_day, at))
        self.assertFalse(checks["daily loss limit"])
        late = dict((n, ok) for n, ok, _ in money.check(order, cfg, [], datetime(2026, 10, 20, 15, 0)))
        self.assertFalse(late["entry window"])


class Machine(unittest.TestCase):
    def setUp(self):
        start = date(2026, 7, 1)
        self.sessions = {}
        d, k = start, 0
        while len(self.sessions) < 40:
            if d.weekday() < 5:
                self.sessions[d] = breakout_day(d, "target" if k % 2 else "stop")
                k += 1
            d += timedelta(days=1)
        self.today = d
        self.plan = signal("A", breakout_day(d, "target"), Strategy())

    def test_replays_are_repeatable_and_add_up(self):
        pool = timemachine.ratio_pool(self.sessions, self.today)
        a = timemachine.replay(self.plan, pool, Strategy().flat_index, 2000, seed=19)
        b = timemachine.replay(self.plan, pool, Strategy().flat_index, 2000, seed=19)
        self.assertEqual(a, b)
        self.assertAlmostEqual(a["target"] + a["stop"] + a["neither"], 1.0)

    def test_history_sees_both_outcomes(self):
        hist = timemachine.history("A", self.sessions, self.today, Strategy())
        self.assertEqual(len(hist), 40)
        self.assertEqual(sum(h["result"] == "target" for h in hist), 20)

    def test_verdict_counts_costs(self):
        self.assertIn("coin flip", timemachine.verdict(0.40, 0.40, 1.0, 0.0))
        self.assertIn("toward the stop", timemachine.verdict(0.40, 0.40, 1.0, 0.25))  # needs 62.5%, gets 50%

    def test_portfolio_caps_trades_and_stops_at_daily_limit(self):
        cfg = money.load_config(Path("/nonexistent"))
        mk = lambda t, pnl: {"day": "2026-10-20", "time": t, "rel_volume": 1, "pnl_net": pnl}  # noqa: E731
        taken = backtest.portfolio([mk("09:35", -1600), mk("09:40", -1600), mk("09:45", 900), mk("09:50", 900)], cfg)
        self.assertEqual(len(taken), 2)  # the second loss hits -3,200, past the -3,000 limit


class Commands(unittest.TestCase):
    def test_scan_plan_timemachine_size_risk_offline(self):
        with TemporaryDirectory() as tmp:
            state = Path(tmp)
            cache = state / "cache"
            cache.mkdir()
            (cache / nse.INDEX_FILES["midsmall400"]).write_text("Company Name,Industry,Symbol\nAbc Ltd,Test,ABC\nQuiet Ltd,Test,QUIET\n")
            today = date(2026, 10, 20)
            days, d = [], today
            while len(days) < 30:
                d -= timedelta(days=1)
                if d.weekday() < 5:
                    days.append(d)
            abc = [b for x in sorted(days) for b in breakout_day(x, "target" if x.day % 2 else "stop")]
            abc += breakout_day(today, "target")
            quiet = [b for x in sorted(days) + [today] for b in flat_day(x)]
            for sym, bars in (("ABC", abc), ("QUIET", quiet)):
                for n in (5, 60):
                    (cache / f"{sym}-{n}d-5m.json").write_text(json.dumps(chart_json(bars)))
            now = time.time()
            for f in cache.iterdir():
                f.touch()
            run = lambda *args: self._run(["--state", str(state), *args])  # noqa: E731
            out = run("scan", "--date", "2026-10-20", "--at", "10:00", "--min-traded-cr", "0.01", "--write")
            self.assertIn("ABC", out)
            self.assertNotIn("QUIET   ", out)
            self.assertIn("09:35", run("plan", "--symbol", "ABC", "--date", "2026-10-20", "--at", "10:00", "--target-r", "2"))
            tm = run("timemachine", "--replays", "500")
            self.assertIn("History: the same rule fired on 30 of 30", tm)
            self.assertIn("Verdict:", tm)
            self.assertIn("BUY", run("size"))
            with self.assertRaises(SystemExit) as e:
                run("risk", "--date", "2026-10-20", "--at", "09:40", "--record")
            self.assertEqual(e.exception.code, 0)
            self.assertIn("after charges", run("close", "--symbol", "ABC", "--price", "101.5"))
            report = run("backtest", "--min-traded-cr", "0.01", "--target-r", "2", "--out", str(state / "report"))
            self.assertIn("| Trading days | 26 |", report)  # 31 days, the first 5 only build liquidity history
            self.assertIn("## Verdict", report)
            self.assertTrue((state / "report" / "daytrade-report.md").exists())
            self.assertLess(time.time() - now, 60)

    @staticmethod
    def _run(argv):
        buf = io.StringIO()
        with redirect_stdout(buf):
            try:
                main(argv)
            except SystemExit as e:
                if e.code not in (0, None):
                    raise
                if e.code == 0:
                    print(buf.getvalue(), end="")
                    raise
        return buf.getvalue()


if __name__ == "__main__":
    unittest.main()
