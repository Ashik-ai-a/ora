# NSE day trader (Claude skills)

Six Claude skills for intraday trading on NSE, plus the plain Python behind them
(standard library only, Python 3.9+):

| skill | what it does |
| --- | --- |
| `/nse-scanner` | opening-range breakouts right now on the Nifty MidSmallcap 400 (mid and small caps only) |
| `/nse-plan` | entry, stop-loss, target and the 15:15 exit, written down before you trade |
| `/nse-timemachine` | 10,000 replays of the rest of the day + the real history, after charges |
| `/nse-size` | shares for your max loss, with every Indian charge, as an order ticket |
| `/nse-risk` | PASS / BLOCK against your limits; journal of trades taken |
| `/nse-backtest` | the rule on every stock over the last ~60 days: did it make money after charges? |

Open the repository in Claude Code and type the command, or run the tools yourself from the
repository root: `PYTHONPATH=daytrader python3 -m daytrader scan`.

Nothing here places orders. You place them in your own broker app.

**Honest limits.** Free Yahoo data, ~60 days of 5-minute bars, a few minutes' delay. One
strategy (opening-range breakout). Charges are a discount broker's 2025 rates; set yours in
`trader/config.json` under `"costs"`. A backtest is not a promise.

The Time Machine method and the scanner → plan → replay → size → risk flow come from the
MIT-licensed [Day Trader skill](https://github.com/Jakeschincariol/day-trader-skill) by
Jake Schincariol, rewritten for NSE hours, rupees and Indian charges.

Tests: `cd daytrader && python3 -m unittest discover -s tests`
