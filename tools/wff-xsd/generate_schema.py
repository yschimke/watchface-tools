#!/usr/bin/env python3
"""Generate wff-core's schema table from Google's Watch Face Format XSDs.

Usage:
  git clone https://github.com/google/watchface /path/to/watchface
  python3 -I tools/wff-xsd/generate_schema.py /path/to/watchface > \
    wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffSchemaData.kt

Reads third_party/wff/specification/documents/<version>/ for every version present and records,
for each element, attribute and parent→child edge, the first version that allows it. Element ids
are the XML name, qualified by the parent id only where one name has several different
definitions (for example ListConfiguration under UserConfigurations versus under Scene).
Standard library only.
"""

import os
import subprocess
import sys
import xml.etree.ElementTree as ET

XS = "{http://www.w3.org/2001/XMLSchema}"
INT_TYPES = {
    "xs:int",
    "xs:integer",
    "xs:nonNegativeInteger",
    "xs:positiveInteger",
    "xs:long",
    "xs:short",
    "xs:unsignedInt",
}
FLOAT_TYPES = {"xs:float", "xs:double", "xs:decimal"}


def tag(node):
    return node.tag.replace(XS, "")


class Version:
    def __init__(self, vdir):
        self.roots = []
        for d, _, fs in sorted(os.walk(vdir)):
            for f in sorted(fs):
                if f.endswith(".xsd"):
                    self.roots.append(ET.parse(os.path.join(d, f)).getroot())
        self.g = {
            k: {}
            for k in ("element", "attribute", "attributeGroup", "complexType", "group", "simpleType")
        }
        for r in self.roots:
            for c in r:
                if tag(c) in self.g and c.get("name"):
                    self.g[tag(c)].setdefault(c.get("name"), c)
        self.substitutes = {}
        for e in self.g["element"].values():
            if e.get("substitutionGroup"):
                self.substitutes.setdefault(e.get("substitutionGroup"), []).append(e)

    def concrete(self, el):
        """The element itself unless abstract, plus every member of its substitution group."""
        out = [] if el.get("abstract") == "true" else [el]
        for s in self.substitutes.get(el.get("name"), []):
            out += self.concrete(s)
        return out

    # ---- types -------------------------------------------------------------------------------

    def simple_base(self, node, seen=()):
        """(base type name, enum values) of an attribute or simpleType node."""
        enums = [e.get("value") for e in node.iter(XS + "enumeration")]
        t = node.get("type")
        base = None
        for r in node.iter(XS + "restriction"):
            base = r.get("base")
            break
        if t and t in self.g["simpleType"] and t not in seen:
            b2, e2 = self.simple_base(self.g["simpleType"][t], seen + (t,))
            return (b2 or t), (enums or e2)
        return (base or t or "xs:string"), enums

    def kind(self, name, node):
        base, enums = self.simple_base(node)
        t = node.get("type") or ""
        if enums:
            if sorted(enums) == ["FALSE", "TRUE"]:
                return "BOOLEAN", enums
            return "ENUM", enums
        if "boolean" in t.lower():
            return "BOOLEAN", ["TRUE", "FALSE"]
        if name == "id":
            return "ID", []
        if "resource" in t.lower() or name in ("resource",):
            return "RESOURCE", []
        if "color" in t.lower() or name in ("color", "tintColor"):
            return "COLOR", []
        if name in ("expression", "value") and "expression" in t.lower():
            return "EXPRESSION", []
        if base in INT_TYPES:
            return "INT", []
        if base in FLOAT_TYPES:
            return "FLOAT", []
        return "STRING", []

    # ---- element bodies ----------------------------------------------------------------------

    def body(self, el):
        """(attrs {name: (kind, enums, required)}, child element nodes, allows text)."""
        attrs, kids = {}, []
        text = [False]

        def walk(n, seen):
            for c in n:
                t = tag(c)
                if t == "attribute":
                    a = c if c.get("name") else self.g["attribute"].get(c.get("ref"))
                    if a is None:
                        continue
                    k, e = self.kind(a.get("name"), a)
                    attrs[a.get("name")] = (k, e, c.get("use") == "required")
                elif t == "attributeGroup" and c.get("ref"):
                    ref = c.get("ref")
                    if ref in self.g["attributeGroup"] and ("ag", ref) not in seen:
                        walk(self.g["attributeGroup"][ref], seen | {("ag", ref)})
                elif t == "group" and c.get("ref"):
                    ref = c.get("ref")
                    if ref in self.g["group"] and ("g", ref) not in seen:
                        walk(self.g["group"][ref], seen | {("g", ref)})
                elif t == "element":
                    if c.get("name"):
                        kids.append(c)
                    elif c.get("ref") in self.g["element"]:
                        kids.extend(self.concrete(self.g["element"][c.get("ref")]))
                elif t in ("extension", "restriction") and c.get("base") in self.g["complexType"]:
                    base = c.get("base")
                    if ("ct", base) not in seen:
                        walk(self.g["complexType"][base], seen | {("ct", base)})
                    walk(c, seen)
                elif t == "simpleContent":
                    text[0] = True
                    walk(c, seen)
                elif t == "complexType":
                    if c.get("mixed") == "true":
                        text[0] = True
                    walk(c, seen)
                elif t in ("annotation", "documentation", "simpleType"):
                    continue
                else:
                    walk(c, seen)

        walk(el, frozenset())
        t = el.get("type")
        if t and t in self.g["complexType"]:
            ct = self.g["complexType"][t]
            if ct.get("mixed") == "true":
                text[0] = True
            a2, k2, t2 = self.body(ct)
            attrs.update(a2)
            kids += k2
            text[0] |= t2
        elif t and (t.startswith("xs:") or t in self.g["simpleType"]):
            text[0] = True
        elif tag(el) == "element" and el.find(XS + "complexType") is None and not t:
            text[0] = True
        if tag(el) == "complexType" and el.get("mixed") == "true":
            text[0] = True
        return attrs, kids, text[0]

    def graph(self, ambiguous):
        """{id: (name, attrs, [(child id)], text)} for everything reachable from WatchFace."""
        out = {}
        ids = {}

        def visit(node, parent_id):
            if id(node) in ids:
                return ids[id(node)]
            name = node.get("name")
            is_global = node in self.g["element"].values()
            nid = name if (is_global or name not in ambiguous or parent_id is None) else f"{parent_id}.{name}"
            ids[id(node)] = nid
            attrs, kids, text = self.body(node)
            entry = out.setdefault(nid, [name, {}, [], False])
            entry[1].update(attrs)
            entry[3] |= text
            for k in kids:
                cid = visit(k, nid)
                if cid not in entry[2]:
                    entry[2].append(cid)
            return nid

        visit(self.g["element"]["WatchFace"], None)
        return out

    def definition_signatures(self):
        sigs = {}
        for r in self.roots:
            for e in r.iter(XS + "element"):
                if e.get("name"):
                    a, k, _ = self.body(e)
                    sigs.setdefault(e.get("name"), set()).add(
                        (tuple(sorted(a)), tuple(sorted(c.get("name") for c in k)))
                    )
        return sigs


