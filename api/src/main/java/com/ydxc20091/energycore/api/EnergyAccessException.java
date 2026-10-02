package com.ydxc20091.energycore.api;

/** An operation does not own its storage or crossed its permitted execution tick. */
public class EnergyAccessException extends IllegalStateException {
    public EnergyAccessException(String message) { super(message); }
    public EnergyAccessException(String message, Throwable cause) { super(message, cause); }
}
