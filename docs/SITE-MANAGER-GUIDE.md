# Runway — Site Manager & Designer Guide

> **Audience:** **site managers** (the people who run an individual site) **and limited-access
> users** such as **designers** who log in only to edit pages.
> **What this is:** how to configure a site and its plugins, manage its users and their
> permissions, and create and author its pages.

> **Doc status.** Every section covering a built capability is written; this document is self-contained and
> carries no `_Pending_` placeholders.

> **How to read this:** sections 1–12 are the full site-manager picture. If you are a
> **designer** with limited access, start at **§13 — For designers** and follow its pointers
> back into §6–§7 as needed.

---

## 1. Overview & roles at a glance

A **site** is one self-contained tenant on the platform: its own pages, templates, static
assets, users, roles, and per-plugin configuration. As a **site manager** you control
**everything within your site — and nothing outside it.** You can never reach another site's
data, nor the platform's own configuration; that boundary is enforced by the server on every
request, not merely hidden in the UI. It is a foundational design decision, not a setting: a
mistake or a compromise inside one site stays contained to that site.

**Two worlds that never cross.** The platform has two completely separate sign-in worlds
("realms"), each with its own login page and its own user store:

- the **application realm** — the operators who run the *platform itself* (create sites,
  install plugins, configure the server);
- the **site realm** — the people who manage an *individual site* (you).

Authenticating in one **never** grants the other. There is no shared account, no "escalate to
the platform" path. A platform operator cannot log in and edit your pages as if they were one
of your users, and you cannot reach the platform console. This keeps each side's blast radius
to itself.

**The accounts inside your site:**

| Account | What it is | Reach |
|---------|------------|-------|
| **Site superadmin** (master) | Every site is created with exactly one top account. It fully manages the site and is the only account that may **restore a backup**. | The whole site. **Cannot be removed, disabled, or demoted** from inside the site — there is always a way back in. Only the platform operator can suspend it (for example while the account is investigated), and only they can resume it. |
| **Administrator** | A full-access site user (the `ADMIN` role). Does everything a manager does day to day. | The whole site, except master-only operations (e.g. restore). |
| **Operator** | A basic seed user (the `OPERATOR` role) — a starting point you grant real permissions onto. | Only what it has been granted. |
| **Custom users** | Any user you create, carrying the roles and individual grants you assign. | Only what they have been granted; confined to **this one site**. |

A site superadmin **may be assigned to more than one site** (the platform operator does that
assignment) and then manages each of them — but it still **cannot operate at the application
level**. Every other user you create belongs to **exactly one site**.

**"Designer" is a persona, not an account type.** There is no separate designer login or role.
A designer is simply a user you have granted page-editing access to (see **§5**, **§13**). They
may work entirely outside the platform in plain HTML and hand you finished files, or log in and
edit in the admin UI — either way, the same permission gates the work.

---

## 2. Signing in to site administration

Your site answers on its own hostname. Sign in at **`/login`** on that host — for example
`https://your-site.example/login`. This is the **site** login; it is a different page and a
different account store from the platform operators' login (`/app/login`), and the two never
mix (**§1**).

On success you land in the **site console at `/__admin`** — your dashboard, with the admin
navigation across the top:

The links are organized into **dropdown groups** so the bar stays short:

| Group | Menu item | Where it goes | Covered in |
|-------|-----------|---------------|------------|
| — | **Dashboard** | `/__admin` | this section |
| **Resources** | Pages | `/__admin/pages` | §6, §7 |
| **Resources** | Review | `/__admin/review` | §10 |
| **Resources** | Fragments | `/__admin/fragments` | §6 |
| **Resources** | Content widgets | `/__admin/widgets` | §8 |
| **Resources** | Documents | `/__admin/documents` | §11 |
| **Resources** | Static files | `/__admin/static` | §11.1 |
| **Resources** | Messages | `/__admin/messages` | §9 |
| **Access** | Users | `/__admin/users` | §4 |
| **Access** | Permissions | `/__admin/permissions` | §5 |
| **Activity** | Insights | `/__admin/insights` | §10 |
| **Activity** | Bandwidth | `/__admin/bandwidth` | §3 |
| **Activity** | Logs | `/__admin/logs` | §3 |
| **Settings** | Site config | `/__admin/config` | §3 |
| **Settings** | SEO | `/__admin/seo` | §10 |
| **Settings** | Icons | `/__admin/icons` | §3 |
| **Settings** | Web app manifest | `/__admin/manifest` | §3 |
| **Settings** | Backup & restore | `/__admin/backups` | §12 |
| **Settings** | Maintenance | `/__admin/maintenance` | §3 |
| **Settings** | *(each configurable plugin)* | `/__admin/plugins/<id>/settings` | §8 |

A badge in the top bar shows **"`<your-site-id>` site management"**, so you always know which
site you are working in (useful if you manage several). Whole groups or individual items you have
no permission for simply don't appear — an empty group hides itself. Each item is shown exactly when its
screen would let you in, by the same check: **Pages** needs *Edit pages* or *Disable pages*; Fragments,
Content widgets, Messages, Site config, SEO, Icons and Web app manifest need *Configure site*; Documents,
Backup & restore, Maintenance and **Insights** need their own permission; **Users** needs *Manage users*
or *Delete users*; **Permissions**, **Bandwidth** and **Logs** are for the site's superadmin and
administrators. Hiding a link is only a
convenience — every screen checks again on its own. Each plugin that offers
per-site configuration adds its own entry at the bottom of the **Settings** group (there is no
separate "Site settings" page any more).

**Your account.** Open the user menu (top-right) → **Account** (`/__admin/account`). **Your profile** sets
the display name shown in the top bar and the user lists, and your **recovery email**, where a "Forgot your
password?" link is sent; changing the email asks for your current password, so a session left open on someone
else's screen cannot redirect your password resets. **Change your password** takes the current password, the new
one and a confirmation. Five wrong current passwords in a row, on either form, sign that session out and show the
sign-in page (even when they are sent all at once); each wrong one is recorded in the site's log as
`account.password-check-failed` with the address it came from, and counts toward
the same lockout from your network as a failed sign-in. Changing it **signs out every other session
on your account** — another browser, another device, or your sign-in to another site you manage — on
its next click, while the session you changed it from stays signed in. If you think someone else knows
your password, changing it is how you throw them out. Every password field on the platform has a
**show/hide eye toggle** so you can verify what you typed before submitting; the toggle sits at
the tail of the tab order so it never interrupts your path from field to button.

**Forgot your password?** If your login page shows a **"Forgot your password?"** link, enter the
email address on your account and Runway emails you a one-hour, single-use link to set a new
password. It works only if your account has a **recovery email** set (§4) and the platform
operator has configured mail — if the link isn't shown, ask your operator to reset your password.
The link works only on **this site's** login; it never crosses to another site or to the platform
console.

**Signing out.** The user menu → **Sign out** ends your session and returns you to the site
login.

> **Verify:** after signing in, the top bar reads *"`<your-site-id>` site management"* and the
> URL is `/__admin`. If instead you see the platform console or an "application" badge, you
> signed in to the wrong realm — that cannot happen from `/login`, so re-check the host.

