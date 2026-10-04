#!/usr/bin/env python3
"""Render docs/PRESENTATION.md into a real Word .docx.

Why this exists
---------------
The presentation pack is submitted as a Word document, and it was previously produced by hand.
A hand-built artifact rots: the Markdown was corrected several times and the .docx kept
describing an older, smaller, fake-speech version of the project. Anything that has to be
regenerated more than once belongs in a script.

No third-party library. WordprocessingML is a handful of XML elements inside a ZIP, and
`zipfile` is in the standard library. Adding `python-docx` would mean adding a dependency to a
project whose whole point is that a reviewer can read everything it depends on.

Usage
-----
    python scripts/make_docx.py                     # docs/PRESENTATION.md -> docs/PRESENTATION.docx
    python scripts/make_docx.py in.md out.docx

What it supports
----------------
Headings (`#`..`####`), paragraphs, unordered and ordered lists, fenced code blocks (kept
verbatim, monospace, shaded -- the DFD/CFD diagrams are ASCII art and must not be reflowed),
pipe tables with a shaded header row, `---` rules, and inline `**bold**` plus `` `code` ``.
Anything it does not recognise is emitted as a plain paragraph rather than dropped, so an
unsupported construct shows up in the output instead of silently vanishing.
"""

from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SRC = ROOT / "docs" / "PRESENTATION.md"
DEFAULT_OUT = ROOT / "docs" / "PRESENTATION.docx"

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

# Word's own built-in style ids, so Word does not have to resolve anything.
STYLE_NORMAL = "Normal"
STYLE_H1 = "Heading1"
STYLE_H2 = "Heading2"
STYLE_H3 = "Heading3"
STYLE_H4 = "Heading4"
STYLE_TITLE = "Title"
STYLE_CODE = "CodeBlock"
STYLE_CAPTION = "Caption"

INLINE = re.compile(r"(\*\*.+?\*\*|`[^`]+`)")


# --------------------------------------------------------------------------------------
# XML helpers
# --------------------------------------------------------------------------------------
def run(text: str, *, bold: bool = False, italic: bool = False, mono: bool = False,
        size: int | None = None, colour: str | None = None) -> str:
    """One <w:r>. Whitespace is preserved because the code blocks depend on it."""
    props = []
    if mono:
        props.append('<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/>')
    if bold:
        props.append("<w:b/>")
    if italic:
        props.append("<w:i/>")
    if colour:
        props.append('<w:color w:val="%s"/>' % colour)
    if size:
        props.append('<w:sz w:val="%d"/><w:szCs w:val="%d"/>' % (size, size))

    rpr = "<w:rPr>%s</w:rPr>" % "".join(props) if props else ""
    # xml:space="preserve" is not optional here; without it Word collapses runs of spaces and
    # the ASCII diagrams come out mangled.
    return '<w:r>%s<w:t xml:space="preserve">%s</w:t></w:r>' % (rpr, escape(text))


def para(children: str, *, style: str | None = None, shade: str | None = None,
         spacing_before: int = 0, spacing_after: int = 120, keep_next: bool = False,
         indent: int = 0) -> str:
    props = []
    if style:
        props.append('<w:pStyle w:val="%s"/>' % style)
    if keep_next:
        props.append("<w:keepNext/>")
    if shade:
        props.append('<w:shd w:val="clear" w:color="auto" w:fill="%s"/>' % shade)
    if indent:
        props.append('<w:ind w:left="%d"/>' % indent)
    props.append('<w:spacing w:before="%d" w:after="%d"/>' % (spacing_before, spacing_after))
    ppr = "<w:pPr>%s</w:pPr>" % "".join(props)
    return "<w:p>%s%s</w:p>" % (ppr, children)


def inline(text: str, *, size: int | None = None) -> str:
    """Parse `**bold**` and `` `code` `` into runs."""
    out = []
    for piece in INLINE.split(text):
        if not piece:
            continue
        if piece.startswith("**") and piece.endswith("**") and len(piece) > 4:
            out.append(run(piece[2:-2], bold=True, size=size))
        elif piece.startswith("`") and piece.endswith("`") and len(piece) > 2:
            out.append(run(piece[1:-1], mono=True, size=(size or 20) - 2))
        else:
            out.append(run(piece, size=size))
    return "".join(out) or run("")


