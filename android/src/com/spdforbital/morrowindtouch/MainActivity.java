package com.spdforbital.morrowindtouch;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Settings screen: PC address, password, look speed; starts/stops the overlay. */
public class MainActivity extends Activity {
    static final String PREFS = "mwt";

    private EditText host, password;
    private TextView status, sensLabel;
    private SeekBar sens;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int pad = dp(16);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Morrowind Touch");
        title.setTextSize(26);
        col.addView(title);

        TextView help = new TextView(this);
        help.setText("On-screen Morrowind controls on top of Steam Link.\n\n"
                + "1. On the PC, the Morrowind Touch helper must be running.\n"
                + "2. In Steam Link, turn its own touch controls off.\n"
                + "3. Press Start overlay, then switch to Steam Link and launch Morrowind.\n\n"
                + "The small ◐ button in the top-left corner hides the controls so you can use Steam Link itself.");
        help.setPadding(0, dp(8), 0, dp(16));
        col.addView(help);

        col.addView(label("PC address: its Wi-Fi IP at home, or its Tailscale/VPN address or host name on mobile data"));
        host = new EditText(this);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        host.setText(p.getString("host", ""));
        col.addView(host);

        col.addView(label("Password (the one in the PC helper's password.txt)"));
        password = new EditText(this);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(p.getString("password", ""));
        col.addView(password);

        sensLabel = label("");
        col.addView(sensLabel);
        sens = new SeekBar(this);
        sens.setMax(40);
        sens.setProgress(p.getInt("sens", 12));
        sens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) { updateSensLabel(); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        col.addView(sens);
        updateSensLabel();

        Button start = new Button(this);
        start.setText("Start overlay");
        start.setOnClickListener(v -> startOverlay());
        col.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop overlay");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            status.setText("Overlay stopped.");
        });
        col.addView(stop);

        Button steam = new Button(this);
        steam.setText("Open Steam Link");
        steam.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.valvesoftware.steamlink");
            if (i != null) startActivity(i); else status.setText("Steam Link is not installed.");
        });
        col.addView(steam);

        status = new TextView(this);
        status.setPadding(0, dp(12), 0, 0);
        status.setGravity(Gravity.START);
        col.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    private void updateSensLabel() {
        sensLabel.setText("Look speed: " + (sens.getProgress() + 2) / 10f);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void startOverlay() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("host", host.getText().toString().trim())
                .putString("password", password.getText().toString().trim())
                .putInt("sens", sens.getProgress())
                .apply();
        if (!Settings.canDrawOverlays(this)) {
            status.setText("Allow \"Display over other apps\" for Morrowind Touch, then come back and press Start again.");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Intent i = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        status.setText("Overlay running. Switch to Steam Link.");
    }
}