**Using the console with a keyboard or a screen reader.** Both consoles (this one and the platform
operator's) work without a mouse:

- **Skip to main content.** Press **Tab** once on any console screen: the first stop is a "Skip to main
  content" link (hidden until it has focus) that jumps past the menus.
- **Language.** The page language follows the console language you choose in the top bar, so a screen
  reader pronounces it correctly; the current language and the current menu entry are announced as such. The
  console offers English and Spanish unless your operator changed the list. Until you choose, the console follows your
  browser's language when it is offered. Your choice is remembered in this browser only, in a cookie of its own,
  so it never changes the language your visitors see (that is the site's own language choice, **§9**).
- **Code editors** (page HTML, decoupled logic, fragments): **Tab** indents inside the editor. To move on,
  press **Esc** and then **Tab** — the hint under the editor says so.
- **Listing tables** (pages, users, documents, …): focus a column header and press **Enter** to sort by it;
  **Down arrow** or **Shift+F10** opens the column menu (sort, order, move, widen/narrow); in the menu the
  arrows move, **Escape** goes back to the header and **Tab** closes it and moves on.
- The password **show/hide** eye is deliberately not a Tab stop — Tab goes from the password straight to
  the button; use the mouse or touch to reveal what you typed.

> **Verify:** on the Pages screen press Tab once — "Skip to main content" appears top-left; press Enter and
> the next Tab lands in the page content, not in the menu.

---

## 3. Site configuration

The **Settings** menu is a dropdown, not a page: it gathers the site-shaping screens (SEO, Icons,
Web app manifest, Backup & restore, Maintenance) and, at the bottom, one entry per plugin that
offers per-site configuration (see **§8**). There is no general "Site settings" landing page.

The site's **name**, its **default language and the set of languages it offers**, its public
**contact** details and its site-wide **template parameters** are edited on **Site config**
(`/__admin/config`, gated by *Configure site*), and templates show them through `${contact}` and `${params}`
(**§7**); the platform operator can also change the name and
languages from the application console. Its **hosts** (the addresses it answers on) and the origin its
absolute links are built on (canonical links, sitemap, social cards) are set by the platform operator; Site
config shows both read-only under **General** ("Addresses", "Links built on"). How the operator sets them, and the
site's other operator-held settings (suspension, the site files on the server), is in
[ADMINISTRATION.md](ADMINISTRATION.md) §10.

**Name and languages, in detail.** The **name** cannot be blank. It is the `og:site_name` a shared page carries
(and the shared title of a page that has none), and templates read it as `${site.displayName}` (**§7**). Languages
are language tags: `en`, `es`, `fr`, or with a region, `pt-BR`. *Add a language* takes one per save. A tag the
platform cannot read (`pt_BR`, with an underscore) is skipped without a message, so check the list after saving.
The default must be one of the offered languages; otherwise the save is refused. **Offer one variant per
language:** a page keeps its titles, descriptions and localized paths per language, and a language's addresses
start with the language alone (`/pt/…`), so `pt-BR` and `pt-PT` together would share all of them. When you remove a
language that pages still declare, the save names those pages; review them in the page editor. The site **id**,
shown in the top-bar badge, never changes: it is the name of the site's folder on the server. It is made of
letters, digits, `.`, `_` and `-`, and starts with a letter or digit.

**Template parameters on disk.** On the server they are the site's `params.yml`, one `key: value` per line. If a
hand edit gives a key a nested value (a list, or indented keys under it), the whole file cannot be read: the site
then has **no** parameters until the file is fixed, and the server log says so.

**Embedded content.** A browser loads a third party into your pages — a video player, a map, images from another
host — only if the page's security policy allows it, and that policy is the platform's. Under **Site config →
Embedded content** you see the hosts the platform operator has approved, each with what it may provide (for
example "frame-src" for an embedded player). Tick the ones your pages use and save: from the next page load your
**public** pages allow them (never your console). If a host you need is not listed, ask the operator to approve it.
Scripts from other hosts cannot be switched on here; they are the operator's decision for the whole platform.

Two further site-shaping controls you hold from the console:

- **which languages each page is published in** — set per page in the page editor (**§6**, **§9**);
- **how strictly publishing is governed** — the **Editorial workflow** on Site config: *Advisory*
  (the default), *Enforced* or *Reviewed* (**§10**). Changing it also needs *Approve pages*;
- **the site's look and chrome** — owned entirely by the site's own templates and static
  assets, which you edit as HTML/CSS (**§7**).

The **SEO** screen (`/__admin/seo`, gated by *Configure site*) is built — set your site's social-card,
search-engine-verification and extra-`<head>` metadata there (**§10**). The **Icons** screen
(`/__admin/icons`, same permission) is built too. The fastest path is **Generate from an SVG**: upload one
master SVG and the platform produces the whole raster set for you (apple-touch, 192, 512 and `favicon.ico`)
and keeps the SVG as your SVG favicon — existing slots are replaced. You can also upload any slot manually,
one at a time. The icons a page needs are referenced from its `<head>` automatically, and
`/manifest.webmanifest` is served with an icon list derived from the slots you actually provide; a slot you
leave empty simply isn't advertised. Each icon file, and the SVG you generate from, can be up to 256 KB. A
manual upload is stored as it is, without checking its format or size, so give each slot the file it expects:
an SVG for the SVG favicon, an `.ico` file for `favicon.ico`, and PNGs of 180×180 (apple-touch), 192×192 and
512×512 pixels — the sizes *Generate* produces (its `favicon.ico` is 32×32). The two favicons and the apple-touch
icon are linked from every page's `<head>`; the 192 and 512 PNGs and the SVG are listed in the manifest.
The SVG favicon is sent with a sandbox policy, so a script inside it cannot run if someone opens
`/favicon.svg` directly; browsers still show it as the icon.

The **Web app manifest** screen (`/__admin/manifest`, same permission) is the companion to Icons —
edit the manifest's name, short name, description, theme/background colors, display mode and language there;
those fields plus the icon slots make your site installable as a progressive web app. Colours are written as
`#rrggbb` (or `#rgb`, `#rrggbbaa`); the display mode is chosen from the four a browser knows (`fullscreen`,
`standalone`, `minimal-ui`, `browser`, or Default to let it decide); the language is a tag such as `en` or `pt-BR`.
A value outside these is refused with the reason, because a browser would silently ignore it.
What each field does: *Name* is the app's full name once installed; *Short name* is the label under its icon on a
home screen; *Description* is a sentence about it; *Theme color* tints the browser's toolbar, and is also sent as a
`theme-color` tag in every page's `<head>`; *Background color* fills the screen while the installed app starts;
*Language* is the language those texts are written in. The display modes: `fullscreen` uses the whole screen,
`standalone` opens in its own window like an app, `minimal-ui` does the same with a few browser controls, and
`browser` opens an ordinary tab. A field left empty is left out of the manifest; nothing is filled in from the site
name or the default language. While no field is set and no manifest icon (192, 512 or SVG) exists,
`/manifest.webmanifest` answers 404 and pages do not link to it.

The **Logs** screen (`/__admin/logs`) shows your site's own **access log** (public traffic — the most recent
lines; every sign-in attempt on your site's login page is there too, as a `POST /login`, and so is a request your
site refused, such as a `503` while it is in maintenance) and its **audit journal**: who did what on your site, and when. The journal records sign-ins
(`login.success`, `login.failure`) and sign-outs (`logout`), each with the address it came from (`ip=…`, in full:
the anonymizing setting below applies to the access log only); password recovery through an emailed link
(`recovery.requested`, `recovery.completed`); page changes (`page.saved`, `page.deleted`, `page.published`,
`page.unpublished`); user changes (`user.created`, `user.password-reset`, `user.deleted`); role and permission
changes on **Permissions** (`permissions.role-assigned`, `permissions.permission-granted`,
`permissions.role-created` and their counterparts); and settings changes — a plugin's settings
(`plugin.settings-changed`, naming the plugin), the site settings (`site-config.changed`, naming which parts —
name, languages, contact, parameters) and these log settings (`logs.settings-changed`). Only changes that took
effect are recorded, and **never a password, a reset link or a setting's value** — a template parameter or a
plugin setting may hold something you would not want copied into a log.

The journal is a table, newest first, 100 entries per page, covering everything your retention keeps (page
through it with **Older** / **Newer**). Filter by a **date range**, an **actor** and an **action** — both match
any part of the text, so action `page.` shows every page change. Each action is shown in your console language
with its key beneath it. **Download CSV** exports the filtered entries (up to 10 000 rows, times with their UTC
offset); a value a spreadsheet would treat as a formula is prefixed with `'` so it cannot run when opened. A
search reads a bounded amount of the journal; if it stops early the screen says so — narrow the dates. The
screen and the CSV are restricted to a site's administrators —
logs are strictly private to your site and are never visible to the platform operator or any other site.
From the settings panel you can **anonymize client IP addresses** in the access log (on by default: an IPv4
address is written with its last part as `0`, an IPv6 address keeps only its first three groups). The masking
happens when a line is written, so switching it changes only the lines written afterwards. You can also choose
how many **days** of logs to keep, 30 by default, up to the
platform's maximum (90 unless your operator changed it; the field's hint says it). A value above it is refused. If
the operator lowers the maximum below what you chose, your choice is kept, the logs are kept only up to the new
maximum, and the panel says so until you save a value within it. Logs are *transient*: they never travel in a backup, so restoring a site never carries old logs.

The **Bandwidth** screen (`/__admin/bandwidth`, site-admin only) charts your site's data transfer — a
30-day daily graph plus 24-hour / 7-day / 30-day totals. Like logs, the underlying stats are transient and
never travel in a backup. (The platform operator can also see an all-sites aggregate, but only these same
byte totals — never your logs or content.) The platform operator *may* set an outbound **bandwidth quota**
for your site. The screen shows it under the 30-day total ("Of a 50.0 GB cap per 30 days, set by the platform
operator", or "No cap on this site's traffic"). If your rolling-30-day traffic exceeds it, your **public** pages
return a temporary "unavailable" notice (503) while this console, its sign-in and password recovery stay reachable,
and the screen shows a red
warning. Contact your operator if you need a higher allowance: the cap is theirs and not editable from here, and a
raised cap reopens your site at once.

The **Maintenance** screen (`/__admin/maintenance`, gated by *Maintenance mode*) takes your **public**
site offline for maintenance: turn the switch on and visitors see a simple *"Down for maintenance"* page
(HTTP 503) carrying an optional message you provide — while your **console, login and password recovery
("Forgot your password?") stay reachable**, so you are never locked out and can switch it back off. The message is plain text, shown exactly as typed (no
formatting, no links), and is cut at 2,000 characters. Left empty, visitors read *"This site is temporarily down
for maintenance. Please check back shortly."* The maintenance page is the platform's own, in English whatever your
site's languages; it does not use your master or your error pages. It asks search engines to come back in an hour.
Turning maintenance off clears the message and the public site is live again immediately.

Maintenance is independent of an operator **suspension**, which takes precedence regardless of this switch. While
your site is suspended, **every** address on it — your public pages, `/login` and this console alike — answers
*"This site is temporarily unavailable."* (HTTP 503), so nobody can sign in until the operator resumes it. If you
see that message on `/login`, ask your operator.

_Per-site **styles** are your own files under `static/` (for example `static/css/site.css`), managed on the
**Static files** screen (**§11.1**) and linked from your master (**§7**)._

---

## 4. Users & access

Manage your site's users from **Users** (`/__admin/users`). The site superadmin and full-access
administrators reach this screen and can do everything on it. You can also **delegate** user management
without making someone an administrator, with two permissions (**§5**):

- ***Manage users*** opens the screen and lets the holder **create**, **edit**, **suspend**, **resume**
  and **reset the password** of users;
- ***Delete users*** lets the holder **delete** users (on its own, it shows the list with only the
  *Delete* buttons).

A delegated manager who is not an administrator works **within their own authority**: they create
**Operator** accounts only (never an *Administrator*), cannot add custom roles when creating a user,
cannot change anyone's role, and can act only on users who hold **nothing they do not hold themselves** —
never on an administrator, and never on an operator who was granted more than they were. Rows outside
that reach show no buttons, and the server refuses such an action with *"This account holds more than you
do"*. **Why:** resetting someone's password is taking over their account, so letting a helper reset an
administrator's password would make *Manage users* a back door to full access. Every action here affects
**only your site** — you can never see or touch another site's users.

**Create a user.** Fill the *Create a user* form: username, a starting password, a **role**
(*Operator* or *Administrator*), and optionally a display name and email. The new user belongs
to **exactly this site** and can sign in at your site's `/login`.

The username must be **free on this site**: not already used by another user of the site, and not the
name of a **superadmin** who manages the site. Names are compared ignoring upper/lower case and surrounding
spaces, so `Owner`, `owner` and ` OWNER ` are the same name. A taken name is refused with *"The username …
is already in use"*. **Why:** everyone signs in on the same `/login` form, so two accounts with one name
would be ambiguous — and a helper with *Manage users* could otherwise create a user named like the
superadmin, sign in under that name, and lock the real superadmin out. If an older install already has
such a pair, the **superadmin** is the one who signs in, and the server log names the clash; delete the
other user.

- Create as **Operator** for a basic account you'll then grant specific permissions to (**§5**)
  — the right choice for a **designer** who should only edit pages.
- Create as **Administrator** for a trusted full-access manager.

**Edit a user.** Use **Edit** on the user's row to change their **display name**, their **email** (the
address password recovery mails a reset link to) and their **role** (*Operator* ↔ *Administrator*).
The username — what they sign in with — does not change. A role change takes effect **immediately**,
even in a console tab the user already has open: their next click runs with the new role. You cannot
change your **own** role (you could demote yourself with nobody to undo it).

**Suspend and resume a user.** **Suspend** blocks an account without deleting it — for someone who
has left the team for a while, or an account you think someone else is using. A suspended user cannot
sign in (the login page just says the credentials are invalid), is **signed out on their next click**
if they have the console open, and cannot use *Forgot your password?*. Everything else about the
account — password, email, role, permissions — is kept, so **Resume** gives back exactly the access
they had. Suspended users carry a **suspended** badge in the table. You cannot suspend your own
account. Both actions are written to your site's **audit log** (**§3**, Logs) with your name.

**Reset a password.** Pick the user in *Reset a password*, type a new one, and submit. Any session
the user has open is **signed out on their next click**, so a reset also locks out whoever else may
have been using the old password. (Resetting your own password here keeps *your* session, like the
Account page. A user can also change their own password from their **Account** page — **§2** — and a
password set through a *Forgot your password?* link signs out their open sessions too.)

**Delete a user.** Use **Delete** in the users table. Deleting a user also ends any session they
still have open. Prefer **Suspend** when the person may come back.

> **Why you can't lock yourself out:** the site superadmin (master) account cannot be deleted,
> disabled, suspended or demoted from this screen — it is not even listed here, because it is managed
> by the platform operator — so there is always one account that can manage the site. Guarding the
> last way in is deliberate. The superadmin sets its own display name and **recovery email** on its
> **Account** page (**§2**), like any user; the platform operator can also set them on their *Site masters*
> screen. Without a recovery email, *Forgot your password?* cannot reach the superadmin.

**The permissions each user ends up with** — the roles they hold and any individual grants — are
managed on the **Permissions** screen (**§5**).

> **Verify:** after *Create user*, a green confirmation names the user and it appears in the
> table. Sign in as that user at `/login`; it lands in `/__admin` but sees only the menu items
> its permissions allow.

