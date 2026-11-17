#!/usr/bin/env python3
"""Builds the website in docs/. The terms and privacy pages are taken from the app's own text
(app/src/main/java/app/pocketagent/ui/Docs.kt), so the site and the app never say different things."""
import html, os, re, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = 'https://github.com/thegeekygamechanger/my-pai'


SITE = 'https://thegeekygamechanger.github.io/my-pai/'

# What search engines read about the app itself: name, kind, price and where it runs.
APP_DATA = '''<script type="application/ld+json">
{"@context":"https://schema.org","@type":"SoftwareApplication","name":"My PAI","applicationCategory":"ProductivityApplication","operatingSystem":"Android, Windows, Linux","description":"Free, open-source AI chat and agent app that runs on your own API keys. No ads, no subscription, no account.","offers":{"@type":"Offer","price":"0","priceCurrency":"USD"},"license":"https://opensource.org/licenses/MIT","url":"https://thegeekygamechanger.github.io/my-pai/","downloadUrl":"https://github.com/thegeekygamechanger/my-pai/releases/latest","codeRepository":"https://github.com/thegeekygamechanger/my-pai"}
</script>
'''


def page(title, body, desc, script='', name='', extra=''):
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{desc}">
<link rel="canonical" href="{SITE}{name}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="My PAI">
<meta property="og:title" content="{title}">
<meta property="og:description" content="{desc}">
<meta property="og:url" content="{SITE}{name}">
<meta property="og:image" content="{SITE}shots/social.jpg">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="{title}">
<meta name="twitter:description" content="{desc}">
<meta name="twitter:image" content="{SITE}shots/social.jpg">
{extra}<link rel="icon" href="icon.png">
<link rel="stylesheet" href="style.css">
<script>
// The colours behind the page follow the hour, as they do in the app.
(function () {{ var h = new Date().getHours(); document.documentElement.dataset.phase = h >= 5 && h <= 11 ? 'morning' : h >= 12 && h <= 16 ? 'day' : h >= 17 && h <= 20 ? 'evening' : 'night'; }})();
</script>
</head>
<body data-device="desktop">
<div class="wrap">
<header>
  <a class="mark" href="index.html"><img src="icon.png" alt="">My PAI</a>
  <nav><a href="terms.html">Terms</a><a href="privacy.html">Privacy</a><a href="open-source.html">Open source</a><a class="pill" href="{REPO}/releases/latest">Download</a></nav>
</header>
{body}
<footer>
  <a href="whats-new.html">What's new</a><a href="compare.html">How it compares</a><a href="terms.html">Terms</a><a href="privacy.html">Privacy</a><a href="open-source.html">Open source</a><a href="{REPO}/blob/main/CONTRIBUTING.md">Contribute</a><a href="{REPO}">GitHub</a>
  <p>My PAI is open-source software, not affiliated with Anthropic, OpenAI, Google or any other AI service. Their product names are trademarks of their owners.</p>
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


def clip(name, alt, wide=False):
    """A short film of one feature, made from real screens of the app (scripts/clips.py). It plays by itself, silently, while it is on screen."""
    return (f'<figure class="clip{" wide" if wide else ""}"><video data-auto muted loop playsinline preload="none" poster="clips/{name}.jpg" aria-label="{alt}">'
            f'<source src="clips/{name}.mp4" type="video/mp4"></video></figure>')


def feature(title, text, name, alt, flip=False, wide=False, more=''):
    return (f'<section class="feature{" flip" if flip else ""}">\n  <div class="words"><h2>{title}</h2><p>{text}</p>{more}</div>\n  {clip(name, alt, wide)}\n</section>')


