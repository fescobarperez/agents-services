/*!
 * Widget de chat de Stackline (canal web de Tino).
 *
 * Uso, en la página de la empresa:
 *   <script src="https://AGENTES/v1/web/widget.js" data-key="wk_..." async></script>
 *
 * Opcionales: data-position="left" (por defecto a la derecha), data-open="true"
 * (abre el chat al cargar), data-lang="es|en" (por defecto, el del navegador).
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
  var OPEN = script.getAttribute('data-open') === 'true';
  var LANG = (script.getAttribute('data-lang') || navigator.language || 'es').slice(0, 2) === 'en' ? 'en' : 'es';
  var STORE = 'stackline-chat:' + KEY;
  var MAX_HIST = 60;

  var T = {
    es: {
      open: 'Abrir chat', close: 'Cerrar chat', reset: 'Nueva conversación', send: 'Enviar',
      placeholder: 'Escribe tu mensaje…', online: 'Responde en segundos', typing: 'Escribiendo…',
      error: 'No pude enviar tu mensaje. Intenta de nuevo.', offline: 'Sin conexión. Revisa tu internet.',
      resetAsk: '¿Empezar una conversación nueva? Se borrará lo de esta pantalla.', powered: 'Con tecnología de Stackline',
    },
    en: {
      open: 'Open chat', close: 'Close chat', reset: 'New conversation', send: 'Send',
      placeholder: 'Type your message…', online: 'Replies in seconds', typing: 'Typing…',
      error: 'Your message could not be sent. Please try again.', offline: 'You are offline. Check your connection.',
      resetAsk: 'Start a new conversation? This screen will be cleared.', powered: 'Powered by Stackline',
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

  // ── estilos ─────────────────────────────────────────────────────────────
  function contraste(hex) {
    var m = /^#([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return '#ffffff';
    var n = parseInt(m[1], 16);
    var r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    var l = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return l > 0.62 ? '#1d1b1c' : '#ffffff';
  }

  var CSS = [
    ':host{all:initial}',
    '*{box-sizing:border-box;font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}',
    '.wrap{position:fixed;bottom:20px;' + (LEFT ? 'left' : 'right') + ':20px;z-index:2147483000;display:flex;flex-direction:column;align-items:' + (LEFT ? 'flex-start' : 'flex-end') + ';gap:12px}',
    '.launcher{width:58px;height:58px;border-radius:50%;border:0;cursor:pointer;background:var(--c);color:var(--on);display:grid;place-items:center;box-shadow:0 6px 20px rgba(0,0,0,.22);transition:transform .15s ease}',
    '.launcher:hover{transform:scale(1.06)}',
    '.launcher:focus-visible,.icon-btn:focus-visible,.send:focus-visible,.choice:focus-visible{outline:3px solid var(--c);outline-offset:2px}',
    '.launcher svg{width:28px;height:28px}',
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
    '.log{flex:1;overflow-y:auto;padding:16px 14px;display:flex;flex-direction:column;gap:8px;background:#f7f4f5;scrollbar-width:thin}',
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
    '.foot{font-size:11px;color:#8b8186;text-align:center;padding:2px 0 8px;background:#fff}',
    '@media (max-width:480px){.wrap{bottom:12px;' + (LEFT ? 'left' : 'right') + ':12px}.panel.open{position:fixed;inset:0;width:auto;height:auto;max-height:none;border-radius:0}}',
  ].join('\n');

  var ICON = {
    chat: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2Zm3 5v2h10V9H7Zm0 4v2h7v-2H7Z"/></svg>',
    close: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M18.3 5.7 12 12l6.3 6.3-1.4 1.4L10.6 13.4 4.3 19.7 2.9 18.3 9.2 12 2.9 5.7 4.3 4.3l6.3 6.3 6.3-6.3z"/></svg>',
    down: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7.4 8.6 12 13.2l4.6-4.6L18 10l-6 6-6-6z"/></svg>',
    reset: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 5V2L7 6l5 4V7a5 5 0 1 1-5 5H5a7 7 0 1 0 7-7z"/></svg>',
    send: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M3 20.5 21 12 3 3.5 3 10l12 2-12 2z"/></svg>',
  };

  // ── DOM ─────────────────────────────────────────────────────────────────
  var host, root, panel, log, input, sendBtn, launcher;

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
    root = host.attachShadow ? host.attachShadow({ mode: 'open' }) : host;
    var color = /^#[0-9a-f]{6}$/i.test(config.color || '') ? config.color : '#731C37';

    var style = el('style'); style.textContent = CSS; root.appendChild(style);
    var wrap = el('div', 'wrap');
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
    head.appendChild(resetBtn); head.appendChild(closeBtn);

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
    compose.appendChild(input); compose.appendChild(sendBtn);
    compose.onsubmit = function (e) { e.preventDefault(); enviar(input.value); };
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); enviar(input.value); }
    });
    input.addEventListener('input', function () {
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 120) + 'px';
      actualizarBoton();
    });

    var foot = el('div', 'foot'); foot.textContent = T.powered;

    panel.appendChild(head); panel.appendChild(log); panel.appendChild(compose); panel.appendChild(foot);

    launcher = el('button', 'launcher', ICON.chat);
    launcher.type = 'button';
    launcher.setAttribute('aria-label', T.open);
    launcher.onclick = function () { abrir(!panel.classList.contains('open')); };

    wrap.appendChild(panel); wrap.appendChild(launcher);
    root.appendChild(wrap);
    document.body.appendChild(host);

    pintar();
    if (OPEN || estado.open) abrir(true);
  }

  function abrir(si) {
    panel.classList.toggle('open', si);
    launcher.innerHTML = si ? ICON.close : ICON.chat;
    launcher.setAttribute('aria-label', si ? T.close : T.open);
    estado.open = si; guardar();
    if (si) { bajar(); setTimeout(function () { input.focus(); }, 50); }
  }

  function actualizarBoton() { sendBtn.disabled = enviando || !input.value.trim(); }

  function bajar() { log.scrollTop = log.scrollHeight; }

  function pintar() {
    log.innerHTML = '';
    if (config.greeting && !estado.messages.length) {
      var g = el('div', 'msg bot'); conEnlaces(g, config.greeting); log.appendChild(g);
    }
    estado.messages.forEach(function (m) {
      var n = el('div', 'msg ' + (m.from === 'me' ? 'me' : 'bot'));
      conEnlaces(n, m.text);
      log.appendChild(n);
    });
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
    if (estado.error) { var s = el('div', 'sys'); s.textContent = estado.error; log.appendChild(s); }
    actualizarBoton();
    bajar();
  }

  function reiniciar() {
    if (enviando || (estado.messages.length && !window.confirm(T.resetAsk))) return;
    estado = { session: rid(24), messages: [], choices: [], open: true };
    guardar(); pintar(); input.focus();
  }

  /** `valor` es lo que viaja (el id de la opción, como el botón de WhatsApp); `texto`, lo que se ve. */
  function enviar(texto, valor) {
    texto = (texto || '').trim();
    if (!texto || enviando) return;
    enviando = true;
    estado.error = null;
    estado.choices = [];
    estado.messages.push({ from: 'me', text: texto });
    input.value = ''; input.style.height = 'auto';
    guardar(); pintar();

    fetch(BASE + '/' + KEY + '/messages', {
      method: 'POST',
      // text/plain evita el preflight de CORS; el servidor lo lee como JSON.
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: JSON.stringify({ session: estado.session, id: rid(22), text: valor || texto }),
    })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (d) { return { ok: r.ok, data: d }; });
      })
      .then(function (res) {
        if (res.ok) {
          (res.data.messages || []).forEach(function (t) { estado.messages.push({ from: 'bot', text: t }); });
          estado.choices = res.data.choices || [];
        } else {
          estado.error = (res.data && res.data.message) || T.error;
        }
      })
      .catch(function () { estado.error = navigator.onLine === false ? T.offline : T.error; })
      .then(function () {
        enviando = false;
        guardar(); pintar();
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
