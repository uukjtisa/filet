"""Rasterise filet.svg into the window icon (PNG) and the installer icon (ICO).

Run from this folder: python make_icons.py. Chrome renders the SVG, because nothing else on a
stock Windows box draws SVG arcs; Pillow scales it down and writes the ICO's sizes.
"""
import os, subprocess, sys
from PIL import Image

here = os.path.dirname(os.path.abspath(__file__))
chrome = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
big = os.path.join(here, "filet-1024.png")
subprocess.run([chrome, "--headless", "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000",
                "--window-size=1024,1024", "--screenshot=" + big, "file:///" + os.path.join(here, "filet.svg").replace("\\", "/")],
               check=True, capture_output=True)
img = Image.open(big).convert("RGBA")
img.resize((256, 256), Image.LANCZOS).save(os.path.join(here, "..", "src", "main", "resources", "filet.png"))
img.save(os.path.join(here, "filet.ico"), sizes=[(s, s) for s in (16, 20, 24, 32, 40, 48, 64, 128, 256)])
os.remove(big)
print("ok")
