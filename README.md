# Morrowind Touch

On-screen **touch controls for Morrowind (OpenMW) streamed to an Android phone** with Steam Link, in the style of
OpenMW for Android.

The game runs on your PC and Steam Link shows the video. Morrowind Touch draws its own controls on top of Steam Link
and sends them over Wi-Fi **or mobile data** to a small helper on the PC. The helper turns them into a virtual gamepad, mouse
and keyboard.

- **Gameplay layout:**
  - A floating walk stick on the left.
  - Swipe anywhere else to look around.
  - Big Attack, Use, Jump, Weapon, Magic and Sneak buttons.
  - A top row with Journal, Inventory/Map, Objectives, Ledger, Quick keys, Rest, View, Save and Menu.
- **Menu layout:** a D-pad, A/B/X/Y, LB/RB for tabs, triggers and Menu. The overlay switches to it **automatically**
  when a game menu opens and back when it closes. This works with OpenMW's *controller menus* option
  (`[GUI] controller menus = true`).
- **◐** hides the controls so you can use Steam Link itself; tap it again to bring them back. **⇄** switches
  layouts by hand.

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

## Controller bindings used

The defaults from OpenMW, plus a few extras:

| Button | Action |
|---|---|
| LB | Journal |
| B | Inventory/Map |
| RB | Rest |
| Back | Quick keys menu |
| D-pad up / down | Objective panel / Collector's Ledger (Comprehensive Objective Mod) |
| D-pad left / right | Cycle spells |

## Build the APK

Only the Android SDK command-line tools are needed (no Gradle):

```bash
KEYSTORE=my.keystore KS_PASS=secret android/build.sh
```
