// BookMyTicket - single-page app logic (extracted from index.html).
    const PRICE = { REGULAR: 150, PREMIUM: 250, RECLINER: 400 };
    const $ = (id) => document.getElementById(id);
    const app = $('app');

    let me = null;                          // logged-in user or null
    let walletBalance = null;               // current user's wallet balance (or null)
    let authConfig = { googleEnabled: false, loginUrl: '/oauth2/authorization/google' };
    let allMovies = [];                     // cached for search/filter
    let cities = [];                        // cities that have shows (for the location picker)
    const state = { movie: null, shows: [], show: null, seats: [], selected: new Set(),
                    filter: 'All', query: '', city: localStorage.getItem('bmt.city') || 'Mumbai',
                    hold: null, payMode: 'UPI', pollTimer: null, countdownTimer: null };

    // ------------------------------- helpers ------------------------------
    async function getJSON(path) {
      const r = await fetch(path);
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }
    function toast(msg, ok) {
      const t = $('toast'); t.textContent = msg; t.className = 'show ' + (ok ? 'ok' : 'err');
      setTimeout(() => (t.className = ''), 3800);
    }
    function fmtTime(iso) {
      const d = new Date(iso);
      let h = d.getHours(); const m = d.getMinutes().toString().padStart(2, '0');
      const ap = h >= 12 ? 'PM' : 'AM'; h = h % 12 || 12;
      return `${h}:${m} ${ap}`;
    }
    function fmtDate(iso) {
      return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
    }
    function fmtMoney(n) { return Number(n).toLocaleString('en-IN'); }
    function hue(str) { let h = 0; for (const c of str) h = (h * 31 + c.charCodeAt(0)) % 360; return h; }
    function gradient(title) {
      const h = hue(title);
      return `linear-gradient(160deg, hsl(${h} 52% 32%), hsl(${(h + 45) % 360} 60% 14%))`;
    }
    function posterHtml(m) {
      const grad = gradient(m.title);
      const img = m.posterUrl
        ? `<img class="pimg" src="${m.posterUrl}" alt="${m.title}" loading="lazy"
             onerror="this.remove();this.closest('.poster').classList.add('noimg')">`
        : '';
      return `<div class="poster ${img ? '' : 'noimg'}" style="--grad:${grad}">
        ${img}
        <div class="fallback">
          <div class="genre-tag">${m.genre}</div>
          <div class="ttl">${m.title}</div>
        </div>
        <div class="rate"><span class="star">★</span> ${m.rating}/10</div>
      </div>`;
    }

    // -------------------------------- auth --------------------------------
    async function refreshAuth() {
      try { const r = await fetch('/api/me'); me = r.ok ? await r.json() : null; } catch { me = null; }
      try { authConfig = await getJSON('/api/auth/status'); } catch {}
      walletBalance = me ? await fetchWalletBalance() : null;
      renderAuth();
    }
    async function fetchWalletBalance() {
      try { const r = await fetch('/api/me/wallet'); if (!r.ok) return null; return (await r.json()).balance; }
      catch { return null; }
    }
    function renderAuth() {
      $('myBookingsLink').style.display = me ? 'inline' : 'none';
      if (me) {
        const initial = (me.name || me.email || '?').charAt(0).toUpperCase();
        const pic = me.picture ? `<img src="${me.picture}" alt="">` : initial;
        const wallet = walletBalance != null ? `<span class="wallet-chip">👛 ₹${fmtMoney(walletBalance)}</span>` : '';
        $('auth').innerHTML =
          `<div class="profile">${wallet}<div class="avatar">${pic}</div>
             <span style="font-size:14px">${(me.name || me.email).split(' ')[0]}</span>
             <button class="btn btn-ghost" onclick="logout()">Logout</button></div>`;
      } else {
        $('auth').innerHTML = `<button class="btn btn-red" onclick="login()">Sign in</button>`;
      }
    }
    function login() {
      // Real Google when configured; otherwise a local dev sign-in so the app is usable now.
      window.location.href = authConfig.googleEnabled ? authConfig.loginUrl : '/dev-login';
    }
    async function logout() { await fetch('/logout', { method: 'POST' }); me = null; renderAuth(); go('home'); toast('Logged out', true); }

    // ----------------------------- location (city) ------------------------
    async function loadCities() {
      try { cities = await getJSON('/api/cities'); } catch { cities = []; }
      if (cities.length && !cities.includes(state.city)) {
        state.city = cities[0]; localStorage.setItem('bmt.city', state.city);
      }
      if ($('locName')) $('locName').textContent = state.city;
      renderCityMenu();
    }
    function renderCityMenu() {
      const menu = $('locMenu'); if (!menu) return;
      menu.innerHTML = (cities.length ? cities : [state.city]).map((c) =>
        `<div class="ci ${c === state.city ? 'on' : ''}" onclick="pickCity('${c}')">📍 ${c}</div>`).join('');
    }
    function toggleCities(e) { e.stopPropagation(); const m = $('locMenu'); if (m) m.classList.toggle('show'); }
    function pickCity(c) {
      state.city = c; localStorage.setItem('bmt.city', c);
      if ($('locName')) $('locName').textContent = c;
      renderCityMenu(); $('locMenu').classList.remove('show');
      if (state.movie && document.getElementById('shows')) renderMovie();   // re-filter showtimes
      toast('📍 City set to ' + c, true);
    }
    document.addEventListener('click', () => { const m = $('locMenu'); if (m) m.classList.remove('show'); });

    // ------------------------------- routing ------------------------------
    function clearViewTimers() {
      clearInterval(state.pollTimer); state.pollTimer = null;
      clearInterval(state.countdownTimer); state.countdownTimer = null;
    }
    function go(view) {
      clearViewTimers();
      window.scrollTo({ top: 0 });
      if (view === 'home') renderHome();
      else if (view === 'movie') renderMovie();
      else if (view === 'seats') renderSeats();
      else if (view === 'payment') renderPayment();
      else if (view === 'bookings') renderBookings();
    }

    // --------------------------- home (carousel + grid) -------------------
    let carouselTimer = null;
    async function renderHome() {
      app.innerHTML = `
        <div class="carousel" id="carousel"></div>
        <div class="row-head"><h2>Recommended Movies</h2><div class="chips" id="chips"></div></div>
        <div class="grid" id="grid"><div class="empty">Loading…</div></div>`;
      try {
        if (allMovies.length === 0) allMovies = await getJSON('/api/movies');
        // First-run resilience: the catalog may still be seeding for a second or two right
        // after startup. If we got an empty list, wait briefly and try once more.
        if (allMovies.length === 0) {
          await new Promise((r) => setTimeout(r, 1500));
          allMovies = await getJSON('/api/movies');
        }
        buildCarousel();
        buildChips();
        drawGrid();
      } catch (e) { $('grid').innerHTML = `<div class="empty">Failed to load movies</div>`; }
    }

    function buildCarousel() {
      const top = [...allMovies].sort((a, b) => b.rating - a.rating).slice(0, 4);
      const c = $('carousel');
      c.innerHTML = top.map((m, i) => `
        <div class="slide ${i === 0 ? 'active' : ''}" style="background:${gradient(m.title)}" onclick="openMovie('${m.id}')">
          ${m.posterUrl ? `<div class="bg" style="background-image:url('${m.posterUrl}')"></div>
          <img class="art" src="${m.posterUrl}" alt="${m.title}" onerror="this.remove()">` : ''}
          <div class="cap">
            <span class="tag">⭐ ${m.rating}/10 · ${m.certificate}</span>
            <h1>${m.title}</h1>
            <p>${m.genre} · ${m.language} · ${m.durationMins} min</p>
            <button class="btn btn-red" onclick="event.stopPropagation();openMovie('${m.id}')">Book tickets</button>
          </div>
        </div>`).join('') +
        `<div class="dots">${top.map((_, i) => `<i class="${i === 0 ? 'on' : ''}" onclick="event.stopPropagation();slideTo(${i})"></i>`).join('')}</div>`;
      let idx = 0;
      clearInterval(carouselTimer);
      carouselTimer = setInterval(() => slideTo((idx = (idx + 1) % top.length)), 4000);
      window.slideTo = (i) => {
        idx = i;
        document.querySelectorAll('#carousel .slide').forEach((s, j) => s.classList.toggle('active', j === i));
        document.querySelectorAll('#carousel .dots i').forEach((d, j) => d.classList.toggle('on', j === i));
      };
    }

    function buildChips() {
      const langs = ['All', ...new Set(allMovies.map((m) => m.language))];
      $('chips').innerHTML = langs.map((l) =>
        `<span class="chip-f ${state.filter === l ? 'on' : ''}" onclick="setFilter('${l}')">${l}</span>`).join('');
    }
    function setFilter(l) { state.filter = l; buildChips(); drawGrid(); }
    function onSearch(q) { state.query = q.trim().toLowerCase(); if ($('grid')) drawGrid(); }

    function drawGrid() {
      const q = state.query;
      const list = allMovies.filter((m) => {
        const okLang = state.filter === 'All' || m.language === state.filter;
        const okQ = !q || (m.title + ' ' + m.genre + ' ' + m.language).toLowerCase().includes(q);
        return okLang && okQ;
      });
      $('grid').innerHTML = list.length ? list.map((m) => `
        <div class="card" onclick="openMovie('${m.id}')">
          ${posterHtml(m)}
          <h3>${m.title}</h3>
          <p>${m.certificate} · ${m.genre}</p>
          <p>${m.language} · ${m.durationMins} min</p>
        </div>`).join('') : `<div class="empty">No movies match your search</div>`;
    }

    // ----------------------- movie detail + showtimes ---------------------
    async function openMovie(id) {
      const [movie, shows] = await Promise.all([
        getJSON('/api/movies/' + id),
        getJSON('/api/movies/' + id + '/shows'),
      ]);
      state.movie = movie; state.shows = shows; go('movie');
    }
    function dateStrip() {
      const today = new Date();
      return [0, 1, 2, 3, 4].map((n) => {
        const d = new Date(today); d.setDate(d.getDate() + n);
        const day = d.toLocaleDateString('en-IN', { weekday: 'short' });
        return `<div class="date ${n === 0 ? 'on' : ''}">
                  <div class="m">${day}</div><div class="d">${d.getDate()}</div>
                  <div class="m">${d.toLocaleDateString('en-IN', { month: 'short' })}</div></div>`;
      }).join('');
    }
    function renderMovie() {
      const m = state.movie;
      const inCity = state.shows.filter((s) => s.city === state.city);
      const playingCities = [...new Set(state.shows.map((s) => s.city))].sort();

      const byTheatre = {};
      inCity.forEach((s) => (byTheatre[s.theatreId] = byTheatre[s.theatreId] || { info: s, list: [] }).list.push(s));
      const theatres = Object.values(byTheatre).map((t) => `
        <div class="theatre">
          <div class="name">${t.info.theatreName}</div>
          <div class="city">📍 ${t.info.city}</div>
          <div class="times">
            ${t.list.sort((a, b) => a.startTime.localeCompare(b.startTime))
              .map((s) => `<div class="time" onclick="openSeats('${s.id}')">${fmtTime(s.startTime)}</div>`).join('')}
          </div>
        </div>`).join('');

      const emptyState = `
        <div class="empty">
          <div>No shows for <b>${m.title}</b> in <b>${state.city}</b>.</div>
          ${playingCities.length ? `<div style="margin-top:12px">Playing in:
            <div class="chips" style="justify-content:center;margin-top:10px">
              ${playingCities.map((c) => `<span class="chip-f" onclick="pickCity('${c}')">📍 ${c}</span>`).join('')}
            </div></div>` : ''}
        </div>`;

      app.innerHTML = `
        <div class="crumb" onclick="go('home')">← Movies</div>
        <div class="hero">
          ${posterHtml(m)}
          <div class="meta">
            <h2>${m.title}</h2>
            <span class="pill">⭐ ${m.rating}/10</span>
            <span class="pill">${m.certificate}</span>
            <span class="pill">${m.durationMins} min</span>
            <div style="margin-top:6px">
              <span class="pill">${m.genre}</span><span class="pill">${m.language}</span>
            </div>
            <div class="cta"><button class="btn btn-red" onclick="document.getElementById('shows').scrollIntoView({behavior:'smooth'})">Book tickets</button></div>
          </div>
        </div>
        <div class="dates">${dateStrip()}</div>
        <h2 id="shows" style="margin-bottom:14px">Showtimes in 📍 ${state.city}</h2>
        ${theatres || emptyState}`;
    }

    // --------------------------- seat selection ---------------------------
    async function openSeats(showId) {
      state.show = state.shows.find((s) => s.id === showId);
      state.selected.clear();
      const data = await getJSON('/api/shows/' + showId + '/seats');
      state.seats = data.seats; go('seats');
    }
    function renderSeats() {
      const s = state.show;
      app.innerHTML = `
        <div class="crumb" onclick="openMovie('${state.movie.id}')">← ${state.movie.title}</div>
        <div class="seatwrap">
          <div class="seat-head">
            <b>${state.movie.title}</b><br>
            <span>${s.theatreName} · ${fmtDate(s.startTime)} · ${fmtTime(s.startTime)}</span>
          </div>
          <div class="seatmap" id="seatmap"></div>
          <div class="screen"></div>
          <div class="screen-label">ALL EYES THIS WAY PLEASE 👀</div>
          <div class="legend">
            <span><i class="lchip" style="border:1px solid var(--reg)"></i> Regular ₹150</span>
            <span><i class="lchip" style="border:1px solid var(--prem)"></i> Premium ₹250</span>
            <span><i class="lchip" style="border:1px solid var(--rec)"></i> Recliner ₹400</span>
            <span><i class="lchip" style="background:#eceef2"></i> Sold</span>
            <span><i class="lchip" style="background:var(--ok)"></i> Selected</span>
          </div>
          <div class="paybar">
            <div>
              <div class="picked" id="picked">No seats selected</div>
              <div class="total">₹<span id="total">0</span></div>
            </div>
            <button id="proceed" class="btn btn-red" onclick="proceedToPay()" disabled>Proceed</button>
          </div>
        </div>`;
      drawSeats(); updateSummary(); startSeatPolling();
    }
    function drawSeats() {
      const map = $('seatmap'); map.innerHTML = '';
      const rows = {}; state.seats.forEach((s) => (rows[s.row] = rows[s.row] || []).push(s));
      Object.keys(rows).sort((a, b) => a - b).forEach((r) => {
        const line = document.createElement('div'); line.className = 'seat-row';
        line.innerHTML = `<span class="rowlabel">${String.fromCharCode(65 + Number(r))}</span>`;
        rows[r].sort((a, b) => a.col - b.col).forEach((s) => {
          const b = document.createElement('button');
          b.className = 'seat ' + s.type + (s.available ? '' : ' booked') + (state.selected.has(s.id) ? ' selected' : '');
          b.textContent = s.col; b.title = `${s.id} · ${s.type} · ₹${PRICE[s.type]}`;
          if (s.available) b.onclick = () => {
            state.selected.has(s.id) ? state.selected.delete(s.id) : state.selected.add(s.id);
            drawSeats(); updateSummary();
          };
          line.appendChild(b);
        });
        map.appendChild(line);
      });
    }
    function updateSummary() {
      const total = selectedTotal();
      $('total').textContent = total;
      $('picked').textContent = state.selected.size
        ? `${state.selected.size} seat(s): ${[...state.selected].sort().join(', ')}`
        : 'No seats selected';
      const btn = $('proceed');
      btn.disabled = state.selected.size === 0;
      btn.textContent = state.selected.size ? `Proceed (₹${total})` : 'Proceed';
    }

    // -------------- seat selection -> hold -> payment (Saga UI) -----------
    function selectedTotal() {
      return [...state.selected].reduce((sum, id) => {
        const s = state.seats.find((x) => x.id === id); return sum + (s ? PRICE[s.type] : 0); }, 0);
    }

    // Poll the seat map so seats others grab (or hold) disappear live.
    function startSeatPolling() {
      clearInterval(state.pollTimer);
      state.pollTimer = setInterval(refreshSeatAvailability, 5000);
    }
    async function refreshSeatAvailability() {
      try {
        const data = await getJSON('/api/shows/' + state.show.id + '/seats');
        state.seats = data.seats;
        const taken = [];
        state.seats.forEach((s) => {
          if (state.selected.has(s.id) && !s.available) { state.selected.delete(s.id); taken.push(s.id); }
        });
        if (taken.length) toast('⚠ Seat ' + taken.sort().join(', ') + ' was just taken', false);
        if ($('seatmap')) { drawSeats(); updateSummary(); }
      } catch {}
    }

    // Step 1: hold the seats (locks them for everyone else), then show payment.
    async function proceedToPay() {
      if (!me) { toast('Please sign in to continue', false); return login(); }
      const showId = state.show.id;
      try {
        const r = await fetch('/api/shows/' + showId + '/holds', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ seatIds: [...state.selected] }),
        });
        if (r.status === 401) { toast('Please sign in to continue', false); return login(); }
        const body = await r.json();
        if (r.ok) { state.hold = body; state.payMode = 'UPI'; go('payment'); }
        else if (r.status === 409) { toast('😬 Someone just grabbed a seat — pick again', false); refreshSeatAvailability(); }
        else toast('⛔ ' + (body.message || ('HTTP ' + r.status)), false);
      } catch (e) { toast('⛔ ' + e.message, false); }
    }

    // ------------------------------- payment ------------------------------
    async function renderPayment() {
      const s = state.show, total = selectedTotal();
      walletBalance = await fetchWalletBalance();
      app.innerHTML = `
        <div class="crumb" onclick="releaseAndBack()">← Seat selection</div>
        <div class="pay-wrap">
          <div class="pay-card">
            <div class="pay-summary">
              <div>
                <b>${state.movie.title}</b>
                <div class="sub">${s.theatreName} · ${fmtDate(s.startTime)} · ${fmtTime(s.startTime)}</div>
                <div class="sub">🎟 Seats <b>${[...state.selected].sort().join(', ')}</b></div>
              </div>
              <div style="text-align:right">
                <div class="countdown" id="countdown">⏳ --:--</div>
                <div class="total" style="margin-top:10px">₹${total}</div>
              </div>
            </div>
          </div>
          <div class="pay-card">
            <b>Choose payment method</b>
            <div class="methods" id="methods"></div>
            <div class="pay-foot">
              <span style="color:var(--muted);font-size:13px">🔒 Your seats are locked while you pay.</span>
              <button id="payBtn" class="btn btn-red" onclick="pay()">Pay ₹${total}</button>
            </div>
          </div>
        </div>`;
      drawMethods(); startCountdown();
    }
    function drawMethods() {
      const total = selectedTotal();
      const methods = [
        { id: 'UPI', ic: '📲', nm: 'UPI', bal: 'Any UPI app' },
        { id: 'CARD', ic: '💳', nm: 'Card', bal: 'Credit / Debit' },
        { id: 'WALLET', ic: '👛', nm: 'Wallet', bal: walletBalance != null ? '₹' + fmtMoney(walletBalance) + ' left' : 'Balance —' },
      ];
      $('methods').innerHTML = methods.map((m) => {
        const broke = m.id === 'WALLET' && walletBalance != null && walletBalance < total;
        return `<div class="method ${state.payMode === m.id ? 'on' : ''} ${broke ? 'disabled' : ''}"
                     onclick="${broke ? '' : `selectMethod('${m.id}')`}">
                  <div class="ic">${m.ic}</div><div class="nm">${m.nm}</div>
                  <div class="bal">${broke ? 'Low balance' : m.bal}</div>
                </div>`;
      }).join('');
      const broke = state.payMode === 'WALLET' && walletBalance != null && walletBalance < total;
      $('payBtn').disabled = broke;
    }
    function selectMethod(id) { state.payMode = id; drawMethods(); }

    function startCountdown() {
      clearInterval(state.countdownTimer);
      const tick = () => {
        const el = $('countdown');
        if (!el || !state.hold) { clearInterval(state.countdownTimer); return; }
        const ms = new Date(state.hold.heldUntil).getTime() - Date.now();
        if (ms <= 0) {
          clearInterval(state.countdownTimer);
          toast('⌛ Hold expired — please pick seats again', false);
          state.hold = null; go('seats'); refreshSeatAvailability(); return;
        }
        const mm = Math.floor(ms / 60000);
        const ss = Math.floor((ms % 60000) / 1000).toString().padStart(2, '0');
        el.textContent = `⏳ ${mm}:${ss}`;
        el.classList.toggle('warn', ms < 60000);
      };
      tick(); state.countdownTimer = setInterval(tick, 1000);
    }

    // Step 2: pay -> confirm. On failure the saga releases the hold, so we go back.
    async function pay() {
      const showId = state.show.id;
      try {
        const r = await fetch('/api/shows/' + showId + '/bookings', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ seatIds: [...state.selected], paymentMode: state.payMode }),
        });
        if (r.status === 401) { toast('Session expired — sign in again', false); return login(); }
        const body = await r.json();
        if (r.status === 201) {
          clearInterval(state.countdownTimer); state.hold = null;
          walletBalance = await fetchWalletBalance(); renderAuth();   // reflect wallet debit
          showConfirm(body);
        } else if (r.status === 402) {
          toast('💳 Payment failed / low balance — seats released', false);
          state.hold = null; go('seats'); refreshSeatAvailability();
        } else if (r.status === 409) {
          toast('😬 Seat just taken — pick again', false);
          state.hold = null; go('seats'); refreshSeatAvailability();
        } else toast('⛔ ' + (body.message || ('HTTP ' + r.status)), false);
      } catch (e) { toast('⛔ ' + e.message, false); }
    }

    // Back from payment: release the hold so the seats free up immediately.
    async function releaseAndBack() {
      const showId = state.show.id;
      const seatIds = [...state.selected];
      try {
        await fetch('/api/shows/' + showId + '/holds', {
          method: 'DELETE', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ seatIds }),
        });
      } catch {}
      state.hold = null; go('seats'); refreshSeatAvailability();
    }

    function showConfirm(b) {
      $('modal').innerHTML = `
        <div class="top">
          <div class="tick">🎉</div>
          <h3>Booking Confirmed</h3>
          <div style="opacity:.9;font-size:13px">${state.movie.title} · ${fmtTime(state.show.startTime)}</div>
        </div>
        <div class="mid">
          <div class="bid">Booking ID · ${b.bookingId}</div>
          <div class="seats">Seats ${b.seats.join(', ')} · paid ₹${b.amount}</div>
          <button class="btn btn-red" onclick="closeModal();go('bookings')">View My Bookings</button>
        </div>`;
      $('overlay').classList.add('show');
    }
    function closeModal(e) { if (!e || e.target.id === 'overlay') $('overlay').classList.remove('show'); }
    window.closeModal = closeModal;

    // ----------------------------- my bookings ----------------------------
    async function renderBookings() {
      app.innerHTML = `<div class="crumb" onclick="go('home')">← Movies</div>
        <h2 style="margin:6px 0 18px">My Bookings</h2>
        <div class="tickets" id="tickets"><div class="empty">Loading…</div></div>`;
      try {
        const r = await fetch('/api/me/bookings');
        if (r.status === 401) { $('tickets').innerHTML = `<div class="empty">Please sign in to see your bookings</div>`; return; }
        const list = await r.json();
        $('tickets').innerHTML = list.length ? list.map((b) => `
          <div class="ticket">
            <div class="stub"></div>
            <div class="body">
              <h3>${b.movieTitle}</h3>
              <div class="sub">${b.theatreName} · ${fmtDate(b.startTime)} · ${fmtTime(b.startTime)}</div>
              <div class="seats">🎟 Seats <b>${b.seats.join(', ')}</b></div>
              <div class="foot">
                <span style="color:var(--muted);font-size:12px">${b.bookingId}</span>
                <span><span class="badge ${b.status}">${b.status}</span> &nbsp;<b>₹${b.amount}</b></span>
              </div>
            </div>
          </div>`).join('') : `<div class="empty">No bookings yet — go book a movie! 🍿</div>`;
      } catch (e) { $('tickets').innerHTML = `<div class="empty">Failed to load bookings</div>`; }
    }

    // expose handlers used in inline onclick
    Object.assign(window, { go, openMovie, openSeats, proceedToPay, pay, selectMethod,
                            releaseAndBack, login, logout, setFilter, onSearch,
                            toggleCities, pickCity });

    // -------------------------------- boot --------------------------------
    (async function () { await Promise.all([refreshAuth(), loadCities()]); renderHome(); })();
