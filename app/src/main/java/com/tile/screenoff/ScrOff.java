package com.tile.screenoff;

public class ScrOff extends ScreenToggleActivity {

    @Override
    protected boolean targetState() {
        return true;
    }

    @Override
    protected String action() {
        return GlobalService.ACTION_SCR_OFF;
    }
}
