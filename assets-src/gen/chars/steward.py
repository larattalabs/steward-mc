"""THE STEWARD - keeper of a settlement. Light skin, umber hair and a short trimmed beard, a brown felt hat with
a wide brim and a dark band, a long forest-green wool coat with brass buttons and a cream collar, a leather
satchel strap across the chest to the right hip, a leather belt with a brass buckle, walnut trousers and
leather boots. Classic arms. Silhouette: the only wide-brimmed hat, green where the AgentCraft cast is not."""
from chars.base import Box, P, Skin, T, legend_hair, legend_skin, mirror_limbs


def build():
    sk = Skin(slim=False)
    coat = T("cast_ramps", "steward")
    L = {}
    L.update(legend_skin("light"))
    L.update(legend_hair("umber"))
    L.update({
        "w": P("ramps.cream[1]"), "e": P("details.steward.eye"), "b": P("details.steward.brow"), "m": P("skin_tones.light[3]"),
        "@": coat[0], "A": coat[1], "B": coat[2], "C": coat[3], "D": coat[4],
        "q": P("ramps.cream[0]"), "c": P("ramps.cream[1]"), "r": P("ramps.cream[2]"),
        "f": P("details.steward.hat"), "F": P("details.steward.hat_shade"), "g": P("details.steward.hat_band"),
        "n": P("ramps.leather[0]"), "N": P("ramps.leather[1]"), "M": P("ramps.leather[2]"), "O": P("ramps.leather[3]"),
        "y": P("ramps.brass[1]"), "Y": P("ramps.brass[2]"), "k": P("ramps.brass[3]"),
        "p": P("ramps.walnut[1]"), "x": P("ramps.walnut[2]"), "X": P("ramps.walnut[3]"), "z": P("ramps.walnut[4]"),
    })

    # ---------------- head: umber hair, short beard (base); felt hat with a wide brim (overlay) ----------------
    hd = Box("head")
    hd.whole("base", "front", ["HHHHHHHH",
                               "GHHHHHHG",
                               "SSSSSSSS",
                               "SbbSSbbS",
                               "SweSSewS",
                               "SSSssSSS",
                               "HSHmmHSH",
                               "GHHHHHHG"])
    # col 0 = back edge, col 7 = front edge
    hd.whole("base", "right", ["HHHHHHHH",
                               "GHHHHHHG",
                               "KGGSSSSS",
                               "KGGsdSSS",
                               "KGGdsSSS",
                               "KKGSSSSS",
                               "KGGHSSSH",
                               "KGHHHHHH"])
    # col 0 = front edge, col 7 = back edge
    hd.whole("base", "left", ["HHHHHHHH",
                              "GHHHHHHG",
                              "SSSSSGGK",
                              "SSSsdGGK",
                              "SSSdsGGK",
                              "SSSSSGKK",
                              "HSSSHGGK",
                              "HHHHHHGK"])
    hd.whole("base", "back", ["HJHHHHJH",
                              "HHHHHHHH",
                              "GHHHHHHG",
                              "GHHHHHHG",
                              "GGHHHHGG",
                              "KGGGGGGK",
                              "dKGGGGKd",
                              "dsSSSSsd"])
    hd.whole("base", "top", ["KGGGGGGK"] + ["GHHHHHHG"] * 6 + ["KGGGGGGK"])
    hd.whole("base", "bottom", ["dddddddd"] + ["dHHHHHHd"] * 2 + ["dssssssd"] * 4 + ["dddddddd"])
    # the hat: crown over the top two rows, a dark band, the brim's edge shadow on row 2
    hd.whole("overlay", "front", ["FffffffF",
                                  "ffffffff",
                                  "gggggggg",
                                  "........",
                                  "........",
                                  "........",
                                  "........",
                                  "........"])
    for f in ("right", "left", "back"):
        hd.whole("overlay", f, ["FffffffF",
                                "fffFffff",
                                "gggggggg",
                                "........",
                                "........",
                                "........",
                                "........",
                                "........"])
    hd.whole("overlay", "top", ["FFFFFFFF",
                                "FffffffF",
                                "FfFFFFfF",
                                "FfFffFfF",
                                "FfFffFfF",
                                "FfFFFFfF",
                                "FffffffF",
                                "FFFFFFFF"])
    hd.commit(sk, L)

    # ---------------- body: green wool coat, cream collar, brass buttons, satchel strap, belt ----------------
    bd = Box("body")
    bd.garment("base", 0, 11, "A", "B", "C", top=("C", "B"), bottom="C")
    bd.patch("base", "front", 0, 0, ["CArqqrAC",
                                     "BAAccAAC"])
    for yy in (3, 5, 7):          # brass buttons down the placket (viewer left of centre)
        bd.put("base", "front", 3, yy, "y")
    for yy in range(2, 11):
        bd.put("base", "front", 4, yy, "C")
    # satchel strap: from the left shoulder (viewer right) down to the right hip (viewer left)
    for i, (x, y) in enumerate(((6, 1), (6, 2), (5, 3), (5, 4), (4, 5), (3, 6), (2, 7), (1, 8))):
        bd.put("base", "front", x, y, "N" if i % 3 else "n")
    bd.patch("base", "front", 0, 8, ["MNNkyNNM"])   # belt with a brass buckle
    bd.patch("base", "front", 0, 9, ["BB....CC".replace(".", "B")])
    bd.patch("base", "front", 0, 11, ["CCCCCCCC"])
    bd.patch("base", "back", 0, 0, ["CAAAAAAC"])
    bd.patch("base", "back", 0, 8, ["MNNNNNNM"])
    bd.patch("base", "back", 1, 2, ["N"])           # the strap over the back
    bd.patch("base", "back", 1, 3, ["N"])
    bd.patch("base", "back", 2, 4, ["N"])
    for f in ("right", "left"):
        bd.patch("base", f, 0, 8, ["MNNM"])
        bd.patch("base", f, 0, 11, ["CCCC"])
    bd.patch("base", "top", 0, 0, ["CCCCCCCC", "CrqqqqrC", "CBBBBBBC", "CCCCCCCC"])
    # coat skirt on the overlay: a slightly lighter hem that stands off the trousers
    bd.patch("overlay", "front", 0, 10, ["A@AAAA@A", "BBBBBBBB"])
    bd.patch("overlay", "back", 0, 10, ["AAAAAAAA", "BBBBBBBB"])
    bd.put("overlay", "front", 3, 5, "Y")           # the middle button catches the light
    bd.commit(sk, L)

    # ---------------- right arm (classic): green sleeve, cream cuff, light hand ----------------
    ra = Box("right_arm")
    ra.garment("base", 0, 8, "A", "B", "C", top=("B", "A"))
    for f in ("front", "back", "right", "left"):
        ra.patch("base", f, 0, 9, ["r" * ra.dims[f][0]])
    ra.whole("base", "bottom", ["ssss", "sdds", "sdds", "ssss"])
    ra.patch("base", "front", 0, 10, ["hSSs", "SSss"])
    ra.patch("base", "right", 0, 10, ["sSSh", "ssSS"])
    ra.patch("base", "back", 0, 10, ["sSSS", "dssS"])
    ra.patch("base", "left", 0, 10, ["Ssss", "sssd"])
    ra.garment("overlay", 0, 7, "A", "B", "C")
    for f in ("front", "back", "right", "left"):
        ra.patch("overlay", f, 0, 8, ["C" * ra.dims[f][0]])    # turned-back cuff
    ra.commit(sk, L)

    # ---------------- right leg: walnut trousers, leather boots ----------------
    rl = Box("right_leg")
    rl.garment("base", 0, 8, "p", "x", "X", top=("X", "x"))
    rl.patch("base", "front", 0, 9, ["nNNN", "NNNM", "OOOO"])
    rl.patch("base", "right", 0, 9, ["NNNn", "MNNN", "OOOO"])
    rl.patch("base", "back", 0, 9, ["NNNN", "MNNM", "OOOO"])
    rl.patch("base", "left", 0, 9, ["nNNN", "NNNM", "OOOO"])
    rl.whole("base", "bottom", ["OOOO", "OMMO", "OMMO", "OOOO"])
    rl.patch("overlay", "front", 0, 8, ["MMMM"])    # boot tops
    rl.patch("overlay", "right", 0, 8, ["MMMM"])
    rl.patch("overlay", "back", 0, 8, ["MMMM"])
    rl.patch("overlay", "left", 0, 8, ["MMMM"])
    rl.commit(sk, L)

    mirror_limbs(sk)
    return sk
