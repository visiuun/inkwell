<div align="center">

<img src="docs/icon.svg" width="128" alt="Inkwell icon" />

# Inkwell

**Put any picture on your NFC e-ink phone case.**
A small, private replacement for the apps these cases ship with, built with Material 3 Expressive.
No account, no ads, no tracking.

[![Latest release](https://img.shields.io/github/v/release/visiuun/inkwell?style=flat-square&label=download&color=FFD426)](https://github.com/visiuun/inkwell/releases/latest)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)](#compatibility)
[![License: CC BY-NC 4.0](https://img.shields.io/badge/license-CC%20BY--NC%204.0-lightgrey?style=flat-square)](LICENSE)

### [⬇ Download the latest APK](https://github.com/visiuun/inkwell/releases/latest)

</div>

---

<table>
  <tr>
    <td><img src="docs/screenshots/create.png" alt="The editor with a live four-ink preview" /></td>
    <td><img src="docs/screenshots/sending.png" alt="Sending the picture, packet by packet" /></td>
    <td><img src="docs/screenshots/done.png" alt="The picture has landed on the case" /></td>
    <td><img src="docs/screenshots/library.png" alt="Library of sent designs" /></td>
    <td><img src="docs/screenshots/settings.png" alt="Settings" /></td>
  </tr>
  <tr align="center">
    <td>Live four-ink preview</td>
    <td>Sending</td>
    <td>Done</td>
    <td>Library</td>
    <td>Settings</td>
  </tr>
</table>

## What it does

The cases in question have a battery-free e-ink panel on the back that shows black, white, yellow and red. The phone powers it over NFC for the twenty or so seconds a redraw takes, and the picture then stays without power.

- **A preview that tells the truth.** The photo is reduced to the panel's four inks as you edit, pixel for pixel what the case will draw.
- **Frame it by hand.** Drag and pinch to place the photo, rotate it, fit or fill, white or black backdrop.
- **Four textures.** Smooth (Floyd–Steinberg) for photos, Crisp (Atkinson), Pattern (halftone grid) and Flat for logos and text.
- **Choose your inks.** Switch yellow or red off for a two- or three-colour look.
- **Brightness, contrast and colour** sliders, tuned for how little the panel has to work with.
- **Library.** Every design that reaches the case is kept, so going back to a favourite takes two taps.
- **Share to it.** "Put on my case" shows up in Android's share sheet for images.
- **Wipe to white**, read the case's details, and a test card for checking the panel.
- **Haptics** as the picture goes across and when it lands.
- **Updates itself** from this repository's releases. That is the only thing it goes online for.
- Light, dark and true-black OLED themes.

## Compatibility

Inkwell speaks the protocol of the white-label "passive NFC ink screen" cases sold under many names, the ones whose box points to the **SpaceInk**, **SpaceInk+** or **DeerPhoneCase** app (also KAZAVA, Novixand, TYOHINN). They exist for Galaxy S23/S24/S25 Ultra and several iPhones.

| | |
|---|---|
| Confirmed working | Four-colour case for the Galaxy S24 Ultra |
| Should work, not yet confirmed | Other cases that use the same apps |
| Won't work | Cases for other apps (Ink Show, Smart Case, AI Cast and so on) use different chips and protocols |

A quick way to tell: scan the case with any NFC tool. Inkwell's family shows up as a plain **ISO 14443-3A / Type 2** tag whose serial number starts with **1D** (Fudan Microelectronics).

Needs Android 7.0 or newer and a phone with NFC.

### If a picture arrives scrambled or the wrong way round

Settings → **Send the test card** puts a known picture on the case and shows what it should look like. From there:

- streaks instead of a picture: switch **Pixel order** between Rows and Columns
- upside down or mirrored: **Turn upside down** / **Mirror**

If your case needs something other than the defaults, please open an issue with the text from Settings → **Copy case report**.

## Permissions

| Permission | Why |
|---|---|
| NFC | Talking to the case |
| Camera | Optional. A case never leaves the phone, and Android only announces an NFC tag when it arrives. Opening the camera for a blink (no output, nothing captured) makes Android scan again. Inkwell only asks if the case isn't found by itself, and the trick can be switched off |
| Internet, install packages, notifications | Looking for a new release here once a day, and installing it when you say so |

## How it talks to the case

Worked out from the vendor's Android app; none of its code is in here.

1. **Login.** The case's Fudan NFC front end looks like a Type 2 tag. `B0` returns a challenge, `B1` answers it with PRINTcipher-48, `AF E0 01 91` switches the chip into pass-through to the microcontroller behind it.
2. **Handshake.** 32-byte commands starting `00 4A`, AES-128-ECB with a key every case shares. `01 00` returns the firmware name and panel size; the last four bytes of that reply become the session token.
3. **Picture.** Two bits per pixel (`00` black, `01` white, `10` yellow, `11` red), four pixels per byte, sent bottom-right to top-left in 180-byte packets, each followed by a one-byte sum. The case answers every packet with the number it wants next.
4. **Redraw.** The phone keeps the field up and polls `05 00` until the case answers `09 03`.

The whole exchange lives in [`CaseProtocol.kt`](app/src/main/java/com/visiuun/inkwell/nfc/CaseProtocol.kt), with a simulated case and tests beside it.

## Building

```
./gradlew assembleRelease
```

JDK 17 or newer and the Android SDK (compileSdk 37). Without a `keystore.properties` the release build is signed with the debug key. `tools/gen_symbols.py` regenerates the Material Symbols used in the interface.

## License

[CC BY-NC 4.0](LICENSE): use it, change it, share it, with credit, not for money.

Inkwell is an independent project. It is not affiliated with the makers of these cases or their apps; product names are mentioned only to say what it works with.