INDEX = f'''<section class="hero">
  <h1>Your personal AI that gets on with it.</h1>
  <p>My PAI: chat, or hand over a piece of work. Your own keys, on every device.</p>
  <div class="switch" role="tablist" aria-label="Show the app on">
    <span class="thumb"></span>
    <button role="tab" data-show="desktop">Desktop</button>
    <button role="tab" data-show="android">Android</button>
  </div>
  {shot('home', 'The first screen')}
</section>
<section class="film">
  <h2>Everything new, in a minute</h2>
  <p>MCP servers, approvals you can see through, undo, a shield, routines and your own devices working together.</p>
  <figure class="reel"><video src="reel.mp4" poster="shots/reel.jpg" controls muted playsinline preload="none" aria-label="A film of what is new in My PAI"></video></figure>
  <a class="more" href="whats-new.html">Read what changed in each version</a>
</section>
{feature('Ask anything', 'Clear answers, with web search, images and PDFs.', 'ask', 'A question answered in a chat')}
{feature('Hand over the work', 'It plans, writes files you can read as they grow, and asks before it changes anything.', 'work', 'A file being written, then listed in Files', flip=True)}
{feature('MCP servers', 'Add tools from other services, then type @mcp in any chat. Every call asks you first.', 'mcp', 'Picking an MCP server with @mcp and approving its call')}
{feature('You stay in control', 'See what a step will change before it runs. Allow it once or for the task. Undo a file change with one tap.', 'control', 'An approval that says what will change, then Undo', flip=True)}
{feature('A shield and a log', 'Text that tries to give the assistant orders is flagged, never obeyed. Every tool call is listed, with what it cost.', 'shield', 'A planted instruction flagged in the chat, and the activity log')}
{feature('Routines', 'Chain steps into a task that runs on a schedule, or whenever you start it.', 'routines', 'A routine with two steps, running', flip=True)}
{feature('Any model, a backup, offline', 'Claude, OpenAI, Gemini, Ollama and more. A backup model takes over when one fails. Offline mode keeps it all local.', 'models', 'Choosing a model, a backup and offline mode')}
{feature('Your devices, together', 'Lend your computer\'s tools to your phone, share a setup with a code, or let other agents read My PAI. All off until you turn them on.', 'devices', 'Lending tools, and another program asking to read a file', flip=True, wide=True)}
<section class="grid">
  <div><h3>Every device</h3><p>Android, Windows and Linux, in step through encrypted sync.</p></div>
  <div><h3>Yours alone</h3><p>No account, no ads, no tracking. Everything stays on your devices.</p></div>
  <div><h3>On a schedule</h3><p>Set a task once and let it run by itself.</p></div>
  <div><h3>Open source</h3><p>MIT licence. Read it, build it, change it.</p></div>
</section>'''

