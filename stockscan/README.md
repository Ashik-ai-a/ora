# Small-cap momentum scan

A mechanical screen of the Nifty MidSmallcap 400 (mid and small caps only), tested on ~12 years of prices before it
tells you anything. It runs on GitHub every weekday after the market closes and publishes
`report.md` to the **stockscan** release of this repository.

## The rules

Once a month, rank every stock by 6- and 12-month returns (skipping the latest month),
divided by volatility, and hold the top 10 in equal amounts. A stock only qualifies if it:

- trades above its 200-day average,
- trades at least Rs 5 crore a day (you can get in and out),
- did not jump more than 60% in the last month (pumps are skipped, not chased).

When small caps as a group are below their 200-day average, hold cash instead.
Backtest costs: 0.25% on every buy and sell; trades at the next day's close.

## What it does not do

- It does not promise profit. The backtest uses today's index list, which leaves out
  companies that collapsed, so it looks better than real life.
- It does not read company accounts or news. Check results and announcements on NSE/BSE
  before buying.

## Phone alerts

1. Install **ntfy** (free, Android and iPhone) and subscribe to a long random topic name.
2. In the GitHub repository: Settings → Secrets and variables → Actions → New secret
   `NTFY_TOPIC` = that name.

You get the buy list once a month, and an alert the day the rules switch to or from cash.

## Run it yourself

    pip install -r requirements.txt
    python -m stockscan --capital 150000
