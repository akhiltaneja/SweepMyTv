# Solar and weather sky background

Introduced in 1.2.4-base-f1.7 / code 44, this replaces the fixed local-clock day/night palette
with three-stop native sky gradients. The original Talkies layout, F1, portrait
recommendation rows and Continue Watching options remain.

Code 46 / 1.2.4-base-f1.9 retains dark brightness while making daylight visibly warm.
The sequence runs through twilight, muted rose dawn, warm morning, golden/ochre solar
noon, amber and burnt-orange afternoon, warm sunset, dusk and deep blue night.
Daytime and nighttime both keep light text on dark backgrounds. Dock, menus
and controls use dark surfaces tinted by the current sky, replacing the
previous cream panels. Daylight uses warm brown/amber undertones rather than navy,
including the focus accent; cool blues return after sunset. App artwork and logos retain their original colours. Solar events position
the sequence using the selected city's date and timezone. The palette is
interpolated and refreshed each minute and on returning Home, so hourly changes
do not arrive as abrupt switches. Hourly cloud cover, precipitation and weather
codes soften or cool the sky. Text, focus accents, dock and menus follow the
palette. Every phase remains dark enough for light text; there is no white background
veil or day/night text crossover. A chosen photo
wallpaper still takes priority. No film banner, sharpening or image processing
is introduced.

## Location and weather

Control Center → Talkies settings → Keys and location:

- **Weather location:** optional city override. Delhi, India is the requested
  setting for this TV. Removing the override permits automatic city detection.
- **Automatic city:** On by default for new installations; can be switched Off.
  A manual city always takes priority. The screen explains IP approximation.
- Automatic detection uses the anonymous HTTPS ipapi.is endpoint, caches only
  city, timezone context and coordinates rounded to two decimals, and makes at
  most one lookup attempt per 24 hours per installation. The IP response itself
  is not stored. A timezone offset conflict over one hour is rejected. This
  heuristic cannot identify all VPNs or incorrect ISP geolocation.
- No GPS or Android location API is used by this feature and no Android
  permission is added. The original launcher's separate Wi-Fi setup location
  permission is unchanged.
- Open-Meteo geocoding resolves manual cities; its forecast provides daily
  sunrise/sunset and hourly/current cloud cover, precipitation and weather code.
  Weather refreshes at most every 30 minutes, with failed requests throttled.
  Current temperatures retain the original nearby airport/METAR fallback.
- A valid cached forecast works offline. Weather older than 48 hours is not
  applied; solar events are used only when the forecast contains the actual
  date. Without usable data, a nominal 06:00–18:00 sky follows the device's local
  clock. This is explicitly a fallback, not a measured sunrise/sunset estimate.
- Control Center → Weather shows the city, sunrise/sunset, sky phase and whether
  hourly weather or the local-clock fallback is in use. The TV clock is not changed.

For a public product, a shared backend should cache IP city lookups and weather
requests to handle shared-network quotas. Review the weather provider's
commercial plan before distributing a commercial service. No backend, paid
account, subscription or public deployment was created here.

## References

Horizon inspired the solar-gradient direction; no source or assets were copied,
and no browser renderer or dependency was added:
https://github.com/dnlzro/horizon

Weather variables and solar times: https://open-meteo.com/en/docs
Anonymous IP city response and limits: https://ipapi.is/free-tier.html

Automated checks cover every minute of a day for continuity/readability,
solar-time anchoring, Delhi timezone parsing and date rollover, cloud/rain
interpolation, stale/missing data, IP timezone conflicts and manual overrides.
Actual TV installation and verification status is recorded separately in
`dist/sky-gradient-validation.json`; a successful local build alone does not
prove the TV update is installed.
