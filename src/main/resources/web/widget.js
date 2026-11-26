/*!
 * Widget de chat de Stackline (canal web de Tino).
 *
 * Uso, en la página de la empresa:
 *   <script src="https://AGENTES/v1/web/widget.js" data-key="wk_..." async></script>
 *
 * Opcionales: data-position="left" (por defecto a la derecha), data-offset="110"
 * (distancia en px desde abajo, p. ej. para quedar sobre otro boton flotante;
 * por defecto 20), data-open="true"
 * (abre el chat al cargar), data-lang="es|en" (por defecto, el del navegador),
 * data-accent="#RRGGBB" (ojos e insignia de Tino; por defecto, un tono claro
 * del color del canal), data-badge="https://…/logo.svg" (imagen cuadrada que
 * reemplaza el símbolo en el pecho de Tino, p. ej. el logo de la empresa).
 *
 * API para el sitio: cuando el chat está listo se dispara 'stackline-chat:ready'
 * en window y existen window.StacklineChat.open() / .close() (p. ej. para un
 * botón "Habla con nosotros" de la propia página).
 *
 * Sin dependencias y dentro de un Shadow DOM: no hereda ni pisa los estilos
 * del sitio. La conversación se guarda en este navegador (localStorage).
 */
(function () {
  'use strict';

  var script = document.currentScript;
  if (!script) {
    var todos = document.querySelectorAll('script[data-key][src*="widget.js"]');
    script = todos[todos.length - 1];
  }
  if (!script || window.__stacklineChat) return;
  window.__stacklineChat = true;

  var KEY = script.getAttribute('data-key') || '';
  var BASE = script.src.replace(/\/widget\.js(\?.*)?$/, '');
  var LEFT = script.getAttribute('data-position') === 'left';
  // Distancia desde abajo: para convivir con otro boton flotante del sitio
  // (p. ej. el de WhatsApp). Entre 0 y 400 px; sin valor, 20 (12 en movil).
  var OFFSET = (function (v) { var n = parseInt(v, 10); return isNaN(n) ? null : Math.max(0, Math.min(400, n)); })(script.getAttribute('data-offset'));
  var OPEN = script.getAttribute('data-open') === 'true';
  var LANG = (script.getAttribute('data-lang') || navigator.language || 'es').slice(0, 2) === 'en' ? 'en' : 'es';
  var ACCENT = /^#[0-9a-f]{6}$/i.test(script.getAttribute('data-accent') || '') ? script.getAttribute('data-accent') : null;
  // Solo https (o http en localhost) para no aceptar javascript: ni data: arbitrarios.
  var BADGE = (function (u) {
    if (!u) return null;
    try {
      var url = new URL(u, location.href);
      return url.protocol === 'https:' || (url.protocol === 'http:' && /^(localhost|127\.0\.0\.1)$/.test(url.hostname)) ? url.href : null;
    } catch (e) { return null; }
  })(script.getAttribute('data-badge') || '');
  var STORE = 'stackline-chat:' + KEY;
  var MAX_HIST = 60;

  var T = {
    es: {
      open: 'Abrir chat', close: 'Cerrar chat', reset: 'Nueva conversación', send: 'Enviar',
      placeholder: 'Escribe tu mensaje…', online: 'Responde en segundos', typing: 'Escribiendo…',
      error: 'No pude enviar tu mensaje. Intenta de nuevo.', offline: 'Sin conexión. Revisa tu internet.',
      resetAsk: '¿Empezar una conversación nueva? Se borrará lo de esta pantalla.', powered: 'Con tecnología de Corvex',
      attach: 'Adjuntar foto', remove: 'Quitar foto', photo: 'Foto', photoErr: 'No pude subir la foto. Intenta de nuevo.',
      photoType: 'Solo fotos JPG, PNG o WEBP.', photoMax: 'Máximo {n} fotos por mensaje.', preparing: 'Preparando imagen…',
      download: 'Descargar', gone: 'Este archivo ya no está disponible.', view: 'Ver imagen', doc: 'Documento',
      cart: 'Tu cotización', cartOpen: 'Ver tu cotización', cartClose: 'Ocultar cotización', cartDraft: 'En preparación',
      items: '{n} producto(s)', less: 'Menos', more: 'Más', removeItem: 'Quitar', toConfirm: 'Por confirmar', each: 'c/u',
      subtotal: 'Subtotal', subtotalEst: 'Subtotal estimado', tax: 'IVA', total: 'Total', noTax: 'Sin impuestos. El total sale en la cotización.',
      pendingNote: 'Algunos precios los confirma un asesor.', update: 'Actualizar cantidades', discard: 'Descartar',
      ask: 'Pedir mi cotización', askMsg: 'Ya está todo, envíame la cotización por favor.',
      removeMsg: 'Quita «{x}» de la cotización, por favor.', qtyMsg: 'Cambia la cantidad, por favor: {x}.', qtyPart: '«{x}» a {n}',
    },
    en: {
      open: 'Open chat', close: 'Close chat', reset: 'New conversation', send: 'Send',
      placeholder: 'Type your message…', online: 'Replies in seconds', typing: 'Typing…',
      error: 'Your message could not be sent. Please try again.', offline: 'You are offline. Check your connection.',
      resetAsk: 'Start a new conversation? This screen will be cleared.', powered: 'Powered by Corvex',
      attach: 'Attach photo', remove: 'Remove photo', photo: 'Photo', photoErr: 'The photo could not be uploaded. Please try again.',
      photoType: 'Only JPG, PNG or WEBP photos.', photoMax: 'Up to {n} photos per message.', preparing: 'Preparing image…',
      download: 'Download', gone: 'This file is no longer available.', view: 'View image', doc: 'Document',
      cart: 'Your quote', cartOpen: 'View your quote', cartClose: 'Hide quote', cartDraft: 'In progress',
      items: '{n} item(s)', less: 'Less', more: 'More', removeItem: 'Remove', toConfirm: 'To be confirmed', each: 'each',
      subtotal: 'Subtotal', subtotalEst: 'Estimated subtotal', tax: 'Tax', total: 'Total', noTax: 'Before tax. The total comes with the quote.',
      pendingNote: 'Some prices will be confirmed by an advisor.', update: 'Update quantities', discard: 'Discard',
      ask: 'Get my quote', askMsg: 'That is everything, please send me the quote.',
      removeMsg: 'Please remove “{x}” from the quote.', qtyMsg: 'Please change the quantity: {x}.', qtyPart: '“{x}” to {n}',
    },
  }[LANG];

  if (!/^wk_[A-Za-z0-9]{20,40}$/.test(KEY)) {
    console.warn('[Stackline] data-key inválida en el script del chat.');
    return;
  }

  // ── estado ──────────────────────────────────────────────────────────────
  function rid(n) {
    var abc = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    var out = '';
    var bytes = new Uint8Array(n);
    if (window.crypto && window.crypto.getRandomValues) window.crypto.getRandomValues(bytes);
    else for (var i = 0; i < n; i++) bytes[i] = Math.floor(Math.random() * 256);
    for (var j = 0; j < n; j++) out += abc[bytes[j] % abc.length];
    return out;
  }
  function leer() {
    try { var s = JSON.parse(localStorage.getItem(STORE) || 'null'); if (s && s.session) return s; } catch (e) { /* sin storage */ }
    return { session: rid(24), messages: [], choices: [] };
  }
  function guardar() {
    try {
      estado.messages = estado.messages.slice(-MAX_HIST);
      localStorage.setItem(STORE, JSON.stringify(estado));
    } catch (e) { /* modo privado: la conversación vive solo en esta pestaña */ }
  }
  var estado = leer();
  var config = null;
  var enviando = false;
  /** Fotos elegidas que aun no se envian: { blob, url (vista previa local) }. */
  var pendientes = [];
  var consultando = false;
  /** Cantidades que el cliente movio en el carrito y aun no manda: indice → cantidad. */
  var ediciones = {};
  var ESTRECHO = '(max-width:720px)';
  var MAX_LADO = 1600, CALIDAD = 0.82, ESPERA_MS = 3000, ESPERA_MAX_MS = 180000;

  // ── estilos ─────────────────────────────────────────────────────────────
  function contraste(hex) {
    var m = /^#([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return '#ffffff';
    var n = parseInt(m[1], 16);
    var r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    var l = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return l > 0.62 ? '#1d1b1c' : '#ffffff';
  }

  /** Mezcla dos colores #RRGGBB: t=0 devuelve `a`, t=1 devuelve `b`. */
  function mezclar(a, b, t) {
    var x = parseInt(a.slice(1), 16), y = parseInt(b.slice(1), 16);
    var c = [16, 8, 0].map(function (s) {
      var u = (x >> s) & 255, v = (y >> s) & 255;
      return Math.round(u + (v - u) * t);
    });
    return '#' + c.map(function (n) { return (n < 16 ? '0' : '') + n.toString(16); }).join('');
  }

  var CSS = [
    ':host{all:initial}',
    '*{box-sizing:border-box;font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}',
    '.wrap{position:fixed;bottom:' + (OFFSET == null ? 20 : OFFSET) + 'px;' + (LEFT ? 'left' : 'right') + ':20px;z-index:2147483000;display:flex;flex-direction:column;align-items:' + (LEFT ? 'flex-start' : 'flex-end') + ';gap:12px}',
    '.launcher{width:60px;height:60px;border-radius:50%;border:0;cursor:pointer;background:var(--c);color:var(--on);display:grid;place-items:center;box-shadow:0 6px 20px rgba(0,0,0,.22);transition:transform .15s ease}',
    '.launcher:hover{transform:scale(1.06)}',
    '.launcher:focus-visible,.icon-btn:focus-visible,.send:focus-visible,.choice:focus-visible{outline:3px solid var(--c);outline-offset:2px}',
    '.launcher>svg{width:28px;height:28px}',
    // Tino asomado por la ventanilla del boton (mismo recorte que el widget del ERP):
    // la cabeza sale del circulo y el cuerpo se corta en la mitad inferior.
    '.launcher{position:relative;overflow:visible}',
    ".tino-win{position:absolute;inset:0;pointer-events:none;clip-path:path('M-30 -40H90V40H58.28A30 30 0 0 1 1.72 40H-30Z')}",
    '.tino-fig{position:absolute;left:50%;bottom:-26px;transform:translateX(-50%);line-height:0}',
    '.tino-fig svg{display:block;overflow:visible;width:82px;height:91px}',
    // Con logo en el pecho, Tino asoma un poco mas para que la insignia se vea.
    '.tino-fig.con-insignia{bottom:-17px}',
    '.t-fb{transform-box:fill-box;transform-origin:center}',
    '.t-blink{animation:t-blink 4s ease-in-out infinite}',
    '.t-bob{animation:t-bob 3.2s ease-in-out infinite}',
    '.t-head{transform-box:view-box;transform-origin:32px 42px;transition:transform .3s ease}',
    '.t-arm{transform-box:view-box;transition:transform .3s ease}',
    '.t-arm-l{transform-origin:18px 47px;transform:rotate(8deg)}',
    '.t-arm-r{transform-origin:46px 47px;transform:rotate(-8deg)}',
    '.t-wave{transform-box:view-box;transform-origin:46px 47px}',
    '.t-happy{display:none}',
    '.launcher:hover .t-happy,.launcher:focus-visible .t-happy{display:inline;animation:t-hop .9s ease-in-out infinite}',
    '.launcher:hover .t-eye,.launcher:focus-visible .t-eye{display:none}',
    '.launcher:hover .t-head,.launcher:focus-visible .t-head{transform:rotate(-6deg)}',
    '.launcher:hover .t-arm-r,.launcher:focus-visible .t-arm-r{transform:rotate(-145deg)}',
    '.launcher:hover .t-wave,.launcher:focus-visible .t-wave{animation:t-wave .7s ease-in-out infinite alternate}',
    '@keyframes t-blink{0%,92%,100%{transform:scaleY(1)}95%{transform:scaleY(.1)}}',
    '@keyframes t-bob{0%,100%{transform:translateY(0)}50%{transform:translateY(1.2px)}}',
    '@keyframes t-hop{0%,100%{transform:translateY(0)}50%{transform:translateY(-1px)}}',
    '@keyframes t-wave{from{transform:rotate(-14deg)}to{transform:rotate(14deg)}}',
    '@media (prefers-reduced-motion:reduce){.t-blink,.t-bob,.t-hop,.t-wave,.launcher:hover .t-wave,.launcher:hover .t-happy{animation:none}.t-head,.t-arm{transition:none}}',
    '.panel{width:370px;height:560px;max-height:calc(100vh - 110px);background:#fff;border-radius:20px;box-shadow:0 12px 40px rgba(0,0,0,.25);display:none;flex-direction:column;overflow:hidden;color:#1d1b1c}',
    '.panel.open{display:flex;animation:in .18s ease-out}',
    '@keyframes in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:none}}',
    '.head{background:var(--c);color:var(--on);padding:14px 12px 14px 16px;display:flex;align-items:center;gap:10px}',
    '.avatar{width:38px;height:38px;border-radius:50%;background:rgba(255,255,255,.2);display:grid;place-items:center;flex:none}',
    '.avatar svg{width:22px;height:22px}',
    '.titles{flex:1;min-width:0}',
    '.title{font-size:16px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '.sub{font-size:12px;opacity:.85;display:flex;align-items:center;gap:6px}',
    '.dot{width:8px;height:8px;border-radius:50%;background:#4ade80}',
    '.icon-btn{width:36px;height:36px;border-radius:50%;border:0;background:transparent;color:inherit;cursor:pointer;display:grid;place-items:center}',
    '.icon-btn:hover{background:rgba(255,255,255,.16)}',
    '.icon-btn svg{width:20px;height:20px}',
    '.log{flex:1;overflow-y:auto;overscroll-behavior:contain;padding:16px 14px;display:flex;flex-direction:column;gap:8px;background:#f7f4f5;scrollbar-width:thin}',
    '.msg{max-width:82%;padding:9px 13px;border-radius:16px;font-size:14px;line-height:1.45;white-space:pre-wrap;word-wrap:break-word;overflow-wrap:anywhere}',
    '.bot{align-self:flex-start;background:#fff;border:1px solid #ece6e8;border-bottom-left-radius:4px}',
    '.me{align-self:flex-end;background:var(--c);color:var(--on);border-bottom-right-radius:4px}',
    '.msg a{color:inherit;text-decoration:underline}',
    '.sys{align-self:center;font-size:12px;color:#8a1c1c;background:#fde8e8;padding:6px 10px;border-radius:10px;text-align:center;max-width:90%}',
    '.choices{display:flex;flex-wrap:wrap;gap:6px;align-self:flex-start;max-width:90%}',
    '.choice{border:1.5px solid var(--c);color:var(--c);background:#fff;border-radius:999px;padding:7px 13px;font-size:13px;cursor:pointer}',
    '.choice:hover{background:var(--c);color:var(--on)}',
    '.typing{align-self:flex-start;background:#fff;border:1px solid #ece6e8;border-radius:16px;border-bottom-left-radius:4px;padding:12px 14px;display:flex;gap:4px}',
    '.typing i{width:7px;height:7px;border-radius:50%;background:#b9aeb2;animation:b 1.2s infinite}',
    '.typing i:nth-child(2){animation-delay:.15s}.typing i:nth-child(3){animation-delay:.3s}',
    '@keyframes b{0%,60%,100%{transform:none;opacity:.5}30%{transform:translateY(-4px);opacity:1}}',
    '.compose{display:flex;align-items:flex-end;gap:8px;padding:10px 10px 6px 14px;border-top:1px solid #ece6e8;background:#fff}',
    'textarea{flex:1;resize:none;border:0;outline:0;font-size:14px;line-height:1.4;max-height:120px;padding:8px 0;color:#1d1b1c;background:transparent}',
    '.send{width:40px;height:40px;border-radius:50%;border:0;background:var(--c);color:var(--on);cursor:pointer;display:grid;place-items:center;flex:none}',
    '.send:disabled{opacity:.4;cursor:default}',
    '.send svg{width:18px;height:18px}',
    '.clip{width:36px;height:40px;border:0;background:transparent;color:#8b8186;cursor:pointer;display:grid;place-items:center;flex:none;margin-left:-6px}',
    '.clip:hover{color:var(--c)}.clip:disabled{opacity:.4;cursor:default}.clip svg{width:22px;height:22px}',
    '.clip:focus-visible{outline:3px solid var(--c);outline-offset:2px;border-radius:8px}',
    '.tray{display:none;gap:8px;padding:10px 14px 0;background:#fff;border-top:1px solid #ece6e8;overflow-x:auto}',
    '.tray.on{display:flex}',
    '.thumb{position:relative;width:58px;height:58px;flex:none;border-radius:10px;overflow:hidden;background:#eee}',
    '.thumb img{width:100%;height:100%;object-fit:cover;display:block}',
    '.thumb button{position:absolute;top:3px;right:3px;width:20px;height:20px;border-radius:50%;border:0;background:rgba(0,0,0,.6);color:#fff;cursor:pointer;display:grid;place-items:center;padding:0}',
    '.thumb button svg{width:12px;height:12px}',
    '.tray+.compose{border-top:0}',
    '.pics{display:grid;gap:3px;border-radius:14px;overflow:hidden;max-width:240px}',
    '.pics.n2,.pics.n3,.pics.n4,.pics.n5{grid-template-columns:1fr 1fr}',
    '.pics img{width:100%;height:100%;min-height:90px;max-height:260px;object-fit:cover;display:block;cursor:zoom-in;background:#e9e4e6}',
    '.pics.n1 img{max-height:300px}',
    '.media{padding:4px;max-width:82%}',
    '.media .cap{padding:6px 8px 3px;font-size:14px;line-height:1.45;white-space:pre-wrap;overflow-wrap:anywhere}',
    '.gone{font-size:12px;color:#8b8186;padding:10px;font-style:italic}',
    '.doc{width:250px;max-width:82%;padding:4px}',
    '.doc-prev{display:flex;align-items:center;justify-content:center;height:92px;border-radius:10px 10px 0 0;background:linear-gradient(135deg,#fbeaea,#f3f0f1);color:#c62828;text-decoration:none}',
    '.doc-prev svg{width:44px;height:52px}',
    '.doc-row{display:flex;align-items:center;gap:10px;padding:9px 8px;background:#f6f2f3;border-radius:0 0 10px 10px}',
    '.doc-ico{width:30px;height:36px;flex:none;color:#c62828}.doc-ico svg{width:100%;height:100%}',
    '.doc-txt{flex:1;min-width:0}',
    '.doc-name{font-size:13px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:#1d1b1c}',
    '.doc-meta{font-size:11px;color:#8b8186}',
    '.doc-dl{width:34px;height:34px;border-radius:50%;flex:none;display:grid;place-items:center;border:1.5px solid #d9d1d4;color:#5b5357;text-decoration:none}',
    '.doc-dl:hover{border-color:var(--c);color:var(--c)}.doc-dl svg{width:18px;height:18px}',
    '.doc .cap{padding:7px 6px 3px;font-size:13px;line-height:1.4;white-space:pre-wrap;overflow-wrap:anywhere}',
    '.doc .btn-dl{display:block;margin:6px 2px 2px;text-align:center;padding:8px;border-radius:10px;background:var(--c);color:var(--on);font-size:13px;font-weight:600;text-decoration:none}',
    '.wait{align-self:flex-start;display:flex;align-items:center;gap:8px;font-size:12px;color:#6b6266;background:#fff;border:1px solid #ece6e8;border-radius:12px;padding:8px 12px}',
    '.spin{width:14px;height:14px;border-radius:50%;border:2px solid #d9d1d4;border-top-color:var(--c);animation:spin .8s linear infinite}',
    '@keyframes spin{to{transform:rotate(360deg)}}',
    '.lightbox{position:absolute;inset:0;background:rgba(10,8,9,.92);display:flex;align-items:center;justify-content:center;z-index:5;cursor:zoom-out}',
    '.lightbox img{max-width:94%;max-height:86%;border-radius:6px}',
    '.lightbox a{position:absolute;bottom:14px;color:#fff;font-size:13px;text-decoration:underline}',
    '.panel{position:relative}',
    '.panel.drop .log{outline:3px dashed var(--c);outline-offset:-8px}',
    '.stage{display:flex;align-items:stretch;gap:12px;flex-direction:' + (LEFT ? 'row-reverse' : 'row') + '}',
    '.cart{display:none;width:290px;height:560px;max-height:calc(100vh - 110px);background:#fff;border-radius:20px;box-shadow:0 12px 40px rgba(0,0,0,.25);flex-direction:column;overflow:hidden;color:#1d1b1c}',
    '.wrap.abierto .cart.on:not(.min){display:flex;animation:in .18s ease-out}',
    '.c-head{padding:14px 12px 12px 16px;border-bottom:1px solid #ece6e8;display:flex;align-items:center;gap:8px}',
    '.c-head .titles{color:#1d1b1c}.c-head .sub{color:#8b8186;opacity:1}',
    '.c-head .icon-btn{color:#5b5357}.c-head .icon-btn:hover{background:#f3eef0}',
    '.c-list{flex:1;overflow-y:auto;overscroll-behavior:contain;padding:6px 0;scrollbar-width:thin}',
    '.c-item{padding:10px 16px;border-bottom:1px solid #f3eef0}',
    '.c-name{font-size:14px;font-weight:600;line-height:1.3;overflow-wrap:anywhere}',
    '.c-detail{font-size:12px;color:#8b8186;margin-top:2px;overflow-wrap:anywhere}',
    '.c-row{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:8px}',
    '.qty{display:flex;align-items:center;border:1px solid #e3dcdf;border-radius:999px;overflow:hidden}',
    '.qty button{width:28px;height:28px;border:0;background:#fff;cursor:pointer;font-size:16px;line-height:1;color:#5b5357}',
    '.qty button:hover:not(:disabled){background:#f3eef0}.qty button:disabled{opacity:.35;cursor:default}',
    '.qty span{min-width:34px;text-align:center;font-size:13px;font-weight:600}',
    '.qty.edit{border-color:var(--c)}',
    '.c-price{font-size:13px;font-weight:600;text-align:right}',
    '.c-price.pend{font-weight:500;color:#8a6d1c;font-size:12px}',
    '.c-unit{font-size:11px;color:#8b8186;font-weight:400}',
    '.c-del{border:0;background:none;color:#8b8186;font-size:12px;cursor:pointer;padding:4px 0 0;text-decoration:underline}',
    '.c-del:hover:not(:disabled){color:#8a1c1c}.c-del:disabled{opacity:.4;cursor:default}',
    '.c-edit{display:flex;gap:6px;padding:10px 16px;border-top:1px solid #ece6e8;background:#fffaf2}',
    '.c-edit button{flex:1;border-radius:10px;padding:8px;font-size:13px;cursor:pointer;border:1.5px solid var(--c)}',
    '.c-edit .ok{background:var(--c);color:var(--on)}.c-edit .no{background:#fff;color:var(--c)}',
    '.c-foot{padding:12px 16px 14px;border-top:1px solid #ece6e8;background:#faf8f9}',
    '.c-sum{display:flex;justify-content:space-between;font-size:13px;color:#5b5357;padding:2px 0}',
    '.c-sum.tot{font-size:16px;font-weight:700;color:#1d1b1c;padding-top:6px}',
    '.c-note{font-size:11px;color:#8b8186;margin-top:6px;line-height:1.35}',
    '.c-ask{width:100%;margin-top:10px;border:0;border-radius:12px;padding:11px;background:var(--c);color:var(--on);font-size:14px;font-weight:600;cursor:pointer}',
    '.c-ask:disabled,.c-edit button:disabled{opacity:.45;cursor:default}',
    '.cart-btn{display:none;position:relative}.cart-btn.on{display:grid}',
    '.badge{position:absolute;top:2px;right:0;min-width:17px;height:17px;padding:0 4px;border-radius:9px;background:#fff;color:var(--c);font-size:11px;font-weight:700;display:grid;place-items:center;line-height:1}',
    '@keyframes pop{50%{transform:scale(1.35)}}.badge.pop{animation:pop .4s ease}',
    '@media ' + ESTRECHO + '{.stage{position:relative}.wrap.abierto .cart.on:not(.min){display:none}.wrap.abierto .cart.on.show{display:flex;position:absolute;inset:0;width:auto;height:auto;z-index:4}}',
    '.foot{font-size:11px;color:#8b8186;text-align:center;padding:2px 0 8px;background:#fff}',
    '.foot a{color:inherit;text-decoration:none}.foot a:hover{color:#3b3438;text-decoration:underline}',
    '@media (max-width:480px){.wrap.abierto .cart.on.show{position:fixed;border-radius:0;max-height:none}.wrap{bottom:' + (OFFSET == null ? 12 : OFFSET) + 'px;' + (LEFT ? 'left' : 'right') + ':12px}.panel.open{position:fixed;inset:0;width:auto;height:auto;max-height:none;border-radius:0}}',
  ].join('\n');

  var ICON = {
    chat: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2Zm3 5v2h10V9H7Zm0 4v2h7v-2H7Z"/></svg>',
    close: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M18.3 5.7 12 12l6.3 6.3-1.4 1.4L10.6 13.4 4.3 19.7 2.9 18.3 9.2 12 2.9 5.7 4.3 4.3l6.3 6.3 6.3-6.3z"/></svg>',
    down: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7.4 8.6 12 13.2l4.6-4.6L18 10l-6 6-6-6z"/></svg>',
    reset: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 5V2L7 6l5 4V7a5 5 0 1 1-5 5H5a7 7 0 1 0 7-7z"/></svg>',
    send: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M3 20.5 21 12 3 3.5 3 10l12 2-12 2z"/></svg>',
    clip: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m21.4 11.1-8.5 8.5a5.5 5.5 0 0 1-7.8-7.8l8.5-8.5a3.7 3.7 0 0 1 5.2 5.2l-8.5 8.5a1.8 1.8 0 0 1-2.6-2.6l7.8-7.8"/></svg>',
    pdf: '<svg viewBox="0 0 40 48" aria-hidden="true"><path d="M4 2h22l10 10v32a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Z" fill="#fff" stroke="currentColor" stroke-width="2"/><path d="M26 2v10h10" fill="none" stroke="currentColor" stroke-width="2"/><rect x="2" y="26" width="30" height="13" rx="2" fill="currentColor"/><text x="17" y="36" text-anchor="middle" font-size="9" font-weight="700" fill="#fff" font-family="Arial,sans-serif">PDF</text></svg>',
    cart: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4Zm10 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4ZM5.2 4H2V2h4.8l.9 2H21l-3.4 8.1a2 2 0 0 1-1.8 1.2H8.4l-1 1.7h12.1v2H4l2.7-4.6L5.2 4Z"/></svg>',
    dl: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M11 4h2v9.2l3.3-3.3 1.4 1.4L12 17l-5.7-5.7 1.4-1.4 3.3 3.3V4ZM5 19h14v2H5z"/></svg>',
  };

  /**
   * Tino de cuerpo completo (el mismo personaje del widget del ERP, en SVG
   * plano). Colores: contorno y circulo salen del color del canal; ojos e
   * insignia, de data-accent.
   */
  function tino(color) {
    var ojo = ACCENT || mezclar(color, '#ffffff', 0.7);
    var marca = ACCENT ? mezclar(ACCENT, '#000000', 0.25) : color;
    var contorno = mezclar(color, '#000000', 0.35);
    var b = 'fill="#ffffff" stroke="' + contorno + '" stroke-width="1.6"';
    return '<span class="tino-win" aria-hidden="true"><span class="tino-fig' + (BADGE ? ' con-insignia' : '') + '">' +
      '<svg viewBox="-6 -4 76 84" focusable="false">' +
      '<defs><filter id="stk-halo" x="-20%" y="-20%" width="140%" height="140%">' +
      '<feMorphology in="SourceAlpha" operator="dilate" radius="1.6" result="borde"/>' +
      '<feFlood flood-color="#F6F2EA"/><feComposite in2="borde" operator="in" result="halo"/>' +
      '<feMerge><feMergeNode in="halo"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>' +
      '<g filter="url(#stk-halo)"><g class="t-bob">' +
      '<rect x="24" y="61" width="6.5" height="13" rx="3.2" ' + b + '/>' +
      '<rect x="33.5" y="61" width="6.5" height="13" rx="3.2" ' + b + '/>' +
      '<g class="t-arm t-arm-l"><rect x="15" y="44" width="6" height="16" rx="3" ' + b + '/></g>' +
      '<g class="t-arm t-arm-r"><g class="t-wave"><rect x="43" y="44" width="6" height="16" rx="3" ' + b + '/></g></g>' +
      '<rect x="20.5" y="41" width="23" height="22" rx="8.5" ' + b + '/>' +
      (BADGE
        ? '<image href="' + BADGE.replace(/"/g, '%22') + '" x="25.5" y="44" width="13" height="13" preserveAspectRatio="xMidYMid meet"/>'
        : '<path d="M12 4a8 8 0 1 1-8 8V4z" transform="translate(26.5 46.5) scale(.46)" fill="' + marca + '"/>') +
      '<g class="t-head">' +
      '<path d="M32 6a20 20 0 1 1-20 20V6z" ' + b + ' stroke-linejoin="round"/>' +
      '<rect x="18" y="15" width="28" height="20" rx="7" fill="#17151C"/>' +
      '<rect x="21" y="18" width="5" height="2.2" rx="1.1" fill="#3A3540"/>' +
      '<circle class="t-eye t-fb t-blink" cx="33" cy="25" r="3.6" fill="' + ojo + '"/>' +
      '<path class="t-happy" d="M28 27q5-6 10 0" fill="none" stroke="' + ojo + '" stroke-width="2.6" stroke-linecap="round"/>' +
      '</g></g></g></svg></span></span>';
  }

  // ── DOM ─────────────────────────────────────────────────────────────────
  var host, root, wrap, panel, log, input, sendBtn, launcher, TINO, clipBtn, fileIn, tray, cart, cartBtn;

  function el(tag, cls, html) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (html) n.innerHTML = html;
    return n;
  }

  /** Texto con enlaces clicables, sin innerHTML: lo que escribe el modelo nunca se interpreta como HTML. */
  function conEnlaces(nodo, texto) {
    var re = /(https?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)])/g;
    var ultimo = 0, m;
    while ((m = re.exec(texto))) {
      if (m.index > ultimo) nodo.appendChild(document.createTextNode(texto.slice(ultimo, m.index)));
      var a = document.createElement('a');
      a.href = m[1]; a.target = '_blank'; a.rel = 'noopener noreferrer'; a.textContent = m[1];
      nodo.appendChild(a);
      ultimo = m.index + m[1].length;
    }
    if (ultimo < texto.length) nodo.appendChild(document.createTextNode(texto.slice(ultimo)));
  }

  function montar() {
    host = document.createElement('div');
    host.setAttribute('data-stackline-chat', '');
    // Paginas con scroll suave (Lenis, Locomotive…) capturan la rueda en toda
    // la ventana y mueven la pagina: el chat no podria desplazarse.
    host.setAttribute('data-lenis-prevent', '');
    host.setAttribute('data-scroll-ignore', '');
    ['wheel', 'touchmove'].forEach(function (tipo) {
      host.addEventListener(tipo, function (e) { e.stopPropagation(); }, { passive: true });
    });
    root = host.attachShadow ? host.attachShadow({ mode: 'open' }) : host;
    var color = /^#[0-9a-f]{6}$/i.test(config.color || '') ? config.color : '#731C37';
    TINO = tino(color);

    var style = el('style'); style.textContent = CSS; root.appendChild(style);
    wrap = el('div', 'wrap');
    wrap.style.setProperty('--c', color);
    wrap.style.setProperty('--on', contraste(color));

    panel = el('div', 'panel');
    panel.setAttribute('role', 'dialog');
    panel.setAttribute('aria-label', config.title);

    var head = el('div', 'head');
    head.appendChild(el('div', 'avatar', ICON.chat));
    var titles = el('div', 'titles');
    var title = el('div', 'title'); title.textContent = config.title; titles.appendChild(title);
    var sub = el('div', 'sub'); sub.appendChild(el('span', 'dot')); sub.appendChild(document.createTextNode(T.online));
    titles.appendChild(sub);
    head.appendChild(titles);
    var resetBtn = el('button', 'icon-btn', ICON.reset);
    resetBtn.type = 'button'; resetBtn.title = T.reset; resetBtn.setAttribute('aria-label', T.reset);
    resetBtn.onclick = reiniciar;
    var closeBtn = el('button', 'icon-btn', ICON.down);
    closeBtn.type = 'button'; closeBtn.title = T.close; closeBtn.setAttribute('aria-label', T.close);
    closeBtn.onclick = function () { abrir(false); };
    cartBtn = el('button', 'icon-btn cart-btn', ICON.cart);
    cartBtn.type = 'button'; cartBtn.title = T.cartOpen; cartBtn.setAttribute('aria-label', T.cartOpen);
    cartBtn.onclick = alternarCarrito;
    head.appendChild(cartBtn); head.appendChild(resetBtn); head.appendChild(closeBtn);

    log = el('div', 'log');
    log.setAttribute('role', 'log');
    log.setAttribute('aria-live', 'polite');

    var compose = el('form', 'compose');
    input = el('textarea');
    input.rows = 1;
    input.placeholder = T.placeholder;
    input.setAttribute('aria-label', T.placeholder);
    input.maxLength = config.maxLength || 1000;
    sendBtn = el('button', 'send', ICON.send);
    sendBtn.type = 'submit'; sendBtn.setAttribute('aria-label', T.send);
    if (config.maxImages > 0) {
      clipBtn = el('button', 'clip', ICON.clip);
      clipBtn.type = 'button'; clipBtn.title = T.attach; clipBtn.setAttribute('aria-label', T.attach);
      fileIn = el('input');
      fileIn.type = 'file'; fileIn.accept = 'image/jpeg,image/png,image/webp'; fileIn.multiple = true; fileIn.hidden = true;
      clipBtn.onclick = function () { fileIn.click(); };
      fileIn.onchange = function () { agregarFotos(fileIn.files); fileIn.value = ''; };
      compose.appendChild(clipBtn); compose.appendChild(fileIn);
      input.addEventListener('paste', function (e) {
        var fs = [].slice.call((e.clipboardData && e.clipboardData.files) || []);
        if (fs.length) { e.preventDefault(); agregarFotos(fs); }
      });
    }
    compose.appendChild(input); compose.appendChild(sendBtn);
    tray = el('div', 'tray');
    compose.onsubmit = function (e) { e.preventDefault(); enviar(input.value); };
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); enviar(input.value); }
    });
    input.addEventListener('input', function () {
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 120) + 'px';
      actualizarBoton();
    });

    // La firma: enlace a Corvex (nueva pestaña) si el servicio trae la URL; si no, texto.
    var foot = el('div', 'foot');
    if (/^https:\/\//.test(config.poweredByUrl || '')) {
      var firma = el('a');
      firma.textContent = T.powered;
      firma.href = config.poweredByUrl;
      firma.target = '_blank';
      firma.rel = 'noopener';
      foot.appendChild(firma);
    } else foot.textContent = T.powered;

    panel.appendChild(head); panel.appendChild(log); panel.appendChild(tray); panel.appendChild(compose); panel.appendChild(foot);
    if (config.maxImages > 0) {
      panel.addEventListener('dragover', function (e) { e.preventDefault(); panel.classList.add('drop'); });
      panel.addEventListener('dragleave', function (e) { if (e.target === panel || !panel.contains(e.relatedTarget)) panel.classList.remove('drop'); });
      panel.addEventListener('drop', function (e) {
        e.preventDefault(); panel.classList.remove('drop');
        if (e.dataTransfer && e.dataTransfer.files) agregarFotos(e.dataTransfer.files);
      });
    }

    launcher = el('button', 'launcher', TINO);
    launcher.type = 'button';
    launcher.setAttribute('aria-label', T.open);
    launcher.onclick = function () { abrir(!panel.classList.contains('open')); };

    cart = el('aside', 'cart');
    cart.setAttribute('aria-label', T.cart);
    var stage = el('div', 'stage');
    stage.appendChild(cart); stage.appendChild(panel);
    wrap.appendChild(stage); wrap.appendChild(launcher);
    root.appendChild(wrap);
    document.body.appendChild(host);

    pintar();
    if (OPEN || estado.open) abrir(true);
    if (estado.waitUntil && estado.waitUntil > Date.now()) consultar();
    pintarCarrito();
    if (estado.messages.length) refrescarCarrito();

    window.StacklineChat = {
      open: function () { abrir(true); },
      close: function () { abrir(false); }
    };
    try { window.dispatchEvent(new Event('stackline-chat:ready')); } catch (e) { /* navegadores muy viejos */ }
  }

  function abrir(si) {
    panel.classList.toggle('open', si);
    launcher.innerHTML = si ? ICON.close : TINO;
    launcher.setAttribute('aria-label', si ? T.close : T.open);
    estado.open = si; guardar();
    wrap.classList.toggle('abierto', si);
    if (!si) cart.classList.remove('show');
    if (si) { bajar(); setTimeout(function () { input.focus(); }, 50); }
  }

  function actualizarBoton() {
    sendBtn.disabled = enviando || (!input.value.trim() && !pendientes.length);
    if (clipBtn) clipBtn.disabled = enviando || pendientes.length >= config.maxImages;
    if (cart) [].forEach.call(cart.querySelectorAll('button[data-bloq]'), function (b) { b.disabled = enviando; });
  }

  function bajar() { log.scrollTop = log.scrollHeight; }

  function pintar() {
    log.innerHTML = '';
    if (config.greeting && !estado.messages.length) {
      var g = el('div', 'msg bot'); conEnlaces(g, config.greeting); log.appendChild(g);
    }
    estado.messages.forEach(function (m) { log.appendChild(burbuja(m)); });
    if (!enviando && estado.choices && estado.choices.length) {
      var box = el('div', 'choices');
      estado.choices.forEach(function (c) {
        var b = el('button', 'choice'); b.type = 'button'; b.textContent = c.label;
        b.onclick = function () { enviar(c.label, c.id); };
        box.appendChild(b);
      });
      log.appendChild(box);
    }
    if (enviando) {
      var t = el('div', 'typing'); t.setAttribute('aria-label', T.typing);
      t.appendChild(el('i')); t.appendChild(el('i')); t.appendChild(el('i'));
      log.appendChild(t);
    }
    if (consultando) {
      var w = el('div', 'wait'); w.appendChild(el('span', 'spin')); w.appendChild(document.createTextNode(T.preparing));
      log.appendChild(w);
    }
    if (estado.error) { var s = el('div', 'sys'); s.textContent = estado.error; log.appendChild(s); }
    actualizarBoton();
    bajar();
  }

  function reiniciar() {
    if (enviando || (estado.messages.length && !window.confirm(T.resetAsk))) return;
    estado = { session: rid(24), messages: [], choices: [], open: true };
    limpiarPendientes();
    ediciones = {}; pintarCarrito();
    guardar(); pintar(); input.focus();
  }

  // ── carrito ─────────────────────────────────────────────────────────────
  function dinero(v) {
    if (v == null || v === '') return '';
    var n = Number(v);
    try {
      return new Intl.NumberFormat(LANG === 'en' ? 'en-US' : 'es-GT', { style: 'currency', currency: config.currency || 'GTQ', currencyDisplay: 'narrowSymbol' }).format(n);
    } catch (e) { return (config.currency || '') + ' ' + n.toFixed(2); }
  }

  function cantidadTexto(q) {
    var n = Number(q);
    return isNaN(n) ? String(q) : String(Math.round(n * 1000) / 1000);
  }

  /** Nombre con medidas: el agente distingue dos pergolas de distinto tamaño. */
  function etiqueta(it) { return it.name + (it.detail ? ' ' + it.detail : ''); }

  function alternarCarrito() {
    if (window.matchMedia && window.matchMedia(ESTRECHO).matches) {
      cart.classList.toggle('show');
    } else {
      estado.cartMin = !cart.classList.contains('min') ? true : false;
      cart.classList.toggle('min', estado.cartMin);
      guardar();
    }
    var visible = cart.classList.contains('show') || !cart.classList.contains('min');
    cartBtn.setAttribute('aria-expanded', String(visible));
  }

  function recibirCarrito(c) {
    var antes = estado.cart && estado.cart.items ? estado.cart.items.length : 0;
    estado.cart = c && c.items && c.items.length ? c : null;
    ediciones = {};
    var ahora = estado.cart ? estado.cart.items.length : 0;
    // El primer producto despliega el panel; despues respeta si el cliente lo oculto.
    if (!antes && ahora) estado.cartMin = false;
    pintarCarrito(ahora !== antes);
  }

  function refrescarCarrito() {
    fetch(BASE + '/' + KEY + '/cart?session=' + encodeURIComponent(estado.session))
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) { if (d && !enviando) { recibirCarrito(d.cart); guardar(); } })
      .catch(function () { /* se queda el ultimo conocido */ });
  }

  function pintarCarrito(cambio) {
    if (!cart) return;
    var c = estado.cart;
    var hay = !!(c && c.items && c.items.length);
    cart.classList.toggle('on', hay);
    cart.classList.toggle('min', !!estado.cartMin);
    cartBtn.classList.toggle('on', hay);
    cartBtn.innerHTML = ICON.cart;
    if (!hay) { cart.innerHTML = ''; cart.classList.remove('show'); return; }
    var badge = el('span', 'badge' + (cambio ? ' pop' : '')); badge.textContent = c.items.length;
    cartBtn.appendChild(badge);

    cart.innerHTML = '';
    var head = el('div', 'c-head');
    var titles = el('div', 'titles');
    var tt = el('div', 'title'); tt.textContent = T.cart; titles.appendChild(tt);
    var sb = el('div', 'sub');
    sb.textContent = (c.number || T.cartDraft) + ' · ' + T.items.replace('{n}', c.items.length);
    titles.appendChild(sb); head.appendChild(titles);
    var cerrar = el('button', 'icon-btn', ICON.close);
    cerrar.type = 'button'; cerrar.title = T.cartClose; cerrar.setAttribute('aria-label', T.cartClose);
    cerrar.onclick = alternarCarrito;
    head.appendChild(cerrar);
    cart.appendChild(head);

    var lista = el('div', 'c-list');
    c.items.forEach(function (it, i) {
      var fila = el('div', 'c-item');
      var nm = el('div', 'c-name'); nm.textContent = it.name; fila.appendChild(nm);
      if (it.detail) { var dt = el('div', 'c-detail'); dt.textContent = it.detail; fila.appendChild(dt); }
      var row = el('div', 'c-row');
      var base = Number(it.quantity);
      var q = ediciones.hasOwnProperty(i) ? ediciones[i] : base;
      var entera = !isNaN(base) && Math.floor(base) === base;
      var paso = el('div', 'qty' + (ediciones.hasOwnProperty(i) ? ' edit' : ''));
      var menos = el('button'); menos.type = 'button'; menos.textContent = '−'; menos.setAttribute('aria-label', T.less);
      var mas = el('button'); mas.type = 'button'; mas.textContent = '+'; mas.setAttribute('aria-label', T.more);
      var num = el('span'); num.textContent = cantidadTexto(q) + (it.unit && it.unit !== 'u' ? ' ' + it.unit : '');
      // Cantidades con decimales (m², m³…) se cambian escribiendo en el chat.
      menos.disabled = !entera || q <= 1 || enviando; mas.disabled = !entera || enviando;
      menos.onclick = function () { mover(i, base, q - 1); };
      mas.onclick = function () { mover(i, base, q + 1); };
      paso.appendChild(menos); paso.appendChild(num); paso.appendChild(mas);
      row.appendChild(paso);
      var precio = el('div', 'c-price');
      if (c.showPrices && it.pendingPrice) { precio.className = 'c-price pend'; precio.textContent = T.toConfirm; }
      else if (it.lineTotal != null && !ediciones.hasOwnProperty(i)) {
        precio.textContent = dinero(it.lineTotal);
        if (it.unitPrice != null && base !== 1) { var u = el('div', 'c-unit'); u.textContent = dinero(it.unitPrice) + ' ' + T.each; precio.appendChild(u); }
      }
      row.appendChild(precio);
      fila.appendChild(row);
      var del = el('button', 'c-del'); del.type = 'button'; del.textContent = T.removeItem; del.setAttribute('data-bloq', '');
      del.disabled = enviando;
      del.onclick = function () { ediciones = {}; enviar(T.removeMsg.replace('{x}', etiqueta(it))); };
      fila.appendChild(del);
      lista.appendChild(fila);
    });
    cart.appendChild(lista);

    var cambios = Object.keys(ediciones);
    if (cambios.length) {
      var ed = el('div', 'c-edit');
      var no = el('button', 'no'); no.type = 'button'; no.textContent = T.discard; no.setAttribute('data-bloq', '');
      no.onclick = function () { ediciones = {}; pintarCarrito(); };
      var ok = el('button', 'ok'); ok.type = 'button'; ok.textContent = T.update; ok.setAttribute('data-bloq', '');
      ok.onclick = function () {
        var partes = cambios.map(function (k) {
          return T.qtyPart.replace('{x}', etiqueta(c.items[k])).replace('{n}', cantidadTexto(ediciones[k]));
        });
        ediciones = {};
        enviar(T.qtyMsg.replace('{x}', partes.join(', ')));
      };
      no.disabled = ok.disabled = enviando;
      ed.appendChild(no); ed.appendChild(ok);
      cart.appendChild(ed);
    }

    var pie = el('div', 'c-foot');
    function suma(etq, v, cls) {
      var f = el('div', 'c-sum' + (cls ? ' ' + cls : ''));
      var a = el('span'); a.textContent = etq; var b = el('span'); b.textContent = dinero(v);
      f.appendChild(a); f.appendChild(b); pie.appendChild(f);
    }
    if (c.showPrices) {
      if (c.subtotal != null) suma(c.estimated ? T.subtotalEst : T.subtotal, c.subtotal);
      if (c.tax != null) suma(T.tax, c.tax);
      if (c.total != null) suma(T.total, c.total, 'tot');
      else if (c.subtotal == null && c.pendingPrices) {
        // Sin ningun monto todavia: el asesor pone el precio.
        var pf = el('div', 'c-sum tot'); var pa = el('span'); pa.textContent = T.total;
        var pb = el('span', 'c-price pend'); pb.textContent = T.toConfirm;
        pf.appendChild(pa); pf.appendChild(pb); pie.appendChild(pf);
      }
      var notas = [];
      if (c.estimated && c.subtotal != null) notas.push(T.noTax);
      if (c.pendingPrices) notas.push(T.pendingNote);
      if (notas.length) { var n = el('div', 'c-note'); n.textContent = notas.join(' '); pie.appendChild(n); }
    }
    var pedir = el('button', 'c-ask'); pedir.type = 'button'; pedir.textContent = T.ask; pedir.setAttribute('data-bloq', '');
    pedir.disabled = enviando || cambios.length > 0;
    pedir.onclick = function () { enviar(T.askMsg); };
    pie.appendChild(pedir);
    cart.appendChild(pie);
  }

  function mover(i, base, q) {
    if (q < 1) return;
    if (q === base) delete ediciones[i]; else ediciones[i] = q;
    pintarCarrito();
  }

  // ── adjuntos ────────────────────────────────────────────────────────────
  /** Solo rutas del propio servidor: nada que mande el modelo se usa como URL. */
  function urlArchivo(ruta, descargar) {
    if (typeof ruta !== 'string' || !/^\/wk_[A-Za-z0-9]+\/media\/wm_[A-Za-z0-9]{32}$/.test(ruta)) return null;
    return BASE + ruta + (descargar ? '?download=1' : '');
  }

  function tamano(b) {
    if (!b) return '';
    return b < 1024 * 1024 ? Math.max(1, Math.round(b / 1024)) + ' KB' : (b / 1048576).toFixed(1) + ' MB';
  }

  function verImagen(src) {
    var box = el('div', 'lightbox');
    box.setAttribute('role', 'dialog'); box.setAttribute('aria-label', T.view);
    var img = el('img'); img.src = src; img.alt = '';
    var a = el('a'); a.href = src + '?download=1'; a.textContent = T.download; a.target = '_blank'; a.rel = 'noopener';
    a.onclick = function (e) { e.stopPropagation(); };
    box.appendChild(img); box.appendChild(a);
    box.onclick = function () { box.remove(); };
    panel.appendChild(box);
  }

  function imagenes(urls) {
    var g = el('div', 'pics n' + Math.min(urls.length, 5));
    urls.forEach(function (u) {
      var img = el('img'); img.alt = T.photo; img.loading = 'lazy'; img.src = u;
      img.onclick = function () { verImagen(u); };
      img.onerror = function () { var x = el('div', 'gone'); x.textContent = T.gone; img.replaceWith(x); };
      g.appendChild(img);
    });
    return g;
  }

  /** Tarjeta del PDF al estilo de WhatsApp: vista, nombre, tamaño y boton de descarga. */
  function documento(a) {
    var abrirUrl = urlArchivo(a.url, false), bajarUrl = urlArchivo(a.url, true);
    var card = el('div', 'msg bot doc');
    var prev = el('a', 'doc-prev', ICON.pdf);
    prev.href = abrirUrl; prev.target = '_blank'; prev.rel = 'noopener'; prev.setAttribute('aria-label', a.name || T.doc);
    var row = el('div', 'doc-row');
    row.appendChild(el('span', 'doc-ico', ICON.pdf));
    var txt = el('div', 'doc-txt');
    var nm = el('div', 'doc-name'); nm.textContent = a.name || T.doc; txt.appendChild(nm);
    var meta = el('div', 'doc-meta'); meta.textContent = ['PDF', tamano(a.size)].filter(Boolean).join(' · '); txt.appendChild(meta);
    row.appendChild(txt);
    var dl = el('a', 'doc-dl', ICON.dl);
    dl.href = bajarUrl; dl.setAttribute('download', a.name || ''); dl.title = T.download; dl.setAttribute('aria-label', T.download);
    row.appendChild(dl);
    card.appendChild(prev); card.appendChild(row);
    if (a.caption) { var c = el('div', 'cap'); conEnlaces(c, a.caption); card.appendChild(c); }
    var btn = el('a', 'btn-dl'); btn.href = bajarUrl; btn.setAttribute('download', a.name || ''); btn.textContent = T.download;
    card.appendChild(btn);
    return card;
  }

  function burbuja(m) {
    var yo = m.from === 'me';
    if (m.att && m.att.type === 'document' && urlArchivo(m.att.url)) return documento(m.att);
    var fotos = (m.images || []).slice();
    if (m.att && m.att.type === 'image' && urlArchivo(m.att.url)) fotos.push(urlArchivo(m.att.url));
    var cap = m.att ? m.att.caption : m.text;
    if (!fotos.length) {
      var n = el('div', 'msg ' + (yo ? 'me' : 'bot'));
      conEnlaces(n, m.text || '');
      return n;
    }
    var b = el('div', 'msg media ' + (yo ? 'me' : 'bot'));
    b.appendChild(imagenes(fotos));
    if (cap) { var c = el('div', 'cap'); conEnlaces(c, cap); b.appendChild(c); }
    return b;
  }

  function aviso(texto) { estado.error = texto; pintar(); }

  function agregarFotos(lista) {
    var fs = [].slice.call(lista || []);
    if (!fs.length || enviando) return;
    var tipos = /^image\/(jpeg|png|webp)$/;
    estado.error = null;
    fs.forEach(function (f) {
      if (!tipos.test(f.type)) { estado.error = T.photoType; return; }
      if (pendientes.length >= config.maxImages) { estado.error = T.photoMax.replace('{n}', config.maxImages); return; }
      pendientes.push({ file: f, url: URL.createObjectURL(f) });
    });
    pintarBandeja(); pintar();
  }

  function pintarBandeja() {
    tray.innerHTML = '';
    tray.classList.toggle('on', pendientes.length > 0);
    pendientes.forEach(function (p, i) {
      var t = el('div', 'thumb');
      var img = el('img'); img.src = p.url; img.alt = T.photo; t.appendChild(img);
      var x = el('button', '', ICON.close); x.type = 'button'; x.setAttribute('aria-label', T.remove);
      x.onclick = function () { URL.revokeObjectURL(p.url); pendientes.splice(i, 1); pintarBandeja(); actualizarBoton(); };
      t.appendChild(x); tray.appendChild(t);
    });
    actualizarBoton();
  }

  function limpiarPendientes() {
    pendientes.forEach(function (p) { URL.revokeObjectURL(p.url); });
    pendientes = [];
    if (tray) pintarBandeja();
  }

  /** Reduce la foto a 1600 px de lado y JPEG: sube rapido y el modelo la lee igual. */
  function comprimir(file) {
    return new Promise(function (ok, mal) {
      var img = new Image();
      var u = URL.createObjectURL(file);
      img.onload = function () {
        URL.revokeObjectURL(u);
        var k = Math.min(1, MAX_LADO / Math.max(img.naturalWidth, img.naturalHeight));
        var c = document.createElement('canvas');
        c.width = Math.round(img.naturalWidth * k); c.height = Math.round(img.naturalHeight * k);
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

  function post(ruta, cuerpo) {
    return fetch(BASE + '/' + KEY + ruta, {
      method: 'POST',
      // text/plain evita el preflight de CORS; el servidor lo lee como JSON.
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: JSON.stringify(cuerpo),
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (d) { return { ok: r.ok, data: d }; });
    });
  }

  function subir(p) {
    return comprimir(p.file).then(function (data) {
      return post('/media', { session: estado.session, mime: 'image/jpeg', data: data });
    }).then(function (res) {
      if (!res.ok || !res.data.id) throw new Error((res.data && res.data.message) || T.photoErr);
      return res.data;
    });
  }

  function recibir(adjuntos) {
    (adjuntos || []).forEach(function (a) {
      if (a && urlArchivo(a.url)) estado.messages.push({ from: 'bot', att: a });
    });
  }

  /** Mientras haya un render en camino, pregunta cada 3 s por lo nuevo (hasta 3 min). */
  function consultar() {
    if (consultando) return;
    consultando = true; pintar();
    (function paso() {
      setTimeout(function () {
        fetch(BASE + '/' + KEY + '/updates?session=' + encodeURIComponent(estado.session) + '&after=' + (estado.cursor || 0))
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(function (d) {
            if (d) {
              (d.items || []).forEach(function (it) {
                if (it.attachment) recibir([it.attachment]);
                else if (it.text) estado.messages.push({ from: 'bot', text: it.text });
              });
              if (typeof d.cursor === 'number') estado.cursor = d.cursor;
              if (!d.pending) estado.waitUntil = 0;
            }
          })
          .catch(function () { /* se reintenta en el siguiente paso */ })
          .then(function () {
            var sigue = estado.waitUntil && estado.waitUntil > Date.now();
            if (!sigue) { consultando = false; estado.waitUntil = 0; }
            guardar(); pintar();
            if (sigue) paso();
          });
      }, ESPERA_MS);
    })();
  }

  /** `valor` es lo que viaja (el id de la opción, como el botón de WhatsApp); `texto`, lo que se ve. */
  function enviar(texto, valor) {
    texto = (texto || '').trim();
    var fotos = valor ? [] : pendientes.slice();
    if ((!texto && !fotos.length) || enviando) return;
    enviando = true;
    estado.error = null;
    estado.choices = [];
    input.value = ''; input.style.height = 'auto';
    pendientes = []; pintarBandeja();
    var local = fotos.map(function (p) { return p.url; });
    var mio = { from: 'me', text: texto, images: local };
    estado.messages.push(mio);
    pintar();

    // Primero las fotos (una por una), luego el mensaje con sus ids.
    var ids = [];
    fotos.reduce(function (cadena, p) {
      return cadena.then(function () { return subir(p).then(function (ack) { ids.push(ack); }); });
    }, Promise.resolve())
      .then(function () {
        // En el historial quedan las del servidor: las locales mueren al recargar.
        mio.images = ids.map(function (a) { return urlArchivo(a.url); }).filter(Boolean);
        local.forEach(function (u) { URL.revokeObjectURL(u); });
        return post('/messages', {
          session: estado.session, id: rid(22), text: valor || texto,
          images: ids.length ? ids.map(function (a) { return a.id; }) : undefined,
        });
      })
      .then(function (res) {
        if (res.ok) {
          (res.data.messages || []).forEach(function (t) { estado.messages.push({ from: 'bot', text: t }); });
          recibir(res.data.attachments);
          if ('cart' in res.data) recibirCarrito(res.data.cart);
          estado.choices = res.data.choices || [];
          if (typeof res.data.cursor === 'number' && res.data.cursor > (estado.cursor || 0)) estado.cursor = res.data.cursor;
          if (res.data.pending) estado.waitUntil = Date.now() + ESPERA_MAX_MS;
        } else {
          estado.error = (res.data && res.data.message) || T.error;
        }
      })
      .catch(function (e) {
        if (navigator.onLine === false) estado.error = T.offline;
        else estado.error = (e && e.message && e.message !== 'foto' && e.message !== 'Failed to fetch') ? e.message : (fotos.length ? T.photoErr : T.error);
        // Si la foto no subio, el mensaje queda sin ella: se quitan las vistas locales.
        if (!ids.length) mio.images = [];
      })
      .then(function () {
        enviando = false;
        if (!mio.text && !(mio.images || []).length) estado.messages.splice(estado.messages.indexOf(mio), 1);
        guardar(); pintar(); pintarCarrito();
        if (estado.waitUntil) consultar();
        if (panel.classList.contains('open')) input.focus();
      });
  }

  // ── arranque ────────────────────────────────────────────────────────────
  function iniciar() {
    fetch(BASE + '/' + KEY + '/config')
      .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, data: d }; }); })
      .then(function (res) {
        if (!res.ok) {
          console.warn('[Stackline] El chat no se muestra: ' + ((res.data && res.data.message) || 'error'));
          return;
        }
        config = res.data;
        montar();
      })
      .catch(function () { console.warn('[Stackline] No se pudo cargar el chat.'); });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', iniciar);
  else iniciar();
})();
