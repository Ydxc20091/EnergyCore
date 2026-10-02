# EnergyCore Examples

Optional CraftEngine machines by **ydxc20091**. Install `EnergyCore-Examples` with EnergyCore and the required CraftEngine build. The bundled pack is installed after CraftEngine initializes its default resources and before its first pack scan; existing pack configuration is preserved.

Place a **Coal Generator**, **Energy Bank** and **Electric Furnace** next to one another. The generator sends energy to adjacent storage; the furnace pulls energy from adjacent storage. The bank has no ticker. Cross-region transfers are skipped on Folia.

- The generator consumes coal or charcoal, producing 12,800 FE per fuel at 80 FE per tick.
- The furnace processes raw iron into an iron ingot in 200 working ticks, consuming 20 FE per tick.
- The bank stores 100,000 FE and transfers up to 256 FE per tick in each direction.
- A battery stores 10,000 FE. Use it on a storage block to charge it; sneak to discharge it.

Open a machine with an empty hand. Input is on the left; furnace output is on the right. Left click, right click, drag and shift click are supported. Machine slots, fuel reserves, processing progress and energy survive breaking and replacing the machine. Unsupported inventory actions are cancelled. Hopper automation is outside these examples.

Successful configuration or CraftEngine reloads close machine interfaces; reopen a machine to use its updated configuration.

## Developer access

Use the EnergyCore provider service for energy. Example controllers additionally expose transactional logical slots:

```java
try (var transaction = EnergyTransaction.open(machine.accessContext())) {
    machine.setItem(0, fuel, transaction);
    transaction.commit();
}
```

Access the machine from its owning region. All changes must complete within the same tick. `setItem` validates input slots; `removeItem` can extract output. Each viewer receives a separate projection, while mutations operate on the controller's real inventory.
