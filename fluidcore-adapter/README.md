# EnergyCore-FluidCore

Optional integration by **ydxc20091**. Install this plugin alongside CraftEngine, EnergyCore and FluidCore.

Use one coordinator for the complete operation:

```java
try (var tx = FluidEnergyTransaction.open(energy.context())) {
    long removedEnergy = energy.extractEnergy(100, tx.energy());
    long removedFluid = fluid.extract(water, 10, tx.fluids());
    if (removedEnergy == 100 && removedFluid == 10) {
        // Enlist the shared real-slot participant before consuming inputs or inserting outputs.
        tx.commit();
    }
}
```

Closing without commit restores all participants, including transfer budgets. Reuse one inventory participant throughout the operation. Transactions must finish synchronously in the same tick and ownership context. Commit notification errors indicate that state was already committed.
