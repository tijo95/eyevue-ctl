#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_refs.py - verifie que tout Cmd.X / PObjet.X cite dans le code Kotlin
existe bien dans Cmd.kt / Params.kt (fichiers GENERES par protocol/gen_kotlin.py).

Le projet n'a pas de kotlinc ici : ce script remplace, a bas niveau, le
controle de resolution des symboles. Il ignore commentaires et strings.

Usage : python tools/check_refs.py     (exit 0 = ok)
"""
import os
import re
import sys

SRC = os.path.join("app", "src", "main", "java", "com", "local", "eyevuectl")


def strip_code(src):
    """Retire commentaires // et /* */, et les litteraux string."""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    src = re.sub(r'"[^"]*"', '""', src)
    return src


def main():
    if not os.path.isdir(SRC):
        sys.exit("introuvable : %s (a lancer depuis eyevue-ctl/)" % SRC)
    cmd_txt = open(os.path.join(SRC, "Cmd.kt"), encoding="utf-8").read()
    par_txt = open(os.path.join(SRC, "Params.kt"), encoding="utf-8").read()

    # objets params attendus d'apres protocol.json (nom PascalCase P<Commande>)
    expected = set()
    for path in (os.path.join("..", "protocol", "protocol.json"),
                 os.path.join(os.pardir, "protocol", "protocol.json")):
        if os.path.exists(path):
            import json
            cmds = json.load(open(path, encoding="utf-8"))["commands"]
            for v in cmds.values():
                for part in v.split("/"):
                    expected.add("P" + "".join(w.capitalize() for w in part.split("_")))
            break

    cmd_consts = set(re.findall(r'const val (\w+): Int', cmd_txt))
    cmd_funs = set(re.findall(r'fun (\w+)\(', cmd_txt))
    objs = {}
    for m in re.finditer(r'object (P\w+) \{(.*?)\n\}', par_txt, re.S):
        objs[m.group(1)] = (set(re.findall(r'const val (\w+): Int', m.group(2))),
                            set(re.findall(r'fun (\w+)\(', m.group(2))))

    problems, checked = [], 0
    for fn in sorted(os.listdir(SRC)):
        if not fn.endswith(".kt") or fn in ("Cmd.kt", "Params.kt"):
            continue
        code = strip_code(open(os.path.join(SRC, fn), encoding="utf-8").read())
        for name in re.findall(r'\bCmd\.(\w+)', code):
            checked += 1
            if name not in cmd_consts and name not in cmd_funs:
                problems.append("%s : Cmd.%s -> ni constante ni fonction dans Cmd.kt" % (fn, name))
        for obj, member in re.findall(r'\b(P[A-Z][A-Za-z_0-9]*)\.(\w+)', code):
            if obj not in objs and obj not in expected:
                continue          # classe framework (PackageManager, ...)
            checked += 1
            if obj not in objs:
                problems.append("%s : %s -> objet params ATTENDU mais non genere" % (fn, obj))
            elif member not in objs[obj][0] and member not in objs[obj][1]:
                problems.append("%s : %s.%s -> constante/fonction absente (dispo : %s)"
                                % (fn, obj, member, ", ".join(sorted(objs[obj][0] | objs[obj][1]))))

    print("objets params : %d | constantes Cmd : %d | attendus JSON : %d | references verifiees : %d"
          % (len(objs), len(cmd_consts), len(expected), checked))
    for p in problems:
        print("  PROBLEME", p)
    if problems:
        sys.exit(1)
    print("OK : toutes les references Cmd.* / P*.* resolvent.")


if __name__ == "__main__":
    main()
