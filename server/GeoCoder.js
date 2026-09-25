"use strict";

/**
 * 高德逆地理编码：经纬度 → 结构化地址。
 *
 * 密钥约定：Key 只从环境变量 AMAP_KEY 读取，绝不写入代码、配置或日志；
 * 未配置时 reverse() 返回 null，调用方降级为经纬度显示。
 *
 * 缓存：同一坐标（精度 4 位，约 11m 网格）地址永久不变，命中缓存即可，
 * 60s 轮询下不会反复请求外部服务。
 */

const CACHE = new Map();
const CACHE_MAX = 2000;
const KEY = process.env.AMAP_KEY || "";
const REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo";
const WEATHER_URL = "https://restapi.amap.com/v3/weather/weatherInfo";
// 天气 10 分钟缓存：变化慢，空间页 60s 轮询下避免反复请求外部服务
const WEATHER_CACHE = new Map();
const WEATHER_TTL = 10 * 60 * 1000;

function isConfigured() {
  return !!KEY;
}

function cacheKey(lat, lng) {
  return `${lng.toFixed(4)},${lat.toFixed(4)}`;
}

/**
 * @returns {Promise<{address:string, province:string, city:string, district:string, adcode:string}|null>}
 *          未配置 Key 或解码失败时返回 null（调用方降级显示经纬度）
 */
async function reverse(lat, lng) {
  if (!KEY) return null;
  const latNum = Number(lat);
  const lngNum = Number(lng);
  if (!Number.isFinite(latNum) || !Number.isFinite(lngNum)) return null;

  const key = cacheKey(latNum, lngNum);
  const cached = CACHE.get(key);
  if (cached) return cached;

  const url =
    `${REGEO_URL}?key=${KEY}` +
    `&location=${encodeURIComponent(`${lngNum},${latNum}`)}` +
    `&extensions=base&output=JSON`;
  try {
    const resp = await fetch(url, { signal: AbortSignal.timeout(5000) });
    const data = await resp.json();
    if (data.status !== "1" || !data.regeocode) return null;
    const comp = data.regeocode.addressComponent || {};
    // 高德在直辖市/无市级城市时 city 返回空数组，统一归一为字符串
    const city = Array.isArray(comp.city) ? "" : String(comp.city || "");
    const out = {
      address: String(data.regeocode.formatted_address || ""),
      province: String(comp.province || ""),
      city,
      district: String(comp.district || ""),
      adcode: String(comp.adcode || ""),
    };
    if (CACHE.size > CACHE_MAX) CACHE.delete(CACHE.keys().next().value);
    CACHE.set(key, out);
    return out;
  } catch (e) {
    // 超时/网络异常/非 200：降级，不阻塞空间页展示
    return null;
  }
}

/**
 * 天气查询（实时/base 扩展）。adcode 由 reverse() 顺带返回，无需再查一次。
 * @returns {Promise<{city:string, text:string, temp:string, humidity:string, wind:string, reportTime:string}|null>}
 */
async function weather(adcode) {
  if (!KEY || !adcode) return null;
  const code = String(adcode).trim();
  if (!/^\d{4,12}$/.test(code)) return null;

  const hit = WEATHER_CACHE.get(code);
  if (hit && Date.now() - hit.ts < WEATHER_TTL) return hit.data;

  const url =
    `${WEATHER_URL}?key=${KEY}` +
    `&city=${encodeURIComponent(code)}&extensions=base&output=JSON`;
  try {
    const resp = await fetch(url, { signal: AbortSignal.timeout(5000) });
    const data = await resp.json();
    if (data.status !== "1" || !Array.isArray(data.lives) || data.lives.length === 0) return null;
    const l = data.lives[0];
    const out = {
      city: String(l.city || l.province || ""),
      text: String(l.weather || ""),
      temp: l.temperature != null ? String(l.temperature) : "",
      humidity: String(l.humidity || ""),
      wind: l.winddirection && l.windpower ? `${l.winddirection}风 ${l.windpower}级` : "",
      reportTime: String(l.reporttime || ""),
    };
    WEATHER_CACHE.set(code, { data: out, ts: Date.now() });
    return out;
  } catch (e) {
    // 超时/网络异常：降级，天气不影响位置展示
    return null;
  }
}

module.exports = { reverse, weather, isConfigured };
