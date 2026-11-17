#!/usr/bin/env python3
"""Makes the short films on the website: one for each feature, from real screens of the app.
Each film shows a few screens in turn, fading from one to the next, and loops. Needs Pillow and ffmpeg.
Usage: clips.py <folder with the screens> <docs/clips>      (the screens are made with the app's own test tool)"""
import os, subprocess, sys
from PIL import Image

FPS, HOLD, FADE = 24, 2.2, 0.6   # how long each screen stays, and how long the change to the next takes

# name of the film: the screens it shows, each as (file, crop box or None)
FILMS = {
    'ask': [('chat.png', None), ('answer.png', None)],
    'work': [('u-draft.png', None), ('wrote.png', None), ('u-files.png', None)],
    'mcp': [('at.png', None), ('approve.png', None), ('answer.png', None)],
    'control': [('dry.png', None), ('wrote.png', None), ('undone.png', None)],
    'shield': [('shield.png', None), ('activity.png', None)],
    'routines': [('tasks.png', None), ('editor.png', None), ('routine.png', None)],
    'models': [('models.png', None), ('reliability.png', None)],
    'devices': [('d-conn.png', None), ('d-expanded.png', None), ('d-ask.png', None)],
}


def film(name, shots, src, out):
    frames = []
    for f, box in shots:
        im = Image.open(os.path.join(src, f)).convert('RGB')
        if box: im = im.crop(box)
        frames.append(im)
    # Every screen of one film is brought to the same size: the width of a phone on the page, twice over for sharp screens.
    w = 540
    h = max(int(im.height * w / im.width) for im in frames) // 2 * 2
    fitted = []
    for im in frames:
        r = im.resize((w, int(im.height * w / im.width)), Image.LANCZOS)
        canvas = Image.new('RGB', (w, h), r.getpixel((4, 4)))
        canvas.paste(r, (0, (h - r.height) // 2))
        fitted.append(canvas)
    path = os.path.join(out, name + '.mp4')
    ff = subprocess.Popen(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{w}x{h}', '-r', str(FPS), '-i', '-',
                           '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '27', '-preset', 'slow', '-movflags', '+faststart', '-an', path], stdin=subprocess.PIPE)
    n = len(fitted)
    for i in range(n):
        a, b = fitted[i], fitted[(i + 1) % n]   # the last screen fades back into the first, so the loop has no jump
        still = a.tobytes()
        for _ in range(int(HOLD * FPS)): ff.stdin.write(still)
        steps = int(FADE * FPS)
        for k in range(1, steps + 1):
            t = k / (steps + 1); t = t * t * (3 - 2 * t)
            ff.stdin.write(Image.blend(a, b, t).tobytes())
    ff.stdin.close(); ff.wait()
    fitted[0].save(os.path.join(out, name + '.jpg'), quality=82)
    print(name, f'{w}x{h}', os.path.getsize(path) // 1024, 'KB')
    return w, h


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for name, shots in FILMS.items(): film(name, shots, src, out)


if __name__ == '__main__':
    main()
