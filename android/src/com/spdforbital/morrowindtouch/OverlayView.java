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
    static final int ESSENTIAL = 1, NORMAL = 2, ALWAYS = 3, TOPBAR = 4;

    // element kinds
    static final int KEY = 0, MOUSE_BTN = 1, JOY_LEFT = 2, JOY_RIGHT = 3, ATTACK = 4, TOGGLE_TOP = 5, HIDE = 6;

    static class El {
        final int kind, vis, bit, vx, vy, vsize, image;
        final String text;
        RectF r = new RectF();
        El(int kind, int vis, int image, String text, int vx, int vy, int vsize, int bit) {
            this.kind = kind; this.vis = vis; this.image = image; this.text = text;
            this.vx = vx; this.vy = vy; this.vsize = vsize; this.bit = bit;
        }
    }

    static class Ptr {
        El el;
        float ix, iy, x, y, lx, ly;
        long down;
    }

    private final Net net;
    private final float lookSpeed;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;

    private final List<El> els = new ArrayList<>();
    private final SparseArray<Bitmap> bitmaps = new SparseArray<>();
    private final SparseArray<Ptr> ptrs = new SparseArray<>();
    private boolean hidden, topVisible, inMenu, linkOk, badPassword;
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
        // our mods, in a column on the left (mirrors the inventory button on the right)
        els.add(new El(KEY, NORMAL, R.drawable.objectives, null, 0, 95, SIZE, Net.K_K));
        els.add(new El(KEY, ALWAYS, R.drawable.ledger, null, 0, 180, SIZE, Net.K_L));
        els.add(new El(KEY, NORMAL, R.drawable.compass, null, 0, 265, SIZE, Net.K_O));
        // top bar, opened with the toggle button (same order as OpenMW-Android; the mouse icon hides the overlay)
        els.add(new El(KEY, TOPBAR, R.drawable.third_person, null, TOP, 0, SIZE, Net.K_TAB));
        els.add(new El(KEY, TOPBAR, R.drawable.save, null, TOP * 2, 0, SIZE, Net.K_F5));
        els.add(new El(KEY, TOPBAR, R.drawable.journal, null, TOP * 3, 0, SIZE, Net.K_J));
        els.add(new El(KEY, TOPBAR, R.drawable.wait, null, TOP * 4, 0, SIZE, Net.K_T));
        hideBtn = new El(HIDE, TOPBAR, R.drawable.mouse, null, TOP * 5, 0, SIZE, 0);
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
    }

    void detach() {
        try { wm.removeView(this); } catch (Exception ignored) {}
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

    // ---- PC link --------------------------------------------------------------------------------
    @Override
    public void onMode(String m) {
        post(() -> {
            boolean menu = !(m.equals("none") || m.startsWith("Loading"));
            if (menu != inMenu) {
                inMenu = menu;
                releaseAll();
                invalidate();
            }
        });
    }

    @Override
    public void onLink(boolean ok) {
        post(() -> { linkOk = ok; if (ok) badPassword = false; invalidate(); });
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
            if (a == MotionEvent.ACTION_UP) setHidden(false);
            return true;
        }
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = ev.getX(idx), y = ev.getY(idx);
                El e = hit(x, y);
                if (e == null) break;
                if (e.kind == TOGGLE_TOP || e.kind == HIDE) {
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
                        net.addMouse((nx - p.lx) * 900f / size * lookSpeed, (ny - p.ly) * 900f / size * lookSpeed);
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
                    else if (p.el.kind == HIDE) { setHidden(true); return true; }
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
            return;
        }
        for (El e : els) {
            if (!visible(e)) continue;
            if (e.kind == JOY_LEFT || e.kind == JOY_RIGHT) {
                drawJoystick(cv, e);
            } else if (e.image != 0) {
                cv.drawBitmap(bitmaps.get(e.image), null, e.r, img);
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
