const provider = document.querySelector('#sample-provider');
const language = document.querySelector('#sample-language');
const reset = document.querySelector('#reset-sample');
const status = document.querySelector('#sample-status');
const grid = document.querySelector('#sample-titles');
const providers = {netflix: 'Netflix', prime: 'Prime Video', 'apple-tv-plus': 'Apple TV', jiohotstar: 'JioHotstar', zee5: 'ZEE5'};
const languages = {en: 'English', hi: 'Hindi', ko: 'Korean', ta: 'Tamil'};
let titles = [];

function https(value) {
  try { const url = new URL(value); return url.protocol === 'https:' ? url.href : null; }
  catch { return null; }
}

function render() {
  const matching = titles.filter(title => (provider.value === 'all' || title.provider === provider.value)
    && (language.value === 'all' || title.language === language.value));
  const visible = matching.slice(0, 6);
  grid.replaceChildren();
  for (const title of visible) {
    const card = document.createElement('li');
    const image = document.createElement('img');
    image.src = title.poster;
    image.alt = `${title.title} poster`;
    image.width = 200;
    image.height = 300;
    image.loading = 'lazy';
    image.decoding = 'async';
    image.addEventListener('error', () => {
      image.src = '../assets/sweep.svg';
      image.alt = 'SweepTV';
      image.style.objectFit = 'contain';
      image.style.padding = '35px';
    }, {once: true});
    const heading = document.createElement('h4');
    const link = document.createElement('a');
    link.textContent = title.title;
    link.href = title.link;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.setAttribute('aria-label', `${title.title} on ${providers[title.provider]} (opens in a new tab)`);
    heading.append(link);
    const detail = document.createElement('p');
    detail.textContent = `${providers[title.provider]} · ${languages[title.language]}`;
    const rating = document.createElement('p');
    rating.textContent = `IMDb ${title.rating.toFixed(1)}${title.year ? ' · ' + title.year : ''}`;
    card.append(image, heading, detail, rating);
    grid.append(card);
  }
  status.textContent = matching.length
    ? `Showing ${visible.length} of ${matching.length} matching sample titles.`
    : 'No titles in this small sample match those choices. Your TV catalogue may have more titles.';
}

document.querySelector('.engine-filter-controls').addEventListener('submit', event => event.preventDefault());
provider.addEventListener('change', render);
language.addEventListener('change', render);
reset.addEventListener('click', () => { provider.value = 'netflix'; language.value = 'en'; render(); });

fetch('examples.json').then(response => {
  if (!response.ok) throw new Error('Sample unavailable');
  return response.json();
}).then(data => {
  if (!Array.isArray(data.titles)) throw new Error('Sample unavailable');
  titles = data.titles.filter(title => title && typeof title.title === 'string'
    && providers[title.provider] && languages[title.language]
    && typeof title.rating === 'number' && title.rating > 0 && title.rating <= 10
    && https(title.poster) && https(title.link));
  provider.disabled = language.disabled = reset.disabled = false;
  render();
}).catch(() => { status.textContent = 'The sample is temporarily unavailable. You can still explore how the engine works on this page.'; });

fetch('../recommendation-engine.json').then(response => {
  if (!response.ok) throw new Error('Version unavailable');
  return response.json();
}).then(({releases}) => {
  const current = releases?.[0];
  if (!current || !/^v\d+(?:\.\d+){0,2}$/.test(current.version) || !['Beta', 'Stable'].includes(current.stage)) return;
  const label = current.stage === 'Stable' ? current.version.toUpperCase() : `${current.stage} ${current.version.toUpperCase()}`;
  for (const badge of document.querySelectorAll('[data-engine-version]')) badge.textContent = label;
}).catch(() => { /* Static version badges remain available. */ });
