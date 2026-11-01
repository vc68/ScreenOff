package com.tile.screenoff;

public class ScrOn extends ScreenToggleActivity {

    @Override
    protected boolean targetState() {
        return false;
    }

    @Override
    protected String action() {
        return GlobalService.ACTION_SCR_OFF;
    }
}
