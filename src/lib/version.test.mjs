import assert from "node:assert/strict";
import test from "node:test";
import {
  channelForBranch,
  channelOf,
  isUpdateFor,
  parseVersion,
  resolveVersion,
} from "./version-core.mjs";

test("channel resolution from branch names", () => {
  assert.equal(channelForBranch("main"), "stable");
  assert.equal(channelForBranch("testing"), "testing");
  assert.equal(channelForBranch("refs/heads/testing"), "testing");
  assert.equal(channelForBranch("feature/foo"), null);
});

test("channel isolation prevents cross-channel updates", () => {
  assert.equal(isUpdateFor("1.0.0", "1.0.1"), true);
  assert.equal(isUpdateFor("1.0.0", "1.0.1-testing.1"), false);
  assert.equal(isUpdateFor("1.0.1-testing.1", "1.0.1-testing.2"), true);
  assert.equal(isUpdateFor("1.0.1-testing.1", "1.0.1"), false);
  assert.equal(isUpdateFor("1.0.1-testing.2", "1.0.1-testing.1"), false);
});

test("first stable release is 1.0.0 and subsequent releases increment", () => {
  assert.equal(
    resolveVersion({ channel: "stable", tags: [], packageVersion: "0.1.0" }),
    "1.0.0",
  );
  assert.equal(
    resolveVersion({ channel: "testing", tags: ["v1.0.0"], packageVersion: "0.1.0" }),
    "1.0.1-testing.1",
  );
  assert.equal(
    resolveVersion({
      channel: "testing",
      tags: ["v1.0.0", "v1.0.1-testing.1"],
      packageVersion: "0.1.0",
    }),
    "1.0.1-testing.2",
  );
  assert.equal(
    resolveVersion({
      channel: "stable",
      tags: ["v1.0.0", "v1.0.1-testing.2"],
      packageVersion: "0.1.0",
    }),
    "1.0.1",
  );
});

test("odometer rollover at 99", () => {
  assert.equal(
    resolveVersion({ channel: "stable", tags: ["v1.0.99"], packageVersion: "0.1.0" }),
    "1.1.0",
  );
  assert.equal(parseVersion("1.2.3-testing.4")?.channel, "testing");
  assert.equal(channelOf("1.2.3"), "stable");
});
