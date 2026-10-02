# EnergyCore

A CraftEngine energy API for Paper and Folia, maintained by **ydxc20091**.

## Features

- Long energy amounts and capacities, with FE or RF display units.
- Transactional storage, simulation, rollback and atomic transfers.
- Shared per-tick transfer limits and configurable sided ports.
- CraftEngine energy blocks, battery items and protected persistent data.
- Optional shared fluid, energy and item transactions through EnergyCore-FluidCore.
- Separate examples: coal generator, energy bank, electric furnace and rechargeable battery.

## Installation

Download the [release files](https://github.com/Ydxc20091/EnergyCore/releases/tag/v0.1.0-SNAPSHOT).

Install **CraftEngine 26.10-SNAPSHOT** and `EnergyCore-0.1.0-SNAPSHOT.jar`.
Install `EnergyCore-Examples` for the example pack, or `EnergyCore-FluidCore` alongside FluidCore for joint transactions. Restart the server after installation.

The CraftEngine baseline is `26.10-20260929.192451-4`, SHA-256 `46ebe45f31f3e3f0965179a85cb1f308d8729f53281d6c64f4ef2af5c23d99f6`. API changes in other snapshots may require an update.

Configure display units and background work in `plugins/EnergyCore/config.yml`. CE battery items use `energycore:battery`; blocks use `energycore:storage`. See the [example pack](examples/src/main/resources/pack/configuration/examples.yml).

## Development

Depend on EnergyCore and use its API as a compile-only dependency. Resolve block and item providers through `EnergyCorePlugin.service()`; custom providers can register without extending the default storage.

```java
try (var tx = EnergyTransaction.open(source.context())) {
    long moved = EnergyTransfers.move(source, target, 1_000, tx);
    if (moved > 0) tx.commit();
}
```

Closing without commit rolls back changes and transfer budgets. Transactions must finish in the same tick and owning execution context; cross-region atomic transfers are rejected. Commit notification errors mean the state has already committed. Atomicity covers in-memory operations, not coordinated disk writes.

Build with Java 21:

```text
./gradlew distribution -PceJar=/path/to/craft-engine-paper-plugin-26.10-SNAPSHOT.jar -PfluidcoreApiJar=/path/to/fluidcore-api.jar
```

Artifacts are written to `dist/`. Source builds require the pinned CE JAR and the FluidCore API JAR for the optional integration module.

## Compatibility

| Server | Minecraft | Build | Java |
|---|---|---|---|
| Paper | 1.21.4 | 232 | 21 |
| Paper | 1.21.11 | 132 | 21 |
| Paper | 26.3 | 140 | 25 |
| Folia | 1.21.4 | 6 | 21 |
| Folia | 26.2 | 7 | 25 |

These builds passed startup, storage, items, example machines and restart persistence checks with the pinned CraftEngine snapshot. Paper 26.3 remains experimental. The plugin uses Java 21 bytecode.

## License

API: Apache-2.0. Implementation and examples: GPL-3.0-only. Bundled dependency licenses are included in [third-party notices](THIRD-PARTY-NOTICES.md).
