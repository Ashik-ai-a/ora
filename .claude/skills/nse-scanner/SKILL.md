---
name: nse-scanner
description: >-
  NSE Chart Scanner. Finds opening-range breakouts on Indian stocks right now: the first
  5-minute close above (or, with --shorts, below) the 9:15-9:30 IST range, on the Nifty
  Smallcap 250 by default, only stocks trading at least Rs 10 crore a day, ranked by
  opening volume against their usual. Use when the user says "scan", "scan small caps",
  "any breakouts", "what's moving", "intraday setups", "ORB", or at the start of an Indian
  trading day. Hands off to /nse-plan.
argument-hint: "[universe: smallcap250 | midcap150 | nifty500 | nifty100]"
---

# nse-scanner

Run from the repository root:

```bash
PYTHONPATH=daytrader python3 -m daytrader scan --write                                   # Smallcap 250, now
PYTHONPATH=daytrader python3 -m daytrader scan --universe nifty500 --write               # a wider list
PYTHONPATH=daytrader python3 -m daytrader scan --date 2026-10-20 --at 10:15              # replay a past day at a time (last ~60 days)
PYTHONPATH=daytrader python3 -m daytrader scan --shorts --min-traded-cr 25               # also breakdowns; only very liquid stocks
```

Data: Yahoo Finance 5-minute bars (free, unofficial, can lag a few minutes) and the index
list from niftyindices.com. Only closed bars count: a bar still forming is never used.

## Steps

1. If it is before 9:30 IST, say the range isn't set yet and offer a replay of yesterday.
2. Run the scan with `--write`. Show the table as printed, top first, with the time each
   broke out. Mention the "near the top of the range" watch list if there is one.
3. Say plainly: a breakout is a setup, not a reason to trade. Most ORB trades on small caps
   are close to a coin flip once charges are paid; /nse-backtest shows the real record.
4. Offer `/nse-plan SYMBOL` for the one the user picks. Never pick for them.
