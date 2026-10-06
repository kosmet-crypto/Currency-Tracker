# Currency-Tracker

Android апликација „Курсеви“:

- **Конвертор**: куцаш износ у EUR, USD, NOK или RSD, остале валуте се одмах прерачунају.
- **Виџет**: EUR/NOK, EUR/USD и USD/NOK са курсом, променом за 24 сата, графиком и дугметом за ажурирање.

## Извори
- [Twelve Data](https://twelvedata.com) за курсеве уживо (EUR, USD, NOK), на сваких 15 минута за последња 24 сата.
- [open.er-api.com](https://open.er-api.com) за динар, једном дневно.

## APK
GitHub Actions гради APK на сваки push (**Actions → Build APK → Artifacts → CurrencyTracker-apk**).
API кључ се узима из тајне `TWELVEDATA_API_KEY`. Може се унети и у самој апликацији, на дну екрана.
