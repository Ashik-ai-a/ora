"use strict";
(function () {
  const $ = (id) => document.getElementById(id);
  const radar = document.querySelector(".radar");
  const human = (n) => n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(0) + " KB"
    : n < 1073741824 ? (n / 1048576).toFixed(1) + " MB" : (n / 1073741824).toFixed(2) + " GB";
  const api = (path, opts = {}) => fetch(path, Object.assign({ credentials: "same-origin" }, opts, {
    headers: Object.assign({ "X-Airbox": "1" }, opts.headers || {}),
  }));

  // ---- Banner (iOS-style, slides from the top) ----
  let bannerTimer;
  function toast(message, isError) {
    $("toast").textContent = message;
    $("banner").classList.toggle("error", !!isError);
    $("banner").classList.add("show");
    clearTimeout(bannerTimer);
    bannerTimer = setTimeout(() => $("banner").classList.remove("show"), 2600);
  }

  // ---- Peer bubble: the AirDrop-style progress ring lives around the avatar ----
  let peerName = "Android";
  let idleStatus = "Looking for your Android phone";
  function initials(name) {
    const words = name.replace(/['’]s\b/g, "").split(/\s+/).filter(Boolean);
    const pick = words.length > 1 ? words[0][0] + (words[1].match(/\d/) ? words[1] : words[1][0]) : name.slice(0, 2);
    return pick.slice(0, 2).toUpperCase();
  }
  function setOnline(on) {
    radar.classList.toggle("connected", on);
    radar.classList.toggle("offline", !on);
    $("dot").className = "dot " + (on ? "on" : "off");
    idleStatus = on ? "Ready" : "Not reachable on this Wi-Fi";
    if (!radar.classList.contains("busy") && !radar.classList.contains("done")) $("peerStatusText").textContent = idleStatus;
  }
  const ring = {
    timer: null,
    start(label) {
      clearTimeout(this.timer);
      radar.classList.remove("done");
      radar.classList.add("busy");
      this.progress(0.02, label);
    },
    progress(p, label) {
      const pct = Math.max(0, Math.min(1, p)) * 100;
      $("ringFill").style.strokeDasharray = `${pct} 100`;
      $("peerStatusText").textContent = label || `Sending… ${Math.round(pct)}%`;
    },
    done(label) {
      this.progress(1, label);
      setTimeout(() => {
        radar.classList.add("done");
        $("bubble").classList.add("pop");
        setTimeout(() => $("bubble").classList.remove("pop"), 260);
        if (navigator.vibrate) navigator.vibrate(18);
      }, 220);
      this.timer = setTimeout(() => this.reset(), 2600);
    },
    fail(message) {
      radar.classList.remove("busy");
      $("peerStatusText").textContent = message;
      $("bubble").animate([{ transform: "translateX(0)" }, { transform: "translateX(-8px)" }, { transform: "translateX(8px)" },
        { transform: "translateX(-5px)" }, { transform: "translateX(0)" }], { duration: 380, easing: "ease-out" });
      this.timer = setTimeout(() => this.reset(), 2600);
    },
    reset() {
      radar.classList.remove("busy", "done");
      $("ringFill").style.strokeDasharray = "0 100";
      $("peerStatusText").textContent = idleStatus;
    },
  };

  api("/api/info").then((r) => {
    if (r.status === 401) { location.reload(); return; }
    return r.json().then((j) => {
      peerName = j.deviceName;
      $("peer").textContent = peerName;
      $("avatar").textContent = initials(peerName);
      $("inboxTitle").textContent = "From " + peerName;
      document.querySelectorAll(".peerName").forEach((n) => { n.textContent = peerName; });
      setOnline(true);
    });
  }).catch(() => setOnline(false));

  // ---- Text ----
  const sendButton = $("sendText");
  const syncSend = () => { sendButton.disabled = !$("text").value.trim(); };
  $("text").addEventListener("input", () => {
    syncSend();
    $("text").style.height = "auto";
    $("text").style.height = Math.min(180, $("text").scrollHeight) + "px";
  });
  syncSend();

  // Pages on plain HTTP can't read the clipboard, so "Paste" opens the box with the
  // keyboard and the iOS Paste menu one tap away.
  $("pasteHint").addEventListener("click", () => {
    $("text").focus();
    toast("Touch and hold the box, then tap Paste");
  });

  sendButton.addEventListener("click", async () => {
    const text = $("text").value;
    if (!text.trim()) return;
    sendButton.disabled = true;
    ring.start("Sending…");
    // Text is instant: run the ring round so the send is felt.
    let p = 0.02;
    const sweep = setInterval(() => ring.progress(p = Math.min(0.92, p + 0.1), "Sending…"), 45);
    try {
      const r = await api("/api/clipboard", { method: "POST", headers: { "Content-Type": "text/plain; charset=utf-8" }, body: text });
      if (!r.ok) throw new Error(await r.text());
      await new Promise((res) => setTimeout(res, 320));
      clearInterval(sweep);
      ring.done("Copied to clipboard");
      $("text").value = "";
      $("text").style.height = "auto";
      toast("Copied to " + peerName);
    } catch (e) {
      clearInterval(sweep);
      ring.fail("Couldn’t send");
      toast("Couldn’t reach " + peerName + ". Check you’re on the same Wi-Fi.", true);
    } finally {
      syncSend();
    }
  });

  // ---- Files and photos (one ring for the whole batch) ----
  // Like AirDrop: send, then wait for the other phone to Accept. Several photos from one
  // pick share a batch, so Android asks once.
  async function sendFiles(files) {
    if (!files.length) return;
    const total = files.reduce((n, f) => n + f.size, 0) || 1;
    const batch = Math.random().toString(36).slice(2, 10);
    let before = 0;
    const results = [];
    ring.start();
    for (const f of files) {
      results.push(await upload(f, batch, (loaded) => ring.progress((before + loaded) / total)));
      before += f.size;
    }
    if (results.some((r) => !r)) {
      ring.fail("Couldn’t send");
      toast("Some items didn’t send. Try them again.", true);
      return;
    }
    const waiting = results.filter((r) => r.status === "pending");
    let outcome = "saved";
    if (waiting.length) {
      radar.classList.add("waiting");
      $("peerStatusText").textContent = "Waiting for " + peerName + " to accept…";
      outcome = await waitFor(waiting[waiting.length - 1].id);
      radar.classList.remove("waiting");
    }
    if (outcome === "saved") {
      ring.done(files.length === 1 ? "Saved on " + peerName : `${files.length} saved on ${peerName}`);
      toast(files.length === 1 ? "Saved on " + peerName : `Saved ${files.length} items on ${peerName}`);
    } else {
      ring.fail(outcome === "declined" ? "Declined" : "Not accepted in time");
      toast(outcome === "declined" ? peerName + " declined" : "Nobody accepted on " + peerName, true);
    }
  }

  async function waitFor(id) {
    for (let i = 0; i < 600; i++) {
      try {
        const r = await api("/api/transfers/" + encodeURIComponent(id));
        if (r.ok) {
          const s = await r.json();
          if (s.status !== "pending") return s.status;
        }
      } catch (e) { /* keep waiting through a network blip */ }
      await new Promise((res) => setTimeout(res, 1000));
    }
    return "expired";
  }
  for (const id of ["photos", "files"]) {
    $(id).addEventListener("change", (ev) => {
      const files = Array.from(ev.target.files || []);
      ev.target.value = "";
      sendFiles(files);
    });
  }

  function miniRing() {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 22 22");
    svg.setAttribute("class", "mini-ring");
    svg.innerHTML = '<circle class="t" cx="11" cy="11" r="9"/><circle class="f" cx="11" cy="11" r="9" pathLength="100" stroke-dasharray="0 100"/>';
    return svg;
  }

  function upload(file, batch, onProgress) {
    return new Promise((resolve) => {
      const li = document.createElement("li");
      const progress = miniRing();
      const name = document.createElement("span"); name.className = "name"; name.textContent = file.name;
      const meta = document.createElement("span"); meta.className = "meta"; meta.textContent = human(file.size);
      li.append(progress, name, meta);
      $("uploads").prepend(li);
      const xhr = new XMLHttpRequest();
      xhr.open("POST", "/api/upload?name=" + encodeURIComponent(file.name));
      xhr.setRequestHeader("X-Airbox", "1");
      xhr.setRequestHeader("X-Airbox-Batch", batch);
      xhr.setRequestHeader("Content-Type", file.type || "application/octet-stream");
      xhr.upload.onprogress = (e) => {
        if (!e.lengthComputable) return;
        progress.querySelector(".f").setAttribute("stroke-dasharray", `${(e.loaded / e.total) * 100} 100`);
        onProgress(e.loaded);
      };
      xhr.onload = () => {
        const ok = xhr.status === 200 || xhr.status === 202;
        progress.querySelector(".f").setAttribute("stroke-dasharray", "100 100");
        progress.querySelector(".f").style.stroke = ok ? "var(--green)" : "var(--red)";
        meta.textContent = xhr.status === 202 ? "Sent" : ok ? "Saved" : (xhr.responseText || "Failed");
        meta.classList.add(ok ? "ok" : "bad");
        let body = null;
        try { body = JSON.parse(xhr.responseText); } catch (e) { body = null; }
        resolve(ok ? (body || { status: "saved" }) : null);
      };
      xhr.onerror = () => { meta.textContent = "Failed"; meta.classList.add("bad"); resolve(null); };
      xhr.send(file);
    });
  }

  // ---- Copy what Android shared ----
  // navigator.clipboard needs HTTPS; fall back to a selection copy, which iOS allows
  // inside a tap. If even that's blocked, leave the text selected for the iOS Copy menu.
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
      if (navigator.clipboard && window.isSecureContext) { await navigator.clipboard.writeText(text); ok = true; }
    } catch (e) { /* fall through */ }
    if (!ok) {
      const ta = document.createElement("textarea");
      ta.value = text; ta.contentEditable = "true"; ta.readOnly = false;
      ta.style.position = "fixed"; ta.style.top = "0"; ta.style.fontSize = "16px"; ta.style.opacity = "0";
      document.body.appendChild(ta);
      const range = document.createRange();
      range.selectNodeContents(ta);
      const sel = window.getSelection();
      sel.removeAllRanges(); sel.addRange(range);
      ta.setSelectionRange(0, text.length);
      try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
      ta.remove();
    }
    if (ok) {
      $("copy").textContent = "Copied";
      setTimeout(() => { $("copy").textContent = "Copy"; }, 1600);
      toast("Copied");
    } else {
      selectNode(box);
      toast("Text selected — tap Copy");
    }
  });

  // ---- One-time Shortcut setup (Share sheet + Back Tap clipboard) ----
  $("makeKey").addEventListener("click", async () => {
    try {
      const r = await api("/api/shortcut-key", { method: "POST" });
      if (!r.ok) throw new Error();
      const j = await r.json();
      const origin = location.origin;
      $("uploadUrl").textContent = origin + j.upload + "?name=Photo";
      $("clipUrl").textContent = origin + j.clipboard;
      $("authValue").textContent = "Bearer " + j.key;
      $("keyBox").hidden = false;
      $("makeKey").hidden = true;
    } catch (e) {
      toast("Couldn’t reach " + peerName, true);
    }
  });
  document.querySelectorAll("[data-copy]").forEach((b) => b.addEventListener("click", () => {
    const node = $(b.getAttribute("data-copy"));
    const text = node.textContent;
    const done = () => { b.textContent = "Copied"; setTimeout(() => { b.textContent = "Copy"; }, 1500); };
    if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done); return; }
    const ta = document.createElement("textarea");
    ta.value = text; ta.style.position = "fixed"; ta.style.opacity = "0"; ta.style.fontSize = "16px";
    document.body.appendChild(ta); ta.select(); ta.setSelectionRange(0, text.length);
    let ok = false; try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
    ta.remove();
    if (ok) done(); else { selectNode(node); toast("Text selected — tap Copy"); }
  }));

  // ---- Poll for things Android shares ----
  let lastText = 0, lastFiles = "";
  async function poll() {
    try {
      const [t, f] = await Promise.all([api("/api/text"), api("/api/files")]);
      if (t.status === 401) { location.reload(); return; }
      const text = await t.json();
      const files = await f.json();
      setOnline(true);
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
