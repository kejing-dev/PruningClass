# Swapping the rose's stand / pot for Tripo models (not done in v0.1.5)

The stand and pot are procedural (ui/pc/scene/PropMesher.kt) until Tripo works in this PC's Chrome.
1. Generate in Tripo (this PC's Chrome, chrome-tripo lock): an oak plant stand ~40 x 40 x 40 cm, a terracotta pot with a
   rolled rim and soil ~35 cm across, 30 cm tall. Export GLB to ~/Downloads/pruning-class-tripo/ (stand-raw.glb, pot-raw.glb).
2. `C:/Users/Admin/.factory-venv/Scripts/python.exe tools/tripo-swap/prep_props.py <stand-raw.glb> <pot-raw.glb>`
   -> app/src/main/assets/models/stand.glb, pot.glb (4000 tris, 1024 px, opaque, bottom centre at the origin, y up, scaled).
3. Put PropModels.kt.txt back as app/src/main/java/io/embodit/pruningclass/ui/pc/scene/PropModels.kt and wire it in
   PcScene: `private val props = PropModels()`; in build() after `ms = meshes`: `props.load(); pace()`; in detach before
   `shared?.close()`: `props.close()`; in the rose jobs clone `props.stand` / `props.pot` (procedural when null; the Tripo
   pot brings its own soil). The contact-shadow discs stay.
