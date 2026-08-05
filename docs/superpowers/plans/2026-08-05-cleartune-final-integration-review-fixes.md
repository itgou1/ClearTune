# Final Integration Review Fixes

## Scope

Close the two remaining Task 6 review gaps without changing unrelated behavior:

1. Make source removal linearizable with download enqueue.
2. Validate embedded MP3 and FLAC artwork before it reaches the cache.

## Cycle 1: Source removal linearization

1. Add a controlled interleaving test that pauses removal immediately after its
   database transaction. Verify a download committed before that transaction is
   canceled, while an enqueue attempted after it fails and leaves no queued row.
2. Run the focused test and record the expected RED failure against the current
   effects-before-transaction ordering.
3. Move the removal authority marker, affected-download query, and durable state
   updates into one Room transaction. Return the committed result and perform
   WorkManager cancellation and file cleanup only from that result.
4. Make reconciliation retry external download cancellation for removed sources.
5. Run focused JVM/instrumentation compilation and tests to GREEN.

## Cycle 2: Embedded artwork validation

1. Add valid minimal JPEG/PNG fixtures plus rejection tests for arbitrary bytes,
   truncation, MIME spoofing, excessive dimensions, and excessive total pixels
   through both ID3 APIC and FLAC PICTURE paths.
2. Run the focused parser tests and record RED failures.
3. Add a shared decoder-free validator that sniffs JPEG/PNG, parses bounded
   headers, checks declared MIME and FLAC dimensions, and applies compressed-byte,
   per-dimension, and total-pixel limits with overflow-safe arithmetic.
4. Ensure only validated artwork objects can be sent to the artwork cache.
5. Run focused parser tests to GREEN.

## Verification and delivery

1. Run the fixed Gradle 9.5 full verification gate.
2. Run all four repository verification scripts, inspect the final diff, build
   the APK, and perform the requested adb verification.
3. Write the Task 6 round-3 report with RED/GREEN and final-gate evidence.
4. Commit exactly once with subject `fix: close final integration review gaps`.
