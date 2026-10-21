/* Runway Carousel Manager — behaviour. Served at /__plugin/carousel-manager/static/carousel.js.
   Idempotent: initializes every .rw-carousel not already initialized, so the widget can include this script
   once per instance without double-binding. No dependencies. */
(function () {
    "use strict";

    function init(root) {
        if (root.dataset.rwInit === "1") { return; }
        root.dataset.rwInit = "1";

        var slides = Array.prototype.slice.call(root.querySelectorAll(".rw-carousel-slide"));
        if (slides.length === 0) { return; }
        var index = slides.findIndex(function (s) { return s.classList.contains("is-active"); });
        if (index < 0) { index = 0; }

        function show(i) {
            index = (i + slides.length) % slides.length;
            slides.forEach(function (s, k) { s.classList.toggle("is-active", k === index); });
        }

        var prev = root.querySelector(".rw-carousel-prev");
        var next = root.querySelector(".rw-carousel-next");
        if (prev) { prev.addEventListener("click", function () { show(index - 1); pause(); }); }
        if (next) { next.addEventListener("click", function () { show(index + 1); pause(); }); }

        var interval = parseInt(root.dataset.interval, 10);
        var timer = null;
        function play() {
            if (!(interval > 0) || slides.length < 2) { return; }
            timer = window.setInterval(function () { show(index + 1); }, interval * 1000);
        }
        function pause() { if (timer) { window.clearInterval(timer); timer = null; } }

        root.addEventListener("mouseenter", pause);
        root.addEventListener("mouseleave", play);
        show(index);
        play();
    }

    function scan() {
        Array.prototype.forEach.call(document.querySelectorAll(".rw-carousel"), init);
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", scan);
    } else {
        scan();
    }
})();
