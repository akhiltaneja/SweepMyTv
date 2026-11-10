const $ = (selector) => document.querySelector(selector);
const screens = {
  home: {src:'assets/home.png', title:'Sweep TV home screen', alt:'Actual Sweep TV television showing Continue Watching, the app dock and compact header', note:'Actual test-TV screenshot · build 70 · Android TV 10'},
  recommendations: {src:'assets/recommendations.png', title:'Recommended for you', alt:'Actual Sweep TV recommendations from Apple TV, Netflix and JioHotstar', note:'Actual test-TV screenshot · build 67 · portrait recommendations'},
  formula: {src:'assets/formula-one.png', title:'Formula 1 race center', alt:'Actual Sweep TV F1 cards with driver portraits, team logos, race countdown and published session results', note:'Actual test-TV screenshot · build 70 · F1 schedule and countdown'}
};
let currentScreen = 'home';
let release;
let toastTimer;
const tabs = [...document.querySelectorAll('[data-screen]')];
function selectScreen(key) {
  if (!screens[key]) return;
  currentScreen = key;
  const screen = screens[key];
  $('#screen-image').src = screen.src; $('#screen-image').alt = screen.alt;
  $('#screen-note').textContent = screen.note;
  for (const tab of tabs) { const active = tab.dataset.screen === key; tab.setAttribute('aria-selected', String(active)); tab.tabIndex = active ? 0 : -1; }
  $('#screen-panel').setAttribute('aria-labelledby', tabs.find(tab => tab.dataset.screen === key).id);
}
for (const tab of tabs) {
  tab.addEventListener('click', () => selectScreen(tab.dataset.screen));
  tab.addEventListener('keydown', (event) => {
    const index = tabs.indexOf(tab);
    const next = event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length : event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : -1;
    if (next < 0) return; event.preventDefault(); selectScreen(tabs[next].dataset.screen); tabs[next].focus();
  });
}
for (const button of document.querySelectorAll('[data-select-screen]')) button.addEventListener('click', () => {
  selectScreen(button.dataset.selectScreen); $('#preview').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'});
});
$('#expand-preview').addEventListener('click', () => {
  const screen = screens[currentScreen]; $('#dialog-image').src = screen.src; $('#dialog-image').alt = screen.alt;
  $('#preview-dialog-title').textContent = screen.title; $('#dialog-note').textContent = screen.note; $('#preview-dialog').showModal();
});
$('#open-recovery').addEventListener('click', () => $('#recovery-dialog').showModal());
for (const button of document.querySelectorAll('[data-close-dialog]')) button.addEventListener('click', () => button.closest('dialog').close());
for (const dialog of document.querySelectorAll('dialog')) dialog.addEventListener('click', (event) => {
  if (event.target !== dialog) return; const r = dialog.getBoundingClientRect();
  if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) dialog.close();
});
function checkModel(value) {
  if (/mooq1/i.test(value)) return {kind:'reference', title:'This is our reference model.', body:'The launcher, recommendations and F1 widget were verified on the MiTV-MOOQ1 running Android TV 10. Your device still needs its own setup and remote/playback checks.'};
  if (/samsung|tizen|\blg\b|webos|roku|vidaa|fire\s?tv|fire\s?os/i.test(value)) return {kind:'other', title:'This needs a different setup path.', body:'This preview targets Android TV / Google TV. It does not claim support for Tizen, webOS, Roku, VIDAA, or Fire TV. Check the operating system of the device you want to customize.'};
  if (/android|google\s?tv|xiaomi|redmi|mi\s?tv|sony|bravia|tcl|hisense|philips|oneplus|realme|shield|nvidia/i.test(value)) return {kind:'candidate', title:'A good candidate—check the operating system.', body:'If this device runs Android TV 8.0+ or Google TV and permits APK installation, it may support the preview. Brand alone cannot confirm compatibility. The setup prompt asks your assistant to verify the device first.'};
  return {kind:'unknown', title:'Let’s identify the TV first.', body:'Check Settings → About for Android TV / Google TV and the Android version. You can still create a setup prompt below; it tells your assistant to verify the operating system before making changes.'};
}
$('#compatibility-form').addEventListener('submit', (event) => {
  event.preventDefault(); const model = $('#tv-model').value.trim(); if (!model) return;
  const result = checkModel(model); const target = $('#compatibility-result');
  target.replaceChildren(); const strong = document.createElement('strong'); strong.textContent = result.title;
  const text = document.createElement('p'); text.textContent = result.body; target.append(strong, text); target.dataset.kind = result.kind; target.hidden = false;
  $('#setup-model').value = model;
});
function address(value) {
  const match = /^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?$/.exec(value.trim());
  if (!match || match[1].split('.').some(part => Number(part) > 255 || String(Number(part)) !== part)) return null;
  const port = match[2] ? Number(match[2]) : 5555; if (port < 1 || port > 65535) return null;
  return `${match[1]}:${port}`;
}
function buildPrompt(model, host, scope) {
  const apkName = release.file.split('/').pop();
  return `Help me set up Sweep TV on my own Android TV. Work step by step and verify the actual device.

TV model (user-provided, verify it): ${JSON.stringify(model)}
ADB address: ${host}
Downloaded APK: ${apkName}
Expected package: com.sweepmytv.launcher
Preview version: ${release.version}
Expected SHA-256: ${release.sha256}

1. Confirm this is Android TV / Google TV, Android 8.0 or newer, and supports sideloading. If it is not, stop and explain. Connect only to the TV address above; ask me to accept its debugging prompt. Do not scan other devices.
2. Find the APK I downloaded from the Sweep TV website. If it is missing, ask me to download it. Verify its checksum before installing. Back up the existing app/disabled-package list, current Home app, and relevant settings first. Do not print or collect API keys.
3. Install Sweep TV as an update if its package is already present, preserving app data. Keep my other launchers and streaming apps installed. Do not uninstall Talkies or anything else without asking.
4. Help me make the Home button work while keeping the stock launcher enabled. Test the normal Home preference first. Explain and ask before granting WRITE_SECURE_SETTINGS or Accessibility access. Do not change DNS, network, firmware, or unrelated settings.
5. Show me the home screen and verify recommendations and the F1 widget. Add my own TMDB key only through the TV's Keys and location flow; never ask me to paste it into chat. F1's schedule/results widget needs no API key.
${scope === 'review' ? '6. Review optional cleanup using CleanYourTV’s guarded tools and a device-specific profile. Produce a concrete package list with reasons and a recovery file, then ask me before disabling anything. Never apply a generic debloat list. Preserve streaming, Google services, voice, casting, updates, inputs, Bluetooth, remote and keyboard.' : '6. Launcher setup only. Do not disable or remove any TV packages.'}
7. Ask me to test the real remote, video playback, inputs and casting. Keep a recovery path. Ask whether I want debugging left enabled for this session before closing it.

This is a personal prototype. This candidate passed local checks but is not yet physically verified on the reference TV. Earlier builds were checked on one MiTV-MOOQ1 running Android TV 10. Test this exact build on this TV; do not infer verification from earlier versions.`;
}
$('#setup-form').addEventListener('submit', (event) => {
  event.preventDefault(); const model = $('#setup-model').value.replace(/\s+/g,' ').trim(); const host = address($('#setup-ip').value);
  const error = $('#setup-error'); error.hidden = true;
  if (!model || !host || !release) {
    error.textContent = !model ? 'Enter your TV brand and model.' : !host ? 'Enter a valid TV IP address, like 192.168.1.100. Add :port only if your TV shows a different debugging port.' : 'Preview details have not loaded. Reload this page before creating a setup prompt.';
    error.hidden = false; (!model ? $('#setup-model') : $('#setup-ip')).focus(); return;
  }
  const scope = new FormData(event.currentTarget).get('scope');
  $('#setup-prompt').textContent = buildPrompt(model, host, scope); $('#setup-form').hidden = true; $('#prompt-result').hidden = false;
  $('#copy-status').textContent = ''; $('#copy-prompt').focus();
});
$('#edit-setup').addEventListener('click', () => { $('#prompt-result').hidden = true; $('#setup-form').hidden = false; $('#setup-model').focus(); });
function toast(message) { clearTimeout(toastTimer); $('#toast').textContent = message; $('#toast').hidden = false; toastTimer = setTimeout(() => { $('#toast').hidden = true; }, 3500); }
async function copyText(value, status) {
  try { await navigator.clipboard.writeText(value); if (status) status.textContent = 'Copied. Paste it into your coding assistant.'; toast('Copied to clipboard'); }
  catch { if (status) status.textContent = 'Clipboard access is unavailable. Select the prompt above and copy it manually.'; toast('Please select and copy the text manually.'); }
}
$('#copy-prompt').addEventListener('click', () => copyText($('#setup-prompt').textContent, $('#copy-status')));
$('#copy-checksum').addEventListener('click', () => { if (release) copyText(release.sha256); });
fetch('release.json').then(response => { if (!response.ok) throw new Error('Release unavailable'); return response.json(); }).then(data => {
  if (!/^downloads\/sweep-tv-[\w.-]+\.apk$/.test(data.file) || !/^[a-f0-9]{64}$/.test(data.sha256)) throw new Error('Invalid release');
  release = data; $('#release-version').textContent = data.version;
  $('#release-size').textContent = data.bytes >= 1_000_000 ? `${(data.bytes / 1_000_000).toFixed(1)} MB` : `${Math.round(data.bytes / 1000)} KB`;
  $('#release-checks').textContent = `${data.tests} Android + ${data.gateTests} release checks`;
  $('#download-apk').href = data.file; $('#download-apk').download = data.file.split('/').pop(); $('#download-apk').removeAttribute('aria-disabled');
  $('#release-checksum').textContent = data.sha256;
  $('#release-stage').textContent = data.deviceVerified ? 'This build was checked on the reference TV; other devices require their own checks.' : `Build ${data.code}: title-cover artwork update. Local checks passed; installation and physical-TV verification are pending. Screenshots show earlier TV-tested builds. Last installed: ${data.previousTVVersion}.`;
}).catch(() => { $('#release-version').textContent = 'Release details unavailable'; $('#release-stage').textContent = 'Reload this page to restore the verified download link.'; });
