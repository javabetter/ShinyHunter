"""Generates the MoulConfig mirror class from ConfigScreen's own row declarations.

Run it again whenever a setting is added to ConfigScreen; the generated file is committed so the
build needs no code generation step.
"""
import io, re, os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "java", "com", "shinyhunter")
OUT = os.path.join(SRC, "gui", "moul", "ShinyMoulConfig.java")

src = io.open(os.path.join(SRC, "gui", "ConfigScreen.java"), encoding="utf-8").read()
body = src[src.index("private void buildRows()"):src.index("    private void buildBlockRows(")]

# Rows and sections in declaration order, with the Edition.PUBLIC blocks tracked by brace depth.
TOKEN = re.compile(
    r'(?P<public_guard>if \(!Edition\.PUBLIC\) \{)'
    r'|(?P<open>\{)|(?P<close>\})'
    r'|section\("(?P<section>[^"]*)"\)'
    r'|group\("(?P<group>[^"]*)"\)'
    r'|(?P<kind>bool|text|number|decimal)\('
    r'\s*"(?P<label>(?:[^"\\]|\\.)*)",\s*"(?P<tip>(?:[^"\\]|\\.)*)",'
    r'\s*\(\)\s*->\s*c\.(?P<field>\w+),\s*v\s*->\s*c\.\w+\s*=\s*v'
    r'(?:,\s*(?P<min>-?[\d.]+),\s*(?P<max>-?[\d.]+))?\s*\)'
    r'|(?P<listcall>build(?:Keyword|Tracker|Block|CritterHud|StringList)\w*Rows?\()'
)

rows = []          # (section, kind, label, tip, field, min, max, private_only, group)
sections = []      # section names in order
section = None
group = None       # the group("...") heading rows are currently under, within the section
entries = {}       # section -> ordered ("row", row) / ("list", name, group, private)
private_groups = set()  # (section, group) declared inside a private-only guard
depth = 0
guard_depths = []  # brace depths at which an Edition.PUBLIC guard opened
private_sections = set()  # sections declared inside a private-only guard

for m in TOKEN.finditer(body):
    if m.group("public_guard"):
        depth += 1
        guard_depths.append(depth)
        continue
    if m.group("open"):
        depth += 1
        continue
    if m.group("close"):
        if guard_depths and guard_depths[-1] == depth:
            guard_depths.pop()
        depth -= 1
        continue
    if m.group("section") is not None:
        section = m.group("section")
        group = None
        if section not in sections:
            sections.append(section)
        if guard_depths:
            private_sections.add(section)
        entries.setdefault(section, []).append(("heading", section, None, bool(guard_depths)))
        continue
    if m.group("group") is not None:
        group = m.group("group")
        if guard_depths:
            private_groups.add((section, group))
        entries.setdefault(section, []).append(("heading", group, group, bool(guard_depths)))
        continue
    if m.group("kind"):
        row = (section, m.group("kind"), m.group("label"), m.group("tip"),
               m.group("field"), m.group("min"), m.group("max"), bool(guard_depths), group)
        rows.append(row)
        entries.setdefault(section, []).append(("row", row))

# Sections whose real content is a list editor; those get a button into the old screen.
LIST_SECTIONS = {
    "Hunt keywords": "Hunt keywords",
    "Tracked entities": "Tracked entities",
    "Tracked blocks": "Tracked blocks",
    "Sparkling particles": "Sparkling particles",
    "Sparkling textures": "Sparkling textures",
    "Critter list": "Critter list",
    "Snoozling walls": "Snoozling walls",
}

# A heading (section or group) named like a list section gets that list's editor button, in place.
for sec in list(entries):
    resolved = []
    for entry in entries[sec]:
        if entry[0] == "heading":
            if entry[1] in LIST_SECTIONS:
                resolved.append(("list", entry[1], entry[2], entry[3]))
            continue
        resolved.append(entry)
    entries[sec] = resolved
list_names = [e[1] for sec in sections for e in entries.get(sec, []) if e[0] == "list"]

def camel(text):
    parts = re.split(r"[^A-Za-z0-9]+", text)
    return "".join(p[:1].upper() + p[1:] for p in parts if p)

def java_string(text):
    return text  # already escaped exactly as it appears in the Java source

used = set()
by_section = {}
for row in rows:
    by_section.setdefault(row[0], []).append(row)

lines = []
lines.append("""package com.shinyhunter.gui.moul;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.Config;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.common.text.StructuredText;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The settings, as MoulConfig sees them — the same library Firmament draws its config with.
 *
 * <p><b>Generated.</b> Every field here mirrors a field of {@link com.shinyhunter.ShinyConfig} with
 * the same name, and every option's text is the same label and tooltip the mod's own screen uses,
 * because this file is generated from that screen's row declarations
 * ({@code scratchpad/gen_moulconfig.py}). Regenerate it after adding a setting rather than editing
 * it by hand, so the two can't drift apart.
 *
 * <p>Values are copied in and out by {@link MoulConfigBridge} rather than stored here: the mod's
 * own JSON file stays the one source of truth, so nothing else in the mod has to change and an
 * existing config keeps working.
 */
public class ShinyMoulConfig extends Config {

    @Override
    public StructuredText getTitle() {
        return StructuredText.of(com.shinyhunter.Edition.NAME);
    }

    @Override
    public boolean shouldSearchCategoryNames() {
        return true;
    }
""")

