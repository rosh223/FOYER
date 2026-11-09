/*!
 * Foyer tag — soft protection for pages without a server you control (static sites, SPAs, landing pages).
 *
 *   <script src="https://YOUR-FOYER/foyer.js" data-room="echoes-mumbai" data-room-id="rm_echoes"></script>
 *
 * Put it first in <head>. Visitors without a current pass for this room are sent to the waiting room, and come back here
 * with ?foyer_pass=… once it's their turn. The tag only checks the pass's room and expiry: anyone can bypass browser code,
 * so the things that matter (checkout, the API that sells) must verify the pass on the server — with the Java filter,
 * nginx auth_request, or GET /api/passes/verify. Free and open source.
 */
(function () {
  var script = document.currentScript;
  if (!script) return;
  var slug = script.getAttribute('data-room');
  var roomId = script.getAttribute('data-room-id');
  var base = script.getAttribute('data-foyer') || new URL(script.src).origin;
  if (!slug) return console.warn('[foyer] data-room is required');
  var KEY = 'foyer_pass_' + slug;

  function decode(pass) {
    try {
      var parts = pass.split('.');
      if (parts.length !== 3 || parts[0] !== 'fp1') return null;
      var b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      var f = decodeURIComponent(escape(atob(b64 + '==='.slice((b64.length + 3) % 4)))).split('|');
      return { roomId: f[0], visitorId: f[1], passId: f[2], notBefore: +f[3], expiresAt: +f[4] };
    } catch (e) {
      return null;
    }
  }

  function valid(pass) {
    var c = pass && decode(pass);
    return !!c && (!roomId || c.roomId === roomId) && c.expiresAt > Date.now();
  }

  var url = new URL(location.href);
  var fresh = url.searchParams.get('foyer_pass');
  if (fresh && valid(fresh)) {
    try { sessionStorage.setItem(KEY, fresh); } catch (e) { /* private mode */ }
    document.cookie = 'foyer_pass=' + fresh + '; path=/; SameSite=Lax' + (location.protocol === 'https:' ? '; Secure' : '');
    url.searchParams.delete('foyer_pass');
    history.replaceState(history.state, '', url.toString());
    window.foyer = { pass: fresh, claims: decode(fresh) };
    return;
  }
  var stored = null;
  try { stored = sessionStorage.getItem(KEY); } catch (e) { /* ignore */ }
  if (valid(stored)) {
    window.foyer = { pass: stored, claims: decode(stored) };
    return;
  }
  document.documentElement.style.visibility = 'hidden';
  location.replace(base + '/q/' + encodeURIComponent(slug) + '?return=' + encodeURIComponent(location.href));
})();
