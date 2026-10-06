# Currency Tracker

Android app with a currency converter and a home-screen widget. English by default, Serbian when the phone is set to Serbian.

- **Converter**: type an amount in one currency and the others update instantly. Add or remove currencies (34 to choose from) and drag ≡ to reorder; the first one is the base, and each row shows its rate against it.
- **Widget**: 1–4 chosen pairs in either direction (⇄ swaps, e.g. USD/NOK ↔ NOK/USD), each with rate, change and chart. Shrinks to two rows. Period (1D, 7D, 1M, 1Y) is picked top-left; ⟳ refreshes.
- **Chart screen**: tap a pair in the widget or app; slide a finger across the chart to read the rate and time at that point.

## Data
- [Yahoo Finance](https://finance.yahoo.com) chart data (unofficial endpoint, no key) for pairs and live converter rates.
- [open.er-api.com](https://open.er-api.com) daily rates as fallback.

## APK
GitHub Actions builds the APK on every push (**Actions → Build APK → Artifacts → CurrencyTracker-apk**).
