/*
    Morrowind Touch - on-screen controls over Steam Link.

    The control layout, joystick behaviour, attack-button aiming and button images are taken from
    OpenMW-Android (ui/controls/Osc.kt, Joystick*.kt, OscAttackButton.kt),
    Copyright (C) 2018, 2019 Ilya Zhuravlev, licensed under the GNU GPL v3 or later.
    This file is likewise distributed under the GNU GPL v3 or later.
*/
package com.spdforbital.morrowindtouch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenMW-Android's on-screen controls, rebuilt as an overlay that sends input to the PC.
 * Positions are on OpenMW-Android's virtual 1024x768 screen; x scales with width, y with height,
 * size with width, exactly like the original.
 */
public class OverlayView extends View implements Net.Listener {

    static final int VW = 1024, VH = 768, SIZE = 70, JOY = 230, JOY_OFF = 110, TOP = 90;

    // visibility, as in OpenMW-Android: ESSENTIAL stays in menus, NORMAL only during gameplay, ALWAYS both
    static final int ESSENTIAL = 1, NORMAL = 2, ALWAYS = 3, TOPBAR = 4, MENU_ONLY = 5;

    // element kinds
    static final int KEY = 0, MOUSE_BTN = 1, JOY_LEFT = 2, JOY_RIGHT = 3, ATTACK = 4, TOGGLE_TOP = 5, HIDE = 6,
            LATCH = 7, KEYBOARD = 8;

    static class El {
        final int kind, vis, bit, vx, vy, vsize, image;
        final String text;
        RectF r = new RectF();
        boolean latched;   // LATCH buttons: tap on, tap off
        El(int kind, int vis, int image, String text, int vx, int vy, int vsize, int bit) {
            this.kind = kind; this.vis = vis; this.image = image; this.text = text;
            this.vx = vx; this.vy = vy; this.vsize = vsize; this.bit = bit;
        }
    }

    static class Ptr {
        El el;
        float ix, iy, x, y, lx, ly;
        long down, down2;
    }

    private final Net net;
    private final float lookSpeed;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;

    private final List<El> els = new ArrayList<>();
    private final SparseArray<Bitmap> bitmaps = new SparseArray<>();
    private final SparseArray<Ptr> ptrs = new SparseArray<>();
    private boolean hidden, topVisible, inMenu, linkOk, badPassword;
    private boolean userHidden, gameOff = true;   // collapsed by the user / because the game isn't running
    private final El hideBtn;

    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            label = new Paint(Paint.ANTI_ALIAS_FLAG), box = new Paint(Paint.ANTI_ALIAS_FLAG);

    OverlayView(Context c, Net net, float lookSpeed) {
        super(c);
        this.net = net;
        this.lookSpeed = lookSpeed;
        img.setAlpha(Math.round(0.4f * 255));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.GRAY);
        stroke.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        label.setColor(Color.WHITE);
        label.setAlpha(Math.round(0.6f * 255));
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
        box.setColor(Color.argb(Math.round(0.4f * 255), 60, 60, 60));