---

## 5. Roles & permissions

Open **Permissions** (`/__admin/permissions`) to see, for your site, every user with their
**roles** and **direct grants**, every **role** with the permissions it grants, and the forms to
assign them. Like everything else, this screen is scoped to your site alone — the realm is fixed
by *who you are*, so you can never drive another site's (or the platform's) permission matrix.

**The three seed roles**, arranged as a hierarchy:

| Role | Key | What it is |
|------|-----|------------|
| **Site superadmin** | `SUPERADMIN` | The immutable top of the hierarchy — the site's master account (**§1**). Always passes every check within the site; cannot be removed or demoted. |
| **Administrator** | `ADMIN` | Full access to the site, except master-only operations (like restoring a backup). |
| **Operator** | `OPERATOR` | An ordinary seed role — a starting point with no special power; you grant it exactly the permissions a user needs. |

You can also build **custom roles** and arrange them in a hierarchy: a parent role
**accumulates everything beneath it**. (The two reserved keys `SUPERADMIN` and `ADMIN` are
sealed — a custom role can neither be named one nor inherit one.)

**The model is additive — there is no "deny."** A user's effective access is the **union** of:

1. every permission from every role they hold (and everything **beneath** those roles in the
   hierarchy), plus
2. any **individual permissions** granted directly to that user.

There are **no negative grants** on a user — you never take a permission *away* from a person;
you give them fewer roles or grants. (The superadmin and full-admin roles sit *above* this
union as short-circuits — they pass regardless.) This makes access easy to reason about: to see
what someone can do, add up their roles and their direct grants.

You *can* tune what a **role** grants: on the Permissions screen you may **grant a permission to
a role** or **revoke one from a role** (a per-site adjustment layered over the role's platform
defaults — it changes what the role means *on your site* without touching the code). What you
never do is deny a permission to an individual after their roles have granted it.

**The built-in site permissions** you can assign:

| Permission | Allows |
|------------|--------|
| **Edit pages** (`EDIT_PAGES`) | Create, edit, save, preview, re-seed, rename and delete (draft) pages. The core designer permission. |
| **Disable pages** (`DISABLE_PAGES`) | Take a page offline / bring it back (the Disable/Enable toggle). On a **Reviewed** site, bringing a page online also needs *Approve pages*. |
| **Approve pages** (`APPROVE_PAGES`) | On a **Reviewed** site: publish, open the **Review queue**, approve or send back what editors submit, and publish one's own edits directly; on any site: change the **editorial level** (**§10**). |
| **Manage users** (`MANAGE_USERS`) | The **Users** screen (**§4**): create, edit, suspend, resume and reset the password of users — Operator accounts that hold nothing beyond the holder's own permissions; never an administrator, never a role change. The **Permissions** screen stays with the site's administrators. |
| **Delete users** (`DELETE_USERS`) | Delete users on the **Users** screen, within the same reach as *Manage users*; on its own it opens the screen with only the *Delete* buttons. |
| **Configure site** (`CONFIGURE_SITE`) | Site-wide configuration: **Site config**, **SEO**, **Icons**, **Web app manifest** (**§3**, **§10**), and the **Fragments**, **Content widgets**, **Static files** and **Messages** screens. |
| **Maintenance mode** (`MAINTENANCE_MODE`) | Take the public site offline with a notice — the **Maintenance** screen (**§3**). |
| **Manage documents** (`MANAGE_DOCUMENTS`) | The site's **Documents** screen (**§11**). |
| **Manage backups** (`MANAGE_BACKUPS`) | Save backups on the server, delete them, and download **transfer copies** (**§12**). Downloading a *full* backup and restoring stay with the site's administrators. |
| **View site insights** (`VIEW_SITE_INSIGHTS`) | The **Site insights** report — top 404s and most-visited pages (**§10**). Held by no role until you grant it, so out of the box only site administrators see it, as with Logs; unlike Logs it shows only paths and counts, never an IP address, so it is safe to hand to an editor. |

Installed **plugins may add their own permissions**, which appear in the same matrix and are
assigned the same way.

> **Verify:** grant *Edit pages* to an Operator user, then sign in as them — the **Pages** menu
> item now appears and they can open the editor; they still see no **Users** or **Permissions**.
> Grant them *Manage users* as well and **Users** appears: they can create an Operator, but the
> *Administrator* choice is not offered and an administrator's row has no buttons.
> Assignments that would exceed your own authority are rejected with a clear message rather than
> silently applied.

---

## 6. Managing pages

**Pages** (`/__admin/pages`) lists your site's pages — slug, path, the languages each is
published in, and status — **Draft**, **In review**, **Draft · sent back**, **Published**, **Published ·
pending revision** or **Published · revision sent back** (the last four only on a site that uses review,
**§10**) — with **New page**, **Edit**, and a **Disable/Enable** toggle. Editing needs *Edit pages*; the
toggle needs *Disable pages* (**§5**), and on a *Reviewed* site **Enable** is shown only to users who may
approve pages.

**Create a page** — **New page** (`/__admin/pages/new`): give it a **slug** (its id and file
name: letters, digits, `.`, `_` and `-`, starting with a letter or digit; ids beginning `rw-preview-` are kept for
previews; you can change it later with **Rename**, below), an optional title and path, and pick a **Master**:

- a **master** (e.g. `_master`) → the page is **born from that master** — it starts as a copy of
  the shared frame with the correct chrome already in place, so it previews finished and carries
  the exact seams the platform expects (**§7**);
- **Standalone (no master)** → a single self-contained HTML file with no shared chrome — right
  for a quick draft or a one-off.

Creating drops you straight into the editor.

**The page editor** (`/__admin/pages/<slug>/edit`) is a two-column workspace:

- **Left** — the **Page HTML** (a syntax-highlighted code editor), an optional **Decoupled
  logic** area (the paired binding file — see **§7**; leave it empty for a plain page), and the
  **Metadata** (path, master, active-nav, author, publication date, canonical URL, Cache-Control, the
  **Locales** checkboxes, per-locale title/description/path, the three search-engine switches and the
  template parameters, all below).
- **Right** — a **live Preview** and the **Page quality** panel (**§10**).

**Save** with the **Save** button. **Preview** renders the page **bound** — real data, the
site's real chrome, actual language — in an **isolated frame**: the preview runs with no access
to the editor, your session, or its security token, so even a page's own scripts can't reach
back into the console. It shows you *production*, not just your sample text.

**The preview follows your typing.** About half a second after you stop typing in **Page HTML** or
**Decoupled logic** (or upload a file into either), the preview re-renders what is in the editors —
**before you save** — and an **Unsaved changes** badge appears next to its title. Nothing is saved by
this: the badge means the preview is ahead of the saved page, and **Save** is still what keeps your work
(the badge disappears after the save reloads the editor, or when you undo back to the saved text). The
old preview stays on screen until the new one is ready, so it does not flicker. Two things it does not
follow until you save: the **Metadata** fields (title, path, master… — the preview uses the saved ones,
or those of the pending revision you are editing), and the **popped-out** preview window, which shows
the saved page (when you reattach it, the panel catches up with your edits). If a preview cannot be
produced in time — the server unreachable, your session expired — the panel goes back to the saved page
and stops following your typing until you reload the editor; your editing is not affected. A mistake in
your markup shows up *in* the preview as an error message, so you can fix it before saving.

**Editor conveniences.** Each code panel (**Page HTML**, **Decoupled logic**) carries **download**
and **upload** icons: download a copy (named `<slug>.html` / `<slug>.th.xml`) to edit in your own
tool, then upload it back into the editor — you still click **Save** to apply it. A per-panel
**expand** toggle grows a code editor when you need more room. On a wide screen the
**⅓ · ½ · ⅔ · ¾** control next to the page title splits the editor and preview columns to taste —
**¾** narrows the preview to sanity-check a **mobile-width** layout. And **Pop out** detaches the
preview into its own window so you can arrange the editor and preview however you like (the Preview
panel also shows a small **warning/error flag** when the **Page quality** panel has open items —
see **§10**).

**Publish vs. draft.** A page carries a **draft** flag; a published page is served on the public
site, a draft is not. Use the **Disable/Enable** toggle on the Pages list (or **Publish** in the editor)
to take a live page offline and bring it back. On an *Enforced* or *Reviewed* site a page with quality
**errors** cannot be published, and on a *Reviewed* site editors **submit for review** instead (**§10**).

**Metadata fields.** Each applies on the next request once saved. On a *Reviewed* site, a change to a published page
made by someone without *Approve pages* applies once it is approved.

