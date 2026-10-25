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
    try {
      const r = await api("/api/text", { method: "POST", headers: { "Content-Type": "text/plain; charset=utf-8" }, body: text });
      if (!r.ok) throw new Error(await r.text());
      $("text").value = "";
      toast("Copied to " + peerName + "’s clipboard");
    } catch (e) {
      toast("Couldn’t send: " + (e.message || "network error"));
    } finally {
      $("sendText").disabled = false;
    }
  });

  // Uploads one at a time with XHR, which (unlike fetch) reports upload progress.
  $("files").addEventListener("change", async (ev) => {
    const files = Array.from(ev.target.files || []);
    ev.target.value = "";
    for (const f of files) await upload(f);
  });

  function upload(file) {
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
      xhr.upload.onprogress = (e) => { if (e.lengthComputable) bar.value = e.loaded / e.total; };
      xhr.onload = () => {
        bar.remove();
        meta.textContent = xhr.status === 200 ? "Sent ✓" : (xhr.responseText || "Failed");
        resolve();
      };
      xhr.onerror = () => { bar.remove(); meta.textContent = "Failed"; resolve(); };
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
