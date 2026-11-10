# Third-party notices

The Control Center (`Sleep.java`, `SleepService.java`, `res/xml/sleep_service.xml`, `res/drawable/ic_cc.xml` and the Control Center code in `HomeActivity.java`) is adapted from
tv-vibehack's launcher, branch `pr/control-center` (https://github.com/samdegan2311/tv-vibehack/tree/pr/control-center), under this license:

```
MIT License

Copyright (c) 2026 Gathin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

The Outfit typeface in launcher/app/src/main/res/font/outfit.ttf is copyright
The Outfit Project Authors and licensed under the SIL Open Font License 1.1,
see launcher/FONT-LICENSE-OFL.txt.
```

(In this project the Outfit font is `app/src/main/res/font/outfit.ttf`, and its license is `OUTFIT-OFL.txt`.)

---

`Spring.java` (the damped spring behind the focus, scrolling and picks motion) and `Qr.java` (the QR code for Send
from your phone) are from Free My TV (https://github.com/simbasjungles/free-my-tv, `app/src/io/freemytv/Spring.java`
and `Qr.java`), and `PhonePage.java` is adapted from its `KeyPage.java`, all under
this license:

```
MIT License

Copyright (c) 2026 Free My TV contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

`SpeedTest.java` is adapted from Matinee (https://github.com/Jarvis322/matinee, `system/SpeedTest.kt`); the HDMI-CEC device names in Inputs and the Play Store update bell follow its approach. Under this license:

```
MIT License

Copyright (c) 2026 Yiğit Can Polat

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

`ServiceRepair.java` (putting the accessibility service back after a force stop) is adapted from CleanYourTV's
launcher (https://github.com/Gathin23/cleanyourtv, `launcher/app/src/main/java/com/gathin/tvhome/ServiceRepair.java`),
and the sleep timer's one-minute warning follows its Sleep Timer add-on; both under the MIT license above
(Copyright (c) 2026 Gathin; tv-vibehack was renamed CleanYourTV).

---

`Devices.java` (recognising what's plugged into an HDMI port from its HDMI-CEC name, vendor and type) is adapted from
Luma (https://github.com/0xProbabillity/luma-tv, `src/dev/luma/tv/Devices.java`); Now playing and the weather
forecast follow its widgets. Under this license:

```
MIT License

Copyright (c) 2026 0xProbability

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

`Tiles.java` (tiles for apps without a TV banner) is adapted from GlassLauncher
(https://github.com/tlfares/GlassLauncher, `gfx/TileFactory.kt`); Control Center's Animations and Storage rows follow
its System module. Under this license:

```
MIT License

Copyright (c) 2026 Fares

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

`Qr.java` follows Project Nayuki's QR Code generator library (https://www.nayuki.io/page/qr-code-generator-library),
cut down to byte mode, level M and versions 1 to 10. Copyright (c) Project Nayuki, MIT License; the full notice is at
the top of `Qr.java`.


---

The Sweep TV Formula 1 dashboard uses public schedules, race classifications and championship standings from Jolpica (https://api.jolpi.ca/), completed-session classifications and driver metadata from OpenF1 (https://openf1.org/), and best-effort session status from ESPN’s public scoreboard. OpenF1 is unofficial and its published free access is intended for personal, educational and non-commercial fan use. Commercial distribution requires a separate data-rights review. No affiliation with or endorsement by Formula 1, the FIA, teams, drivers or data providers is claimed. This personal preview does not connect to paid live timing or copy private F1-app account data.


F1 widget visuals in this personal preview: driver portraits, team logos, team car images, F1 mark and circuit outlines are sourced from Formula1.com’s public media assets and OpenF1 metadata. They remain the property of their respective rights holders and are not covered by the launcher’s MIT license. Provenance URLs are recorded in research/f1-2026-10-31/visual-assets.json. The F1 mark is used within the motorsport information widget, not as Sweep TV branding.


## DiceBear Thumbs avatars

The twelve bundled flat profile illustrations in assets/avatars are DiceBear Thumbs, by DiceBear, dedicated under CC0 1.0 Universal.
Style and provenance: https://www.dicebear.com/styles/thumbs/
License: https://creativecommons.org/publicdomain/zero/1.0/
Downloaded from the DiceBear 10.x API with fixed non-personal seeds Sweep01 through Sweep12 at 256 pixels. Avatars are bundled offline; no user identity is sent to DiceBear at runtime.


## Twemoji animal and character avatars

Animal and Little things profile illustrations use Twemoji graphics by Twitter, Inc. (X Corp.) and other contributors, licensed under Creative Commons Attribution 4.0 International (CC BY 4.0).
Project: https://github.com/jdecked/twemoji
License: https://creativecommons.org/licenses/by/4.0/
Full license text: TWEMOJI-CC-BY-4.0.txt in the bundled licences.
Pinned source revision: b6b55fef1e8636b540a6d016a4729ca8cdf2e60b
The 24 SVG illustrations were converted to native Android vector drawables and adapted with coloured circular backgrounds, padding and scaling. Character shapes and colours are preserved. Source filenames: 1f98a.svg, 1f431.svg, 1f43c.svg, 1f428.svg, 1f430.svg, 1f438.svg, 1f427.svg, 1f436.svg, 1f42f.svg, 1f989.svg, 1f43b.svg, 1f984.svg, 1f916.svg, 1f47d.svg, 1f47b.svg, 1f680.svg, 1f31f.svg, 1fa90.svg, 1f31e.svg, 1f31b.svg, 1f9c1.svg, 1f308.svg, 1f951.svg, 1f353.svg.