| Field | What it does | Values, and what blank means |
|---|---|---|
| **Path** | The page's address in the default language. | Starts with `/`. Blank: `/<page id>`. Refused with *"The page path is not valid."*: `..`, `//`, `\|` or a control character, and any address under `/__`, `/actuator`, `/static` or `/error`, which belong to the platform. Two published pages cannot share an address in one language (*"Another page already uses that path."*). Do not begin a path with the code of a language your site offers (`/es/…` on a site offering Spanish, `/en/…` on one offering English): that first part is read as the language, so the page is never served at that address. |
| **Localized path** (per language, under *Per-locale title, description and path*) | The page's address in that language (**§9**). | The same rules as Path, but a value of the wrong form is **dropped** on save without a message, and that language falls back to Path. A clash with another published page is refused, as for Path. Kept per language, so region variants of one language share it (**§3**). Blank: Path. |
| **Title**, **Description** (per language) | The page's title and description in that language. | Blank in one language: the page shows another language's value — whichever is found first, not necessarily the default's — so fill every language you publish in. The quality panel flags a missing title (**§10**). |
| **Master template** | The master this page is shown in (**§7**). | The name of a master listed on **Fragments**, such as `_master`: `_`, then a letter or digit, then letters, digits, `.`, `_` or `-`. A name of `_` and digits only (`_404`) is an error page, not a master, and is refused. Blank: `_master`. New page offers a list; here you type it. |
| **Active nav item** | A word the master compares to mark the current menu item. | Use the key your master checks. The starter master highlights its `nav-home` link for `home` and its `nav-about` link for `about` (it adds the class `is-active`); a master of your own reads the value as `${activeNav}`. Blank: nothing is marked. |
| **Author** | Who is credited for the page. | Free text, a name. Templates show it as `${page.provenance.author}`, and plugins that emit structured data can use it. Blank: no byline — right for a landing or contact page. |
| **Published on** | The date the page was first published. | A date, stored as that day at midnight UTC; editing the page later does not move it. Templates read it as `${page.provenance.publishedAt}`. A value that cannot be read as a date is saved as no date. Blank: no date. |
| **Canonical URL** | Replaces the address the page declares as its original (`<link rel="canonical">` and the shared card's `og:url`). For a page that copies one published elsewhere. | A full URL is used as typed; a value starting with `/` is added to the address your links are built on (Site config, *Links built on*). It is not checked, and it replaces the canonical of **every** language of the page with that one address. The page's real addresses and its language alternates do not change. Blank: each language's own address. |
| **Cache-Control** | How long a browser or CDN may keep this page without asking again. | Sent exactly as typed and not checked, so a typo goes out as is. `no-store` stops anyone keeping the page; `private` lets the visitor's browser keep it but not a shared cache. The page also carries a version tag (an ETag), so a cache that asks again after its time is up gets a quick *not modified* when nothing changed. A request with a query string to a page whose page or master carries a widget is sent as `private, no-cache` whatever you set (**§8**). Blank: the platform's default, `public, max-age=300, stale-while-revalidate=60` unless your operator changed it. |

**Search-engine switches.** Three checkboxes in **Metadata** decide how engines treat this one page.
**Exclude from search engines** keeps it out of results altogether. **Do not show a snippet** is the gentler
one: the page stays indexed and keeps ranking, but engines are asked not to quote its text — which also
governs whether it may be used in a generative answer. Reach for it when a page should be findable but not
repeated elsewhere, and for *Exclude* only when it should not be found at all. **Removed permanently** takes
effect **only while the page is unpublished**: its address then answers *410 Gone* instead of *404 Not
found*, which tells search engines the page is not coming back and they should drop the URL rather than keep
retrying it. Leave it off for a page you are pulling down temporarily — a 410 invites engines to forget it.

**Template parameters.** At the bottom of **Metadata**, *Template parameters* are key/value pairs your
templates read as `${params.key}` — an analytics id, a phone number, a campaign switch. Your site has
its own set too, edited on **Site config** (`/__admin/config`), and a page's own value **wins** over the
site's for the same key. So that you always see what a render will actually use, the editor shows:

- under each of the page's own parameters, **"Overrides the site value: …"** when the site also defines
  that key — the value you are replacing on this page;
- below the rows, **Inherited from the site** — the site parameters this page does not override, with
  their values, read-only. To change one for the whole site, edit it on **Site config**; to change it
  for this page only, add a row with the same key here.

A saved parameter is live at once, everywhere it reaches: a site parameter saved on **Site config** changes the
version tag (the ETag) of every page of the site, so a visitor's browser or a CDN holding an older copy fetches the
new page at its next check instead of being told "unchanged". The same holds for a fragment saved on **Fragments**,
a placement saved on **Content widgets**, and a page whose address changes (every page linking to it by
`${urls['…']}` moves with it).

**Re-seed chrome from master.** After the site's **master** changes, click **Re-seed chrome from
master** in the editor to refresh *this page's* copy of the shared frame. Re-seed **preserves
your content** — it rewrites only the chrome seams, never your `<main>` content or your
page-specific head — so it is safe to run. (A page built on the master's seams always pulls the live
master, so for it this keeps the page's offline/preview copy current. A page **without** seams — an imported
or standalone page — serves its own copy, so re-seeding it changes what visitors see.)

A re-seed is an **edit**, so it follows your site's editorial level (**§10**) exactly like **Save**: on a
**Reviewed** site, if you cannot approve pages, re-seeding a published page saves it as a **pending revision**
for an approver (if the page already has one, that revision is re-seeded instead, so your pending changes are
kept); on an **Enforced** or **Reviewed** site, a published page is re-seeded only if the result passes the
quality checks — otherwise you see *"Not re-seeded"* with the errors. **Why:** a re-seed that wrote the
public page directly could, on a reviewed site, put something live that no approver had seen.

**Rename a page.** Below the editor, **Page id** → **Rename** changes the page's id: the name of its files, and
how templates link to it (`${urls['id']}`). If the page's path is `/<id>`, the path follows the new id and the old
address stops answering (a visitor or search engine using it gets a 404); a page with a path of its own keeps it.
Links written with the old id (`${urls['old-id']}`) must be updated by hand. Who may rename: **Edit pages** renames
a draft, or a page with its own path. Renaming a **published** page whose address follows its id moves a public
address, so it also needs **Publish/unpublish pages**, and **Approve pages** on a Reviewed site — the same as
publishing. A page with pending review work cannot be renamed until that work is approved, sent back or withdrawn.

**Delete a page.** **Delete page**, at the bottom of the editor, removes the page and its files after you confirm;
its address answers 404 from then on, and only restoring a backup brings it back. To hide a page but keep it, use
**Disable** on the Pages list instead. **Edit pages** deletes a draft; deleting a **published** page needs
**Publish/unpublish pages** as well (and **Approve pages** on a Reviewed site). A page with pending review work
cannot be deleted until that work is resolved.

> **Verify:** type a word into **Page HTML** and pause — the Preview shows it and the **Unsaved
> changes** badge appears; reload the page without saving and the word is gone. After **Save**, the
> confirmation shows and the Preview reflects your change. After
> **Disable**, the page's status badge flips and the public URL stops serving it; **Enable**
> brings it back.

### Fragments and error pages

**Fragments** (`/__admin/fragments`, *Configure site*) lists the site's shared pieces: files whose name starts with
`_`, such as the layout `_master` and includes like `_head` or `_nav`. They are never reachable by an address and
never listed as pages; masters and pages pull them by name (§7). **New fragment** creates one, **Edit** opens the
same code editor as a page (HTML, plus optional decoupled logic), **Delete** removes it, and **Reload** re-reads a
file changed outside the console. Every change is live on the next request.

**Error pages**, on the same screen, are what a visitor sees for an error in your site's own look: `_404` when an
address does not exist, `_410` for a page you removed on purpose, `_500` when something fails, and any other status
from 400 to 599. Type or pick a status and press **Create error page**: it starts from your `_master` (header,
footer, styling) with a heading and a sentence for that status in English, which you rewrite in your own words.
Edit and delete it like a fragment. A status without its own page gets the platform's plain page, and so does any
error page whose master fails to render, so a broken master never hides the error. Error pages are never cached
and never indexed. An error page always uses `_master`, and its logic gets fewer values than a page (**§7**):
`site`, `page`, `locale`, `master`, `year` and `pageTitle` (the status and its English name, such as
`404 Not Found`), with `pageDescription`, `activeNav`, `canonical`, `alternates`, `xDefault`, `siteLocales`,
`widgets` and `urls` empty. `params` holds the site's template parameters (an error page has none of its own),
`contact` the site's contact details, and `noindex` is true, so a master that shows a phone number or an email in
its footer looks the same on an error page.

> **Verify:** create the 404 page, change its heading, save, then open an address that does not exist on your
> site, such as `/no-such-page`: your page appears, with your header and footer. Delete it and the same address
> shows the plain platform page.

### Plugin content and URL conflicts

Some plugins serve **their own pages at your site's URLs** — a blog plugin's entries, a shop's
product pages. To visitors these are ordinary pages of your site: they wear your master's chrome,
follow the same language rules (per-language slugs, the `?lang=` switcher), and, when a plugin
provides your sitemap and `llms.txt` (**§10**), appear in them automatically. You do **not** see them in the Pages list — the plugin that
owns them gives you its own screen to manage them.

**When a page and plugin content want the same address, your page wins** — always. The plugin's
content simply becomes unreachable at that address while your page keeps it. This is never
silent:

- the **page editor** warns you (a *Page quality* finding) when your page's path is also served
  by plugin content, naming what you are shadowing;
- the **Pages screen** shows a **URL conflicts** panel — only while conflicts exist — listing
  every contested address, its language, who wins, and what happens;
- a well-behaved plugin warns on **its** side too, when you give its content a path a page owns.

Two *plugins* claiming the same address is a configuration error: requests to that address fail
(loudly, in the log) until you change one of them — the conflicts panel names both.

**Linking plugin content from your pages**: the same by-id link map that keeps page links from
breaking (**§7**) also carries plugin content, keyed `<plugin>:<id>` — e.g.
`th:href="${urls['posts:my-post']}"` resolves to that entry's URL *in the language being served*
(localized slug and language prefix included), falling back to the entry's primary language so
the link never 404s.

> **Verify:** create a page whose Path matches a plugin entry's URL — the editor shows the
> shadow warning, the Pages screen grows the conflicts panel, and the public URL serves your
> page. Delete the page (or move its path) and the entry serves again; the panel disappears.

---

## 7. Authoring pages — the design contract

Runway's authoring model is **page-as-entry**: a **page is a complete, ordinary HTML document**.
Open your `.html` from disk (`file://`) and it renders finished — styled, with header, footer,
and content. There is no "template with blanks" wrapping your page; **your page is the thing**,
and when the platform serves it, it *overlays* the live site onto it — swapping in the master's
live chrome, filling your marked elements with real text and data, and splicing in
plugin/SEO markup at the edge. That inversion is exactly why your file previews offline and why
you never need the platform to see your work. You author in plain **HTML / CSS / JavaScript** —
no template language, no framework, no platform internals.

**The whole contract is three rules:**

1. **Put a stable, meaningful `id` on anything that varies.** Titles, paragraphs, links, dates,
   prices, swapped images — anything filled with text or data at render. The `id` is the
   handshake that lets the platform bind live content to your element. Use ids that survive a
   restyle (`about-title`, `nav-about`), not auto-generated ones. When in doubt, add one — an
   unbound `id` is harmless.

2. **Keep the chrome seams and the content slot intact.** Every page carries the shared regions:
   the whole `<head>`, `<header id="site-header">`, `<footer id="site-footer">`,
   `<div id="site-scripts">` (plus any extra a master declares, e.g. `<aside id="sidebar">`), and
   the content slot `<main id="content">`. **Restyle them freely**, but don't rename, move, or
   remove those `id`s, and keep the element present. At render the platform replaces the seams
   with the master's **live** versions and keeps `#content` as your page's own.

3. **No template code in your HTML — ever.** No `th:*` attributes, no `${…}`, no `#{…}`, no
   fragment syntax. Your `.html` stays 100% browser-renderable.

Everything you like about this model — offline preview, restyling without a developer, nothing
to learn — falls out of these three rules.

**What site search reads.** When the search plugin indexes a page, it reads the page's title, its
description and the text inside `#content` in the page's `.html` file. Your header, navigation, breadcrumb,
language links and footer are not indexed, so they never show up in a result's snippet. Scripts and styles
inside `#content` are not indexed either. A page without a `#content` element is indexed from its whole
`<body>`. One limit to know: search reads the **file**, not the rendered page. Words the platform fills in from
your translations (the **Messages** screen) are not in the file, so they are not indexed. A visitor finds a
translated page by the words written in its file and by its title and description in each language.

**Your JavaScript and the security policy.** Write ordinary `<script src="…">` tags that load files
from your own site, or from a host your operator has allowed. The platform's Content-Security-Policy
trusts a script only when it carries the page's security *nonce*, and it adds that nonce for you when the
page is served — to every script your site or an allowed host serves, and to nothing else. There is
nothing to write and nothing to keep in sync. A few habits keep your scripts working once the operator
switches the policy from *report* to *enforce*:

- put your code in `.js` files and load them with `<script src>`: an inline `<script>…</script>` block
  never gets the nonce, so under *enforce* it does not run (it did not run before the nonce either);
- a script from another host (a CDN, a widget) needs that host on the operator's allowlist — ask them
  rather than pasting the tag in and hoping;
- load extra scripts with `<script src>` or `document.createElement('script')`, never
  `document.write('<script …>')` (a script written by `document.write` carries no nonce and is blocked);
- attach behaviour from your script files (`addEventListener`), not `onclick="…"` attributes or
  `javascript:` links — those are blocked too;
- give data blocks a data type — `<script type="application/ld+json">`, `type="application/json"` —
  they are not code and are left exactly as written;
- preload hints follow the same rule as scripts: `<link rel="modulepreload">`, `<link rel="prefetch">` or
  `<link rel="preload" as="script">` pointing at your own site or an allowed host is kept as you wrote it; one
  pointing anywhere else is **removed** when the page is served (the browser would otherwise contact that host for
  every visitor). Keep such hints in the page's ordinary markup — not inside an inline `<svg>`'s `<title>` or
  `<style>` or an `<iframe srcdoc>`, where the platform cannot remove one and serves that page without its nonce
  instead.

*Why:* without the nonce, any page on an allowed host could be pulled in as a script; with it, the
browser runs only the scripts your page loads from trusted places, and the ones they load. Inline blocks
are left out on purpose: a script slipped into a page through a form or a link would be inline too, and
the platform cannot tell yours from an attacker's.

**The master — the shared frame.** The **master** is the chrome every page shares (the `<head>`,
header/menu, footer, shared scripts). It, too, is a full, openable HTML document. The platform
**seeds a starter master into the site when the site is created** — a copy the site then owns —
so you usually **restyle** a master rather than start blank. A site may have **more than one**
master (e.g. a marketing frame and a docs frame); each page picks its master in the page
metadata (**§6**).

> **Self-containment — and its accepted trade-off.** A site carries **everything it needs to
> render**: its master, its templates, its CSS/JS/images/fonts, its translations — all in its
> own folder. It never depends on a platform-level resource at render time. The payoff: a site's
> backup is **complete** — it restores anywhere and renders identically (**§12**), and the
> platform can never silently change your site's appearance. The price, accepted on purpose: a
> later improvement to the *starter* master does **not** back-propagate into your site — you own
> your copy. Keep your **design assets in the site's own `static/`**; never depend on a shared or
> app-level asset.

**One page serves every language.** You write **readable sample text** so the page previews
finished; at render the platform **looks up** each marked element's text in the site's
translation bundle **for the visitor's language**. The platform **never translates or invents
text** — it only resolves a key to a string **someone already wrote**. So a new marked element
isn't automatically multilingual: its text must be filled for **every language the page is
published in**, or it renders empty (and the quality panel flags the untranslated text). There
is **no per-language copy of a page** — only per-language values in the bundle. Not multilingual
yet? Skip the keys entirely: hardcode the text and publish a single-language page.

**Links localize themselves.** Give a link a stable `id` and a plausible sample `href`
(`<a id="nav-about" href="/about">About</a>`); the platform binds the **correct localized URL**
by `id`, so the Spanish render points at `/acerca-de` automatically. Use **root-relative** hrefs
(`/about`) — never an app-name prefix.

**Place a plugin feature with a plain anchor.** Drop an ordinary attribute where you want a
plugin's content — no template code:

```html
<div data-widget="search/box"></div>
```

At render the platform replaces the anchor with the plugin's rendered content. If the plugin
isn't installed, it **soft-degrades** — the anchor renders empty and the page still works. An
anchor in the **master** shows on every page of that master; an anchor in one page shows only
there. You place the anchor; the operator configures the instance.

**The language switcher** is a site-wide, sticky visitor preference (not a per-page menu). You
provide its **container and one sample option** — `<nav id="lang-switcher"><a id="lang-option" …>`
— and **style** it; the platform **repeats your option per configured language** and marks the
current one. You own the look; the platform owns the list.

**Growing into dynamic content.** The smallest publishable page is a single `.html` (plus its
assets) — one language, text written straight into the markup, nothing more. You add the paired
**decoupled-logic file** (and translation keys) **only when** the page needs dynamic data or more
than one language. Writing that binding file is a platform-**expert** task, not the designer's —
your HTML stays template-free either way.

**What a logic file can reach — your site, nothing else.** A logic file may pull your own master,
fragments and pages (`~{${master} :: …}` and the like), however the name is written. It cannot pull
another site's templates: such a name fails exactly like a template that does not exist (the page
shows the error page, the preview shows "could not be resolved"), whether or not that site has a
template by that name. And it cannot call into the platform: `${@…}` (a Spring bean) is not
available in a site's templates and fails the render. *Why:* many sites share one server, and the
person who edits one site's templates must never be able to read another site's drafts, private
fragments or records through them.