def kt(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main(repo):
    docs = os.path.join(repo, "third_party/wff/specification/documents")
    versions = sorted((int(v) for v in os.listdir(docs) if v.isdigit()))
    loaded = {v: Version(os.path.join(docs, str(v))) for v in versions}
    ambiguous = set()
    for v in versions:
        ambiguous |= {n for n, s in loaded[v].definition_signatures().items() if len(s) > 1}
    graphs = {v: loaded[v].graph(ambiguous) for v in versions}
    latest = versions[-1]

    def first(pred):
        return next(v for v in versions if pred(v))

    try:
        rev = subprocess.run(
            ["git", "-C", repo, "rev-parse", "HEAD"], capture_output=True, text=True, check=True
        ).stdout.strip()
    except Exception:
        rev = "unknown"

    w = sys.stdout.write
    w("// GENERATED by tools/wff-xsd/generate_schema.py from github.com/google/watchface\n")
    w(f"// at {rev}, third_party/wff/specification/documents/{{{','.join(map(str, versions))}}}.\n")
    w("// The XSDs are Copyright Samsung Electronics, Apache License 2.0. Do not edit by hand.\n\n")
    w("package ee.schimke.watchface.wff\n\n")
    w("import ee.schimke.watchface.wff.WffAttributeType.*\n\n")
    w(f"internal val GENERATED_LATEST_VERSION: Int = {latest}\n\n")
    w("internal val GENERATED_SCHEMA: List<WffElementSpec> =\n  listOf(\n")
    order = list(graphs[latest].keys())
    for nid in order:
        name, attrs, kids, text = graphs[latest][nid]
        since = first(lambda v: nid in graphs[v])
        w(f"    WffElementSpec(\n      id = {kt(nid)},\n      name = {kt(name)},\n")
        w(f"      sinceVersion = WffVersion.V{since},\n")
        w("      attributes =\n        listOf(\n")
        for an in sorted(attrs):
            k, enums, req = attrs[an]
            asince = first(lambda v: nid in graphs[v] and an in graphs[v][nid][1])
            args = [kt(an), k]
            if req:
                args.append("required = true")
            if asince > since:
                args.append(f"sinceVersion = WffVersion.V{asince}")
            if enums and k != "BOOLEAN":
                args.append("enumValues = listOf(" + ", ".join(kt(e) for e in enums) + ")")
            elif k == "BOOLEAN":
                args.append('enumValues = listOf("TRUE", "FALSE")')
            w(f"          WffAttributeSpec({', '.join(args)}),\n")
        w("        ),\n      children =\n        listOf(\n")
        for cid in kids:
            esince = first(lambda v: nid in graphs[v] and cid in graphs[v][nid][2])
            w(f"          WffChildSpec({kt(cid)}, WffVersion.V{esince}),\n")
        w("        ),\n")
        if text:
            w("      allowsText = true,\n")
        w("    ),\n")
    w("  )\n")


if __name__ == "__main__":
    main(sys.argv[1])
