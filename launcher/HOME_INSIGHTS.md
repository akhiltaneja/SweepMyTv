# At a glance: air, news and local holidays

Build 58 / 1.3.0-sweep.12 adds one compact row below the dock and before F1.
It follows the supplied reference with a small air card, wider news card and
festival/holiday countdown. The existing launcher, avatars, film-language
filters, Continue Watching and F1 remain in place.

The cards use native Canvas views, three small opaque gradient surfaces and no
backdrop bitmap, blur, WebView or third-party runtime library. Data, RSS/ICS parsing
and channel signatures run on workers; the existing navigation-idle update policy
and view reuse remain active. The existing minute callback advances dated cards;
there are no extra per-second card timers. Source requests occur only while Home
is active; a started refresh can finish and cache its result after leaving Home.

## Location and air

Weather's city override takes priority. Otherwise its optional approximate IP city
is reused. No Android location permission is requested. City, country, administrative
region, timezone and rounded coordinates are used; no IP address or account identity
is retained by the widget. The existing IP parser now accepts the provider's nested
location object, so it can preserve country and region rather than inferring all
users' calendars from a language setting. A location change immediately excludes
old-city air readings and calendars. Manually entered coordinates use the TV's
country/timezone as a coarse fallback when no city metadata is available.

AQI.in rejected automated access during development; no token scraping or access
workaround was integrated. The user approved a labelled Open-Meteo/CAMS fallback.
The air card explicitly says **US AQI · model estimate**, with Open-Meteo/CAMS
attribution. This is not an AQI.in reading, Indian AQI, or a nearby station sensor.
The value uses the returned US AQI bands and a timestamp in the returned timezone.
Values older than twelve hours, invalid values and future timestamps are not shown.
Old/error-preserved estimates say Cached. Details show estimate time and PM2.5;
a QR code opens AQI.in on the user's phone for comparison.

Source: https://open-meteo.com/en/docs/air-quality-api
Dataset attribution: Open-Meteo / Copernicus Atmosphere Monitoring Service (CAMS).

## Holidays and festivals

Office Holidays' public iCalendar subscription follows the resolved country and,
where available, the state/region. Delhi uses india/delhi; US, Canadian and Australian
regions are also attempted. Country calendars are the fallback; unrelated regional
events are filtered out. There is no hardcoded India calendar for TVs elsewhere.
Unsupported calendars show unavailable, rather than a different country's events.
All-day dates and countdowns use the location's timezone and calendar date, handling
DST and midnight without 24-hour arithmetic. Duplicate, past and unrelated regional
events are removed. Upcoming holidays include major festivals as well as public
holidays; bank account-closing and election/working-day entries are excluded.
The public calendar is refreshed every 48 hours. Dates can change with the publisher
or local observance. The details view lists the next entries and links to the source.

Sources:
- https://www.officeholidays.com/subscribe
- https://www.officeholidays.com/subscribe/india/delhi

## US AI, technology and market headlines

Only public RSS/Atom titles, publisher names, dates and links are retained. No
article body, downloaded news image, publisher login or paywall workaround is used.
Feeds: WSJ Technology and Markets, CNN Latest (topic-filtered), CNBC Technology and
Markets, and TechCrunch AI. Headlines prioritize US companies/markets and AI from
US publisher feeds. The film/series language preference does not filter this
explicitly requested English news card. The visible headline changes every two
minutes from the current cache; OK opens the dated list. QR codes lead to the
publisher on the user's phone, where its normal access requirements apply.

Dates must be valid and no more than seven days old or fifteen minutes ahead.
Duplicate titles are removed. Item links must use HTTPS and match the corresponding
publisher's domain; XML external entities/DOCTYPEs and oversized responses are
rejected. Each source has its own status. A failed feed retains only its own dated,
still-valid cached headlines; another publisher is never labelled as that source.
CNN failed TLS checks during development; it remains optional and is retried, with
an honest unavailable status. Actual TV source status is recorded in validation.

Primary feed URLs:
- https://feeds.content.dowjones.io/public/rss/RSSWSJD
- https://feeds.content.dowjones.io/public/rss/RSSMarketsMain
- https://rss.cnn.com/rss/cnn_latest.rss
- https://www.cnbc.com/id/19854910/device/rss/rss.html
- https://www.cnbc.com/id/100003114/device/rss/rss.html
- https://techcrunch.com/category/artificial-intelligence/feed/

## Controls and preservation

Settings → Status widget → AQI, festivals and news controls the whole row, individual
cards and sources. The existing location screen supplies the city override. The
whole section can also be hidden/restored in Home sections or by long-pressing a
card. TV apps, cleanup state, remote mappings, accessibility and permissions are
not changed. Validation: dist/home-insights-validation.json.

Build 59 / 1.3.0-sweep.13, 4 November 2026: TV validation found an unrelated
political headline in the broad WSJ feed. The filter now requires a matching
AI, technology or market topic for every source, and distinguishes uppercase US
or U.S. from the pronoun "us". The same policy filters existing cached stories
before rendering, so a cached false match cannot persist after updating.

Build 60 / 1.3.0-sweep.14 moves the cards into a 46dp-high header strip beside
the content tabs. AQI is a fixed 112dp tile; news is 286dp and the festival is
142dp. Individual remaining cards keep those compact sizes. The large below-dock
section, its heading and duplicate captions are removed. Media cards and dock
geometry remain at their existing positions. The strip has a separate remote-focus
zone, Up/Down navigation, OK details and long-press hide/settings actions. A clearer
type hierarchy, AQI status accent, newspaper badge and calendar date badge replace
the sparse oversized panels. Estimates and full source information remain in details.

Build 61 / 1.3.0-sweep.15: actual-TV review found the old discovery credit
caption behind the festival tile. Hide that duplicate caption while the header
widgets are visible; the logo and full TMDB attribution remain in About.

Build 62 / 1.3.0-sweep.16 replaces the three-card strip with a single 196 × 36dp
rotating tile beside the top-right control buttons. AQI, news and the next festival
rotate every 12 seconds, skipping disabled cards. No new data fetching is added.
Rotation stops while Home is backgrounded, pauses while the tile or a menu/dialog
is selected, and resumes on return. OK opens the currently displayed card. Right
reaches Control Center; Left from Control Center returns to the tile. Down goes to
the content tabs or dock. The separate row beside the content tabs is gone.

The AQI face is a smaller 96 × 32dp badge; news uses 196 × 36dp and the
calendar uses 148 × 36dp. All faces share a fixed right edge within one reserved
header slot, so control buttons never shift while the content rotates.
