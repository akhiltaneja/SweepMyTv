const $ = (selector) => document.querySelector(selector);

const compareRange = $('#comparison-range');
const comparison = $('#comparison-screen');

function updateComparison() {
  comparison.style.setProperty('--split', compareRange.value + '%');
  compareRange.setAttribute('aria-valuetext', compareRange.value + '% of the original home screen');
}
compareRange.addEventListener('input', updateComparison);
updateComparison();
compareRange.hidden = false;

function initWatchPreview() {
  const demo = $('.watch-demo');
  const providers = {
    hotstar: 'JioHotstar',
    'prime-video': 'Prime Video',
    'apple-tv': 'Apple TV',
    netflix: 'Netflix',
    zee5: 'ZEE5',
  };
  const titles = [
    ['modern-family', 'Modern Family', 'Series', 'hotstar', '8.5', 56],
    ['grand-tour', 'The Grand Tour', 'Series', 'prime-video', '8.7', 68],
    ['monarch', 'Monarch: Legacy of Monsters', 'Series', 'apple-tv', '7.0', 33],
    ['lupin', 'Lupin', 'Series', 'netflix', '7.5', 76],
    ['the-gorge', 'The Gorge', 'Film', 'apple-tv', '6.7', 47],
    ['study-group', 'Study Group', 'Series', 'prime-video', '8.2', 29],
    ['legend-of-hanuman', 'The Legend of Hanuman', 'Series', 'hotstar', '9.1', 64],
    ['silo', 'Silo', 'Series', 'apple-tv', '8.1', 22],
    ['gyaarah-gyaarah', 'Gyaarah Gyaarah', 'Series', 'zee5', '8.1', 41],
    ['october', 'October', 'Film', 'hotstar', '7.6', 57],
    ['stick', 'Stick', 'Series', 'apple-tv', '7.2', 36],
    ['slow-horses', 'Slow Horses', 'Series', 'apple-tv', '8.4', 74],
  ];
  const track = demo.querySelector('.watch-track');
  const title = demo.querySelector('[data-watch-title]');
  const details = demo.querySelector('[data-watch-details]');
  const status = demo.querySelector('[data-watch-status]');
  const cards = [...titles, ...titles, ...titles].map(([key, , , provider, rating, progress]) => {
    const card = document.createElement('div');
    card.className = 'watch-card';
    // These TV captures already contain the streaming app's icon.
    if (key === 'modern-family') card.classList.add('has-provider-icon');
    if (key === 'grand-tour') card.classList.add('has-portrait-provider-icon');
    for (const view of ['portrait', 'landscape']) {
      const image = document.createElement('img');
      image.className = 'watch-art watch-' + view;
      image.alt = '';
      image.loading = 'lazy';
      image.decoding = 'async';
      image.src = 'assets/watch-preview/' + key + '-' + view + '.webp';
      card.append(image);
    }
    const bar = document.createElement('span');
    bar.className = 'watch-progress';
    bar.style.setProperty('--watch-progress', progress + '%');
    const score = document.createElement('span');
    score.className = 'watch-rating';
    score.textContent = rating;
    const badge = document.createElement('img');
    badge.className = 'watch-provider-icon';
    badge.alt = '';
    badge.loading = 'lazy';
    badge.decoding = 'async';
    badge.src = 'assets/watch-preview/providers/' + provider + '.webp';
    card.append(bar, score, badge);
    return card;
  });
  track.replaceChildren(...cards);

  let index = titles.length;
  let canAnimate = false;
  let timer;
  let touchStart;
  function select(next, manual = false) {
    cards[index].classList.remove('is-current');
    index = next;
    cards[index].classList.add('is-current');
    track.style.setProperty('--watch-index', index);
    const position = index % titles.length;
    const [, name, type, provider, rating] = titles[position];
    title.textContent = name;
    details.textContent = type + ' · ' + providers[provider] + ' · IMDb ' + rating;
    if (manual) {
      status.textContent = [name, details.textContent, (position + 1) + ' of ' + titles.length].join(', ');
    }
  }
  function advance(direction, manual = false) {
    if (index < titles.length || index >= titles.length * 2) {
      // Reposition to the identical card in the middle copy before sliding again.
      track.classList.add('is-repositioning');
      select(titles.length + index % titles.length);
      track.getBoundingClientRect();
      track.classList.remove('is-repositioning');
    }
    select(index + direction, manual);
    if (manual) {
      clearTimeout(timer);
      timer = undefined;
      sync();
    }
  }
  function sync(animate = canAnimate) {
    canAnimate = animate;
    if (canAnimate && !demo.querySelector(':focus-visible')) {
      if (timer === undefined) timer = setTimeout(() => {
        timer = undefined;
        advance(1);
        sync();
      }, 2400);
    } else {
      clearTimeout(timer);
      timer = undefined;
    }
  }

  demo.querySelector('[data-watch-previous]').addEventListener('click', () => advance(-1, true));
  demo.querySelector('[data-watch-next]').addEventListener('click', () => advance(1, true));
  demo.addEventListener('keydown', event => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    advance(event.key === 'ArrowRight' ? 1 : -1, true);
  });
  const viewport = demo.querySelector('.watch-window');
  viewport.addEventListener('touchstart', event => { touchStart = event.touches[0]; }, {passive: true});
  viewport.addEventListener('touchend', event => {
    if (!touchStart) return;
    const dx = event.changedTouches[0].clientX - touchStart.clientX;
    const dy = event.changedTouches[0].clientY - touchStart.clientY;
    touchStart = undefined;
    if (Math.abs(dx) > 35 && Math.abs(dx) > Math.abs(dy)) advance(dx < 0 ? 1 : -1, true);
  }, {passive: true});
  viewport.addEventListener('touchcancel', () => { touchStart = undefined; });
  for (const event of ['focusin', 'focusout']) {
    demo.addEventListener(event, () => queueMicrotask(() => sync()));
  }
  select(index);
  demo.hidden = false;
  $('#watch-still').hidden = true;
  demo.closest('figure').querySelector('figcaption').textContent = 'Interactive preview · Sample titles and progress';
  return {demo, sync};
}

