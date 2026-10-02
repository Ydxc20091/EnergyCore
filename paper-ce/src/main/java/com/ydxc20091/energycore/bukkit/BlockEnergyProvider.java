/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import com.ydxc20091.energycore.api.*;
import org.bukkit.Location;
import java.util.Optional;
@FunctionalInterface
public interface BlockEnergyProvider { Optional<EnergyStorage> resolve(Location location, EnergySide side); }
