(() => {
  if (window.__vtInstalled) return; window.__vtInstalled = true;
  const bridge = window.__vb;
  if (!bridge) return;
  const frameId = Math.random().toString(36).slice(2);
  const vids = new Set();
  let armedUntil = 0, fakeEl = null;
  const send = (o) => { try { bridge.postMessage(JSON.stringify({ frameId, ...o })); } catch (e) {} };
  const track = (v) => { if (v instanceof HTMLVideoElement) vids.add(v); };

  const EVTS = ['play','playing','pause','ended','loadedmetadata','emptied','seeked'];
  const hook = (root) => EVTS.forEach(t => root.addEventListener(t, e => { track(e.target); report(); }, true));
  hook(document);
  const origAttach = Element.prototype.attachShadow;
  Element.prototype.attachShadow = function (init) { const r = origAttach.call(this, init); hook(r); return r; };
  const origPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () { track(this); return origPlay.apply(this, arguments); };

  function pick() {
    let best = null, bestArea = 0;
    for (const v of vids) {
      if (!v.isConnected) { vids.delete(v); continue; }
      const r = v.getBoundingClientRect();
      const w = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
      const h = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
      const vis = (w * h) / Math.max(1, r.width * r.height);
      const ok = !v.paused && !v.ended && r.width >= 200 && r.height >= 200 && vis >= 0.75;
      if (ok && w * h > bestArea) { best = v; bestArea = w * h; }
    }
    return best;
  }

  let t = 0;
  function report() {
    clearTimeout(t);
    t = setTimeout(() => {
      const v = pick();
      if (!v) return send({ type: 'state', playing: false });
      const r = v.getBoundingClientRect();
      send({ type: 'state', playing: true, duration: v.duration, currentTime: v.currentTime,
             isLive: v.duration === Infinity, videoW: v.videoWidth, videoH: v.videoHeight,
             rect: [r.left, r.top, r.width, r.height], dpr: devicePixelRatio, top: window === top });
    }, 150);
  }
  addEventListener('scroll', report, { passive: true, capture: true });
  setInterval(report, 2000);

  function container(v) {
    const vr = v.getBoundingClientRect(); let el = v, best = v;
    while ((el = el.parentElement || (el.getRootNode() && el.getRootNode().host))) {
      const r = el.getBoundingClientRect();
      if (r.width > vr.width * 1.1 || r.height > vr.height * 1.25) break;
      best = el;
    }
    return (window.__vtAdapter && window.__vtAdapter.container(v)) || best;
  }

  function onOrient() {
    if (Date.now() > armedUntil) return;
    armedUntil = 0;
    const v = pick(); if (!v || document.fullscreenElement) return;
    const el = container(v);
    const p = (el.requestFullscreen || el.webkitRequestFullscreen).call(el);
    if (p && p.then) p.then(() => send({ type: 'fs-ok' }), () => send({ type: 'fs-fail' }));
  }
  addEventListener('orientationchange', onOrient);
  if (screen.orientation) screen.orientation.addEventListener('change', onOrient);

  const FAKE = ';position:fixed!important;inset:0!important;width:100vw!important;' +
               'height:100vh!important;z-index:2147483647!important;background:#000!important';
  function fake(on) {
    const v = pick() || [...vids][0]; if (!v) return;
    if (on) { fakeEl = container(v); fakeEl.dataset.vtCss = fakeEl.style.cssText;
              fakeEl.style.cssText += FAKE; v.style.objectFit = 'contain'; }
    else if (fakeEl) { fakeEl.style.cssText = fakeEl.dataset.vtCss || ''; fakeEl = null; }
    if (window !== top) parent.postMessage({ __vt: 'fake-iframe', on }, '*');
  }
  addEventListener('message', (e) => {
    if (!e.data || e.data.__vt !== 'fake-iframe') return;
    for (const f of document.querySelectorAll('iframe')) if (f.contentWindow === e.source) {
      if (e.data.on) { f.dataset.vtCss = f.style.cssText; f.style.cssText += FAKE; }
      else f.style.cssText = f.dataset.vtCss || '';
    }
  });

  function clampSeek(v, target) {
    if (v.duration === Infinity && v.seekable.length)
      return Math.min(Math.max(target, v.seekable.start(0)), v.seekable.end(v.seekable.length - 1) - 1);
    return Math.min(Math.max(target, 0), Math.max(0, v.duration - 0.5));
  }

  bridge.onmessage = (e) => {
    let m; try { m = JSON.parse(e.data); } catch { return; }
    const v = pick() || [...vids].find(x => !x.paused) || [...vids][0];
    switch (m.cmd) {
      case 'arm': armedUntil = Date.now() + 1500; break;
      case 'seekBy': if (v && isFinite(+m.delta)) v.currentTime = clampSeek(v, v.currentTime + +m.delta); report(); break;
      case 'seekTo': if (v && isFinite(+m.t)) v.currentTime = clampSeek(v, +m.t); report(); break;
      case 'exit': if (document.fullscreenElement) document.exitFullscreen(); break;
      case 'fake': fake(!!m.on); break;
      case 'query': report(); break;
    }
  };
  send({ type: 'ready', top: window === top, origin: location.origin });
})();
