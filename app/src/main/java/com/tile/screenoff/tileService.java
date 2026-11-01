package com.tile.screenoff;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷开关磁贴：点一下就在“息屏运行 / 退出息屏”之间切换。
 * 旧版本不管当前状态，点一下永远是去息屏，非常反直觉。
 */
public class tileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (isAccessibilityEnabled()) {
            sendBroadcast(new Intent(GlobalService.ACTION_TOGGLE));
        } else {
            openActivity();
        }
        refreshTile();
    }

    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        SharedPreferences state = getSharedPreferences("state", MODE_PRIVATE);
        boolean special = state.getInt("last", GlobalService.STATE_ON) == GlobalService.STATE_SPECIAL;
        tile.setState(special ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(special ? R.string.scron : R.string.shortcutoff));
        tile.updateTile();
    }

    private boolean isAccessibilityEnabled() {
        String setting = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return setting != null && setting.contains(getPackageName());
    }

    private void openActivity() {
        Intent intent = new Intent(this, ScrOff.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
