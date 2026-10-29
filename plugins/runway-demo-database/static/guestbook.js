// Runway demo — guestbook list. Fills each .rw-guestbook-list from its data-src endpoint (never cached), a page at a
// time. Entries are inserted as text, never as HTML.
(function () {
  "use strict";

  function item(entry, lang) {
    var li = document.createElement("li");
    var who = document.createElement("strong");
    who.textContent = entry.name;
    var when = document.createElement("time");
    when.dateTime = entry.at;
    when.textContent = new Date(entry.at).toLocaleString(lang || undefined);
    var text = document.createElement("p");
    text.textContent = entry.message;
    li.appendChild(who);
    li.appendChild(document.createTextNode(" "));
    li.appendChild(when);
    li.appendChild(text);
    return li;
  }

  function note(list, message) {
    var li = document.createElement("li");
    li.className = "rw-guestbook-empty";
    li.textContent = message;
    list.appendChild(li);
  }

  function load(list, page) {
    var url = list.dataset.src + "?page=" + page;
    fetch(url, { credentials: "same-origin", cache: "no-store" })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var old = list.querySelector(".rw-guestbook-more");
        if (old) { old.remove(); }
        if (!data.available) { note(list, list.dataset.unavailable); return; }
        if (page === 0 && data.entries.length === 0) { note(list, list.dataset.empty); return; }
        data.entries.forEach(function (e) { list.appendChild(item(e, list.dataset.lang)); });
        if (data.more) {
          var li = document.createElement("li");
          li.className = "rw-guestbook-more";
          var button = document.createElement("button");
          button.type = "button";
          button.textContent = list.dataset.more;
          button.addEventListener("click", function () { load(list, page + 1); });
          li.appendChild(button);
          list.appendChild(li);
        }
      })
      .catch(function () { note(list, list.dataset.unavailable); });
  }

  function start() {
    document.querySelectorAll(".rw-guestbook-list:not([data-loaded])").forEach(function (list) {
      list.dataset.loaded = "1";
      load(list, 0);
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
