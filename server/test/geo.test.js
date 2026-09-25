const test = require("node:test");
const assert = require("node:assert");
const geo = require("../GeoCoder");

test("weather: 非法 adcode 返回 null", async () => {
  assert.equal(await geo.weather(""), null);
  assert.equal(await geo.weather("abc"), null);
  assert.equal(await geo.weather(null), null);
});

test("reverse: 非法坐标返回 null", async () => {
  assert.equal(await geo.reverse("abc", 120), null);
});
