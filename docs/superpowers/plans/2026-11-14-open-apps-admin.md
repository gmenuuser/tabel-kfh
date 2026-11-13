# Open Apps + Owner-Only Admin Site Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove helper accounts; the iPhone and Android apps connect themselves with no sign-in; the site kfh.cubely.ru is the owner's alone (e-mail + password) and carries the admin panel (devices, «new devices» switch, app download links).

**Architecture:** The server gets a second kind of device: an *app device* with no user (`devices.user_id IS NULL`), created by an open `POST /devices/register`, with fixed rights `app` (everything except settings records and owner endpoints). The owner keeps a `web` device created by `/auth/login`. Migration 003 converts existing phones into app devices without touching their tokens, so installed apps keep working. Clients replace the sign-in screen with an automatic «connect» step and trim their settings.

**Tech Stack:** server Node 24 TS + Fastify 5 + PostgreSQL 17 (node:test); web React 18 + Vite (node:test); iOS SwiftUI + SwiftData (CI only, `.github/workflows/ios.yml`); Android Kotlin + Compose + Room (local Gradle + CI `android.yml`).

**Spec:** `docs/superpowers/specs/2026-11-14-open-apps-admin-design.md` (read it; its «Уточнения после проверки» section is binding).

## Global Constraints

- All user-facing text in Russian, exact strings below where given.
- Exact strings:
  - closed: «Владелец сейчас не подключает новые устройства»
  - revoked: «Это устройство отключено владельцем»
  - offline: «Нет связи с сервером»
  - buttons: «Повторить», «Подключиться снова»
  - old login: «Вход по логину отменён — скачайте новую версию приложения у владельца»
  - conflicts in apps: «Есть конфликты — их решает владелец»
  - admin switch: «Новые устройства: пускать / не пускать»
- Registration limit: at most **5** `POST /devices/register` per IP per **hour**.
- The `newDevicesAllowed` switch defaults to **true**.
- App device wire compatibility:
  - `/me` and `/devices/register` return `role: 'manager'` plus `kind: 'app'` for app devices, so that old app builds (which only know the six old roles) keep showing every module.
  - New clients treat `kind == 'app'` as the local role `app`: all modules, every entity writable except `farmSettings`, `coalSettings`, `livestockSettings`.
- Existing tokens are never rotated or invalidated by the migration, except helpers' `web` devices, which are revoked.
- One farm on the server: app devices attach to the oldest farm (`ORDER BY created_at LIMIT 1`).
- Device name on register: Android `"${Build.MANUFACTURER} ${Build.MODEL}"`; iOS the `utsname` machine id (e.g. `iPhone14,5`). Max 100 characters.
- Server tests: `cd server && npm run test:db` (once) then `npm test`. Web tests: `cd web && npm test`. Android: `cd android && ./gradlew testDebugUnitTest`. iOS: push and `bash scripts/ci.sh wait` (no Mac).
- Never run two implementers in one working tree. Commit at the end of each task with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Settings defaults on a fresh app device.** A brand-new phone's first sync leaves **zero** refused changes. Clients create default `CoalSettings`/`LivestockSettings`/`FarmSettings` rows lazily; with role `app` they must not insert them (iOS already guards with `SyncedSave.canWrite`). Tests: Task 5 and Task 7.
2. **Upgrade over an old build.** A phone with an old token and local data opens the new build straight to the main screen: no register call, no connect screen, local data and pending queue kept. Tests: Task 4 and Task 6.
3. **Revoked device does not re-register by itself.** After a 401 the app shows «Это устройство отключено владельцем» and waits for the button. Tests: Task 4 and Task 6.
4. **App token on owner endpoints.** An app token on `/devices`, `PATCH /farm`, `/backups*`, `/audit` and `/conflicts/:id/resolve` gets 403; on `/auth/login` with platform `ios`/`android` it gets 403 with the old-login text. Test: Task 2.
5. **Migration on real data.** Helpers' phones survive with names suffixed. The owner's phones become app devices. Helpers' web sessions are revoked. Helper users are deleted. Records keep `created_by_device`. Running 003 twice changes nothing. Test: Task 1.

---

### Task 1: Server — app devices, migration 003, registration