SWITCH = '''<script>
// The short films play by themselves, but only while they are on screen, so a phone does not fetch them all at once.
// Someone who has asked their device for less motion gets a still picture with a play button instead.
(function () {
  var films = document.querySelectorAll('video[data-auto]');
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) { films.forEach(function (v) { v.controls = true; }); return; }
  if (!('IntersectionObserver' in window)) { films.forEach(function (v) { v.play().catch(function () {}); }); return; }
  var seen = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) { if (e.isIntersecting) e.target.play().catch(function () {}); else e.target.pause(); });
  }, { threshold: 0.35 });
  films.forEach(function (v) { seen.observe(v); });
})();
</script>
<script>
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


# How My PAI compares. Every cell about another project is what that project says about itself on the page listed
# under it, read on the date below; "Not stated" means its own pages did not say. Nothing here is an opinion.
COMPARED_ON = '17 November 2026'
COMPARE_APPS = ['My PAI', 'Open WebUI', 'LibreChat', 'Jan', 'AnythingLLM', 'LobeHub (LobeChat)', 'Chatbox']
COMPARE_ROWS = [
    ('Licence', ['MIT', 'Open WebUI License (BSD-3-Clause terms plus a branding clause)', 'MIT', 'Apache 2.0', 'MIT', 'LobeHub Community License (Apache 2.0 with added conditions)', 'GPLv3 (Community Edition)']),
    ('Android app', ['Yes', 'Not stated (installs as a web app)', 'Not stated', 'Not stated', 'Yes', 'Yes', 'Yes']),
    ('Windows and Linux app', ['Yes', 'Yes', 'Not stated (runs in a browser)', 'Yes', 'Yes', 'Yes', 'Yes']),
    ('macOS app', ['No', 'Yes', 'Not stated', 'Yes', 'Yes', 'Yes', 'Yes']),
    ('iPhone app', ['No', 'Not stated', 'Not stated', 'Not stated', 'Not stated', 'Yes', 'Yes']),
    ('Runs in a browser', ['No', 'Yes, on a server you host', 'Yes, on a server you host', 'Yes (Jan Web)', 'Yes, with Docker', 'Yes', 'Yes']),
    ('Account', ['None, ever', 'A local account on your own server', 'An account on your own server', 'None for the desktop app', 'None for the desktop app', 'Sign-in for the cloud; can be self-hosted', 'Not stated']),
    ('MCP servers', ['Yes, from 1.1.0', 'Yes', 'Yes', 'Yes', 'Yes', 'Yes', 'Yes']),
    ('Ollama', ['Yes', 'Yes', 'Yes', 'Yes', 'Yes', 'Yes', 'Yes']),
    ('Paid plans from the project', ['None', 'Enterprise licence', 'None stated', 'None stated', 'Hosted cloud plans', 'Hosted cloud plans', 'Subscription for hosted models']),
]
COMPARE_SOURCES = [
    ('Open WebUI', ['https://github.com/open-webui/open-webui', 'https://github.com/open-webui/open-webui/blob/main/LICENSE', 'https://docs.openwebui.com/getting-started/quick-start/', 'https://docs.openwebui.com/features/extensibility/mcp/', 'https://docs.openwebui.com/enterprise']),
    ('LibreChat', ['https://github.com/LibreChat-AI/LibreChat', 'https://github.com/LibreChat-AI/LibreChat/blob/main/LICENSE', 'https://www.librechat.ai/docs/features/mcp', 'https://www.librechat.ai/tos']),
    ('Jan', ['https://github.com/janhq/jan', 'https://github.com/janhq/jan/blob/main/LICENSE', 'https://docs.jan.ai/desktop', 'https://docs.jan.ai/desktop/integrations/mcp-servers']),
    ('AnythingLLM', ['https://github.com/Mintplex-Labs/anything-llm', 'https://github.com/Mintplex-Labs/anything-llm/blob/master/LICENSE', 'https://anythingllm.com/mobile', 'https://docs.anythingllm.com/mcp-compatibility/overview', 'https://anythingllm.com/pricing']),
    ('LobeHub (LobeChat)', ['https://github.com/lobehub/lobehub', 'https://github.com/lobehub/lobehub/blob/main/LICENSE', 'https://lobehub.com/downloads', 'https://lobehub.com/pricing']),
    ('Chatbox', ['https://github.com/chatboxai/chatbox', 'https://github.com/chatboxai/chatbox/blob/main/LICENSE', 'https://chatboxai.app/en', 'https://docs.chatboxai.app/en/guides/mcp']),
]


def compare():
    for _, cells in COMPARE_ROWS:
        assert len(cells) == len(COMPARE_APPS)
    e = html.escape
    head = '<tr><th></th>' + ''.join('<th%s>%s</th>' % (' class="ours"' if i == 0 else '', e(a)) for i, a in enumerate(COMPARE_APPS)) + '</tr>'
    body = '\n'.join('<tr><th>%s</th>%s</tr>' % (e(name), ''.join('<td%s>%s</td>' % (' class="ours"' if i == 0 else '', e(c)) for i, c in enumerate(cells))) for name, cells in COMPARE_ROWS)
    sources = '\n'.join('<li><strong>%s</strong>: %s</li>' % (e(name), ', '.join('<a href="%s" rel="nofollow noopener">%s</a>' % (u, e(u.split('//')[1])) for u in urls)) for name, urls in COMPARE_SOURCES)
    return f'''<article class="wide"><h1>How My PAI compares</h1>
