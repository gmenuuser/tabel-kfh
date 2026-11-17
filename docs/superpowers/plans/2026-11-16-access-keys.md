# Ключи доступа — план реализации

> **Для агентов-исполнителей:** ОБЯЗАТЕЛЬНЫЙ ПОД-СКИЛЛ: `superpowers:subagent-driven-development` (рекомендуется) или `superpowers:executing-plans`. Шаги отмечать чекбоксами (`- [ ]`).

**Цель:** приложения подключаются к хозяйству 4-значным кодом, который админ выдаёт человеку с именем и ролью; вход по почте и паролю остаётся только админу на сайте, а публичная страница показывает лишь ссылки на скачивание.

**Архитектура:** ключ (`access_keys`) — это приглашение; при первом вводе кода сервер в одной транзакции заводит пользователя без почты и пароля, членство с ролью ключа и устройство с `user_id`. Дальше права, аудит и синхронизация работают без изменений: роль читается из `memberships`. Флаг `KEYS_REQUIRED` даёт трёхшаговый выпуск, при котором старые телефоны не ломаются.

**Стек:** Node 24 + TypeScript (без сборки), Fastify 5, `pg`, Postgres 17; сайт — React 18 + Vite; Android — Kotlin + Room; iPhone — SwiftUI. Новых зависимостей нет.

**Спека:** `docs/superpowers/specs/2026-11-16-access-keys-design.md`

## Глобальные ограничения

- Роли, которые выдаёт ключ: `manager | timesheet | coal | livestock | viewer`. **`owner` ключом не выдаётся никогда** — телефон не меняет настройки хозяйства, цену угля и настройки скота.
- Код: ровно 4 цифры, одноразовый, живёт по умолчанию 24 часа (админ может 1–30 дней), в базе только SHA-256 с «перцем» (`KEYS_PEPPER`).
- Лимиты подключения: 20 попыток в час с одного IP (затем IP отдыхает 24 часа); 200 неверных попыток в час на хозяйство → пауза 30 минут и запись в журнал.
- Миграции — новые файлы `server/sql/NNN_*.sql`; применённые не редактируются. Следующий номер — `004`.
- Тексты ошибок и интерфейс — по-русски, в стиле существующих.
- Никакого деплоя без явного «выкладывай».
- Сервер: `npm test` (нужна база из `npm run test:db`), сайт: `npm test` и `npm run build`.

## Что проверять ревьюеру (и где это закреплено тестом)

1. **Ни одного активного ключа, а код вводят** — человек видит «Код неверный», а не 500 и не пустой экран (Task 3, шаг с текстами).
2. **Двойное нажатие «Подключиться»** — второй запрос с тем же кодом не заводит второго человека и второе устройство (Task 3, тест на два запроса подряд).
3. **Роль сузили, пока телефон на связи** — следующий `/me` отдаёт новую роль, а запись в закрытый модуль сервер отклоняет (Task 4, тест на `PATCH /people/:id` + push).
4. **Переходный период `KEYS_REQUIRED=false`** — работает и старый путь без кода, и новый с кодом (Task 3, отдельный тест).
5. **Отзыв безымянных устройств** — сессия владельца на сайте (`platform = web`) остаётся живой, гаснут только телефоны (Task 5).
6. **Мусор вместо кода** (`12`, `abcd`, пусто) — 400 с понятным текстом, без пометки «Код неверный» (Task 3).

---

### Task 1: Схема ключей и человек без пароля

**Файлы:**
- Создать: `server/sql/004_access_keys.sql`
- Изменить: `server/test/helpers.ts:13-14` (список таблиц для TRUNCATE)
- Тест: `server/test/keys.test.ts` (новый)

**Интерфейсы:**
- Готовит для следующих задач: таблицу `access_keys (id, farm_id, person_name, role, code_hash, expires_at, created_at, created_by, used_at, user_id, revoked_at)` и ослабленный `CHECK` у `users`, разрешающий человека без почты и логина, но с именем.

