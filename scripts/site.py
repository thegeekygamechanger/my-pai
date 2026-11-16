#!/usr/bin/env python3
"""Builds the website in docs/. The terms and privacy pages are taken from the app's own text
(app/src/main/java/app/pocketagent/ui/Docs.kt), so the site and the app never say different things."""
import html, os, re, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = 'https://github.com/thegeekygamechanger/pocket-agent'


def page(title, body, desc):
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{desc}">
<link rel="icon" href="icon.png">
<link rel="stylesheet" href="style.css">
</head>
<body>
<div class="wrap">
<header>
  <a class="mark" href="index.html"><img src="icon.png" alt="">Pocket Agent</a>
  <nav><a href="terms.html">Terms</a><a href="privacy.html">Privacy</a><a href="{REPO}">Source</a></nav>
</header>
{body}
<footer>
  <a href="terms.html">Terms of use</a><a href="privacy.html">Privacy</a><a href="{REPO}">Source code</a><a href="{REPO}/blob/main/LICENSE">MIT licence</a>
  <p>Pocket Agent is open-source software, not affiliated with Anthropic, OpenAI, Google or any other AI service. Their product names are trademarks of their owners.</p>
</footer>
</div>
</body>
</html>
'''


INDEX = f'''<section class="hero">
  <h1>Pocket Agent</h1>
  <p>Chat with AI, or hand it work and let it get on with it. On your phone and your computer, with your own keys.</p>
  <div class="buttons">
    <a class="btn primary" href="{REPO}/releases/latest">Download</a>
    <a class="btn" href="{REPO}">View source</a>
  </div>
</section>
<section class="grid">
  <div><h3>Your keys</h3><p>Claude, OpenAI, OpenRouter, Groq, Gemini and more. Paste a key and go.</p></div>
  <div><h3>Chat and Work</h3><p>Ask a question, or give it a task with files, web search, GitHub and Gmail.</p></div>
  <div><h3>Every device</h3><p>Android, Windows and Linux, kept in step with encrypted sync.</p></div>
  <div><h3>Yours alone</h3><p>No account, no ads, no tracking. Everything stays on your devices.</p></div>
</section>
<section class="note">
  <h2>Free and open source</h2>
  <p>Released under the MIT licence. Read the code, build it yourself, change it, share it.</p>
</section>'''


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

    open(os.path.join(docs, 'index.html'), 'w').write(page('Pocket Agent', INDEX, 'Chat with AI or hand it work, on Android, Windows and Linux, with your own API keys. Free and open source.'))
    open(os.path.join(docs, 'terms.html'), 'w').write(page('Terms of use · Pocket Agent', '<article><h1>Terms of use</h1>\n' + markdown(grab('TERMS_TEXT')) + '\n</article>', 'Terms of use for Pocket Agent.'))
    open(os.path.join(docs, 'privacy.html'), 'w').write(page('Privacy · Pocket Agent', '<article><h1>Privacy</h1>\n' + markdown(grab('PRIVACY_TEXT')) + '\n</article>', 'How Pocket Agent handles your data: on your device, encrypted, with no account and no tracking.'))
    shutil.copy(os.path.join(ROOT, 'desktop/icons/icon.png'), os.path.join(docs, 'icon.png'))
    open(os.path.join(docs, '.nojekyll'), 'w').write('')


if __name__ == '__main__':
    main()
