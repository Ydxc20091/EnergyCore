/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
/**
 * Paper integration contracts. Resolve and mutate blocks, items and player inventories on their
 * current owning thread. Item handlers operate on count-one detached copies; real transfers install
 * the replacement in the same transaction as energy mutation. Obtain the shared player inventory
 * participant through BukkitEnergyService.inventory for operations that touch multiple slots or
 * combine energy, fluids and items. Notifications are delivered only after committed state is saved
 * as an immutable snapshot; a notification exception never means that resources were rolled back.
 */
package com.ydxc20091.energycore.bukkit;