**What a logic file can read.** Every page render carries these values; a logic file reads them with
`${…}` (for example `th:text="${contact.email}"`). A value that is not set reads as empty, never as an error.

| Variable | What it holds |
|---|---|
| `site` | The site: `site.displayName`, `site.id`, `site.defaultLocale`, `site.locales`, `site.baseUrl`. |
| `page` | The page being shown: `page.slug`, `page.title`, `page.description`, `page.availableLocales`, `page.noindex`, and its byline and date as `page.provenance.author` and `page.provenance.publishedAt` (**§6**). |
| `locale` | The language of this render (a Java `Locale`; `${locale.language}` gives `en`, `es`, …). |
| `pageTitle`, `pageDescription` | The page's title and description in this language. |
| `canonical` | The page's canonical URL. |
| `alternates`, `xDefault` | The page's other-language URLs, for `hreflang` links. |
| `siteLocales` | The language switcher's entries: each has `code`, `label`, `url` and `current`. |
| `activeNav` | The navigation key the page marks as current (set in the page editor). |
| `noindex` | Whether the page asks search engines not to index it. |
| `year` | The current year, as text (for a copyright line). |
| `params` | The site's **template parameters** (Site config), overlaid by the page's own; read one as `${params['phone']}`. |
| `contact` | The site's **contact details** (Site config): `contact.email`, `contact.phone`, `contact.address`; `${contact.isEmpty()}` is true when none is set, so a contact block can hide itself. |
| `urls` | Every page's and content item's URL in this language, by key: `${urls['about']}`, `${urls['posts:p1']}`. Link with these rather than typing paths, so a renamed path never breaks a link. |
| `master` | The name of the page's master template, for `~{${master} :: #site-header}`. |
| `widgets` | The content widgets placed on the page (plugins fill these). |
| `content` | On a page a plugin generates (a blog post, say): the item itself, for example `${content.bodyHtml}`. |

A change to any of these, including a contact or parameter save, changes the page's version, so a browser or
cache never keeps showing the old text.

> **Verify:** open your `.html` from `file://` — it must render **finished** (styled, with
> header and footer). If it looks broken offline, it isn't a complete document — fix that first.
> Then use the editor's **Preview** (**§6**) to see the **bound** result: real chrome, real text,
> real language. Sample text and sample links won't match production — that's expected; plugin
> widgets render empty offline.

---

## 8. Plugins on your site

Plugins are optional features the platform operator installs; a site manager **configures the
ones that offer per-site settings** and places their content on pages.

**Configure a plugin for your site.** Open the **Settings** dropdown: every plugin that exposes
per-site configuration appears as its own entry at the bottom of that menu (below the built-in
Settings screens), labelled with the plugin's name. **The platform renders and validates each
plugin's form for you** from the plugin's own declared schema (typed fields, defaults, ranges);
you never edit configuration files, and a plugin that offers no per-site settings simply doesn't
appear in the menu. A few advanced fields take a block of text in a format the plugin describes, such as a short YAML document. A
plugin may ignore a block it cannot read rather than refuse it, so after saving one, check that it took effect. Your entries are stored **inside your site's folder** and travel in your
backup (**§12**) — they are *your* data even though they're useless without the plugin installed.

Changes apply **immediately** (no restart) — the plugin is notified and reacts (for example, an
index-backed plugin rebuilds its index).

**Reading a plugin's form.** A plugin may organize its form, and most larger ones do:

- **Sections** group related fields under a heading and a one-line introduction.
- **Each field has a line under it** saying what it does; when the plugin provides a help page, a **Learn
  more** link opens it, in a new tab, at the matching section.
- **Advanced** opens the fields most sites never need. It opens by itself when one of them is set, so a
  setting that is in effect is never hidden.
- **Fields that depend on a switch** (for example, a product's details while "Describe a product" is
  off) are hidden until you turn the switch on. Hiding never erases them: what you typed is kept.
- A choice set to **inherit** says what it currently resolves to, e.g. *inherit (currently: allow)*.
- When a feature also needs the **platform operator's** permission and they have it off, the field says
  so; your setting is saved, but has no effect until they turn it on.
- **Passwords and keys are never shown.** Leave such a field blank to keep what is stored. A plugin can tie
  a password to the settings that say where it is sent — for example, an SMTP password to the
  server, port and username. If you change one of those and leave the password blank, the stored password
  is **removed** and the page asks you to enter it again. **Why:** the settings form may be delegated to
  someone who should not know the password (anyone granted the plugin's *settings* permission); without
  this, they could point the server at one they control and have the platform send them the password.
  Keep in mind that whoever holds a plugin's *settings* permission can still change where its messages go.

> A plugin's per-site **settings form** is always reached through the **Settings** dropdown. A plugin
> may also bring screens of its own: under **Settings** (like *Essential Protection*, below) or
> **Activity**, or as an item of its own in the top bar — typically a screen to manage the content
> it serves. Any help or reference page the plugin provides is linked from *within* its forms.
>
> A value stored for a setting the plugin no longer declares (after a plugin upgrade, or a hand edit
> of the file on the server) is kept when you save, but not shown on the form.

**Place plugin content on a page.** A designer drops a `data-widget="type/instance"` anchor into a
page or master template (described in **§7**); you decide where it appears and — on the **Content
widgets** screen below — how each placement behaves.

### Content widgets — configuring each placement

Open **Resources → Content widgets** (`/__admin/widgets`). The screen lists **every widget a
designer anchored** anywhere in your templates, one card per placement, showing the widget **type**,
its **instance** name, and which template(s) it sits in. The instance name is what lets the *same*
widget type appear more than once and be tuned separately — a `carousel/home` and a `carousel/promos`
are two independent placements.

- If the widget's plugin **declares per-instance settings**, the card shows a **typed form** (the
  platform renders and validates it from the plugin's schema, exactly like a plugin's per-site
  settings). Set the values and **Save** — changes apply **immediately** to the public page, and every
  page's version tag (ETag) moves with the save, so browsers and CDNs holding an older copy get the new one at
  their next check; blank a field to fall back to the widget's built-in default.
- If the widget **declares no settings**, the card simply notes that there's nothing to configure —
  the placement still renders, using the widget's defaults.

A page that carries a widget, requested **with a query string** — search results (`/search?q=…`), a form's
confirmation (`?sent`) — is never cached by a CDN and never answered "unchanged": the widget may answer from data
that changes on its own (the search index follows every page you edit), so that response is rendered fresh each
time. The same page without a query is cached as usual.

Your widget configuration is stored **inside your site's folder** (`widget-instances.yml`) and travels
in your backup (**§12**) — it is *your* data, even though it needs the plugin installed to have any
effect. The screen is available to site managers with the **Configure site** permission.

In that file each placement's `type/instance` key holds its settings as `setting: value` lines; a secret is stored
encrypted, as `ENC(…)`. Removing an anchor from your templates does not delete its settings: they stay in the
file, are no longer listed on this screen, and apply again if the same `type/instance` is anchored again.

**What each plugin may add:**

- a **per-site settings form** (above);
- **content widgets** with their own per-placement settings (this section);
- **its own permissions**, which show up in your Permissions matrix (**§5**);
- an optional **status/actions panel** the platform renders below the plugin's settings.

> **Which knobs are yours vs. the operator's.** Site-owned *behaviour* (enable switches,
> tuning, credentials your site owns) is per-site and lives here. Platform concerns (global
> timers, the request rate limit, filesystem paths) are set at the platform level and are not exposed
> to a site manager — a site-editable path or rate limit would be an isolation risk.

