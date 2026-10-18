import assert from "node:assert/strict";
import test from "node:test";
import { openDatabase, migrateDatabaseSchema } from "../src/db.js";
import { readChanges } from "../src/changes.js";

function fixture(t) {
  const db = openDatabase(":memory:");
  t.after(() => db.close());
  return db;
}

function insert(db, id, version = 1) {
  db.prepare("INSERT INTO cards(id, body_json) VALUES (?, ?)").run(id, JSON.stringify({ id, version }));
}

test("change pages include tombstones and settings, and unchanged pulls are empty", t => {
  const db = fixture(t);
  insert(db, "a"); insert(db, "b");
  const first = readChanges(db, "", 1);
  assert.equal(first.cards.length, 1);
  assert.equal(first.has_more, true);
  const second = readChanges(db, first.sync_cursor, 1);
  assert.equal(second.cards[0].id, "b");
  assert.equal(second.has_more, false);
  assert.equal(readChanges(db, second.sync_cursor).cards.length, 0);
  db.prepare("UPDATE cards SET body_json=? WHERE id='a'").run(JSON.stringify({ id: "a", deleted_at: "2026-09-08T00:00:00Z" }));
  db.prepare("INSERT INTO settings VALUES ('main', ?, ?)").run('{"daily_word_goal":5}', "2026-09-08T00:00:00Z");
  const updated = readChanges(db, second.sync_cursor);
  assert.ok(updated.cards[0].deleted_at);
  assert.equal(updated.settings.daily_word_goal, 5);
});

test("rolled-back and identical writes never advance the cursor", t => {
  const db = fixture(t);
  insert(db, "a");
  const cursor = readChanges(db).sync_cursor;
  assert.throws(() => db.transaction(() => { insert(db, "b"); throw Error("rollback"); })());
  db.prepare("UPDATE cards SET body_json=body_json").run();
  assert.equal(readChanges(db, cursor).sync_cursor, cursor);
  migrateDatabaseSchema(db);
  assert.equal(readChanges(db, cursor).sync_cursor, cursor);
});

test("replaced databases and cursors ahead of a restored database replay safely", t => {
  const db = fixture(t);
  insert(db, "a");
  const cursor = readChanges(db).sync_cursor;
  const replay = readChanges(db, cursor.replace(/:\d+$/, ":999"));
  assert.equal(replay.reset, true);
  assert.equal(replay.cards.length, 1);
  const fresh = readChanges(db, "00000000-0000-0000-0000-000000000000:1");
  assert.equal(fresh.reset, true);
  assert.equal(fresh.cards.length, 1);
  assert.throws(() => readChanges(db, "arbitrary"), { status: 400 });
  assert.throws(() => readChanges(db, "", 0), { status: 400 });
});

test("large history stays bounded to the requested page and returns only a new edit afterward", t => {
  const db = fixture(t);
  db.transaction(() => { for (let i = 0; i < 12000; i++) insert(db, `card-${i}`); })();
  let cursor = "";
  let count = 0;
  let pages = 0;
  for (;;) {
    const page = readChanges(db, cursor, 400);
    assert.ok(page.cards.length <= 400);
    count += page.cards.length; pages++;
    cursor = page.sync_cursor;
    if (!page.has_more) break;
  }
  assert.equal(count, 12000);
  assert.equal(pages, 30);
  insert(db, "last");
  const delta = readChanges(db, cursor);
  assert.deepEqual(delta.cards.map(c => c.id), ["last"]);
  assert.ok(JSON.stringify(delta).length < 500);
});
