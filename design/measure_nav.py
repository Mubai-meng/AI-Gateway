from PIL import Image
import numpy as np
import sys

im = Image.open(sys.argv[1] if len(sys.argv) > 1 else 'ui-base.png')
a = np.array(im.convert('L'))
dark = a.mean() < 110
seg = a[:, 30:290]
mask = (seg < 120) if not dark else (seg > 100)
rows = mask.sum(axis=1)
bands = []
inb = False
for y, c in enumerate(rows):
    if c > 2 and not inb:
        start = y
        inb = True
    elif c <= 2 and inb:
        bands.append((start, y))
        inb = False
for b in bands[:14]:
    ys, xs = mask[b[0]:b[1]].nonzero()
    print('band y', b, 'x', int(xs.min()), int(xs.max()))
