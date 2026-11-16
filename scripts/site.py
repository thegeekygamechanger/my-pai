#!/usr/bin/env python3
"""Builds the website in docs/. The terms and privacy pages are taken from the app's own text
(app/src/main/java/app/pocketagent/ui/Docs.kt), so the site and the app never say different things."""
import html, os, re, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = 'https://github.com/thegeekygamechanger/pocket-agent'


def page(title, body, desc, script=''):
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{desc}">
<link rel="icon" href="icon.png">
<link rel="stylesheet" href="style.css">
<script>
// The colours behind the page follow the hour, as they do in the app.
(function () {{ var h = new Date().getHours(); document.documentElement.dataset.phase = h >= 5 && h <= 11 ? 'morning' : h >= 12 && h <= 16 ? 'day' : h >= 17 && h <= 20 ? 'evening' : 'night'; }})();
</script>
</head>
<body data-device="desktop">
<div class="wrap">
<header>
  <a class="mark" href="index.html"><img src="icon.png" alt="">Pocket Agent</a>
  <nav><a href="terms.html">Terms</a><a href="privacy.html">Privacy</a><a href="open-source.html">Open source</a><a class="pill" href="{REPO}/releases/latest">Download</a></nav>
</header>
{body}
<footer>
  <a href="terms.html">Terms</a><a href="privacy.html">Privacy</a><a href="open-source.html">Open source</a><a href="{REPO}">GitHub</a>
  <p>Pocket Agent is open-source software, not affiliated with Anthropic, OpenAI, Google or any other AI service. Their product names are trademarks of their owners.</p>
</footer>
</div>
{script}
</body>
</html>
'''


def shot(name, alt):
    """One picture of the product for each kind of device; the switch at the top shows one or the other."""
    return (f'<figure class="shot"><img class="desktop" src="shots/d-{name}.jpg" alt="{alt}, on a computer" loading="lazy">'
            f'<img class="android" src="shots/a-{name}.jpg" alt="{alt}, on a phone" loading="lazy"></figure>')


INDEX = f'''<section class="hero">
  <h1>AI that gets on with it.</h1>
  <p>Chat, or hand over a piece of work. Your own keys, on every device.</p>
  <div class="switch" role="tablist" aria-label="Show the app on">
    <span class="thumb"></span>
    <button role="tab" data-show="desktop">Desktop</button>
    <button role="tab" data-show="android">Android</button>
  </div>
  {shot('home', 'The first screen')}
</section>
<section class="feature">
  <div><h2>Ask anything</h2><p>Clear answers, with web search, images and PDFs.</p></div>
  {shot('chat', 'A chat')}
</section>
<section class="feature flip">
  <div><h2>Hand over the work</h2><p>It plans, uses files, the web, GitHub and Gmail, and asks before it changes anything.</p></div>
  {shot('work', 'A piece of work in progress')}
</section>
<section class="feature">
  <div><h2>Any model, your keys</h2><p>Claude, OpenAI, OpenRouter, Groq, Gemini and more. No account with us.</p></div>
  {shot('models', 'Choosing a model')}
</section>
<section class="grid">
  <div><h3>Every device</h3><p>Android, Windows and Linux, in step through encrypted sync.</p></div>
  <div><h3>Yours alone</h3><p>No ads, no tracking. Everything stays on your devices.</p></div>
  <div><h3>On a schedule</h3><p>Set a task once and let it run by itself.</p></div>
  <div><h3>Open source</h3><p>MIT licence. Read it, build it, change it.</p></div>
</section>'''

SWITCH = '''<script>
// Shows the computer or the phone pictures. The choice is remembered; a narrow screen starts on the phone.
(function () {
  var saved = null;
  try { saved = localStorage.getItem('device'); } catch (e) {}
  function show(device) {
    document.body.dataset.device = device;
    document.querySelectorAll('.switch button').forEach(function (b) { b.setAttribute('aria-selected', b.dataset.show === device); });
    try { localStorage.setItem('device', device); } catch (e) {}
  }
  document.querySelectorAll('.switch button').forEach(function (b) { b.addEventListener('click', function () { show(b.dataset.show); }); });
  show(saved || (window.matchMedia('(max-width: 700px)').matches ? 'android' : 'desktop'));
})();
</script>'''


def markdown(text):
    out, in_list = [], False

    def inline(s):
        return re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', html.escape(s, quote=False))

    for line in text.split('\n'):
        if line.startswith('- '):
            if not in_list:
                out.append('<ul>')
                in_list = True
            out.append('<li>' + inline(line[2:]) + '</li>')
            continue
        if in_list:
            out.append('</ul>')
            in_list = False
        if line.startswith('## '):
            out.append('<h2>' + inline(line[3:]) + '</h2>')
        elif line.strip():
            out.append('<p>' + inline(line) + '</p>')
    if in_list:
        out.append('</ul>')
    return '\n'.join(out)


def main():
    docs = os.path.join(ROOT, 'docs')
    src = open(os.path.join(ROOT, 'app/src/main/java/app/pocketagent/ui/Docs.kt')).read()

    def grab(name):
        return re.search(r'private val %s = """\n(.*?)\n""".trimIndent\(\)' % name, src, re.S).group(1)

    open(os.path.join(docs, 'index.html'), 'w').write(page('Pocket Agent', INDEX, 'Chat with AI or hand it work, on Android, Windows and Linux, with your own API keys. Free and open source.', SWITCH))
    open(os.path.join(docs, 'terms.html'), 'w').write(page('Terms of use · Pocket Agent', '<article><h1>Terms of use</h1>\n' + markdown(grab('TERMS_TEXT')) + '\n</article>', 'Terms of use for Pocket Agent.'))
    open(os.path.join(docs, 'privacy.html'), 'w').write(page('Privacy · Pocket Agent', '<article><h1>Privacy</h1>\n' + markdown(grab('PRIVACY_TEXT')) + '\n</article>', 'How Pocket Agent handles your data: on your device, encrypted, with no account and no tracking.'))
    open(os.path.join(docs, 'open-source.html'), 'w').write(page('Open source · Pocket Agent', '<article><h1>Open source</h1>\n' + markdown(grab('LICENSES_TEXT')) + '\n</article>', 'Pocket Agent is open source under the MIT licence.'))
    shutil.copy(os.path.join(ROOT, 'desktop/icons/icon.png'), os.path.join(docs, 'icon.png'))
    open(os.path.join(docs, '.nojekyll'), 'w').write('')


if __name__ == '__main__':
    main()
