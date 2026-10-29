"""What one intraday (MIS) round trip on NSE equity really costs.

Default rates are the published ones for a discount broker as of 2025 (Zerodha-style
brokerage: Rs 20 or 0.03% per executed order, whichever is lower). Rates change: check your
broker's charges page and override any of them in trader/config.json under "costs".
Slippage is not a fee, but it is real money: small caps rarely fill exactly at the signal.
"""
from __future__ import annotations

from dataclasses import dataclass, fields


@dataclass(frozen=True)
class Rates:
    brokerage_pct: float = 0.0003  # 0.03% of order value...
    brokerage_cap: float = 20.0  # ...or Rs 20 per executed order, whichever is lower
    stt_sell_pct: float = 0.00025  # STT 0.025%, sell side only (intraday)
    exchange_pct: float = 0.0000297  # NSE transaction charge, both sides
    sebi_pct: float = 0.000001  # SEBI fee, Rs 10 per crore, both sides
    stamp_buy_pct: float = 0.00003  # stamp duty 0.003%, buy side only (intraday)
    gst_pct: float = 0.18  # on brokerage + exchange charge + SEBI fee
    slippage_pct: float = 0.0005  # 0.05% each way, assumed

    @classmethod
    def from_config(cls, cfg: dict | None) -> "Rates":
        known = {f.name for f in fields(cls)}
        return cls(**{k: float(v) for k, v in (cfg or {}).items() if k in known})


def round_trip(qty: int, buy_price: float, sell_price: float, r: Rates = Rates()) -> dict:
    """Every charge on buying qty at buy_price and selling at sell_price (either order)."""
    buy, sell = qty * buy_price, qty * sell_price
    brokerage = min(r.brokerage_cap, buy * r.brokerage_pct) + min(r.brokerage_cap, sell * r.brokerage_pct)
    stt = sell * r.stt_sell_pct
    exchange = (buy + sell) * r.exchange_pct
    sebi = (buy + sell) * r.sebi_pct
    stamp = buy * r.stamp_buy_pct
    gst = (brokerage + exchange + sebi) * r.gst_pct
    slippage = (buy + sell) * r.slippage_pct
    fees = brokerage + stt + exchange + sebi + stamp + gst
    return {"brokerage": brokerage, "stt": stt, "exchange": exchange, "sebi": sebi, "stamp": stamp,
            "gst": gst, "fees": fees, "slippage": slippage, "total": fees + slippage}


def per_share(qty: int, entry: float, exit_: float, side: str, r: Rates = Rates()) -> float:
    """Total cost of the round trip divided by the shares, in rupees per share."""
    if qty <= 0:
        return 0.0
    buy, sell = (entry, exit_) if side == "long" else (exit_, entry)
    return round_trip(qty, buy, sell, r)["total"] / qty
