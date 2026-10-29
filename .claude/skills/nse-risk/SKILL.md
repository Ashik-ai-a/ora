---
name: nse-risk
description: >-
  NSE Risk Manager and trade journal. Checks the sized order against every limit: max loss
  per trade, daily loss limit (realised plus open risk), trades per day, reward to risk
  after charges, the 9:30-14:30 IST entry window, and capital. PASS or BLOCK with the
  reason. Records taken trades and their results so the daily limit holds. Use when the user
  says "check it", "can I take this", "risk check", "I took it", "I'm out at", "close the
  trade", or after /nse-size.
---

# nse-risk

```bash
PYTHONPATH=daytrader python3 -m daytrader risk                         # PASS / BLOCK, nothing recorded
PYTHONPATH=daytrader python3 -m daytrader risk --record                # on PASS, log it as taken (only after the user says they placed it)
PYTHONPATH=daytrader python3 -m daytrader close --symbol SYMBOL --price 412.35     # how it ended, after charges, and today's total
```

## Rules for Claude

- A BLOCK is final for this trade. Do not suggest changing the limits to get a PASS.
- This pack never places orders. The user places the order in their own broker app
  (Zerodha, Groww, Upstox, Angel...). Give them the ticket: product MIS, the quantity,
  a stop-loss order at the stop, and the 15:15 exit.
- Use `--record` only after the user confirms they placed it, and `close` when they're out.
- After the daily loss limit is hit, the day is over, whatever the scanner shows.
