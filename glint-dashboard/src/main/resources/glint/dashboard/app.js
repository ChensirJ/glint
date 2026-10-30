(function () {
  const base = document.location.pathname.replace(/\/index\.html$/, "").replace(/\/?$/, "");

  async function jget(path) {
    const r = await fetch(base + path, { cache: "no-store" });
    if (!r.ok) throw new Error(path + " " + r.status);
    return r.json();
  }

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function sparkSvg(p50, p99) {
    const a = Math.max(0.1, Number(p50) || 0.1);
    const b = Math.max(a, Number(p99) || a);
    const h = 28, w = 88;
    const y1 = h - Math.min(h - 2, (a / b) * (h - 4)) - 1;
    const y2 = 2;
    return (
      '<svg class="spark" viewBox="0 0 ' + w + " " + h + '" xmlns="http://www.w3.org/2000/svg">' +
      '<defs><linearGradient id="sg" x1="0" y1="0" x2="1" y2="0">' +
      '<stop stop-color="#7dd3fc"/><stop offset="1" stop-color="#a78bfa"/></linearGradient></defs>' +
      '<path d="M2 ' + y1 + " L" + (w / 2) + " " + y1 + " L" + (w - 2) + " " + y2 +
      '" fill="none" stroke="url(#sg)" stroke-width="2" stroke-linecap="round"/>' +
      '<circle cx="' + (w - 2) + '" cy="' + y2 + '" r="2.2" fill="#a78bfa"/>' +
      "</svg>"
    );
  }

  function renderWaterfall(spans) {
    const demo = [
      { name: "auth.check", ms: 4, cls: "" },
      { name: "db.findUser", ms: 86, cls: "db" },
      { name: "cache.fill", ms: 12, cls: "" },
      { name: "synchronized*", ms: 41, cls: "lock" },
      { name: "json.render", ms: 3, cls: "" }
    ];
    const list = spans && spans.length ? spans : demo;
    const max = Math.max(...list.map((s) => s.ms), 1);
    const root = document.getElementById("waterfall");
    root.innerHTML = "";
    list.forEach((s) => {
      const row = document.createElement("div");
      row.className = "wf-row";
      const label = document.createElement("div");
      label.textContent = s.name;
      const track = document.createElement("div");
      track.className = "wf-track";
      const bar = document.createElement("div");
      bar.className = "wf-bar " + (s.cls || (String(s.name).includes("db") ? "db" : ""));
      if (String(s.name).toLowerCase().includes("sync") || String(s.name).includes("lock")) {
        bar.className = "wf-bar lock";
      }
      bar.style.width = Math.max(6, (s.ms / max) * 100) + "%";
      track.appendChild(bar);
      const ms = document.createElement("div");
      ms.className = "wf-ms";
      ms.textContent = Number(s.ms).toFixed(1) + " ms";
      row.append(label, track, ms);
      root.appendChild(row);
    });
    document.getElementById("waterfallHint").textContent =
      spans && spans.length
        ? "Spans from the latest slow request."
        : "Illustrative spans - live data appears after a slow request.";
  }

  function renderPins(events) {
    const list = document.getElementById("pinningList");
    const msg = document.getElementById("pinningMsg");
    const chip = document.getElementById("pinChip");
    list.innerHTML = "";
    if (!events || !events.length) {
      chip.classList.add("hidden");
      msg.textContent =
        "No pinning detected yet. On JDK 21, blocking inside synchronized pins the virtual thread to its carrier.";
      return;
    }
    chip.classList.remove("hidden");
    msg.textContent =
      events.length +
      " recent pinning event(s). Upgrade libraries that block inside synchronized, or switch to ReentrantLock.";
    events.slice(0, 5).forEach((e) => {
      const div = document.createElement("div");
      div.className = "pin-card";
      div.innerHTML =
        '<div class="title"><strong>' +
        esc(e.durationMs) +
        " ms · " +
        esc(e.path || "-") +
        "</strong><span>" +
        esc(e.reqId || "-") +
        "</span></div><pre>" +
        esc(e.stack || "") +
        "</pre>";
      list.appendChild(div);
    });
  }

  function renderSlow(items) {
    const ul = document.getElementById("slowList");
    const empty = document.getElementById("slowEmpty");
    ul.innerHTML = "";
    if (!items || !items.length) {
      empty.classList.remove("hidden");
      document.getElementById("slowCount").textContent = "0";
      return;
    }
    empty.classList.add("hidden");
    document.getElementById("slowCount").textContent = String(items.length);
    items.slice(0, 8).forEach((it) => {
      const li = document.createElement("li");
      li.className = "slow-item";
      const spans = (it.spans || [])
        .map((s) => "  " + s.name + "  " + Number(s.ms).toFixed(1) + " ms")
        .join("\n");
      li.innerHTML =
        '<div class="title"><strong>' +
        esc(it.path) +
        "</strong><span>" +
        Number(it.durationMs).toFixed(1) +
        " ms</span></div>" +
        (spans ? '<div class="spans">' + esc(spans) + "</div>" : "");
      ul.appendChild(li);
    });
  }

  async function refresh() {
    try {
      const [health, metrics, threads, slow, pins] = await Promise.all([
        jget("/api/health"),
        jget("/api/metrics"),
        jget("/api/threads"),
        jget("/api/slow").catch(() => ({ items: [] })),
        jget("/api/pinning").catch(() => ({ events: [] }))
      ]);

      const pill = document.getElementById("healthPill");
      pill.classList.remove("bad");
      document.getElementById("health").textContent =
        "UP · Java " + (health.java || "?");

      const vt = threads.virtualThreadsApprox;
      const active = threads.activeRequests;
      document.getElementById("vtCount").textContent =
        vt != null ? vt : (active != null ? active : "-");
      document.getElementById("vtSub").textContent =
        "in-flight requests: " + (active != null ? active : "-");

      const pinN = threads.pinningEvents != null ? threads.pinningEvents
        : (metrics.pinningEvents != null ? metrics.pinningEvents : 0);
      document.getElementById("pinCount").textContent = pinN;

      const paths = metrics.paths || {};
      document.getElementById("pathCount").textContent = Object.keys(paths).length;

      const rl = metrics.rateLimit || {};
      const rejected = rl.rejected != null ? rl.rejected : 0;
      const allowed = rl.allowed != null ? rl.allowed : 0;
      document.getElementById("rlRejected").textContent = rejected;
      document.getElementById("rlSub").textContent =
        rejected + " rejected / " + allowed + " allowed";

      const st = metrics.staticFiles || {};
      const hits = st.cacheHits != null ? st.cacheHits : 0;
      const misses = st.cacheMisses != null ? st.cacheMisses : 0;
      const zc = st.zeroCopySends != null ? st.zeroCopySends : 0;
      document.getElementById("stHits").textContent = hits;
      document.getElementById("stSub").textContent =
        hits + " hits / " + misses + " misses, zero-copy " + zc;

      const tbody = document.querySelector("#latencyTable tbody");
      tbody.innerHTML = "";
      const keys = Object.keys(paths).sort();
      document.getElementById("latencyEmpty").classList.toggle("hidden", keys.length > 0);
      keys.forEach((p) => {
        const m = paths[p];
        const tr = document.createElement("tr");
        tr.innerHTML =
          '<td class="path">' +
          esc(p) +
          "</td>" +
          "<td>" +
          m.count +
          "</td>" +
          "<td>" +
          Number(m.p50Ms).toFixed(2) +
          " ms</td>" +
          "<td>" +
          Number(m.p99Ms).toFixed(2) +
          " ms</td>" +
          "<td>" +
          sparkSvg(m.p50Ms, m.p99Ms) +
          "</td>";
        tbody.appendChild(tr);
      });

      const slowItems = slow.items || [];
      renderSlow(slowItems);
      const latestSpans = slowItems.length
        ? (slowItems[0].spans || []).map((s) => ({ name: s.name, ms: s.ms }))
        : null;
      renderWaterfall(latestSpans);
      renderPins(pins.events || []);
    } catch (e) {
      const pill = document.getElementById("healthPill");
      pill.classList.add("bad");
      document.getElementById("health").textContent = "Unreachable";
    }
  }

  renderWaterfall(null);
  refresh();
  setInterval(refresh, 2000);
})();
