/* Externalized from restarting.html so the console page carries no inline <script> — that is what lets the CSP
   script-src drop 'unsafe-inline'. Loaded deferred; behaviour is identical to the former inline block. */
(function () {
    // Land on a FRESH login after the restart: the old in-memory session (and its CSRF token) is gone, so a
    // clean sign-in gives a new session + token and avoids a stale-token 403 on the next action.
    var target = '/app/login?restarted', started = Date.now(), HARD = 120000, sawDown = false;
    function manual() { var n = document.getElementById('rs-note'); if (n) { n.innerHTML = 'This is taking longer than usual — <a href="' + target + '">sign in</a>.'; } }
    function tick() {
        if (Date.now() - started > HARD) { manual(); return; }
        fetch('/actuator/health', { cache: 'no-store' }).then(function (r) {
            if (r && r.ok) { if (sawDown) { window.location.replace(target); return; } setTimeout(tick, 1500); }
            else { sawDown = true; setTimeout(tick, 1500); }
        }).catch(function () { sawDown = true; setTimeout(tick, 1500); });
    }
    setTimeout(tick, 1500);
})();
