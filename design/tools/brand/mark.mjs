// The Overdrive mark: a two-turn spiral whose outer turn leaves the ring as a sharp tail (design/brand.md).
// Pure geometry, no dependencies. glyph() returns the filled outline of the tapered stroke as one SVG path in a 1000-unit box,
// its longest side exactly 1000 and centred on (500, 500), and the stroke of the rings 31 units wide.

const smooth = (x) => {
  const t = Math.max(0, Math.min(1, x));
  return t * t * (3 - 2 * t);
};

/** Points (x, y, half width) along the spiral, counter-clockwise on screen, from the inner start to the tail tip. */
function centreline(W, n = 900) {
  const turns = 2.0;
  const end = (-35 * Math.PI) / 180; // the tail tip points to the upper right
  const start = end + 2 * Math.PI * turns;
  const [R1, R2, RT] = [215, 278, 540]; // inner ring, outer ring, where the tail ends
  const pts = [];
  for (let i = 0; i <= n; i++) {
    const s = i / n;
    const th = start - 2 * Math.PI * turns * s;
    let r = R1 + (R2 - R1) * smooth((s - 0.4) / 0.15);
    r += (RT - R2) * smooth((s - 0.8) / 0.2) ** 1.7;
    const w = s > 0.8 ? W * (1 - smooth((s - 0.8) / 0.2)) ** 0.55 : W * smooth(s / 0.04);
    pts.push([500 + r * Math.cos(th), 500 + r * Math.sin(th), w]);
  }
  return pts;
}

function outline(pts) {
  const left = [];
  const right = [];
  pts.forEach(([x, y, w], i) => {
    const [x0, y0] = pts[Math.max(0, i - 1)];
    const [x1, y1] = pts[Math.min(pts.length - 1, i + 1)];
    const dx = x1 - x0;
    const dy = y1 - y0;
    const d = Math.hypot(dx, dy) || 1;
    left.push([x - (dy / d) * w, y + (dx / d) * w]);
    right.push([x + (dy / d) * w, y - (dx / d) * w]);
  });
  return [...left, ...right.reverse()];
}

export function glyph(points = 900) {
  let W = 15.5;
  let pts = [];
  let k = 1;
  let box = [0, 0, 0, 0];
  for (let pass = 0; pass < 3; pass++) {
    pts = outline(centreline(W, points));
    const xs = pts.map((p) => p[0]);
    const ys = pts.map((p) => p[1]);
    box = [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
    k = 1000 / Math.max(box[2] - box[0], box[3] - box[1]);
    W = 15.5 / k;
  }
  const cx = (box[0] + box[2]) / 2;
  const cy = (box[1] + box[3]) / 2;
  const norm = pts.map(([x, y]) => [500 + (x - cx) * k, 500 + (y - cy) * k]);
  const d = `M${norm.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(" L")}Z`;
  const xs = norm.map((p) => p[0]);
  const ys = norm.map((p) => p[1]);
  return { d, bbox: [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)] };
}
