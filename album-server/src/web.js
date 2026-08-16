"use strict";
/**
 * 相册网页：响应式网格 + 点击全屏查看原图（无外部依赖）；上传中自动刷新，点大图按需实时拉取。
 * renderAlbumPage：单个会话相册页（/<token>/，供浏览器/查看端按会话访问）。
 * renderAllAlbumPage：聚合相册页（/all），把所有会话的照片归拢到一个视图，无需链接即可查看全部。
 */
function pad(i) {
  return String(i).padStart(4, "0");
}

function renderAlbumPage(session, key) {
  const isVideo = (i) => session.videos && session.videos.has(i);
  const K = key ? "?key=" + encodeURIComponent(key) : "";
  const q = (url) => (key ? url + K : url);
  const idx = Array.from({ length: session.total }, (_, i) => i + 1)
    .filter((i) => session.received.has(i))
    .map(
      (i) =>
        `<a class="p${isVideo(i) ? " v" : ""}" href="javascript:void(0)" onclick="openView(${i},${isVideo(i)})"><img loading="lazy" src="${pad(i)}.jpg${K}" alt=""><span class="vb">▶</span></a>`
    )
    .join("");
  const done = session.done;
  return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>相册</title><style>
body{margin:0;background:#111;font-family:-apple-system,sans-serif}
.top{position:sticky;top:0;background:rgba(17,17,17,.92);backdrop-filter:blur(8px);color:#fff;padding:12px 16px;font-size:15px;z-index:10}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:3px;padding:3px}
.p{display:block;aspect-ratio:1;overflow:hidden;background:#222;position:relative}
.p img{width:100%;height:100%;object-fit:cover;display:block}
.p .vb{display:none}
.p.v .vb{display:flex;position:absolute;inset:0;align-items:center;justify-content:center;font-size:40px;color:#fff;text-shadow:0 1px 6px rgba(0,0,0,.7);background:rgba(0,0,0,.18)}
.none{color:#888;text-align:center;padding:40px 16px;font-size:14px}
#ov{position:fixed;inset:0;background:rgba(0,0,0,.96);display:none;align-items:center;justify-content:center;z-index:99;cursor:zoom-out}
#ovimg{max-width:96vw;max-height:96vh;object-fit:contain}
#ovvideo{max-width:96vw;max-height:96vh}
#ovtip{position:absolute;top:14px;left:50%;transform:translateX(-50%);color:#fff;background:rgba(0,0,0,.6);padding:6px 14px;border-radius:20px;font-size:13px}
@media(min-width:768px){.grid{grid-template-columns:repeat(auto-fill,minmax(220px,1fr))}}
</style></head><body>
<div class="top">📷 相册${done ? ` · ${session.received.size} 项` : " · 上传中… 已收 " + session.received.size + " 项"}</div>
${idx ? `<div class="grid">${idx}</div>` : `<div class="none">${done ? "相册是空的" : "照片正在上传，请稍后刷新"}</div>`}
<div id="ov" onclick="closeView()"><div id="ovtip">加载高清大图中…</div><img id="ovimg" alt=""><video id="ovvideo" controls style="display:none"></video></div>
<script>
var TOKEN="${session.token}", DONE=${done}, RECEIVED=${session.received.size}, ALBUM_KEY="${key ? key.replace(/"/g, '\\"') : ""}";
function K(){return ALBUM_KEY?"?key="+encodeURIComponent(ALBUM_KEY):"";}
function openView(i,isV){
  var ov=document.getElementById("ov"), img=document.getElementById("ovimg"), vid=document.getElementById("ovvideo");
  if(isV){
    img.style.display="none"; vid.style.display="block";
    vid.src="/api/video?token="+TOKEN+"&index="+i+K();
    document.getElementById("ovtip").style.display="none";
    ov.style.display="flex"; vid.play();
    return;
  }
  vid.style.display="none"; vid.pause(); img.style.display="block";
  img.src=pad(i)+".jpg"+K();
  document.getElementById("ovtip").style.display="block";
  document.getElementById("ovtip").textContent="加载高清大图中…";
  ov.style.display="flex";
  loadOrig(i,img,0);
}
function loadOrig(i,img,tries){
  fetch("/api/original?token="+TOKEN+"&index="+i+K()).then(function(r){
    if(r.ok && (r.headers.get("content-type")||"").indexOf("image")>=0){
      return r.blob().then(function(b){ img.src=URL.createObjectURL(b); document.getElementById("ovtip").style.display="none"; });
    }
    return r.json().then(function(j){
      if(j.status==="pending"){
        if(tries>=30){ document.getElementById("ovtip").textContent="共享方不在线，显示预览图"; return; }
        setTimeout(function(){ loadOrig(i,img,tries+1); },1500);
      }
    });
  }).catch(function(){ setTimeout(function(){ loadOrig(i,img,tries+1); },2000); });
}
function closeView(){ var v=document.getElementById("ovvideo"); if(v) v.pause(); document.getElementById("ov").style.display="none"; }
setInterval(function(){
  if(DONE || document.getElementById("ov").style.display!=="none") return;
  fetch("/api/status?token="+TOKEN+K()).then(function(r){return r.json();}).then(function(j){
    if(j.received!==RECEIVED){ location.reload(); }
  }).catch(function(){});
},2000);
</script>
</body></html>`;
}

module.exports = { renderAlbumPage, renderAllAlbumPage };

/**
 * 聚合相册页：一次性展示服务器上所有会话的全部照片（无需链接，主 App 内 WebView 直接打开）。
 * 数据从 /api/albums 动态拉取，照片混排为一个网格，点击按需加载原图，上传中自动刷新。
 */
function renderAllAlbumPage(key) {
  const K = key ? "?key=" + encodeURIComponent(key) : "";
  const KJS = key ? key.replace(/"/g, '\\"') : "";
  return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>相册 · 全部照片</title><style>
body{margin:0;background:#111;font-family:-apple-system,sans-serif}
.top{position:sticky;top:0;background:rgba(17,17,17,.92);backdrop-filter:blur(8px);color:#fff;padding:12px 16px;font-size:15px;z-index:10;display:flex;align-items:center;justify-content:space-between}
.top b{font-weight:600}
.refresh{font-size:13px;color:#888;padding:4px 10px;border:1px solid #333;border-radius:14px;cursor:pointer}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:3px;padding:3px}
.p{display:block;aspect-ratio:1;overflow:hidden;background:#222;position:relative}
.p img{width:100%;height:100%;object-fit:cover;display:block}
.p .vb{display:none}
.p.v .vb{display:flex;position:absolute;inset:0;align-items:center;justify-content:center;font-size:40px;color:#fff;text-shadow:0 1px 6px rgba(0,0,0,.7);background:rgba(0,0,0,.18)}
.none{color:#888;text-align:center;padding:40px 16px;font-size:14px}
#ov{position:fixed;inset:0;background:rgba(0,0,0,.96);display:none;align-items:center;justify-content:center;z-index:99;cursor:zoom-out}
#ovimg{max-width:96vw;max-height:96vh;object-fit:contain}
#ovvideo{max-width:96vw;max-height:96vh}
#ovtip{position:absolute;top:14px;left:50%;transform:translateX(-50%);color:#fff;background:rgba(0,0,0,.6);padding:6px 14px;border-radius:20px;font-size:13px}
@media(min-width:768px){.grid{grid-template-columns:repeat(auto-fill,minmax(220px,1fr))}}
</style></head><body>
<div class="top"><span id="tcount"><b>相册</b> · 加载中…</span><span class="refresh" onclick="location.reload()">刷新</span></div>
<div id="grid" class="grid"></div>
<div id="none" class="none" style="display:none">还没有照片，共享方上传后会自动归拢到这里</div>
<div id="ov" onclick="closeView()"><div id="ovtip">加载高清大图中…</div><img id="ovimg" alt=""><video id="ovvideo" controls style="display:none"></video></div>
<script>
var TOKEN_ARR=[], RECEIVED=0, ALBUM_KEY="${KJS}";
function K(){return ALBUM_KEY?"?key="+encodeURIComponent(ALBUM_KEY):"";}
function pad(i){return String(i).padStart(4,"0");}
function render() {
  var grid = document.getElementById("grid"), none = document.getElementById("none");
  var html = "";
  TOKEN_ARR.forEach(function (a) {
    var videos = (a.videos || []);
    a.received.forEach(function (i) {
      var isV = videos.indexOf(i) >= 0;
      html +=
        '<a class="p' + (isV ? " v" : "") + '" href="javascript:void(0)" onclick="openView(\'' + a.token + '\',' + i + ',' + isV + ')"><img loading="lazy" src="/' + a.token + "/" + pad(i) + '.jpg' + K() + '" alt=""><span class="vb">▶</span></a>';
    });
  });
  grid.innerHTML = html;
  document.getElementById("tcount").innerHTML = "<b>相册</b> · 共 " + RECEIVED + " 项";
  none.style.display = RECEIVED > 0 ? "none" : "block";
  grid.style.display = RECEIVED > 0 ? "grid" : "none";
}
function loadAlbums() {
  fetch("/api/albums" + K())
    .then(function (r) { return r.json(); })
    .then(function (j) {
      TOKEN_ARR = j.albums || [];
      RECEIVED = j.count || 0;
      render();
    }).catch(function () {});
}
function openView(token, i, isV) {
  var ov = document.getElementById("ov"), img = document.getElementById("ovimg"), vid = document.getElementById("ovvideo");
  if (isV) {
    img.style.display = "none"; vid.style.display = "block";
    vid.src = "/api/video?token=" + token + "&index=" + i + K();
    document.getElementById("ovtip").style.display = "none";
    ov.style.display = "flex"; vid.play();
    return;
  }
  vid.style.display = "none"; vid.pause(); img.style.display = "block";
  img.src = "/" + token + "/" + pad(i) + ".jpg" + K();
  document.getElementById("ovtip").style.display = "block";
  document.getElementById("ovtip").textContent = "加载高清大图中…";
  ov.style.display = "flex";
  loadOrig(token, i, img, 0);
}
function loadOrig(token,i,img,tries){
  fetch("/api/original?token="+token+"&index="+i+K()).then(function(r){
    if(r.ok && (r.headers.get("content-type")||"").indexOf("image")>=0){
      return r.blob().then(function(b){ img.src=URL.createObjectURL(b); document.getElementById("ovtip").style.display="none"; });
    }
    return r.json().then(function(j){
      if(j.status==="pending"){
        if(tries>=30){ document.getElementById("ovtip").textContent="共享方不在线，显示预览图"; return; }
        setTimeout(function(){ loadOrig(token,i,img,tries+1); },1500);
      }
    });
  }).catch(function(){ setTimeout(function(){ loadOrig(token,i,img,tries+1); },2000); });
}
function closeView(){ var v=document.getElementById("ovvideo"); if(v) v.pause(); document.getElementById("ov").style.display="none"; }
// 上传中自动刷新：张数变化即重载
setInterval(function(){
  if(document.getElementById("ov").style.display!=="none") return;
  fetch("/api/albums"+K()).then(function(r){return r.json();}).then(function(j){
    var n=(j.count||0);
    if(n!==RECEIVED){ location.reload(); }
  }).catch(function(){});
},5000);
loadAlbums();
</script>
</body></html>`;
}
