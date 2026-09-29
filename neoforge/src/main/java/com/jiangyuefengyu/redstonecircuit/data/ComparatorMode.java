package com.jiangyuefengyu.redstonecircuit.data;

/** Comparator output mode, mirroring the vanilla comparator's two modes. */
public enum ComparatorMode {
    COMPARE,
    SUBTRACT;

    public static ComparatorMode byName(String name, ComparatorMode fallback) {
        for (ComparatorMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return fallback;
    }
}