        int joyRightX = VW - JOY - JOY_OFF;
        els.add(new El(JOY_LEFT, NORMAL, 0, null, JOY_OFF, 400, JOY, 0));
        els.add(new El(JOY_RIGHT, ESSENTIAL, 0, null, joyRightX, 400, JOY, 0));
        els.add(new El(TOGGLE_TOP, ALWAYS, R.drawable.toggle, null, 0, 0, SIZE, 0));
        els.add(new El(MOUSE_BTN, ALWAYS, R.drawable.inventory, null, 940, 95, SIZE, Net.M_RIGHT));
        els.add(new El(KEY, NORMAL, R.drawable.sneak, null, 940 - TOP, 0, SIZE, Net.K_CTRL));
        els.add(new El(KEY, ESSENTIAL, R.drawable.pause, null, 940, 0, SIZE, Net.K_ESC));
        els.add(new El(KEY, NORMAL, R.drawable.toggle_magic, null, 940, 450, SIZE, Net.K_R));
        els.add(new El(KEY, NORMAL, R.drawable.toggle_weapon, null, 940, 560, SIZE, Net.K_F));
        els.add(new El(ATTACK, ESSENTIAL, R.drawable.attack, null, 800, 315, 120, Net.M_LEFT));
        els.add(new El(KEY, NORMAL, R.drawable.use, null, JOY_OFF + JOY / 2 + 105, 630, SIZE, Net.K_SPACE));
        els.add(new El(KEY, NORMAL, R.drawable.jump, null, joyRightX + JOY / 2 - 105 - SIZE, 630, SIZE, Net.K_E));
        // menus only: scroll lists and OK, where the walk stick sits during play
        els.add(new El(KEY, MENU_ONLY, R.drawable.scroll_up, null, JOY_OFF + 30, 380, 90, Net.WHEEL_UP));
        els.add(new El(KEY, MENU_ONLY, R.drawable.scroll_down, null, JOY_OFF + 30, 560, 90, Net.WHEEL_DOWN));
        els.add(new El(KEY, MENU_ONLY, R.drawable.ok, null, JOY_OFF + 160, 470, SIZE, Net.K_ENTER));
        // menus only: Grab (tap = pick up / hold the click, tap again = drop) and Shift (latched) for dragging stacks
        els.add(new El(LATCH, MENU_ONLY, R.drawable.grab, null, 940, 450, SIZE, Net.M_LEFT));
        els.add(new El(LATCH, MENU_ONLY, R.drawable.shift, null, 940, 560, SIZE, Net.K_SHIFT));
        // Collector's Ledger, on the left (mirrors the inventory button on the right)
        els.add(new El(KEY, ALWAYS, R.drawable.ledger, null, 0, 95, SIZE, Net.K_L));
        // top bar, opened with the toggle button (same order as OpenMW-Android; the mouse icon hides the overlay)
        els.add(new El(KEY, TOPBAR, R.drawable.third_person, null, TOP, 0, SIZE, Net.K_TAB));
        els.add(new El(KEY, TOPBAR, R.drawable.save, null, TOP * 2, 0, SIZE, Net.K_F5));
        els.add(new El(KEY, TOPBAR, R.drawable.journal, null, TOP * 3, 0, SIZE, Net.K_J));
        els.add(new El(KEY, TOPBAR, R.drawable.wait, null, TOP * 4, 0, SIZE, Net.K_T));
        els.add(new El(KEYBOARD, TOPBAR, R.drawable.keyboard, null, TOP * 5, 0, SIZE, 0));
        hideBtn = new El(HIDE, ALWAYS, R.drawable.mouse, null, 0, 180, SIZE, 0);
        els.add(hideBtn);

