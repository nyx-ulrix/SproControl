// Self-check for k2.html's protocol layer, run in a browser against the served page:
//   python -m http.server 8765   then open http://localhost:8765/tests/page_test.html
// It feeds the page frames captured from a live K2 and checks what the page decodes and sends.
(async function () {
  const sent = [];
  window.AndroidBle = { write: h => sent.push(h), connect() {}, disconnect() {}, setName() {}, setProfile() {}, setOffset() {}, setPurge() {}, setLowBatt() {} };
  const k = window.__k2, sleep = ms => new Promise(r => setTimeout(r, ms)), $ = id => document.getElementById(id);
  const fr = (cmd, data) => { const d = [0x7e, 0, data.length + 5, 0, cmd, ...data]; d[3] = d.reduce((a, b) => a + b, 0) & 255; return new Uint8Array(d); };
  const hx = s => new Uint8Array(s.match(/../g).map(x => parseInt(x, 16)));
  const results = [];
  const check = (name, ok) => results.push((ok ? 'PASS ' : 'FAIL ') + name);
  // writes leave the page 40 ms apart and resends wait 300 ms, so frame checks poll instead of sleeping
  const until = async (pred, ms = 1500) => { for (let t = 0; t < ms; t += 50) { if (pred()) return true; await sleep(50); } return pred(); };
  try { localStorage.clear(); localStorage.setItem('k2-seeded', '1'); } catch (e) {} // no Default preset in the test run

  // encoder: frames from PROTOCOL.md §6
  const H = a => Array.from(a).map(x => x.toString(16).padStart(2, '0')).join(' ');
  check('GET_DEVICE_STATE', H(k.frame(0x00)) === '7f 00 05 84 00');
  check('start heat+extract', H(k.frame(0x02, [1, 1])) === '7f 00 07 8a 02 01 01');
  check('work params', H(k.frame(0x18, [1, 5, 3, 30])) === '7f 00 09 c7 18 01 05 03 1e');

  k2Native.ready('Hi-t-1292G0_B526'); await sleep(350);
  sent.length = 0;
  // a lost handshake reply is asked for again
  check('missing replies re-queried', await until(() => sent.includes('7f 00 05 99 15') && sent.includes('7f 00 05 9b 17'), 2500));
  check('placeholders dimmed until the machine answers', $('app').classList.contains('stale'));
  sent.length = 0;
  // live frames: temp setting (°F), work params, status (split across two notifications)
  k.feed(hx('7e0008961564cccb'));
  k.feed(hx('7e000fda170207000a07000afa1efa'));
  k.feed(hx('7e000b0200')); k.feed(hx('014502310000'));
  await sleep(50);
  check('preset shown in °C (95)', $('tempLbl').textContent === 'Temp · 95°');
  check('status decoded 49° / 69%', $('tval').textContent === '49' && $('bpct').textContent === '69%');
  check('soak/hold/extraction from 0x17', $('soakLbl').textContent === 'Pre-soak · 7s' && $('holdLbl').textContent === 'Hold · 7s' && $('v-ext').textContent === '250s');
  check('dimming lifted once answered', !$('app').classList.contains('stale'));

  // a corrupt frame is dropped: wrong checksum must not change the readout
  k.feed(hx('7e000b0300014502310000')); // CHK 03 is wrong for this payload (02 is right)
  await sleep(20);
  check('bad checksum dropped', $('cur').textContent === '49°');

  // bad bridge input is ignored
  k2Native.data('zz'); k2Native.data(null); k2Native.data('7e0');
  check('bridge rejects malformed hex', $('cur').textContent === '49°');

  // set temperature: the page sends °F and confirms in °C
  const svg = $('arcsvg'), r = svg.getBoundingClientRect(), sc = Math.min(r.width / 300, r.height / 500), ox = r.right - 300 * sc, oy = r.top + (r.height - 500 * sc) / 2;
  const at = c => { const a = (124 + (c - 38) / (96 - 38) * 112) * Math.PI / 180; return [ox + (330 + 285 * Math.cos(a)) * sc, oy + (250 + 285 * Math.sin(a)) * sc]; };
  const p = at(92); const hit = $('tempHit');
  hit.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientX: p[0], clientY: p[1], pointerId: 1 }));
  window.dispatchEvent(new PointerEvent('pointerup', { pointerId: 1 }));
  for (let i = 0; i < 20 && !sent.some(h => h === '7f 00 06 61 16 c6'); i++) await sleep(50); // writes are queued 40 ms apart
  check('92 °C sent as 198 °F', sent.some(h => h === '7f 00 06 61 16 c6'));
  k.feed(fr(0x15, [100, 204, 197])); await sleep(30);
  check('read-back 197 °F accepted as 92 °C', $('pnote').textContent === 'Temp 92°C saved');

  // dropped write: a mismatching read-back triggers a resend
  sent.length = 0;
  const sl = $('tempHit'); const q = at(90);
  sl.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientX: q[0], clientY: q[1], pointerId: 1 }));
  window.dispatchEvent(new PointerEvent('pointerup', { pointerId: 1 }));
  await until(() => sent.some(h => h.includes(' 16 '))); sent.length = 0;
  k.feed(fr(0x15, [100, 204, 197]));
  check('mismatch resends the set', await until(() => sent.some(h => h.includes(' 16 '))));

  // a setting the machine keeps rejecting blocks the brew buttons until a later set is accepted
  for (let i = 0; i < 4; i++) { k.feed(fr(0x15, [100, 204, 197])); await sleep(350); }
  check('rejected temperature reported', await until(() => $('pnote').textContent === 'Temp 90°C not accepted', 2500));
  sent.length = 0; document.querySelector('.mode.both').click(); await sleep(80);
  check('brew blocked while the machine rejects the profile', document.querySelector('.mode.both').classList.contains('blocked') && !sent.some(h => h.includes(' 02 ')));
  const t93 = at(93);
  sl.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientX: t93[0], clientY: t93[1], pointerId: 1 }));
  window.dispatchEvent(new PointerEvent('pointerup', { pointerId: 1 }));
  await until(() => sent.some(h => h === '7f 00 06 62 16 c7')); k.feed(fr(0x15, [100, 204, 199])); await sleep(30);
  check('accepted set unblocks the brew', !document.querySelector('.mode.both').classList.contains('blocked'));

  // brew timers
  k.feed(fr(0x00, [1, 69, 2, 49, 3, 0])); await sleep(1100);
  check('extraction starts the countdown', /Pre-soak · \d+s left/.test($('soakLbl').textContent));
  check('brew timer counting', await until(() => !$('timer').hidden && /^0:0[1-3]$/.test($('timer').textContent), 2500));
  k.feed(fr(0x00, [1, 69, 2, 49, 4, 0])); await sleep(50);
  check('brew done shown', $('phase').textContent === 'Brew done');
  check('final time kept', !$('timer').hidden && $('timer').classList.contains('done'));
  $('timer').click(); await sleep(300);
  check('tap dismisses the time', $('timer').hidden);
  k.feed(fr(0x00, [1, 69, 2, 49, 0, 0])); await sleep(50);

  // purge: params 0/0/30 then extraction start; the previous settings come back when it ends
  sent.length = 0;
  document.querySelector('.mode.purge').click();
  check('purge sets 0/0/30 then starts extraction', await until(() => sent.includes('7f 00 09 c0 18 02 00 00 1e') && sent.includes('7f 00 07 8b 02 02 01')));
  k.feed(fr(0x17, [2, 0, 0, 10, 0, 0, 10, 30, 30, 250])); k.feed(fr(0x00, [1, 69, 2, 49, 3, 0])); await sleep(50);
  sent.length = 0; k.feed(fr(0x00, [1, 69, 2, 49, 4, 0]));
  check('purge restores 7/7/250 afterwards', await until(() => sent.includes('7f 00 09 aa 18 02 07 07 fa')));

  // a purge interrupted by a disconnect is undone on the next connect, before anything else
  document.querySelector('.mode.purge').click();
  await until(() => sent.includes('7f 00 07 8b 02 02 01'));
  k2Native.gone(); await sleep(50);
  sent.length = 0; k2Native.ready('Hi-t-1292G0_B526'); await sleep(350);
  k.feed(fr(0x00, [1, 69, 2, 49, 0, 0])); k.feed(fr(0x17, [2, 0, 0, 10, 0, 0, 10, 30, 30, 250]));
  check('interrupted purge restored on reconnect', await until(() => sent.includes('7f 00 09 aa 18 02 07 07 fa')));
  k.feed(fr(0x17, [2, 7, 0, 10, 7, 0, 10, 250, 30, 250])); k.feed(fr(0x15, [100, 204, 197])); await sleep(50);

  // a purge stopped by hand before the K2 ever reported "extracting" still gets the settings back
  k.feed(fr(0x00, [1, 69, 2, 49, 0, 0])); await sleep(50); sent.length = 0;
  document.querySelector('.mode.purge').click();
  await until(() => sent.includes('7f 00 07 8b 02 02 01'));
  k.feed(fr(0x17, [2, 0, 0, 10, 0, 0, 10, 30, 30, 250])); await sleep(50); sent.length = 0;
  $('stop').click();
  check('stop ends the purge', await until(() => sent.includes('7f 00 07 8a 02 02 00')));
  check('stopped purge restores 7/7/250', await until(() => sent.includes('7f 00 09 aa 18 02 07 07 fa'), 2000));
  k.feed(fr(0x17, [2, 7, 0, 10, 7, 0, 10, 250, 30, 250])); await sleep(50);
  // a purge when the machine already holds the purge settings sends no settings at all
  k.feed(fr(0x17, [2, 0, 0, 10, 0, 0, 10, 30, 30, 250])); await sleep(50); sent.length = 0;
  document.querySelector('.mode.purge').click();
  await until(() => sent.includes('7f 00 07 8b 02 02 01'));
  check('purge on purge settings sets nothing', !sent.some(h => h.includes(' 18 ')));
  $('stop').click(); await sleep(800);
  check('and restores nothing', !sent.some(h => h.includes(' 18 ')));
  k.feed(fr(0x17, [2, 7, 0, 10, 7, 0, 10, 250, 30, 250])); await sleep(50);

  // a brew started with unsaved settings becomes the temporary "soak • hold • temp" profile
  k.feed(fr(0x00, [1, 69, 2, 49, 0, 0])); await sleep(50);
  document.querySelector('.mode.both').click(); await sleep(50);
  check('temporary profile named from the settings', $('prof').textContent === '7 • 7 • 92°C' && localStorage.getItem('k2-last') === '7 • 7 • 92°C');
  document.getElementById('stop').click(); await sleep(50);

  // disconnect clears everything
  k2Native.gone(); await sleep(50);
  check('disconnect resets timers', $('phase').hidden === true && $('soakLbl').textContent === 'Pre-soak · 7s');

  document.body.insertAdjacentHTML('beforeend', '<pre id="results" style="position:fixed;inset:auto 0 0 0;background:#111;color:#0f0;padding:8px;z-index:99;font:12px monospace">' + results.join('\n') + '</pre>');
  window.__results = results;
})();
