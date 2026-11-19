'use strict';

const providerNames = {
  netflix: 'Netflix', prime: 'Prime Video', 'apple-tv-plus': 'Apple TV', jiohotstar: 'JioHotstar',
  'sony-liv': 'SonyLIV', zee5: 'ZEE5',
};
const sections = {trending: 'Trending', new: 'Recently Released', provider: 'App recommendations'};
const mainProviders = ['netflix', 'prime', 'apple-tv-plus', 'jiohotstar'];
const languageNames = new Intl.DisplayNames(['en'], {type: 'language'});
const fmtDate = new Intl.DateTimeFormat('en-IN', {dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata'});
const perPage = 12;
const $ = id => document.getElementById(id);
let catalog = null;
let page = 0;
let tmdbRequest = 0;
const sectionViews = {trending: 'trending', recent: 'new'};
let sectionScope = '';

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function language(code) {
  return code ? languageNames.of(code) : 'Unknown';
}

function date(value) {
  return value ? `${fmtDate.format(new Date(value))} IST` : '—';
}

function link(href, text) {
  const a = element('a', text);
  a.href = href;
  a.target = '_blank';
  a.rel = 'noopener noreferrer';
  return a;
}

function image(src, className) {
  const img = element('img', null, className);
  img.alt = '';
  img.loading = 'lazy';
  img.decoding = 'async';
  img.referrerPolicy = 'no-referrer';
  img.addEventListener('error', () => {
    img.removeAttribute('src');
    img.classList.add('unavailable-poster');
  }, {once: true});
  if (src) img.src = src;
  else img.classList.add('unavailable-poster');
  return img;
}

function detailList(container, values) {
  container.replaceChildren();
  for (const [label, value] of values) {
    const content = element('dd');
    content.append(value || '—');
    container.append(element('dt', label), content);
  }
}

function emptyRow(body, message, columns) {
  const row = element('tr');
  const cell = element('td', message, 'empty');
  cell.colSpan = columns;
  row.append(cell);
  body.append(row);
}

function configureOptions(select, values, label) {
  const selected = select.value;
  select.replaceChildren(select.options[0]);
  for (const value of values) {
    const option = element('option', label(value));
    option.value = value;
    select.append(option);
  }
  select.value = values.includes(selected) ? selected : '';
}

function matches(row) {
  const query = $('search').value.trim().toLowerCase();
  const inclusion = $('feed-inclusion').value;
  const matchesInclusion = {
    included: row.in_feed,
    eligible: (row.eligible ?? row.in_feed) && !row.section_edit?.hidden,
    excluded: !row.in_feed,
    hidden: row.section_edit?.hidden,
  };
  return (!query || `${row.title} ${row.id}`.toLowerCase().includes(query))
    && (!$('provider').value || row.provider === $('provider').value)
    && (!$('language').value || (row.language || 'unknown') === $('language').value)
    && (!sectionScope || row.row === sectionScope)
    && (!$('row').value || row.row === $('row').value)
    && (!inclusion || matchesInclusion[inclusion])
    && (!$('link').value || Boolean(row.link) === ($('link').value === 'linked'));
}

function renderTable() {
  if (!catalog) return;
  const rows = catalog.titles.filter(matches);
  if (sectionScope) rows.sort((a, b) => (a.section_position ?? Infinity) - (b.section_position ?? Infinity));
  const body = $('titles');
  body.replaceChildren();
  const pages = Math.max(1, Math.ceil(rows.length / perPage));
  page = Math.min(page, pages - 1);
  $('result-count').textContent = `${rows.length} ${rows.length === 1 ? 'entry' : 'entries'}`;
  $('page-status').textContent = `Page ${page + 1} of ${pages}`;
  $('previous').disabled = page === 0;
  $('next').disabled = page === pages - 1;
  if (!rows.length) emptyRow(body, 'No titles match these filters.', 6);
  for (const data of rows.slice(page * perPage, (page + 1) * perPage)) {
    const row = element('tr');
    const title = element('td');
    const button = element('button', null, 'title-button');
    const labels = element('span');
    labels.append(element('span', data.title, 'title-name'), element('small', `${data.kind === 'tv' ? 'Series' : 'Film'}${data.year ? ` · ${data.year}` : ''}`));
    button.append(image(data.poster, 'poster'), labels);
    button.addEventListener('click', () => openDetails(data));
    title.append(button);
    const placement = data.in_feed ? `${sections[data.row]} · #${data.section_position}`
      : `${sections[data.row]} · ${listingStatus(data)}`;
    row.append(title, element('td', providerNames[data.provider]), element('td', language(data.language)),
      element('td', placement, 'muted'), element('td', data.rating ? `${data.rating.toFixed(1)} IMDb` : '—', 'muted'));
    const availability = element('td');
    availability.append(element('span', offerStatus(data), `indicator ${data.in_feed ? 'good' : 'neutral'}`));
    row.append(availability);
    body.append(row);
  }
}

function listingStatus(data) {
  if (data.section_edit?.hidden) return 'Hidden';
  return data.eligible ? 'Eligible' : 'Withheld';
}

function offerStatus(data) {
  if (!data.link) return 'Missing link';
  if (data._netflix_english) {
    return data._netflix_access === 'included' ? 'Included with Netflix' : 'Netflix India access unconfirmed';
  }
  if (data.provider === 'apple-tv-plus') {
    return data._apple_access === 'included' ? 'Included with Apple TV' : 'Apple TV access unconfirmed';
  }
  if (data.provider !== 'prime') return 'Title link';
  if (data._prime_access === 'paid') return 'Rent or buy';
  if (data._prime_access === 'included' || data.in_feed) return 'Included with Prime';
  return 'Prime access unconfirmed';
}

function openDetails(data) {
  const top = element('div', null, 'detail-top');
  const info = element('div');
  const title = element('h2', data.title);
  title.id = 'detail-title';
  info.append(title, element('p', `${data.kind === 'tv' ? 'Series' : 'Film'} · ${language(data.language)}${data.released ? ` · ${data.released}` : ''}`));
  if (data.summary) info.append(element('p', data.summary, 'summary'));
  top.append(image(data.poster), info);
  const details = element('dl', null, 'details-grid');
  detailList(details, [
    ['Sweep TV ID', String(data.title_id)], ['Source ID', data.id], ['Rating', data.rating ? `IMDb ${data.rating.toFixed(1)} / 10` : '—'],
    ['Cast', data.cast], ['Observed', date(data.observed_at)], ['Catalog source', data.source],
    ...(data._just_in_date ? [['StreamRadar Just In date', data._just_in_date]] : []),
    ['Synopsis and cast source', data._metadata_tmdb_id
      ? link(`https://www.themoviedb.org/${data.kind}/${data._metadata_tmdb_id}`,
        data._apple_original ? 'TMDB · Apple title matched' : 'TMDB · IMDb ID and chart artwork matched')
      : data._metadata_issue || 'See catalog source'],
    ...(data.supplement_expires ? [['Supplement expires', date(data.supplement_expires)]] : []),
    ['Portrait artwork', data.poster ? link(data.poster, new URL(data.poster).hostname) : '—'],
    ['Wide artwork', data.background ? link(data.background, data._metadata_issue ? 'Unverified source artwork' : new URL(data.background).hostname) : '—'],
    ['TV feed', data.in_feed ? `${sections[data.row]} · #${data.section_position}` : listingStatus(data)],
  ]);
  const offers = element('ul', null, 'detail-offers');
  for (const item of catalog.titles.filter(row => row.title_id === data.title_id)) {
    const line = element('li');
    line.append(element('span', `${providerNames[item.provider]} · ${sections[item.row]} · ${item.in_feed ? `#${item.section_position} in feed` : 'Withheld'}`),
      item.link ? link(item.link, 'Open title link') : element('span', 'No title link', 'muted'));
    offers.append(line);
  }
  $('detail-content').replaceChildren(top, details, element('h3', 'India listings', 'detail-heading'), offers);
  if (data.provider === 'prime') {
    const access = element('p', offerStatus(data), 'muted');
    if (data._prime_access === 'included') access.append(' · India availability by ', link('https://www.justwatch.com/in', 'JustWatch'));
    info.append(access);
  }
  if (data.row === 'trending' || data.row === 'new') $('detail-content').append(sectionForm(data));
  if (data.in_feed) $('detail-content').append(editorialForm(data));
  $('detail').showModal();
}

function sectionForm(data) {
  const form = element('form', null, 'editorial-form');
  const hidden = element('input');
  hidden.type = 'checkbox';
  hidden.checked = Boolean(data.section_edit?.hidden);
  const hideLabel = element('label', `Hide from ${sections[data.row]}`, 'inline-check');
  hideLabel.append(hidden);
  const position = element('input');
  position.type = 'number';
  position.min = 1;
  position.max = 192;
  position.value = data.section_edit?.position ?? '';
  position.placeholder = 'Default order';
  const positionLabel = element('label', 'Position in section');
  positionLabel.append(position);
  const save = element('button', 'Save section changes', 'secondary');
  const status = element('p', null, 'muted');
  status.setAttribute('role', 'status');
  form.append(element('h3', sections[data.row], 'detail-heading'), hideLabel, positionLabel, save, status);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    save.disabled = true;
    try {
      const response = await fetch('/api/section-edit', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({kind: data.kind, id: data.id, provider: data.provider, row: data.row,
          hidden: hidden.checked, position: position.valueAsNumber || null}),
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || 'Could not save the section changes');
      status.textContent = 'Section changes saved.';
      await load();
    } catch (error) {
      status.textContent = error.message;
    } finally {
      save.disabled = false;
    }
  });
  return form;
}

function editorialForm(data) {
  const form = element('form', null, 'editorial-form');
  const fields = {};
  form.append(element('h3', 'Sweep TV picks', 'detail-heading'));
  for (const [key, label, type] of [
    ['rank', 'Our Recommendations position (1–10)', 'number'],
    ['tmdb_id', 'TMDB ID for this title', 'number'],
    ['poster', 'Portrait artwork URL', 'url'],
    ['background', 'Wide artwork URL', 'url'],
  ]) {
    const row = element('label', label);
    const input = element('input');
    input.type = type;
    input.value = data.editorial?.[key] ?? '';
    if (type === 'number') { input.min = 1; input.step = 1; }
    if (key === 'rank') input.max = 10;
    fields[key] = input;
    row.append(input);
    form.append(row);
  }
  const save = element('button', 'Save to private feed', 'secondary');
  const status = element('p', null, 'muted');
  status.setAttribute('role', 'status');
  form.append(save, status);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    save.disabled = true;
    status.textContent = 'Checking the edit…';
    try {
      const response = await fetch('/api/editorial', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          kind: data.kind, id: data.id, rank: fields.rank.valueAsNumber || null,
          tmdb_id: fields.tmdb_id.valueAsNumber || null, poster: fields.poster.value.trim(),
          background: fields.background.value.trim(),
        }),
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || 'Could not save the edit');
      status.textContent = 'Saved to the private feed.';
      await load();
    } catch (error) {
      status.textContent = error.message;
    } finally {
      save.disabled = false;
    }
  });
  return form;
}

