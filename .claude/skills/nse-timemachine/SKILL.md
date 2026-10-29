---
name: nse-timemachine
description: >-
  NSE Time Machine. Before risking a rupee, replays the rest of the session 10,000 times
  from the stock's own 5-minute bars over the last ~60 days (same time of day), and
  re-runs the same rule on every past session, then gives the odds of target first, stop
  first or neither by 15:15, the average R before and after Indian charges, and one honest
  verdict. Use when the user says "time machine", "replay", "what are the odds",
  "simulate", "is this a coin flip", or after /nse-plan. Hands off to /nse-size.
---

# nse-timemachine

```bash
PYTHONPATH=daytrader python3 -m daytrader timemachine --write              # trader/plan.json, 10,000 replays, seed 19
PYTHONPATH=daytrader python3 -m daytrader timemachine --replays 20000 --seed 7
```

Method from the MIT-licensed Day Trader skill (github.com/Jakeschincariol/day-trader-skill),
moved to NSE hours with charges included. It refuses with fewer than 20 full sessions.

## Steps

1. Run it. Report, in this order: the four odds, the exit-price range, the average R before
   and after charges, the history line, then the verdict exactly as printed. Say the seed.
2. If the verdict is a coin flip, close to break-even, or toward the stop, say so plainly
   and suggest skipping the trade. Do not dress it up.
3. Say once: built from the last ~60 days, not a prediction. Results, news, circuit limits
   and F&O ban changes can do what those days never did.
4. Only if the user still wants it, go on to /nse-size.