<p>My PAI is one of several apps with public source code that let you use AI with your own keys. They are good projects, and the right one depends on what you need. This page sets out plain facts side by side so you can choose. It is not a ranking.</p>
<div class="table"><table>
<thead>{head}</thead>
<tbody>
{body}
</tbody>
</table></div>
<p>What each project says about itself, read on {COMPARED_ON}. "Not stated" means its own pages did not say, not that it is missing.</p>
<h2>Where My PAI is different</h2>
<ul>
<li><strong>No account and no server to set up.</strong> Install it, add a key (or point it at Ollama) and start. There is nothing to sign in to.</li>
<li><strong>Phone and computer in step.</strong> Chats, files and keys sync between your own devices, encrypted end to end, linked by a code or a QR picture. The relay in between stores nothing.</li>
<li><strong>It asks first.</strong> Anything that changes a file, sends an email or calls an MCP server is shown to you for a yes or no.</li>
<li><strong>Small and plain.</strong> One MIT-licensed codebase for Android, Windows and Linux, with no paid plan.</li>
</ul>
<h2>Where the others go further</h2>
<ul>
<li><strong>Apple devices.</strong> My PAI has no macOS or iPhone app. Several of the apps above do.</li>
<li><strong>Teams.</strong> My PAI is for one person. Open WebUI, LibreChat and AnythingLLM can be hosted for many users.</li>
<li><strong>A browser version.</strong> My PAI is an installed app only.</li>
<li><strong>Years of features.</strong> These projects are older and larger, with plug-ins, marketplaces and document tools My PAI does not have.</li>
</ul>
<h2>Sources</h2>
<ul class="sources">
{sources}
</ul>
<h2>Found a mistake?</h2>
<p>These projects change quickly. If anything here is wrong or out of date, please <a href="{REPO}/issues/new/choose">open an issue</a> and it will be corrected.</p>
<p>All product names, logos and trademarks belong to their owners. My PAI is not affiliated with, endorsed by or sponsored by any project named on this page.</p>
</article>'''


def notes():
    """Every release's notes, newest first, as (version, Markdown text)."""
    d = os.path.join(ROOT, 'release-notes')
    versions = sorted((f[:-3] for f in os.listdir(d) if f.endswith('.md')), key=lambda v: [int(x) for x in v.split('.')], reverse=True)
    return [(v, open(os.path.join(d, v + '.md')).read().strip()) for v in versions]


def changelog_kt():
    """The same notes inside the app (Settings, What's new). Written here so the app and the site never differ."""
    text = '\n\n'.join('## Version %s\n%s' % (v, body) for v, body in notes()).replace('$', "${'$'}")
    assert '"""' not in text
    path = os.path.join(ROOT, 'app/src/main/java/app/pocketagent/ui/Changelog.kt')
    open(path, 'w').write('package app.pocketagent.ui\n\n// Written by scripts/site.py from release-notes/. Do not edit by hand.\n\n'
                          '/** What changed in each version, newest first. */\ninternal val CHANGELOG_TEXT = """\n' + text + '\n""".trimIndent()\n')


def main():
    changelog_kt()
    docs = os.path.join(ROOT, 'docs')
    src = open(os.path.join(ROOT, 'app/src/main/java/app/pocketagent/ui/Docs.kt')).read()

    def grab(name):
        return re.search(r'private val %s = """\n(.*?)\n""".trimIndent\(\)' % name, src, re.S).group(1)

    open(os.path.join(docs, 'index.html'), 'w').write(page('My PAI: open-source AI chat and agent with your own API keys', INDEX, 'Free, open-source AI app for Android, Windows and Linux. Bring your own Claude, OpenAI or Gemini key: chat, or hand over a task. No ads, no subscription.', SWITCH, '', APP_DATA))
    open(os.path.join(docs, 'terms.html'), 'w').write(page('Terms of use · My PAI', '<article><h1>Terms of use</h1>\n' + markdown(grab('TERMS_TEXT')) + '\n</article>', 'Terms of use for My PAI.', name='terms.html'))
    open(os.path.join(docs, 'privacy.html'), 'w').write(page('Privacy · My PAI', '<article><h1>Privacy</h1>\n' + markdown(grab('PRIVACY_TEXT')) + '\n</article>', 'How My PAI handles your data: on your device, encrypted, with no account and no tracking.', name='privacy.html'))
    open(os.path.join(docs, 'open-source.html'), 'w').write(page('Open source · My PAI', '<article><h1>Open source</h1>\n' + markdown(grab('LICENSES_TEXT')) + '\n</article>', 'My PAI is open source under the MIT licence.', name='open-source.html'))
    whats_new = '<article><h1>What\'s new</h1>\n<p>Every version of My PAI and what changed in it, newest first.</p>\n<figure class="reel"><video src="reel.mp4" poster="shots/reel.jpg" controls muted loop playsinline preload="none" aria-label="A short film of what is new in My PAI"></video></figure>\n' + '\n'.join('<h2>Version %s</h2>\n%s' % (v, markdown(body)) for v, body in notes()) + '\n</article>'
    open(os.path.join(docs, 'whats-new.html'), 'w').write(page('What\'s new · My PAI', whats_new, 'What changed in each version of My PAI.', name='whats-new.html'))
    open(os.path.join(docs, 'compare.html'), 'w').write(page('How My PAI compares with other open-source AI apps', compare(), 'My PAI side by side with Open WebUI, LibreChat, Jan, AnythingLLM, LobeHub and Chatbox: licence, devices, accounts, MCP and Ollama.', name='compare.html'))
    shutil.copy(os.path.join(ROOT, 'desktop/icons/icon.png'), os.path.join(docs, 'icon.png'))
    open(os.path.join(docs, '.nojekyll'), 'w').write('')
    open(os.path.join(docs, 'robots.txt'), 'w').write('User-agent: *\nAllow: /\nSitemap: ' + SITE + 'sitemap.xml\n')
    pages = ['', 'whats-new.html', 'compare.html', 'terms.html', 'privacy.html', 'open-source.html']
    open(os.path.join(docs, 'sitemap.xml'), 'w').write('<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' + ''.join('  <url><loc>' + SITE + p + '</loc></url>\n' for p in pages) + '</urlset>\n')


if __name__ == '__main__':
    main()
