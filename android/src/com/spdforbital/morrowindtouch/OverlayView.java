package com.spdforbital.morrowindtouch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen transparent overlay with Morrowind controls, in the spirit of OpenMW for Android:
 * a floating walk stick on the left, swipe-to-look everywhere else, and big action buttons.
 * When the PC reports that a game menu is open it switches to a menu layout (D-pad + A/B/X/Y + tabs).
 */
public class OverlayView extends View implements Net.Listener {

    // what a control sends
    static final int PAD = 0, KEY = 1, TRIGGER_L = 2, TRIGGER_R = 3, TOGGLE = 4, HIDE = 5, SWAP = 6;

    static class Ctl {
        final String label; final int kind, bit;
        final float fx, fy, fr;   // position/radius as fractions of the screen height; x from the left (>=0) or right (<0)
        final boolean fromBottom;
        float cx, cy, r;
        boolean on;               // toggle state
        Ctl(String label, int kind, int bit, float fx, float fy, boolean fromBottom, float fr) {
            this.label = label; this.kind = kind; this.bit = bit;
            this.fx = fx; this.fy = fy; this.fromBottom = fromBottom; this.fr = fr;
        }
    }

    static class Ptr {
        int role;          // 0 none, 1 control, 2 stick, 3 look
        Ctl ctl;
        float ox, oy, lx, ly, x, y;
    }

    private final Net net;
    private final float lookSpeed;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;

    private final List<Ctl> game = new ArrayList<>(), menu = new ArrayList<>();
    private List<Ctl> current = game;
    private final SparseArray<Ptr> ptrs = new SparseArray<>();
    private boolean hidden, linkOk, badPassword, menuForced, menuAuto;
    private String mode = "none";
    private Ctl sneak;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    OverlayView(Context c, Net net, float lookSpeed) {
        super(c);
        this.net = net;
        this.lookSpeed = lookSpeed;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        text.setShadowLayer(4, 0, 0, Color.BLACK);
        buildLayouts();
    }

    // ---- layouts --------------------------------------------------------------------------------
    private void buildLayouts() {
        // gameplay: right-hand action cluster (x < 0 = measured from the right edge, fromBottom = y from the bottom)
        game.add(new Ctl("Attack", TRIGGER_R, 0, -0.17f, 0.20f, true, 0.135f));
        game.add(new Ctl("Use", PAD, Net.A, -0.47f, 0.14f, true, 0.095f));
        game.add(new Ctl("Jump", TRIGGER_L, 0, -0.14f, 0.50f, true, 0.085f));
        game.add(new Ctl("Weapon", PAD, Net.X, -0.42f, 0.42f, true, 0.072f));
        game.add(new Ctl("Magic", PAD, Net.Y, -0.70f, 0.13f, true, 0.072f));
        sneak = new Ctl("Sneak", TOGGLE, Net.LS, -0.35f, 0.68f, true, 0.065f);
        game.add(sneak);
        // top row
        String[] names = {"Journal", "Inventory\nMap", "Objectives", "Ledger", "Quick\nkeys", "Rest", "View", "Save", "Menu"};
        int[] kinds = {PAD, PAD, PAD, PAD, PAD, PAD, PAD, KEY, PAD};
        int[] bits = {Net.LB, Net.B, Net.UP, Net.DOWN, Net.BACK, Net.RB, Net.RS, Net.K_F5, Net.START};
        for (int i = 0; i < names.length; i++) {
            game.add(new Ctl(names[i], kinds[i], bits[i], -(0.09f + 0.165f * (names.length - 1 - i)), 0.085f, false, 0.072f));
        }
        game.add(new Ctl("◐", HIDE, 0, 0.06f, 0.06f, false, 0.05f));
        game.add(new Ctl("⇄", SWAP, 0, 0.17f, 0.06f, false, 0.05f));

        // menus: D-pad left, face buttons right, tabs/triggers on top
        float dx = 0.33f, dy = 0.36f, s = 0.17f;
        menu.add(new Ctl("▲", PAD, Net.UP, dx, dy + s, true, 0.085f));
        menu.add(new Ctl("▼", PAD, Net.DOWN, dx, dy - s, true, 0.085f));
        menu.add(new Ctl("◀", PAD, Net.LEFT, dx - s, dy, true, 0.085f));
        menu.add(new Ctl("▶", PAD, Net.RIGHT, dx + s, dy, true, 0.085f));
        float fx = -0.36f;
        menu.add(new Ctl("A\nSelect", PAD, Net.A, fx, dy - s, true, 0.085f));
        menu.add(new Ctl("B\nBack", PAD, Net.B, fx + s, dy, true, 0.085f));
        menu.add(new Ctl("X", PAD, Net.X, fx - s, dy, true, 0.075f));
        menu.add(new Ctl("Y", PAD, Net.Y, fx, dy + s, true, 0.075f));
        menu.add(new Ctl("LB ◀", PAD, Net.LB, 0.40f, 0.10f, false, 0.075f));
        menu.add(new Ctl("LT", TRIGGER_L, 0, 0.60f, 0.10f, false, 0.06f));
        menu.add(new Ctl("RT", TRIGGER_R, 0, -0.60f, 0.10f, false, 0.06f));
        menu.add(new Ctl("▶ RB", PAD, Net.RB, -0.40f, 0.10f, false, 0.075f));
        menu.add(new Ctl("Menu", PAD, Net.START, -0.12f, 0.10f, false, 0.06f));
        menu.add(new Ctl("◐", HIDE, 0, 0.06f, 0.06f, false, 0.05f));
        menu.add(new Ctl("⇄", SWAP, 0, 0.17f, 0.06f, false, 0.05f));
    }

