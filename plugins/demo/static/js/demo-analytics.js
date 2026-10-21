/*
 * runway-demo — an inert demo script served at /__plugin/runway-demo/static/js/demo-analytics.js.
 * Referenced by the DemoAnalyticsHead <script> tag (only when per-site analytics is enabled AND the app-level write
 * key is set) purely to demonstrate the per-plugin static-asset seam. It does nothing but log; a real analytics
 * plugin would emit the vendor snippet here. A plain <script src> is all a plugin emits: the kernel stamps the CSP
 * nonce on it at the response edge.
 */
(function () {
  "use strict";
  var el = document.currentScript;
  var site = el && el.getAttribute("data-site");
  console.debug("[runway-demo] analytics placeholder loaded for site:", site || "(unknown)");
})();
