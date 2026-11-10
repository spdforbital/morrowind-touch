#!/usr/bin/env python3
"""Morrowind Touch - PC side.

Receives the phone overlay's touch state over UDP and replays it
as a virtual gamepad + mouse + keyboard (Linux uinput) that OpenMW sees like real hardware.
It also tells the phone which game menu is open (read from openmw.log, where the
Comprehensive Objective Mod prints "[CO-UI] <mode>"), so the overlay can switch layouts.

The virtual devices only exist while the phone is connected (created on the first packet,
removed after IDLE_DROP seconds without packets).
"""
import fcntl, hashlib, hmac, os, socket, struct, time

PORT = 47811
HERE = os.path.dirname(os.path.abspath(__file__))
PASSWORD_FILE = os.path.join(HERE, 'password.txt')
MAX_CLOCK_SKEW_MS = 60000   # packets older/newer than this are refused (stops replays)
LOG = os.path.expanduser('~/.config/openmw/openmw.log')
IDLE_RELEASE = 0.5     # no packet for this long: release everything
IDLE_DROP = 60         # no packet for this long: remove the virtual devices

# --- uinput -------------------------------------------------------------------------------
EV_SYN, EV_KEY, EV_REL, EV_ABS = 0, 1, 2, 3
UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_RELBIT, UI_SET_ABSBIT = 0x40045564, 0x40045565, 0x40045566, 0x40045567
UI_DEV_CREATE, UI_DEV_DESTROY = 0x5501, 0x5502
ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y = 0, 1, 2, 3, 4, 5, 0x10, 0x11
REL_X, REL_Y, REL_WHEEL = 0, 1, 8
BTN_LEFT, BTN_RIGHT = 0x110, 0x111

# phone button bit -> evdev code (D-pad bits 11-14 go to the hat instead)
PAD_BUTTONS = [0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e]
#               A      B      X      Y      LB     RB     Back   Start  Guide  LS     RS
KEYS = [1, 28, 63, 67, 36, 37, 38, 15, 57, 18, 20, 29, 19, 33, 24, 42, 14]
#       Esc Enter F5 F9  J   K   L   Tab Space E  T  LCtrl R  F   O  LShift Backspace

# typing (US layout): char -> (evdev key, needs shift)
_ROWS = {'1234567890-=': [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13], '!@#$%^&*()_+': [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13],
         'qwertyuiop[]': list(range(16, 28)), 'QWERTYUIOP{}': list(range(16, 28)),
         "asdfghjkl;'`": list(range(30, 42)), 'ASDFGHJKL:"~': list(range(30, 42)),
         '\\zxcvbnm,./': [43] + list(range(44, 54)), '|ZXCVBNM<>?': [43] + list(range(44, 54))}
CHARMAP = {' ': (57, False), '\n': (28, False), '\b': (14, False)}
for chars, codes in _ROWS.items():
    shifted = chars[0] in '!QA|'
    for ch, code in zip(chars, codes):
        CHARMAP[ch] = (code, shifted)
TYPE_KEYS = sorted({c for c, _ in CHARMAP.values()} | set(KEYS))
MOUSE_LEFT_BIT, MOUSE_RIGHT_BIT = 20, 21   # mouse buttons ride in the same bitmask
WHEEL_UP_BIT, WHEEL_DOWN_BIT = 22, 23       # held = scroll one notch now and then every WHEEL_REPEAT s
WHEEL_REPEAT = 0.12


class Device:
    def __init__(self, name, keys=(), rels=(), absinfo=None, vendor=0x1209, product=0x4d57):
        self.fd = os.open('/dev/uinput', os.O_WRONLY | os.O_NONBLOCK)
        if keys:
            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
            for k in keys: fcntl.ioctl(self.fd, UI_SET_KEYBIT, k)
        if rels:
            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_REL)
            for r in rels: fcntl.ioctl(self.fd, UI_SET_RELBIT, r)
        absmax, absmin, absflat = [0] * 64, [0] * 64, [0] * 64
        if absinfo:
            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_ABS)
            for code, (lo, hi) in absinfo.items():
                fcntl.ioctl(self.fd, UI_SET_ABSBIT, code)
                absmin[code], absmax[code] = lo, hi
        dev = struct.pack('80sHHHHi', name.encode()[:79], 0x03, vendor, product, 1, 0)
        dev += struct.pack('64i', *absmax) + struct.pack('64i', *absmin)
        dev += struct.pack('64i', *[0] * 64) + struct.pack('64i', *absflat)
        os.write(self.fd, dev)
        fcntl.ioctl(self.fd, UI_DEV_CREATE)
        self.state = {}

    def emit(self, etype, code, value):
        os.write(self.fd, struct.pack('llHHi', 0, 0, etype, code, value))

    def set(self, etype, code, value):
        if self.state.get((etype, code)) != value:
            self.state[(etype, code)] = value
            self.emit(etype, code, value)
            return True
        return False

    def sync(self):
        self.emit(EV_SYN, 0, 0)

    def close(self):
        try:
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        finally:
            os.close(self.fd)