    private void place(int w, int h) {
        for (List<Ctl> l : new List[]{game, menu}) {
            for (Ctl c : l) {
                c.r = c.fr * h;
                c.cx = c.fx >= 0 ? c.fx * h : w + c.fx * h;
                c.cy = c.fromBottom ? h - c.fy * h : c.fy * h;
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!hidden) place(w, h);
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
            mode = m;
            boolean inMenu = !(m.equals("none") || m.startsWith("Loading"));
            if (inMenu != menuAuto) {
                menuAuto = inMenu;
                menuForced = false;
                switchTo(inMenu ? menu : game);
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

    private void switchTo(List<Ctl> l) {
        if (current == l) return;
        releaseAll();
        current = l;
        invalidate();
    }

    // ---- touch ----------------------------------------------------------------------------------
    private Ctl hit(float x, float y) {
        Ctl best = null;
        float bestD = Float.MAX_VALUE;
        for (Ctl c : current) {
            float d = (float) Math.hypot(x - c.cx, y - c.cy);
            if (d <= c.r * 1.2f && d < bestD) { best = c; bestD = d; }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        int idx = e.getActionIndex();
        if (hidden) {
            if (a == MotionEvent.ACTION_UP) setHidden(false);
            return true;
        }
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx), y = e.getY(idx);
                Ptr p = new Ptr();
                p.x = p.lx = p.ox = x;
                p.y = p.ly = p.oy = y;
                Ctl c = hit(x, y);
                if (c != null) {
                    if (c.kind == HIDE) { setHidden(true); return true; }
                    if (c.kind == SWAP) {
                        menuForced = true;
                        switchTo(current == game ? menu : game);
                        return true;
                    }
                    if (c.kind == TOGGLE) c.on = !c.on;
                    p.role = 1;
                    p.ctl = c;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                } else if (current == game) {
                    p.role = (x < getWidth() * 0.40f && y > getHeight() * 0.28f) ? 2 : 3;
                }
                ptrs.put(e.getPointerId(idx), p);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Ptr p = ptrs.get(e.getPointerId(i));
                    if (p == null) continue;
                    p.x = e.getX(i);
                    p.y = e.getY(i);
                    if (p.role == 3) {
                        net.addMouse((p.x - p.lx) * lookSpeed, (p.y - p.ly) * lookSpeed);
                    }
                    p.lx = p.x;
                    p.ly = p.y;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                ptrs.remove(e.getPointerId(idx));
                break;
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

    /** Recompute what is held from the active fingers and hand it to the network thread. */
    private void pushState() {
        int buttons = 0, keys = 0;
        float lt = 0, rt = 0, lx = 0, ly = 0;
        float stickR = getHeight() * 0.14f;
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr p = ptrs.valueAt(i);
            if (p.role == 1 && p.ctl != null) {
                Ctl c = p.ctl;
                if (c.kind == PAD) buttons |= 1 << c.bit;
                else if (c.kind == KEY) keys |= 1 << c.bit;
                else if (c.kind == TRIGGER_L) lt = 1;
                else if (c.kind == TRIGGER_R) rt = 1;
            } else if (p.role == 2 && stickR > 0) {
                float dx = (p.x - p.ox) / stickR, dy = (p.y - p.oy) / stickR;
                float m = (float) Math.hypot(dx, dy);
                if (m > 1) { dx /= m; dy /= m; }
                lx = dx;
                ly = dy;
            }
        }
        if (current == game && sneak.on) buttons |= 1 << Net.LS;
        net.lx = lx; net.ly = ly; net.lt = lt; net.rt = rt;
        net.buttons = buttons;
        net.keys = keys;
    }

    // ---- drawing --------------------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas cv) {
        if (hidden) {
            float s = getWidth();
            drawButton(cv, s / 2, s / 2, s * 0.42f, "◐", false, s * 0.35f);
            return;
        }
        float h = getHeight();
        for (Ctl c : current) {
            boolean held = c.kind == TOGGLE ? c.on : isHeld(c);
            drawButton(cv, c.cx, c.cy, c.r, c.label, held, c.r * (c.label.length() > 7 ? 0.30f : 0.40f));
        }
        // walk stick
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr p = ptrs.valueAt(i);
            if (p.role != 2) continue;
            float R = h * 0.14f;
            fill.setColor(Color.argb(60, 0, 0, 0));
            cv.drawCircle(p.ox, p.oy, R, fill);
            ring.setColor(Color.argb(140, 255, 255, 255));
            cv.drawCircle(p.ox, p.oy, R, ring);
            float dx = p.x - p.ox, dy = p.y - p.oy, m = (float) Math.hypot(dx, dy);
            if (m > R) { dx *= R / m; dy *= R / m; }
            fill.setColor(Color.argb(150, 222, 184, 107));
            cv.drawCircle(p.ox + dx, p.oy + dy, R * 0.42f, fill);
        }
        // link status next to the swap button
        dot.setColor(linkOk ? Color.rgb(90, 200, 90) : Color.rgb(220, 70, 60));
        cv.drawCircle(h * 0.25f, h * 0.06f, h * 0.012f, dot);
        text.setTextSize(h * 0.028f);
        text.setTextAlign(Paint.Align.LEFT);
        cv.drawText(linkOk ? "PC" : (badPassword ? "wrong password" : "no PC"), h * 0.27f, h * 0.07f, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private boolean isHeld(Ctl c) {
        for (int i = 0; i < ptrs.size(); i++) if (ptrs.valueAt(i).ctl == c) return true;
        return false;
    }

    private void drawButton(Canvas cv, float x, float y, float r, String label, boolean held, float ts) {
        fill.setColor(held ? Color.argb(150, 222, 184, 107) : Color.argb(70, 0, 0, 0));
        cv.drawCircle(x, y, r, fill);
        ring.setColor(held ? Color.argb(230, 255, 230, 170) : Color.argb(150, 230, 215, 180));
        cv.drawCircle(x, y, r, ring);
        text.setTextSize(ts);
        String[] lines = label.split("\n");
        float lh = ts * 1.1f;
        float y0 = y - (lines.length - 1) * lh / 2 + ts * 0.35f;
        for (int i = 0; i < lines.length; i++) cv.drawText(lines[i], x, y0 + i * lh, text);
    }
}
