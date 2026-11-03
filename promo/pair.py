import sys
from PIL import Image
ims=[Image.open(p).resize((1000,600)) for p in sys.argv[2:]]
c=Image.new('RGB',(1000*len(ims),600))
for i,im in enumerate(ims): c.paste(im,(1000*i,0))
c.save(sys.argv[1])
