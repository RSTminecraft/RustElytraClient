package dev.rstminecraft.utils;

import dev.rstminecraft.RustElytraClient;

public class TimelinessCounter {
    private final int updateInterval;
    public int LastUpdateTick;
    private int count = 0;

    public TimelinessCounter(int updateInterval) {
        this.updateInterval = updateInterval;
        LastUpdateTick = -updateInterval - 1;
    }

    public void accumulate() {
        if (RustElytraClient.currentTick - LastUpdateTick > updateInterval) {
            count = 1;
            LastUpdateTick = RustElytraClient.currentTick;
        } else {
            count++;
        }
    }

    public int getCount() {
        return RustElytraClient.currentTick - LastUpdateTick > updateInterval ? 0 : count;
    }

    public void clear() {
        count = 0;
        LastUpdateTick = RustElytraClient.currentTick;
    }
}