- [ ] **Step 1: Написать падающий тест**

```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness, owner as seedFarm } from './helpers.ts';

test('схема: ключ и человек без почты и логина', async () => {
  const h = await harness();
  try {
    const farm = await seedFarm(h.app, h.db);
    const u = await h.db.query("INSERT INTO users (name, password_hash) VALUES ('Иван', '') RETURNING id");
    await h.db.query('INSERT INTO memberships (farm_id, user_id, role) VALUES ($1, $2, $3)', [farm.farmId, u.rows[0].id, 'timesheet']);
    const k = await h.db.query(
      `INSERT INTO access_keys (farm_id, person_name, role, code_hash, expires_at)
       VALUES ($1, 'Пётр', 'coal', 'hash', now() + interval '1 day') RETURNING id`, [farm.farmId]);
    assert.ok(k.rows[0].id);
    await assert.rejects(h.db.query("INSERT INTO access_keys (farm_id, person_name, role, code_hash, expires_at) VALUES ($1,'X','owner','h2', now())", [farm.farmId]));
  } finally { await h.close(); }
});
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `cd server && npm test -- --test-name-pattern="схема: ключ"` — ожидается `relation "access_keys" does not exist`.

- [ ] **Step 3: Написать `server/sql/004_access_keys.sql`**

```sql
-- Ключи доступа: админ выдаёт человеку 4-значный код; на первом вводе сервер заводит человека и устройство.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_or_login;
ALTER TABLE users ADD CONSTRAINT users_email_or_login CHECK (email IS NOT NULL OR login IS NOT NULL OR name <> '');