# --------------------------------------------------------------------------------------
# Block-level conversion
# --------------------------------------------------------------------------------------
def table(rows: list[list[str]]) -> str:
    """A pipe table. The first row is the header, shaded and bold."""
    grid_cols = max(len(r) for r in rows)
    width = 9360 // grid_cols  # twips: 9360 = 6.5in of usable width on Letter with 1in margins

    borders = "".join(
        '<w:%s w:val="single" w:sz="4" w:space="0" w:color="9AA0A6"/>' % edge
        for edge in ("top", "left", "bottom", "right", "insideH", "insideV")
    )
    xml = [
        "<w:tbl><w:tblPr>",
        '<w:tblW w:w="0" w:type="auto"/>',
        "<w:tblBorders>%s</w:tblBorders>" % borders,
        '<w:tblCellMar><w:top w:w="60" w:type="dxa"/><w:left w:w="90" w:type="dxa"/>'
        '<w:bottom w:w="60" w:type="dxa"/><w:right w:w="90" w:type="dxa"/></w:tblCellMar>',
        "</w:tblPr>",
        "<w:tblGrid>%s</w:tblGrid>"
        % "".join('<w:gridCol w:w="%d"/>' % width for _ in range(grid_cols)),
    ]

    for r, cells in enumerate(rows):
        header = r == 0
        trpr = "<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>" if header else "<w:trPr/>"
        xml.append("<w:tr>%s" % trpr)
        for c in range(grid_cols):
            text = cells[c] if c < len(cells) else ""
            shade = '<w:shd w:val="clear" w:color="auto" w:fill="E8EAED"/>' if header else ""
            body = inline(text, size=18) if text else run("")
            xml.append(
                '<w:tc><w:tcPr><w:tcW w:w="%d" w:type="dxa"/>%s</w:tcPr>%s</w:tc>'
                % (width, shade, para(body, spacing_after=40))
            )
        xml.append("</w:tr>")
    xml.append("</w:tbl>")
    # Word needs a paragraph after a table or consecutive tables merge.
    xml.append(para(run(""), spacing_after=120))
    return "".join(xml)


def convert(markdown: str) -> str:
    lines = markdown.replace("\r\n", "\n").split("\n")
    body: list[str] = []
    i = 0

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        # Fenced code block -- emitted verbatim, one paragraph per line.
        if stripped.startswith("```"):
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                body.append(
                    para(
                        run(lines[i], mono=True, size=15) if lines[i] else run(""),
                        style=STYLE_CODE,
                        shade="F1F3F4",
                        spacing_after=0,
                    )
                )
                i += 1
            i += 1  # closing fence
            body.append(para(run(""), spacing_after=120))
            continue

        # Table
        if stripped.startswith("|") and i + 1 < len(lines) and re.match(
            r"^\s*\|[\s:\-|]+\|\s*$", lines[i + 1]
        ):
            rows = []
            rows.append([c.strip() for c in stripped.strip("|").split("|")])
            i += 2  # header + separator
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            body.append(table(rows))
            continue

        # Horizontal rule
        if re.match(r"^-{3,}$", stripped):
            body.append(
                '<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" '
                'w:color="DADCE0"/></w:pBdr><w:spacing w:after="160"/></w:pPr></w:p>'
            )
            i += 1
            continue

        # Heading
        m = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if m:
            level = len(m.group(1))
            style = [STYLE_TITLE, STYLE_H1, STYLE_H2, STYLE_H3, STYLE_H4][level]
            body.append(para(inline(m.group(2)), style=style, keep_next=True))
            i += 1
            continue

        # Ordered list
        m = re.match(r"^(\d+)\.\s+(.*)$", stripped)
        if m:
            body.append(para(inline(m.group(2)), indent=360, spacing_after=60))
            i += 1
            continue

        # Bullet list, including the "- [ ]" checkbox form used in the limitations lists.
        m = re.match(r"^[-*]\s+\[([ xX])\]\s+(.*)$", stripped)
        if m:
            mark = "☒" if m.group(1).lower() == "x" else "☐"
            body.append(
                para(run(mark + "  ", mono=True) + inline(m.group(2)), indent=360, spacing_after=60)
            )
            i += 1
            continue

        if stripped.startswith(("- ", "* ")):
            body.append(para(inline(stripped[2:]), indent=360, spacing_after=60))
            i += 1
            continue

        # Blank line
        if not stripped:
            i += 1
            continue

        # Paragraph: gather the run of non-blank, non-block lines.
        chunk = [stripped]
        i += 1
        while i < len(lines):
            nxt = lines[i].strip()
            if (not nxt or nxt.startswith(("#", "|", "```", "- ", "* ", ">"))
                    or re.match(r"^-{3,}$", nxt) or re.match(r"^\d+\.\s", nxt)):
                break
            chunk.append(nxt)
            i += 1
        body.append(para(inline(" ".join(chunk))))

    return "".join(body)