function initAerialPreview(motionPreference) {
  const scenes = [
    ["fjords", "Norwegian fjords", "https://www.pexels.com/video/epic-aerial-view-of-norwegian-fjords-and-cliffs-39925485/"],
    ["big-sur", "Big Sur coastline", "https://www.pexels.com/video/aerial-view-of-california-s-scenic-big-sur-coastline-30167141/"],
    ["autumn", "Autumn forest", "https://www.pexels.com/video/aerial-view-of-dense-forest-in-autumn-35893827/"],
    ["palawan", "Palawan lagoons", "https://www.pexels.com/video/stunning-aerial-view-of-lagoons-in-palawan-35218347/"],
    ["cappadocia", "Balloons over the mountains", "https://www.pexels.com/video/drone-footage-of-hot-air-balloons-flying-over-the-mountains-8207252/"],
    ["sunset", "Mountain sunset", "https://www.pexels.com/video/drone-shot-of-the-mountain-peak-during-sunset-4288053/"],
    ["new-york", "New York skyline", "https://www.pexels.com/video/drone-video-of-new-york-city-skyline-12122310/"],
    ["iceland", "Icelandic waterfall", "https://www.pexels.com/video/stunning-drone-view-of-waterfall-in-iceland-34476954/"],
    ["reef", "Coral reef", "https://www.pexels.com/video/school-of-fish-in-the-deep-blue-sea-5358755/"],
    ["forest-falls", "Forest waterfall", "https://www.pexels.com/video/aerial-view-of-waterfall-in-rainy-forest-5061380/"],
    ["desert", "Peruvian sand dunes", "https://www.pexels.com/video/aerial-view-of-sand-dunes-in-peruvian-desert-28916863/"],
    ["alps", "Snowy Alps", "https://www.pexels.com/video/breathtaking-aerial-drone-view-of-snowy-alps-35000595/"],
  ];
  const player = $('.aerial-player');
  const [first, second] = player.querySelectorAll('video');
  const controls = player.querySelector('.aerial-controls');
  const playButton = player.querySelector('[data-aerial-play]');
  const label = player.querySelector('[data-aerial-scene]');
  const credit = player.closest('figure').querySelector('[data-aerial-credit]');
  const failed = new Set();
  let active = first;
  let spare = second;
  let current;
  let prepared;
  let deck = [];
  let switching = false;
  let visible = false;
  let automatic = false;
  let manual = false;
  let blocked = false;

  const mayPlay = () => visible && (manual || automatic) && !blocked;

  function prepare() {
    if (prepared) return prepared;
    if (!deck.length) {
      deck = scenes.filter(scene => !failed.has(scene));
      for (let i = deck.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [deck[i], deck[j]] = [deck[j], deck[i]];
      }
      if (deck.length > 1 && deck.at(-1) === current) {
        [deck[0], deck[deck.length - 1]] = [deck.at(-1), deck[0]];
      }
    }
    prepared = deck.pop();
    if (prepared) {
      spare.muted = true;
      spare.preload = 'auto';
      spare.src = 'assets/aerial-preview/' + prepared[0] + '.mp4';
    }
    return prepared;
  }

  function showPlayButton() {
    blocked = true;
    manual = false;
    playButton.hidden = !visible;
  }

  async function nextScene() {
    if (switching || !mayPlay()) return;
    switching = true;
    try {
      while (mayPlay()) {
        const scene = prepare();
        if (!scene) {
          showPlayButton();
          return;
        }
        const incoming = spare;
        const deadline = setTimeout(() => incoming.pause(), 8000);
        try {
          await incoming.play();
        } catch (error) {
          if (!mayPlay()) return;
          if (error.name === 'NotAllowedError') {
            showPlayButton();
            return;
          }
          failed.add(scene);
          prepared = undefined;
          continue;
        } finally {
          clearTimeout(deadline);
        }
        if (!mayPlay()) {
          incoming.pause();
          return;
        }

        const outgoing = active;
        [active, spare] = [incoming, outgoing];
        current = scene;
        prepared = undefined;
        label.textContent = scene[1];
        credit.href = scene[2];
        credit.setAttribute('aria-label', scene[1] + ' footage on Pexels');
        controls.hidden = false;
        // Only the incoming layer fades. The outgoing frame stays opaque underneath.
        incoming.classList.add('is-incoming', 'is-visible');
        if (!motionPreference.matches) await new Promise(resolve => setTimeout(resolve, 550));
        outgoing.pause();
        outgoing.classList.remove('is-visible');
        incoming.classList.remove('is-incoming');
        if (mayPlay()) prepare();
        return;
      }
    } finally {
      switching = false;
    }
  }

  function sync(inView, canAnimate) {
    visible = inView;
    automatic = canAnimate && !navigator.connection?.saveData;
    playButton.hidden = !visible || mayPlay();
    if (!mayPlay()) {
      first.pause();
      second.pause();
      return;
    }
    if (switching) return;
    if (!current || active.ended) {
      nextScene();
    } else if (active.paused) {
      active.play().catch(() => { if (mayPlay()) showPlayButton(); });
    }
  }

  for (const video of [first, second]) {
    video.addEventListener('timeupdate', () => {
      if (video === active && video.duration - video.currentTime < .85) nextScene();
    });
    video.addEventListener('ended', () => { if (video === active) nextScene(); });
  }
  player.querySelector('[data-aerial-next]').addEventListener('click', nextScene);
  playButton.addEventListener('click', () => {
    if (failed.size) {
      failed.clear();
      deck = [];
    }
    blocked = false;
    manual = true;
    sync(visible, automatic);
  });
  return {player, sync};
}

