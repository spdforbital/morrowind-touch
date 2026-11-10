# Morrowind Touch

On-screen **touch controls for Morrowind (OpenMW) streamed to an Android phone** with Steam Link, in the style of
OpenMW for Android.

The game runs on your PC and Steam Link shows the video. Morrowind Touch draws its own controls on top of Steam Link
and sends them over Wi-Fi **or mobile data** to a small helper on the PC. The helper turns them into a virtual gamepad, mouse
and keyboard.

- **The controls are OpenMW for Android's**, with the same layout, joystick behaviour and button images:
  - Left stick to walk, right stick to look.
  - A big Attack button that you can also hold and drag to aim.
  - Use, Jump, Ready Weapon, Ready Magic, Sneak, Inventory and Pause.
  - A ≡ top bar with 3rd person, Quicksave, Journal and Rest. Its mouse icon hides the overlay so you can tap
    Steam Link itself.
- **A Collector's Ledger button** (gem) on the left, for the companion mod.
- **Menus work like OpenMW for Android:** when a game menu opens, only the right stick (which moves the cursor), Attack
  (which clicks), Pause, Journal and Inventory stay. The overlay switches **automatically**, because the PC tells it when
  a menu is open.

## Install

**Phone:**
1. Download `MorrowindTouch.apk` from [Releases](../../releases) and install it (allow "install unknown apps" once).
2. Open it and enter the PC's address and your password.
3. Press **Start overlay** and allow **Display over other apps**.
4. In Steam Link, turn off its own touch controls.

**PC (Linux):**

```bash
echo 'your-password' > pc/password.txt && chmod 600 pc/password.txt   # at least 8 characters
python3 pc/touch_helper.py          # listens on UDP 47811
```

- Every packet is signed with HMAC-SHA256 using a key made from the password, and carries a timestamp and
  counter, so packets can't be forged or replayed. The phone shows "wrong password" if the passwords differ.
- **At home:** use the PC's Wi-Fi IP.
- **On mobile data:** the phone has to reach the PC. The simplest way is [Tailscale](https://tailscale.com) on
  both, then use the PC's Tailscale address (100.x.y.z). Forwarding UDP 47811 on your router also works.
  Steam Link streams over mobile data by itself.

- The helper needs write access to `/dev/uinput`, which most desktops with Steam installed already give.
- The virtual devices only exist while the phone is connected.
- For automatic menu switching, the game must print `[CO-UI] <mode>` lines to `openmw.log`. The
  [Comprehensive Objective Mod](https://github.com/spdforbital/comprehensive-objective-mod) does that.

## Credits and license

The on-screen control layout, joystick/attack-button behaviour and button images come from
[OpenMW-Android](https://github.com/xyzz/openmw-android) by Ilya Zhuravlev, licensed under the GNU GPL v3.
Morrowind Touch is therefore also licensed under the **GNU GPL v3 or later** (see `LICENSE`).

## Build the APK

Only the Android SDK command-line tools are needed (no Gradle):

```bash
KEYSTORE=my.keystore KS_PASS=secret android/build.sh
```
