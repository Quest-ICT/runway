/*
 * Invisible reCAPTCHA-v3 client — shared by the operator login/recovery pages AND the protection plugin's
 * injected widgets, served once from /__assets/captcha-v3.js so no page needs an inline <script> (that is what
 * lets script-src drop 'unsafe-inline'). It wires every `input.rw-captcha-v3` on the page to its form: on the
 * first submit it fetches a fresh token, writes it into the hidden field, and re-submits. Idempotent per field;
 * if the grecaptcha API failed to load it falls back to a plain submit, so a provider outage never blocks a form.
 * Inert on pages with no such field (or whose field carries no data-sitekey), so it is safe to load everywhere.
 * The reCAPTCHA action label comes from the field's optional data-action (default "submit").
 */
(function () {
    function wire(el) {
        if (el.dataset.rwWired) { return; }
        el.dataset.rwWired = '1';
        var key = el.dataset.sitekey;
        if (!key) { return; } // no v3 site key on this field → nothing to do (e.g. a visible provider is selected)
        var form = el.closest('form');
        if (!form) { return; }
        var action = el.dataset.action || 'submit';
        form.addEventListener('submit', function (ev) {
            if (form.dataset.rwCaptchaReady === '1') { return; } // second pass: let it post
            ev.preventDefault();
            if (typeof grecaptcha === 'undefined') { // API failed to load — degrade to a plain submit
                form.dataset.rwCaptchaReady = '1';
                form.submit();
                return;
            }
            grecaptcha.ready(function () {
                grecaptcha.execute(key, { action: action }).then(function (token) {
                    el.value = token;
                    form.dataset.rwCaptchaReady = '1';
                    form.submit();
                });
            });
        });
    }

    function scan() {
        var els = document.querySelectorAll('input.rw-captcha-v3');
        for (var i = 0; i < els.length; i++) { wire(els[i]); }
    }

    if (document.readyState !== 'loading') { scan(); }
    else { document.addEventListener('DOMContentLoaded', scan); }
})();
