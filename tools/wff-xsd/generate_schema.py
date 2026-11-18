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
import re
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


def sanitize(pattern):
    """XSD patterns escape some non-syntax characters (`\\_`, `\\:`) that unicode-mode regex engines
    (Kotlin/Wasm runs on JavaScript's) reject; drop those escapes."""
    return re.sub(r"\\([^a-zA-Z0-9.*+?()\[\]{}|\\/^$\-])", r"\1", pattern)


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

    # ---- value rules -------------------------------------------------------------------------
    #
    # A rule is a nested tuple, mirrored by WffValueRule in WffSchema.kt:
    #   ("any",) ("enum", [values]) ("pattern", regex) ("number", integer, min, minExcl, max, maxExcl)
    #   ("expression",) ("union", [rules]) ("list", rule)
    # Facets other than enumeration, pattern and the numeric bounds are ignored (they are not used
    # by the attributes the format defines).

    INT_BUILTINS = {
        "xs:int": (-2147483648, 2147483647),
        "xs:integer": (None, None),
        "xs:long": (None, None),
        "xs:short": (-32768, 32767),
        "xs:nonNegativeInteger": (0, None),
        "xs:positiveInteger": (1, None),
        "xs:unsignedInt": (0, 4294967295),
        "xs:unsignedByte": (0, 255),
    }

    def builtin_rule(self, t):
        if t in self.INT_BUILTINS:
            lo, hi = self.INT_BUILTINS[t]
            return ("number", True, lo, False, hi, False)
        if t in FLOAT_TYPES:
            return ("number", False, None, False, None, False)
        if t == "xs:boolean":
            return ("enum", ["true", "false", "1", "0"])
        return ("any",)

    def type_rule(self, name, seen=()):
        if name in self.g["simpleType"] and name not in seen:
            if "xpression" in name and name.lower().endswith("expressiontype"):
                # arithmeticExpressionType and friends are unions with xs:string: anything
                # lexically passes the XSD, so the rule is "parse as an expression".
                return ("expression",)
            return self.simple_rule(self.g["simpleType"][name], seen + (name,))
        return self.builtin_rule(name)

    def simple_rule(self, node, seen=()):
        """Rule of an <xs:simpleType> (or any node carrying type=/an inline simpleType)."""
        st = node if tag(node) == "simpleType" else node.find(XS + "simpleType")
        if st is None:
            t = node.get("type")
            return self.type_rule(t, seen) if t else ("any",)
        for c in st:
            t = tag(c)
            if t == "union":
                members = [self.type_rule(m, seen) for m in (c.get("memberTypes") or "").split()]
                members += [self.simple_rule(i, seen) for i in c.findall(XS + "simpleType")]
                if ("any",) in members:
                    return ("any",)  # a free-text member accepts everything
                return ("union", members)
            if t == "list":
                item = c.get("itemType")
                inner = self.type_rule(item, seen) if item else self.simple_rule(c, seen)
                return ("list", inner)
            if t == "restriction":
                base = c.get("base")
                inner = c.find(XS + "simpleType")
                rule = (
                    self.type_rule(base, seen)
                    if base
                    else (self.simple_rule(inner, seen) if inner is not None else ("any",))
                )
                enums = [e.get("value") for e in c.findall(XS + "enumeration")]
                pats = [e.get("value") for e in c.findall(XS + "pattern")]
                if enums:
                    return ("enum", enums)
                if pats:
                    return ("pattern", "|".join(f"(?:{sanitize(p)})" for p in pats))
                if rule[0] == "number":
                    _, integer, lo, lox, hi, hix = rule
                    for f in c:
                        ft = tag(f)
                        if ft in ("minInclusive", "minExclusive", "maxInclusive", "maxExclusive"):
                            v = float(f.get("value"))
                            if integer and v == int(v):
                                v = int(v)
                            if ft == "minInclusive":
                                lo, lox = v, False
                            elif ft == "minExclusive":
                                lo, lox = v, True
                            elif ft == "maxInclusive":
                                hi, hix = v, False
                            else:
                                hi, hix = v, True
                    return ("number", integer, lo, lox, hi, hix)
                return rule
        return ("any",)

    def attr_rule(self, a):
        return self.simple_rule(a)

    def text_rule(self, el):
        """Rule for the text content of a simple-content element, ("any",) when unconstrained."""
        ct = el if tag(el) == "complexType" else el.find(XS + "complexType")
        if ct is not None:
            for sc in ct.findall(XS + "simpleContent"):
                for c in sc:
                    if tag(c) in ("extension", "restriction") and c.get("base"):
                        return self.type_rule(c.get("base"))
        t = el.get("type")
        if t and (t.startswith("xs:") or t in self.g["simpleType"]):
            return self.type_rule(t)
        return ("any",)

    # ---- content models ----------------------------------------------------------------------
    #
    # ("seq"|"choice"|"all", min, max, [items]) or ("el", node, min, max); max None = unbounded.

    def occurs(self, n):
        mx = n.get("maxOccurs", "1")
        return int(n.get("minOccurs", "1")), (None if mx == "unbounded" else int(mx))

    def model_of(self, n, seen=frozenset()):
        t = tag(n)
        mn, mx = self.occurs(n)
        if t in ("sequence", "choice", "all"):
            items = [m for m in (self.model_of(c, seen) for c in n) if m is not None]
            return (t, mn, mx, items)
        if t == "group" and n.get("ref"):
            g = self.g["group"].get(n.get("ref"))
            if g is None or ("g", n.get("ref")) in seen:
                return None
            inner = [
                m
                for m in (self.model_of(c, seen | {("g", n.get("ref"))}) for c in g)
                if m is not None
            ]
            return ("seq", mn, mx, inner)
        if t == "element":
            if n.get("name"):
                return ("el", n, mn, mx)
            ref = self.g["element"].get(n.get("ref"))
            if ref is None:
                return None
            members = self.concrete(ref)
            if len(members) == 1 and members[0] is ref:
                return ("el", ref, mn, mx)
            return ("choice", mn, mx, [("el", m, 1, 1) for m in members])
        if t == "any":
            return ("any", mn, mx)
        return None

    def content_model(self, el, seen=frozenset()):
        """Model of the element children of an element/complexType node."""
        parts = []
        t = el.get("type")
        if tag(el) == "element" and t and t in self.g["complexType"] and ("ct", t) not in seen:
            m = self.content_model(self.g["complexType"][t], seen | {("ct", t)})
            if m:
                parts.append(m)
        for c in el:
            ct = tag(c)
            if ct in ("sequence", "choice", "all", "group"):
                m = self.model_of(c, seen)
                if m:
                    parts.append(m)
            elif ct == "complexType":
                m = self.content_model(c, seen)
                if m:
                    parts.append(m)
            elif ct in ("complexContent", "extension", "restriction"):
                base = c.get("base")
                if ct == "extension" and base in self.g["complexType"] and ("ct", base) not in seen:
                    m = self.content_model(self.g["complexType"][base], seen | {("ct", base)})
                    if m:
                        parts.append(m)
                m = self.content_model(c, seen)
                if m:
                    parts.append(m)
        if not parts:
            return None
        return parts[0] if len(parts) == 1 else ("seq", 1, 1, parts)

    @staticmethod
    def _mul(a, b):
        if a == 0 or b == 0:
            return 0
        return None if a is None or b is None else a * b

    @staticmethod
    def _add(a, b):
        return None if a is None or b is None else a + b

    @staticmethod
    def _max(a, b):
        return None if a is None or b is None else max(a, b)

    def leaves(self, m):
        """Every element node under model [m]."""
        if m[0] == "el":
            return [m[1]]
        if m[0] == "any":
            return []
        return [n for i in m[3] for n in self.leaves(i)]

    def kinds(self, m):
        if m[0] == "any":
            return {"any"}
        if m[0] == "el":
            return {"el"}
        return {k for i in m[3] for k in self.kinds(i)} | {m[0]}

    def slots(self, m):
        """Ordered slots of element nodes: a node in an earlier slot must precede one in a later
        slot. Only an `xs:sequence` that occurs once keeps its items in order; everything else
        collapses to one slot (no order within it). None when the model has a
        wildcard (`xs:any`), where no order can be promised."""
        if "any" in self.kinds(m):
            return None
        if m[0] == "el":
            return [list(self.leaves(m))]
        # Only a literal xs:sequence: the "seq" kind (a group reference, or a base type plus its
        # extension) is not ordered in practice - Google's own samples put `Transform` after the
        # shape in a `PartDraw`, which extends an `all` base with an `all`.
        if m[0] == "sequence" and m[2] == 1:
            out = []
            for i in m[3]:
                out += self.slots(i)
            return out
        return [self.leaves(m)]

    def bounds(self, m):
        """(total min, total max, {element node id: (min, max)}) of children under model [m]."""
        k = m[0]
        if k == "el":
            _, node, mn, mx = m
            return mn, mx, {id(node): (mn, mx)}
        if k == "any":
            return 0, None, {}
        _, mn, mx, items = m
        subs = [self.bounds(i) for i in items]
        if k in ("seq", "all"):
            tmin, tmax = 0, 0
            per = {}
            for a, b, d in subs:
                tmin += a
                tmax = self._add(tmax, b)
                for key, (lo, hi) in d.items():
                    plo, phi = per.get(key, (0, 0))
                    per[key] = (plo + lo, self._add(phi, hi))
        else:
            tmin = min((a for a, _, _ in subs), default=0)
            tmax = 0
            for _, b, _ in subs:
                tmax = self._max(tmax, b)
            per = {}
            keys = {key for _, _, d in subs for key in d}
            for key in keys:
                los = [d[key][0] if key in d else 0 for _, _, d in subs]
                his = [d[key][1] for _, _, d in subs if key in d]
                hi = 0
                for h in his:
                    hi = self._max(hi, h)
                per[key] = (min(los), hi)
        return (
            tmin * mn,
            self._mul(tmax, mx),
            {key: (lo * mn, self._mul(hi, mx)) for key, (lo, hi) in per.items()},
        )

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
                    attrs[a.get("name")] = (k, e, c.get("use") == "required", self.attr_rule(a))
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

    def record_order(self, ranks, model, cids):
        """Fold the slot index of each child (by id) of [model] into [ranks]; a child with two
        slots (or two different ranks across definitions sharing an id) gets None = unordered."""
        slots = self.slots(model)
        if slots is None:
            for cid in cids.values():
                ranks[cid] = None
            return
        here = {}
        for idx, nodes in enumerate(slots):
            for n in nodes:
                cid = cids.get(id(n))
                if cid is None:
                    continue
                here[cid] = idx if cid not in here or here[cid] == idx else None
        for cid, r in here.items():
            if cid in ranks and ranks[cid] != r:
                ranks[cid] = None
            else:
                ranks[cid] = r

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
            entry = out.setdefault(nid, [name, {}, [], False, None, {}, ('any',), {}])
            if self.text_rule(node) != ('any',):
                entry[6] = self.text_rule(node)
            entry[1].update(attrs)
            entry[3] |= text
            cids = {}
            for k in kids:
                cid = visit(k, nid)
                cids[id(k)] = cid
                if cid not in entry[2]:
                    entry[2].append(cid)
            model = self.content_model(node)
            if model is not None:
                tmin, tmax, per = self.bounds(model)
                prev = entry[4]
                entry[4] = (tmin, tmax) if prev is None else (min(prev[0], tmin), self._max(prev[1], tmax))
                merged = {}
                for key, (lo, hi) in per.items():
                    cid = cids.get(key)
                    if cid is None:
                        continue
                    plo, phi = merged.get(cid, (lo, hi))
                    merged[cid] = (min(plo, lo), self._max(phi, hi))
                for cid, (lo, hi) in merged.items():
                    plo, phi = entry[5].get(cid, (lo, hi))
                    entry[5][cid] = (min(plo, lo), self._max(phi, hi))
                self.record_order(entry[7], model, cids)
            return nid

        visit(self.g["element"]["WatchFace"], None)
        return out

    def sources_and_functions(self):
        """({source name: True}, {name/arity: True}) the version's XSDs enumerate."""
        sources, funcs = set(), set()
        for r in self.roots:
            for st in r.iter(XS + "simpleType"):
                nm = st.get("name") or ""
                if nm.endswith("SourceType") or nm.endswith("SourceEnums") or nm.endswith("SourcePatterns"):
                    for e in st.iter(XS + "enumeration"):
                        sources.add(e.get("value"))
                    for e in st.iter(XS + "pattern"):
                        sources.add(re.sub(r"\\\.\\d\+", ".{index}", e.get("value")).replace("\\.", "."))
                if nm == "_functionType":
                    for e in st.iter(XS + "enumeration"):
                        m = re.match(r"(\w+)\((.*)\)$", e.get("value"))
                        if m:
                            args = m.group(2)
                            funcs.add(f"{m.group(1)}/{args.count(',') + 1 if args or ',' in args else 1}")
        ok = re.compile(r"[A-Z][A-Z0-9_]*(\.[A-Za-z0-9_{}]+)*$")
        return {x for x in sources if ok.match(x) and not x.startswith("WEATHER.WEATHER.")}, funcs

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


