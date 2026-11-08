# -*- coding: utf-8 -*-
"""无设备时的视觉校验：把 assets/web 复制成「Android 注入后」的页面，
放进指定尺寸的 iframe 里用 headless Chrome 截图（headless 窗口最小约 500px，
用 iframe 才能拿到真实的手机视口），并回报 JS 报错数 / 关键 CSS 是否生效。

用法： python tools/shot_android.py
"""
import os
import shutil
import subprocess

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "assets", "web")
OUT = os.path.join(ROOT, "tools", "_shots")
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

STUB = """<script>
window.__errors=[];
window.addEventListener('error',function(e){window.__errors.push(String(e.message));});
window.CarCareAndroid={
  post:function(s){(window.__posted=window.__posted||[]).push(s);},
  getInsets:function(){return 'TOP,BOTTOM';},
  notificationState:function(){return 'granted';},
  platform:function(){return 'android';}
};
</script><script src="cc-android.js"></script>"""

CHECK = """<script>
setTimeout(function(){
  var h=document.querySelector('header');
  var nav=document.querySelector('nav');
  document.title='ERR:'+window.__errors.length
    +'|HP:'+(h?getComputedStyle(h).paddingTop:'-')
    +'|NAV:'+(nav?getComputedStyle(nav).bottom:'-')
    +'|BODY:'+getComputedStyle(document.body).paddingBottom
    +'|BRIDGE:'+window.__ccAndroidBridge;
  parent.postMessage(document.title,'*');
},600);
</script></body>"""

FRAME = """<!DOCTYPE html><html><head><meta charset="utf-8">
<style>html,body{margin:0;background:#222}
iframe{display:block;border:0;width:%(w)dpx;height:%(h)dpx}</style></head>
<body><iframe src="index.html"></iframe>
<script>window.addEventListener('message',function(e){document.title=e.data;});</script>
</body></html>"""


def build(top, bottom):
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    for name in os.listdir(SRC):
        shutil.copy(os.path.join(SRC, name), os.path.join(OUT, name))
    html = open(os.path.join(SRC, "index.html"), encoding="utf-8").read()
    html = html.replace("<head>",
                        "<head>" + STUB.replace("TOP", str(top)).replace("BOTTOM", str(bottom)), 1)
    head, sep, tail = html.rpartition("</body>")
    html = head + CHECK + sep + tail
    open(os.path.join(OUT, "index.html"), "w", encoding="utf-8").write(html)


def shot(w, h, name, scale=2):
    frame = os.path.join(OUT, "_frame_%d.html" % w)
    open(frame, "w", encoding="utf-8").write(FRAME % {"w": w, "h": h})
    raw = os.path.join(OUT, "_raw_%s.png" % name)
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--force-device-scale-factor=%d" % scale,
                    "--window-size=%d,%d" % (w + 60, h + 60),
                    "--virtual-time-budget=3000",
                    "--screenshot=" + raw,
                    "file:///" + frame.replace("\\", "/")],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    img = Image.open(raw)
    img.crop((0, 0, w * scale, h * scale)).save(os.path.join(OUT, name + ".png"))
    os.remove(raw)
    return os.path.join(OUT, name + ".png")


def dump(w):
    frame = os.path.join(OUT, "_frame_%d.html" % w)
    p = subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--virtual-time-budget=3000",
                        "--dump-dom", "file:///" + frame.replace("\\", "/")],
                       capture_output=True, text=True, encoding="utf-8", errors="ignore")
    for line in p.stdout.splitlines():
        if "<title>ERR" in line or "<title>ERR" in line:
            print("CHECK:", line.strip())


def main():
    build(24, 34)
    print("shot:", shot(390, 844, "android-390x844"))
    print("shot:", shot(360, 800, "android-360x800"))
    dump(390)


if __name__ == "__main__":
    main()