CREATE TABLE IF NOT EXISTS access_keys (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id     uuid NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    person_name text NOT NULL,
    role        text NOT NULL CHECK (role IN ('manager', 'timesheet', 'coal', 'livestock', 'viewer')),
    code_hash   text NOT NULL,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    used_at     timestamptz,
    user_id     uuid REFERENCES users(id) ON DELETE CASCADE,
    revoked_at  timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS access_keys_active ON access_keys (farm_id, code_hash)
    WHERE used_at IS NULL AND revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS access_keys_farm ON access_keys (farm_id, created_at DESC);
```

Добавить `access_keys` в `TRUNCATE` в `server/test/helpers.ts`.

- [ ] **Step 4: Прогнать тест — зелёный**

Run: `cd server && npm test -- --test-name-pattern="схема: ключ"` — ожидается `pass 1`.

- [ ] **Step 5: Коммит**

```bash
git add server/sql/004_access_keys.sql server/test/helpers.ts server/test/keys.test.ts
git commit -m "feat(server): access keys schema, a user without a password"
```

---

### Task 2: Генерация и проверка кода

**Файлы:**
- Создать: `server/src/keys.ts`
- Тест: `server/test/keys.test.ts` (дописать)

**Интерфейсы:**
- `export const CODE_LENGTH = 4`
- `export function normalCode(v: unknown): string | null` — `null`, если не ровно 4 цифры.
- `export function codeHash(farmId: string, code: string): string` — `sha256(`${process.env.KEYS_PEPPER ?? ''}:${farmId}:${code}`)`.
- `export type Redeem = { ok: true; key: { id: string; role: string; personName: string; userId: string | null } } | { ok: false; reason: 'wrong' | 'expired' | 'used' }`
- `export async function redeemKey(tx: { query: Db['query'] }, farmId: string, code: string): Promise<Redeem>`
- `export async function createKey(tx: { query: Db['query'] }, farmId: string, createdBy: string, o: { personName: string; role: string; days: number; userId?: string }): Promise<{ id: string; code: string }>` — код перебирается до 20 раз, пока не вставится (уникальный индекс на активные коды).

- [ ] **Step 1: Написать падающие тесты**

```ts
test('код: четыре цифры, свой у каждого ключа', async () => {
  const h = await harness(); const farm = await seedFarm(h.app, h.db);
  try {
    const a = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Иван', role: 'timesheet', days: 1 });
    const b = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Пётр', role: 'coal', days: 1 });
    assert.match(a.code, /^\d{4}$/); assert.notEqual(a.code, b.code);
    assert.equal(normalCode('12'), null); assert.equal(normalCode('abcd'), null);
  } finally { await h.close(); }
});

test('код: использованный и просроченный различаются', async () => {
  // redeemKey → ok; второй раз → reason 'used'; ключ с expires_at в прошлом → 'expired'; '0000' → 'wrong'
});
```

- [ ] **Step 2: Тест падает** — Run: `cd server && npm test -- --test-name-pattern="код:"` (модуль не найден).

- [ ] **Step 3: Написать `server/src/keys.ts`** с сигнатурами выше. Код: `String(randomInt(0, 10_000)).padStart(4, '0')` из `node:crypto`.

- [ ] **Step 4: Тесты зелёные** — Run: `cd server && npm test -- --test-name-pattern="код:"`.

- [ ] **Step 5: Коммит** — `git commit -m "feat(server): 4-digit key codes, hashed with a pepper"`.

---

### Task 3: Подключение по коду вместо саморегистрации

**Файлы:**
- Изменить: `server/src/app.ts:11-21` (`AppOptions`), `server/src/app.ts:37-63` (лимитеры), `server/src/app.ts:147-160` и `193-204` (`POST /devices/register`), `server/test/helpers.ts:22-38` (`HarnessOptions`, `harness`)
- Тест: `server/test/keys.test.ts`

**Интерфейсы:**
- `AppOptions` получает `keysRequired?: boolean` и `keysPepper?: string` (в `main.ts` — `process.env.KEYS_REQUIRED === 'true'` и `process.env.KEYS_PEPPER`). `HarnessOptions` получает те же два поля и передаёт их в `buildApp`.
- Запрос: `POST /devices/register` с `{ platform: 'ios'|'android', name?: string, code?: string }`.
- Ответ 201: `{ token, deviceId, farmId, role, kind: 'app' }`, где `role` — роль ключа.
- Ошибки: 400 «Укажите 4-значный код», 403 «Код неверный» / «Код просрочен» / «Код уже использован — попросите новый», 429 «Слишком много попыток, попробуйте позже».
- Лимитеры: `allowPair = limiter(20, 3_600_000)` по `pair:${req.ip}`, при превышении адрес не пускается 24 часа (отдельная карта `blockedUntil`); `wrongPair = limiter(200, 3_600_000)` на хозяйство, при превышении — 30 минут паузы и `audit(null, 'keys.bruteforce', { farmId })`.
- Журнал: `device.pair` с `{ personName, platform }`.

- [ ] **Step 1: Тесты**

```ts
test('подключение по коду заводит человека, членство и устройство', async () => {
  const h = await harness({ keysRequired: true });
  const farm = await seedFarm(h.app, h.db);
  const key = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Иван', role: 'timesheet', days: 1 });
  const r = await h.app.inject({ method: 'POST', url: '/devices/register', remoteAddress: '10.1.0.1',
    payload: { platform: 'android', name: 'Honor', code: key.code } });
  assert.equal(r.statusCode, 201);
  assert.equal(r.json().role, 'timesheet');
  const u = (await h.db.query('SELECT u.name, m.role FROM devices d JOIN users u ON u.id = d.user_id JOIN memberships m ON m.user_id = u.id')).rows[0];
  assert.deepEqual(u, { name: 'Иван', role: 'timesheet' });
});

test('один код — одно устройство: второй запрос отвечает «Код уже использован»', ...);
test('без кода при KEYS_REQUIRED — 400, мусор — 400', ...);
test('при KEYS_REQUIRED=false работает и старый путь, и код', ...);
test('лимит: 21-я попытка с одного IP — 429', ...);
test('лимит: 201 неверная попытка за час останавливает хозяйство на 30 минут и пишет в журнал', ...);
```

- [ ] **Step 2: Тесты падают** — Run: `cd server && npm test -- --test-name-pattern="подключение по коду"`.

- [ ] **Step 3: Реализовать в `app.ts`**

Внутри `inTransaction`: `redeemKey` → если `ok`, найти или создать пользователя (`INSERT INTO users (name, password_hash) VALUES ($1, '')`), `INSERT ... ON CONFLICT (farm_id, user_id) DO UPDATE SET role = EXCLUDED.role` в `memberships`, `issueDevice(...)` с `user_id`, `UPDATE access_keys SET used_at = now(), user_id = $2 WHERE id = $1`. Одноразовость держит транзакция и `used_at IS NULL` в запросе; второй запрос видит `used`.

- [ ] **Step 4: Тесты зелёные** — `cd server && npm test` (весь набор, включая старые `open-apps.test.ts`).

- [ ] **Step 5: Коммит** — `git commit -m "feat(server): pair devices with an admin-issued code"`.

---

### Task 4: Админские запросы ключей и людей

**Файлы:**
- Изменить: `server/src/app.ts` (после блока `/devices/:id/revoke`, строка ~256)
- Тест: `server/test/keys.test.ts`

**Интерфейсы:**
- `GET /keys` → `{ keys: { id, personName, role, status: 'free'|'paired'|'expired'|'revoked', expiresAt, createdAt, userId, deviceName?, devicePlatform? }[] }`
- `POST /keys` `{ personName, role, days }` → 201 `{ id, code }` (единственный раз).
- `POST /people/:userId/keys` `{ days, role? }` → 201 `{ id, code }`, человека не дублирует.
- `POST /keys/:id/reissue` `{ days }` → 201 `{ id, code }`, устройства этого человека гаснут.
- `POST /keys/:id/revoke` → `{ ok: true }`, устройства человека гаснут.
- `DELETE /keys/:id` → `{ ok: true }`.
- `GET /people` → `{ people: { id, name, role, devices, lastSeenAt }[] }`
- `PATCH /people/:id` `{ name?, role? }` → `{ ok: true }`.
- Все — `preHandler: [signedIn, ownerOnly]`.
- Журнал: `key.create`, `key.reissue`, `key.revoke`, `person.update` с именем и ролью.

- [ ] **Step 1: Тесты** — по одному на запрос; отдельно: «телефон после смены роли пишет только в свой модуль» (подключить устройство, `PATCH /people/:id {role:'coal'}`, затем `POST /sync/push` с `hoursMark` → `rejected`, с `coalSale` → `applied`), и «чужой не видит ключи» (устройство приложения на `/keys` → 403).

- [ ] **Step 2: Падают** — Run: `cd server && npm test -- --test-name-pattern="ключи"`.

- [ ] **Step 3: Реализовать запросы** в `app.ts`; отзыв человека — `UPDATE devices SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`.

- [ ] **Step 4: Зелёные** — `cd server && npm test`.

- [ ] **Step 5: Коммит** — `git commit -m "feat(server): issue, reissue and revoke access keys; edit people"`.

---

### Task 5: Отключение безымянных устройств

**Файлы:**
- Изменить: `server/src/app.ts` (рядом с `/devices/:id/revoke`), `web/src/pages/devices.tsx` (позже — Task 7)
- Тест: `server/test/keys.test.ts`

**Интерфейсы:**
- `POST /devices/revoke-anonymous` `{ confirm: true }` → `{ revoked: number }`; без `confirm` — 400. Гаснут только `user_id IS NULL` и `revoked_at IS NULL`.

- [ ] **Step 1: Тест** — владелец входит на сайт (`platform: 'web'`), есть два анонимных телефона → `revoked: 2`, сессия владельца после этого отвечает на `/me` 200.
- [ ] **Step 2: Тест падает** — Run: `cd server && npm test -- --test-name-pattern="безымянн"`.
- [ ] **Step 3: Реализовать** запрос и `audit(s, 'devices.revoke.anonymous', { revoked: n })`.
- [ ] **Step 4: Зелёный** — `cd server && npm test`.
- [ ] **Step 5: Коммит** — `git commit -m "feat(server): an explicit switch to revoke anonymous phones"`.

---

### Task 6: Публичная страница и вход админа отдельно

**Файлы:**
- Создать: `web/src/lib/routes.ts`, `web/src/pages/public.tsx`
- Изменить: `web/src/pages/app-links.tsx:3-6` (экспортировать `LINKS`), `web/src/main.tsx:88-95` (`Root`)
- Тест: `web/test/routes.test.ts`

**Интерфейсы:**
- `export type Gate = 'public' | 'login' | 'app'`
- `export function gate(hash: string, signedIn: boolean): Gate` — без сессии: `#/admin` → `login`, иначе `public`; с сессией → `app`.
- `export function PublicPage()` — название «КФХ — Учёт», две кнопки из `LINKS`, инструкция по установке, ссылка «Вход для администратора» на `#/admin`.

- [ ] **Step 1: Тест**

```ts
test('без сессии: админка — форма входа, всё остальное — публичная страница', () => {
  assert.equal(gate('', false), 'public');
  assert.equal(gate('#/timesheet', false), 'public');
  assert.equal(gate('#/admin', false), 'login');
  assert.equal(gate('#/admin', true), 'app');
});
```

- [ ] **Step 2: Падает** — Run: `cd web && npm test -- --test-name-pattern="без сессии"`.
- [ ] **Step 3: Реализовать** `routes.ts`, `public.tsx`, переключение в `Root` (`gate(location.hash, !!session)`), ссылку «Вход для администратора».
- [ ] **Step 4: Зелёный** — `cd web && npm test && npm run build`.
- [ ] **Step 5: Коммит** — `git commit -m "feat(web): a public page with app links; the admin signs in at #/admin"`.

---

### Task 7: Раздел «Ключи» и люди в админке

**Файлы:**
- Создать: `web/src/pages/keys.tsx`
- Изменить: `web/src/pages/settings.tsx:1-40` (вставить `<KeysSection/>`), `web/src/pages/devices.tsx` (имя человека вместо безымянного, убрать переключатель `newDevicesAllowed`, кнопка «Отключить все безымянные устройства» с подтверждением)

**Интерфейсы:**
- `export function KeysSection({ note }: { note: (text: string) => void })` — форма (имя, роль, срок), список, кнопки «Ещё устройство», «Новый код», «Отозвать».
- Код показывается крупно один раз с кнопкой «Скопировать», после закрытия формы восстановить его нельзя.

- [ ] **Step 1: Написать компонент** без новых зависимостей, в стиле `settings.tsx`; данные — `engine.call('/keys')`, действия — `engine.call('/keys', { method: 'POST', body })`.
- [ ] **Step 2: Проверка** — `cd web && npm run build` (tsc ловит несовпадение типов), затем ручная проверка на `npm run dev` с живым сервером: создать ключ, увидеть код, обновить страницу — кода нет, статус «свободен».
- [ ] **Step 3: Коммит** — `git commit -m "feat(web): the keys section — create, reissue, revoke"`.

---

### Task 8: Android — код в подключении

**Файлы:**
- Изменить: `android/core/src/main/kotlin/ru/kfh/core/sync/Api.kt:57` (`register`), `android/app/src/main/kotlin/ru/kfh/tabel/session/SessionManager.kt:146` (`connect`), `android/app/src/main/kotlin/ru/kfh/tabel/ui/signin/ConnectScreen.kt`
- Тесты: `android/app/src/test/kotlin/ru/kfh/tabel/session/SessionManagerTest.kt`, `android/app/src/androidTest/kotlin/ru/kfh/tabel/ConnectHomeTest.kt`

**Интерфейсы:**
- `suspend fun register(name: String, code: String): String` — кладёт `code` в тело.
- `suspend fun connect(code: String): String?`
- `ConnectReason` получает `CodeWrong`, `CodeExpired`, `CodeUsed`; текст на экране — соответствующий.

- [ ] **Step 1: Падающий тест** — `SessionManagerTest`: `connect("1234")` → сервер получил `code: "1234"`; 403 с текстом «Код неверный» → состояние `NotConnected(CodeWrong)`.
- [ ] **Step 2: Падает** — Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*SessionManagerTest*'`.
- [ ] **Step 3: Реализовать** `Api.register`, `SessionManager.connect`, поле ввода из 4 цифр в `ConnectScreen` (клавиатура цифровая, кнопка активна только при 4 цифрах).
- [ ] **Step 4: Зелёные** — `./gradlew :app:testDebugUnitTest` и `./gradlew :app:connectedDebugAndroidTest` (если есть устройство).
- [ ] **Step 5: Коммит** — `git commit -m "feat(android): connect with a 4-digit code"`.

---

### Task 9: iPhone — код вместо почты и пароля

**Файлы:**
- Изменить: `TabelKFH/Sync/SessionStore.swift:215-240` (`signIn`), `TabelKFH/Features/SignIn/SignInView.swift`
- Тесты: `TabelKFHUITests/`, `TabelKFHTests/` (обновить существующие сценарии входа)

**Интерфейсы:**
- `func pair(code: String) async throws` — `POST /devices/register` с `{ platform: "ios", name: deviceName, code }`; 403 с текстом → `APIError.server(403, text)`.
- Экран: одно поле «Код доступа», кнопка «Подключиться»; удаляются поля почты и пароля.

- [ ] **Step 1: Обновить тест** — сценарий «подключение по коду» и «неверный код показывает текст сервера».
- [ ] **Step 2: Тест падает** — Run: `xcodebuild test -project TabelKFH.xcodeproj -scheme TabelKFH -destination 'platform=iOS Simulator,name=iPhone 15'`.
- [ ] **Step 3: Реализовать** `pair(code:)` и новый `SignInView`; старый `signIn(login:password:)` удалить, `/auth/login` из приложения больше не вызывается.
- [ ] **Step 4: Зелёный** — та же команда.
- [ ] **Step 5: Коммит** — `git commit -m "feat(ios): pair with a 4-digit code"`.

---

### Task 10: Переходный период и выпуск

**Файлы:**
- Изменить: `server/src/main.ts:13` (передать `keysRequired` и `keysPepper`), `server/deploy/compose.prod.yml` (переменные), `HANDOFF.md`, `README.md` (раздел «Подключение по коду»)

**Интерфейсы:**
- `KEYS_REQUIRED` (по умолчанию `false`) и `KEYS_PEPPER` (обязателен в проде; без него код работает, но хеш слабее — в журнале предупреждение при старте).

- [ ] **Step 1: Тест** — `main.ts` не тестируется; вместо него тест на `buildApp({ keysRequired: false })` и `true` уже есть в Task 3; здесь проверяется, что без переменных сервер стартует (smoke: `node src/main.ts` с `DATABASE_URL`).
- [ ] **Step 2: Дописать документацию** — порядок выпуска из спеки, что делать владельцу (завести ключи → выключить открытую регистрацию → отключить безымянные устройства).
- [ ] **Step 3: Коммит** — `git commit -m "feat(server): KEYS_REQUIRED switch and the release order"`.

---

## Порядок выпуска (после «выкладывай»)

1. Сервер с `KEYS_REQUIRED=false` — принимает и код, и старую саморегистрацию.
2. Сайт: публичная страница + раздел «Ключи».
3. Android APK и iPhone IPA с полем кода.
4. Владелец заводит ключи всем, кому нужно; подключает свой телефон.
5. `KEYS_REQUIRED=true` и кнопка «Отключить все безымянные устройства» — только когда все нужные люди уже с кодами.
