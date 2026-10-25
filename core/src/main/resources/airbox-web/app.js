"use strict";
(function () {
  const $ = (id) => document.getElementById(id);
  const toast = (msg) => {
    const t = $("toast");
    t.textContent = msg;
    t.classList.add("show");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.remove("show"), 2500);
  };
  const human = (n) => n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(0) + " KB"
    : n < 1073741824 ? (n / 1048576).toFixed(1) + " MB" : (n / 1073741824).toFixed(2) + " GB";
  // ---- Sending halo: dots around a button that fill with progress, then a tick. ----
  const SVG = "http://www.w3.org/2000/svg";
  // Rounded rect concentric with the button (corner radius + gap), starting at the bottom
  // centre and running clockwise, like the island version.
  function ringPath(w, h, r) {
    r = Math.min(r, h / 2, w / 2);
    return `M ${w / 2} ${h} H ${r} A ${r} ${r} 0 0 1 0 ${h - r} V ${r} A ${r} ${r} 0 0 1 ${r} 0 ` +
      `H ${w - r} A ${r} ${r} 0 0 1 ${w} ${r} V ${h - r} A ${r} ${r} 0 0 1 ${w - r} ${h} Z`;
  }
  const GAP = 7, PAD = 5; // ring distance from the button; room for stroke + glow
  function halo(host) {
    host.classList.add("halo-host");
    let svg = host.querySelector(":scope > svg.halo");
    if (!svg) {
      svg = document.createElementNS(SVG, "svg");
      svg.setAttribute("class", "halo");
      svg.setAttribute("aria-hidden", "true");
      host.append(svg);
      const badge = document.createElement("span");
      badge.className = "halo-badge";
      badge.innerHTML = '<svg viewBox="0 0 30 30" width="30" height="30"><path d="M8.5 15.5 L13 20 L21.5 10.5"/></svg>';
      host.append(badge);
    }
    const badge = host.querySelector(":scope > .halo-badge");
    const draw = () => {
      const bw = host.offsetWidth, bh = host.offsetHeight;
      const radius = parseFloat(getComputedStyle(host).borderTopLeftRadius) || 0;
      const w = bw + GAP * 2, h = bh + GAP * 2;
      const d = ringPath(w, h, radius + GAP);
      svg.style.inset = `${-(GAP + PAD)}px`;
      svg.style.width = `${w + PAD * 2}px`;
      svg.style.height = `${h + PAD * 2}px`;
      svg.setAttribute("viewBox", `${-PAD} ${-PAD} ${w + PAD * 2} ${h + PAD * 2}`);
      svg.innerHTML = `<path class="track" d="${d}"/>` +
        `<mask id="m${host.id}" maskUnits="userSpaceOnUse" x="${-PAD}" y="${-PAD}" width="${w + PAD * 2}" height="${h + PAD * 2}">` +
        `<path d="${d}" stroke="#fff" stroke-width="10" fill="none" pathLength="100" class="reveal" stroke-dasharray="0 100"/></mask>` +
        `<path class="lit" d="${d}" mask="url(#m${host.id})"/><path class="solid" d="${d}"/>`;
    };
    return {
      start() {
        draw(); badge.classList.remove("on"); svg.classList.remove("done", "fail");
        requestAnimationFrame(() => svg.classList.add("on")); this.progress(0.03);
      },
      progress(p) {
        const reveal = svg.querySelector(".reveal");
        if (reveal) reveal.setAttribute("stroke-dasharray", `${Math.max(0, Math.min(1, p)) * 100} 100`);
      },
      done() {
        this.progress(1);
        setTimeout(() => { svg.classList.add("done"); badge.classList.add("on"); if (navigator.vibrate) navigator.vibrate(20); }, 180);
        setTimeout(() => this.hide(), 1800);
      },
      fail() {
        svg.classList.add("fail"); host.classList.add("shake");
        setTimeout(() => { host.classList.remove("shake"); this.hide(); }, 900);
      },
      hide() { svg.classList.remove("on"); badge.classList.remove("on"); },
    };
  }

  const api = (path, opts = {}) => fetch(path, Object.assign({ credentials: "same-origin" }, opts, {
    headers: Object.assign({ "X-Airbox": "1" }, opts.headers || {}),
  }));

  function setOnline(on, name) {
    $("dot").className = "dot " + (on ? "on" : "off");
    $("peer").textContent = on ? "Connected to " + name : "Android phone not reachable";
  }

  let peerName = "Android";
  api("/api/info").then((r) => {
    if (r.status === 401) { location.reload(); return; }
    return r.json().then((j) => { peerName = j.deviceName; setOnline(true, peerName); });
  }).catch(() => setOnline(false));

  $("sendText").addEventListener("click", async () => {
    const text = $("text").value;
    if (!text.trim()) { toast("Paste something first"); return; }
    $("sendText").disabled = true;
    const ring = halo($("sendText"));
    ring.start();
    // Text is instant: run the ring round so the send is felt, then tick.
    let p = 0.03;
    const spin = setInterval(() => ring.progress(p = Math.min(0.9, p + 0.12)), 40);
    try {
      const r = await api("/api/text", { method: "POST", headers: { "Content-Type": "text/plain; charset=utf-8" }, body: text });
      if (!r.ok) throw new Error(await r.text());
      await new Promise((res) => setTimeout(res, 300));
      clearInterval(spin);
      ring.done();
      $("text").value = "";
      toast("Copied to " + peerName + "’s clipboard");
    } catch (e) {
      clearInterval(spin);
      ring.fail();
      toast("Couldn’t send: " + (e.message || "network error"));
    } finally {
      $("sendText").disabled = false;
    }
  });

  // Uploads one at a time with XHR, which (unlike fetch) reports upload progress.
  $("files").addEventListener("change", async (ev) => {
    const files = Array.from(ev.target.files || []);
    ev.target.value = "";
    if (!files.length) return;
    const ring = halo(document.querySelector('label[for="files"]'));
    ring.start();
    const total = files.reduce((n, f) => n + f.size, 0) || 1;
    let before = 0, ok = true;
    for (const f of files) {
      ok = (await upload(f, (loaded) => ring.progress((before + loaded) / total))) && ok;
      before += f.size;
    }
    if (ok) ring.done(); else ring.fail();
  });

  function upload(file, onProgress) {
    return new Promise((resolve) => {
      const li = document.createElement("li");
      const name = document.createElement("span"); name.className = "name"; name.textContent = file.name;
      const bar = document.createElement("progress"); bar.max = 1; bar.value = 0;
      const meta = document.createElement("span"); meta.className = "meta"; meta.textContent = human(file.size);
      li.append(name, bar, meta);
      $("uploads").prepend(li);
      const xhr = new XMLHttpRequest();
      xhr.open("POST", "/api/upload?name=" + encodeURIComponent(file.name));
      xhr.setRequestHeader("X-Airbox", "1");
      xhr.setRequestHeader("Content-Type", file.type || "application/octet-stream");
      xhr.upload.onprogress = (e) => {
        if (!e.lengthComputable) return;
        bar.value = e.loaded / e.total;
        if (onProgress) onProgress(e.loaded);
      };
      xhr.onload = () => {
        bar.remove();
        meta.textContent = xhr.status === 200 ? "Sent ✓" : (xhr.responseText || "Failed");
        resolve(xhr.status === 200);
      };
      xhr.onerror = () => { bar.remove(); meta.textContent = "Failed"; resolve(false); };
      xhr.send(file);
    });
  }

  // navigator.clipboard is unavailable on plain-HTTP pages, so use the selection-based
  // copy that iOS Safari supports inside a tap. If even that is blocked, leave the text
  // selected so the user only has to tap "Copy" in the iOS menu.
  function selectNode(node) {
    const range = document.createRange();
    range.selectNodeContents(node);
    const sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }

  $("copy").addEventListener("click", async () => {
    const box = $("incomingText");
    const text = box.textContent;
    let ok = false;
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        ok = true;
      }
    } catch (e) { /* fall through */ }
    if (!ok) {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.contentEditable = "true";
      ta.readOnly = false;
      ta.style.position = "fixed";
      ta.style.top = "0";
      ta.style.fontSize = "16px"; // avoids iOS zoom
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      const range = document.createRange();
      range.selectNodeContents(ta);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      ta.setSelectionRange(0, text.length);
      try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
      ta.remove();
    }
    if (ok) {
      toast("Copied — paste it anywhere");
    } else {
      selectNode(box);
      toast("Text selected — tap Copy");
    }
  });

  let lastText = 0;
  let lastFiles = "";
  async function poll() {
    try {
      const [t, f] = await Promise.all([api("/api/text"), api("/api/files")]);
      if (t.status === 401) { location.reload(); return; }
      const text = await t.json();
      const files = await f.json();
      setOnline(true, peerName);
      if (text && text.updatedAtEpochMs !== lastText) {
        if (lastText !== 0) toast("New text from " + peerName);
        lastText = text.updatedAtEpochMs;
        $("incomingText").textContent = text.text;
        $("incomingTextBox").hidden = false;
      }
      const key = JSON.stringify(files);
      if (key !== lastFiles) {
        lastFiles = key;
        const ul = $("downloads");
        ul.textContent = "";
        for (const file of files) {
          const li = document.createElement("li");
          const name = document.createElement("span"); name.className = "name"; name.textContent = file.name;
          const meta = document.createElement("span"); meta.className = "meta"; meta.textContent = human(file.size);
          const a = document.createElement("a"); a.href = "/api/files/" + encodeURIComponent(file.id); a.textContent = "Save";
          a.setAttribute("download", file.name);
          li.append(name, meta, a);
          ul.append(li);
        }
      }
      $("nothing").hidden = !!text || files.length > 0;
    } catch (e) {
      setOnline(false);
    }
  }
  poll();
  setInterval(poll, 3000);
})();