**Files:**
- Create: `server/sql/003_open_apps.sql`
- Modify: `server/src/auth.ts` (Session, sessionOf), `server/src/entities.ts` (role `app`), `server/src/app.ts` (register endpoint, `/me`, `PATCH /farm`), `server/src/sync.ts` (nullable userId in audit)
- Test: `server/test/open-apps.test.ts`

**Interfaces:**
- Produces:
  - `type Role = 'owner' | 'manager' | 'timesheet' | 'coal' | 'livestock' | 'viewer' | 'app'`.
  - `canWrite('app', e)` is true for every entity except `farmSettings`, `coalSettings`, `livestockSettings`; `canRead('app', e)` is true for every known entity.
  - `Session { userId: string | null; farmId; deviceId; role: Role }`: role `'app'` when `devices.user_id IS NULL`, else the membership role.
  - Endpoints:
    - `POST /devices/register` `{ platform: 'ios'|'android', name?: string }` → 201 `{ token, deviceId, farmId, role: 'manager', kind: 'app' }`.
      - 403 `{ error: 'Владелец сейчас не подключает новые устройства' }` when the switch is off.
      - 400 for any other platform.
      - 429 over the limit.
      - 503 `'Хозяйство ещё не создано'` with no farm.
    - `GET /me` adds `kind: 'app'|'owner'`; for app devices `role: 'manager'` and `userId: null`; for the owner `farm.newDevicesAllowed: boolean`.
    - `PATCH /farm` accepts `newDevicesAllowed: boolean` (owner only, as now).

- [ ] **Step 1: Write `003_open_apps.sql`, idempotent (it is re-run by the test).**
  - `farms.new_devices_allowed boolean NOT NULL DEFAULT true` (`ADD COLUMN IF NOT EXISTS`).
  - `devices.user_id` DROP NOT NULL; replace FK `devices_user_id_fkey` with `ON DELETE SET NULL` (`DROP CONSTRAINT IF EXISTS` + ADD).
  - Revoke non-owner `web` devices.
  - For non-revoked `ios`/`android` devices with a user:
    - if the user is a non-owner, append to the name `' — ' || COALESCE(NULLIF(u.name,''), u.login, u.email)`;
    - then set `user_id = NULL`.
  - `DELETE FROM invites`.
  - Delete users that have no `owner` membership (memberships cascade).
- [ ] **Step 2: Write failing tests in `server/test/open-apps.test.ts`.**
  - `регистрация устройства без входа`: register android → 201, `kind === 'app'`, `role === 'manager'`; `pullAll` works; push `hoursMark` → `applied`; push `coalSettings` → `rejected`.
  - `переключатель закрывает новые устройства`: owner `PATCH /farm {newDevicesAllowed:false}` → register → 403 with the exact text. Already registered devices still sync.
  - `не больше 5 регистраций в час с адреса`: the 6th → 429. Use the same `remoteAddress` in `inject`.
  - `миграция 003 переводит устройства`:
    - Seed the old shape: owner + helper (login `petrov`, name `Петров`) + memberships, helper devices ios and web, owner device ios, a record with `created_by_device`.
    - Re-run the SQL file.
    - Assert:
      - helper ios name ends with `' — Петров'` and `user_id IS NULL`, and its token still works on `/sync/pull`;
      - helper web device revoked;
      - helper user gone;
      - owner ios device `user_id IS NULL`;
      - owner user present;
      - record intact.
    - Re-run again: no changes.
- [ ] **Step 3: Run `npm test`; expect the new tests to FAIL.**
- [ ] **Step 4: Implement.**
  - `sessionOf` uses `LEFT JOIN memberships` and maps a null user to role `app`.
  - Register limiter: keep a separate per-hour map for registrations, and keep `allow` for login.
  - Audit inserts accept a null `userId`.
- [ ] **Step 5: Run `npm run typecheck && npm test`.** Expect all green, including the old suites, which still use `/auth/register` here.
- [ ] **Step 6: Commit** `feat(server): app devices register themselves; migration 003`.

### Task 2: Server — remove accounts, owner-only admin endpoints