const watchPreview = initWatchPreview();
const motionPreference = matchMedia('(prefers-reduced-motion: reduce)');
const aerialPreview = initAerialPreview(motionPreference);
const heroActions = $('.hero-actions');
const heroCtas = Array.from(heroActions.querySelectorAll('[data-hero-cta]'), (link, position) => {
  const label = link.querySelector('.hero-cta-text');
  const phrases = [label, ...link.querySelectorAll('.hero-cta-option')].map(span => span.textContent);
  label.parentElement.dataset.initialLabel = phrases[0];
  return { link, label, phrases, index: 0, length: phrases[0].length, deleting: true,
    firstPause: 2800 + position * 1700, timer: undefined };
});
const widgetShowcase = $('.widget-showcase');
const catalogMedia = $('.catalog-media');
const loopImages = new Map(Array.from(document.querySelectorAll('[data-loop]'), image => [
  image, { still: image.getAttribute('src'), visible: false },
]));
let heroActionsVisible = false;
let widgetVisible = false;
let catalogVisible = false;
let watchVisible = false;
let aerialVisible = false;

function typeHeroLabel(cta) {
  const phrase = cta.phrases[cta.index];
  cta.length += cta.deleting ? -1 : 1;
  cta.label.textContent = phrase.slice(0, cta.length);
  cta.link.classList.add('is-typing');
  let delay = cta.deleting ? 32 : 55;
  if (cta.length === 0) {
    cta.index = (cta.index + 1) % cta.phrases.length;
    cta.link.setAttribute('aria-label', cta.phrases[cta.index]);
    cta.deleting = false;
    delay = 145;
  } else if (cta.length === phrase.length) {
    cta.link.classList.remove('is-typing');
    cta.deleting = true;
    delay = 4200;
  }
  cta.timer = setTimeout(() => typeHeroLabel(cta), delay);
}

