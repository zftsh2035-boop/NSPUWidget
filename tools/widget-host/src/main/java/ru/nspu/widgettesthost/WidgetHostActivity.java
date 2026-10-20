package ru.nspu.widgettesthost;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Disposable launcher substitute. This APK is never part of the distributed app. */
public final class WidgetHostActivity extends Activity {
    private AppWidgetHost host;
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        host = new AppWidgetHost(this, 2901);
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        SharedPreferences settings = getSharedPreferences("host", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(0xffe1e5eb);
        setContentView(root);
        ComponentName provider = new ComponentName("ru.nspu.schedule", "ru.nspu.schedule.ScheduleWidget");
        for (int number = 1; number <= 2; number++) {
            int height = number == 1 ? 320 : getIntent().getIntExtra("compact_height", 112);
            int id = settings.getInt("widget_" + number, -1);
            Bundle options = new Bundle();
            int width = Math.round(getResources().getDisplayMetrics().widthPixels /
                    getResources().getDisplayMetrics().density) - 16;
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height);
            if (id < 0) {
                id = host.allocateAppWidgetId();
                if (!manager.bindAppWidgetIdIfAllowed(id, provider, options)) {
                    TextView error = new TextView(this);
                    error.setText("BIND_APPWIDGET permission missing");
                    root.addView(error);
                    return;
                }
                settings.edit().putInt("widget_" + number, id).apply();
            } else manager.updateAppWidgetOptions(id, options);
            TextView label = new TextView(this);
            label.setText("Widget " + number + ": " + id);
            label.setTextSize(12);
            root.addView(label, new LinearLayout.LayoutParams(-1, dp(24)));
            AppWidgetHostView view = host.createView(this, id, manager.getAppWidgetInfo(id));
            root.addView(view, new LinearLayout.LayoutParams(-1, dp(height)));
        }
    }
    @Override public void onStart() { super.onStart(); host.startListening(); }
    @Override public void onStop() { host.stopListening(); super.onStop(); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int previousHeight = getIntent().getIntExtra("compact_height", 112);
        setIntent(intent);
        if (previousHeight != intent.getIntExtra("compact_height", 112)) recreate();
    }
}
