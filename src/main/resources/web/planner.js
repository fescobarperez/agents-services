/*!
 * Planificador por presupuesto de Stackline.
 *
 * Uso, en la página de la empresa (un bloque fijo, no una burbuja):
 *   <div id="planificador"></div>
 *   <script src="https://AGENTES/v1/planner/embed.js" data-key="pk_..."
 *           data-target="#planificador" data-lang="es" async></script>
 *
 * Opcionales: data-lang="es|en" (por defecto, el del navegador) y
 * data-accent="#RRGGBB" (gana sobre el color configurado en la consola).
 * Sin data-target, el bloque se inserta justo después del script.
 *
 * Sin dependencias y dentro de un Shadow DOM: no hereda ni pisa los estilos
 * del sitio (solo la tipografía). El diseño se guarda en este navegador
 * (localStorage) y en el servidor los días que diga la empresa.
 */
(function () {
  'use strict';

  var script = document.currentScript;
  if (!script) {
    var todos = document.querySelectorAll('script[data-key][src*="embed.js"]');
    script = todos[todos.length - 1];
  }
  if (!script || window.__stacklinePlanner) return;
  window.__stacklinePlanner = true;

  var KEY = script.getAttribute('data-key') || '';
  var BASE = script.src.replace(/\/embed\.js(\?.*)?$/, '');
  var LANG = (script.getAttribute('data-lang') || navigator.language || 'es').slice(0, 2) === 'en' ? 'en' : 'es';
  var ACCENT = /^#[0-9a-f]{6}$/i.test(script.getAttribute('data-accent') || '') ? script.getAttribute('data-accent') : null;
  var STORE = 'stackline-planner:' + KEY;
  var MAX_HIST = 40;

  var T = {
    es: {
      title: 'Planificador de proyectos', note: 'Precios totales estimados · un asesor confirma la cotización final',
      budget: '¿Cuánto quieres invertir?', budgetHint: 'Monto total de tu proyecto', project: 'Proyecto',
      details: 'Detalles de tu proyecto', detailsHint: 'Se anotan mientras describes tu idea', remove: 'Quitar',
      options: 'Tu propuesta', optionsFor: 'Tu propuesta para {m}', optionsEmpty: 'Tu propuesta aparece aquí cuando describas tu idea.',
      placeholderFirst: 'Cuéntanos tu idea…', placeholder: 'Pide un cambio…',
      placeholderMeasures: 'Medidas (largo × fondo)…', placeholderQty: '¿Cuántas unidades?',
      send: 'Enviar', typing: 'Escribiendo…', tinoName: 'Tino', online: 'Responde en segundos', hello: '¿Qué te gustaría construir? Cuéntame tu idea y armo tu propuesta con precios reales.',
      noChat: 'Elige el tipo de proyecto y arma tu propuesta; luego prueba los cambios rápidos.', firstOption: 'Armar mi propuesta',
      fits: 'Encaja · te sobran {m}', tight: 'Justo en tu presupuesto', over: 'Se pasa por {m}', review: 'Un asesor confirma el precio',
      backTo: 'Para volver a {m}:', backToNo: 'Para acercarte a {m}:', suggestion: 'con «{c}», tu propuesta queda en {t}.', tryIt: 'Probar ese cambio',
      created: 'Listo, armé tu propuesta: {t}. {s}.',
      createdReview: 'Listo, armé tu propuesta. Un asesor confirma su precio.',
      kept: 'Tus diseños se guardan {n} días', renders: '{u} de {n} renders de este diseño',
      contact: 'Contactar a un asesor de ventas', contactHint: 'Se abre WhatsApp con tu diseño ya escrito',
      reset: 'Empezar de nuevo', resetAsk: '¿Empezar un diseño nuevo? Se borrará la propuesta de esta pantalla.',
      error: 'No pude completar eso. Intenta de nuevo.', offline: 'Sin conexión. Revisa tu internet.', wait: 'Vas muy rápido. Intenta en {n} s.',
      optionLabel: 'Tu propuesta', priceReview: 'Por confirmar',
      photo: 'Foto de tu espacio', photoAdd: 'Sube una foto de tu espacio', photoHint: 'Opcional: el render sale sobre ella',
      photoErr: 'No pude subir la foto. Intenta de nuevo.', photoType: 'Solo fotos JPG, PNG o WEBP.', photoRemove: 'Quitar foto',
      attach: 'Adjuntar una foto de tu espacio',
      renderHere: 'Tu render aparece aquí', renderHint: 'Confirma los detalles y toca «Ver render» en tu propuesta.',
      renderWait: 'Preparando tu render…', renderWaitHint: 'Tarda unos segundos.', renderFail: 'No pude generar el render. Toca «Reintentar» en tu propuesta.',
      seeRender: 'Ver render', renderReady: 'Render listo', renderPending: 'Preparando…', retry: 'Reintentar',
      illustrative: 'Render ilustrativo', renderAlt: 'Render ilustrativo de tu propuesta', noRendersLeft: 'Sin renders',
      renderNone: 'Ya usaste los {n} renders de este diseño. Puedes seguir ajustando tu propuesta; un asesor ve los cambios.',
      loadingProducts: 'Cargando productos…', noProducts: 'Por ahora no hay productos en esta categoría.',
      perUnit: '{p} / {u}', priceTbd: 'Precio por confirmar', measures: 'Medidas de tu espacio',
      length: 'Largo (m)', depth: 'Fondo (m)', qty: 'Cantidad', areaIs: 'Área: {a} m²',
      build: 'Armar mi propuesta', rebuild: 'Probar estas medidas', rebuildQty: 'Probar esta cantidad', reference: 'Foto de referencia',
      badNumber: 'Escribe medidas mayores que cero.',
      powered: 'Con tecnología de Corvex',
      viewFull: 'Ver imagen completa', close: 'Cerrar', zoomIn: 'Acercar', zoomOut: 'Alejar', fit: 'Ajustar a la pantalla',
      zoomHint: 'Rueda, pellizca o doble clic para acercar · arrastra para moverte · Esc para cerrar',
    },
    en: {
      title: 'Project planner', note: 'Estimated total prices · an advisor confirms the final quote',
      budget: 'How much do you want to invest?', budgetHint: 'Total amount for your project', project: 'Project',
      details: 'Project details', detailsHint: 'They fill in as you describe your idea', remove: 'Remove',
      options: 'Your proposal', optionsFor: 'Your proposal for {m}', optionsEmpty: 'Your proposal will show up here once you describe your idea.',
      placeholderFirst: 'Tell us your idea…', placeholder: 'Ask for a change…',
      placeholderMeasures: 'Size (length × depth)…', placeholderQty: 'How many?',
      send: 'Send', typing: 'Typing…', tinoName: 'Tino', online: 'Replies in seconds', hello: 'What would you like to build? Tell me your idea and I will put together your proposal with real prices.',
      noChat: 'Choose the project type and build your proposal; then try the quick changes.', firstOption: 'Build my proposal',
      fits: 'Fits · {m} to spare', tight: 'Right at your budget', over: '{m} over budget', review: 'An advisor confirms the price',
      backTo: 'To get back to {m}:', backToNo: 'To get closer to {m}:', suggestion: 'with “{c}”, your proposal comes to {t}.', tryIt: 'Try that change',
      created: 'Done, your proposal is ready: {t}. {s}.',
      createdReview: 'Done, your proposal is ready. An advisor confirms its price.',
      kept: 'Your designs are kept for {n} days', renders: '{u} of {n} renders for this design',
      contact: 'Contact a sales advisor', contactHint: 'WhatsApp opens with your design already written',
      reset: 'Start over', resetAsk: 'Start a new design? The proposal on this screen will be cleared.',
      error: 'That did not go through. Please try again.', offline: 'You are offline. Check your connection.', wait: 'Too fast. Try again in {n} s.',
      optionLabel: 'Your proposal', priceReview: 'To be confirmed',
      photo: 'Photo of your space', photoAdd: 'Upload a photo of your space', photoHint: 'Optional: the render is drawn on it',
      photoErr: 'The photo could not be uploaded. Please try again.', photoType: 'Only JPG, PNG or WEBP photos.', photoRemove: 'Remove photo',
      attach: 'Attach a photo of your space',
      renderHere: 'Your render shows up here', renderHint: 'Confirm the details and tap “See render” on your proposal.',
      renderWait: 'Preparing your render…', renderWaitHint: 'It takes a few seconds.', renderFail: 'The render could not be made. Tap “Retry” on your proposal.',
      seeRender: 'See render', renderReady: 'Render ready', renderPending: 'Preparing…', retry: 'Retry',
      illustrative: 'Illustrative render', renderAlt: 'Illustrative render of your proposal', noRendersLeft: 'No renders left',
      renderNone: 'You used the {n} renders for this design. You can keep adjusting your proposal; an advisor sees the changes.',
      loadingProducts: 'Loading products…', noProducts: 'There are no products in this category yet.',
      perUnit: '{p} / {u}', priceTbd: 'Price to be confirmed', measures: 'Your space',
      length: 'Length (m)', depth: 'Depth (m)', qty: 'Quantity', areaIs: 'Area: {a} m²',
      build: 'Build my proposal', rebuild: 'Try these measures', rebuildQty: 'Try this quantity', reference: 'Reference photo',
      badNumber: 'Enter measures greater than zero.',
      powered: 'Powered by Corvex',
      viewFull: 'View full image', close: 'Close', zoomIn: 'Zoom in', zoomOut: 'Zoom out', fit: 'Fit to screen',
      zoomHint: 'Scroll, pinch or double-click to zoom · drag to move · Esc to close',
    },
  }[LANG];

  if (!/^pk_[A-Za-z0-9]{20,40}$/.test(KEY)) {
    console.warn('[Stackline] data-key inválida en el script del planificador.');
    return;
  }

  // ── estado ──────────────────────────────────────────────────────────────
  var config = null;
  /** Lo que devuelve el servidor: presupuesto, tipo, notas, opciones con su semáforo. */
  var estado = null;
  /** Lo de este navegador: la sesión, la conversación y las respuestas rápidas. */
  var local = leer();
  var ocupado = false;
  var escribiendo = false;
  var aviso = null;
  /** Mientras un render está en camino se consulta el estado cada pocos segundos. */
  var consulta = null, consultaHasta = 0;
  var MAX_LADO = 1600, CALIDAD = 0.82, CADA_MS = 3000, ESPERA_MAX_MS = 180000;
  /** Productos de cada categoría (la lista del servidor), mientras la página está abierta. */
  var productos = {}, cargando = {}, fallos = {};

  function rid(n) {
    var abc = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    var bytes = new Uint8Array(n), out = '';
    if (window.crypto && window.crypto.getRandomValues) window.crypto.getRandomValues(bytes);
    else for (var i = 0; i < n; i++) bytes[i] = Math.floor(Math.random() * 256);
    for (var j = 0; j < n; j++) out += abc[bytes[j] % abc.length];
    return out;
  }
  function leer() {
    try { var s = JSON.parse(localStorage.getItem(STORE) || 'null'); if (s && typeof s === 'object') return s; } catch (e) { /* sin storage */ }
    return { session: null, messages: [], replies: [] };
  }
  function guardar() {
    try {
      local.messages = (local.messages || []).slice(-MAX_HIST);
      localStorage.setItem(STORE, JSON.stringify(local));
    } catch (e) { /* modo privado: el diseño vive solo en esta pestaña */ }
  }
  function f(plantilla, datos) {
    return plantilla.replace(/\{(\w+)\}/g, function (_, k) { return datos[k] == null ? '' : datos[k]; });
  }

  /** «Q 20,000»: el símbolo de la moneda de la empresa y centavos solo si los hay. */
  function dinero(v) {
    if (v == null || v === '') return '';
    var n = Number(v);
    var cur = (config && config.currency) || 'GTQ';
    var sim = cur === 'GTQ' ? 'Q' : cur === 'USD' ? '$' : cur;
    var entero = Math.abs(n - Math.round(n)) < 0.005;
    return sim + ' ' + n.toLocaleString('en-US', { minimumFractionDigits: entero ? 0 : 2, maximumFractionDigits: entero ? 0 : 2 });
  }
  function simbolo() {
    var cur = (config && config.currency) || 'GTQ';
    return cur === 'GTQ' ? 'Q' : cur === 'USD' ? '$' : cur;
  }

  function contraste(hex) {
    var m = /^#([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return '#ffffff';
    var n = parseInt(m[1], 16);
    var l = (0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
    return l > 0.62 ? '#1f1b17' : '#ffffff';
  }

  function mezclar(a, b, t) {
    var x = parseInt(a.slice(1), 16), y = parseInt(b.slice(1), 16);
    var c = [16, 8, 0].map(function (s) {
      var u = (x >> s) & 255, v = (y >> s) & 255;
      return Math.round(u + (v - u) * t);
    });
    return '#' + c.map(function (n) { return (n < 16 ? '0' : '') + n.toString(16); }).join('');
  }

  /**
   * Tino de cuerpo completo: el mismo personaje del chat web (widget.js), con
   * sus colores sacados del color del planificador. `id` distingue el filtro
   * del halo, porque en la página aparece más de una vez.
   */
  function tino(color, id) {
    var ojo = ACCENT || mezclar(color, '#ffffff', 0.7);
    var marca = ACCENT ? mezclar(ACCENT, '#000000', 0.25) : color;
    var contorno = mezclar(color, '#000000', 0.35);
    var b = 'fill="#ffffff" stroke="' + contorno + '" stroke-width="1.6"';
    var halo = 'pl-halo-' + id;
    return '<svg viewBox="-6 -4 76 84" focusable="false" aria-hidden="true">' +
      '<defs><filter id="' + halo + '" x="-20%" y="-20%" width="140%" height="140%">' +
      '<feMorphology in="SourceAlpha" operator="dilate" radius="1.6" result="borde"/>' +
      '<feFlood flood-color="#F6F2EA"/><feComposite in2="borde" operator="in" result="halo"/>' +
      '<feMerge><feMergeNode in="halo"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>' +
      '<g filter="url(#' + halo + ')"><g class="t-bob">' +
      '<rect x="24" y="61" width="6.5" height="13" rx="3.2" ' + b + '/>' +
      '<rect x="33.5" y="61" width="6.5" height="13" rx="3.2" ' + b + '/>' +
      '<g class="t-arm t-arm-l"><rect x="15" y="44" width="6" height="16" rx="3" ' + b + '/></g>' +
      '<g class="t-arm t-arm-r"><g class="t-wave"><rect x="43" y="44" width="6" height="16" rx="3" ' + b + '/></g></g>' +
      '<rect x="20.5" y="41" width="23" height="22" rx="8.5" ' + b + '/>' +
      '<path d="M12 4a8 8 0 1 1-8 8V4z" transform="translate(26.5 46.5) scale(.46)" fill="' + marca + '"/>' +
      '<g class="t-head">' +
      '<path d="M32 6a20 20 0 1 1-20 20V6z" ' + b + ' stroke-linejoin="round"/>' +
      '<rect x="18" y="15" width="28" height="20" rx="7" fill="#17151C"/>' +
      '<rect x="21" y="18" width="5" height="2.2" rx="1.1" fill="#3A3540"/>' +
      '<circle class="t-eye t-fb t-blink" cx="33" cy="25" r="3.6" fill="' + ojo + '"/>' +
      '</g></g></g></svg>';
  }

  // ── red ─────────────────────────────────────────────────────────────────
  function pedir(metodo, ruta, cuerpo) {
    var opciones = { method: metodo };
    if (cuerpo !== undefined) {
      // text/plain evita el preflight de CORS; el servidor lo lee como JSON.
      opciones.headers = { 'Content-Type': 'text/plain;charset=UTF-8' };
      opciones.body = JSON.stringify(cuerpo);
    }
    return fetch(BASE + '/' + KEY + ruta, opciones).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (d) {
        return { ok: r.ok, status: r.status, data: d || {}, retry: Number(r.headers.get('Retry-After')) || null };
      });
    }, function () {
      return { ok: false, status: 0, data: { message: navigator.onLine === false ? T.offline : T.error } };
    });
  }
  function ruta(resto) { return '/sessions/' + encodeURIComponent(local.session) + (resto || ''); }

  /** Mensaje de error para el visitante: el del servidor, o uno genérico. */
  function motivo(res) {
    if (res.status === 429 && res.retry) return f(T.wait, { n: res.retry });
    return (res.data && res.data.message) || T.error;
  }

  // ── estilos ─────────────────────────────────────────────────────────────
  // El mismo clip del chat web (widget.js) para adjuntar la foto del espacio.
  var CLIP = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m21.4 11.1-8.5 8.5a5.5 5.5 0 0 1-7.8-7.8l8.5-8.5a3.7 3.7 0 0 1 5.2 5.2l-8.5 8.5a1.8 1.8 0 0 1-2.6-2.6l7.8-7.8"/></svg>';
  // Y el mismo avión de papel para enviar.
  var SEND = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M3 20.5 21 12 3 3.5 3 10l12 2-12 2z"/></svg>';
  var CSS = [
    // Se aísla de los estilos del sitio salvo la tipografía, para que el bloque se vea de la página.
    ':host{all:initial;display:block;font-family:inherit}',
    '.pl{--bg:#fbf9f6;--soft:#f4efe8;--line:#e6ded3;--ink:#1f1b17;--muted:#70675d;--ok:#2e7d4f;--ok-bg:#e3f2e8;--warn:#9a6200;--warn-bg:#fff1d6;',
    '--bad:#b3261e;--bad-bg:#fce4e2;--grey:#70675d;--grey-bg:#ece7e0;container-type:inline-size;font-family:var(--pl-font,inherit);',
    'color:var(--ink);background:var(--bg);border:1px solid var(--line);border-radius:24px;overflow:hidden;box-sizing:border-box;',
    'line-height:1.45;font-size:15px;-webkit-font-smoothing:antialiased;text-align:left}',
    '.pl *,.pl *::before,.pl *::after{box-sizing:border-box}',
    '.pl button{font:inherit;cursor:pointer}.pl button:disabled{cursor:default;opacity:.55}',
    '.head{display:flex;gap:12px;align-items:baseline;justify-content:space-between;flex-wrap:wrap;padding:20px 28px;border-bottom:1px solid var(--line)}',
    '.head h2{margin:0;font-size:20px;font-weight:700;letter-spacing:-.01em}',
    '.head .note{color:var(--muted);font-size:13px}',
    '.reset{background:none;border:0;padding:0;color:var(--muted);font-size:13px;text-decoration:underline;margin-left:12px}',
    // Alto fijo: el bloque no cambia de tamaño con cada mensaje; cada columna hace scroll por dentro.
    '.body{display:grid;grid-template-columns:minmax(220px,1fr) minmax(0,2.1fr) minmax(240px,1.15fr);height:640px}',
    '.col{padding:24px;min-width:0;min-height:0}.left{border-right:1px solid var(--line)}.right{border-left:1px solid var(--line)}',
    '.left,.right{overflow-y:auto;overscroll-behavior:contain;scrollbar-width:thin}',
    '.lbl{display:block;margin:0 0 8px;color:var(--c);font-size:12px;font-weight:700;letter-spacing:.08em;text-transform:uppercase}',
    '.money{display:flex;align-items:center;gap:8px;border:2px solid var(--c);border-radius:14px;padding:8px 16px;background:#fff}',
    '.money span{color:var(--c);font-weight:700;font-size:20px}',
    '.money input{flex:1;min-width:0;border:0;outline:0;background:transparent;font:inherit;font-size:30px;font-weight:700;color:var(--ink);padding:4px 0}',
    '.hint{color:var(--muted);font-size:13px;margin:6px 0 0}',
    '.sec{margin-top:24px}',
    '.chips{display:flex;flex-wrap:wrap;gap:8px}',
    '.chip{border:1px solid var(--line);background:#fff;color:var(--ink);border-radius:10px;padding:8px 14px;font-size:14px}',
    '.chip:hover:not(:disabled){border-color:var(--c)}',
    '.chip.on{background:var(--c);border-color:var(--c);color:var(--on)}',
    '.tipos{display:flex;flex-direction:column;gap:10px}',
    // La lista de productos de la categoría: foto, nombre y precio de referencia.
    '.prods{display:flex;flex-direction:column;gap:6px;max-height:340px;overflow-y:auto;overscroll-behavior:contain;scrollbar-width:thin;padding-right:2px}',
    '.prod{display:flex;gap:10px;align-items:center;width:100%;text-align:left;border:1px solid var(--line);background:#fff;border-radius:12px;padding:6px;color:var(--ink)}',
    '.prod:hover:not(:disabled){border-color:var(--c)}.prod.on{border:2px solid var(--c);padding:5px}',
    '.prod img,.prod .ph{width:52px;height:52px;border-radius:9px;object-fit:cover;flex:none;background:linear-gradient(135deg,#dfe7ee,#efe6d8)}',
    '.prod .pt{min-width:0;display:flex;flex-direction:column;gap:2px}',
    '.prod b{font-size:14px;line-height:1.25;overflow-wrap:anywhere}.prod small{color:var(--muted);font-size:12px}',
    '.medir{display:flex;flex-wrap:wrap;align-items:flex-end;gap:10px;background:var(--soft);border-radius:14px;padding:12px 14px}',
    '.medir .lbl{width:100%;margin:0}.medir label{display:flex;flex-direction:column;gap:4px;font-size:12px;color:var(--muted)}',
    '.medir input{width:96px;border:1px solid var(--line);border-radius:10px;padding:8px 10px;font:inherit;color:var(--ink);background:#fff;outline:0}',
    '.medir input:focus{border-color:var(--c)}.medir .area{font-size:13px;color:var(--muted);padding-bottom:9px}',
    '.medir button{border:0;border-radius:999px;background:var(--c);color:var(--on);font-weight:700;padding:10px 18px;margin-left:auto}',
    '.dets{display:flex;flex-direction:column;gap:6px}',
    '.det{display:grid;grid-template-columns:auto 1fr auto;gap:12px;align-items:center;background:var(--soft);border:1px solid transparent;border-radius:10px;padding:8px 12px}',
    '.det.new{background:var(--ok-bg);border-color:#8cc5a2}',
    '.det b{color:var(--c);font-size:11px;letter-spacing:.06em;text-transform:uppercase;min-width:74px}',
    '.det.new b{color:var(--ok)}',
    '.det .x{background:none;border:0;color:var(--muted);font-size:16px;line-height:1;padding:2px 4px}',
    '.center{display:flex;flex-direction:column;gap:12px;overflow:hidden}',
    '.center>*{flex:none}',
    // El chat en su caja: ocupa el resto del alto bajo el render; los mensajes hacen scroll por dentro.
    '.center>.chat{flex:1 1 auto;min-height:0;display:flex;flex-direction:column;gap:10px;background:var(--soft);border:1px solid var(--line);border-radius:16px;padding:12px}',
    '.chat>*{flex:none}.alts:empty{display:none}',
    '.log{display:flex;flex-direction:column;gap:10px;flex:1 1 auto;min-height:0;overflow-y:auto;overscroll-behavior:contain;scrollbar-width:thin;padding-right:2px}',
    '.msg{max-width:88%;padding:12px 16px;border-radius:16px;white-space:pre-wrap;word-wrap:break-word}',
    '.msg.me{align-self:flex-end;background:var(--c);color:var(--on);border-bottom-right-radius:6px}',
    '.msg.bot{align-self:flex-start;background:#fff;border:1px solid var(--line);border-bottom-left-radius:6px}',
    '.msg.err{align-self:flex-start;background:var(--bad-bg);color:var(--bad);font-size:14px}',
    '.msg .replies{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}',
    '.reply{border:1.5px solid var(--c);background:#fff;color:var(--c);border-radius:999px;padding:7px 16px;font-weight:600;font-size:14px}',
    '.reply:hover:not(:disabled){background:var(--c);color:var(--on)}',
    // Tino: el encabezado del chat y los tres puntos mientras responde (mismas animaciones del chat web).
    '.chead{display:flex;align-items:center;gap:10px;padding:0 2px 10px;border-bottom:1px solid var(--line)}',
    '.chead .tino svg{width:34px;height:38px}',
    '.chead b{display:block;font-size:14px;line-height:1.2}',
    '.chead small{display:flex;align-items:center;gap:6px;color:var(--muted);font-size:12px}',
    ".chead small::before{content:'';width:7px;height:7px;border-radius:50%;background:var(--ok)}",
    '.tino{line-height:0;flex:none}.tino svg{display:block;overflow:visible}',
    '.typing{align-self:flex-start;display:flex;align-items:flex-end;gap:8px}',
    '.typing .tino svg{width:24px;height:27px}',
    '.dots{background:#fff;border:1px solid var(--line);border-radius:14px;border-bottom-left-radius:4px;padding:10px 12px;display:flex;gap:4px}',
    '.dots i{width:7px;height:7px;border-radius:50%;background:#b9aeb2;animation:pl-dot 1.2s infinite}',
    '.dots i:nth-child(2){animation-delay:.15s}.dots i:nth-child(3){animation-delay:.3s}',
    '@keyframes pl-dot{0%,60%,100%{transform:none;opacity:.5}30%{transform:translateY(-4px);opacity:1}}',
    '.t-fb{transform-box:fill-box;transform-origin:center}',
    '.t-blink{animation:t-blink 4s ease-in-out infinite}',
    '.t-bob{animation:t-bob 3.2s ease-in-out infinite}',
    '.t-head{transform-box:view-box;transform-origin:32px 42px}',
    '.t-arm{transform-box:view-box}.t-arm-l{transform-origin:18px 47px;transform:rotate(8deg)}.t-arm-r{transform-origin:46px 47px;transform:rotate(-8deg)}',
    '@keyframes t-blink{0%,92%,100%{transform:scaleY(1)}95%{transform:scaleY(.1)}}',
    '@keyframes t-bob{0%,100%{transform:translateY(0)}50%{transform:translateY(1.2px)}}',
    '@media (prefers-reduced-motion:reduce){.t-blink,.t-bob,.dots i{animation:none}}',
    // Un solo campo, como el chat web: clip, texto y enviar dentro del mismo borde.
    '.ask{display:flex;align-items:flex-end;gap:4px;border:1px solid var(--line);border-radius:14px;background:#fff;padding:4px}',
    '.ask:focus-within{border-color:var(--c)}',
    '.ask textarea{flex:1;min-width:0;resize:none;border:0;padding:10px 6px;font:inherit;color:var(--ink);background:transparent;min-height:40px;max-height:140px;outline:0}',
    '.ask textarea::placeholder{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '.send{width:40px;height:40px;flex:none;border:0;border-radius:50%;background:var(--c);color:var(--on);display:grid;place-items:center;padding:0}',
    '.send:disabled{opacity:.45;cursor:default}.send svg{width:18px;height:18px}',
    '.send:focus-visible{outline:2px solid var(--c);outline-offset:2px}',
    '.clip{width:40px;height:40px;flex:none;border:0;border-radius:10px;background:transparent;color:var(--muted);display:grid;place-items:center;padding:0}',
    '.clip:hover:not(:disabled){color:var(--c)}.clip:disabled{opacity:.45;cursor:default}.clip svg{width:22px;height:22px}',
    '.clip:focus-visible{outline:2px solid var(--c);outline-offset:2px}',
    '.tray{display:none}.tray.on{display:flex;gap:12px;align-items:center;border:1px solid var(--line);border-radius:12px;padding:8px 10px;background:#fff}',
    '.tray img{width:48px;height:48px;border-radius:8px;object-fit:cover;flex:none}',
    '.tray b{display:block;font-size:14px}.tray small{color:var(--muted);font-size:13px}',
    '.tray .x{margin-left:auto;background:none;border:0;color:var(--muted);font-size:18px;padding:4px 6px}',
    '.alts{display:flex;flex-wrap:wrap;gap:8px}',
    '.first{align-self:flex-start;border:0;border-radius:999px;background:var(--c);color:var(--on);font-weight:700;padding:12px 22px}',
    '.empty{color:var(--muted);font-size:14px;border:1px dashed var(--line);border-radius:14px;padding:20px;text-align:center}',
    '.cards{display:flex;flex-direction:column;gap:12px}',
    '.card{display:block;width:100%;text-align:left;background:#fff;border:1px solid var(--line);border-radius:14px;padding:12px 14px;color:var(--ink)}',
    '.card.on{border:2px solid var(--c);padding:11px 13px;box-shadow:0 3px 10px rgba(31,27,23,.06)}',
    '.card .top{display:flex;flex-wrap:wrap;justify-content:space-between;gap:2px 10px;align-items:baseline}',
    '.card h3{flex:1 1 9em;min-width:0;margin:0;font-size:15px;font-weight:700;line-height:1.25;overflow-wrap:anywhere}',
    '.card .price{font-weight:700;font-size:16px;white-space:nowrap}',
    '.card .sum{color:var(--muted);font-size:12px;margin:2px 0 0}',
    '.card .bar{height:6px;margin:10px 0 8px}.card .pill{padding:3px 10px;font-size:12px}.card .rbtn{padding:4px 12px;font-size:12px}',
    '.bar{position:relative;height:8px;border-radius:999px;background:var(--grey-bg);margin:14px 0 12px}',
    '.bar i{position:absolute;left:0;top:0;bottom:0;border-radius:999px}',
    '.bar em{position:absolute;top:-6px;bottom:-6px;width:2px;background:var(--ink)}',
    '.pill{display:inline-block;border-radius:999px;padding:4px 12px;font-size:13px;font-weight:700}',
    '.fits{color:var(--ok);background:var(--ok-bg)}.tight{color:var(--warn);background:var(--warn-bg)}',
    '.over{color:var(--bad);background:var(--bad-bg)}.review{color:var(--grey);background:var(--grey-bg)}',
    '.tip{margin-top:12px;background:#fff8e6;border:1px solid #f0dcaa;border-radius:14px;padding:14px;font-size:14px;color:#5b4512}',
    '.tip b{color:#5b4512}.tip button{margin-top:10px;border:1px solid #d6b866;background:#fff;color:#5b4512;border-radius:999px;padding:6px 14px;font-weight:600;font-size:13px}',
    '.foot{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap;padding:16px 28px;border-top:1px solid var(--line);background:var(--soft)}',
    '.foot .kept{color:var(--muted);font-size:14px}',
    // La misma firma que el chat web (widget.js), pegada al pie.
    '.powered{font-size:11px;color:var(--muted);text-align:center;padding:0 16px 10px;background:var(--soft)}',
    '.powered a{color:inherit;text-decoration:none}.powered a:hover{color:var(--ink);text-decoration:underline}',
    '.btns{display:flex;gap:10px;flex-wrap:wrap}',
    '.ghost{border:1.5px solid var(--c);background:#fff;color:var(--c);border-radius:999px;padding:11px 22px;font-weight:700}',
    '.primary{border:0;background:var(--c);color:var(--on);border-radius:999px;padding:12px 24px;font-weight:700;text-decoration:none;display:inline-block;text-align:center}',
    '.primary:hover{filter:brightness(1.06)}',
    '.sr{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}',
    '.photo{display:flex;gap:12px;align-items:center;width:100%;border:1.5px dashed var(--line);border-radius:14px;padding:12px;background:transparent;color:var(--ink);text-align:left}',
    '.photo:hover:not(:disabled){border-color:var(--c)}',
    '.photo img,.photo .ph{width:56px;height:56px;border-radius:10px;object-fit:cover;flex:none;background:linear-gradient(135deg,#dfe7ee,#efe6d8)}',
    '.photo b{display:block;font-size:14px}.photo small{color:var(--muted);font-size:13px}',
    '.photo .x{margin-left:auto;background:none;border:0;color:var(--muted);font-size:18px;padding:4px}',
    // Siempre arriba y de alto fijo, haya render o no: el chat queda debajo sin saltos.
    '.render{position:relative;border-radius:16px;overflow:hidden;background:var(--soft);height:220px;display:flex;align-items:center;justify-content:center;text-align:center}',
    '.render.empty{border:1.5px dashed var(--line);background:#f6f1ea;padding:16px}',
    '.render.empty b{display:block;font-size:16px;margin-bottom:4px}.render.empty span{color:var(--muted);font-size:13px}',
    '.render img{width:100%;height:100%;object-fit:cover;display:block}',
    '.render .badge{position:absolute;top:14px;left:14px;background:#fff;border-radius:999px;padding:6px 14px;font-size:13px;font-weight:700;display:flex;gap:8px;align-items:center;box-shadow:0 2px 8px rgba(0,0,0,.12)}',
    '.render .badge i{width:9px;height:9px;border-radius:50%;display:inline-block}',
    '.render.zoomable img{cursor:zoom-in}',
    '.render .expand{position:absolute;top:14px;right:14px;width:38px;height:38px;border:0;border-radius:999px;background:rgba(31,27,23,.72);color:#fff;font-size:18px;line-height:1;display:flex;align-items:center;justify-content:center;box-shadow:0 2px 8px rgba(0,0,0,.2)}',
    '.render .expand:hover{background:rgba(31,27,23,.9)}.render .expand:focus-visible{outline:2px solid #fff;outline-offset:2px}',
    '.render .tag{position:absolute;right:14px;bottom:14px;background:rgba(31,27,23,.82);color:#fff;border-radius:8px;padding:5px 10px;font-size:12px}',
    '.spin{width:26px;height:26px;border:3px solid var(--line);border-top-color:var(--c);border-radius:50%;margin:0 auto 10px;animation:pl-spin 1s linear infinite}',
    '@keyframes pl-spin{to{transform:rotate(360deg)}}',
    '.cfoot{display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap}',
    '.rbtn{border:1.5px solid var(--c);background:#fff;color:var(--c);border-radius:999px;padding:5px 14px;font-weight:700;font-size:13px}',
    '.rbtn.done{border-color:var(--line);color:var(--muted)}',
    // Una columna en contenedores angostos (el ancho del bloque, no de la ventana):
    // chat y render → presupuesto y proyecto → propuesta y detalles → botones.
    '@container (max-width:819px){.body{grid-template-columns:1fr;height:auto}.left,.right{overflow:visible}.center{order:1;overflow:visible}.left{order:2;border-right:0;border-top:1px solid var(--line)}',
    '.right{order:3;border-left:0;border-top:1px solid var(--line)}.col{padding:20px 16px}.head{padding:18px 16px}',
    '.foot{flex-direction:column;align-items:stretch;padding:16px}.btns>*{flex:1}.center>.chat{flex:none}.log{flex:none;height:300px}.money input{font-size:26px}}',
  ].join('');

  // ── vista ───────────────────────────────────────────────────────────────
  var host, root, ui = {};

  function el(tag, cls, texto) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (texto != null) n.textContent = texto;
    return n;
  }
  function boton(cls, texto, accion) {
    var b = el('button', cls, texto);
    b.type = 'button';
    b.addEventListener('click', function () { if (!ocupado) accion(); });
    return b;
  }

  function montar() {
    var destino = document.querySelector(script.getAttribute('data-target') || '');
    host = el('div');
    host.setAttribute('data-stackline-planner', '');
    // Páginas con scroll suave (Lenis, Locomotive…) capturan la rueda en toda
    // la ventana: sin esto el chat y las opciones no se podrían desplazar.
    host.setAttribute('data-lenis-prevent', '');
    host.setAttribute('data-scroll-ignore', '');
    if (destino) destino.appendChild(host);
    else script.parentNode.insertBefore(host, script.nextSibling);
    root = host.attachShadow ? host.attachShadow({ mode: 'open' }) : host;

    var color = ACCENT || (/^#[0-9a-f]{6}$/i.test(config.accent || '') ? config.accent : '#8B6549');
    var style = el('style'); style.textContent = CSS; root.appendChild(style);
    var pl = el('section', 'pl');
    pl.setAttribute('aria-label', config.title || T.title);
    pl.style.setProperty('--c', color);
    pl.style.setProperty('--on', contraste(color));

    var head = el('header', 'head');
    head.appendChild(el('h2', null, config.title || T.title));
    var nota = el('div', 'note', T.note);
    nota.appendChild(boton('reset', T.reset, reiniciar));
    head.appendChild(nota);

    var body = el('div', 'body');
    // Izquierda: presupuesto, tipo de proyecto y detalles.
    var left = el('div', 'col left');
    var lbl = el('label', 'lbl', T.budget);
    var money = el('div', 'money');
    money.appendChild(el('span', null, simbolo()));
    ui.budget = el('input');
    ui.budget.inputMode = 'numeric';
    ui.budget.autocomplete = 'off';
    ui.budget.id = 'pl-budget';
    lbl.htmlFor = 'pl-budget';
    ui.budget.addEventListener('input', function () {
      var limpio = ui.budget.value.replace(/[^\d]/g, '').replace(/^0+(?=\d)/, '').slice(0, 12);
      ui.budget.value = limpio ? Number(limpio).toLocaleString('en-US') : '';
    });
    ui.budget.addEventListener('change', guardarPresupuesto);
    ui.budget.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); ui.budget.blur(); } });
    money.appendChild(ui.budget);
    left.appendChild(lbl);
    left.appendChild(money);
    left.appendChild(el('p', 'hint', T.budgetHint));
    var secTipo = el('div', 'sec');
    secTipo.appendChild(el('span', 'lbl', T.project));
    ui.types = el('div', 'tipos');
    secTipo.appendChild(ui.types);
    left.appendChild(secTipo);

    // La foto del espacio: base del render (opcional). Con chat se adjunta con
    // el clip, como en el chat web; sin chat va debajo de los detalles.
    var conFoto = !!(config.features && config.features.renders);
    if (conFoto) {
      ui.file = el('input');
      ui.file.type = 'file';
      ui.file.accept = 'image/jpeg,image/png,image/webp';
      ui.file.className = 'sr';
      ui.file.tabIndex = -1;
      ui.file.addEventListener('change', function () { var f0 = ui.file.files && ui.file.files[0]; ui.file.value = ''; if (f0) subirFoto(f0); });
    }

    // Centro, de arriba abajo: el render (haya o no), la conversación, los
    // cambios rápidos y donde se escribe. Alto fijo: lo que crece es el chat, con scroll.
    var center = el('div', 'col center');
    ui.center = center;
    if (config.features && config.features.renders) {
      ui.render = el('div', 'render empty');
      ui.render.setAttribute('aria-live', 'polite');
      center.appendChild(ui.render);
    }
    // El chat en su propio contenedor: mensajes, cambios rápidos, foto adjunta y donde se escribe.
    var chat = el('div', 'chat');
    center.appendChild(chat);
    ui.color = color;
    if (config.chat) {
      // Con quién habla: Tino, igual que en el chat web.
      var cab = el('div', 'chead');
      var fig = el('span', 'tino');
      fig.innerHTML = tino(color, 'cab');
      cab.appendChild(fig);
      var quien = el('span');
      quien.appendChild(el('b', null, T.tinoName));
      quien.appendChild(el('small', null, T.online));
      cab.appendChild(quien);
      chat.appendChild(cab);
    }
    ui.log = el('div', 'log');
    ui.log.setAttribute('aria-live', 'polite');
    chat.appendChild(ui.log);
    ui.alts = el('div', 'alts');
    chat.appendChild(ui.alts);
    if (config.chat && conFoto) {
      // La foto adjunta, encima de donde se escribe.
      ui.tray = el('div', 'tray');
      chat.appendChild(ui.tray);
    }
    if (config.chat) {
      var ask = el('form', 'ask');
      if (conFoto) {
        ui.clip = el('button', 'clip');
        ui.clip.type = 'button';
        ui.clip.innerHTML = CLIP;
        ui.clip.title = T.attach;
        ui.clip.setAttribute('aria-label', T.attach);
        ui.clip.addEventListener('click', function () { if (!ocupado) ui.file.click(); });
        ask.appendChild(ui.clip);
        ask.appendChild(ui.file);
      }
      ui.input = el('textarea');
      ui.input.rows = 1;
      ui.input.maxLength = config.maxMessageLength || 600;
      ui.input.setAttribute('aria-label', T.placeholder);
      ui.input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); enviar(); }
      });
      ui.input.addEventListener('input', function () {
        ui.input.style.height = 'auto';
        ui.input.style.height = Math.min(ui.input.scrollHeight, 140) + 'px';
      });
      ui.send = el('button', 'send');
      ui.send.innerHTML = SEND;
      ui.send.title = T.send;
      ui.send.setAttribute('aria-label', T.send);
      ui.send.type = 'submit';
      ask.addEventListener('submit', function (e) { e.preventDefault(); enviar(); });
      ask.appendChild(ui.input);
      ask.appendChild(ui.send);
      chat.appendChild(ask);
    } else {
      // Sin chat, las medidas (o la cantidad) del producto elegido se escriben
      // aquí y arman la propuesta. Con chat se dicen en la conversación.
      ui.medir = el('div');
      center.appendChild(ui.medir);
    }

    // Derecha: una sola propuesta (la activa) con su semáforo y, debajo, los
    // detalles del proyecto. Las versiones anteriores no se muestran.
    var right = el('div', 'col right');
    ui.optionsTitle = el('span', 'lbl');
    right.appendChild(ui.optionsTitle);
    ui.cards = el('div', 'cards');
    right.appendChild(ui.cards);
    ui.tip = el('div');
    right.appendChild(ui.tip);
    var secDet = el('div', 'sec');
    secDet.appendChild(el('span', 'lbl', T.details));
    ui.details = el('div', 'dets');
    secDet.appendChild(ui.details);
    secDet.appendChild(el('p', 'hint', T.detailsHint));
    right.appendChild(secDet);
    if (conFoto && !config.chat) {
      ui.photo = el('div', 'sec');
      right.appendChild(ui.photo);
      right.appendChild(ui.file);
    }

    body.appendChild(left);
    body.appendChild(center);
    body.appendChild(right);

    var foot = el('footer', 'foot');
    ui.kept = el('span', 'kept');
    foot.appendChild(ui.kept);
    // «Contactar a un asesor»: no se crea cotización; abre el WhatsApp de la empresa con el diseño escrito.
    ui.btns = el('div', 'btns');
    foot.appendChild(ui.btns);

    pl.appendChild(head);
    pl.appendChild(body);
    pl.appendChild(foot);
    // La firma: enlace a Corvex (nueva pestaña) si el servicio trae la URL; si no, texto.
    var firma = el('div', 'powered');
    if (/^https:\/\//.test(config.poweredByUrl || '')) {
      var a = el('a', null, T.powered);
      a.href = config.poweredByUrl;
      a.target = '_blank';
      a.rel = 'noopener';
      firma.appendChild(a);
    } else firma.textContent = T.powered;
    pl.appendChild(firma);
    root.appendChild(pl);
  }

  function activa() {
    if (!estado) return null;
    var l = estado.activeOption;
    for (var i = 0; i < estado.options.length; i++) if (estado.options[i].letter === l) return estado.options[i];
    return estado.options[estado.options.length - 1] || null;
  }

  function textoEstado(o) {
    var b = o.budget || {};
    if (b.status === 'fits') return f(T.fits, { m: dinero(b.remaining) });
    if (b.status === 'tight') return T.tight;
    if (b.status === 'over') return f(T.over, { m: dinero(b.excess) });
    if (b.status === 'review') return T.review;
    return '';
  }

  function pintar() {
    if (!estado) return;
    pintarPresupuesto();
    pintarTipos();
    pintarDetalles();
    pintarConversacion();
    pintarMedidas();
    pintarCambios();
    pintarOpciones();
    pintarFoto();
    pintarRender();
    pintarContacto();
    seguirRenders();
    var u = estado.renders || {};
    // Tope por diseño (la sesión), del playbook del agente: como las imágenes por cotización.
    var guardado = f(T.kept, { n: config.retentionDays || 7 });
    // A mitad de frase va en minúscula: «0 de 2 renders de este diseño · tus diseños…».
    ui.kept.textContent = ui.render && u.max > 0
      ? f(T.renders, { u: u.used || 0, n: u.max }) + ' · ' + guardado.charAt(0).toLowerCase() + guardado.slice(1)
      : guardado;
  }

  function url(ruta) { return BASE + ruta; }

  function pintarFoto() {
    if (ui.clip) ui.clip.disabled = ocupado;
    if (ui.tray) {
      // Con chat: la foto adjunta, como un adjunto del mensaje.
      ui.tray.textContent = '';
      ui.tray.className = 'tray' + (estado.photo ? ' on' : '');
      if (!estado.photo) return;
      var mini = el('img');
      mini.src = url(estado.photo.url);
      mini.alt = '';
      ui.tray.appendChild(mini);
      var dice = el('span');
      dice.appendChild(el('b', null, T.photo));
      dice.appendChild(el('small', null, T.photoHint));
      ui.tray.appendChild(dice);
      var quitar = boton('x', '×', function () { if (!ocupado) quitarFoto(); });
      quitar.setAttribute('aria-label', T.photoRemove);
      quitar.disabled = ocupado;
      ui.tray.appendChild(quitar);
      return;
    }
    if (!ui.photo) return;
    ui.photo.textContent = '';
    ui.photo.appendChild(el('span', 'lbl', T.photo));
    var caja = boton('photo', null, function () { ui.file.click(); });
    caja.disabled = ocupado;
    if (estado.photo) {
      var img = el('img');
      img.src = url(estado.photo.url);
      img.alt = '';
      caja.appendChild(img);
      var txt = el('span');
      txt.appendChild(el('b', null, estado.photo.name || T.photo));
      txt.appendChild(el('small', null, T.photoHint));
      caja.appendChild(txt);
      var x = el('span', 'x', '×');
      x.setAttribute('role', 'button');
      x.setAttribute('aria-label', T.photoRemove);
      x.addEventListener('click', function (e) { e.stopPropagation(); if (!ocupado) quitarFoto(); });
      caja.appendChild(x);
    } else {
      caja.appendChild(el('span', 'ph'));
      var t2 = el('span');
      t2.appendChild(el('b', null, T.photoAdd));
      t2.appendChild(el('small', null, T.photoHint));
      caja.appendChild(t2);
    }
    ui.photo.appendChild(caja);
  }

  /** El render de la opción activa: listo, en camino, fallido o el lugar donde va a aparecer. */
  function pintarRender() {
    if (!ui.render) return;
    var o = activa();
    var r = o && o.render;
    ui.render.textContent = '';
    ui.render.className = 'render' + (r && r.status === 'ready' && r.url ? '' : ' empty');
    if (r && r.status === 'ready' && r.url) {
      var img = el('img');
      img.src = url(r.url);
      img.alt = T.renderAlt;
      ui.render.appendChild(img);
      ampliable(img, T.optionLabel + ' · ' + o.title);
      var b = o.budget || {};
      var badge = el('span', 'badge');
      var punto = el('i');
      punto.style.background = b.status === 'over' ? 'var(--bad)' : b.status === 'tight' ? '#d99a1e' : b.status === 'fits' ? 'var(--ok)' : 'var(--grey)';
      badge.appendChild(punto);
      badge.appendChild(document.createTextNode(T.optionLabel + (textoEstado(o) ? ' · ' + textoEstado(o) : '')));
      ui.render.appendChild(badge);
      ui.render.appendChild(el('span', 'tag', T.illustrative));
      return;
    }
    var p = productoElegido();
    if (!r && p && p.image) {
      ui.render.className = 'render';
      var ref = el('img');
      // La miniatura de la lista sirve de respaldo mientras llega la completa.
      ref.src = url(p.image.replace('size=thumb&', ''));
      ref.alt = p.name;
      ui.render.appendChild(ref);
      ui.render.appendChild(el('span', 'tag', T.reference));
      ampliable(ref, p.name);
      return;
    }
    var caja = el('div');
    if (r && r.status === 'pending') {
      caja.appendChild(el('div', 'spin'));
      caja.appendChild(el('b', null, T.renderWait));
      caja.appendChild(el('span', null, T.renderWaitHint));
    } else {
      caja.appendChild(el('b', null, T.renderHere));
      var u = estado.renders || {};
      var sinQuedar = (u.max || 0) > 0 && (u.used || 0) >= u.max;
      caja.appendChild(el('span', null, r && r.status === 'failed' ? T.renderFail : sinQuedar ? f(T.renderNone, { n: u.max }) : T.renderHint));
    }
    ui.render.appendChild(caja);
  }

  /** Clic en la imagen (o en ⤢) la abre completa en el visor, con zoom. */
  function ampliable(img, titulo) {
    ui.render.classList.add('zoomable');
    var b = el('button', 'expand', '⤢');
    b.type = 'button';
    b.title = T.viewFull;
    b.setAttribute('aria-label', T.viewFull);
    var abrir = function () { abrirVisor(img.src, titulo, b); };
    img.addEventListener('click', abrir);
    b.addEventListener('click', abrir);
    ui.render.appendChild(b);
  }

  var VISOR_CSS = [
    ':host{all:initial;font-family:inherit}',
    '.v{position:fixed;inset:0;z-index:2147483000;display:flex;flex-direction:column;background:rgba(17,14,11,.97);color:#fff;',
    'font-family:inherit;-webkit-font-smoothing:antialiased;animation:pl-in .18s ease-out}',
    '@keyframes pl-in{from{opacity:0}to{opacity:1}}',
    '.bar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 16px}',
    '.bar span{min-width:0;font-size:14px;opacity:.85;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '.tools{display:flex;gap:6px;flex:none}',
    '.tools button{height:40px;min-width:40px;padding:0 10px;border-radius:999px;border:1px solid rgba(255,255,255,.25);background:rgba(255,255,255,.08);',
    'color:#fff;font:inherit;font-size:18px;line-height:1;cursor:pointer}',
    '.tools button:hover{background:rgba(255,255,255,.18)}.tools button:focus-visible{outline:2px solid #fff;outline-offset:2px}',
    '.tools .pct{font-size:13px;min-width:62px}',
    '.stage{position:relative;flex:1;overflow:hidden;touch-action:none;cursor:zoom-in}',
    '.stage.z{cursor:grab}.stage.drag{cursor:grabbing}',
    '.stage img{position:absolute;left:50%;top:50%;max-width:none;max-height:none;transform-origin:center center;user-select:none;-webkit-user-drag:none;will-change:transform}',
    '.stage.anim img{transition:transform .2s ease-out}',
    '.hint{text-align:center;font-size:12px;opacity:.6;padding:6px 16px 14px}',
    '@media (max-width:600px){.bar span{display:none}.bar{justify-content:flex-end}.hint{display:none}}',
  ].join('');

  /**
   * La imagen completa sobre toda la página, con zoom (rueda, pellizco, doble
   * clic o los botones) y arrastre para moverse. Vive fuera del bloque, en su
   * propio Shadow DOM sobre el body: así no la recorta ni la mueve el sitio.
   */
  function abrirVisor(src, titulo, origen) {
    var cap = document.createElement('div');
    cap.setAttribute('data-stackline-planner-visor', '');
    cap.setAttribute('data-lenis-prevent', '');
    document.body.appendChild(cap);
    var sr = cap.attachShadow ? cap.attachShadow({ mode: 'open' }) : cap;
    var estilo = el('style'); estilo.textContent = VISOR_CSS; sr.appendChild(estilo);
    var v = el('div', 'v');
    v.setAttribute('role', 'dialog');
    v.setAttribute('aria-modal', 'true');
    v.setAttribute('aria-label', titulo || T.viewFull);
    var bar = el('div', 'bar');
    bar.appendChild(el('span', null, titulo || ''));
    var tools = el('div', 'tools');
    var botones = [];
    function boton2(texto, etiqueta, accion, cls) {
      var x = el('button', cls || null, texto);
      x.type = 'button';
      x.title = etiqueta;
      x.setAttribute('aria-label', etiqueta);
      x.addEventListener('click', accion);
      tools.appendChild(x);
      botones.push(x);
      return x;
    }
    var stage = el('div', 'stage');
    var img = el('img');
    img.alt = titulo || '';
    img.draggable = false;
    stage.appendChild(img);

    var s = 1, tx = 0, ty = 0, fw = 0, fh = 0, MAX = 6;
    boton2('−', T.zoomOut, function () { suave(); zoomEn(1 / 1.5, 0, 0); });
    var pct = boton2('100%', T.fit, function () { suave(); ajustar(); }, 'pct');
    boton2('+', T.zoomIn, function () { suave(); zoomEn(1.5, 0, 0); });
    var cerrarBtn = boton2('✕', T.close, cerrar);
    bar.appendChild(tools);
    v.appendChild(bar);
    v.appendChild(stage);
    v.appendChild(el('div', 'hint', T.zoomHint));
    sr.appendChild(v);
    var overflowPrevio = document.documentElement.style.overflow;
    document.documentElement.style.overflow = 'hidden';

    /** La imagen entera dentro de la pantalla (puede crecer); el zoom va encima. */
    function medir() {
      var W = Math.max(stage.clientWidth - 24, 50), H = Math.max(stage.clientHeight - 24, 50);
      var nw = img.naturalWidth || W, nh = img.naturalHeight || H;
      var k = Math.min(W / nw, H / nh);
      fw = nw * k; fh = nh * k;
      img.style.width = fw + 'px';
      img.style.height = fh + 'px';
      img.style.marginLeft = (-fw / 2) + 'px';
      img.style.marginTop = (-fh / 2) + 'px';
      aplicar();
    }
    /** No se puede arrastrar la imagen fuera de la vista. */
    function aplicar() {
      var mx = Math.max(0, (fw * s - stage.clientWidth) / 2), my = Math.max(0, (fh * s - stage.clientHeight) / 2);
      tx = Math.min(mx, Math.max(-mx, tx));
      ty = Math.min(my, Math.max(-my, ty));
      img.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + s + ')';
      pct.textContent = Math.round(s * 100) + '%';
      stage.classList.toggle('z', s > 1.01);
    }
    /** Acerca o aleja manteniendo fijo el punto (px, py), medido desde el centro. */
    function zoomEn(factor, px, py) {
      var n = Math.min(MAX, Math.max(1, s * factor));
      tx = px - (px - tx) * n / s;
      ty = py - (py - ty) * n / s;
      s = n;
      aplicar();
    }
    function ajustar() { s = 1; tx = 0; ty = 0; aplicar(); }
    function suave() { stage.classList.add('anim'); setTimeout(function () { stage.classList.remove('anim'); }, 220); }
    function punto(x, y) { var r = stage.getBoundingClientRect(); return [x - r.left - r.width / 2, y - r.top - r.height / 2]; }
    function alternar(x, y) { suave(); if (s > 1.01) ajustar(); else { var c = punto(x, y); zoomEn(2.5, c[0], c[1]); } }

    stage.addEventListener('wheel', function (e) {
      e.preventDefault();
      var c = punto(e.clientX, e.clientY);
      zoomEn(Math.exp(-Math.max(-60, Math.min(60, e.deltaY)) * 0.004), c[0], c[1]);
    }, { passive: false });

    var punteros = {}, arrastre = null, pellizco = null, ultimoToque = 0, tocando = false;
    function distancia(a, b) { return Math.hypot(a[0] - b[0], a[1] - b[1]) || 1; }
    stage.addEventListener('pointerdown', function (e) {
      tocando = e.pointerType !== 'mouse';
      try { stage.setPointerCapture(e.pointerId); } catch (err) { /* sin captura */ }
      punteros[e.pointerId] = [e.clientX, e.clientY];
      var ids = Object.keys(punteros);
      if (ids.length === 2) {
        pellizco = { d: distancia(punteros[ids[0]], punteros[ids[1]]), s: s };
        arrastre = null;
      } else if (ids.length === 1) {
        arrastre = { x: e.clientX, y: e.clientY, tx: tx, ty: ty, movio: false };
        if (tocando) {
          // Doble toque: alterna entre ajustada y 2.5×.
          var ahora = Date.now();
          if (ahora - ultimoToque < 300) { alternar(e.clientX, e.clientY); arrastre = null; ultimoToque = 0; } else ultimoToque = ahora;
        }
      }
    });
    stage.addEventListener('pointermove', function (e) {
      if (!punteros[e.pointerId]) return;
      punteros[e.pointerId] = [e.clientX, e.clientY];
      var ids = Object.keys(punteros);
      if (pellizco && ids.length === 2) {
        var a = punteros[ids[0]], b = punteros[ids[1]];
        var c = punto((a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
        zoomEn(pellizco.s * distancia(a, b) / pellizco.d / s, c[0], c[1]);
      } else if (arrastre) {
        var dx = e.clientX - arrastre.x, dy = e.clientY - arrastre.y;
        if (Math.abs(dx) + Math.abs(dy) > 3) arrastre.movio = true;
        if (s > 1.01) {
          tx = arrastre.tx + dx;
          ty = arrastre.ty + dy;
          stage.classList.add('drag');
          aplicar();
        }
      }
    });
    function soltar(e) {
      delete punteros[e.pointerId];
      if (Object.keys(punteros).length < 2) pellizco = null;
      if (!Object.keys(punteros).length) stage.classList.remove('drag');
    }
    stage.addEventListener('pointerup', function (e) {
      // Un clic suelto fuera de la imagen (sin zoom ni arrastre) cierra el visor.
      if (arrastre && !arrastre.movio && e.target === stage && s <= 1.01 && !tocando) { soltar(e); cerrar(); return; }
      soltar(e);
    });
    stage.addEventListener('pointercancel', soltar);
    stage.addEventListener('dblclick', function (e) { if (!tocando) alternar(e.clientX, e.clientY); });

    function tecla(e) {
      if (e.key === 'Escape') { e.preventDefault(); cerrar(); }
      else if (e.key === '+' || e.key === '=') { suave(); zoomEn(1.5, 0, 0); }
      else if (e.key === '-' || e.key === '_') { suave(); zoomEn(1 / 1.5, 0, 0); }
      else if (e.key === '0') { suave(); ajustar(); }
      else if (e.key === 'Tab') {
        // El foco no sale del visor mientras está abierto.
        var i = botones.indexOf(sr.activeElement);
        e.preventDefault();
        botones[(i + (e.shiftKey ? botones.length - 1 : 1) + botones.length) % botones.length].focus();
      }
    }
    document.addEventListener('keydown', tecla, true);
    window.addEventListener('resize', medir);
    img.addEventListener('load', medir);
    img.src = src;
    if (img.complete) medir();
    cerrarBtn.focus();

    function cerrar() {
      document.removeEventListener('keydown', tecla, true);
      window.removeEventListener('resize', medir);
      document.documentElement.style.overflow = overflowPrevio;
      if (cap.parentNode) cap.parentNode.removeChild(cap);
      // Vuelve el foco al botón que lo abrió, si sigue en pantalla.
      if (origen && origen.isConnected) try { origen.focus(); } catch (err) { /* nada */ }
    }
  }

  /** Mientras haya un render en camino, se consulta el estado (hasta 3 minutos). */
  function seguirRenders() {
    var enCamino = estado && estado.options.some(function (o) { return o.render && o.render.status === 'pending'; });
    if (!enCamino) { consultaHasta = 0; return; }
    if (!consultaHasta) consultaHasta = Date.now() + ESPERA_MAX_MS;
    if (consulta || Date.now() > consultaHasta) return;
    consulta = setTimeout(function () {
      consulta = null;
      if (ocupado) { seguirRenders(); return; }
      pedir('GET', ruta()).then(function (res) {
        if (res.ok && !ocupado) { estado = res.data; pintar(); } else seguirRenders();
      });
    }, CADA_MS);
  }

  /**
   * Un enlace normal (no window.open tras una llamada: los navegadores lo
   * bloquean). El servidor lo arma con la opción en pantalla y su último
   * render, así que se renueva con cada cambio.
   */
  function pintarContacto() {
    ui.btns.textContent = '';
    if (!estado.contact || !estado.contact.whatsappUrl) return;
    var a = el('a', 'primary', T.contact + ' →');
    a.href = estado.contact.whatsappUrl;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    a.title = T.contactHint;
    a.addEventListener('click', avisarContacto);
    ui.btns.appendChild(a);
  }

  /** Deja constancia del contacto sin frenar la salida a WhatsApp (sendBeacon sobrevive al cambio de página). */
  function avisarContacto() {
    var o = activa();
    var cuerpo = JSON.stringify({ option: o ? o.letter : null });
    var u = BASE + '/' + KEY + ruta('/contact');
    try {
      if (navigator.sendBeacon && navigator.sendBeacon(u, new Blob([cuerpo], { type: 'text/plain;charset=UTF-8' }))) return;
    } catch (e) { /* sin beacon: se intenta con fetch */ }
    try {
      fetch(u, { method: 'POST', headers: { 'Content-Type': 'text/plain;charset=UTF-8' }, body: cuerpo, keepalive: true }).catch(function () {});
    } catch (e) { /* no detiene el contacto */ }
  }

  function pintarPresupuesto() {
    if (root.activeElement === ui.budget) return;
    ui.budget.value = estado.budget != null ? Number(estado.budget).toLocaleString('en-US', { maximumFractionDigits: 2 }) : '';
  }

  function pintarTipos() {
    ui.types.textContent = '';
    var fijos = el('div', 'chips');
    (config.projectTypes || []).forEach(function (t) {
      var b = boton('chip' + (estado.projectType === t.code ? ' on' : ''), t.label, function () { elegirTipo(t.code); });
      b.setAttribute('aria-pressed', estado.projectType === t.code ? 'true' : 'false');
      b.disabled = ocupado;
      fijos.appendChild(b);
    });
    if (fijos.childNodes.length) ui.types.appendChild(fijos);
    var cats = config.categories || [];
    var actual = categoriaActual();
    if (!actual) return;
    // Con una sola categoría el chip sobra: va directo la lista.
    if (cats.length > 1 || fijos.childNodes.length) {
      var fila = el('div', 'chips');
      cats.forEach(function (c) {
        var b = boton('chip' + (c.id === actual.id ? ' on' : ''), c.name, function () {
          local.cat = c.id;
          guardar();
          pintar();
        });
        b.setAttribute('aria-pressed', c.id === actual.id ? 'true' : 'false');
        fila.appendChild(b);
      });
      ui.types.appendChild(fila);
    }
    var lista = el('div', 'prods');
    var ps = productos[actual.id];
    if (!ps && fallos[actual.id]) {
      // Sin reintento automático: cada repintado volvería a pedirla.
      lista.appendChild(el('p', 'hint', fallos[actual.id]));
      lista.appendChild(boton('rbtn', T.retry, function () { fallos[actual.id] = null; cargarProductos(actual.id); pintar(); }));
    } else if (!ps) {
      cargarProductos(actual.id);
      lista.appendChild(el('p', 'hint', T.loadingProducts));
    } else if (!ps.length) {
      lista.appendChild(el('p', 'hint', T.noProducts));
    }
    (ps || []).forEach(function (p) {
      var on = estado.projectType === p.type;
      var b = boton('prod' + (on ? ' on' : ''), null, function () { elegirProducto(p, actual.id); });
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
      b.disabled = ocupado;
      if (p.summary) b.title = p.summary;
      var foto = el(p.image ? 'img' : 'span', p.image ? null : 'ph');
      if (p.image) { foto.src = url(p.image); foto.alt = ''; foto.loading = 'lazy'; foto.decoding = 'async'; }
      b.appendChild(foto);
      var tx = el('span', 'pt');
      tx.appendChild(el('b', null, p.name));
      tx.appendChild(el('small', null, precioLista(p)));
      b.appendChild(tx);
      lista.appendChild(b);
    });
    ui.types.appendChild(lista);
  }

  /** La categoría que se está viendo: la que eligió (si sigue en el planificador) o la primera. */
  function categoriaActual() {
    var cats = config.categories || [];
    for (var i = 0; i < cats.length; i++) if (cats[i].id === local.cat) return cats[i];
    return cats[0] || null;
  }

  function cargarProductos(id) {
    if (cargando[id]) return;
    cargando[id] = true;
    pedir('GET', '/categories/' + id + '/products').then(function (res) {
      cargando[id] = false;
      if (res.ok && Array.isArray(res.data)) productos[id] = res.data;
      else fallos[id] = motivo(res);
      pintar();
    });
  }

  /** «Q 950 / m²», o «Precio por confirmar» si la empresa aún no lo tiene. */
  function precioLista(p) {
    if (p.price == null) return T.priceTbd;
    return p.unit ? f(T.perUnit, { p: dinero(p.price), u: p.unit }) : dinero(p.price);
  }

  function elegirProducto(p, cat) {
    local.cat = cat;
    // Para pintar las medidas tras recargar la página sin volver a pedir la lista.
    local.prod = { type: p.type, name: p.name, unit: p.unit, image: p.image };
    guardar();
    if (estado.projectType === p.type) { pintar(); return; }
    elegirTipo(p.type);
  }

  /** El producto de la lista que tiene elegido (código p{id}), o null si es un tipo fijo o nada. */
  function productoElegido() {
    var t = estado && estado.projectType;
    if (!t || !/^p\d+$/.test(t)) return null;
    for (var id in productos) {
      if (!productos.hasOwnProperty(id)) continue;
      for (var i = 0; i < productos[id].length; i++) if (productos[id][i].type === t) return productos[id][i];
    }
    return local.prod && local.prod.type === t ? local.prod : null;
  }

  function porArea(u) { return /^(m²|m2|mt2|mts2|metros? cuadrados?)$/i.test(String(u || '').trim()); }

  /**
   * Las medidas del producto elegido: largo × fondo si se vende por m² (el
   * servidor calcula el área), o cuántos si se vende por unidad. Con una
   * opción suya en pantalla, «Probar estas medidas» crea otra desde ella.
   */
  function pintarMedidas() {
    if (!ui.medir) return;
    ui.medir.textContent = '';
    ui.medir.className = '';
    var p = productoElegido();
    if (!p) return;
    var o = activa();
    var desde = o && o.projectType === p.type ? o.letter : null;
    var previas = local.medidas && local.medidas.type === p.type ? local.medidas : {};
    var area = porArea(p.unit);
    var form = el('form', 'medir');
    form.appendChild(el('span', 'lbl', area ? T.measures : T.qty));
    function campo(texto, valor) {
      // Sin texto (la cantidad): el título de la sección ya lo dice; queda como nombre accesible.
      var l = el('label', null, area ? texto : null);
      var i = el('input');
      if (!area) i.setAttribute('aria-label', texto);
      i.type = 'number'; i.inputMode = 'decimal'; i.min = '0'; i.step = 'any';
      if (valor != null) i.value = valor;
      l.appendChild(i);
      form.appendChild(l);
      return i;
    }
    var largo = area ? campo(T.length, previas.ancho) : null;
    var fondo = area ? campo(T.depth, previas.profundidad) : null;
    var cuantos = area ? null : campo(T.qty, previas.quantity != null ? previas.quantity : 1);
    var nota = area ? el('span', 'area') : null;
    function n(i) { var v = Number(String(i.value).replace(',', '.')); return v > 0 ? v : null; }
    function calcular() {
      if (!nota) return;
      var a = n(largo), b = n(fondo);
      nota.textContent = a && b ? f(T.areaIs, { a: Math.round(a * b * 100) / 100 }) : '';
    }
    if (area) {
      largo.addEventListener('input', calcular);
      fondo.addEventListener('input', calcular);
      form.appendChild(nota);
      calcular();
    }
    var b = el('button', null, desde ? (area ? T.rebuild : T.rebuildQty) : T.build);
    b.type = 'submit';
    b.disabled = ocupado;
    form.appendChild(b);
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      if (ocupado) return;
      var cuerpo = { projectType: p.type };
      if (desde) cuerpo.from = desde;
      if (area) {
        var a = n(largo), c = n(fondo);
        if (!a || !c) { aviso = T.badNumber; pintar(); return; }
        cuerpo.params = { ancho: a, profundidad: c };
        local.medidas = { type: p.type, ancho: a, profundidad: c };
      } else {
        var q = n(cuantos);
        if (!q) { aviso = T.badNumber; pintar(); return; }
        cuerpo.quantity = q;
        local.medidas = { type: p.type, quantity: q };
      }
      aviso = null;
      guardar();
      crearOpcion(cuerpo);
    });
    ui.medir.appendChild(form);
  }

  function pintarDetalles() {
    ui.details.textContent = '';
    var o = activa();
    (o ? o.details : []).forEach(function (d) {
      var fila = el('div', 'det' + (d.changed ? ' new' : ''));
      fila.appendChild(el('b', null, d.label));
      fila.appendChild(el('span', null, d.value));
      fila.appendChild(el('span'));
      ui.details.appendChild(fila);
    });
    (estado.notes || []).forEach(function (n) {
      var fila = el('div', 'det');
      fila.appendChild(el('b', null, n.key.replace(/_/g, ' ')));
      fila.appendChild(el('span', null, n.value));
      var x = boton('x', '×', function () { quitarNota(n.key); });
      x.setAttribute('aria-label', T.remove + ' ' + n.key);
      fila.appendChild(x);
      ui.details.appendChild(fila);
    });
  }

  function pintarConversacion() {
    ui.log.textContent = '';
    var mensajes = local.messages || [];
    if (!mensajes.length) ui.log.appendChild(el('div', 'msg bot', config.chat ? T.hello : T.noChat));
    mensajes.forEach(function (m, i) {
      var burbuja = el('div', 'msg ' + (m.from === 'me' ? 'me' : m.from === 'err' ? 'err' : 'bot'), m.text);
      // Las respuestas rápidas van dentro del último mensaje del asistente.
      if (i === mensajes.length - 1 && m.from === 'bot' && (local.replies || []).length) {
        var fila = el('div', 'replies');
        local.replies.forEach(function (r) {
          var b = boton('reply', r.label, function () { responder(r); });
          b.disabled = ocupado;
          fila.appendChild(b);
        });
        burbuja.appendChild(fila);
      }
      ui.log.appendChild(burbuja);
    });
    if (escribiendo) {
      // Tino con los tres puntos mientras responde.
      var esc = el('div', 'typing');
      esc.setAttribute('role', 'status');
      esc.setAttribute('aria-label', T.typing);
      var mini = el('span', 'tino');
      mini.innerHTML = tino(ui.color, 'esc');
      esc.appendChild(mini);
      var puntos = el('span', 'dots');
      for (var k = 0; k < 3; k++) puntos.appendChild(el('i'));
      esc.appendChild(puntos);
      ui.log.appendChild(esc);
    }
    if (aviso) ui.log.appendChild(el('div', 'msg err', aviso));
    if (ui.input) {
      // Eligió un producto de la lista y aún no hay propuesta: que diga sus medidas (o cuántos) en el chat.
      var elegido = productoElegido();
      ui.input.placeholder = estado.options.length ? T.placeholder
        : elegido ? (porArea(elegido.unit) ? T.placeholderMeasures : T.placeholderQty) : T.placeholderFirst;
      ui.input.disabled = ocupado;
      ui.send.disabled = ocupado;
    }
    ui.log.scrollTop = ui.log.scrollHeight;
  }

  /** Cambios de un toque, ya valorados: ajustan la propuesta (por dentro es una versión nueva). */
  function pintarCambios() {
    ui.alts.textContent = '';
    var o = activa();
    if (!o) {
      if (!config.chat && estado.projectType && !productoElegido()) ui.alts.appendChild(boton('first', T.firstOption, function () { crearOpcion({}); }));
      return;
    }
    (o.alternatives || []).forEach(function (a) {
      var b = boton('chip', a.label, function () { crearOpcion({ from: o.letter, choices: a.change }); });
      b.title = dinero(a.total);
      b.disabled = ocupado;
      ui.alts.appendChild(b);
    });
  }

  /** Una sola propuesta: la activa. Las versiones anteriores quedan en la sesión, no en pantalla. */
  function pintarOpciones() {
    ui.optionsTitle.textContent = estado.budget != null ? f(T.optionsFor, { m: dinero(estado.budget) }) : T.options;
    ui.cards.textContent = '';
    ui.tip.textContent = '';
    ui.tip.className = '';
    var o = activa();
    if (!o) { ui.cards.appendChild(el('div', 'empty', T.optionsEmpty)); return; }
    ui.cards.appendChild(tarjeta(o));
    if (o.budget && o.budget.status === 'over' && o.suggestion && estado.budget != null) {
      ui.tip.className = 'tip';
      var s = o.suggestion;
      ui.tip.appendChild(el('b', null, f(s.fits ? T.backTo : T.backToNo, { m: dinero(estado.budget) }) + ' '));
      ui.tip.appendChild(document.createTextNode(f(T.suggestion, { c: s.label, t: dinero(s.total) })));
      ui.tip.appendChild(el('br'));
      ui.tip.appendChild(boton(null, T.tryIt, function () { crearOpcion({ from: o.letter, choices: s.change }); }));
    }
  }

  function tarjeta(o) {
    var c = el('div', 'card on');
    c.setAttribute('aria-label', T.optionLabel + ': ' + o.title);
    var top = el('div', 'top');
    top.appendChild(el('h3', null, o.title));
    top.appendChild(el('span', 'price', o.total != null ? dinero(o.total) : T.priceReview));
    c.appendChild(top);
    if (o.summary) c.appendChild(el('p', 'sum', o.summary));
    var b = o.budget || {};
    if (estado.budget != null && b.status) {
      // La barra va hasta 125 % del presupuesto; la marca negra es el monto.
      var tope = Number(estado.budget) * 1.25;
      var barra = el('div', 'bar');
      var relleno = el('i');
      relleno.style.width = (o.total != null ? Math.min(Number(o.total) / tope, 1) * 100 : 0) + '%';
      relleno.style.background = b.status === 'over' ? 'var(--bad)' : b.status === 'tight' ? '#d99a1e' : b.status === 'fits' ? 'var(--ok)' : 'var(--grey)';
      barra.appendChild(relleno);
      var marca = el('em');
      marca.style.left = '80%';
      barra.appendChild(marca);
      c.appendChild(barra);
    }
    var pie = el('div', 'cfoot');
    var txt = textoEstado(o);
    pie.appendChild(txt ? el('span', 'pill ' + b.status, txt) : el('span'));
    var rb = botonRender(o);
    if (rb) pie.appendChild(rb);
    c.appendChild(pie);
    return c;
  }

  /** «Ver render» / «Preparando…» / «Render listo» / «Reintentar», según su estado y lo que quede del tope. */
  function botonRender(o) {
    if (!ui.render) return null;
    var r = o.render, u = estado.renders || {};
    var quedan = (u.max || 0) - (u.used || 0);
    var texto, accion = null, hecho = false;
    if (r && r.status === 'ready') { texto = T.renderReady; hecho = true; accion = function () { activar(o.letter); }; }
    else if (r && r.status === 'pending') { texto = T.renderPending; hecho = true; }
    else if (quedan <= 0) { texto = T.noRendersLeft; hecho = true; }
    else { texto = r && r.status === 'failed' ? T.retry : T.seeRender; accion = function () { pedirRender(o.letter); }; }
    var b = el('button', 'rbtn' + (hecho ? ' done' : ''), texto);
    b.type = 'button';
    b.disabled = ocupado || !accion;
    b.addEventListener('click', function (e) {
      e.stopPropagation();
      if (ocupado || !accion) return;
      // «Render listo» de la opción activa ya se ve: no hace falta activarla.
      if (r && r.status === 'ready' && o.letter === (activa() || {}).letter) return;
      accion();
    });
    return b;
  }

  // ── acciones ────────────────────────────────────────────────────────────
  function ocupar(si) {
    ocupado = si;
    pintar();
  }

  function aplicar(res, siOk) {
    if (res.ok) {
      aviso = null;
      siOk(res.data);
    } else if (res.status === 404 && res.data && res.data.code === 'SESSION') {
      // El diseño venció: se empieza uno nuevo sin perder lo que escribió.
      local.session = null;
      guardar();
      return sesionNueva();
    } else {
      aviso = motivo(res);
    }
    ocupar(false);
  }

  function guardarPresupuesto() {
    var n = Number(ui.budget.value.replace(/[^\d]/g, ''));
    if (!n || (estado.budget != null && Number(estado.budget) === n)) { pintarPresupuesto(); return; }
    ocupar(true);
    pedir('POST', ruta('/update'), { budget: n }).then(function (res) { aplicar(res, function (d) { estado = d; }); });
  }

  function elegirTipo(code) {
    if (estado.projectType === code) return;
    ocupar(true);
    pedir('POST', ruta('/update'), { projectType: code }).then(function (res) { aplicar(res, function (d) { estado = d; }); });
  }

  function quitarNota(clave) {
    var cambio = {};
    cambio[clave] = '';
    ocupar(true);
    pedir('POST', ruta('/update'), { notes: cambio }).then(function (res) { aplicar(res, function (d) { estado = d; }); });
  }

  function activar(letra) {
    ocupar(true);
    pedir('POST', ruta('/options/' + encodeURIComponent(letra) + '/activate')).then(function (res) {
      aplicar(res, function (d) { estado = d; });
    });
  }

  /** Una opción nueva sin pasar por el asistente (chips, sugerencia): se cuenta en el chat. */
  function crearOpcion(cuerpo) {
    ocupar(true);
    local.replies = [];
    pedir('POST', ruta('/options'), cuerpo).then(function (res) {
      aplicar(res, function (d) {
        estado = d.state;
        var o = activa();
        if (o) {
          local.messages.push({ from: 'bot', text: o.total != null
            ? f(T.created, { t: dinero(o.total), s: textoEstado(o) })
            : T.createdReview });
          guardar();
        }
      });
    });
  }

  function responder(r) {
    if (r.change && activa()) crearOpcion({ from: activa().letter, choices: r.change });
    else if (r.change) crearOpcion({ choices: r.change });
    else enviar(r.text || r.label);
  }

  function enviar(texto) {
    if (!config.chat) return;
    var valor = (texto != null ? texto : ui.input.value).trim();
    if (!valor || ocupado) return;
    if (texto == null) { ui.input.value = ''; ui.input.style.height = 'auto'; }
    local.messages.push({ from: 'me', text: valor });
    local.replies = [];
    guardar();
    escribiendo = true;
    ocupar(true);
    pedir('POST', ruta('/messages'), { id: rid(22), text: valor }).then(function (res) {
      escribiendo = false;
      aplicar(res, function (d) {
        (d.messages || []).forEach(function (m) { local.messages.push({ from: 'bot', text: m }); });
        local.replies = d.replies || [];
        estado = d.state;
        guardar();
      });
      if (ui.input && !ocupado) ui.input.focus();
    });
  }

  function pedirRender(letra) {
    ocupar(true);
    pedir('POST', ruta('/options/' + encodeURIComponent(letra) + '/render')).then(function (res) {
      consultaHasta = 0;
      aplicar(res, function (d) { estado = d; });
    });
  }

  function subirFoto(archivo) {
    if (!/^image\/(jpeg|png|webp)$/.test(archivo.type)) { aviso = T.photoType; pintar(); return; }
    ocupar(true);
    comprimir(archivo).then(function (data) {
      return pedir('POST', ruta('/photo'), { data: data, name: archivo.name });
    }, function () {
      return { ok: false, status: 0, data: { message: T.photoErr } };
    }).then(function (res) { aplicar(res, function (d) { estado = d; }); });
  }

  function quitarFoto() {
    ocupar(true);
    pedir('POST', ruta('/update'), { removePhoto: true }).then(function (res) { aplicar(res, function (d) { estado = d; }); });
  }

  /** La foto se reduce en el navegador (lado mayor 1600 px, JPG): sube rápido y pesa poco. */
  function comprimir(archivo) {
    return new Promise(function (ok, mal) {
      var u = URL.createObjectURL(archivo);
      var img = new Image();
      img.onload = function () {
        URL.revokeObjectURL(u);
        var k = Math.min(1, MAX_LADO / Math.max(img.width, img.height));
        var c = document.createElement('canvas');
        c.width = Math.round(img.width * k);
        c.height = Math.round(img.height * k);
        var ctx = c.getContext('2d');
        ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
        ctx.drawImage(img, 0, 0, c.width, c.height);
        var data = c.toDataURL('image/jpeg', CALIDAD);
        ok(data.slice(data.indexOf(',') + 1));
      };
      img.onerror = function () { URL.revokeObjectURL(u); mal(new Error('foto')); };
      img.src = u;
    });
  }

  function reiniciar() {
    if (!window.confirm(T.resetAsk)) return;
    local = { session: null, messages: [], replies: [] };
    guardar();
    aviso = null;
    sesionNueva();
  }

  // ── sesión ──────────────────────────────────────────────────────────────
  function sesionNueva() {
    ocupar(true);
    return pedir('POST', '/sessions', {}).then(function (res) {
      if (res.ok) {
        local.session = res.data.session;
        estado = res.data;
        guardar();
      } else {
        aviso = motivo(res);
      }
      ocupado = false;
      if (estado) pintar(); else pintarError();
    });
  }

  function pintarError() {
    ui.log.textContent = '';
    ui.log.appendChild(el('div', 'msg err', aviso || T.error));
  }

  function cargarSesion() {
    if (!local.session) return sesionNueva();
    return pedir('GET', ruta()).then(function (res) {
      if (res.ok) {
        estado = res.data;
        pintar();
      } else if (res.status === 404) {
        local = { session: null, messages: [], replies: [] };
        sesionNueva();
      } else {
        aviso = motivo(res);
        pintarError();
      }
    });
  }

  // ── arranque ────────────────────────────────────────────────────────────
  function iniciar() {
    pedir('GET', '/config').then(function (res) {
      if (!res.ok) {
        console.warn('[Stackline] El planificador no se muestra: ' + ((res.data && res.data.message) || 'error'));
        return;
      }
      config = res.data;
      montar();
      cargarSesion();
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', iniciar);
  else iniciar();
})();
