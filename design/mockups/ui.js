// Small helpers shared by the mock-up pages.

export const ic = (name, cls = "") =>
  `<span class="i ${cls}" style="--src:url(../icons/${name}.svg)"></span>`;

/** Fills every [data-icon="name"] placeholder with the icon. Optional data-size="16|24". */
export const hydrateIcons = (root = document) => {
  for (const node of root.querySelectorAll("[data-icon]")) {
    const size = node.dataset.size ? `i-${node.dataset.size}` : "";
    node.outerHTML = ic(node.dataset.icon, size);
  }
};

/** Device status cluster for the tablet top bar: [{ icon, state: "ok" | "lost" | "off" }]. */
export const deviceCluster = (devices) =>
  `<div style="display:flex;gap:4px;align-items:center">${devices
    .map((d) => {
      const color =
        d.state === "ok"
          ? "var(--color-text-secondary)"
          : d.state === "lost"
            ? "var(--color-text-primary)"
            : "var(--color-text-tertiary)";
      const badge = d.state === "lost" ? ic("triangle-alert", "i-16") : "";
      const label = d.label ? `<span class="t-label num">${d.label}</span>` : "";
      return `<span class="chip" style="background:transparent;padding:0 8px;color:${color}">${ic(d.icon, "i-16")}${label}${badge}</span>`;
    })
    .join("")}</div>`;