function renderMetrics(state) {
  const english = new Set(state.titles.filter(row => row.language === 'en').map(row => row.title_id));
  const linked = new Set(state.titles.filter(row => row.link).map(row => row.title_id));
  const values = [['Distinct titles', state.unique_titles], ['English titles', english.size],
    ['Streaming apps', new Set(state.titles.map(row => row.provider)).size], ['Titles with links', linked.size]];
  $('metrics').replaceChildren(...values.map(([label, value]) => {
    const card = element('article', null, 'metric');
    card.append(element('span', label), element('strong', value));
    return card;
  }));
  $('target-caption').textContent = `Catalog totals and TV feed coverage · Target ${state.target} per major app`;
  $('coverage').replaceChildren(...mainProviders.map(provider => {
    const {titles, links} = state.coverage[provider];
    const tvCount = state.tv_coverage[provider].titles;
    const bar = element('progress');
    bar.max = state.target;
    bar.value = Math.min(tvCount, state.target);
    bar.setAttribute('aria-label', `${providerNames[provider]} English TV feed coverage`);
    const panel = element('div', null, 'provider-count');
    const count = element('strong', `${titles} `);
    count.append(element('small', `· ${links} with links`));
    panel.append(element('span', providerNames[provider]), count, bar, element('span', `${tvCount} in the TV feed`));
    return panel;
  }));
}