        for (El e : els) {
            if (e.image != 0 && bitmaps.get(e.image) == null) {
                bitmaps.put(e.image, BitmapFactory.decodeResource(getResources(), e.image));
            }
        }
    }

    private boolean visible(El e) {
        if (e.vis == TOPBAR) return topVisible;
        if (e.vis == ALWAYS) return true;
        if (e.vis == MENU_ONLY) return inMenu;
        if (inMenu) return e.vis == ESSENTIAL;
        return true;
    }

    private void layoutEls(int w, int h) {
        for (El e : els) {
            float x = (float) e.vx * w / VW, y = (float) e.vy * h / VH, s = (float) e.vsize * w / VW;
            e.r.set(x, y, x + s, y + s);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!hidden) layoutEls(w, h);
    }

    // ---- window ---------------------------------------------------------------------------------
    void attach(WindowManager wm, WindowManager.LayoutParams lp) {
        this.wm = wm;
        this.lp = lp;
        wm.addView(this, lp);
        post(this::updateHidden);    // start collapsed until the PC says Morrowind is running
    }

    void detach() {
        closeKeyboard();
        try { wm.removeView(this); } catch (Exception ignored) {}
    }

    /** Collapse when the user asked to, or when Morrowind isn't running / the PC can't be reached. */
    private void updateHidden() {
        boolean h = userHidden || gameOff || !linkOk;
        if (h != hidden) setHidden(h);
        else invalidate();
    }

    void toggleUserHidden() {
        userHidden = !userHidden;
        updateHidden();
    }

    private void setHidden(boolean h) {
        hidden = h;
        releaseAll();
        int size = Math.round(56 * getResources().getDisplayMetrics().density);
        lp.width = h ? size : WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = h ? size : WindowManager.LayoutParams.MATCH_PARENT;
        wm.updateViewLayout(this, lp);
        invalidate();
    }

    // ---- phone keyboard -> typing on the PC ------------------------------------------------------
    private android.widget.EditText kbField;

    private void openKeyboard() {
        if (kbField != null) return;
        final android.widget.EditText f = new android.widget.EditText(getContext());
        f.setSingleLine(true);
        f.setHint("Type here - Enter sends, back closes");
        f.setBackgroundColor(Color.argb(220, 30, 30, 30));
        f.setTextColor(Color.WHITE);
        f.setHintTextColor(Color.GRAY);
        f.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        f.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        final String[] prev = {""};
        f.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            public void onTextChanged(CharSequence c, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable ed) {
                String now = ed.toString(), old = prev[0];
                int common = 0;
                while (common < now.length() && common < old.length() && now.charAt(common) == old.charAt(common)) common++;
                StringBuilder out = new StringBuilder();
                for (int i = common; i < old.length(); i++) out.append('\b');
                out.append(now.substring(common));
                net.sendText(out.toString());
                prev[0] = now;
            }
        });
        f.setOnEditorActionListener((v, actionId, event) -> {
            net.sendText("\n");
            closeKeyboard();
            return true;
        });
        f.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                closeKeyboard();
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DEL && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && f.getText().length() == 0) {
                net.sendText("\b");          // backspace into text that was already in the game
                return true;
            }
            return false;
        });
        WindowManager.LayoutParams kp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, android.graphics.PixelFormat.TRANSLUCENT);
        kp.gravity = android.view.Gravity.TOP;
        kp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
        wm.addView(f, kp);
        kbField = f;
        f.requestFocus();
        f.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            im.showSoftInput(f, 0);
        }, 150);
    }

    private void closeKeyboard() {
        if (kbField == null) return;
        try { wm.removeView(kbField); } catch (Exception ignored) {}
        kbField = null;
    }

    // ---- PC link --------------------------------------------------------------------------------
    @Override
    public void onMode(String m) {
        post(() -> {
            boolean off = m.equals("off");
            if (off != gameOff) { gameOff = off; updateHidden(); }
            boolean menu = !(off || m.equals("none") || m.startsWith("Loading"));
            if (menu != inMenu) {
                inMenu = menu;
                for (El e : els) e.latched = false;
                releaseAll();
                invalidate();
            }
        });
    }

    @Override
    public void onLink(boolean ok) {
        post(() -> { linkOk = ok; if (ok) badPassword = false; updateHidden(); });
    }

    @Override
    public void onBadPassword() {
        post(() -> { badPassword = true; invalidate(); });
    }

    // ---- touch ----------------------------------------------------------------------------------
    private El hit(float x, float y) {
        for (int i = els.size() - 1; i >= 0; i--) {     // later elements are on top
            El e = els.get(i);
            if (visible(e) && e.r.contains(x, y)) return e;
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        int idx = ev.getActionIndex();
        if (hidden) {
            if (a == MotionEvent.ACTION_UP) {
                if (userHidden) toggleUserHidden();
                else android.widget.Toast.makeText(getContext(),
                        !linkOk ? (badPassword ? "Morrowind Touch: wrong password" : "Morrowind Touch: can't reach the PC")
                                : "Morrowind Touch: controls appear when Morrowind is running",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = ev.getX(idx), y = ev.getY(idx);
                El e = hit(x, y);
                if (e == null) break;
                if (e.kind == LATCH) {
                    e.latched = !e.latched;
                    break;
                }
                if (e.kind == TOGGLE_TOP || e.kind == HIDE || e.kind == KEYBOARD) {
                    Ptr p = new Ptr();         // act on release, like OpenMW-Android's custom buttons
                    p.el = e;
                    ptrs.put(ev.getPointerId(idx), p);
                    break;
                }
                Ptr p = new Ptr();
                p.el = e;
                p.ix = p.x = p.lx = x;
                p.iy = p.y = p.ly = y;
                p.down = System.currentTimeMillis();
                p.down2 = ev.getEventTime();
                ptrs.put(ev.getPointerId(idx), p);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    Ptr p = ptrs.get(ev.getPointerId(i));
                    if (p == null || p.el == null) continue;
                    float nx = ev.getX(i), ny = ev.getY(i);
                    float size = p.el.r.width();
                    if (p.el.kind == JOY_RIGHT && size > 0) {
                        // OpenMW-Android: mouseScalingFactor 900 over the stick's size
                        float gain = 900f / size * lookSpeed;
                        if (inMenu) {
                            // cursor acceleration: slow thumb = precise, quick flick = far
                            long t = ev.getEventTime();
                            float dist = (float) Math.hypot(nx - p.lx, ny - p.ly);
                            float dt = Math.max(1, t - p.down2);
                            float speed = dist / dt * 1000f / getResources().getDisplayMetrics().density; // dp per second
                            gain *= 0.35f + Math.min(2.2f, speed / 600f);
                            p.down2 = t;
                        }
                        net.addMouse((nx - p.lx) * gain, (ny - p.ly) * gain);
                    } else if (p.el.kind == ATTACK && size > 0 && System.currentTimeMillis() - p.down > 100) {
                        // hold attack for 100 ms, then dragging aims (factor 400)
                        net.addMouse((nx - p.lx) * 400f / size * lookSpeed, (ny - p.ly) * 400f / size * lookSpeed);
                    }
                    p.x = p.lx = nx;
                    p.y = p.ly = ny;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                Ptr p = ptrs.get(ev.getPointerId(idx));
                ptrs.remove(ev.getPointerId(idx));
                if (p != null && p.el != null) {
                    if (p.el.kind == TOGGLE_TOP) topVisible = !topVisible;
                    else if (p.el.kind == HIDE) { toggleUserHidden(); return true; }
                    else if (p.el.kind == KEYBOARD) { openKeyboard(); }
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                ptrs.clear();
                break;
        }
        pushState();
        invalidate();
        return true;
    }

    private void releaseAll() {
        ptrs.clear();
        pushState();
    }

    private void pushState() {
        int keys = 0;
        float lx = 0, ly = 0;
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr p = ptrs.valueAt(i);
            if (p.el == null) continue;
            switch (p.el.kind) {
                case KEY:
                case MOUSE_BTN:
                case ATTACK:
                    keys |= 1 << p.el.bit;
                    break;
                case JOY_LEFT: {
                    // OpenMW-Android JoystickLeft: width/3 for full tilt, axis bias, small dead-zone kick
                    float w = p.el.r.width() / 3f;
                    float dx = p.x - p.ix, dy = p.y - p.iy, bias = 0.3f;
                    if (Math.abs(dx) > Math.abs(dy)) {
                        dy = Math.signum(dy) * Math.max(0f, Math.abs(dy) - bias * Math.abs(dx));
                    } else {
                        dx = Math.signum(dx) * Math.max(0f, Math.abs(dx) - bias * Math.abs(dy));
                    }
                    lx = clamp(dx / w + 0.2f * Math.signum(dx));
                    ly = clamp(dy / w + 0.2f * Math.signum(dy));
                    break;
                }
                default:
                    break;
            }
        }
        for (El e : els) if (e.kind == LATCH && e.latched && visible(e)) keys |= 1 << e.bit;
        net.lx = lx;
        net.ly = ly;
        net.keys = keys;
        net.buttons = 0;
    }

    private static float clamp(float v) { return Math.max(-1f, Math.min(1f, v)); }

    // ---- drawing --------------------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas cv) {
        if (hidden) {
            Bitmap b = bitmaps.get(R.drawable.mouse);
            cv.drawBitmap(b, null, new RectF(0, 0, getWidth(), getHeight()), img);
            box.setColor(!linkOk ? Color.rgb(200, 60, 50) : (userHidden ? Color.rgb(90, 180, 90) : Color.GRAY));
            cv.drawCircle(getWidth() * 0.82f, getHeight() * 0.18f, getWidth() * 0.12f, box);
            box.setColor(Color.argb(Math.round(0.4f * 255), 60, 60, 60));
            return;
        }
        for (El e : els) {
            if (!visible(e)) continue;
            if (e.kind == JOY_LEFT || e.kind == JOY_RIGHT) {
                drawJoystick(cv, e);
            } else if (e.image != 0) {
                if (e.kind == LATCH && e.latched) {
                    cv.drawRoundRect(e.r, e.r.width() * 0.2f, e.r.width() * 0.2f, box);
                    img.setAlpha(255);
                    cv.drawBitmap(bitmaps.get(e.image), null, e.r, img);
                    img.setAlpha(Math.round(0.4f * 255));
                } else {
                    cv.drawBitmap(bitmaps.get(e.image), null, e.r, img);
                }
            } else {
                cv.drawRoundRect(e.r, e.r.width() * 0.15f, e.r.width() * 0.15f, box);
                label.setTextSize(e.r.width() * (e.text.length() > 3 ? 0.24f : 0.32f));
                cv.drawText(e.text, e.r.centerX(), e.r.centerY() + label.getTextSize() * 0.35f, label);
            }
        }
        if (!linkOk) {
            label.setTextSize(getHeight() * 0.035f);
            cv.drawText(badPassword ? "wrong password" : "no PC", getWidth() / 2f, getHeight() * 0.05f, label);
        }
    }

    private void drawJoystick(Canvas cv, El e) {
        Ptr p = null;
        for (int i = 0; i < ptrs.size(); i++) if (ptrs.valueAt(i).el == e) p = ptrs.valueAt(i);
        float w = e.r.width();
        if (p != null) {
            cv.drawCircle(p.ix, p.iy, w / 10f, stroke);
            cv.drawCircle(p.x, p.y, w / 5f, stroke);
        } else {
            cv.drawCircle(e.r.centerX(), e.r.centerY(), w / 2f - stroke.getStrokeWidth(), stroke);
        }
    }
}
