import { useEffect, useRef, useState } from 'react';
import {
  BarController, BarElement, CategoryScale, Chart, Filler, LinearScale, LineController, LineElement, PointElement, Tooltip,
} from 'chart.js';

// Register only what the two charts use (keeps the bundle small).
Chart.register(LineController, LineElement, PointElement, BarController, BarElement, CategoryScale, LinearScale, Filler, Tooltip);

// Design tokens (same values as styles.css). One series per chart -> one colour, no legend (the card title names it).
const C = {
  series: '#1f56d6',         // --blue-600, validated: passes lightness, chroma and 3:1 contrast on white
  wash: 'rgba(31, 86, 214, 0.10)',
  grid: '#e7ebf1',
  axis: '#c9d0db',
  text: '#586579',           // --gray-600 (text never wears the series colour)
  ink: '#172033',
  surface: '#ffffff',
};
const FONT = { family: "'Segoe UI', system-ui, -apple-system, Roboto, Arial, sans-serif", size: 12 };

const fmtDay = (iso, long = false) => new Date(`${iso}T00:00:00`).toLocaleDateString(undefined,
  long ? { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' } : { day: 'numeric', month: 'short' });
const fmtNum = (n) => n.toLocaleString();

/** Creates the chart once, updates it in place when data changes, destroys it on unmount. */
function useChart(build, deps) {
  const canvas = useRef(null);
  const chart = useRef(null);
  useEffect(() => {
    const config = build();
    if (chart.current) {
      chart.current.data = config.data;
      chart.current.options = config.options;
      chart.current.update();
    } else {
      chart.current = new Chart(canvas.current, config);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => () => { chart.current?.destroy(); chart.current = null; }, []);
  return canvas;
}

// ---------------------------------------------------------------- small plugins

/** Vertical hairline at the hovered day (crosshair for the line chart). */
const crosshair = {
  id: 'crosshair',
  afterDatasetsDraw(chart) {
    const active = chart.tooltip?.getActiveElements?.() ?? [];
    if (!active.length) return;
    const { ctx, chartArea } = chart;
    const x = active[0].element.x;
    ctx.save();
    ctx.strokeStyle = C.axis;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(x, chartArea.top);
    ctx.lineTo(x, chartArea.bottom);
    ctx.stroke();
    ctx.restore();
  },
};

/** Value label at the end of the line (selective direct label: the latest day only). */
const endLabel = {
  id: 'endLabel',
  afterDatasetsDraw(chart) {
    const meta = chart.getDatasetMeta(0);
    const last = meta.data.at(-1);
    const value = chart.data.datasets[0].data.at(-1);
    if (!last || value == null) return;
    const { ctx } = chart;
    ctx.save();
    ctx.font = `600 12px ${FONT.family}`;
    ctx.fillStyle = C.ink;
    ctx.textAlign = 'right';
    ctx.textBaseline = 'bottom';
    ctx.fillText(fmtNum(value), last.x - 6, last.y - 8);
    ctx.restore();
  },
};

/** Count at the tip of every bar (few bars, so each is labelled; text in ink, not the series colour). */
const barValues = {
  id: 'barValues',
  afterDatasetsDraw(chart) {
    const { ctx } = chart;
    const meta = chart.getDatasetMeta(0);
    ctx.save();
    ctx.font = `600 12px ${FONT.family}`;
    ctx.fillStyle = C.ink;
    ctx.textBaseline = 'middle';
    ctx.textAlign = 'left';
    meta.data.forEach((bar, i) => {
      ctx.fillText(fmtNum(chart.data.datasets[0].data[i]), bar.x + 6, bar.y);
    });
    ctx.restore();
  },
};

const tooltipBase = {
  backgroundColor: '#0d1b36',
  titleColor: '#ffffff',
  bodyColor: '#dde7fb',
  titleFont: { ...FONT, weight: '600' },
  bodyFont: FONT,
  padding: 10,
  cornerRadius: 6,
  displayColors: false,
};

const axisBase = {
  border: { color: C.axis },
  ticks: { color: C.text, font: FONT },
};

// ---------------------------------------------------------------- charts

/** IDs issued per day: line, 2px, 10% wash, end dot with a surface ring, crosshair + tooltip. */
export function TrendChart({ daily, label }) {
  const canvas = useChart(() => ({
    type: 'line',
    data: {
      labels: daily.map((d) => fmtDay(d.date)),
      datasets: [{
        data: daily.map((d) => d.count),
        borderColor: C.series,
        borderWidth: 2,
        borderJoinStyle: 'round',
        borderCapStyle: 'round',
        backgroundColor: C.wash,
        fill: 'origin',
        tension: 0,
        pointRadius: daily.map((_, i) => (i === daily.length - 1 ? 4 : 0)),
        pointHoverRadius: 5,
        pointBackgroundColor: C.series,
        pointBorderColor: C.surface,
        pointBorderWidth: 2,
        pointHitRadius: 12,
      }],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      interaction: { mode: 'index', intersect: false },
      layout: { padding: { top: 18, right: 8 } },
      scales: {
        x: {
          ...axisBase,
          grid: { display: false },
          // Labels counted back from the LAST day, so today is always labelled (autoSkip could drop it).
          ticks: {
            ...axisBase.ticks,
            maxRotation: 0,
            autoSkip: false,
            callback: (_, i) => ((daily.length - 1 - i) % (daily.length > 10 ? 4 : 1) === 0 ? fmtDay(daily[i].date) : ''),
          },
        },
        y: {
          ...axisBase,
          beginAtZero: true,
          grid: { color: C.grid, lineWidth: 1 },
          border: { display: false },
          ticks: { ...axisBase.ticks, precision: 0, maxTicksLimit: 6, callback: (v) => fmtNum(v) },
        },
      },
      plugins: {
        tooltip: {
          ...tooltipBase,
          callbacks: {
            title: (items) => fmtDay(daily[items[0].dataIndex].date, true),
            label: (item) => `${fmtNum(item.parsed.y)} ${item.parsed.y === 1 ? 'ID' : 'IDs'} issued`,
          },
        },
      },
    },
    plugins: [crosshair, endLabel],
  }), [daily]);
  return <canvas ref={canvas} role="img" aria-label={label} />;
}

/** IDs per template: horizontal bars, max 24px thick, 4px rounded tip, square at the baseline, value at the tip. */
export function TemplateBarChart({ rows, label }) {
  const canvas = useChart(() => ({
    type: 'bar',
    data: {
      labels: rows.map((r) => r.templateId),
      datasets: [{
        data: rows.map((r) => r.count),
        backgroundColor: C.series,
        hoverBackgroundColor: '#1a44b8',
        maxBarThickness: 24,
        borderRadius: 4,
        borderSkipped: 'start',
        minBarLength: 0,
      }],
    },
    options: {
      indexAxis: 'y',
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      layout: { padding: { right: 44 } },
      scales: {
        x: {
          ...axisBase,
          beginAtZero: true,
          grid: { color: C.grid, lineWidth: 1 },
          border: { display: false },
          ticks: { ...axisBase.ticks, precision: 0, maxTicksLimit: 6, callback: (v) => fmtNum(v) },
        },
        y: { ...axisBase, grid: { display: false }, ticks: { ...axisBase.ticks, color: C.ink, font: { ...FONT, family: 'ui-monospace, Menlo, Consolas, monospace' } } },
      },
      plugins: {
        tooltip: {
          ...tooltipBase,
          callbacks: {
            title: (items) => `${rows[items[0].dataIndex].templateId} · ${rows[items[0].dataIndex].name}`,
            label: (item) => `${fmtNum(item.parsed.x)} ${item.parsed.x === 1 ? 'ID' : 'IDs'} issued`,
          },
        },
      },
    },
    plugins: [barValues],
  }), [rows]);
  // Height follows the number of templates so bars never squash.
  return (
    <div style={{ position: 'relative', height: Math.max(160, rows.length * 38 + 40) }}>
      <canvas ref={canvas} role="img" aria-label={label} />
    </div>
  );
}

/** Accessible table view of a chart's numbers, toggled by a button. */
export function DataTable({ caption, columns, rows }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="chart-table">
      <button type="button" className="btn btn-ghost btn-sm" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        {open ? 'Hide data table' : 'Show data table'}
      </button>
      {open && (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">{caption}</caption>
            <thead><tr>{columns.map((c) => <th key={c} scope="col">{c}</th>)}</tr></thead>
            <tbody>
              {rows.map((r) => <tr key={r[0]}>{r.map((v, i) => <td key={i}>{typeof v === 'number' ? fmtNum(v) : v}</td>)}</tr>)}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export { fmtDay, fmtNum };
