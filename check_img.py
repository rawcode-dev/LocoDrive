from PIL import Image
import sys

def check_image(path):
    try:
        img = Image.open(path)
        img = img.convert("RGBA")
        colors = img.getcolors(maxcolors=1000000)
        opaque_colors = [c for count, c in colors if c[3] > 10] # ignore fully transparent
        is_monochrome = all(c[0] == c[1] == c[2] for c in opaque_colors)
        print(f"{path}: size={img.size}, monochrome={is_monochrome}, colors={len(opaque_colors)}")
    except Exception as e:
        print(e)

check_image("src/main/resources/images/upload.png")
check_image("src/main/resources/images/download.png")