**Login protection for your site** (**Settings → Essential Protection**, `/__admin/protection`, gated by
*Configure site*). It guards your site's own login page; changes apply at once.

| Setting | Values | What it does |
|---|---|---|
| Honeypot | on (default) / off | A hidden field a person never fills and a bot usually does; a login that fills it is refused with the ordinary "invalid username or password" message, so the bot cannot tell why. It guards your **Forgot your password?** form too. |
| CAPTCHA | on / off (default) | Asks for a reCAPTCHA on your login page and on your **Forgot your password?** form. It is only asked when it is on **and** both keys are set. It also protects a public form a plugin offers, when that plugin asks the platform for the CAPTCHA. |
| Provider | `recaptcha-v2` (default; the "I'm not a robot" box) / `recaptcha-v3` (invisible, score-based) | Which Google reCAPTCHA you registered your keys for. Any other value found in the file (for example from an old backup) is treated as `recaptcha-v2`. |
| Site key | from your reCAPTCHA admin console | Public; shown in your page. When you register the key, list **every host name your site answers on**; on a host that is not listed, the challenge cannot be solved. |
| Secret key | from your reCAPTCHA admin console | Stored encrypted; leave the field blank on a later save to keep the stored one. Storing it needs an encryption key on the server: if your operator has not set one, the save is refused with a message and nothing changes. |
| Score threshold | 0 to 1, default 0.5 (`recaptcha-v3` only) | A response scoring below it is refused. Higher is stricter. |

> **A wrong CAPTCHA setting can lock you out of your own console.** A wrong key, a key that does not list the host
> name you sign in on, or a v3 threshold set too high makes the challenge impossible to pass, and you then cannot
> sign in to fix it. Make sure the keys are right before you turn the CAPTCHA on, and prefer `recaptcha-v2`: it is a
> plain pass or fail, while v3 can score a real person too low on a quiet site. If it happens, ask your platform
> operator: they can switch your CAPTCHA off from their **Sites** screen, keeping your keys, so you can sign in and
> correct them.

If Google cannot be reached, the challenge is let through rather than locking everyone out; the honeypot and the
lockout still apply. Your operator may allow **"Remember this device"**: the box then appears under the CAPTCHA, and
a device that signs in with it ticked skips your login CAPTCHA for the number of days shown (never the password, and
never on the Forgot your password? form).

How many failed logins lock an address out, and for how long, is the platform's: it counts per address across
every login on the server, and on the login page a failed CAPTCHA or a filled honeypot counts as a failed login.

