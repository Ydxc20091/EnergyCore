package com.ydxc20091.energycore.api;

public enum EnergyPortMode {
    NONE(false, false), INPUT(true, false), OUTPUT(false, true), BOTH(true, true);
    private final boolean receive;
    private final boolean extract;
    EnergyPortMode(boolean receive, boolean extract) { this.receive = receive; this.extract = extract; }
    public boolean canReceive() { return receive; }
    public boolean canExtract() { return extract; }
}
