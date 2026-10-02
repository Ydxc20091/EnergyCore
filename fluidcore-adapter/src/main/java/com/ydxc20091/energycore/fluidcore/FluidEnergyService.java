/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.fluidcore;

import com.ydxc20091.energycore.api.EnergyAccessContext;

/** Optional service available only when EnergyCore-FluidCore is installed. */
public interface FluidEnergyService {
    FluidEnergyTransaction open(EnergyAccessContext context);
}
