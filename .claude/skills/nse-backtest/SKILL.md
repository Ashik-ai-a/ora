---
name: nse-backtest
description: >-
  NSE proof run. Tests the opening-range breakout rule on every stock in an NSE universe over
  the ~60 trading days of free 5-minute data, after all Indian charges, with the user's real
  limits (trades per day, daily loss limit), and says whether it made or lost money. Use
  when the user asks "does this work", "has it made money", "backtest", "prove it",
  "should I day trade this", or before trading the strategy for real.
argument-hint: "[universe] [--target-r 2] [--shorts]"
---

# nse-backtest

```bash
PYTHONPATH=daytrader python3 -m daytrader backtest                                   # Smallcap 250, default rule (1.5R)
PYTHONPATH=daytrader python3 -m daytrader backtest --target-r 2 --stop-rule or-far   # try a variant
PYTHONPATH=daytrader python3 -m daytrader backtest --universe nifty500 --shorts
```

It downloads ~60 days of 5-minute bars for every stock (a few minutes), then writes
`trader/report/daytrade-report.md`. GitHub runs it every Saturday too (the "Day trade proof"
workflow) and publishes the report on the "daytrader" release.

Report the "What you could actually take" column first: profit after charges, the share
of winning trades, the worst drop and the worst day. If profit after charges is negative,
say clearly that the rule lost money and the user should not trade it. If it is positive,
say 60 days is weak evidence and suggest paper trading it for a month before real money.
Do not try parameter after parameter until one looks good: that finds luck, not an edge.