# categories
cat_fields = []
for section in sections:
    if not entries.get(section):
        continue
    cls = camel(section) + "Category"
    field = camel(section)
    field = field[:1].lower() + field[1:]
    cat_fields.append((section, cls, field))

for section, cls, field in cat_fields:
    lines.append("    @Expose")
    lines.append('    @Category(name = "%s", desc = "%s")' % (section, section))
    lines.append("    public %s %s = new %s();\n" % (cls, field, cls))

# runnable ids for the list-editor buttons
runnables = []
for name in list_names:
    if name not in runnables:
        runnables.append(name)

lines.append("    /** Which settings only exist in the private build; the public one hides them. */")
private_only = sorted({row[4] for row in rows if row[7]}
                      # a private list's editor button is private too
                      | {"open" + camel(e[1]) for sec in entries for e in entries[sec] if e[0] == "list" and e[3]}
                      # and so is the accordion of a group that only exists in the private build
                      | {"accordion" + camel(sec) + camel(grp) for sec, grp in private_groups})
lines.append("    public static final Set<String> PRIVATE_ONLY = Set.of(")
lines.append("            " + ",\n            ".join('"%s"' % f for f in private_only) + ");\n")

lines.append("    /** Category identifier -> the section name the old screen filters on. */")
lines.append("    public static final Map<Integer, String> LIST_EDITORS = Map.of(")
lines.append(",\n".join('            %d, "%s"' % (i, s) for i, s in enumerate(runnables)) + ");\n")

lines.append("""    @Override
    public boolean isValidRunnable(int runnableId) {
        return LIST_EDITORS.containsKey(runnableId);
    }

    @Override
    public void executeRunnable(int runnableId) {
        String section = LIST_EDITORS.get(runnableId);
        if (section != null) {
            MoulConfigBridge.openListEditor(section);
        }
    }
""")

for section, cls, field in cat_fields:
    lines.append("    public static class %s {\n" % cls)
    accordions = {}
    def accordion_of(grp):
        # Each group heading becomes a collapsible accordion; its options carry its id.
        if grp is None:
            return None
        if grp not in accordions:
            accordions[grp] = len(accordions)
            lines.append("        @Expose")
            lines.append('        @ConfigOption(name = "%s", desc = "")' % grp)
            lines.append("        @ConfigEditorAccordion(id = %d)" % accordions[grp])
            lines.append("        public boolean accordion%s%s = false;\n" % (camel(section), camel(grp)))
        return accordions[grp]
    for entry in entries.get(section, []):
        if entry[0] == "list":
            _, list_name, grp, _private = entry
            acc = accordion_of(grp)
            idx = runnables.index(list_name)
            lines.append("        @Expose")
            if acc is not None:
                lines.append("        @ConfigAccordionId(id = %d)" % acc)
            button = list_name if list_name.lower().endswith("list") else list_name + " list"
            lines.append('        @ConfigOption(name = "%s", desc = "Open the list editor for %s.")'
                         % (button, list_name.lower()))
            lines.append('        @ConfigEditorButton(runnableId = %d, buttonText = "Edit")' % idx)
            lines.append("        public boolean open%s = false;\n" % camel(list_name))
            continue
        (_, kind, label, tip, name, low, high, private_only_row, grp) = entry[1]
        if name in used:
            continue
        used.add(name)
        acc = accordion_of(grp)
        lines.append("        @Expose")
        if acc is not None:
            lines.append("        @ConfigAccordionId(id = %d)" % acc)
        lines.append('        @ConfigOption(name = "%s", desc = "%s")' % (label, tip))
        if kind == "bool":
            lines.append("        @ConfigEditorBoolean")
            lines.append("        public boolean %s;\n" % name)
        elif kind == "text":
            lines.append('        @ConfigEditorText')
            lines.append("        public String %s = \"\";\n" % name)
        elif kind == "number":
            lines.append("        @ConfigEditorSlider(minValue = %sf, maxValue = %sf, minStep = 1f)" % (low, high))
            lines.append("        public int %s;\n" % name)
        else:
            step = "0.1f"
            lines.append("        @ConfigEditorSlider(minValue = %sf, maxValue = %sf, minStep = %s)" % (low, high, step))
            lines.append("        public double %s;\n" % name)
    lines.append("    }\n")

lines.append("}")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
io.open(OUT, "w", encoding="utf-8").write("\n".join(lines))
print("wrote", OUT)
print("categories:", len(cat_fields), "options:", len(used), "private-only:", len(private_only))
