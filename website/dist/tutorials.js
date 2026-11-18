for (const block of document.querySelectorAll('.tutorial-article pre:not([data-prompt-preview])')) {
  if (!navigator.clipboard?.writeText) continue;
  const button = document.createElement('button');
  const status = document.createElement('span');
  const controls = document.createElement('div');
  controls.className = 'code-actions';
  button.type = 'button';
  button.className = 'button outline';
  button.textContent = 'Copy';
  status.setAttribute('role', 'status');
  button.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(block.querySelector('code').textContent);
      status.textContent = 'Copied';
    } catch {
      status.textContent = 'Select the text to copy it manually.';
    }
  });
  controls.append(button, status);
  block.after(controls);
}

for (const container of document.querySelectorAll('[data-prompt-source]')) {
  const button = container.querySelector('[data-copy-prompt]');
  const preview = container.querySelector('[data-prompt-preview]');
  const status = container.querySelector('[data-prompt-status]');

  fetch(container.dataset.promptSource)
    .then(response => {
      if (!response.ok) throw new Error('Setup prompt unavailable');
      return response.text();
    })
    .then(prompt => {
      if (preview) {
        preview.querySelector('code').textContent = prompt;
        preview.hidden = false;
      }
      if (!navigator.clipboard?.writeText) return;
      button.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(prompt);
          status.textContent = 'Copied. Paste it into your AI assistant.';
        } catch {
          status.textContent = 'Open the prompt to copy it manually.';
        }
      });
      button.hidden = false;
    })
    .catch(() => { status.textContent = 'The setup prompt could not load. Try the prompt link.'; });
}
