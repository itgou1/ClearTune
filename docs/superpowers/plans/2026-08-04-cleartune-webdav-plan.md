# ClearTune WebDAV Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add secure WebDAV sources that can be tested, browsed, recursively synchronized, and progressively enriched without exposing credentials.

**Architecture:** Store only a credential alias in Room; resolve secrets from Android Keystore immediately before an HTTP call. Keep URL/auth/XML behavior in small testable components. Run breadth-first `PROPFIND Depth: 1`, persist sync checkpoints, then enrich candidates with bounded range reads.

**Tech Stack:** OkHttp 5.3.0 and MockWebServer3, Android Keystore AES/GCM, XmlPullParser, Room, WorkManager, MediaMetadataRetriever over bounded temporary input, Compose.

## Global Constraints

- Require HTTPS by default. An HTTP source can be saved only after an unchecked-by-default confirmation and must retain a visible warning.
- Support Basic and RFC 7616 Digest authentication. Never preemptively send Basic credentials to a different origin or after redirect.
- Normalize and percent-encode URLs with OkHttp `HttpUrl`; never concatenate paths as strings.
- Use `PROPFIND` with `Depth: 1` only and recurse client-side. Treat each `href` as untrusted server input.
- Persist errors as sanitized codes/messages; never persist headers, passwords, auth challenges, or query tokens.

---

### Task 1: Encrypt WebDAV credentials with Android Keystore

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/remote/CredentialStore.kt`
- Create: `app/src/main/java/com/cleartune/app/data/remote/KeystoreCredentialStore.kt`
- Create: `app/src/main/java/com/cleartune/app/data/remote/EncryptedCredentialFile.kt`
- Modify: `app/src/main/java/com/cleartune/app/di/AppContainer.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/data/remote/KeystoreCredentialStoreTest.kt`

**Interfaces:**

```kotlin
data class WebDavCredential(val username: String, val password: CharArray)
interface CredentialStore {
    suspend fun put(alias: String, credential: WebDavCredential)
    suspend fun get(alias: String): WebDavCredential?
    suspend fun delete(alias: String)
}
```

- [ ] Write failing instrumentation tests for encrypt/decrypt, overwrite, delete, corrupted ciphertext, unique IV per write, and proving plaintext bytes are absent from the backing file.
- [ ] Run the focused instrumentation class; expect compilation failure.
- [ ] Implement AES-256-GCM with a non-exportable Keystore key named `cleartune.webdav.credentials.v1`; serialize UTF-8 lengths explicitly, zero temporary password arrays, and convert corruption to `CredentialUnavailable`.
- [ ] Re-run tests, then scan app private test files for the known password fixture; expect no plaintext match.
- [ ] Commit with `git commit -m "feat: protect WebDAV credentials"`.

### Task 2: Normalize source URLs and enforce transport policy

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/remote/WebDavUrlPolicy.kt`
- Modify: `app/src/main/java/com/cleartune/app/data/repository/SourceRepository.kt`
- Create: `app/src/test/java/com/cleartune/app/data/remote/WebDavUrlPolicyTest.kt`

**Interfaces:** `normalizeBaseUrl(raw, allowCleartext): HttpUrl`; base URL always ends in `/`; accepted schemes are `https` and explicitly enabled `http`; credentials/user-info and fragments are rejected.

- [ ] Write failing cases for Unicode/space paths, missing slash, dot segments, encoded traversal, user-info, fragment, non-HTTP schemes, HTTPS success, and HTTP opt-in/denial.
- [ ] Run the focused test; expect compilation failure.
- [ ] Implement canonicalization and same-origin checks used by redirects and auth. Source repository stores normalized URL, display name, `allowCleartext`, and credential alias in one transaction.
- [ ] Re-run the focused and full unit suites.
- [ ] Commit with `git commit -m "feat: validate WebDAV source URLs"`.

### Task 3: Implement Basic and Digest authentication

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/remote/DigestAuth.kt`
- Create: `app/src/main/java/com/cleartune/app/data/remote/WebDavAuthenticator.kt`
- Create: `app/src/test/java/com/cleartune/app/data/remote/DigestAuthTest.kt`
- Create: `app/src/test/java/com/cleartune/app/data/remote/WebDavAuthenticatorTest.kt`

**Interfaces:**

```kotlin
interface NonceSource { fun nextCnonce(): String }
fun digestAuthorization(challenge: String, method: String, requestUri: String, username: String, password: CharArray, nonceCount: Int, cnonce: String): String
```

- [ ] Write failing RFC vector tests for MD5, MD5-sess, SHA-256, SHA-256-sess, `qop=auth`, absent qop, stale nonce, escaped quoted fields, unsupported `auth-int`, and nonce-count increments. Add redirect tests proving auth is removed cross-origin.
- [ ] Run focused tests; expect compilation failure.
- [ ] Implement challenge parsing without regex-only tokenization, deterministic test nonce injection, Basic fallback only when offered, Digest preference over Basic, loop prevention after two failed challenges, and redacted `toString`/logging.
- [ ] Run the auth suite under MockWebServer and inspect recorded requests: first unauthenticated challenge, second authorized, no password in failure output.
- [ ] Commit with `git commit -m "feat: support WebDAV Basic and Digest auth"`.

### Task 4: Parse and execute WebDAV PROPFIND

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/remote/WebDavXmlParser.kt`
- Create: `app/src/main/java/com/cleartune/app/data/remote/WebDavClient.kt`
- Create: `app/src/test/resources/webdav/{multistatus.xml,mixed-status.xml,malformed.xml}`
- Create: `app/src/test/java/com/cleartune/app/data/remote/WebDavXmlParserTest.kt`
- Create: `app/src/test/java/com/cleartune/app/data/remote/WebDavClientTest.kt`

