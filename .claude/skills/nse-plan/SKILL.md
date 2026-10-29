---
name: nse-plan
description: >-
  NSE Exit Planner. Turns one opening-range breakout into a written plan before any money
  moves: entry (the breakout close), stop-loss (range midpoint, or the far side), target
  (1.5R by default), and out by 15:15 IST either way, before the broker's own intraday
  square-off. Use when the user says "plan it", "set my stop", "where's the target",
  "plan SYMBOL", or after /nse-scanner. Hands off to /nse-timemachine.
argument-hint: "SYMBOL [--target-r 2] [--stop-rule or-far]"
---

# nse-plan

```bash
PYTHONPATH=daytrader python3 -m daytrader plan --symbol SYMBOL                           # today, now
PYTHONPATH=daytrader python3 -m daytrader plan --symbol SYMBOL --target-r 2              # a 2R target
PYTHONPATH=daytrader python3 -m daytrader plan --symbol SYMBOL --stop-rule or-far        # stop at the far side of the range
PYTHONPATH=daytrader python3 -m daytrader plan --symbol SYMBOL --date 2026-10-20 --at 10:15
```

Writes `trader/plan.json`. Read the printed plan back to the user in words: entry, stop,
risk per share, target, and the 15:15 exit. A 1R target rarely pays in India: charges
and slippage often turn it into ~0.7R. Then run /nse-timemachine.
