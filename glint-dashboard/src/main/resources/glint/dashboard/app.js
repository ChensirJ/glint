(function () {
  const base = document.location.pathname.replace(/\/?$/, "");

  async function jget(path) {
    const r = await fetch(base + path, { cache: "no-store" });
    if (!r.ok) throw new Error(path + " " + r.status);
    return r.json();
  }

  function renderWaterfall() {
    // Demo spans so the README screenshot looks complete out of the box.
    const spans = [
      { name: "auth.check", ms: 4, cls: "" },
      { name: "db.findUser", ms: 86, cls: "db" },
      { name: "cache.fill", ms: 12, cls: "" },
      { name: "synchronized*", ms: 41, cls: "lock" },
      { name: "json.render", ms: 3, cls: "" }
    ];
    const max = Math.max(...spans.map((s) => s.ms));
    const root = document.getElementById("waterfall");
    root.innerHTML = "";
    spans.forEach((s) => {
      const row = document.createElement("div");
      row.className = "wf-row";
      const label = document.createElement("div");
      label.textContent = s.name;
      const track = document.createElement("div");
      const bar = document.createElement("div");
      bar.className = "wf-bar " + s.cls;
      bar.style.width = Math.max(6, (s.ms / max) * 100) + "%";
      track.appendChild(bar);
      const ms = document.createElement("div");
      ms.className = "wf-ms";
      ms.textContent = s.ms + " ms";
      row.append(label, track, ms);
      root.appendChild(row);
    });
  }

  async function refresh() {
    try {
      const [health, metrics, threads] = await Promise.all([
        jget("/api/health"),
        jget("/api/metrics"),
        jget("/api/threads")
      ]);
      document.getElementById("health").textContent =
        "UP · Java " + (health.java || "?");
      document.getElementById("vtCount").textContent =
        threads.virtualThreadsApprox ?? "—";
      const pins = threads.pinningEvents ?? metrics.pinningEvents ?? 0;
      document.getElementById("pinCount").textContent = pins;
      const paths = metrics.paths || {};
      document.getElementById("pathCount").textContent = Object.keys(paths).length;

      const tbody = document.querySelector("#latencyTable tbody");
      tbody.innerHTML = "";
      Object.keys(paths).sort().forEach((p) => {
        const m = paths[p];
        const tr = document.createElement("tr");
        tr.innerHTML =
          "<td>" + p + "</td>" +
          "<td>" + m.count + "</td>" +
          "<td>" + Number(m.p50Ms).toFixed(2) + " ms</td>" +
          "<td>" + Number(m.p99Ms).toFixed(2) + " ms</td>";
        tbody.appendChild(tr);
      });

      const msg = document.getElementById("pinningMsg");
      const sample = document.getElementById("pinningSample");
      if (pins > 0) {
        msg.textContent =
          pins + " pinning event(s) recorded. Check stderr for stack traces correlated to request id/path.";
        sample.classList.remove("hidden");
        sample.textContent =
          "[PINNED 23ms] req=… path=/users/42\n" +
          "  java.base/java.lang.VirtualThread$VThreadContinuation.onPinned\n" +
          "  app.Repo.find(Repo.java:42)  << synchronized block";
      } else {
        msg.innerHTML =
          "No pinning detected yet. On JDK 21, blocking inside <code>synchronized</code> will show up here.";
        sample.classList.add("hidden");
      }
    } catch (e) {
      document.getElementById("health").textContent = "Unreachable";
      document.getElementById("health").style.color = "#ff6b8a";
    }
  }

  renderWaterfall();
  refresh();
  setInterval(refresh, 2000);
})();