class Devices:
    def __init__(self):
        S = 32767
        self.pad = Device('Morrowind Touch Gamepad', keys=PAD_BUTTONS, absinfo={
            ABS_X: (-S, S), ABS_Y: (-S, S), ABS_RX: (-S, S), ABS_RY: (-S, S),
            ABS_Z: (0, 255), ABS_RZ: (0, 255), ABS_HAT0X: (-1, 1), ABS_HAT0Y: (-1, 1)})
        self.mouse = Device('Morrowind Touch Mouse', keys=(BTN_LEFT, BTN_RIGHT), rels=(REL_X, REL_Y, REL_WHEEL),
                            product=0x4d58)
        self.kbd = Device('Morrowind Touch Keyboard', keys=TYPE_KEYS, product=0x4d59)

    def apply(self, lx, ly, rx, ry, lt, rt, buttons, mdx, mdy, keys):
        p, S = self.pad, 32767
        for code, v in ((ABS_X, lx), (ABS_Y, ly), (ABS_RX, rx), (ABS_RY, ry)):
            p.set(EV_ABS, code, int(max(-1, min(1, v)) * S))
        p.set(EV_ABS, ABS_Z, int(max(0, min(1, lt)) * 255))
        p.set(EV_ABS, ABS_RZ, int(max(0, min(1, rt)) * 255))
        for bit, code in enumerate(PAD_BUTTONS):
            p.set(EV_KEY, code, 1 if buttons >> bit & 1 else 0)
        up, down, left, right = (buttons >> 11 & 1, buttons >> 12 & 1, buttons >> 13 & 1, buttons >> 14 & 1)
        p.set(EV_ABS, ABS_HAT0X, right - left)
        p.set(EV_ABS, ABS_HAT0Y, down - up)
        p.sync()
        changed = self.mouse.set(EV_KEY, BTN_LEFT, keys >> MOUSE_LEFT_BIT & 1)
        changed = self.mouse.set(EV_KEY, BTN_RIGHT, keys >> MOUSE_RIGHT_BIT & 1) or changed
        wheel = (keys >> WHEEL_UP_BIT & 1) - (keys >> WHEEL_DOWN_BIT & 1)
        now = time.monotonic()
        if wheel and (wheel != getattr(self, 'wheelDir', 0) or now - getattr(self, 'wheelAt', 0) >= WHEEL_REPEAT):
            self.mouse.emit(EV_REL, REL_WHEEL, wheel)
            self.wheelAt = now
            changed = True
        self.wheelDir = wheel
        if mdx or mdy:
            self.mouse.emit(EV_REL, REL_X, mdx)
            self.mouse.emit(EV_REL, REL_Y, mdy)
            changed = True
        if changed:
            self.mouse.sync()
        for bit, code in enumerate(KEYS):
            self.kbd.set(EV_KEY, code, 1 if keys >> bit & 1 else 0)
        self.kbd.sync()

    def type_text(self, text):
        k = self.kbd
        for ch in text:
            code, shift = CHARMAP.get(ch, (None, False))
            if code is None:
                continue
            if shift: k.emit(EV_KEY, 42, 1)
            k.emit(EV_KEY, code, 1); k.sync()
            time.sleep(0.008)
            k.emit(EV_KEY, code, 0)
            if shift: k.emit(EV_KEY, 42, 0)
            k.sync()
            time.sleep(0.008)

    def release(self):
        self.apply(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    def close(self):
        for d in (self.pad, self.mouse, self.kbd):
            d.close()


# --- game menu state from openmw.log ------------------------------------------------------
class ModeWatcher:
    def __init__(self):
        self.mode, self.pos, self.inode = 'none', 0, None

    def poll(self):
        now = time.monotonic()
        if now - getattr(self, 'checked', 0) > 1.0:            # is the game running at all?
            self.checked = now
            self.running = False
            for p in os.listdir('/proc'):
                if p.isdigit():
                    try:
                        if open(f'/proc/{p}/comm').read().strip() == 'openmw.x86_64':
                            self.running = True
                            break
                    except OSError:
                        pass
        if not self.running:
            return 'off'                                        # game closed: the phone collapses its controls
        try:
            st = os.stat(LOG)
        except FileNotFoundError:
            return self.mode
        if st.st_ino != self.inode or st.st_size < self.pos:   # new game session -> new log
            self.inode, self.pos, self.mode = st.st_ino, 0, 'none'
        if st.st_size > self.pos:
            with open(LOG, 'rb') as f:
                f.seek(self.pos)
                chunk = f.read()
            self.pos = st.st_size
            for line in chunk.decode('utf-8', 'replace').splitlines():
                i = line.find('[CO-UI] ')
                if i >= 0:
                    self.mode = line[i + 8:].strip() or 'none'
        return self.mode


def load_key():
    """Every packet is signed with HMAC-SHA256 using a key made from the password in password.txt."""
    pw = open(PASSWORD_FILE, encoding='utf-8').read().strip()
    if not pw:
        raise SystemExit('password.txt is empty')
    if len(pw) < 10:
        print('note: short password - fine at home, use a longer one if the PC is reachable from the internet', flush=True)
    return hashlib.sha256(('mwt2:' + pw).encode('utf-8')).digest()


def sign(key, msg):
    return hmac.new(key, msg.encode('ascii'), hashlib.sha256).hexdigest()[:32]


def main():
    key = load_key()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(('0.0.0.0', PORT))
    sock.settimeout(0.1)
    devs, last, modes = None, 0.0, ModeWatcher()
    session, lastSeq, lastBad = None, -1, 0.0
    released = True
    print(f'Morrowind Touch helper listening on UDP {PORT}', flush=True)
    while True:
        now = time.monotonic()
        try:
            data, addr = sock.recvfrom(512)
        except (socket.timeout, OSError):
            data = None
        if data:
            text = data.decode('ascii', 'replace')
            body, _, mac = text.rpartition(' ')
            parts = body.split()
            if not parts or parts[0] not in ('MWT2', 'MWT2X') or (parts[0] == 'MWT2' and len(parts) != 14) \
                    or (parts[0] == 'MWT2X' and len(parts) != 5):
                continue
            if not hmac.compare_digest(sign(key, body), mac):
                if now - lastBad > 1.0:                         # tell the app the password is wrong (rate-limited)
                    lastBad = now
                    sock.sendto(b'MWT2 badauth', addr)
                continue
            if parts[0] == 'MWT2X':                                 # typed text from the phone keyboard
                try:
                    sess, ts, seq = parts[1], int(parts[2]), int(parts[3])
                    text = bytes.fromhex(parts[4]).decode('utf-8')
                except ValueError:
                    continue
                if abs(ts - time.time() * 1000) > MAX_CLOCK_SKEW_MS or (sess == session and seq <= lastSeq):
                    continue
                if sess != session:
                    session = sess
                lastSeq = seq
                if devs is None:
                    devs = Devices()
                devs.type_text(text)
                last = now
                continue
            try:
                sess, ts, seq = parts[1], int(parts[2]), int(parts[3])
                lx, ly, rx, ry, lt, rt = (float(x) for x in parts[4:10])
                buttons, mdx, mdy, keys = (int(x) for x in parts[10:14])
            except ValueError:
                continue
            if abs(ts - time.time() * 1000) > MAX_CLOCK_SKEW_MS:
                continue                                        # stale or replayed
            if sess != session:
                session, lastSeq = sess, -1                     # app (re)started
            if seq <= lastSeq:
                continue                                        # old/out-of-order packet
            lastSeq = seq
            if devs is None:
                devs = Devices()
                print(f'phone connected from {addr[0]}', flush=True)
            devs.apply(lx, ly, rx, ry, lt, rt, buttons, mdx, mdy, keys)
            last, released = now, False
            reply = 'MWT2 mode=' + modes.poll()
            sock.sendto((reply + ' ' + sign(key, reply)).encode(), addr)
        if devs is not None:
            if not released and now - last > IDLE_RELEASE:
                devs.release(); released = True
            if now - last > IDLE_DROP:
                devs.close(); devs = None
                print('phone gone; virtual devices removed', flush=True)


if __name__ == '__main__':
    main()