function renderRuns(state) {
  $('schedule').textContent = state.next_refresh ? `Next refresh ${date(state.next_refresh)}. The Mac and dashboard server must stay running.` : 'Automatic refresh is paused for this dashboard session.';
  const body = $('run-rows');
  body.replaceChildren();
  if (!state.runs.length) emptyRow(body, 'No refresh runs yet.', 5);
  for (const run of state.runs) {
    const tr = element('tr');
    const status = element('td');
    const labels = {success: 'Accepted', failed: 'Rejected', running: 'Running'};
    status.append(element('span', labels[run.status], `indicator ${run.status === 'success' ? 'good' : 'warn'}`));
    tr.append(element('td', date(run.started_at)), element('td', run.source), status,
      element('td', run.entries ?? '—'), element('td', run.detail || '—', 'muted'));
    body.append(tr);
  }
}

function renderIssues(state) {
  const body = $('issue-rows');
  body.replaceChildren();
  if (!state.issues.length) emptyRow(body, 'No listing issues recorded.', 6);
  for (const issue of state.issues) {
    const row = element('tr');
    const title = element('td', issue.title);
    title.append(element('div', `${issue.external_id} · ${sections[issue.section]}`, 'muted'));
    const status = issue.resolved_at ? `Resolved ${date(issue.resolved_at)}` : 'Withheld';
    row.append(title, element('td', providerNames[issue.provider]), element('td', issue.reason),
      element('td', date(issue.first_seen)), element('td', date(issue.last_seen)), element('td', status));
    body.append(row);
  }
}