# --------------------------------------------------------------------------------------
# Package assembly
# --------------------------------------------------------------------------------------
CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>"""

ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

CORE = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>iTantra Message - Project Presentation Pack</dc:title>
<dc:subject>Offline peer-to-peer Bluetooth messaging</dc:subject>
<dc:creator>iTantra Message contributors</dc:creator>
<cp:lastModifiedBy>iTantra Message contributors</cp:lastModifiedBy>
<cp:keywords>iTantra Message; Bluetooth RFCOMM; AES-256-GCM; sherpa-onnx; Whisper</cp:keywords>
<dcterms:created xsi:type="dcterms:W3CDTF">2026-01-01T00:00:00Z</dcterms:created>
<dcterms:modified xsi:type="dcterms:W3CDTF">2026-01-01T00:00:00Z</dcterms:modified>
</cp:coreProperties>"""

APP = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
<Application>scripts/make_docx.py</Application>
<Company>iTantra Message</Company>
</Properties>"""


def style(sid: str, name: str, *, size: int, bold: bool = False, before: int = 0,
          after: int = 120, outline: int | None = None, colour: str | None = None,
          mono: bool = False) -> str:
    props = ['<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>' if mono
             else '<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/>']
    if bold:
        props.append("<w:b/>")
    if colour:
        props.append('<w:color w:val="%s"/>' % colour)
    if outline is not None:
        props.append('<w:outlineLvl w:val="%d"/>' % outline)
    props.append('<w:sz w:val="%d"/><w:szCs w:val="%d"/>' % (size, size))
    return (
        '<w:style w:type="paragraph" w:styleId="%s"><w:name w:val="%s"/>'
        "<w:qFormat/><w:pPr><w:spacing w:before=\"%d\" w:after=\"%d\"/></w:pPr>"
        "<w:rPr>%s</w:rPr></w:style>" % (sid, name, before, after, "".join(props))
    )


STYLES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<w:styles xmlns:w="%s">'
    "<w:docDefaults><w:rPrDefault><w:rPr>"
    '<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/>'
    "</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
    '<w:spacing w:after="120" w:line="259" w:lineRule="auto"/>'
    "</w:pPr></w:pPrDefault></w:docDefaults>"
    % W
    + style(STYLE_NORMAL, "Normal", size=22)
    + style(STYLE_TITLE, "Title", size=40, bold=True, before=0, after=200, colour="0B3D2E")
    + style(STYLE_H1, "heading 1", size=32, bold=True, before=320, after=140, outline=0,
            colour="0B3D2E")
    + style(STYLE_H2, "heading 2", size=26, bold=True, before=260, after=120, outline=1)
    + style(STYLE_H3, "heading 3", size=23, bold=True, before=200, after=100, outline=2)
    + style(STYLE_H4, "heading 4", size=22, bold=True, before=160, after=80, outline=3)
    + style(STYLE_CODE, "Code Block", size=15, after=0, mono=True)
    + style(STYLE_CAPTION, "caption", size=18)
    + "</w:styles>"
)

SECTION = (
    '<w:sectPr><w:pgSz w:w="12240" w:h="15840"/>'
    '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" '
    'w:header="720" w:footer="720" w:gutter="0"/></w:sectPr>'
)


def build(src: Path, out: Path) -> int:
    markdown = src.read_text(encoding="utf-8")
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="%s"><w:body>%s%s</w:body></w:document>'
        % (W, convert(markdown), SECTION)
    )

    out.parent.mkdir(parents=True, exist_ok=True)
    # Fixed timestamp so regenerating an unchanged source produces an identical file; a .docx
    # that churns on every run makes `git status` useless.
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, payload in (
            ("[Content_Types].xml", CONTENT_TYPES),
            ("_rels/.rels", ROOT_RELS),
            ("docProps/core.xml", CORE),
            ("docProps/app.xml", APP),
            ("word/document.xml", document),
            ("word/styles.xml", STYLES),
            ("word/_rels/document.xml.rels", DOC_RELS),
        ):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o600 << 16
            z.writestr(info, payload)

    return len(document)


def main(argv: list[str]) -> int:
    src = Path(argv[1]) if len(argv) > 1 else DEFAULT_SRC
    out = Path(argv[2]) if len(argv) > 2 else DEFAULT_OUT
    if not src.is_file():
        print("missing source: %s" % src, file=sys.stderr)
        return 2

    size = build(src, out)
    print("wrote %s (%d bytes, document.xml %d chars)" % (out, out.stat().st_size, size))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))