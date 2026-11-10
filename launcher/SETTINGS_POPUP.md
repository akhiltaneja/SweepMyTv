# Settings popup, build 53

Sweep TV settings now use a centered native popup over a dimmed Home screen. The root shows the existing nine categories in a three-column grid, with category icons, short descriptions, a local profile badge and a remote-control footer. Submenus use the same surface with spacious setting cards, a clear focus border and visible On/Off or Shown/Hidden switch indicators.

The TV remote navigates the category grid in two dimensions. OK opens a category or changes the selected option; Back restores the previous category and its selection. Home dismisses the full overlay. Up/Down also scroll long settings and licence text. Standard TV Inputs and Control Center retain their compact menus.

All existing preference keys and actions are retained. Names come from the existing local profile. Data-provider credits and the Talkies/MIT source notices remain in About. This change does not modify TV services, permissions, cleanup, Home-key mapping or the paused Volume hold setup.

The popup uses a native Canvas surface, gradients and vector icons. It does not blur Home or take screenshots to create its background. Hidden popup views add no Home-screen drawing work.

`dist/settings-popup-validation.json` records build tests, installation checks and actual-TV visual validation. Private TV screenshots and logs are under `research/settings-popup-2026-11-03/`.