def kdouble(v):
    return "null" if v is None else f"{float(v)!r}"


def flat_enums(rule, out=None):
    out = [] if out is None else out
    if rule[0] == "enum":
        out += rule[1]
    elif rule[0] == "union":
        for m in rule[1]:
            flat_enums(m, out)
    elif rule[0] == "list":
        flat_enums(rule[1], out)
    return out


def render_rule(rule, since):
    """Kotlin source for a rule; [since] maps enum values to the first version that has them."""
    k = rule[0]
    if k == "enum":
        late = {v: since[v] for v in rule[1] if v in since}
        extra = ""
        if late:
            extra = ", since = mapOf(" + ", ".join(f"{kt(v)} to {n}" for v, n in late.items()) + ")"
        return "WffValueRule.Enumeration(listOf(" + ", ".join(kt(v) for v in rule[1]) + ")" + extra + ")"
    if k == "pattern":
        return f"WffValueRule.Pattern({kt(rule[1])})"
    if k == "number":
        _, integer, lo, lox, hi, hix = rule
        return (
            f"WffValueRule.Number(integer = {str(integer).lower()}, min = {kdouble(lo)}, "
            f"minExclusive = {str(lox).lower()}, max = {kdouble(hi)}, maxExclusive = {str(hix).lower()})"
        )
    if k == "expression":
        return "WffValueRule.Expression"
    if k == "union":
        return "WffValueRule.Union(listOf(" + ", ".join(render_rule(m, since) for m in rule[1]) + "))"
    if k == "list":
        return f"WffValueRule.ListOf({render_rule(rule[1], since)})"
    return "WffValueRule.Anything"