function renderFeed(state) {
  const snapshot = state.snapshot;
  document.querySelector('.feed-panel a').hidden = !snapshot;
  detailList($('feed-details'), [
    ['Status', snapshot ? 'Validated' : 'No accepted catalog'], ['Region', 'India'],
    ['TV listings', String(state.tv_entries)], ['Catalog listings', String(state.entries)],
    ['File size', snapshot ? `${(snapshot.bytes / 1024).toFixed(1)} KiB` : '—'],
    ['Last accepted', date(snapshot?.finished_at)], ['SHA-256', snapshot?.checksum],
  ]);
}

async function loadTmdb() {
  const requestId = ++tmdbRequest;
  const body = $('tmdb-results');
  const caption = $('tmdb-caption');
  const count = $('tmdb-count');
  count.textContent = 'Searching…';
  try {
    const params = new URLSearchParams({q: $('tmdb-query').value, kind: $('tmdb-kind').value});
    const response = await fetch(`/api/tmdb-index?${params}`);
    if (!response.ok) throw new Error('Index unavailable');
    const state = await response.json();
    if (requestId !== tmdbRequest) return;

    caption.textContent = state.export_date ? `Private snapshot from ${state.export_date}` : 'No TMDB snapshot has been imported.';
    const movies = state.counts.movie ?? 0;
    const tv = state.counts.tv ?? 0;
    $('tmdb-metrics').replaceChildren(...[['All titles', movies + tv], ['Movies', movies], ['TV series', tv]].map(([label, value]) => {
      const card = element('article', null, 'metric');
      card.append(element('span', label), element('strong', value.toLocaleString('en-IN')));
      return card;
    }));
    const found = state.titles.length;
    count.textContent = state.has_more ? 'Top 50 results' : `${found} ${found === 1 ? 'result' : 'results'}`;
    body.replaceChildren();
    if (!state.titles.length) emptyRow(body, 'No titles match this search.', 4);
    for (const item of state.titles) {
      const tr = element('tr');
      const title = element('td');
      title.append(link(`https://www.themoviedb.org/${item.kind}/${item.tmdb_id}`, item.name || 'Untitled'));
      tr.append(title, element('td', item.kind === 'movie' ? 'Film' : 'Series'),
        element('td', item.tmdb_id), element('td', item.popularity.toFixed(1)));
      body.append(tr);
    }
  } catch {
    if (requestId !== tmdbRequest) return;
    count.textContent = 'Search unavailable';
    body.replaceChildren();
    emptyRow(body, 'Could not load the TMDB index.', 4);
  }
}