_Only one plugin is bundled in the installer (the request-protection plugin: its rate limit is the
operator's, its login protection above is yours); every other plugin is installed by your operator separately, so which entries you
see in the **Settings** menu depends on your installation — §10's crawl surfaces, for example, come from such a
plugin. The site-facing settings/permissions/widget seams above are in
place and exercised by the plugins that use them — see [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md)._

> **Verify:** after saving a plugin's per-site form, the confirmation shows and the behaviour
> takes effect on the next request (no restart). Blank a field to fall back to the plugin's
> default.

---

## 9. Multilingual content

Your site has a **default language** and a set of languages it offers. Each **page** declares
**which of those languages it is published in** — the **Locales** checkboxes in the page editor
(**§6**):

- both languages checked → published in both;
- one language only → available only in that language, even on a site whose default is another;
- **none checked** → the site's **default language only**.

This declaration drives the public URLs and the alternate-language links the platform advertises
for the page. **One HTML page serves every language** — there is never a per-language copy of a
page (**§7**), only per-language values in the site's translation bundle. The platform **resolves
keys to strings that already exist**; it never translates, so each language you publish a page in
must have its strings written (a site operator or translator maintains the bundle).

**Editing the bundle** — the **Resources → Messages** screen is a grid of one row per key and one
column per language; filter by key or value, edit any cell, **add** a key, or untick **Keep** to
delete one. **Save** applies **only what you changed** (so fixing one string is instant even on a
large site), and the button shows a count of your unsaved edits and warns before you navigate away
with changes pending. A blank cell means "no value in this language": the page then shows the
**default language's** text for that key, and only a key that is blank in the default language too shows as
`??key??` — your cue to translate it. Installed plugins bring texts for their own keys; your site's texts are
looked up first, so a plugin never replaces one of yours.

On the server the texts are `i18n/messages.properties` for the default language and
`i18n/messages_<language tag>.properties` for each other one (`messages_es.properties`), in UTF-8. The Messages
screen writes them for you; the names matter only when you read a backup or hand files to your operator. Runway
also reads `messages*.xml` bundles (Java's XML properties format), which this screen does not show.

**Requesting a language a page doesn't offer** degrades gracefully: the visitor's sticky
language preference is remembered site-wide, and the platform falls back rather than 404-ing.
Internal links resolve to a page's real URL — a link from an English page to a Spanish-only page
still works.

> **Verify:** publish a page in two languages, fill both languages' strings, and switch the
> preview language — the same page renders each language's text. Publish in one language only and
> the platform won't advertise the other for that page.

### How a URL resolves to a language (dispatch rules)

**The URL scheme.** Your **default language** is served at the **bare path** (`/about`); **every other**
language is served under a one-segment **language prefix** (`/es/about`). If a page has a **per-locale
Path** (the *Localized path* field in the editor's *Per-locale content*), that language uses its own
slug — so on a **Spanish-default** site the About page can be **`/acerca-de`** in Spanish (default, no
prefix) and **`/en/about`** in English (prefixed). The default language is canonical **only** at the bare
path, so its *prefixed* form (`/es/acerca-de` on a Spanish-default site) permanently redirects to it.

**Which language a request gets.** For a page that exists at the requested path, the platform decides in
this order — the first row that matches wins:

| The incoming request… | What the platform does |
|---|---|
| carries **`?lang=xx`** (the language switcher) | Sets a **sticky language cookie** to `xx` and **302-redirects** to that language's URL for the page. |
| starts with the **default** language's prefix (e.g. `/es/…` on a Spanish-default site) | **301-redirects** to the bare URL — the default language is canonical unprefixed. |
| starts with **another** language's prefix (`/en/…`) and the page **is** published in it | Serves that language — **200**. |
| starts with a language prefix the page **is not** published in | **404** — the platform never fabricates a URL for a language a page doesn't offer. |
| is **unprefixed**, and a **language cookie** is set (from an earlier switch) to a language the page offers | Serves that language, at its own URL (302-redirecting to the prefix if it isn't the default). |
| is **unprefixed**, no cookie, and the browser sent an **`Accept-Language`** naming a language the page offers | Serves the browser's preferred language, at its own URL (302-redirecting to the prefix if it isn't the default). |
| is **unprefixed**, no cookie, **no `Accept-Language`** header (a crawler, a `curl`, a link-checker) | Serves the **site default** language, **in place** — no redirect. |
| matches none of the above | Serves the page's **primary** language (the site default when the page offers it, else the first language it is published in). |

(An unknown path — no page there for the implied language — is a **404** before any of this.)

**Why you and a search-engine crawler can see different languages at the same bare URL.** Your browser
sends an `Accept-Language` header — and, the moment you use the language switcher, a sticky cookie — so a
bare URL may redirect **you** to your language. A crawler (or a `curl` with no header) has neither, so it
gets the **site default** at the bare URL. Both are correct and intended: every language keeps a **stable
canonical URL** (cross-linked with `hreflang`, plus an `x-default`) for search engines, while human
visitors land in their own language. This is why testing a multilingual site with your browser can hide a
routing problem a crawler would hit — check with a no-`Accept-Language` request (below).

**Changing the default language re-labels URLs.** Because the default is the *unprefixed* one, switching a
site's default language moves the **bare** URLs to the new language and pushes the old default under its
prefix (English `/about` becomes `/en/about`; Spanish takes the bare path). Existing links to the old bare
URLs then resolve to a different language. The platform never rewrites your paths, so after such a change
review each page's **Path** and **Localized path** — the **Page quality** panel flags a page whose URL
isn't localized, and the **Site config** screen warns you when you change the default (see **§10**, **§3**).

> **Verify:** on a non-English-default site, open a default-language page's **bare** URL in your browser
> (it serves the default), then request the same URL with **no `Accept-Language`** —
> `curl -s -o /dev/null -w '%{http_code}\n' https://your-site/that-path` — it should also return **200**,
> not a `3xx` redirect. Now set your browser to prefer another offered language and reload the bare URL:
> you are redirected to that language's own URL (e.g. `/en/…`), while the bare URL still serves the default
> to anyone without that preference.

---

## 10. Page quality & SEO/GEO assistance

The page editor's right column runs a **live Page quality panel** as you work. Each finding carries
a severity (info / warning / error) and a plain message. The checks **never edit** your page; whether
they can **stop** a publish depends on the site's **editorial level** (below). Out of the box every
site is **Advisory**: the panel only reports, and you can always save and publish with open findings.

**Ignoring a finding.** Any **warning** or **note** can be marked **Ignore** (the toggle on its row):
it stays listed but dimmed and no longer lights the **warning flag** shown on the Preview panel. Your
choices are remembered per page in your browser. **Errors cannot be ignored** — they have no ignore
toggle and always light the flag (in red), so a genuinely broken page can never be quietly hidden.

### What the built-in checks look for

The checks run over the page **as visitors receive it** — rendered, with the master's chrome — so a
fault in the master (a logo without `alt`) shows on every page until the master is fixed. Only the two
findings that mean something is **clearly broken** are errors; everything else is advice, because on an
enforced site an error stops a publish.

| Finding | Severity | Why |
|---------|----------|-----|
| No **title** for the page's language | **error** | Browser tabs, bookmarks, shared links and search results show the bare address instead. |
| An **image without `alt`** | **error** | A screen reader announces the file name; search cannot read it. Use `alt=""` for a purely decorative image — that is correct and not flagged. |
| Title longer than 60 characters | warning | Search results cut it off. |
| No **description** | warning | Engines write their own snippet from the page text. |
| Description shorter than 50 / longer than 160 characters | note / warning | Too short to be chosen as the snippet / cut off. |
| No `<h1>`, or more than one | warning | The page has no single main heading. A master that puts the site name in an `<h1>` causes the second. |
| Heading levels skip (`<h2>` then `<h4>`) | warning | Breaks the outline screen readers navigate by. |
| A link with no text (an icon-only link) | warning | A screen reader announces just "link". Add text or an `aria-label`. |
| Generic link text ("click here", "read more", "aquí", "leer más"…) | note | Out of context — read aloud, or in a list of links — it says nothing about where it goes. |
| Fewer than 150 words in the page's `<main>` | note | Possibly thin; fine for a contact or form page. |
| Same title or description as another published page | warning | The two pages compete with each other in search results. |
| The page cannot be rendered at all | **error** | Shown with the reason; fix the master or the logic. |

Installed **plugins add their own checks** to the same panel. A plugin decides its findings' severity — so on an enforced site a plugin *error* blocks
publishing just like a built-in one.

The panel also runs a few built-in **URL-path hygiene** advisories over a page's **Path** and each
**Localized path** — best-practice nudges, never rewrites, and never blocking (they are warnings and
notes, so they play no part in enforcement):

- **Underscores** — prefer hyphens: search engines read `-` as a word separator but `_` as a word
  joiner, so `about-us` counts as two words and `about_us` as one.
- **Improper characters** — a space or a symbol (`? # % &` …) has to be percent-encoded, which makes
  links fragile and, because the platform routes paths by an exact, case-sensitive match, can leave
  the page unreachable at the address a visitor would type. (Accented **letters** are covered by their
  own, softer advisory below, not this one.)
- **Uppercase letters** — URLs are case-sensitive, so `/About` and `/about` are different addresses (a
  broken-link / duplicate-URL risk); prefer all-lowercase.
- **Non-ASCII letters** — an accented or other-script letter (`/niños`, `/café`) is a valid URL
  character but is percent-encoded in links (`/niños` is shared and stored as `/ni%C3%B1os`), which
  looks messy and can break in older tools. This is **acceptable for a slug meant for readers of that
  language**; otherwise a plain-ASCII form (`ninos`, `cafe`) is more portable. The message says as much
  — it is a trade-off note, not a "don't".
- **Un-localized URL** on a multilingual page — see **§9**.

Use lowercase, hyphen-separated slugs (`/about-us`, `/our-team`) and these stay quiet.

### Editorial levels — advisory, enforced, reviewed

**Site config → Editorial workflow** (`/__admin/config`) sets how strictly publishing is governed on
your site. Changing it needs *Configure site* **and** *Approve pages*: the level is what stands between
an editor and the live site, so someone who can only configure the site cannot switch review off. The new
level applies to the next save or publish; nothing already in review moves.

| Level | Saving a draft | Publishing a draft | Saving a page that is live |
|-------|----------------|--------------------|----------------------------|
| **Advisory** (default) | saved | *Disable pages* publishes | saved straight to the live page |
| **Enforced** | saved (a draft is work in progress — never checked) | refused while the page has **errors**; the errors are listed | refused while the edit has **errors**; the editor comes back with your typing intact and the errors listed |
| **Reviewed** | saved | only a user with ***Approve pages*** publishes (after the enforced checks); everyone else **submits for review** | with *Approve pages*: as enforced, straight to live. Without it: saved as a **pending revision** (below) |

Warnings and notes **never** block at any level. Unpublishing (**Disable**) is never gated: taking a page
down needs no second opinion.

### The review flow (Reviewed sites)

**A new page.** The editor works on the draft as usual, then clicks **Submit for review** in the editor.
The Pages list shows it as **In review**. **Withdraw from review** takes it back out.

**A change to a live page.** When someone without *Approve pages* saves a published page, the change is
kept as a **pending revision** beside the page. **The live page does not change at all** — visitors, the
sitemap, search indexing and browser caches keep seeing the published version. The Save button says
**Save as pending revision**, and from then on the editor opens the *revision* (with a banner saying so),
so the editor can keep improving it; every save resubmits it. **Discard pending changes** throws the
revision away (the live page is unaffected). The Pages list shows **Published · pending revision**.

**The approver.** **Review queue** (`/__admin/review`, under Resources; needs *Approve pages*) lists
everything waiting, oldest first — first publications and changes to live pages. Opening an item shows:

- the **quality findings** of the proposed content (on an enforced or reviewed site, errors here mean the
  approval will be refused until they are fixed);
- for a change to a live page, **what changed**: the settings that differ (title, path, description…) and a
  line-by-line comparison of the page HTML and its logic against the live version;
- the **live page and the proposal side by side** (a first publication shows the proposal only).

Then either:

- **Approve and publish** — the revision replaces the live page (or the draft goes live). The approval is
  tied to exactly what you looked at: if the editor saves again in the meantime you are asked to look again
  rather than publishing something you have not seen.
- **Send back** with a note (a note is required). Nothing is published; the editor sees your note in a
  banner on the page, keeps the revision as their working copy, and resubmits by saving again (a draft: by
  **Submit for review**). The Pages list shows **sent back**.

**Approvers publish their own work directly** — no second person is needed when you hold *Approve pages*.
Approvers do not need *Edit pages* to review: the queue, the comparison and the previews are open to
*Approve pages* alone.

A page with something in review **cannot be renamed** until it is approved, sent back and discarded, or
withdrawn. **Deleting** a page deletes its pending revision too. Every step — submitted, approved, sent
back (with the start of the note), withdrawn, discarded, and any publish or save **blocked by quality
errors** — is recorded in the site's audit log (**Logs**).

Pending revisions are part of your site: a **backup** carries them, so a restore brings back pending
reviews with the pages they belong to. A **transfer copy** does not: it carries your site as published, without
the changes waiting for review, who submitted them, or reviewers' notes (**§12**).

> **Verify:** on an *Advisory* site (the default), remove a page's `<title>` in the editor — the panel shows
> the missing-title error live, and **Save** and **Publish** still work. Then set **Reviewed** on Site config. As a user with *Edit pages* only, change a live page and
> save — the flash says *Saved as a pending revision*, and the public page is unchanged. As a user with
> *Approve pages*, open **Review queue**, look at the comparison, and **Approve** — the public page now shows
> the change. Set **Enforced**, remove a page's title and try to save it — the save is refused with the
> error listed and your edit still in the editor.

### The SEO screen

The platform already gives every page fast server-rendered HTML, a canonical URL, and correct
`hreflang` alternates automatically — you configure nothing for those. The **SEO** screen
(`/__admin/seo`, gated by *Configure site*) is where you set the site's own metadata on top:

- **Social sharing** — the preview image (`og:image`), content type (`og:type`: an Open Graph type such as
  `website` or `article`), and Twitter card and handle shown when a page is shared. The card is **Automatic**
  (a large image when there is a preview image, else a summary), `summary` or `summary_large_image`; other values
  are refused, because networks ignore a card type they do not know. A content type that is not of the Open Graph
  form (`website`, `article`, `video.movie`, …) is refused too.
  These values are **site-wide**: every page gets the same image and type. The **preview image** must be a full
  address (`https://your-site.example/static/img/share.png`). It is copied as typed into `og:image` and
  `twitter:image` and is not checked, and networks ignore a relative `/static/…` path. Left empty, shared pages
  carry no image and the card becomes `summary`, even if you chose `summary_large_image`, because a large-image
  card without an image shows blank. An empty content type means `website`. Type the **Twitter handle** with its
  `@` (`@example`); it is not checked.
- **Search-engine verification** — the Google Search Console and Bing Webmaster Tools tokens (paste
  the `content` value from each service's meta-tag verification method).
- **Uploaded documents** — *Keep uploaded documents out of search engines* sends an
  `X-Robots-Tag: noindex` header with every file under `/static/doc/` (**§11**). A PDF or a spreadsheet has
  no `<head>` to put a tag in, so this header is the only indexing instruction an engine will honour for it.
  It is off by default — a published document is often the thing that ranks — and it applies to **all** your
  documents together, not one at a time. It is **not access control**: the files stay downloadable by anyone
  who has the link, and only engines that choose to honour the header are affected.
- **Extra `<head>`** — a free-form block injected verbatim into every page's `<head>`, for trusted
  markup such as a `link rel="me"` or a JSON-LD structured-data script. It is **not escaped**, so use
  it only for markup you author. If it contains Open Graph or Twitter meta tags of its own (`property="og:…"` or
  `name="twitter:…"`), the platform stops emitting its social card for every page — the Social-sharing fields above then have no effect, and your block
  must carry the whole card. Scripts here follow the same rules as in your pages (**§7**): a `<script src>` from
  your site or an allowed host runs; an inline `<script>…</script>` does not once the operator enforces the security
  policy; a JSON-LD block (`type="application/ld+json"`) is data and is left as written.

These are stored inside the site (they travel in your backup) and injected at the response edge, so
they never touch your templates. Analytics and richer GEO markup are added by plugins, not here.

> **Verify:** set a preview image on the SEO screen, save, then view a public page's source — the
> `<meta property="og:image" …>` tag appears in `<head>` even though none of your templates contain it.

### Sitemaps, robots and AI crawlers

The core stops at the page's own `<head>`. The crawl surfaces themselves — `/sitemap.xml`, `/robots.txt`
and `/llms.txt` — and the schema.org structured data that says what a page *is* come from a plugin (the `discoverability` plugin
does all four), if your operator has installed one; it then appears in the **Settings** dropdown
with its own per-site form (**§8**). Without one your site serves no `/robots.txt` at all, which crawlers read as
*everything allowed*.

### Site insights — what visitors reach, and what they miss

The **Insights** screen (**Activity → Insights**, `/__admin/insights`) turns your site's own access log into
two lists, for the **last 7 or 30 days** (never longer than your log retention — **§3**):

- **Top 404 paths** — the addresses visitors asked for that do not exist, with how often and when last. Each one
  is a broken link somewhere (in your pages, on another site, in an old bookmark) or a page that moved. Fix it
  with a corrected link, the missing page or file, or a redirect from the old address.
- **Most-visited pages** — your pages by how often they were successfully viewed, with when last.

**What it counts.** Only `GET` requests (a form post, a sign-in attempt or a crawler's `HEAD` is not a visit). A **page view** is a
successful (2xx) answer for an address that looks like a page — no file extension in the last part, or `.html`
— so stylesheets, images, downloads, `robots.txt` and the sitemap are left out; a browser re-checking a page it
already has (a `304`) is not a new view, and neither is a refusal (a `403`, or a `503` during maintenance). A **404** counts for any address, images included, because a missing
image is a broken link too. Addresses are shown **without their query string** (`/search?q=…` counts as
`/search`), and very long ones are shortened on screen (hover to see the whole stored address).

**What it leaves out.** The consoles and their assets, the platform's health and internal addresses, and the
icon and `/.well-known/` requests browsers and scanners make on their own. The log records no browser
information, so **search-engine crawlers and bots count like visitors** — read the numbers as "requests", not
"people". Expect some 404s from automated scanners probing for software you do not run (`/wp-login.php`,
`/.env` …); they are harmless and safe to ignore.

**How fresh, and how much.** The report reads the log files directly and refreshes **at most once a minute**
(immediately when a new day starts). On a very busy site it reads the most recent two million log lines and
says so if older days were not reached; if your site had more distinct addresses than the report keeps, it
says the counts for rarely-requested addresses may be low. Nothing new is recorded to produce this screen —
it only reads the log you already have — and no IP address ever appears in it.

The **search plugin** adds the third signal on its own settings page: **searches with no results** — what
visitors looked for and did not find. It is off until you turn it on there, because search terms are typed
by visitors and can contain personal data; see that plugin's help.

> **Verify:** open a page that does not exist on your site (e.g. `/this-page-is-missing`), wait a minute, and
> open **Insights** — the address appears under *Top 404 paths* with a count of 1.

---

## 11. Documents

The **Documents** screen (`/__admin/documents`, gated by *Manage documents*) manages your site's
downloadable files — the PDFs, spreadsheets and other documents your pages link to. It is separate from
the `static/` assets a designer edits (**§7**): documents are content you upload and manage from the UI.

- **Upload** — pick a file; it is stored under the site and served at `/static/doc/<name>` (up to 25 MB
  each). The stored name is the file's own name reduced to a safe leaf — no folders, no path tricks.
- **Download** — every enabled document has a Download link. Documents are always served as
  **downloads** (an attachment, never rendered in the browser) with content-type sniffing turned off, so
  an uploaded HTML or SVG can never run script on your site — a deliberate safety choice for uploaded
  content.
- **Disable / enable** — *disabling* a document withholds it from the public (its URL returns 404) **without
  deleting it**. Re-enable to bring it back. Use this to take a file offline without losing it.
- **Delete** — removes the file for good (and clears any disabled flag).

> **Verify:** upload a file, open its Download link (it downloads rather than displaying), then Disable it
> and reload the link — it now returns *Not found*; Enable it and it returns again.

Static files your **pages** reference directly (CSS, JS, images, fonts) are managed on the **Static files**
screen (**§11.1**); the Documents screen is specifically for managed downloads.

### 11.1 Static files

The **Static files** screen (**Resources → Static files**, `/__admin/static`, gated by *Configure site*) manages
everything in your site's `static/` folder, which is served at `/static/…`: stylesheets, scripts, images, fonts,
audio, video and PDFs your pages and master use. A file at `static/css/site.css` is linked from a page or the
master as `/static/css/site.css`.

- **Browse** — folders are listed first; open one to see its contents, and use the path at the top to go back up.
  Each file links to its public address, and **Copy URL** copies that address.
- **Upload files** — pick one or several; each is checked on its own, so a refused file does not stop the
  rest, and the message names each refused file with the reason. Up to 25 MiB per file. Allowed types: `css`,
  `js`, `mjs`, `map`, `json`, `txt`, `xml`, `webmanifest`; images `png`, `jpg`, `jpeg`, `gif`, `webp`, `avif`,
  `svg`, `ico`, `bmp`; fonts `woff`, `woff2`, `ttf`, `otf`, `eot`; audio and video `mp3`, `ogg`, `oga`, `wav`,
  `m4a`, `mp4`, `webm`, `ogv`, `vtt`; and `pdf`. A page (`.html`) is not a static file: create it on **Pages**, so
  it gets the editor, the quality checks and the review workflow. Names are made safe for addresses (spaces and
  accents become `_`). A file of the same name is kept unless you tick **Replace files with the same name**.
- **New folder** — letters, digits, dot, dash and underscore, not starting with a dot; up to 8 levels deep.
- **Delete** — removes a file after you confirm, or a folder once it is empty. This works for every file and
  folder listed, whatever its name: a long uploaded name, or one put on the server by hand or by a restore (with
  spaces, even at the start or end, accents, `@`, or a leading dot such as `.htaccess`), can be opened and
  deleted like any other. A file or folder whose name starts with a dot (`.htaccess`, a `.git` folder) is
  **not** served to visitors: such names are tool and server metadata, never page assets. The one dot folder that
  is served is `.well-known/` (for `security.txt` and similar). The one exception is a name with a backslash (`\`) or an invisible control
  character: it is listed, but this screen cannot name it, and only your operator can remove it on the server.
  A folder link (a symbolic link) put inside `static/` on the server is not listed and not followed.

The `doc/` folder holds your documents and is managed on **Documents** (above); it is listed here with a link
there, and nothing in it can be changed from this screen. Every upload, new folder and delete is recorded in the
site's log (**§3**). Browsers may keep a static file for up to a day, and a **font** (`woff2`, `woff`, `ttf`,
`otf`, `eot`) for up to a **year without asking again**, unless your operator changed that list. So when a change
must show at once, and always for a font, upload the file under a new name (`site-2.css`, `brand-2.woff2`) and
update the link. A font replaced under the same name stays old for visitors who already have it.

An `svg` or `xml` file is sent with a sandbox policy (`Content-Security-Policy: sandbox`): opened directly in the
browser, a script inside it cannot run on your site. Used as an image (`<img>` or a CSS `background`), an SVG
shows as usual.

**Storage limit.** Under the introduction the screen says how much your static files and documents use together,
and, when your platform operator has set one, the most your site may store ("…use 1.2 GB of the 5.0 GB this site
may store"). The operator sets this limit; you cannot change it here. An upload that would go over it is refused
with the reason, on this screen and on **Documents**; the other files of the same upload that still fit are kept.
Replacing a file counts only the difference, so a file of the same size or smaller can always be replaced. If your
site is already over the limit (the operator lowered it, or a backup brought more back), the screen says so. Every
file is kept and still served, and you can still delete files; only new uploads wait until there is room again.
Pages, messages and plugin data do not count toward this limit.

> **Verify:** create a folder `css`, upload `site.css` into it, open its link: the browser shows your stylesheet.
> Upload a `.html` file: it is refused with "files of this type cannot be uploaded here".

## 12. Backups

Open **Backup & restore** from the **Settings** menu (`/__admin/backups`). A backup is a **complete,
self-contained copy of your whole site** in a single `.zip`: your pages and templates, static
assets, users, roles and permissions, per-plugin configuration, and any data plugins keep for
your site. Because it is complete, it restores on the same or any later version of the platform
and renders identically — it is your site's safety net and its portable copy.

Under **Stored backups** a line shows the platform operator's policy for your site: whether backups run on their
own (and on which schedule), and how many are kept for how long. Older ones beyond that are removed automatically.
You cannot change it here; ask the operator.

**Taking a backup.** Two ways:

- **Download now** streams a fresh backup straight to your browser — nothing is kept on the
  server. Use this to keep an off-server copy (before a big redesign, or as a periodic
  archive). *Site administrators only* (see below).
- **Save on server** stores a timestamped backup under the platform's backups area. Saved
  backups are listed newest-first; each can be **downloaded** (administrators only) or **deleted**.
  Saved backups are convenient but live on the same server as the site — keep an occasional
  downloaded copy as well.

**Who may do what.** Taking, listing and deleting backups needs the *Manage backups* permission. A
**full** backup carries every account's password hash and every plugin secret of the site (§12.1), so
**downloading one — fresh or saved — is reserved for the site's administrators**: the site superadmin
and users of the *Administrator* tier. Someone who was only *granted* Manage backups can save backups
on the server, delete them, and download a **transfer copy** (§12.1); the full-download buttons are not
shown to them, and the platform refuses the download (*403*) if they try the address directly. A
backup they save stays on the server, where an administrator — or the platform operator — can
download or restore it. Why: handing out the permission to keep the site safe should not also hand out
a copy of everyone's credentials.

**Restoring.** Restore **replaces your entire site** with the contents of a backup — every
page, template, user and setting reverts to the moment the backup was taken. Because it is
destructive, **only the site's administrators (the site superadmin, or the Administrator tier) may
restore**; the restore controls are hidden for everyone else. You can restore either from a **saved backup** (pick it from the list) or by
**uploading a `.zip`** you downloaded earlier. One thing does not revert: the site's **host names and base URL**
(how visitors reach it) stay as the platform operator has set them now, whatever the backup says.

Restore is safe by design: the platform first checks the file is a genuine, complete site
backup and only then swaps it in, in one step. **A corrupt, empty, or wrong file is rejected
and your live site is left exactly as it was** — a failed or interrupted restore never leaves
you with a half-restored or missing site. After a successful restore the site reflects the
backup immediately (no restart), and the Backups screen shows the site's host names and base URL for you to check: a restore made here keeps them as
they were (how the site is reached is set by the platform operator), so if they look wrong, ask the operator.

A backup made on a **newer** version of the platform than the one your site runs on is refused
the same way, with the message *"This backup was made by a newer version of Runway … Ask the
platform operator to upgrade Runway, then restore it."* The older platform cannot be sure it
would keep everything in the file, so it changes nothing; once the platform is upgraded, the
same file restores normally.

> A backup is a copy of **your** site only. It never contains, and can never reach, another
> tenant's data or the platform's own configuration — the same isolation that applies
> everywhere on the platform (see **§1**).

### 12.1 Sending your site to someone else — use a transfer copy

**A backup is not safe to hand over.** Being complete is the whole point of it, and that means it contains:

- your site's **user accounts**, including their stored password hashes;
- every **credential** your plugins hold for this site — for example a CAPTCHA secret key;
- the **hostnames** your site answers on, and its public base URL.

Anyone you give that file to has all of it. That is fine for your own off-server copy, and wrong for a
designer you are working with, a file attached to a support request, or a site published as an example.

**Download a transfer copy instead.** The button sits next to *Download a backup now*. It produces the same
kind of `.zip`, restorable in the same way, with the parts that are yours alone taken out:

| Included | Removed |
|---|---|
| Pages, templates, styling, images, documents | User accounts and their password hashes |
| Icons, manifest, SEO settings, translations | Roles and permission assignments |
| Plugin settings that are **not** secrets | Plugin secrets (blanked, so you can see what to fill in) |
| | Hostnames and base URL (replaced with a placeholder) |
| | Traffic figures |
| | Plugin-held data such as form submissions |
| | Changes waiting for review — unpublished drafts, who submitted them, reviewers' notes |

The recipient restores it exactly like a backup. Their copy comes up with your content and appearance, no
accounts, and any secret settings blank and waiting to be filled in with **their** credentials.

> **The archive says which kind it is.** Its `manifest.yml` records `RESTORE` or `TRANSFER`, so a file can be
> identified later without opening every entry. An archive taken before this existed is treated as a full
> backup — the safe assumption, since assuming the opposite would mean treating a file full of secrets as
> though it had none.

> **A transfer copy is not an anonymiser.** It removes credentials and accounts; it does not read your
> **content**. If a page, an uploaded document, or a translation contains something private, that travels —
> check what you are sending, the same as you would with any other file.

> **What counts as a secret, and what does not.** A value is blanked if the plugin declares it secret, if it
> is stored encrypted, or if its name says what it is (`secret`, `password`, `token`, `credential`, `apiKey`,
> `privateKey` — at any depth, including inside lists). A value whose name merely contains *key* is **not**
> blanked, because plenty of them are public: a CAPTCHA **site** key ships in your page markup and is not a
> secret at all.
>
> But a site key is still **bound to your domain** — it will not work for whoever you send this to. Nor will
> anything else that names your origin inside a plugin's settings. Treat a transfer copy as *safe to send*,
> not as *ready to run*: the recipient re-enters their own keys, and the blanked fields show them which.

## 13. For designers (limited access)

If your only job is editing pages, this is your entry point.

**Getting in.** Your site manager creates you a user (usually an **Operator**) and grants you
**Edit pages** (and perhaps **Disable pages**) — see **§4**, **§5**. Sign in at your site's
**`/login`** (**§2**); you land in `/__admin` and see only the menu items your permissions allow
— typically just **Pages** (and your **Account**, to change your own password).

**What you can do:** create pages, edit and restyle them, manage their content and per-language
text, preview the live result, and — with *Disable pages* — take a page offline and bring it
back. **What you can't do** (unless separately granted): site configuration, managing users,
installing or configuring plugins.

**How to work.** You author each page as a **complete, self-contained HTML document** — open it
in a browser and it renders finished (**§7**). Follow the three-rule contract: `id` anything that
varies, keep the chrome seams and `#content` intact, and put **no template code** in your HTML.
Edit in **Pages → Edit** (**§6**), where the **Preview** shows the real, bound result and the
**Page quality** panel advises (**§10**) — and on a site set to *Enforced* or *Reviewed*, a page with
quality **errors** cannot be published until they are fixed. You may hand finished `.html` files
to your site manager instead of logging in — the model is identical either way.

**Where to go next:** **§6** for the editor and its buttons, **§7** for the full design contract
(seams, localized links, plugin `data-widget` anchors, the language switcher), and **§9** for
publishing a page in more than one language.
