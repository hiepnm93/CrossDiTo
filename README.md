# CrossDiTo

> **CrossDiTo is a personal Xteink X4 Pro test build made directly from [CrossInk](https://github.com/uxjulia/CrossInk), which is itself based on [CrossPoint Reader](https://github.com/crosspoint-reader/crosspoint-reader).** Full credit for the base firmware and every inherited feature belongs to the CrossInk, CrossPoint, and upstream contributors. This repository documents only the changes made on top of CrossInk.

> [!NOTE]
> **The information in this README is taken from the [CrossDiTo `main` branch](https://github.com/dito94/CrossDiTo/tree/main) merged with the [CrossInk 1.6.0](https://github.com/uxjulia/CrossInk) upstream release.** CrossDiTo 1.5.2 ports the CrossDiTo `main` state (1.5.1) onto the CrossInk 1.6.0 base. Features inherited from CrossInk remain upstream work; features carried over from CrossDiTo `main` are listed below.

### Supported Device

- Xteink X4 Pro

The current hardware-verified build is [CrossDiTo 1.5.1](./docs/releases/v1.5.1.md). The 1.5.2 rebase onto CrossInk 1.6.0 is in progress; see the [draft 1.5.2 release notes](./docs/releases/v1.5.2.md) and [CHANGELOG.md](./CHANGELOG.md).

## What's different in this fork

My goal with this fork was to maintain the core Crosspoint firmware while integrating my preferred typography and some lightweight reading statistics. I've focused on keeping the underlying system stable while layering in a few "nice-to-have" features and UI refinements along the way.

<table>
  <tr>
    <td align="center">
      <img src="./docs/images/bitter-small-15-margin.jpg" alt="Font: Bitter, Size: 12 pt, Margin: 15" /><br/>
      <em>Font: Bitter, Size: 12 pt, Margin: 15</em>
    </td>
    <td align="center">
      <img src="./docs/images/reading-stats.jpg" alt="Reading Stats with custom front button mapping shown" /><br/>
      <em>Reading Stats with custom front button mapping shown</em>
    </td>
  </tr>
</table>

### Carried over from CrossDiTo `main`

- New reader fonts: Lexend Deca and Bitter, with [Inter](https://fonts.google.com/specimen/Inter) as the UI display font.
  - [Lexend Deca](https://fonts.google.com/specimen/Lexend+Deca) - A research-backed sans-serif typeface designed to improve reading fluency, engineered around the theory that reading issues are often a visual crowding (design) problem.
  - [Bitter](https://fonts.google.com/specimen/Bitter) - A contemporary slab serif whose consistent stroke weight renders particularly well on e-ink; the medium weight was chosen specifically for the X4 Pro.
- Music notation and selected supplemental Unicode glyph support to be able to render Project Hail Mary accurately. Built-in reader fonts include music notation, selected Cyrillic glyphs, and the Project Hail Mary CJK fallback ranges; additional SD-card fonts retain emoji fallback support.
- Reader font sizes: 10 pt, 12 pt, 14 pt, and 16 pt. See [SD Card Fonts](./docs/sd-card-fonts.md) for installing additional font families and size ranges.
- A custom `Minimal` theme and sleep screen option, and a `Dashboard` theme and sleep screen option for reading stats enthusiasts.
- ~~Strikethrough~~ support, thicker <u>underlines</u>, `<hr>` section breaks, "redaction" style rendering, and improved simple-markup table support.
- Bookmarks; marking a book as finished from the in-book menu (with a pop-up at 99% and total-books-read tracking); moving finished books to a "Read" folder.
- Pinning a favorite sleep image that always displays under `Custom` or `Cover + Custom` sleep settings.
- Reader-only front-button remapping, plus more in-reader remapping options for side buttons, short power clicks, and long-press menu actions.
- Focus Reading and Guide Dots as optional reader modes; Force Paragraph Indents for wall-of-text books; an in-book menu for quick reader adjustments.
- Reading stats: total books read, total reading time, sessions, pages turned, average session time, and pages per minute, usable as the sleep screen; [all-time stats syncing](./docs/reading-stats-sync.md) and [reading progress sync](./docs/nearby-position-sync.md) between two CrossDiTo devices.
- Customizable Auto Page Turn Interval (5-120 seconds) and a 3x3 grid view for Recent Books.
- Build-level X4 Pro power policy carried over from CrossDiTo 1.5.1: dynamic CPU frequency scaling, tickless light sleep, USB-plug light-sleep wake tuning, and GT911 touch-wake interrupt gating.
- One-shot **Return to Previous Reading Position** after deliberate reader-menu jumps.
- To view release notes and download firmware, visit the [CrossDiTo releases](https://github.com/dito94/CrossDiTo/releases) page.

Item-by-item 1.5.2 status for these features is tracked in the [draft 1.5.2 release notes](./docs/releases/v1.5.2.md) and [CHANGELOG.md](./CHANGELOG.md).

### Inherited from CrossInk 1.6.0 through the merge

- X4 Classic support, the full X4 Pro touch menu, decimal book-progress precision, stable-page and keypad jumps, file rename in the browser, per-build UI language selection, publisher page numbers, and all upstream stability fixes.

### Removed relative to CrossDiTo `main`

The 1.5.2 rebase removes the CrossDiTo 1.5.1 internals that duplicated upstream 1.6 systems: the compiled chapter-event cache, retained-next-page prefetch, whole-book pagination display, the 1.5.0-era reader pipelines, and the 10-bit low-brightness frontlight experiment (frontlight now follows upstream CrossInk). The performance, battery, and frontlight figures below describe the 1.5.1-era `main` state and **no longer apply to this branch**; re-engineering is tracked as follow-up work in the [draft 1.5.2 release notes](./docs/releases/v1.5.2.md).

> [!NOTE]
> The figures in the rest of this section are engineering estimates, not controlled battery or stopwatch benchmarks. EPUB structure, cache state, SD-card speed, frontlight warmth, radio use, and the e-ink waveform can materially change the result.

#### Rough improvement at a glance (CrossDiTo 1.5.1 vs CrossInk 1.5.0)

| Usage | Rough expected change from CrossInk 1.5.0 | Why it changes |
| --- | --- | --- |
| Reopen a cached EPUB or reflow after a layout change | About **10-35% less visible wait** in favorable cached cases; about **20-50% less parser/tokenizer work** | A versioned compiled chapter-event cache avoids tokenizing the same XHTML again |
| Normal text page turn | About **10-30% less CPU preparation** and usually **5-15% faster perceived response** | One retained next page, a direct packed-framebuffer glyph path, and one-pass grayscale composition; the panel waveform still sets most of the visible delay |
| Return to a warm Home carousel | Usually near-immediate apart from the e-ink refresh | Reading-progress-only changes no longer rebuild every cached carousel frame |
| Production boot | About **250 ms faster** | USB file transfer starts asynchronously instead of blocking startup |
| Active reading, frontlight and Wi-Fi off | Roughly **10-25% longer reading time** | The CPU drops from 240 MHz to 80 MHz after activity, idle gaps use light sleep, and long panel waits run at the lower clock |
| Active reading at the same numeric 10% frontlight setting | Roughly **5-15% longer reading time** | MCU savings remain useful and CrossDiTo's low-light curve requests less LED PWM duty; the perceived brightness is intentionally lower than CrossInk at the same number |
| Static page, frontlight off | About **20-50% lower MCU-side idle draw**; the whole-device saving is smaller | Deadline-driven input servicing replaces constant polling and allows automatic light sleep |
| Heavy Wi-Fi, first indexing, or repeated image decoding | Roughly **0-10%** | Radio, SD, parser, and display work dominate, leaving fewer idle gaps to optimize |
| Deep-sleep standby | Roughly **0-5%** | Most CrossDiTo savings target active reading and light-idle time, not an already sleeping device |

#### Better low-light frontlight control (not carried into 1.5.2)

CrossInk's X4 Pro path used an approximately linear brightness percentage and split that integer percentage between the warm and cool LEDs. At very low mixed settings, both channels could round down to zero. CrossDiTo 1.5.1 converted brightness at the panel's full 10-bit PWM precision, applied a perceptual curve, and only then divided the result between the two channels. This kept **1% reliably on**, gave much finer control in a dark room, preserved the selected warmth, and still reached the same 100% maximum. The 1.5.2 rebase intentionally does not carry this experiment over.

| UI setting | CrossInk nominal combined PWM duty* | CrossDiTo 1.5.1 combined PWM duty |
| ---: | ---: | ---: |
| 1% | about 1% (could round to off with mixed warmth) | **0.10%**, minimum non-zero output |
| 5% | about 5% | **0.29%** |
| 10% | about 10% | **0.98%** |
| 25% | about 25% | **6.16%** |
| 50% | about 50% | **25.02%** |
| 75% | about 75% | **55.91%** |
| 100% | 100% | **100%** |

\* CrossInk's nominal value is shown before warm/cool integer rounding. PWM duty is not the same as measured light output or perceived brightness.

---

## Tips for the best reading experience

CrossDiTo uses the X4 Pro's ESP32-S3 and PSRAM, but internal RAM and e-ink bandwidth are still constrained compared with a phone, tablet, or desktop app.

- Keep folders under about 200 files. For the smoothest browsing, aim for 50-100 files per folder.
- Having 1000+ books on the SD card is fine if they are split into smaller folders, such as by author, series, genre, or read/unread status.
- Avoid putting every book in the SD card root. The file browser has to scan and sort the current folder before it can show it.
- Text-first EPUBs are the best fit. Large image-heavy EPUBs, scanned books, comics, and omnibus files with thousands of sections may load slowly or fail under memory pressure.
- As a rough target, EPUBs under 20 MB tend to work the best. Files over 50 MB may still work, but they are more likely to be slow or memory-sensitive, especially if they contain many large images.
- If an EPUB is unusually slow, try [optimizing](./docs/webserver.md#epub-optimization) it with the built-in web optimizer (via File Transfer) before copying it to the SD card: remove unused high-resolution images, split very large omnibus files, and avoid embedding multiple full font families when possible.
- Use a reliable SD card and leave some free space. CrossDiTo stores settings, reading progress, cache files, stats, and generated book data on the card.

---

## Development Device Simulator

The [device simulator](https://github.com/uxjulia/crossink-simulator) renders the e-ink display in an SDL2 window so firmware changes can be sanity-checked without flashing hardware. It now runs natively on Windows (MinGW/MSYS2) and Linux hosts in addition to macOS.

See [Simulator](./docs/simulator.md) for setup, platform notes, keyboard controls, and cache tips.

---

## Installation

Download `CrossDiTo-x4-pro-v1.5.1.bin` from the [CrossDiTo 1.5.1 release](https://github.com/dito94/CrossDiTo/releases/tag/v1.5.1). For an existing CrossDiTo installation, copy the file to the SD card and select **Settings > System > SD Card Firmware Update**. USB command-line flashing is also documented.

See [Installation](./docs/installation.md) for step-by-step flashing and revert instructions.

---

## Documentation

These links are operating manuals for the complete firmware, including inherited upstream behavior. They are documentation, not claims that CrossDiTo created those capabilities. You can also visit [https://www.crossink.dev](https://www.crossink.dev) for upstream guides and additional documentation.

- [User Guide](./docs/user-guide.md)
- [Installation](./docs/installation.md)
- [SD Card Fonts](./docs/sd-card-fonts.md)
- [Reader Features](./docs/reader-features.md)
- [Dictionary](./docs/dictionary.md)
- [Controls](./docs/controls.md)
- [Simulator](./docs/simulator.md)
- [Data Cache](./docs/data-cache.md)
- [Web server usage](./docs/webserver.md)
- [Web server endpoints](./docs/webserver-endpoints.md)
- [Common issues](./docs/troubleshooting.md)
- [Project scope](./SCOPE.md)
- [Development docs](./docs/development/README.md)

---

## Development quick start

CrossDiTo uses PlatformIO for building and flashing firmware.

See [Getting Started](./docs/development/getting-started.md) for prerequisites, clone setup, and validation commands.

### Nix/NixOS

Nix/NixOS users can enter the development shell with either `nix develop` (flakes) or `nix-shell`:

```bash
nix develop -f nix
# or
nix-shell nix
```

To flash the X4 Pro's ESP32-S3, enable PlatformIO's udev rules in your NixOS configuration:

```nix
services.udev.packages = with pkgs; [ platformio-core.udev ];
```

After rebuilding the system configuration, reconnect the device or reload udev rules.

### Build / flash / monitor

Connect the X4 Pro via USB-C:

```sh
# Xteink X4 Pro
pio run -e x4-pro --target upload
```

`x4-pro` is the only production firmware environment. Running `pio run` without `-e` builds the same target.

See [Testing and Debugging](./docs/development/testing-debugging.md) for serial logging, simulator checks, static analysis, and bug-report guidance.

---

## Repository layout

- `src/` - app orchestration, settings/state, and activity implementations (home, reader, settings, network, boot/sleep)
- `lib/` - supporting libraries: EPUB parsing/layout, fonts, i18n, filesystem helpers, HAL wrappers, and more
- `freeink-sdk/` - the exact vendored hardware SDK snapshot used by the verified X4 Pro release; it contains display, input, storage, frontlight, and battery support
- `web/` - web portal sources (`templates/`, `pages/`, `assets/`); compiled by `scripts/build_web.py` into `src/network/html/*.generated.h`
- `docs/` - user and developer documentation, published via the `site/` Astro site
- `site/` - Astro project that builds `docs/` into the CrossDiTo documentation website
- `test/` - unit tests and EPUB test fixtures
- `scripts/` - build, codegen, and release tooling (i18n generation, web asset building, hyphenation tries, release packaging, etc.)
- `bin/` - helper scripts for formatting (`clang-format-fix`) and CI checks
- `fs_/` - sample SD card contents (books, sleep images, themes) used by the simulator
- `nix/` - Nix/NixOS development shell definitions
- `managed_components/` - ESP-IDF managed component dependencies, fetched automatically during build
- [`SCOPE.md`](./SCOPE.md) and [`CHANGELOG.md`](./CHANGELOG.md) - project scope and complete release history

## Internals

CrossDiTo keeps its 48 KB display framebuffer in PSRAM and stores reusable book data on the SD card, preserving faster internal RAM for tasks, drivers, and latency-sensitive work.

See [Data Cache](./docs/data-cache.md) for the `.crosspoint` layout and [File Formats](./docs/file-formats.md) for binary cache details.

---

If you'd like to show some love and support ongoing development, please consider supporting me on Ko-fi.

CrossDiTo intentionally stays narrow and X4 Pro-only. Bug reports may be opened in [CrossDiTo issues](https://github.com/dito94/CrossDiTo/issues). Major features requiring broad device support or ongoing upstream maintenance should be proposed to [CrossPoint Reader](https://github.com/crosspoint-reader/crosspoint-reader).