REGISTRY = "tools/dwf-validator/src/commonMain/kotlin/com/google/wear/watchface/validator/expression/VersionRegistry.kt"


def registry_versions(repo):
    """Google's own dwf-validator table: {sources: {name: v}, functions: {name/arity: v}}."""
    path = os.path.join(repo, REGISTRY)
    if not os.path.exists(path):
        return {}, {}
    text = open(path).read()
    out = {"FUNCTIONS": {}, "SOURCES": {}}
    for block in out:
        m = re.search(block + r"\s*(?::[^=]+)?=\s*mapOf\((.*?)\n        \)", text, re.S)
        for name, v in re.findall(r'"([^"]+)"\s+to\s+VersionRange\((\d+)', m.group(1) if m else ""):
            if block == "FUNCTIONS":
                name = re.sub(r"\{(\d+)\}", r"/\1", name)
            out[block][name] = int(v)
    return out["SOURCES"], out["FUNCTIONS"]


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

    # Data sources and functions: the XSDs' first version, overridden by Google's own validator
    # table where it has an entry (older XSDs misname some weather sources).
    reg_sources, reg_funcs = registry_versions(repo)
    sources, funcs = {}, {}
    for v in versions:
        s, f = loaded[v].sources_and_functions()
        for n in s:
            sources.setdefault(n, v)
        for n in f:
            funcs.setdefault(n, v)
    sources.update(reg_sources)
    funcs.update(reg_funcs)

    w = sys.stdout.write
    w("// GENERATED by tools/wff-xsd/generate_schema.py from github.com/google/watchface\n")
    w(f"// at {rev}, third_party/wff/specification/documents/{{{','.join(map(str, versions))}}}.\n")
    w("// The XSDs are Copyright Samsung Electronics, Apache License 2.0. Do not edit by hand.\n\n")
    w("package ee.schimke.watchface.wff\n\n")
    w("import ee.schimke.watchface.wff.WffAttributeType.*\n\n")
    w(f"internal val GENERATED_LATEST_VERSION: Int = {latest}\n\n")

    # Data sources. `{index}` stands for a numeric path segment (WEATHER.HOURS.{index}.TEMPERATURE).
    w("/** Expression data sources (`[NAME]`) and the first format version that has each. */\n")
    w("internal val GENERATED_DATA_SOURCES: Map<String, Int> =\n  mapOf(\n")
    for n in sorted(sources):
        w(f"    {kt(n)} to {sources[n]},\n")
    w("  )\n\n")
    w("/** Expression functions as `name/arity` and the first format version that has each. */\n")
    w("internal val GENERATED_FUNCTIONS: Map<String, Int> =\n  mapOf(\n")
    for n in sorted(funcs):
        w(f"    {kt(n)} to {funcs[n]},\n")
    w("  )\n\n")

    # Child order (xs:sequence): per element, each child's slot index in the latest version. A
    # child is dropped (unordered) when the latest version has it unordered, or when an older
    # version orders any pair of the surviving children differently, so the table only holds
    # order every version agrees on.
    child_order = {}
    for nid in graphs[latest]:
        ranks = {c: r for c, r in graphs[latest][nid][7].items() if r is not None}
        for v in versions:
            if v == latest or nid not in graphs[v]:
                continue
            old = graphs[v][nid][7]
            for a in list(ranks):
                for b in list(ranks):
                    if a in ranks and b in ranks and ranks[a] < ranks[b]:
                        if a in old and b in old and (old[a] is None or old[b] is None or old[a] >= old[b]):
                            ranks.pop(a, None)
                            ranks.pop(b, None)
        if len(set(ranks.values())) > 1:
            dense = {r: i for i, r in enumerate(sorted(set(ranks.values())))}
            child_order[nid] = {c: dense[r] for c, r in ranks.items()}
    w("/** Per element, the position of each child its XSD orders (`xs:sequence`); equal = any order. */\n")
    w("internal val GENERATED_CHILD_ORDER: Map<String, Map<String, Int>> =\n  mapOf(\n")
    for nid in sorted(child_order):
        items = ", ".join(f"{kt(c)} to {r}" for c, r in sorted(child_order[nid].items(), key=lambda x: (x[1], x[0])))
        w(f"    {kt(nid)} to mapOf({items}),\n")
    w("  )\n\n")

    # Attribute value rules, shared between attributes that have the same type.
    order = list(graphs[latest].keys())
    rules = {}  # rendered text -> name

    def attr_rule_ref(nid, an, asince):
        rule = graphs[latest][nid][1][an][3]
        if rule == ("any",):
            return None
        enum_since = {}
        loose_from = None  # first version in which the attribute took any text
        for v in versions:
            if nid in graphs[v] and an in graphs[v][nid][1]:
                values = flat_enums(graphs[v][nid][1][an][3])
                if not values and loose_from is None:
                    loose_from = v
                for val in values:
                    enum_since.setdefault(val, v)
        since = {}
        for val, n in enum_since.items():
            n = min(n, loose_from or n)
            if n > asince:
                since[val] = n
        text = render_rule(rule, since)
        if text == "WffValueRule.Anything":
            return None
        return rules.setdefault(text, f"RULE_{len(rules) + 1}")

    body = []
    for nid in order:
        name, attrs, kids, text, total, per, trule, _ = graphs[latest][nid]
        since = first(lambda v: nid in graphs[v])
        body.append(f"    WffElementSpec(\n      id = {kt(nid)},\n      name = {kt(name)},\n")
        body.append(f"      sinceVersion = WffVersion.V{since},\n")
        body.append("      attributes =\n        listOf(\n")
        for an in sorted(attrs):
            k, enums, req, _ = attrs[an]
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
            ref = attr_rule_ref(nid, an, asince)
            if ref:
                args.append(f"rule = {ref}")
            body.append(f"          WffAttributeSpec({', '.join(args)}),\n")
        body.append("        ),\n      children =\n        listOf(\n")
        # Occurrence bounds across every version the child exists in (the loosest wins).
        for cid in kids:
            esince = first(lambda v: nid in graphs[v] and cid in graphs[v][nid][2])
            los, his = [], []
            for v in versions:
                if nid in graphs[v] and cid in graphs[v][nid][2]:
                    lo, hi = graphs[v][nid][5].get(cid, (0, None))
                    los.append(lo)
                    his.append(hi)
            lo = min(los)
            hi = None if None in his else max(his)
            extra = ""
            if lo > 0:
                extra += f", minOccurs = {lo}"
            if hi is not None:
                extra += f", maxOccurs = {hi}"
            body.append(f"          WffChildSpec({kt(cid)}, WffVersion.V{esince}{extra}),\n")
        body.append("        ),\n")
        if kids:
            tmins, tmaxs = [], []
            for v in versions:
                if nid in graphs[v] and graphs[v][nid][4] is not None:
                    tmins.append(graphs[v][nid][4][0])
                    tmaxs.append(graphs[v][nid][4][1])
            if tmins:
                tmin = min(tmins)
                tmax = None if None in tmaxs else max(tmaxs)
                if tmin > 0:
                    body.append(f"      minChildren = {tmin},\n")
                if tmax is not None:
                    body.append(f"      maxChildren = {tmax},\n")
        if text:
            body.append("      allowsText = true,\n")
        if trule != ("any",):
            tref = rules.setdefault(render_rule(trule, {}), f"RULE_{len(rules) + 1}")
            body.append(f"      textRule = {tref},\n")
        body.append("    ),\n")

    for text, rname in rules.items():
        w(f"private val {rname}: WffValueRule = {text}\n\n")
    w("internal val GENERATED_SCHEMA: List<WffElementSpec> =\n  listOf(\n")
    w("".join(body))
    w("  )\n")


if __name__ == "__main__":
    main(sys.argv[1])