async function load() {
  $('reload').disabled = true;
  $('notice').hidden = true;
  try {
    const response = await fetch('/api/catalog');
    if (!response.ok) throw new Error('Could not read catalog');
    catalog = await response.json();
    renderWorkspace();
    if (catalog.runs[0]?.status === 'failed' || catalog.open_issues) {
      const messages = [];
      if (catalog.runs[0]?.status === 'failed') messages.push(`Latest refresh was rejected. ${catalog.runs[0].detail}.`);
      if (catalog.open_issues) messages.push(`${catalog.open_issues} open listing issues. Details in Listing issues.`);
      $('notice').textContent = messages.join(' ');
      $('notice').hidden = false;
    }
    configureOptions($('provider'), [...new Set(catalog.titles.map(row => row.provider))].sort(), key => providerNames[key]);
    configureOptions($('language'), [...new Set(catalog.titles.map(row => row.language || 'unknown'))].sort(), code => language(code === 'unknown' ? '' : code));
    renderMetrics(catalog);
    renderTable();
    renderRuns(catalog);
    renderIssues(catalog);
    renderFeed(catalog);
  } catch {
    $('notice').textContent = 'The catalog server could not be reached. Try Refresh view.';
    $('notice').hidden = false;
  } finally {
    $('reload').disabled = false;
  }
}

const navigation = [...document.querySelectorAll('nav [data-view]')];

function renderWorkspace() {
  $('workspace-title').textContent = sections[sectionScope] || 'India catalog';
  $('catalog-caption').textContent = sectionScope
    ? 'Listings in TV feed order. Open a title to change its position or hide it.'
    : catalog?.snapshot ? `Last accepted ${date(catalog.snapshot.finished_at)}` : 'Import an India catalog to begin.';
  for (const id of ['metrics', 'coverage-panel', 'catalog-index-link', 'row']) $(id).hidden = Boolean(sectionScope);
}

function showView() {
  const active = navigation.find(link => link.hash === location.hash) ?? navigation[0];
  for (const link of navigation) {
    if (link === active) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
  const current = active.dataset.view;
  const previousScope = sectionScope;
  sectionScope = sectionViews[current] || '';
  const panel = sectionScope ? 'catalog' : current;
  document.querySelectorAll('.view').forEach(view => { view.hidden = view.id !== panel; });
  if (sectionScope !== previousScope) {
    page = 0;
    $('row').value = '';
    $('feed-inclusion').value = sectionScope ? 'included' : '';
  }
  renderWorkspace();
  $('breadcrumb').textContent = active.textContent.trim().replace(/^[^A-Za-z]+/, '');
  if (current === 'tmdb') {
    $('notice').hidden = true;
    loadTmdb();
  } else if (!catalog) {
    load();
  } else if (panel === 'catalog') {
    renderTable();
  }
}

window.addEventListener('hashchange', showView);
for (const id of ['search', 'provider', 'language', 'row', 'link', 'feed-inclusion']) {
  $(id).addEventListener(id === 'search' ? 'input' : 'change', () => { page = 0; renderTable(); });
}
$('previous').addEventListener('click', () => { page--; renderTable(); });
$('next').addEventListener('click', () => { page++; renderTable(); });
$('close-detail').addEventListener('click', () => $('detail').close());
$('reload').addEventListener('click', () => $('tmdb').hidden ? load() : loadTmdb());
$('tmdb-search-form').addEventListener('submit', event => {
  event.preventDefault();
  loadTmdb();
});
$('tmdb-kind').addEventListener('change', loadTmdb);
showView();
