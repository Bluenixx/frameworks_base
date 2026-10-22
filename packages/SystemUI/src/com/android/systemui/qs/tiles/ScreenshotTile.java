/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.tiles;

import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_OTHER;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.widget.Button;
import androidx.annotation.Nullable;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import javax.inject.Inject;

public class ScreenshotTile extends QSTileImpl<QSTile.BooleanState> {
    public static final String TILE_SPEC = "screenshot";

    private final ScreenshotHelper mScreenshotHelper;
    private final Handler mHandler;

    @Inject
    public ScreenshotTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            ScreenshotHelper screenshotHelper) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager,
                metricsLogger, statusBarStateController, activityStarter, qsLogger);
        mScreenshotHelper = screenshotHelper;
        mHandler = mainHandler;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_screenshot_label);
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        mScreenshotHelper.takeScreenshot(SCREENSHOT_OTHER, mHandler, null);
    }

    @Override
    protected void handleUpdateState(QSTile.BooleanState state, Object arg) {
        state.value = false;
        state.state = Tile.STATE_INACTIVE;
        state.label = getTileLabel();
        state.contentDescription = state.label;
        state.icon = maybeLoadResourceIcon(R.drawable.ic_global_actions_screenshot);
        state.expandedAccessibilityClassName = Button.class.getName();
        state.handlesLongClick = false;
    }

    @Override
    public android.content.Intent getLongClickIntent() {
        return null;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.VIEW_UNKNOWN;
    }
}
