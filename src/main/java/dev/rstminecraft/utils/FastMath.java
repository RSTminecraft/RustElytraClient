package dev.rstminecraft.utils;

public final class FastMath {

    private static final double FLOOR_DOUBLE_D = 1_073_741_824.0;
    private static final int FLOOR_DOUBLE_I = 1_073_741_824;

    public static int fastFloor(final double v) {
        return (int) (v + FLOOR_DOUBLE_D) - FLOOR_DOUBLE_I;
    }

    public static int fastCeil(final double v) {
        return FLOOR_DOUBLE_I - (int) (FLOOR_DOUBLE_D - v);
    }
}
