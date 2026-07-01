// BookMyTicket — AI chat-booking widget. Self-contained: injects a floating button + panel,
// talks to POST /api/chat, and keeps the conversationId for multi-turn memory.
(function () {
  const STORAGE_KEY = 'bmt.chat.conversationId';
  let conversationId = sessionStorage.getItem(STORAGE_KEY) || null;
  let sending = false;

  // ------------------------------- DOM -------------------------------
  const launcher = document.createElement('button');
  launcher.id = 'chatLauncher';
  launcher.setAttribute('aria-label', 'Chat to book tickets');
  launcher.innerHTML = '💬';

  const panel = document.createElement('div');
  panel.id = 'chatPanel';
  panel.innerHTML = `
    <div class="chat-head">
      <div class="chat-title">🎟️ Booking Assistant</div>
      <button class="chat-close" aria-label="Close">✕</button>
    </div>
    <div class="chat-log" id="chatLog"></div>
    <form class="chat-input" id="chatForm" autocomplete="off">
      <input id="chatText" placeholder="e.g. Book 2 recliners for Inception tonight" />
      <button type="submit" id="chatSend">Send</button>
    </form>`;

  document.body.appendChild(launcher);
  document.body.appendChild(panel);

  const log = panel.querySelector('#chatLog');
  const form = panel.querySelector('#chatForm');
  const input = panel.querySelector('#chatText');

  // ----------------------------- helpers -----------------------------
  function escapeHtml(s) {
    return s.replace(/[&<>"']/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function addMessage(text, who) {
    const row = document.createElement('div');
    row.className = 'chat-msg ' + who;
    // preserve line breaks from the assistant's formatted lists
    row.innerHTML = escapeHtml(text).replace(/\n/g, '<br>');
    log.appendChild(row);
    log.scrollTop = log.scrollHeight;
    return row;
  }

  function addTyping() {
    const row = document.createElement('div');
    row.className = 'chat-msg bot typing';
    row.innerHTML = '<span></span><span></span><span></span>';
    log.appendChild(row);
    log.scrollTop = log.scrollHeight;
    return row;
  }

  function openPanel() {
    panel.classList.add('open');
    launcher.classList.add('hidden');
    if (!log.dataset.greeted) {
      addMessage("Hi! I can find movies and book your tickets right here. Try “What movies are showing?” or “Book 2 seats for Inception tonight”.", 'bot');
      log.dataset.greeted = '1';
    }
    setTimeout(() => input.focus(), 50);
  }

  function closePanel() {
    panel.classList.remove('open');
    launcher.classList.remove('hidden');
  }

  // ------------------------------ send -------------------------------
  async function send(message) {
    if (sending) return;
    sending = true;
    addMessage(message, 'user');
    const typing = addTyping();
    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message, conversationId }),
      });
      typing.remove();
      if (!res.ok) {
        addMessage('Sorry — the assistant is unavailable right now (HTTP ' + res.status + ').', 'bot');
        return;
      }
      const data = await res.json();
      conversationId = data.conversationId;
      if (conversationId) sessionStorage.setItem(STORAGE_KEY, conversationId);
      addMessage(data.reply, 'bot');
      // If the bot needs a sign-in and the site exposes a login(), offer a quick button.
      if (!data.signedIn && /sign in/i.test(data.reply) && typeof window.login === 'function') {
        const btn = document.createElement('button');
        btn.className = 'chat-signin';
        btn.textContent = 'Sign in to book';
        btn.onclick = () => window.login();
        log.appendChild(btn);
        log.scrollTop = log.scrollHeight;
      }
    } catch (e) {
      typing.remove();
      addMessage('Network error — please try again.', 'bot');
    } finally {
      sending = false;
    }
  }

  // ------------------------------ wire -------------------------------
  launcher.addEventListener('click', openPanel);
  panel.querySelector('.chat-close').addEventListener('click', closePanel);
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    send(text);
  });
})();