function syncHeroActions() {
  const animate = !motionPreference.matches && !document.hidden && heroActionsVisible;
  for (const cta of heroCtas) {
    if (animate && !cta.link.matches(':hover, :focus')) {
      if (cta.timer === undefined) cta.timer = setTimeout(() => typeHeroLabel(cta), cta.firstPause);
      continue;
    }
    clearTimeout(cta.timer);
    cta.timer = undefined;
    if (motionPreference.matches) cta.index = 0;
    const phrase = cta.phrases[cta.index];
    cta.label.textContent = phrase;
    cta.link.setAttribute('aria-label', phrase);
    cta.link.classList.remove('is-typing');
    cta.length = phrase.length;
    cta.deleting = true;
  }
}

function syncMotion() {
  const canAnimate = !motionPreference.matches && !document.hidden;
  for (const [image, state] of loopImages) {
    const src = canAnimate && state.visible ? image.dataset.loop : state.still;
    if (image.getAttribute('src') !== src) image.src = src;
  }
  widgetShowcase.classList.toggle('is-moving', canAnimate && widgetVisible);
  catalogMedia.classList.toggle('is-moving', canAnimate && catalogVisible);
  watchPreview.sync(canAnimate && watchVisible);
  aerialPreview.sync(!document.hidden && aerialVisible, canAnimate);
  syncHeroActions();
}
for (const {link} of heroCtas) {
  for (const event of ['mouseenter', 'mouseleave', 'focus', 'blur']) link.addEventListener(event, syncHeroActions);
}
motionPreference.addEventListener('change', syncMotion);
document.addEventListener('visibilitychange', syncMotion);
const observer = new IntersectionObserver(entries => {
  for (const {target, isIntersecting} of entries) {
    if (target === heroActions) heroActionsVisible = isIntersecting;
    else if (target === widgetShowcase) widgetVisible = isIntersecting;
    else if (target === catalogMedia) catalogVisible = isIntersecting;
    else if (target === watchPreview.demo) watchVisible = isIntersecting;
    else if (target === aerialPreview.player) aerialVisible = isIntersecting;
    else loopImages.get(target).visible = isIntersecting;
  }
  syncMotion();
}, {threshold: 0.15});
for (const image of loopImages.keys()) observer.observe(image);
observer.observe(heroActions);
observer.observe(widgetShowcase);
observer.observe(catalogMedia);
observer.observe(watchPreview.demo);
observer.observe(aerialPreview.player);
syncMotion();

const engineVersion = $('[data-engine-version]');
fetch('recommendation-engine.json').then(response => {
  if (!response.ok) throw new Error('Engine versions unavailable');
  return response.json();
}).then(({releases}) => {
  const current = releases?.[0];
  if (current && /^v\d+(?:\.\d+){0,2}$/.test(current.version) && ['Beta', 'Stable'].includes(current.stage)) {
    engineVersion.textContent = current.stage === 'Stable' ? current.version.toUpperCase() : `${current.stage} ${current.version.toUpperCase()}`;
  }
}).catch(() => { /* The homepage includes the current version for offline and no-script use. */ });

for (const button of document.querySelectorAll('[data-enlarge]')) button.addEventListener('click', () => {
  const screenshot = button.querySelector('img');
  $('#dialog-image').src = screenshot.currentSrc || screenshot.src;
  $('#dialog-image').alt = screenshot.alt;
  $('#preview-dialog-title').textContent = button.dataset.title;
  $('#dialog-note').textContent = button.dataset.note || 'Captured on our Redmi TV';
  button.focus({preventScroll: true});
  $('#preview-dialog').showModal();
});

for (const button of document.querySelectorAll('[data-close-dialog]')) button.addEventListener('click', () => button.closest('dialog').close());
for (const dialog of document.querySelectorAll('dialog')) dialog.addEventListener('click', (event) => {
  if (event.target !== dialog) return; const r = dialog.getBoundingClientRect();
  if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) dialog.close();
});