**Files:**
- Modify: `server/src/app.ts`, `server/src/main.ts`, `server/test/helpers.ts`, `server/docker-compose.yml`, `server/deploy/deploy.sh` (drop `REGISTRATION_OPEN`), `server/README.md` (protocol: register, kinds, removed endpoints)
- Delete: `server/deploy/set-registration.sh`, `server/test/members.test.ts`
- Test: `server/test/admin.test.ts`; update the other suites to the new helpers

**Interfaces:**
- Consumes: Task 1 endpoints and the `app` role.
- Produces:
  - `/auth/login` `{ login|email, password, deviceName, platform }`: only an e-mail of a user with an `owner` membership and `platform === 'web'` → 200 as now.
    - A non-e-mail login, or any platform other than `web` → 403 `'Вход по логину отменён — скачайте новую версию приложения у владельца'`.
    - Wrong password → 401 as now.
  - Removed: `POST /auth/register`, all `/members*`, `AppOptions.registrationOpen`. `GET /auth/config` stays and always returns `{ registrationOpen: false }` for old builds.
  - Owner only:
    - `GET /devices` → `[{ id, name, platform, kind: 'app'|'owner', createdAt, lastSeenAt, revoked, current }]`;
    - `PATCH /devices/:id { name }` (1–100 chars after trim, 400 otherwise, 404 unknown/foreign/malformed);
    - `POST /devices/:id/revoke`;
    - `POST /conflicts/:id/resolve`.
  - `POST /auth/logout` stays for any device.
  - Test helpers:
    - `seedOwner(db): Promise<{ email, password }>` inserts user + farm + owner membership;
    - `owner(app, db): Promise<Device>` = seed + login with platform `web`;
    - `appDevice(app, platform = 'android'): Promise<Device>` = `/devices/register`.

- [ ] **Step 1: Rewrite `helpers.ts` and port `sync.test.ts`, `limit.test.ts` and `open-apps.test.ts`** to `owner()`/`appDevice()`; delete `members.test.ts`.
- [ ] **Step 2: Write failing `admin.test.ts`.**
  - `старый вход по логину и из приложения отклоняется`: helper-style login and owner e-mail with `platform:'ios'` → 403 with the exact text.
  - `приложение не открывает админские запросы`: an app token on `GET /devices`, `PATCH /devices/:id`, `POST /devices/:id/revoke`, `PATCH /farm`, `GET /backups`, `GET /audit`, `POST /conflicts/:id/resolve` → 403.
  - `владелец переименовывает и отключает устройство`: rename → listed with the new name; revoke → that app token gets 401 on `/sync/pull`.
  - `регистрации хозяйства больше нет`: `POST /auth/register` → 404; `GET /auth/config` → `{registrationOpen:false}`.
- [ ] **Step 3: Run `npm test`; expect the new tests to FAIL.**
- [ ] **Step 4: Implement** the removals and the owner-only guards. Remove `REGISTRATION_OPEN` from main/compose/deploy.
- [ ] **Step 5: Run `npm run typecheck && npm test`; expect all green.**
- [ ] **Step 6: Commit** `feat(server): owner-only site login and admin endpoints; helper accounts removed`.

### Task 3: Web — owner-only login and the admin page

**Files:**
- Modify: `web/src/pages/login.tsx`, `web/src/pages/home.tsx`, `web/src/pages/settings.tsx`, `web/src/app.tsx` (nav label), `web/src/lib/sync.ts` (any `register`/`members` calls)
- Move: `web/src/pages/app-links.tsx` (now used only by settings)
- Test: `web/test/roles.test.ts` (adjust if roles changed)

**Interfaces:**
- Consumes: Task 2 endpoints.

- [ ] **Step 1: Login page.**
  - Fields «Почта» and «Пароль», plus «Забыл пароль?» (reset flow as now).
  - Remove registration and `<AppLinks/>`.
  - Send `platform: 'web'`.
  - Show the server's error text as-is.
