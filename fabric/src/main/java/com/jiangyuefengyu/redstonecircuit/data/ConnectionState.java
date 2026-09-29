package com.jiangyuefengyu.redstonecircuit.data;

/** Per-direction connection override, used by the redstone wrench. */
public enum ConnectionState {
    /** Let the network decide from the surrounding blocks. */
    AUTO,
    /** Force a connection. */
    ON,
    /** Force no connection. */
    OFF;

    public static ConnectionState byName(String name, ConnectionState fallback) {
        for (ConnectionState state : values()) {
            if (state.name().equalsIgnoreCase(name)) {
                return state;
            }
        }
        return fallback;
    }
}