**Interfaces:**

```kotlin
data class WebDavEntry(val href: HttpUrl, val name: String, val isDirectory: Boolean, val sizeBytes: Long?, val etag: String?, val modified: Instant?)
interface WebDavClient {
    suspend fun list(source: MusicSource, directory: HttpUrl): List<WebDavEntry>
    suspend fun readRange(source: MusicSource, url: HttpUrl, start: Long, endInclusive: Long): RangeResponse
}
```

- [ ] Write failing parser/client cases for namespaces, 207 multi-status, per-property status, self entry removal, relative/absolute href, encoded characters, duplicate href, 401, 403, 404, 423, 5xx retry classification, malformed XML, cross-origin href, and `Depth: 1` request header.
- [ ] Run focused tests; expect red.
- [ ] Implement streaming XML parsing and client calls. Enforce same origin/base subtree, cap XML body at 8 MiB, close all bodies, set timeouts, classify errors, and use no automatic cleartext redirects.
- [ ] Re-run tests and verify MockWebServer recorded exactly one `PROPFIND` with XML body and `Depth: 1` per directory request.
- [ ] Commit with `git commit -m "feat: add WebDAV directory client"`.

### Task 5: Add source setup and remote directory browsing

**Files:**
- Create/modify: `app/src/main/java/com/cleartune/app/ui/feature/sources/**/.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/feature/folders/**/.kt`
- Modify: `app/src/main/java/com/cleartune/app/ui/navigation/ClearTuneNavHost.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/ui/WebDavSourceFlowTest.kt`

**Interfaces:** Setup fields are name, URL, username, password, cleartext confirmation; “Test connection” does not persist; “Save” persists only after validation. Folder nodes carry source ID and canonical href, not credentials.

- [ ] Write failing Compose tests for HTTPS happy path, HTTP warning/confirmation, invalid URL, authentication failure, timeout/retry, test success then save, edit without re-entering password, delete confirmation, and remote folder browse loading/error/empty states.
- [ ] Run the focused UI test; expect red.
- [ ] Implement screens and ViewModels with password visibility off by default, IME actions, sanitized messages, source cards, last-sync summary, and live directory browsing through `WebDavClient`.
- [ ] Re-run UI tests at 200% font and with TalkBack semantics; verify the HTTP warning remains visible on saved source detail.
- [ ] Commit with `git commit -m "feat: add WebDAV source setup UI"`.

### Task 6: Synchronize and enrich remote libraries

**Files:**
- Create: `app/src/main/java/com/cleartune/app/data/remote/WebDavSyncEngine.kt`
- Create: `app/src/main/java/com/cleartune/app/data/remote/RemoteMetadataReader.kt`
- Create: `app/src/main/java/com/cleartune/app/worker/WebDavSyncWorker.kt`
- Modify: `app/src/main/java/com/cleartune/app/data/repository/SourceRepository.kt`
- Create: `app/src/test/java/com/cleartune/app/data/remote/WebDavSyncEngineTest.kt`
- Create: `app/src/test/java/com/cleartune/app/data/remote/RemoteMetadataReaderTest.kt`
- Create: `app/src/androidTest/java/com/cleartune/app/worker/WebDavSyncWorkerTest.kt`

**Interfaces:** phase 1 records playable candidates from filename/folder/size/etag; phase 2 attempts bounded metadata reads; supported extension filter is shared with local scan; unique work name is `webdav-sync-<sourceId>`.

- [ ] Write failing tests for breadth-first recursion, self/parent/cycle avoidance, partial directory failure, ETag/size unchanged short-circuit, removed remote plus surviving local location, Range 206, ignored Range 200, unknown total, oversized response abort, and later enrichment replacing fallback metadata without changing track ID.
- [ ] Run focused tests; expect red.
- [ ] Implement checkpointed BFS and Room transactions per directory. Request bounded head/tail ranges needed for the format; cap temporary bytes at 4 MiB per file. If the server ignores Range, close without buffering and retain fallback metadata. Queue retry only for transient network/5xx failures.
- [ ] Re-run unit/worker tests; assert no `Depth: infinity`, no full-file metadata download, and no deletion outside the synchronized source.
- [ ] Commit with `git commit -m "feat: synchronize WebDAV music libraries"`.

## Phase Exit Verification

- [ ] Run `./gradlew.bat lintDebug testDebugUnitTest connectedDebugAndroidTest`.
- [ ] Manually test one Basic HTTPS server, one Digest HTTPS server, one explicitly enabled HTTP server, one server without Range, and one server with a broken subtree.
- [ ] Inspect Room and logs; confirm no username/password/auth header is present and partial sync errors name only sanitized relative folders.
- [ ] Run `rg -n "Depth: infinity|Authorization.*Log|password.*Log" app/src`; expect no matches.