- [ ] **Step 2: Home.** Remove `<AppLinks/>`.
- [ ] **Step 3: Settings becomes «Админка».** Nav label «Админка».
  - Remove the «Помощники» section.
  - «Устройства»:
    - table of name (inline rename → `PATCH /devices/:id`), platform, «был(о) на связи» time, «Отключить» with a confirm;
    - revoked devices shown greyed and last;
    - checkbox «Новые устройства: пускать / не пускать» bound to `PATCH /farm {newDevicesAllowed}` and `/me` `farm.newDevicesAllowed`.
  - «Приложения»: `<AppLinks/>` plus a «Скопировать ссылку» button per link (`navigator.clipboard.writeText` of the absolute URL; on failure show the URL selected).
  - Keep «Хозяйство», sync, conflicts, refused changes and «Резервные копии».
- [ ] **Step 4: Run `cd web && npm test && npm run build`; expect green.** Check in the local stack:
  - `cd server && docker compose up -d --build`;
  - open http://localhost:8080, sign in as a seeded owner (seed with a one-off SQL);
  - toggle the switch, rename a device registered with curl, revoke it.
- [ ] **Step 5: Commit** `feat(web): owner-only site with the admin page`.

### Task 4: Android — connect instead of sign-in

**Files:**
- Modify: `android/app/src/main/kotlin/ru/kfh/tabel/session/SessionManager.kt`, `ui/AppRoot.kt`, the API client (add `register`), the role mapping (`Role` enum: add `APP`, wire mapping from `kind`)
- Create: `ui/signin/ConnectScreen.kt` (replaces `SignInScreen.kt`, which is deleted)
- Test: unit tests next to the existing SessionManager tests; update `androidTest/.../SignInHomeTest.kt` → `ConnectHomeTest.kt`

**Interfaces:**
- Produces:
  - `SessionManager.connect(): String?` replaces `signIn(login, password)`.
    - Registers with `platform='android'` and the device name.
    - Saves the token and starts the engine as sign-in did.
    - Returns null on success or the error text.
  - `SessionState` gains `NotConnected(reason: ConnectReason)` with `enum ConnectReason { Offline, Closed, Revoked }`; `SignedOut` is removed.

- [ ] **Step 1: Write failing unit tests (fake API).**
  - No token → `restore()` calls `connect()` automatically → `SignedIn`.
  - No network → `NotConnected(Offline)`, retried when the network returns.
  - 403 closed → `NotConnected(Closed)`.
  - Saved token + local data (upgrade) → `restore()` never calls register; local data and pending ops are kept.
  - 401 during sync → data wiped, `NotConnected(Revoked)`, no automatic register; `connect()` from the button registers again.
- [ ] **Step 2: Run `./gradlew testDebugUnitTest`; expect the new tests to FAIL.**
- [ ] **Step 3: Implement.**
  - Keep the «revoked» flag in the token store so it survives a restart.
  - `ConnectScreen` shows the exact texts from Global Constraints with «Повторить», or «Подключиться снова» for Revoked, and a spinner while connecting.
  - Map `kind == "app"` to `Role.APP`: all modules; `canWrite` false for the three settings entities.
- [ ] **Step 4: Run the unit tests and `./gradlew assembleDebug`; expect green.**
- [ ] **Step 5: Commit** `feat(android): connects itself, no sign-in`.

### Task 5: Android — trimmed settings

