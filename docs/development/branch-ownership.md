# ClearTune Branch Ownership

All employee branches start from the same shared baseline commit. Paths not listed for an employee are read-only on that employee branch.

| Branch | Owned paths |
|---|---|
| `codex/employee-1-local-library` | `core/database/`, `data/local/`, `feature/library/` |
| `codex/employee-2-webdav-offline` | `core/network/`, `data/webdav/`, `data/download/`, `feature/sources/`, `feature/downloads/` |
| `codex/employee-3-playback-product` | `playback/`, `feature/player/`, `feature/playlists/`, `feature/settings/`, `app/` |

## Frozen shared paths

The following paths are frozen after the baseline commit:

- Root Gradle files, version catalog, wrapper, and `settings.gradle.kts`
- `core/model/`
- `core/contracts/`
- `core/designsystem/`
- `core/testing/`
- `scripts/`

Before pushing an employee branch, run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-branch-ownership.ps1
git diff --check
```

## Contract change procedure

1. Describe the old and proposed signatures, the reason, migration impact, and tests.
2. Create `codex/contract-<topic>` from the latest `main`.
3. Put contract, fake, and contract-test changes in one commit without feature implementation.
4. Obtain confirmation from all three owners and merge the contract branch to `main`.
5. Rebase or merge `main` into every employee branch and rerun the module checks.

Do not duplicate a shared interface inside an owned module to avoid this process.

## Integration order

1. Merge employee 1 to provide Room and local library implementations.
2. Rebase and merge employee 2 to connect WebDAV and offline writes.
3. Rebase employee 3, replace baseline assembly with real implementations, then merge.
4. Run full lint, unit, instrumentation, and release build gates on `main`.
