/* Copyright 2016 Braden Farmer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.taskbar.service;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityOptions;
import android.app.SearchManager;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.SearchView;
import android.widget.TextView;

import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.activity.InvisibleActivity;
import com.farmerbb.taskbar.adapter.StartMenuAdapter;
import com.farmerbb.taskbar.util.AppEntry;
import com.farmerbb.taskbar.util.U;
import com.farmerbb.taskbar.view.ThemedGridView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class StartMenuService extends Service {

    private WindowManager windowManager;
    private LinearLayout layout;
    private ThemedGridView startMenu;
    private SearchView searchView;
    private TextView textView;

    private Handler handler;
    private Thread thread;

    private boolean hasSubmittedQuery = false;

    private int layoutId = R.layout.start_menu_left;

    private View.OnClickListener ocl = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            toggleStartMenu();
        }
    };
    
    private BroadcastReceiver toggleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            toggleStartMenu();
        }
    };
    
    private BroadcastReceiver hideReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            hideStartMenu();
        }
    };
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onCreate() {
        super.onCreate();

        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))
            drawStartMenu();
        else {
            SharedPreferences pref = U.getSharedPreferences(this);
            pref.edit().putBoolean("taskbar_active", false).apply();

            stopSelf();
        }
    }

    @SuppressLint("RtlHardcoded")
    private void drawStartMenu() {
        boolean shouldShowSearchBox = getResources().getConfiguration().keyboard != Configuration.KEYBOARD_NOKEYS;

        int focusableFlag = shouldShowSearchBox
                ? WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;

        // Initialize layout params
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                focusableFlag,
                PixelFormat.TRANSLUCENT);

        // Determine where to show the start menu on screen
        SharedPreferences pref = U.getSharedPreferences(this);
        switch(pref.getString("position", "bottom_left")) {
            case "bottom_left":
                layoutId = R.layout.start_menu_left;
                params.gravity = Gravity.BOTTOM | Gravity.LEFT;
                break;
            case "bottom_vertical_left":
                layoutId = R.layout.start_menu_vertical_left;
                params.gravity = Gravity.BOTTOM | Gravity.LEFT;
                break;
            case "bottom_right":
                layoutId = R.layout.start_menu_right;
                params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                break;
            case "bottom_vertical_right":
                layoutId = R.layout.start_menu_vertical_right;
                params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                break;
        }

        // Initialize views
        layout = (LinearLayout) View.inflate(this, layoutId, null);
        startMenu = (ThemedGridView) layout.findViewById(R.id.start_menu);

        boolean scrollbar = pref.getBoolean("scrollbar", false);
        startMenu.setFastScrollEnabled(scrollbar);
        startMenu.setFastScrollAlwaysVisible(scrollbar);

        searchView = (SearchView) layout.findViewById(R.id.search);
        if(shouldShowSearchBox) {
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    if(!hasSubmittedQuery) {
                        hasSubmittedQuery = true;

                        ListAdapter adapter = startMenu.getAdapter();
                        if(adapter.getCount() > 0) {
                            View view = adapter.getView(0, null, startMenu);
                            LinearLayout layout = (LinearLayout) view.findViewById(R.id.entry);
                            layout.performClick();
                        } else {
                            SharedPreferences pref = U.getSharedPreferences(StartMenuService.this);
                            if(pref.getBoolean("hide_taskbar", false))
                                LocalBroadcastManager.getInstance(StartMenuService.this).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_TASKBAR"));
                            else
                                LocalBroadcastManager.getInstance(StartMenuService.this).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_START_MENU"));

                            Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
                            intent.putExtra(SearchManager.QUERY, query);
                            if(intent.resolveActivity(getPackageManager()) != null)
                                startActivity(intent);
                            else {
                                Uri uri = new Uri.Builder()
                                        .scheme("https")
                                        .authority("www.google.com")
                                        .path("search")
                                        .appendQueryParameter("q", query)
                                        .build();

                                intent = new Intent(Intent.ACTION_VIEW);
                                intent.setData(uri);
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                                try {
                                    startActivity(intent);
                                } catch (ActivityNotFoundException e) { /* Gracefully fail */ }
                            }
                        }
                    }
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    refreshApps(newText);
                    return true;
                }
            });

            searchView.setOnQueryTextFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean b) {
                    if(!b) LocalBroadcastManager.getInstance(StartMenuService.this).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_START_MENU"));
                }
            });

            searchView.setImeOptions(EditorInfo.IME_ACTION_DONE);
        } else {
            FrameLayout searchViewLayout = (FrameLayout) layout.findViewById(R.id.search_view_layout);
            searchViewLayout.setVisibility(View.GONE);
        }

        textView = (TextView) layout.findViewById(R.id.no_apps_found);

        LocalBroadcastManager.getInstance(this).registerReceiver(toggleReceiver, new IntentFilter("com.farmerbb.taskbar.TOGGLE_START_MENU"));
        LocalBroadcastManager.getInstance(this).registerReceiver(hideReceiver, new IntentFilter("com.farmerbb.taskbar.HIDE_START_MENU"));

        handler = new Handler();
        refreshApps();

        windowManager.addView(layout, params);
    }
    
    private void refreshApps() {
        refreshApps(null);
    }

    private void refreshApps(final String query) {
        if(thread != null) thread.interrupt();

        handler = new Handler();
        thread = new Thread() {
            @Override
            public void run() {
                final PackageManager pm = getPackageManager();

                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                final List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);

                Collections.sort(list, new Comparator<ResolveInfo>() {
                    @Override
                    public int compare(ResolveInfo ai1, ResolveInfo ai2) {
                        return ai1.activityInfo.loadLabel(pm).toString().compareTo(ai2.activityInfo.loadLabel(pm).toString());
                    }
                });

                List<ResolveInfo> queryList;
                if(query == null)
                    queryList = list;
                else {
                    queryList = new ArrayList<>();
                    for(ResolveInfo appInfo : list) {
                        if(appInfo.loadLabel(pm).toString().toLowerCase().contains(query.toLowerCase()))
                            queryList.add(appInfo);
                    }
                }

                final List<AppEntry> entries = new ArrayList<>();
                for(ResolveInfo appInfo : queryList) {
                    entries.add(new AppEntry(
                            appInfo.activityInfo.applicationInfo.packageName,
                            new ComponentName(
                                    appInfo.activityInfo.applicationInfo.packageName,
                                    appInfo.activityInfo.name).flattenToString(),
                            appInfo.loadLabel(pm).toString(),
                            ((BitmapDrawable) appInfo.loadIcon(pm)).getBitmap(),
                            false));
                }

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        StartMenuAdapter adapter;
                        SharedPreferences pref = U.getSharedPreferences(StartMenuService.this);
                        if(pref.getString("start_menu_layout", "list").equals("grid")) {
                            startMenu.setNumColumns(3);
                            adapter = new StartMenuAdapter(StartMenuService.this, R.layout.row_alt, entries);
                        } else
                            adapter = new StartMenuAdapter(StartMenuService.this, R.layout.row, entries);

                        int position = startMenu.getFirstVisiblePosition();
                        startMenu.setAdapter(adapter);
                        startMenu.setSelection(position);

                        if(adapter.getCount() > 0)
                            textView.setText(null);
                        else
                            textView.setText(getString(R.string.press_enter));
                    }
                });
            }
        };

        thread.start();
    }
    
    private void toggleStartMenu() {
        if(layout.getVisibility() == View.GONE)
            showStartMenu();
       else
            hideStartMenu();
    }

    @SuppressWarnings("deprecation")
    private void showStartMenu() {
        layout.setOnClickListener(ocl);
        layout.setVisibility(View.VISIBLE);

        SharedPreferences pref = U.getSharedPreferences(this);
        if(!pref.getBoolean("on_home_screen", false)) {
            Intent intent = new Intent(this, InvisibleActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);

                startActivity(intent, ActivityOptions.makeBasic().setLaunchBounds(new Rect(display.getWidth(), display.getHeight(), display.getWidth() + 1, display.getHeight() + 1)).toBundle());
            } else
                startActivity(intent);
        }

        if(searchView.getVisibility() == View.VISIBLE) searchView.requestFocus();

        refreshApps();
    }

    private void hideStartMenu() {
        layout.setOnClickListener(null);
        layout.setVisibility(View.INVISIBLE);

        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("com.farmerbb.taskbar.FINISH_INVISIBLE_ACTIVITY"));

        layout.postDelayed(new Runnable() {
            @Override
            public void run() {
                layout.setVisibility(View.GONE);
                startMenu.setSelection(0);
                searchView.setQuery(null, false);
                hasSubmittedQuery = false;
            }
        }, 250);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if(layout != null) windowManager.removeView(layout);

        LocalBroadcastManager.getInstance(this).unregisterReceiver(toggleReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(hideReceiver);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if(layout != null) {
            windowManager.removeView(layout);
            drawStartMenu();
        }
    }
}