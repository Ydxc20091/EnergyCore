package com.ydxc20091.energycore.api;

public enum EnergySide {
    DOWN, UP, NORTH, SOUTH, WEST, EAST;
    public EnergySide opposite() {
        return switch (this) {
            case DOWN -> UP; case UP -> DOWN;
            case NORTH -> SOUTH; case SOUTH -> NORTH;
            case WEST -> EAST; case EAST -> WEST;
        };
    }
}
