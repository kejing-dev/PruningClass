"""Decimate + calibrate the rose's Tripo stand / pot for the app (profile B12 / D25), with the factory's glb_decimate.py.
Usage (factory venv python): python tools/tripo-swap/prep_props.py <stand-raw.glb> <pot-raw.glb>
Writes app/src/main/assets/models/stand.glb and pot.glb: ~12000 triangles, 1024 px textures, every material OPAQUE,
y up, the bottom centre at the origin, scaled to the design size (stand 0.40 m tall, pot 0.30 m tall; the larger of
width / depth reported). Prints triangles before / after and the calibrated bounding boxes for ui-audit.
"""
import os, subprocess, sys, tempfile
import numpy as np
import trimesh

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "models")
DECIMATE = os.path.join(HERE, "..", "..", "..", "spatial-app-factory", "bin", "glb_decimate.py")
TARGET_H = {"stand": 0.40, "pot": 0.30}


def tris(path):
    s = trimesh.load(path, force="scene")
    return int(sum(len(g.faces) for g in s.geometry.values()))


def prep(role, raw):
    os.makedirs(OUT, exist_ok=True)
    tmp = os.path.join(tempfile.gettempdir(), f"{role}-dec.glb")
    subprocess.run([sys.executable, "-X", "utf8", DECIMATE, raw, tmp, "--faces", "12000", "--tex", "1024"], check=True)
    scene = trimesh.load(tmp, force="scene")
    lo, hi = scene.bounds
    size = hi - lo
    k = TARGET_H[role] / size[1]
    centre = np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])
    m = np.eye(4)
    m[:3, :3] *= k
    m[:3, 3] = -centre * k
    # bake into the vertices (a node transform would depend on the loader honouring it)
    for name in scene.graph.nodes_geometry:
        tf, gname = scene.graph[name]
        scene.geometry[gname].apply_transform(m @ tf)
        scene.graph.update(name, matrix=np.eye(4))
    for g in scene.geometry.values():
        mat = getattr(g.visual, "material", None)
        if mat is not None and hasattr(mat, "alphaMode"):
            mat.alphaMode = "OPAQUE"
    out = os.path.join(OUT, f"{role}.glb")
    scene.export(out)
    lo2, hi2 = trimesh.load(out, force="scene").bounds
    print(f"{role}: raw {tris(raw)} tris -> {tris(out)} tris, scale {k:.4f}, bounds {np.round(lo2, 3).tolist()} .. {np.round(hi2, 3).tolist()}, {os.path.getsize(out) // 1024} KB")


if __name__ == "__main__":
    prep("stand", sys.argv[1])
    prep("pot", sys.argv[2])
