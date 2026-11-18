const search = document.querySelector('#device-search');
const list = document.querySelector('#device-list');
const count = document.querySelector('#device-count');

const statusLabels = {
  verified: 'Verified by us',
  community: 'Community reported',
  issue: 'Known issue',
};
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' });

function element(tag, text, className) {
  const node = document.createElement(tag);
  node.textContent = text;
  if (className) node.className = className;
  return node;
}

function deviceCard(device) {
  const card = element('article', '', 'tv-report');
  const header = element('div', '', 'tv-report-header');
  header.append(element('h2', `${device.brand} ${device.model}`), element('span', statusLabels[device.status], 'tv-report-status'));
  const facts = element('dl', '', 'tv-report-facts');
  for (const [label, value] of [
    ['OS', device.os],
    ['Firmware', device.firmware || 'Unrecorded'],
    ['SweepMyTV version', device.sweepVersion],
    ['Test date', dateFormat.format(new Date(device.tested))],
  ]) {
    const pair = document.createElement('div');
    pair.append(element('dt', label), element('dd', value));
    facts.append(pair);
  }

  card.append(header, facts, element('h3', 'Launcher test'), element('p', device.launcher));
  if (device.limitations) card.append(element('p', device.limitations, 'tv-report-note'));
  if (device.reportUrl) {
    const link = element('a', 'Read the test report ↗', 'tv-report-source');
    link.href = device.reportUrl;
    card.append(link);
  }
  if (device.cleanup || device.cleanupReference) {
    const cleanup = element('div', '', 'tv-report-cleanup');
    cleanup.append(element('h3', 'Optional cleanup'));
    if (device.cleanup) cleanup.append(element('p', device.cleanup));
    if (device.cleanupReference) {
      const link = element('a', 'CleanYourTV device profile ↗');
      link.href = device.cleanupReference;
      cleanup.append(link);
    }
    card.append(cleanup);
  }
  return card;
}

function normalize(value) {
  return value.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
}

async function showDirectory() {
  search.value = new URLSearchParams(location.search).get('q') || '';
  const response = await fetch('../tested-tvs.json');
  if (!response.ok) throw new Error('Device reports unavailable');
  const devices = await response.json();
  const entries = devices.map(device => ({
    card: deviceCard(device),
    keywords: normalize([device.brand, device.model, device.os, device.firmware, device.sweepVersion,
      device.cleanup, ...(device.aliases || [])].join(' ')),
  }));

  function render() {
    const words = search.value.split(/\s+/).map(normalize).filter(Boolean);
    const matches = entries.filter(entry => words.every(word => entry.keywords.includes(word)));
    count.textContent = matches.length === 1 ? '1 matching report' : `${matches.length} matching reports`;
    list.replaceChildren(...matches.map(entry => entry.card));
    if (!matches.length) {
      list.append(element('p', words.length ? 'No reviewed reports match this search. You can still check the setup guide or report your TV.'
        : 'Reviewed TV reports will appear here.', 'tv-report'));
    }
  }

  search.addEventListener('input', render);
  render();
}

showDirectory().catch(() => { count.textContent = 'Could not load the TV reports. Reload the page to try again.'; });