**Files:**
- Modify: `ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `LocalBackupScreen.kt`, `ui/AppRoot.kt` (routes)
- Delete: `ui/settings/HelpersScreen.kt`, `DevicesScreen.kt`, `ServerBackupsScreen.kt`, `FarmScreen.kt`, `ConflictsScreen.kt`, the settings entries for coal prices and drivers (the screens stay if the coal sale flow uses them for adding; only the settings rows go). Also drop `SessionManager.updateFarm`.
- Test: `androidTest/.../SettingsFlowTest.kt`, unit tests

- [ ] **Step 1: Write failing tests.**
  - Settings shows only the sync section, «Не принятые изменения», «Резервная копия» (save only) and «О приложении»/update.
  - With open conflicts the sync section shows «Есть конфликты — их решает владелец», which is not clickable.
  - No «Выйти».
  - Role `APP`: opening the coal sale and livestock screens on an empty store inserts no settings rows into the outbox (pending ops for `coalSettings`/`livestockSettings`/`farmSettings` = 0).
- [ ] **Step 2: Run the tests; expect them to FAIL.**
- [ ] **Step 3: Implement.** Remove restore-from-file from `LocalBackupScreen`. Default settings rows for role `APP` are read-only defaults and not inserted.
- [ ] **Step 4: Run `./gradlew testDebugUnitTest assembleDebug`; expect green.** Then push the branch and wait for CI `android.yml` (emulators) to go green.
- [ ] **Step 5: Commit** `feat(android): settings trimmed to sync, backup and about`.

### Task 6: iPhone — connect instead of sign-in

**Files:**
- Modify: `TabelKFH/Sync/SessionStore.swift`, `TabelKFH/Sync/APIClient.swift` (register), `TabelKFH/Domain/Roles.swift` (role `app`), the root view that switches on `SessionStore.state`
- Create: `TabelKFH/Features/SignIn/ConnectView.swift` (replaces `SignInView.swift`, which is deleted)
- Test: `TabelKFHTests/SessionStoreTests.swift`

**Interfaces:**
- Produces:
  - `SessionStore.connect() async` replaces `signIn(login:password:)`.
  - `State` gains `.notConnected(ConnectReason)` with `enum ConnectReason { case offline, closed, revoked }`; `.signedOut` is removed.
  - `Roles`: `"app"` → all modules; `canEdit(role: "app", .farmSettings/.coalSettings/.livestockSettings) == false`.

- [ ] **Step 1: Write failing tests (stub URLProtocol, as the existing SessionStore tests do).** Same five cases as Task 4 Step 1, with `platform='ios'`.
- [ ] **Step 2: Implement.**
  - Keep the revoked flag in UserDefaults `kfh.revoked`.
  - `ConnectView` uses the exact texts. Retry automatically when NWPathMonitor reports the network up, for Offline only.
- [ ] **Step 3: Push and run `bash scripts/ci.sh wait`.** Expect a green iOS run with the new tests passing.
- [ ] **Step 4: Commit** `feat(ios): connects itself, no sign-in` (commit before the push in Step 3; amend nothing).

### Task 7: iPhone — trimmed settings

**Files:**
- Modify: `TabelKFH/Features/Settings/SettingsView.swift`, `BackupView.swift` (save only)
- Delete: `HelpersView.swift`, `DevicesView.swift`, `ServerBackupsView.swift`, `ConflictsView.swift`; remove the farm-editing and «Продажа угля» settings sections and «Выйти»
- Test: `TabelKFHTests` plus the existing UI test of settings

- [ ] **Step 1: Write failing tests.**
  - Settings content as in Task 5 Step 1.
  - With role `app` on an empty store, calling `CoalSettingsService.current()` and the livestock settings path inserts nothing into the outbox.
- [ ] **Step 2: Implement.** `LivestockService` line ~118 and `SessionStore` FarmSettings creation (~378, ~448) must respect `SyncedSave.canWrite` like `CoalSettingsService` does.
- [ ] **Step 3: Push and run `bash scripts/ci.sh wait`; expect green.** Download artifacts (`bash scripts/ci.sh artifacts`) and look at the settings screenshot in `review-samples`.
- [ ] **Step 4: Commit** `feat(ios): settings trimmed to sync, backup and about`.

### Task 8: Release preparation (no deploy)

**Files:**
- Modify: `server/README.md` (release order), `docs/android-signing.md` only if the flow changed

- [ ] **Step 1: Server + web.**
  - `cd server && npm test`; `cd web && npm test && npm run build`.
  - Build the local stack.
  - Smoke test with curl: register → pull → push hoursMark; owner login with `platform:'web'`; app-platform login → 403 text.
- [ ] **Step 2: Android.** Build the signed release APK from the latest green CI run. Copy it to `Desktop/TabelKFH.apk` and record its versionCode.
- [ ] **Step 3: iPhone.** Copy the latest green IPA to `Desktop/TabelKFH.ipa`.
- [ ] **Step 4: Release notes for the owner.** Write the release order into the ledger:
  1. `bash server/deploy/deploy.sh kfh-new` (migration 003 runs on start);
  2. `deploy-android.sh --apk Desktop/TabelKFH.apk root@kfh-new`;
  3. copy the IPA to `/ios/` on the server;
  4. owner checks.

  **Deploy only after the owner says «выкладывай».**
- [ ] **Step 5: Commit** `docs: release order for open apps`.
