---
name: nse-size
description: >-
  NSE Budget Manager. Sizes the trade in rupees so the loss at the stop, including every
  Indian charge (brokerage, STT, exchange fee, SEBI fee, stamp duty, GST) and slippage,
  stays inside the max loss per trade, and prints a plain order ticket. Use when the user
  says "how many shares", "size it", "position size", "what will it cost", or after
  /nse-timemachine. Hands off to /nse-risk.
---

# nse-size

```bash
PYTHONPATH=daytrader python3 -m daytrader size
```

Limits come from `trader/config.json` (defaults: capital Rs 1,50,000, max loss Rs 1,500 a
trade, daily limit Rs 3,000, 3 trades a day, no leverage). If the file doesn't exist and the
user gives other numbers, write it, for example:

```json
{"capital": 25000, "max_loss_per_trade": 250, "daily_loss_limit": 500, "max_trades_per_day": 2}
```

Show the ticket as printed. Point out the charges line: on small trades it eats a large part
of the profit. Leverage (MIS margin) stays at 1 unless the user asks; if they do, say it
multiplies losses exactly as much as gains. Then run /nse-risk.
